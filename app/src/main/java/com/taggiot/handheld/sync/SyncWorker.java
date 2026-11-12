package com.taggiot.handheld.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.gson.JsonObject;
import com.taggiot.handheld.data.Prefs;
import com.taggiot.handheld.data.Repo;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.data.db.AppDao;
import com.taggiot.handheld.data.db.OutboxEntity;
import com.taggiot.handheld.net.ApiException;
import com.taggiot.handheld.net.Gateway;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.net.Json;
import com.taggiot.handheld.net.dto.Dtos;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Mengirim antrean keluar ke Site Gateway. Idempoten: reads memakai batch_id, bind memakai
 * Idempotency-Key, jadi kiriman ganda (koneksi putus setelah server menerima) aman.
 *
 * Aturan jawaban:
 * - 2xx                      → SENT; untuk reads, tally sesi diperbarui dari jawaban.
 * - 401 unauthenticated      → token kedaluwarsa: sesi ditandai expired, antrean DIBIARKAN,
 *                              operator masuk lagi lalu batch dikirim ulang dengan id yang sama.
 * - 409 count_state, 4xx lain → FAILED (tidak akan berhasil dengan diulang), ditampilkan merah.
 * - 429 / 5xx / jaringan      → tetap PENDING, dicoba lagi (WorkManager backoff).
 * Log tidak pernah memuat EPC, token, atau isi body.
 */
public class SyncWorker extends Worker {
    private static final String TAG = "SyncWorker";
    public static final String UNIQUE_NOW = "sync-now";
    public static final String UNIQUE_PERIODIC = "sync-periodic";

    public SyncWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    public static void runNow(Context ctx) {
        Prefs p = Prefs.get(ctx);
        OneTimeWorkRequest.Builder b = new OneTimeWorkRequest.Builder(SyncWorker.class);
        if (!p.demoMode()) b.setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build());
        WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, b.build());
    }

    public static void schedulePeriodic(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    @NonNull @Override public Result doWork() {
        Context ctx = getApplicationContext();
        Repo repo = Repo.get(ctx);
        AppDao dao = repo.dao();
        Prefs prefs = Prefs.get(ctx);
        Session session = Session.get(ctx);
        List<OutboxEntity> pending = dao.pendingOutbox();
        if (pending.isEmpty()) { repo.purge(); return Result.success(); }
        if (session.token() == null) {
            Log.i(TAG, "antrean menunggu: belum ada sesi operator (" + pending.size() + " batch)");
            return Result.success();   // dikirim setelah login
        }

        Gateway gw = Gateways.get(ctx);
        boolean retryLater = false;
        for (OutboxEntity o : pending) {
            if (OutboxEntity.FAILED.equals(o.status)) continue;   // ditolak permanen, menunggu tindakan operator
            o.attempts++;
            try {
                JsonObject res = gw.postOutbox(o.endpoint, repo.payload(o), o.idempotencyKey);
                o.status = OutboxEntity.SENT; o.sentAt = System.currentTimeMillis(); o.lastError = null;
                o.responseJson = res == null ? null : res.toString();
                dao.updateOutbox(o);
                if (OutboxEntity.KIND_READS.equals(o.kind) && o.countId != null && res != null) {
                    try {
                        Dtos.ReadsResponse rr = Json.gson().fromJson(res, Dtos.ReadsResponse.class);
                        if (rr != null) {
                            repo.updateTally(o.countId, rr.tally);
                            if (rr.replayed) Log.i(TAG, "batch " + o.id.substring(0, 8) + " replayed (sudah diterima sebelumnya)");
                        }
                    } catch (RuntimeException ignored) {}
                }
            } catch (ApiException e) {
                if (e.needsRelogin()) {
                    o.lastError = "Sesi berakhir, masuk lagi";
                    dao.updateOutbox(o);
                    session.expire();
                    Log.w(TAG, "401 unauthenticated: token dibuang, antrean ditahan");
                    return Result.success();   // dilanjutkan setelah operator masuk lagi
                }
                if (e.isPermanent()) {
                    o.status = OutboxEntity.FAILED;
                    o.lastError = e.userMessage() + (e.requestId == null ? "" : " · req " + e.requestId);
                } else {
                    o.lastError = e.userMessage();
                    retryLater = true;
                }
                dao.updateOutbox(o);
                Log.w(TAG, "kirim batch gagal: HTTP " + e.status + " " + e.code);
                if (e.status == 429 || e.isNetwork()) break;   // percuma lanjut sekarang
            } catch (RuntimeException e) {
                o.lastError = e.getClass().getSimpleName();
                dao.updateOutbox(o);
                retryLater = true;
                Log.e(TAG, "kirim batch error", e);
            }
        }
        repo.purge();
        if (!retryLater) prefs.setLastSyncAt(System.currentTimeMillis());
        return retryLater && getRunAttemptCount() < 5 ? Result.retry() : Result.success();
    }
}

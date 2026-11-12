package com.taggiot.handheld.ui.sync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Repo;
import com.taggiot.handheld.data.db.AppDao;
import com.taggiot.handheld.databinding.ActivitySyncBinding;
import com.taggiot.handheld.sync.SyncWorker;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.ui.UiUtil;
import com.taggiot.handheld.util.AppExecutors;

/**
 * Status sinkronisasi: data aman di perangkat, antrean keluar, master data.
 * Bahasa operator: tidak ada istilah "outbox" di layar.
 */
public class SyncActivity extends BaseActivity {

    private ActivitySyncBinding b;
    private final OutboxAdapter adapter = new OutboxAdapter();
    private int pending;
    private boolean running;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySyncBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(true);
        setupTopBar(b.topBar.getRoot(), getString(R.string.sync_title),
                prefs.demoMode() ? "Mode demo · tanpa Site Gateway" : "Site Gateway · " + prefs.siteGatewayUrl(),
                isOnline() ? R.drawable.ic_wifi : R.drawable.ic_wifi_off, v -> renderStatus());

        b.rvOutbox.setLayoutManager(new LinearLayoutManager(this));
        b.rvOutbox.setAdapter(adapter);
        AppDao dao = Repo.get(this).dao();
        dao.outboxLive().observe(this, list -> {
            adapter.submit(list);
            b.tvOutboxEmpty.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
        });
        dao.pendingCountLive().observe(this, n -> { pending = n == null ? 0 : n; renderStatus(); });
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(SyncWorker.UNIQUE_NOW).observe(this, infos -> {
            running = false;
            if (infos != null) for (WorkInfo w : infos) if (w.getState() == WorkInfo.State.RUNNING || w.getState() == WorkInfo.State.ENQUEUED) running = true;
            b.btnSyncNow.setEnabled(!running);
            b.btnSyncNow.setText(running ? "Mengirim…" : getString(R.string.sync_now));
            renderStatus();
        });
        b.btnSyncNow.setOnClickListener(v -> SyncWorker.runNow(this));

        dao.openSessionCountLive().observe(this, n -> {
            b.tvItems.setText(UiUtil.number(n == null ? 0 : n));
            b.tvItemsLabel.setText("sesi hitung terbuka");
        });
        dao.pendingRowsLive().observe(this, n -> {
            b.tvTags.setText(UiUtil.number(n == null ? 0 : n));
            b.tvTagsLabel.setText("EPC menunggu dikirim");
        });
    }

    private void renderStatus() {
        boolean online = isOnline() || prefs.demoMode();
        long last = prefs.lastSyncAt();
        String lastTxt = last == 0 ? "belum pernah" : UiUtil.dayTime(last);
        if (pending == 0) {
            b.boxStatus.setBackgroundResource(R.drawable.bg_status_online);
            b.ivStatus.setImageResource(R.drawable.ic_check);
            ImageViewCompat.setImageTintList(b.ivStatus, ContextCompat.getColorStateList(this, R.color.status_ok));
            b.tvStatusTitle.setText("Semua data terkirim");
            b.tvStatusSub.setText(online ? "Terhubung" : "Offline");
            b.tvStatusNote.setText("Sinkron terakhir " + lastTxt + ".");
        } else {
            b.boxStatus.setBackgroundResource(online ? R.drawable.bg_status_online : R.drawable.bg_status_offline);
            b.ivStatus.setImageResource(R.drawable.ic_cloud_up);
            ImageViewCompat.setImageTintList(b.ivStatus, ContextCompat.getColorStateList(this, online ? R.color.brand_600 : R.color.status_warn));
            b.tvStatusTitle.setText((online ? "" : "Offline · ") + pending + " batch menunggu dikirim");
            b.tvStatusSub.setText("Data aman di perangkat");
            String note = com.taggiot.handheld.data.Session.get(this).token() == null
                    ? "Sesi berakhir: masuk lagi, lalu batch dikirim ulang dengan batch_id yang sama. "
                    : running ? "Sedang mengirim… " : "Terkirim otomatis begitu jaringan tersedia. ";
            b.tvStatusNote.setText(note + "Sinkron terakhir " + lastTxt + ".");
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}

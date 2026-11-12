package com.taggiot.handheld.ui.count;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Repo;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.databinding.ActivityCountSessionBinding;
import com.taggiot.handheld.domain.Epc;
import com.taggiot.handheld.net.ApiException;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.reader.TagRead;
import com.taggiot.handheld.reader.UhfReader;
import com.taggiot.handheld.sync.SyncWorker;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.ui.FinderCache;
import com.taggiot.handheld.ui.find.FindTagActivity;
import com.taggiot.handheld.ui.inventory.TagAdapter;
import com.taggiot.handheld.ui.inventory.TagRow;
import com.taggiot.handheld.util.AppExecutors;
import com.taggiot.handheld.util.Hex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Menghitung satu sesi (drawer check / opname / inventory count):
 * daya penuh → EPC unik disimpan lokal per sesi (count_read) → dibungkus batch ≤ 1.000 dengan batch_id
 * UUID baru tiap 500 EPC atau tiap 5 detik → antrean keluar → SyncWorker → POST /handheld/counts/reads.
 * Batch yang belum terkirim bertahan saat aplikasi ditutup. Area = kode laci GMF (dari sesi), tidak diubah.
 * Operator membaca; supervisor me-review (menghasilkan event count_review untuk GMF) dan menutup.
 */
public class CountSessionActivity extends BaseActivity {

    private static final String EXTRA_ID = "id";
    private static final int TICK_MS = 300;
    private static final long FLUSH_EVERY_MS = 5000;

    private ActivityCountSessionBinding b;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TagAdapter adapter;
    private FinderCache finder;
    private String id;
    private Dtos.CountSession session;
    private String area;
    private final Set<String> seen = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, Integer> rssi = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> fresh = new ConcurrentLinkedQueue<>();
    private volatile boolean dirty;
    private boolean scanning;
    private long lastFlush;
    private int pendingBatches;

    public static Intent intent(Context c, String id) {
        return new Intent(c, CountSessionActivity.class).putExtra(EXTRA_ID, id);
    }

    @Override protected boolean usesReader() { return true; }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.get(this).canCount()) { toast("Peran viewer tidak boleh menghitung."); finish(); return; }
        b = ActivityCountSessionBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(false);
        id = getIntent().getStringExtra(EXTRA_ID);
        setupTopBar(b.topBar.getRoot(), "Hitung", "", R.drawable.ic_more, v -> moreMenu());

        adapter = new TagAdapter(this::onRowClick);
        b.rvTags.setLayoutManager(new LinearLayoutManager(this));
        b.rvTags.setAdapter(adapter);
        b.rvTags.setItemAnimator(null);
        finder = new FinderCache(this, new FinderCache.Listener() {
            @Override public void onResolved() { dirty = true; if (!scanning) renderList(); }
            @Override public void onError(ApiException e) { if (e.needsRelogin()) apiError(e); }
        });
        bindScanToggle(b.btnHoldScan);
        b.btnSend.setOnClickListener(v -> sendNow(true));
        b.btnFinish.setOnClickListener(v -> finishAction());
        b.boxLocation.setOnClickListener(v -> { if (session != null && session.area == null) askArea(); });

        Repo.get(this).dao().pendingBatchesLive(id).observe(this, n -> { pendingBatches = n == null ? 0 : n; renderCounts(); });
        Repo.get(this).dao().sessionLive(id).observe(this, e -> {
            if (e == null) return;
            Dtos.CountSession s = Repo.parse(e);
            if (s != null) { session = s; renderSession(); }
        });

        AppExecutors.dbThenMain(() -> Repo.get(this).readEpcs(id), list -> {
            if (list != null) seen.addAll(list);
            finder.request(new ArrayList<>(seen));
            renderList();
        });
        reload();
    }

    /** Ambil status & tally terbaru dari server (counts/get); gagal jaringan → pakai salinan lokal. */
    private void reload() {
        AppExecutors.netThenMain(() -> {
            Dtos.CountSession s = Gateways.get(this).countGet(id);
            Dtos.CountSession old = Repo.get(this).cachedSession(id);
            if (s.area == null && old != null) s.area = old.area;
            Repo.get(this).cacheSession(s);
            return s;
        }, (s, err) -> {
            if (err == null) return;
            if (err instanceof ApiException && ((ApiException) err).isNetwork()) {
                if (session == null) toast("Offline: memakai data sesi tersimpan");
                return;
            }
            toast(apiError(err));
        });
    }

    // ---------- tampilan ----------

    private void renderSession() {
        Dtos.CountSession s = session;
        String a = CountUi.area(s);
        if (a != null) area = a;
        else if (area == null && "inventory".equals(s.kind) && !prefs.lastArea().isEmpty()) area = prefs.lastArea();
        setupTopBar(b.topBar.getRoot(), s.number, CountUi.kindLabel(s) + " · " + CountUi.creatorLabel(s.createdBy), R.drawable.ic_more, v -> moreMenu());
        b.tvLocation.setText(area == null ? "Area belum diisi (ketuk untuk mengisi)" : ("opname".equals(s.kind) ? "Laci " : "Area ") + area);
        b.tvLocationSub.setText(statusText(s.status));
        boolean open = "open".equals(s.status);
        b.btnHoldScan.setEnabled(open);
        if (!open && scanning) onTriggerUp();
        if (!scanning) b.tvLive.setText(open ? getString(R.string.ready) : statusText(s.status));
        Session me = Session.get(this);
        if (me.isSupervisor()) b.btnFinish.setText(open ? "Review" : "Lihat hasil");
        else if ("inventory".equals(s.kind) && CountUi.isCreator(s, me.opNo())) b.btnFinish.setText("Tutup sesi");
        else b.btnFinish.setText(R.string.count_finish);
        renderCounts();
    }

    private static String statusText(String st) {
        switch (st == null ? "" : st) {
            case "open": return "Terbuka · boleh dihitung";
            case "review": return "Review supervisor · hitung berhenti";
            case "closed": return "Ditutup";
            case "cancelled": return "Dibatalkan";
            default: return st;
        }
    }

    private void renderCounts() {
        if (b == null) return;
        int expected = session == null ? 0 : CountUi.expected(session.tally);
        b.tvExpected.setText(expected > 0 ? String.valueOf(expected) : "–");
        b.tvRead.setText(String.valueOf(seen.size()));
        b.tvServer.setText(session == null ? "0" : String.valueOf(CountUi.distinct(session.tally)));
        StringBuilder sb = new StringBuilder();
        if (pendingBatches > 0) sb.append(pendingBatches).append(" batch menunggu dikirim (aman di perangkat). ");
        else if (!seen.isEmpty()) sb.append("Semua batch terkirim. ");
        sb.append("Diterima server = EPC unik di sesi ini dari semua handheld.");
        b.tvPending.setText(sb);
    }

    private void renderList() {
        dirty = false;
        List<String> order = new ArrayList<>(seen);
        List<TagRow> rows = new ArrayList<>(order.size());
        for (int i = order.size() - 1; i >= 0; i--) {   // terbaru di atas
            String e = order.get(i);
            Integer r = rssi.get(e);
            rows.add(new TagRow(e, r == null ? Integer.MIN_VALUE : r, finder.get(e)));
        }
        adapter.submitList(rows);
        b.tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        renderCounts();
    }

    // ---------- baca ----------

    @Override protected boolean isScanActive() { return scanning; }

    @Override protected void onTriggerDown() {
        if (scanning || !readerReady || session == null) return;
        if (!"open".equals(session.status)) { toast(statusText(session.status)); return; }
        if (area == null && "opname".equals(session.kind)) { askArea(); return; }
        scanning = true;
        b.tvLive.setText(R.string.reading);
        renderScanButton(b.btnHoldScan, true, R.string.start_scan, R.string.stop_scan);
        if (prefs.demoMode() && readers.isSimulated() && area != null) readers.setFakePopulation(Gateways.demo(this).fieldEpcs(area));
        UhfReader r = readers.reader();
        int full = r.maxPower();
        readers.runIo(() -> {
            r.setPower(full);                         // drawer count: daya penuh
            boolean started = r.startInventory(new UhfReader.InventoryListener() {
                @Override public void onTags(List<TagRead> batch) {
                    for (TagRead t : batch) {
                        String e = Epc.normalize(t.epc);
                        if (e == null) continue;      // EPC tidak valid tidak pernah dikirim
                        rssi.put(e, t.rssi);
                        if (seen.add(e)) fresh.add(e);
                    }
                    dirty = true;
                }
                @Override public void onError(String message) { ui.post(() -> toast("Reader: " + message)); }
            });
            if (!started) ui.post(this::onTriggerUp);        // UI jangan tampil "membaca" bila reader gagal mulai
        });
        lastFlush = System.currentTimeMillis();
        ui.post(ticker);
    }

    @Override protected void onTriggerUp() {
        if (!scanning) return;
        scanning = false;
        UhfReader r = readers.reader();
        int p = prefs.readPower();
        readers.runIo(() -> { r.stopInventory(); r.setPower(p); });
        b.tvLive.setText(R.string.ready);
        renderScanButton(b.btnHoldScan, false, R.string.start_scan, R.string.stop_scan);
        ui.postDelayed(() -> { persistFresh(true); renderList(); }, 150);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            boolean any = !fresh.isEmpty();
            if (any) beeper.tick();
            long now = System.currentTimeMillis();
            persistFresh(now - lastFlush >= FLUSH_EVERY_MS);
            if (dirty) { renderList(); finder.request(new ArrayList<>(seen)); }
            if (scanning) ui.postDelayed(this, TICK_MS);
        }
    };

    /** EPC baru → count_read; tiap 500 EPC belum terbungkus atau tiap 5 detik → batch outbox + kirim. */
    private void persistFresh(boolean timeToFlush) {
        List<String> batch = new ArrayList<>();
        String e;
        while ((e = fresh.poll()) != null) batch.add(e);
        String a = area, sid = id;
        boolean force = timeToFlush;
        if (force) lastFlush = System.currentTimeMillis();
        if (batch.isEmpty() && !force) return;
        AppExecutors.dbThenMain(() -> {
            Repo repo = Repo.get(this);
            if (!batch.isEmpty()) repo.addReads(sid, a, batch, System.currentTimeMillis());
            if (force || repo.unbatchedCount(sid) >= Repo.FLUSH_AT) return repo.flushReads(sid);
            return 0;
        }, made -> { if (made != null && made > 0) SyncWorker.runNow(this); });
    }

    private void sendNow(boolean feedback) {
        persistFresh(true);
        SyncWorker.runNow(this);
        if (feedback) toast(prefs.demoMode() ? "Mengirim ke Site Gateway demo…" : "Mengirim ke Site Gateway…");
        ui.postDelayed(this::reload, 2000);
    }

    // ---------- selesai / review / tutup ----------

    private void finishAction() {
        if (session == null) return;
        if (scanning) onTriggerUp();
        Session me = Session.get(this);
        if (me.isSupervisor()) {
            if (!"open".equals(session.status)) { startActivity(ReviewActivity.intent(this, id)); finish(); return; }
            whenAllSent(this::confirmReview);
        } else if ("inventory".equals(session.kind) && CountUi.isCreator(session, me.opNo())) {
            whenAllSent(() -> askComment("Tutup " + session.number, "Catatan penutupan (3–500 karakter)", "Tutup", c ->
                    AppExecutors.netThenMain(() -> { Dtos.CountSession s = Gateways.get(this).countClose(id, c); Repo.get(this).cacheSession(s); return s; },
                            (s, err) -> { if (err != null) { beeper.error(); toast(apiError(err)); return; } beeper.success(); toast(s.number + " ditutup"); finish(); })));
        } else {
            whenAllSent(() -> new MaterialAlertDialogBuilder(this)
                    .setTitle("Pembacaan terkirim")
                    .setMessage("Semua EPC untuk " + session.number + " sudah diterima Site Gateway. Minta supervisor me-review sesi ini; hasilnya (mis. 86/87 dan alat yang hilang) muncul di GMF setelah review.")
                    .setPositiveButton(R.string.ok, (d, w) -> finish())
                    .show());
        }
    }

    /** Review hanya setelah semua batch terkirim, supaya hasil yang dikirim ke GMF lengkap. */
    private void whenAllSent(Runnable then) {
        persistFresh(true);
        AppExecutors.dbThenMain(() -> {
            Repo repo = Repo.get(this);
            return new int[]{repo.unbatchedCount(id), repo.pendingBatches(id), repo.failedBatches(id)};
        }, c -> {
            if (c == null) return;
            if (c[2] > 0) {
                new MaterialAlertDialogBuilder(this).setTitle("Ada batch ditolak server")
                        .setMessage(c[2] + " batch ditolak (lihat menu Sinkron). Sesi mungkin sudah tidak terbuka.")
                        .setPositiveButton(R.string.ok, null).show();
                return;
            }
            if (c[0] + c[1] > 0) {
                SyncWorker.runNow(this);
                new MaterialAlertDialogBuilder(this).setTitle("Belum semua terkirim")
                        .setMessage(c[1] + " batch masih di antrean. Pastikan Wi-Fi tersambung lalu coba lagi.")
                        .setPositiveButton("Kirim sekarang", (d, w) -> sendNow(true))
                        .setNegativeButton(R.string.cancel, null).show();
                return;
            }
            then.run();
        });
    }

    private void confirmReview() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Review " + session.number + "?")
                .setMessage("Penghitungan berhenti dan selisih dihitung (hilang, lebih, tidak dikenal). Hasilnya dikirim ke GMF sebagai drawer check result.")
                .setPositiveButton("Review", (d, w) -> AppExecutors.netThenMain(() -> {
                    Dtos.ReviewResponse r = Gateways.get(this).countReview(id);
                    Repo.get(this).cacheSession(r.session);
                    return r;
                }, (r, err) -> {
                    if (err != null) { beeper.error(); toast(apiError(err)); return; }
                    beeper.success();
                    startActivity(ReviewActivity.intent(this, id));
                    finish();
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void moreMenu() {
        if (session == null) return;
        Session me = Session.get(this);
        boolean canCancel = ("open".equals(session.status) || "review".equals(session.status))
                && (me.isSupervisor() || ("inventory".equals(session.kind) && CountUi.isCreator(session, me.opNo())));
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Muat ulang dari Site Gateway"); actions.add(this::reload);
        if (session.area == null) { labels.add("Ubah area"); actions.add(this::askArea); }
        if (canCancel) {
            labels.add("Batalkan sesi"); actions.add(() -> askComment("Batalkan " + session.number, "Alasan (3–500 karakter)", "Batalkan", c ->
                    AppExecutors.netThenMain(() -> { Dtos.CountSession s = Gateways.get(this).countCancel(id, c); Repo.get(this).cacheSession(s); return s; },
                            (s, err) -> { if (err != null) { toast(apiError(err)); return; } toast(s.number + " dibatalkan"); finish(); })));
        }
        new MaterialAlertDialogBuilder(this)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .show();
    }

    private void askArea() {
        EditText et = new EditText(this);
        boolean opname = "opname".equals(session.kind);
        et.setHint(opname ? "Kode laci GMF, mis. D3" : "Area, mis. Rak A3");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        if (area != null) et.setText(area);
        new MaterialAlertDialogBuilder(this)
                .setTitle(opname ? "Kode laci" : "Area")
                .setMessage(opname ? "Harus sama dengan kode laci di GMF." : "Label area bebas untuk hitungan ini.")
                .setView(pad(et))
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String v = et.getText().toString().trim();
                    if (opname) v = v.toUpperCase();
                    if (v.isEmpty()) return;
                    persistFresh(true);            // EPC sebelumnya tetap dengan area lama
                    area = v;
                    prefs.setLastArea(v);
                    Dtos.CountSession s = session;
                    s.area = v;
                    AppExecutors.db(() -> Repo.get(this).cacheSession(s));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    interface OnText { void accept(String s); }

    private void askComment(String title, String hint, String action, OnText ok) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(pad(et))
                .setPositiveButton(action, (d, w) -> {
                    String c = et.getText().toString().trim();
                    if (c.length() < 3) { toast("Catatan minimal 3 karakter"); return; }
                    ok.accept(c);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private View pad(View v) {
        FrameLayout box = new FrameLayout(this);
        int p = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        box.addView(v);
        return box;
    }

    private void onRowClick(TagRow r) {
        String title = r.known() ? (r.info.name == null ? "Alat" : r.info.name) : "EPC";
        String msg = "EPC " + Hex.group4(r.epc) + (r.known() ? "\nKode " + com.taggiot.handheld.ui.UiUtil.toolCode(r.info)
                + "\n" + com.taggiot.handheld.ui.UiUtil.tagStatusLabel(r.info.tagStatus) : r.unknown() ? "\nTidak dikenal Site Gateway" : "");
        new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("Cari", (d, w) -> startActivity(FindTagActivity.intent(this, new String[]{r.epc})))
                .setNegativeButton(R.string.ok, null).show();
    }

    @Override protected void onPause() {
        persistFresh(true);   // jangan biarkan EPC hanya di memori
        super.onPause();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (finder != null) finder.stop();
        super.onDestroy();
    }
}

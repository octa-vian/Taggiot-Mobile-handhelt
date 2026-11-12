package com.taggiot.handheld.ui.inventory;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Repo;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.databinding.ActivityInventoryBinding;
import com.taggiot.handheld.domain.Epc;
import com.taggiot.handheld.net.ApiException;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.reader.TagAggregator;
import com.taggiot.handheld.reader.TagRead;
import com.taggiot.handheld.reader.UhfReader;
import com.taggiot.handheld.sync.SyncWorker;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.ui.FinderCache;
import com.taggiot.handheld.ui.UiUtil;
import com.taggiot.handheld.ui.count.CountSessionActivity;
import com.taggiot.handheld.ui.encode.EncodeActivity;
import com.taggiot.handheld.ui.find.FindTagActivity;
import com.taggiot.handheld.util.AppExecutors;
import com.taggiot.handheld.util.Hex;

import java.util.ArrayList;
import java.util.List;

/**
 * Scan bebas (semua peran): baca semua tag di sekitar, nama alat dan status tag dari finder/lookup
 * (≤ 200 EPC per panggilan). Juga "pembaca polos" untuk membantu binding di aplikasi GMF:
 * ketuk satu EPC → tampil besar + tombol Salin. Hasil bisa dijadikan inventory count (operator+).
 */
public class InventoryActivity extends BaseActivity {

    private static final int REFRESH_MS = 250;

    private ActivityInventoryBinding b;
    private volatile TagAggregator agg;
    private TagAdapter adapter;
    private FinderCache finder;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean dirty;
    private volatile int freshSinceTick;
    private boolean scanning;
    private int filter;   // 0 semua, 1 dikenal, 2 tidak dikenal

    @Override protected boolean usesReader() { return true; }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityInventoryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(false);
        agg = new TagAggregator(prefs.minRssi());

        setupTopBar(b.topBar.getRoot(), getString(R.string.inv_title), "Scan bebas", R.drawable.ic_filter, v -> chooseRssiFilter());
        adapter = new TagAdapter(this::onRowClick);
        b.rvTags.setLayoutManager(new LinearLayoutManager(this));
        b.rvTags.setAdapter(adapter);
        b.rvTags.setItemAnimator(null);
        finder = new FinderCache(this, new FinderCache.Listener() {
            @Override public void onResolved() { dirty = true; if (!scanning) refresh(); }
            @Override public void onError(ApiException e) {
                if (e.needsRelogin()) apiError(e);
                else setTopBarSubtitle(b.topBar.getRoot(), e.isNetwork() ? "Offline · nama alat belum bisa dimuat" : e.userMessage());
            }
        });

        b.chips.setOnCheckedStateChangeListener((g, ids) -> {
            int id = ids.isEmpty() ? R.id.chipAll : ids.get(0);
            filter = id == R.id.chipKnown ? 1 : id == R.id.chipUnknown ? 2 : 0;
            dirty = true;
            refresh();
        });
        bindScanToggle(b.btnHoldScan);
        b.btnClear.setOnClickListener(v -> confirmClear());
        b.btnSave.setOnClickListener(v -> saveAsCount());
        b.btnSave.setVisibility(Session.get(this).canCount() ? View.VISIBLE : View.GONE);
        setLive(false);
        refresh();
    }

    @Override protected void onReaderReady(boolean ok) {
        super.onReaderReady(ok);
        if (ok) setTopBarSubtitle(b.topBar.getRoot(), readers.reader().name() + " · " + prefs.readPower() + " dBm");
    }

    // ---------- baca ----------

    @Override protected boolean isScanActive() { return scanning; }

    @Override protected void onTriggerDown() {
        if (scanning || !readerReady) return;
        scanning = true;
        setLive(true);
        UhfReader r = readers.reader();
        readers.runIo(() -> {
            boolean started = r.startInventory(new UhfReader.InventoryListener() {
                @Override public void onTags(List<TagRead> batch) {
                    List<TagRead> valid = new ArrayList<>(batch.size());
                    for (TagRead t : batch) if (Epc.isValid(t.epc)) valid.add(t);
                    int fresh = agg.add(valid);
                    if (fresh > 0) { freshSinceTick += fresh; dirty = true; }
                    else if (!valid.isEmpty()) dirty = true;
                }
                @Override public void onError(String message) { ui.post(() -> toast("Reader: " + message)); }
            });
            if (!started) ui.post(this::onTriggerUp);    // UI jangan tampil "membaca" bila reader gagal mulai
        });
        ui.post(ticker);
    }

    @Override protected void onTriggerUp() {
        if (!scanning) return;
        scanning = false;
        UhfReader r = readers.reader();
        readers.runIo(r::stopInventory);
        setLive(false);
        dirty = true;
        ui.postDelayed(this::refresh, 150);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (freshSinceTick > 0) { beeper.tick(); freshSinceTick = 0; }
            if (dirty) refresh();
            if (scanning) ui.postDelayed(this, REFRESH_MS);
        }
    };

    // ---------- tampilan ----------

    private void refresh() {
        dirty = false;
        List<TagAggregator.TagStat> stats = agg.snapshot();
        List<String> epcs = new ArrayList<>(stats.size());
        for (TagAggregator.TagStat s : stats) epcs.add(s.epc);
        finder.request(epcs);
        int known = 0, unk = 0;
        List<TagRow> rows = new ArrayList<>(stats.size());
        for (int i = stats.size() - 1; i >= 0; i--) {     // terbaru di atas
            TagAggregator.TagStat s = stats.get(i);
            TagRow row = new TagRow(s.epc, s.rssiLast, finder.get(s.epc));
            if (row.known()) known++;
            if (row.unknown()) unk++;
            if (filter == 1 && !row.known()) continue;
            if (filter == 2 && !row.unknown()) continue;
            rows.add(row);
        }
        adapter.submitList(rows);
        b.tvCount.setText(UiUtil.number(stats.size()));
        b.tvRate.setText(String.valueOf(agg.readsPerSecond(System.currentTimeMillis())));
        b.tvRegistered.setText(UiUtil.number(known));
        b.tvUnknown.setText(UiUtil.number(unk));
        b.tvEmpty.setVisibility(stats.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void setLive(boolean live) {
        b.tvLive.setText(live ? R.string.reading : R.string.ready);
        Drawable d = ContextCompat.getDrawable(this, live ? R.drawable.bg_dot_ok : R.drawable.bg_dot_warn);
        if (d != null) {
            int s = Math.round(10 * getResources().getDisplayMetrics().density);
            d.setBounds(0, 0, s, s);
            b.tvLive.setCompoundDrawables(live ? d : null, null, null, null);
        }
        renderScanButton(b.btnHoldScan, live, R.string.start_scan, R.string.stop_scan);
    }

    // ---------- aksi ----------

    /** EPC besar + Salin (bantu binding di aplikasi GMF), Cari, dan Bind (hanya site non-GMF, supervisor). */
    private void onRowClick(TagRow r) {
        TextView big = new TextView(this);
        big.setText(Hex.group4Lines(r.epc));
        big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        big.setGravity(Gravity.CENTER);
        big.setTextIsSelectable(true);
        big.setTextColor(ContextCompat.getColor(this, R.color.ink));
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(big);
        String title = r.known() ? (r.info.name == null ? UiUtil.toolCode(r.info) : r.info.name) : r.unknown() ? "Tag tidak dikenal" : "EPC";
        String msg = r.known() ? UiUtil.toolCode(r.info) + " · " + UiUtil.tagStatusLabel(r.info.tagStatus)
                : prefs.gmfSite() ? "Untuk mendaftarkan alat: salin EPC ini ke form Add tool / Bind di aplikasi GMF." : null;
        MaterialAlertDialogBuilder dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(msg)
                .setView(box)
                .setPositiveButton("Salin EPC", (d, w) -> copy(r.epc))
                .setNeutralButton("Cari", (d, w) -> startActivity(FindTagActivity.intent(this, new String[]{r.epc})));
        if (!prefs.gmfSite() && Session.get(this).isSupervisor() && r.unknown())
            dlg.setNegativeButton("Bind", (d, w) -> startActivity(EncodeActivity.intent(this, r.epc)));
        else dlg.setNegativeButton(R.string.cancel, null);
        dlg.show();
    }

    private void copy(String epc) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("EPC", epc));
        toast("EPC disalin");
    }

    private void chooseRssiFilter() {
        String[] labels = {"Tanpa filter (≥ −90 dBm)", "≥ −80 dBm", "≥ −70 dBm (default)", "≥ −60 dBm (dekat saja)"};
        int[] values = {-90, -80, -70, -60};
        int current = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == prefs.minRssi()) current = i;
        AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle("Filter kekuatan sinyal")
                .setSingleChoiceItems(labels, current, (dlg, which) -> {
                    prefs.setMinRssi(values[which]);
                    agg = new TagAggregator(values[which]);
                    dirty = true;
                    refresh();
                    dlg.dismiss();
                    toast("Daftar dikosongkan, filter baru berlaku");
                })
                .create();
        d.show();
    }

    private void confirmClear() {
        if (agg.uniqueCount() == 0) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Hapus hasil scan?")
                .setMessage(agg.uniqueCount() + " tag akan dihapus dari layar.")
                .setPositiveButton("Hapus", (d, w) -> { agg.clear(); refresh(); })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Buat inventory count di Site Gateway (counts/create kind inventory) lalu antrekan EPC ini sebagai reads. */
    private void saveAsCount() {
        List<String> epcs = Epc.normalizeAll(agg.epcs());
        if (epcs.isEmpty()) { toast("Belum ada tag terbaca"); return; }
        EditText et = new EditText(this);
        et.setHint("Area, mis. Rak A3 (opsional)");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        et.setText(prefs.lastArea());
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Jadikan inventory count")
                .setMessage(epcs.size() + " EPC akan diunggah ke sesi inventory count baru.")
                .setView(box)
                .setPositiveButton("Buat", (d, w) -> {
                    String area = et.getText().toString().trim();
                    if (!area.isEmpty()) prefs.setLastArea(area);
                    Dtos.CountCreateRequest req = new Dtos.CountCreateRequest();
                    req.kind = "inventory";
                    req.area = area.isEmpty() ? null : area;
                    long now = System.currentTimeMillis();
                    AppExecutors.netThenMain(() -> {
                        Dtos.CountSession s = Gateways.get(this).countCreate(req);
                        if (s.area == null) s.area = req.area;
                        Repo repo = Repo.get(this);
                        repo.cacheSession(s);
                        repo.addReads(s.id, area, epcs, now);
                        repo.flushReads(s.id);
                        return s;
                    }, (s, err) -> {
                        if (err != null) { beeper.error(); toast(apiError(err)); return; }
                        beeper.success();
                        SyncWorker.runNow(this);
                        agg.clear();
                        refresh();
                        startActivity(CountSessionActivity.intent(this, s.id));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (finder != null) finder.stop();
        super.onDestroy();
    }

    public static Intent intent(android.content.Context c) { return new Intent(c, InventoryActivity.class); }
}

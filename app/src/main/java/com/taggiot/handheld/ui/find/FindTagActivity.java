package com.taggiot.handheld.ui.find;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.taggiot.handheld.R;
import com.taggiot.handheld.databinding.ActivityFindTagBinding;
import com.taggiot.handheld.domain.Epc;
import com.taggiot.handheld.net.ApiException;
import com.taggiot.handheld.net.Gateway;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.reader.BarcodeReceiver;
import com.taggiot.handheld.reader.GeigerMeter;
import com.taggiot.handheld.reader.TagRead;
import com.taggiot.handheld.reader.UhfReader;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.ui.UiUtil;
import com.taggiot.handheld.util.AppExecutors;
import com.taggiot.handheld.util.Hex;

import java.util.ArrayList;
import java.util.List;

/**
 * Cari alat (RSSI homing): cari dengan kode alat GMF atau EPC lewat finder Site Gateway,
 * lalu reader difilter ke EPC target; RSSI dihaluskan (±0,5 dtk) jadi bar 0–100 dan bunyi makin cepat.
 * Power turun otomatis saat sinyal penuh untuk meter terakhir (menemukan laci yang tepat).
 * Status tag ditampilkan: alat "hilang" yang released berarti sedang dipinjam.
 */
public class FindTagActivity extends BaseActivity {

    public static final String EXTRA_EPCS = "epcs";
    public static final String RESULT_FOUND = "found";

    private static final int TICK_MS = 100;
    private static final int MIN_AUTO_POWER = 12;

    private ActivityFindTagBinding b;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final GeigerMeter geiger = new GeigerMeter(-75, -35, 0.2, 1500);   // ±0,5 dtk pada ~10 baca/dtk
    private final ArrayList<String> found = new ArrayList<>();
    private List<String> targets = new ArrayList<>();
    private int index;
    private volatile String target;
    private volatile int lastRssi = Integer.MIN_VALUE;
    private boolean scanning;
    private long lastBeep;
    private long fullSince;
    private int power;
    private BarcodeReceiver barcode;
    private EditText dialogInput;

    public static Intent intent(Context c, String[] epcs) {
        return new Intent(c, FindTagActivity.class).putExtra(EXTRA_EPCS, epcs);
    }

    @Override protected boolean usesReader() { return true; }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityFindTagBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(true);
        setupTopBar(b.topBar.getRoot(), getString(R.string.find_title), "pencari arah (RSSI)", R.drawable.ic_volume, v -> toggleSound());

        String[] epcs = getIntent().getStringArrayExtra(EXTRA_EPCS);
        if (epcs != null) for (String e : epcs) { String n = Epc.normalize(e); if (n != null && !targets.contains(n)) targets.add(n); }
        bindScanToggle(b.btnHoldScan);
        b.btnChange.setOnClickListener(v -> askTarget());
        b.cardTarget.setOnClickListener(v -> askTarget());
        b.btnFound.setOnClickListener(v -> markFound());
        b.gauge.setValue(0);
        power = prefs.readPower();
        b.tvPower.setText(String.valueOf(power));
        if (targets.isEmpty()) askTarget(); else showTarget(0);
        updateSoundIcon();
    }

    @Override protected void onResume() {
        super.onResume();
        barcode = BarcodeReceiver.register(this, data -> {
            if (dialogInput != null) dialogInput.setText(data);
            else resolveQuery(data);
        });
    }

    @Override protected void onPause() {
        BarcodeReceiver.unregister(this, barcode);
        barcode = null;
        super.onPause();
        UhfReader r = readers.reader();
        int p = prefs.readPower();
        readers.runIo(() -> r.setPower(p));
    }

    // ---------- target ----------

    private void showTarget(int i) {
        index = i;
        target = targets.get(i);
        geiger.reset();
        lastRssi = Integer.MIN_VALUE;
        b.tvTargetEpc.setText(Hex.group4(target));
        b.tvTargetName.setText("Memeriksa di Site Gateway…");
        b.tvTargetSub.setText(targets.size() > 1 ? "Target " + (i + 1) + " dari " + targets.size() : "");
        b.btnFound.setText(targets.size() > 1 && i < targets.size() - 1 ? getString(R.string.find_next) : getString(R.string.find_found));
        String epc = target;
        AppExecutors.netThenMain(() -> Gateways.get(this).finderEpc(epc), (f, err) -> {
            if (!epc.equals(target)) return;
            String pos = targets.size() > 1 ? " · target " + (index + 1) + "/" + targets.size() : "";
            if (err != null) {
                b.tvTargetName.setText(R.string.find_unknown_tag);
                b.tvTargetSub.setText(apiError(err) + pos);
                return;
            }
            if (!f.known) {
                b.tvTargetName.setText("Tag tidak dikenal Site Gateway");
                b.tvTargetSub.setText("EPC ini belum pernah didaftarkan" + pos);
                return;
            }
            b.tvTargetName.setText(f.name == null ? UiUtil.toolCode(f) : f.name);
            String status = UiUtil.tagStatusLabel(f.tagStatus);
            if ("released".equals(f.tagStatus)) status += " · mungkin tidak hilang, cek pinjaman di GMF";
            b.tvTargetSub.setText(UiUtil.toolCode(f) + " · " + status + pos);
        });
        renderValue(0);
    }

    private void askTarget() {
        EditText et = new EditText(this);
        et.setHint(R.string.find_target_hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        dialogInput = et;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Alat yang dicari")
                .setMessage("Ketik kode alat GMF (mis. GMF-TL-0042), EPC, atau scan barcode.")
                .setView(box)
                .setPositiveButton("Cari", (d, w) -> resolveQuery(et.getText().toString()))
                .setNegativeButton(R.string.cancel, (d, w) -> { if (targets.isEmpty()) finish(); })
                .setOnDismissListener(d -> dialogInput = null)
                .show();
    }

    /**
     * EPC → langsung. Kode alat → finder/sku (kode alat GMF dipakai sebagai SKU/item_ref), lalu
     * finder/products bila tidak cocok persis. Item state replaced/retired/void dilewati.
     * Catatan: Site Gateway akan menambah pencarian khusus item_ref (keputusan 2026-11-10); ganti di sini saat tersedia.
     */
    private void resolveQuery(String q) {
        String query = q.trim();
        if (query.isEmpty()) return;
        String asEpc = Epc.normalize(query);
        if (asEpc != null && asEpc.length() >= 16) { targets = new ArrayList<>(); targets.add(asEpc); showTarget(0); return; }
        AppExecutors.netThenMain(() -> {
            Gateway gw = Gateways.get(this);
            String code = query.toUpperCase();
            try {
                return epcsOf(gw.finderSku(code, null, null, 200));
            } catch (ApiException e) {
                if (e.status != 404 && e.status != 400) throw e;
            }
            Dtos.ProductsResponse pr = gw.finderProducts(query, null, 20);
            if (pr == null || pr.items == null || pr.items.isEmpty()) return new ArrayList<String>();
            if (pr.items.size() == 1) return epcsOf(gw.finderSku(pr.items.get(0).sku, null, null, 200));
            List<String> marker = new ArrayList<>();
            marker.add("?");
            for (Dtos.Product p : pr.items) marker.add(p.sku + "\t" + p.name);
            return marker;
        }, (list, err) -> {
            if (err != null) { toast(apiError(err)); if (targets.isEmpty()) askTarget(); return; }
            if (list == null || list.isEmpty()) { toast("Tidak ditemukan: " + query); if (targets.isEmpty()) askTarget(); return; }
            if ("?".equals(list.get(0))) { chooseProduct(list.subList(1, list.size())); return; }
            targets = list;
            showTarget(0);
        });
    }

    private void chooseProduct(List<String> rows) {
        String[] labels = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) labels[i] = rows.get(i).replace("\t", " · ");
        new MaterialAlertDialogBuilder(this)
                .setTitle("Pilih alat")
                .setItems(labels, (d, w) -> resolveQuery(rows.get(w).split("\t")[0]))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static List<String> epcsOf(Dtos.SkuResponse r) {
        List<String> out = new ArrayList<>();
        if (r == null || r.items == null) return out;
        for (Dtos.FinderItem f : r.items) {
            if (f.itemState != null && (f.itemState.equals("replaced") || f.itemState.equals("retired") || f.itemState.equals("void"))) continue;
            String e = Epc.normalize(f.epc);
            if (e != null) out.add(e);
        }
        return out;
    }

    // ---------- baca ----------

    @Override protected boolean isScanActive() { return scanning; }

    @Override protected void onTriggerDown() {
        if (scanning || !readerReady || target == null) return;
        scanning = true;
        renderScanButton(b.btnHoldScan, true, R.string.start_find, R.string.stop_find);
        UhfReader r = readers.reader();
        readers.runIo(() -> {
            boolean started = r.startInventory(new UhfReader.InventoryListener() {
                @Override public void onTags(List<TagRead> batch) {
                    String t = target;
                    long now = System.currentTimeMillis();
                    for (TagRead tr : batch) {
                        if (t != null && t.equals(Epc.normalize(tr.epc))) { geiger.onRead(tr.rssi, now); lastRssi = tr.rssi; }
                    }
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
        renderScanButton(b.btnHoldScan, false, R.string.start_find, R.string.stop_find);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            int v = geiger.value(now);
            renderValue(v);
            int interval = GeigerMeter.beepIntervalMs(v);
            if (interval > 0 && now - lastBeep >= interval) { beeper.tick(); lastBeep = now; }
            autoPower(v, now);
            if (scanning) ui.postDelayed(this, TICK_MS);
        }
    };

    /** Sinyal penuh > 1,5 detik → turunkan power 3 dB agar arah lebih jelas di meter terakhir. */
    private void autoPower(int v, long now) {
        if (v >= 92) {
            if (fullSince == 0) fullSince = now;
            if (now - fullSince > 1500 && power - 3 >= MIN_AUTO_POWER) {
                power -= 3;
                fullSince = now;
                UhfReader r = readers.reader();
                int p = power;
                readers.runIo(() -> r.setPower(p));
                b.tvPower.setText(String.valueOf(power));
                geiger.reset();
            }
        } else {
            fullSince = 0;
        }
    }

    private void renderValue(int v) {
        b.gauge.setValue(v);
        b.tvValue.setText(String.valueOf(v));
        String label = GeigerMeter.proximityLabel(v);
        UiUtil.Tone tone = v >= 55 ? UiUtil.Tone.OK : v > 0 ? UiUtil.Tone.INFO : UiUtil.Tone.IDLE;
        UiUtil.badge(b.tvProximity, tone, v >= 80 ? label + " · periksa laci di depan" : label);
        b.tvRssi.setText(lastRssi == Integer.MIN_VALUE ? "–" : String.valueOf(lastRssi).replace('-', '−'));
        long since = geiger.millisSinceLastRead(System.currentTimeMillis());
        b.tvLast.setText(since == Long.MAX_VALUE ? "–" : since < 1000 ? "baru" : (since / 1000) + " dtk");
    }

    // ---------- aksi ----------

    private void markFound() {
        if (target == null) return;
        beeper.success();
        found.add(target);
        if (index < targets.size() - 1) {
            power = prefs.readPower();
            UhfReader r = readers.reader();
            int p = power;
            readers.runIo(() -> r.setPower(p));
            b.tvPower.setText(String.valueOf(power));
            showTarget(index + 1);
        } else {
            setResult(RESULT_OK, new Intent().putStringArrayListExtra(RESULT_FOUND, found));
            finish();
        }
    }

    private void toggleSound() {
        boolean on = !prefs.soundEnabled();
        prefs.setSoundEnabled(on);
        beeper.setEnabled(on);
        updateSoundIcon();
    }

    private void updateSoundIcon() {
        ((android.widget.ImageButton) b.topBar.getRoot().findViewById(R.id.btnAction))
                .setImageResource(prefs.soundEnabled() ? R.drawable.ic_volume : R.drawable.ic_volume_off);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

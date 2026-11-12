package com.taggiot.handheld.ui.encode;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;

import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;

import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Repo;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.databinding.ActivityEncodeBinding;
import com.taggiot.handheld.domain.Epc;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.reader.BarcodeReceiver;
import com.taggiot.handheld.reader.FakeUhfReader;
import com.taggiot.handheld.reader.TagOpResult;
import com.taggiot.handheld.reader.TagRead;
import com.taggiot.handheld.reader.UhfReader;
import com.taggiot.handheld.sync.SyncWorker;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.util.AppExecutors;
import com.taggiot.handheld.util.EpcCodec;
import com.taggiot.handheld.util.Hex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tulis EPC + bind (POST /handheld/items/bind dengan Idempotency-Key), supervisor+, HANYA site non-GMF.
 * Di site GMF tag alat diikat di aplikasi GMF (Add tool / Bind / Replace); bind dari handheld akan
 * membuat tag yang tidak dikenal GMF, jadi layar ini menolak dibuka bila mode site GMF aktif.
 * Encode tag baru di luar cakupan build GMF (handover, pertanyaan terbuka 7).
 */
public class EncodeActivity extends BaseActivity {

    public static final String EXTRA_EPC = "epc";
    private static final String SERIAL_KEY = "_giai";
    private static final int DETECT_EVERY_MS = 700;
    private static final int DETECT_WINDOW_MS = 300;
    private static final int WRITE_RANGE_RSSI = -60;

    private ActivityEncodeBinding b;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Dtos.Product> byLabel = new HashMap<>();
    private final Set<String> written = new HashSet<>();
    private Dtos.Product product;
    private String newEpc, fixedEpc;
    private long serial;
    private List<String> inRange = new ArrayList<>();
    private boolean busy, resumed;
    private BarcodeReceiver barcode;

    public static Intent intent(Context c, String epc) {
        return new Intent(c, EncodeActivity.class).putExtra(EXTRA_EPC, epc);
    }

    @Override protected boolean usesReader() { return true; }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (prefs.gmfSite()) { toast("Site GMF: ikat tag alat di aplikasi GMF, bukan dari handheld."); finish(); return; }
        if (!Session.get(this).isSupervisor()) { toast("Bind tag hanya untuk supervisor."); finish(); return; }
        b = ActivityEncodeBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(true);
        fixedEpc = Epc.normalize(getIntent().getStringExtra(EXTRA_EPC));
        setupTopBar(b.topBar.getRoot(), getString(R.string.enc_title),
                fixedEpc != null ? "Tulis ulang " + Hex.group4(fixedEpc) : "Power tulis " + prefs.writePower() + " dBm", 0, null);

        b.btnWrite.setOnClickListener(v -> write());
        b.actvItem.setOnItemClickListener((p, v, pos, id) -> selectProduct(byLabel.get((String) p.getItemAtPosition(pos))));
        AppExecutors.netThenMain(() -> Gateways.get(this).finderProducts(null, null, 200), (res, err) -> {
            if (err != null) { toast(apiError(err)); return; }
            List<String> labels = new ArrayList<>();
            for (Dtos.Product p : res.items) {
                String l = p.name + " · " + p.sku;
                labels.add(l);
                byLabel.put(l, p);
            }
            b.actvItem.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        });
        renderSteps();
        renderDetect();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (b == null) return;
        barcode = BarcodeReceiver.register(this, data -> {
            for (Dtos.Product p : byLabel.values()) {
                if (p.sku.equalsIgnoreCase(data.trim())) { b.actvItem.setText(p.name + " · " + p.sku, false); selectProduct(p); return; }
            }
            toast("Produk " + data + " tidak ada di Site Gateway");
        });
    }

    @Override protected void onReaderReady(boolean ok) {
        super.onReaderReady(ok);
        if (ok && b != null) ui.post(detectLoop);
    }

    @Override protected void onPause() {
        resumed = false;
        ui.removeCallbacks(detectLoop);
        BarcodeReceiver.unregister(this, barcode);
        barcode = null;
        super.onPause();
        UhfReader r = readers.reader();
        int p = prefs.readPower();
        readers.runIo(() -> r.setPower(p));
    }

    // ---------- produk & EPC ----------

    private void selectProduct(Dtos.Product p) {
        if (p == null) return;
        product = p;
        b.tvItemSub.setText("SKU " + p.sku);
        prepareEpc();
        renderSteps();
    }

    /** GIAI-96 dengan asset reference global per perangkat (unik lintas produk). */
    private void prepareEpc() {
        if (product == null) return;
        serial = prefs.peekSerial(SERIAL_KEY);
        String prefix = prefs.gs1CompanyPrefix();
        try {
            newEpc = EpcCodec.encodeGiai96(0, prefix, serial);
            b.tvUri.setText(EpcCodec.decodeGiai96(newEpc).pureIdentityUri());
            b.tvEpc.setText(Hex.group4(newEpc).replaceFirst("^(\\S+ \\S+ \\S+) ", "$1\n"));
            b.tvScheme.setText("GIAI-96");
        } catch (IllegalArgumentException e) {
            newEpc = null;
            b.tvEpc.setText("–");
            b.tvUri.setText("Tidak bisa membuat EPC: " + e.getMessage());
        }
    }

    // ---------- deteksi tag ----------

    private final Runnable detectLoop = new Runnable() {
        @Override public void run() {
            if (!resumed || !readerReady) return;
            if (!busy) detectOnce(list -> {
                inRange = list;
                renderDetect();
                renderSteps();
            });
            ui.postDelayed(this, DETECT_EVERY_MS);
        }
    };

    private void detectOnce(AppExecutors.Done<List<String>> done) {
        UhfReader r = readers.reader();
        int power = prefs.writePower();
        Set<String> exclude = new HashSet<>(written);
        readers.run(() -> {
            r.setPower(power);
            Map<String, Integer> best = new HashMap<>();
            r.startInventory(new UhfReader.InventoryListener() {
                @Override public void onTags(List<TagRead> batch) {
                    synchronized (best) {
                        for (TagRead t : batch) {
                            Integer prev = best.get(t.epc);
                            if (prev == null || t.rssi > prev) best.put(t.epc, t.rssi);
                        }
                    }
                }
                @Override public void onError(String message) {}
            });
            Thread.sleep(DETECT_WINDOW_MS);
            r.stopInventory();
            List<String> out = new ArrayList<>();
            synchronized (best) {
                for (Map.Entry<String, Integer> e : best.entrySet())
                    if (e.getValue() >= WRITE_RANGE_RSSI && !exclude.contains(e.getKey())) out.add(e.getKey());
            }
            return out;
        }, list -> { if (list != null && !isFinishing()) done.accept(list); });
    }

    private void renderDetect() {
        int n = inRange.size();
        boolean wrongTag = fixedEpc != null && n == 1 && !inRange.get(0).equals(fixedEpc);
        if (n == 0) {
            detectBox(R.drawable.bg_detect_idle, R.drawable.bg_circle_idle, R.drawable.ic_tag,
                    "Dekatkan tag ke antena", "Jarak ± 5 cm dari bagian atas handheld");
        } else if (n == 1 && !wrongTag) {
            detectBox(R.drawable.bg_detect, R.drawable.bg_circle_ok, R.drawable.ic_check,
                    "1 tag terdeteksi", "EPC sekarang " + Hex.group4(inRange.get(0)) + " · power tulis " + prefs.writePower() + " dBm");
        } else if (wrongTag) {
            detectBox(R.drawable.bg_detect_warn, R.drawable.bg_circle_warn, R.drawable.ic_alert,
                    "Tag berbeda terdeteksi", "Dekatkan tag " + Hex.group4(fixedEpc));
        } else {
            detectBox(R.drawable.bg_detect_warn, R.drawable.bg_circle_warn, R.drawable.ic_alert,
                    n + " tag terdeteksi", "Jauhkan tag lain, hanya satu tag boleh di dekat antena");
        }
        b.btnWrite.setEnabled(!busy && product != null && newEpc != null && n == 1 && !wrongTag);
    }

    private void detectBox(int bg, int circle, @DrawableRes int icon, String title, String sub) {
        b.boxDetect.setBackgroundResource(bg);
        b.detectCircle.setBackgroundResource(circle);
        b.ivDetect.setImageResource(icon);
        b.tvDetectTitle.setText(title);
        b.tvDetectSub.setText(sub);
    }

    private void renderSteps() {
        boolean s1 = product != null, s2 = inRange.size() == 1;
        step(b.stepDot1, b.stepLabel1, s1 ? 2 : 1, "1");
        step(b.stepDot2, b.stepLabel2, !s1 ? 0 : s2 ? 2 : 1, "2");
        step(b.stepDot3, b.stepLabel3, s1 && s2 ? 1 : 0, "3");
    }

    private void step(android.widget.TextView dot, android.widget.TextView label, int state, String n) {
        dot.setBackgroundResource(state == 2 ? R.drawable.bg_step_done : state == 1 ? R.drawable.bg_step_cur : R.drawable.bg_step_todo);
        dot.setText(state == 2 ? "✓" : n);
        dot.setTextColor(ContextCompat.getColor(this, state == 0 ? R.color.ink_2 : R.color.on_brand));
        label.setTextColor(ContextCompat.getColor(this, state == 2 || state == 1 ? R.color.brand_700 : R.color.ink_3));
    }

    // ---------- tulis + bind ----------

    private void write() {
        if (busy || product == null || newEpc == null || inRange.size() != 1) return;
        busy = true;
        b.btnWrite.setEnabled(false);
        b.btnWrite.setText("Menulis…");
        String current = inRange.get(0), target = newEpc;
        UhfReader r = readers.reader();
        int power = prefs.writePower();
        readers.run(() -> {
            r.setPower(power);
            TagOpResult res = r.writeEpc(current, target, null);
            if (!res.success) return res;
            for (int i = 0; i < 3; i++) {
                TagRead t = r.readSingle(300);
                if (t != null && t.epc.equals(target)) return TagOpResult.ok(t.epc);
            }
            return TagOpResult.fail("Verifikasi gagal: EPC baru tidak terbaca");
        }, res -> {
            busy = false;
            b.btnWrite.setText(R.string.enc_write);
            if (res == null || !res.success) {
                beeper.error();
                b.tvLastResult.setText("Gagal: " + (res == null ? "reader error" : res.error));
                renderDetect();
                return;
            }
            onWritten(target);
        });
    }

    private void onWritten(String epc) {
        beeper.success();
        written.add(epc);
        Dtos.Product p = product;
        long ser = serial;
        prefs.advanceSerial(SERIAL_KEY);
        Dtos.BindRequest req = new Dtos.BindRequest();
        req.epc = epc; req.sku = p.sku; req.serial = ser;
        AppExecutors.db(() -> { Repo.get(this).enqueueBind(req, "Bind " + p.name); SyncWorker.runNow(this); });
        b.tvLastResult.setText("Tertulis " + Hex.group4(epc) + " · S/N " + ser + " · bind menunggu sinkron");
        inRange = new ArrayList<>();
        if (b.swBulk.isChecked() && fixedEpc == null) {
            if (readers.reader() instanceof FakeUhfReader) ((FakeUhfReader) readers.reader()).addBlankTag();
            prepareEpc();
            renderDetect();
            renderSteps();
        } else {
            toast("Tag berhasil ditulis");
            if (fixedEpc != null) finish(); else { prepareEpc(); renderDetect(); renderSteps(); }
        }
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

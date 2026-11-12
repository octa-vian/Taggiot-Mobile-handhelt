package com.taggiot.handheld.ui.pair;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.databinding.ActivityPairBinding;
import com.taggiot.handheld.net.ApiClient;
import com.taggiot.handheld.net.ApiException;
import com.taggiot.handheld.net.DemoGateway;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.reader.BarcodeReceiver;
import com.taggiot.handheld.security.SecureStore;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.ui.login.LoginActivity;
import com.taggiot.handheld.util.AppExecutors;

/**
 * Pairing perangkat (sekali): admin site membuat handheld di Site Gateway dan memberi kode pairing
 * 8 karakter (berlaku 15 menit, sekali pakai). Aplikasi menukarnya lewat POST /handheld/pair dengan
 * device key, yang disimpan terenkripsi di Android Keystore dan dikirim sebagai X-Handheld-Key saat login.
 * Pasang ulang aplikasi menghapus kunci Keystore → pairing baru wajib.
 */
public class PairActivity extends BaseActivity {

    private ActivityPairBinding b;
    private BarcodeReceiver barcode;
    private boolean busy;

    @Override protected boolean requiresSession() { return false; }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityPairBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(false);
        b.etUrl.setText(prefs.siteGatewayUrl());
        b.swDemo.setChecked(prefs.demoMode());
        b.swDemo.setOnCheckedChangeListener((v, on) -> renderDemoHint(on));
        b.btnPair.setOnClickListener(v -> pair());
        b.btnCancel.setVisibility(SecureStore.get(this).hasDeviceKey() ? View.VISIBLE : View.GONE);
        b.btnCancel.setOnClickListener(v -> finish());
        renderDemoHint(prefs.demoMode());
    }

    @Override protected void onResume() {
        super.onResume();
        barcode = BarcodeReceiver.register(this, data -> {
            // Kode pairing bisa dicetak admin sebagai barcode / QR
            String code = data.trim().toUpperCase();
            if (code.length() == 8) { b.etCode.setText(code); beeper.tick(); }
        });
    }

    @Override protected void onPause() {
        BarcodeReceiver.unregister(this, barcode);
        barcode = null;
        super.onPause();
    }

    private void renderDemoHint(boolean demo) {
        b.tvDemoHint.setVisibility(demo ? View.VISIBLE : View.GONE);
        b.tvDemoHint.setText("Mode demo: Site Gateway tiruan di dalam aplikasi. Kode pairing " + DemoGateway.PAIRING_CODE
                + ", operator " + DemoGateway.OP_OPERATOR + " / supervisor " + DemoGateway.OP_SUPERVISOR
                + " / viewer " + DemoGateway.OP_VIEWER + ", PIN " + DemoGateway.DEMO_PIN + ".");
        b.tilUrl.setEnabled(!demo);
    }

    private void pair() {
        if (busy) return;
        String url = String.valueOf(b.etUrl.getText()).trim();
        String code = String.valueOf(b.etCode.getText()).trim().toUpperCase();
        boolean demo = b.swDemo.isChecked();
        b.tilCode.setError(null);
        b.tilUrl.setError(null);
        if (code.length() != 8) { b.tilCode.setError("Kode pairing 8 karakter"); return; }
        if (!demo) {
            if (!ApiClient.isHttps(url)) { b.tilUrl.setError("Harus https:// — PIN tidak boleh lewat HTTP biasa"); return; }
            if (okhttp3.HttpUrl.parse(url.endsWith("/") ? url : url + "/") == null) { b.tilUrl.setError("URL tidak valid"); return; }
        }
        busy = true;
        b.btnPair.setEnabled(false);
        b.btnPair.setText("Menghubungi Site Gateway…");
        b.tvError.setVisibility(View.GONE);
        prefs.setDemoMode(demo);
        if (!demo) prefs.setSiteGatewayUrl(url);
        Gateways.reset();
        AppExecutors.netThenMain(() -> {
            String key = Gateways.get(this).pair(code);
            SecureStore.get(this).setDeviceKey(key);       // hanya di Keystore
            SecureStore.get(this).setPairedAt(com.taggiot.handheld.data.Repo.iso(System.currentTimeMillis()));
            Session.get(this).end();
            return Boolean.TRUE;
        }, (ok, err) -> {
            busy = false;
            b.btnPair.setEnabled(true);
            b.btnPair.setText(R.string.pair_button);
            if (err == null) {
                beeper.success();
                toast("Perangkat terpasang ke Site Gateway");
                startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
                return;
            }
            beeper.error();
            String msg = err instanceof ApiException ? ((ApiException) err).userMessage()
                    : err instanceof IllegalStateException ? "Android Keystore tidak tersedia; device key tidak bisa disimpan aman."
                    : "Pairing gagal: " + err.getClass().getSimpleName();
            b.tvError.setText(msg);
            b.tvError.setVisibility(View.VISIBLE);
        });
    }
}

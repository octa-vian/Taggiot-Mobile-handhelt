package com.taggiot.handheld.ui.settings;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.taggiot.handheld.BuildConfig;
import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.databinding.ActivitySettingsBinding;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.reader.ReaderManager;
import com.taggiot.handheld.reader.TriggerKeys;
import com.taggiot.handheld.reader.UhfReader;
import com.taggiot.handheld.security.SecureStore;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.ui.login.LoginActivity;
import com.taggiot.handheld.ui.pair.PairActivity;
import com.taggiot.handheld.util.AppExecutors;

import java.util.Set;
import java.util.TreeSet;

/**
 * Profil & pengaturan: ganti PIN (POST /handheld/pin), keluar (POST /handheld/logout), pasangkan ulang.
 * URL Site Gateway dan mode demo hanya berubah lewat pairing ulang (device key terikat ke satu Site Gateway).
 * Pengaturan perangkat (reader, tombol, scanner, mode site GMF) hanya untuk supervisor.
 */
public class SettingsActivity extends BaseActivity {

    private ActivitySettingsBinding b;
    private final Set<Integer> keys = new TreeSet<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(true);
        setupTopBar(b.topBar.getRoot(), getString(R.string.settings_title), prefs.deviceId(), 0, null);

        Session s = Session.get(this);
        b.tvAvatar.setText(s.initials());
        b.tvOperator.setText(s.displayName());
        b.tvRole.setText(Session.roleLabel(s.role()) + " · no. " + s.opNo());
        b.btnLogout.setOnClickListener(v -> logout());
        b.btnChangePin.setOnClickListener(v -> changePin());

        boolean canEdit = s.isSupervisor();
        b.tvTechOnly.setVisibility(canEdit ? View.GONE : View.VISIBLE);
        setEnabledDeep(b.groupReader, canEdit);
        setEnabledDeep(b.groupTrigger, canEdit);
        setEnabledDeep(b.groupBarcode, canEdit);
        b.etPrefix.setEnabled(canEdit && !prefs.gmfSite());

        // Site Gateway: hanya tampil; diubah lewat pairing ulang
        b.etSgwUrl.setText(prefs.demoMode() ? "(mode demo, tanpa jaringan)" : prefs.siteGatewayUrl());
        b.etSgwUrl.setEnabled(false);
        b.swDemo.setChecked(prefs.demoMode());
        b.swDemo.setEnabled(false);
        b.swGmf.setChecked(prefs.gmfSite());
        b.swGmf.setEnabled(canEdit);
        String pairedAt = SecureStore.get(this).pairedAt();
        b.tvPairInfo.setText("Perangkat " + (s.handheldName().isEmpty() ? prefs.deviceId() : s.handheldName())
                + (pairedAt == null ? "" : " · dipasangkan " + pairedAt.substring(0, Math.min(16, pairedAt.length())).replace('T', ' '))
                + ". Ganti URL atau mode demo dengan Pasangkan ulang.");
        b.btnRepair.setEnabled(canEdit);
        b.btnRepair.setOnClickListener(v -> repair());

        switch (prefs.readerMode()) {
            case "seuic": b.rgReader.check(R.id.rbSeuic); break;
            case "fake": b.rgReader.check(R.id.rbFake); break;
            default: b.rgReader.check(R.id.rbAuto);
        }
        b.slReadPower.setValue(clamp(prefs.readPower(), 5, 30));
        b.slWritePower.setValue(clamp(prefs.writePower(), 5, 30));
        b.slMinRssi.setValue(clamp(prefs.minRssi(), -90, -40));
        b.slReadPower.addOnChangeListener((sl, v, u) -> labels());
        b.slWritePower.addOnChangeListener((sl, v, u) -> labels());
        b.slMinRssi.addOnChangeListener((sl, v, u) -> labels());
        keys.addAll(prefs.triggerKeys());
        b.btnCaptureKey.setOnClickListener(v -> captureKey());
        b.btnResetKeys.setOnClickListener(v -> { keys.clear(); for (int k : TriggerKeys.DEFAULTS) keys.add(k); labels(); });
        b.etBcAction.setText(prefs.barcodeAction());
        b.etBcExtra.setText(prefs.barcodeExtra());
        b.etPrefix.setText(prefs.gs1CompanyPrefix());
        b.swSound.setChecked(prefs.soundEnabled());
        b.btnSave.setOnClickListener(v -> save());
        labels();
        about();
    }

    private void labels() {
        b.tvReadPower.setText(getString(R.string.settings_read_power) + " · " + (int) b.slReadPower.getValue() + " dBm");
        b.tvWritePower.setText(getString(R.string.settings_write_power) + " · " + (int) b.slWritePower.getValue() + " dBm");
        b.tvMinRssi.setText(getString(R.string.settings_min_rssi) + " · " + (int) b.slMinRssi.getValue() + " dBm");
        b.tvKeys.setText("Keycode: " + TriggerKeys.toCsv(keys));
    }

    private void about() {
        UhfReader r = readers.reader();
        b.tvReaderInfo.setText("Aktif: " + r.name() + (BuildConfig.HAS_SEUIC_SDK ? "" : "\nSDK SEUIC belum dipasang (app/libs/uhf.jar)"));
        readers.run(r::firmwareInfo, fw -> b.tvAbout.setText(
                "Aplikasi  " + BuildConfig.VERSION_NAME + "\n"
                        + "Perangkat " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + "\n"
                        + "ID       " + prefs.deviceId() + "\n"
                        + "Reader   " + (fw == null ? "–" : fw) + "\n"
                        + "SEUIC    " + (ReaderManager.isSeuicDevice() ? "terdeteksi" : "tidak terdeteksi")
                        + (BuildConfig.HAS_SEUIC_SDK ? " · SDK ada" : " · SDK belum ada")));
    }

    private void captureKey() {
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_trigger_capture)
                .setMessage("Tekan tombol pemicu / scan di handheld sekarang.")
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnKeyListener((dlg, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN || code == KeyEvent.KEYCODE_BACK) return false;
            keys.add(code);
            labels();
            toast("Keycode " + code + " (" + KeyEvent.keyCodeToString(code) + ") ditambahkan");
            dlg.dismiss();
            return true;
        });
        d.show();
    }

    private void save() {
        String prefix = String.valueOf(b.etPrefix.getText()).trim();
        if (!prefix.matches("\\d{6,12}")) { b.tilPrefix.setError("6–12 digit"); return; }
        b.tilPrefix.setError(null);
        prefs.setSoundEnabled(b.swSound.isChecked());
        if (Session.get(this).isSupervisor()) {
            String oldMode = prefs.readerMode();
            String mode = b.rgReader.getCheckedRadioButtonId() == R.id.rbSeuic ? "seuic"
                    : b.rgReader.getCheckedRadioButtonId() == R.id.rbFake ? "fake" : "auto";
            prefs.setGmfSite(b.swGmf.isChecked());
            prefs.setReaderMode(mode);
            prefs.setReadPower((int) b.slReadPower.getValue());
            prefs.setWritePower((int) b.slWritePower.getValue());
            prefs.setMinRssi((int) b.slMinRssi.getValue());
            prefs.setTriggerKeys(keys);
            prefs.setBarcode(String.valueOf(b.etBcAction.getText()).trim(), String.valueOf(b.etBcExtra.getText()).trim());
            prefs.setGs1CompanyPrefix(prefix);
            if (!mode.equals(oldMode)) readers.reset();
        }
        beeper.success();
        toast("Pengaturan disimpan");
        finish();
    }

    /** POST /handheld/pin: PIN lama + baru; server menolak PIN sepele dan PIN = nomor operator. */
    private void changePin() {
        EditText oldPin = pinField("PIN lama"), newPin = pinField("PIN baru"), again = pinField("Ulangi PIN baru");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        box.addView(oldPin); box.addView(newPin); box.addView(again);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_change_pin)
                .setMessage("6 digit. PIN mudah ditebak (111111, 123456) ditolak.")
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String o = oldPin.getText().toString(), n = newPin.getText().toString(), a = again.getText().toString();
                    if (!o.matches("\\d{6}") || !n.matches("\\d{6}")) { toast("PIN harus 6 digit"); return; }
                    if (!n.equals(a)) { toast("PIN baru tidak sama"); return; }
                    AppExecutors.netThenMain(() -> { Gateways.get(this).changePin(o, n); return Boolean.TRUE; }, (ok, err) -> {
                        if (err != null) { beeper.error(); toast(apiError(err)); return; }
                        beeper.success();
                        toast("PIN diganti");
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private EditText pinField(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        return et;
    }

    private void logout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Keluar?")
                .setMessage("Data yang belum terkirim tetap aman di perangkat dan dikirim setelah operator berikutnya masuk.")
                .setPositiveButton(R.string.settings_logout, (d, w) -> AppExecutors.netThenMain(() -> {
                    try { Gateways.get(this).logout(); } catch (Exception ignored) { /* token tetap dibuang lokal */ }
                    return Boolean.TRUE;
                }, (ok, err) -> {
                    Session.get(this).end();
                    readers.closeModule();
                    startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Hapus device key dan sesi, lalu pairing baru (kode dari admin site). Antrean yang belum terkirim tetap disimpan. */
    private void repair() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Pasangkan ulang perangkat?")
                .setMessage("Device key dihapus dari perangkat ini. Anda perlu kode pairing baru dari admin site. Data yang belum terkirim tetap di perangkat.")
                .setPositiveButton("Pasangkan ulang", (d, w) -> AppExecutors.netThenMain(() -> {
                    try { Gateways.get(this).logout(); } catch (Exception ignored) {}
                    return Boolean.TRUE;
                }, (ok, err) -> {
                    Session.get(this).end();
                    SecureStore.get(this).clearDeviceKey();
                    if (prefs.demoMode()) Gateways.demo(this).resetPairing();
                    Gateways.reset();
                    startActivity(new Intent(this, PairActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void setEnabledDeep(View v, boolean enabled) {
        v.setAlpha(enabled ? 1f : 0.6f);
        setEnabledRec(v, enabled);
    }

    private static void setEnabledRec(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledRec(g.getChildAt(i), enabled);
        }
    }

    private static float clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}

package com.taggiot.handheld.ui;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Prefs;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.net.ApiException;
import com.taggiot.handheld.reader.ReaderManager;
import com.taggiot.handheld.reader.ScanKeyClaim;
import com.taggiot.handheld.ui.login.LoginActivity;
import com.taggiot.handheld.util.Beeper;

import java.util.Set;

/**
 * Dasar semua layar:
 * - cek sesi operator (logout otomatis setelah 15 menit tidak aktif),
 * - tombol pemicu fisik → onTriggerDown()/onTriggerUp(),
 * - pegang/lepas reader UHF otomatis untuk layar yang memakai reader,
 * - helper top bar, status bar, bunyi.
 */
public abstract class BaseActivity extends AppCompatActivity {

    protected Beeper beeper;
    protected ReaderManager readers;
    protected Prefs prefs;
    private Set<Integer> triggerKeys;
    /** Pemicu fisik sedang ditekan: tekanan berikutnya baru dihitung setelah tombol dilepas. */
    private boolean triggerHeld;
    /** Satu tekanan shutter bisa datang dua kali (KeyEvent + callback ScanKey vendor); yang kedua diabaikan. */
    private static final long TRIGGER_DEBOUNCE_MS = 300;
    private long lastToggleAt;
    /** Layar reader: shutter diambil dari scanner barcode vendor supaya tidak ikut menyala. */
    private ScanKeyClaim scanKey = ScanKeyClaim.NONE;
    protected boolean readerReady;

    protected boolean requiresSession() { return true; }

    /** true untuk layar yang memakai reader UHF (Inventory, Cari, Opname, Encode). */
    protected boolean usesReader() { return false; }

    protected void onTriggerDown() {}
    protected void onTriggerUp() {}

    /** true bila layar sedang membaca tag. Dipakai tombol layar (toggle) dan pemicu fisik. */
    protected boolean isScanActive() { return false; }

    protected void onReaderReady(boolean ok) {
        if (!ok) toast("Reader UHF tidak bisa dibuka. Cek baterai / restart handheld.");
    }

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        beeper = new Beeper(this);
        readers = ReaderManager.get(this);
        prefs = Prefs.get(this);
        if (usesReader()) scanKey = ScanKeyClaim.create(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (requiresSession() && !Session.get(this).isActive()) {
            // Token kedaluwarsa (401): nomor operator disimpan untuk prefill; selain itu sesi dihapus total.
            if (!Session.get(this).wasExpired()) Session.get(this).end();
            goLogin();
            return;
        }
        Session.get(this).touch();
        triggerKeys = prefs.triggerKeys();
        beeper.setEnabled(prefs.soundEnabled());
        if (usesReader()) {
            scanKey.claim(new ScanKeyClaim.Listener() {
                @Override public void onDown() { triggerPressed(); }
                @Override public void onUp() { triggerHeld = false; }
            });
            readerReady = false;
            readers.acquire(this, ok -> {
                readerReady = ok;
                onReaderReady(ok);
            });
        }
    }

    @Override protected void onPause() {
        triggerHeld = false;
        if (isScanActive()) onTriggerUp();      // jangan biarkan scan menggantung saat layar ditinggalkan
        if (usesReader()) { scanKey.release(); readers.release(this); }
        super.onPause();
    }

    @Override protected void onDestroy() {
        beeper.release();
        super.onDestroy();
    }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        if (requiresSession()) Session.get(this).touch();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (triggerKeys != null && triggerKeys.contains(e.getKeyCode())) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) triggerPressed();
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                triggerHeld = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** Toggle: tekan sekali mulai baca, tekan lagi berhenti. Lepas tombol tidak menghentikan baca. */
    private void triggerPressed() {
        long now = SystemClock.uptimeMillis();
        if (triggerHeld || now - lastToggleAt < TRIGGER_DEBOUNCE_MS) return;
        triggerHeld = true;
        lastToggleAt = now;
        if (isScanActive()) onTriggerUp(); else onTriggerDown();
    }

    /**
     * Tombol layar "Mulai scan": satu ketukan mulai, ketukan berikutnya stop (tanpa perlu ditahan).
     * Pemicu fisik (shutter) berperilaku sama.
     */
    protected void bindScanToggle(View v) {
        v.setOnClickListener(view -> {
            if (isScanActive()) onTriggerUp(); else onTriggerDown();
        });
    }

    /** Tampilan tombol scan: biru "Mulai ..." saat siap, merah "Stop ..." saat membaca. */
    protected void renderScanButton(MaterialButton btn, boolean live, @StringRes int startText, @StringRes int stopText) {
        btn.setText(live ? stopText : startText);
        btn.setIconResource(live ? R.drawable.ic_close : R.drawable.ic_scan);
        btn.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, live ? R.color.status_bad : R.color.brand_500)));
        ColorStateList fg = ColorStateList.valueOf(ContextCompat.getColor(this, live ? R.color.white : R.color.on_brand));
        btn.setTextColor(fg);
        btn.setIconTint(fg);
    }

    /** Status bar: gelap (biru) untuk layar ber-hero, terang untuk layar putih. */
    protected void setStatusBar(boolean lightScreen) {
        getWindow().setStatusBarColor(ContextCompat.getColor(this, lightScreen ? R.color.bg : R.color.brand_700));
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setAppearanceLightStatusBars(lightScreen);
    }

    /** Isi top bar (include view_top_bar_dark / view_top_bar_light). actionIcon 0 = sembunyikan. */
    protected void setupTopBar(View root, String title, String subtitle, @DrawableRes int actionIcon, View.OnClickListener action) {
        ((TextView) root.findViewById(R.id.tvTitle)).setText(title);
        TextView sub = root.findViewById(R.id.tvSubtitle);
        sub.setText(subtitle);
        sub.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        ImageButton act = root.findViewById(R.id.btnAction);
        if (actionIcon == 0) {
            act.setVisibility(View.INVISIBLE);
        } else {
            act.setImageResource(actionIcon);
            act.setOnClickListener(action);
        }
    }

    protected void setTopBarSubtitle(View root, String subtitle) {
        TextView sub = root.findViewById(R.id.tvSubtitle);
        sub.setText(subtitle);
        sub.setVisibility(View.VISIBLE);
    }

    protected void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    protected void goLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    /**
     * Pesan operator untuk kesalahan panggilan Site Gateway. 401 unauthenticated → token dibuang
     * dan operator diarahkan ke login (antrean tetap, dikirim ulang dengan id yang sama setelah masuk).
     */
    protected String apiError(Exception e) {
        if (e instanceof ApiException) {
            ApiException ae = (ApiException) e;
            if (ae.needsRelogin()) {
                Session.get(this).expire();
                goLogin();
            }
            return ae.userMessage();
        }
        return e == null ? "Gagal" : "Gagal: " + e.getClass().getSimpleName();
    }
}

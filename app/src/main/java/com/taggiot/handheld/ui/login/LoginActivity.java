package com.taggiot.handheld.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.databinding.ActivityLoginBinding;
import com.taggiot.handheld.net.ApiException;
import com.taggiot.handheld.net.Gateway;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.reader.BarcodeReceiver;
import com.taggiot.handheld.security.SecureStore;
import com.taggiot.handheld.sync.SyncWorker;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.ui.home.HomeActivity;
import com.taggiot.handheld.ui.pair.PairActivity;
import com.taggiot.handheld.util.AppExecutors;

/**
 * Masuk: Nomor Operator (3–10 digit, nol di depan dipertahankan; ketik atau scan badge) + PIN 6 digit,
 * lewat POST /handheld/login dengan header X-Handheld-Key (device key hasil pairing).
 * Jawaban server dipakai apa adanya: PIN salah dan operator tidak dikenal tampak sama (401),
 * 5 kali salah → 429 dengan Retry-After, 423 akun dinonaktifkan, 403 handheld tidak dikenal.
 * Token tgh_… (12 jam) disimpan terenkripsi; PIN tidak pernah disimpan atau dicatat.
 */
public class LoginActivity extends BaseActivity {

    private static final int OP_MIN = 3, OP_MAX = 10, PIN_LEN = 6;

    private ActivityLoginBinding b;
    private final StringBuilder opNo = new StringBuilder();
    private final StringBuilder pin = new StringBuilder();
    private boolean editingPin;
    private final View[] pinBoxes = new View[PIN_LEN];
    private final View[] pinDots = new View[PIN_LEN];
    private BarcodeReceiver barcode;
    private boolean busy;

    @Override protected boolean requiresSession() { return false; }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SecureStore.get(this).hasDeviceKey()) {
            startActivity(new Intent(this, PairActivity.class));
            finish();
            return;
        }
        if (Session.get(this).isActive()) { openHome(); return; }
        b = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(false);

        Session s = Session.get(this);
        b.tvSite.setText(prefs.demoMode() ? "Mode demo · Site Gateway tiruan" : host(prefs.siteGatewayUrl()));
        String hh = s.handheldName();
        b.tvFooter.setText((hh.isEmpty() ? "Perangkat " + prefs.deviceId() : "Perangkat " + hh) + " · terpasang ke Site Gateway");
        if (s.wasExpired() && !s.opNo().isEmpty()) {
            opNo.append(s.opNo());
            b.tvError.setText("Sesi berakhir. Masukkan PIN lagi; data yang belum terkirim akan dikirim ulang.");
            b.tvError.setVisibility(View.VISIBLE);
        }
        buildPinRow();
        buildKeypad();
        b.boxOpNo.setOnClickListener(v -> setEditingPin(false));
        b.pinRow.setOnClickListener(v -> setEditingPin(true));
        b.btnLogin.setOnClickListener(v -> attemptLogin());
        setEditingPin(opNo.length() >= OP_MIN);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (b == null) return;
        barcode = BarcodeReceiver.register(this, data -> {
            // Badge karyawan: ambil digit saja sebagai Nomor Operator (maks 10 digit terakhir)
            String digits = data.replaceAll("\\D", "");
            if (digits.isEmpty()) return;
            opNo.setLength(0);
            opNo.append(digits.length() > OP_MAX ? digits.substring(digits.length() - OP_MAX) : digits);
            beeper.tick();
            setEditingPin(true);
            render();
        });
    }

    @Override protected void onPause() {
        BarcodeReceiver.unregister(this, barcode);
        barcode = null;
        super.onPause();
    }

    // ---------- tampilan ----------

    private void buildPinRow() {
        int gap = dp(8);
        for (int i = 0; i < PIN_LEN; i++) {
            FrameLayout box = new FrameLayout(this);
            box.setBackgroundResource(R.drawable.bg_pin_box);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.setMarginStart(gap);
            View dot = new View(this);
            dot.setBackgroundResource(R.drawable.bg_dot_ink);
            box.addView(dot, new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER));
            box.setOnClickListener(v -> setEditingPin(true));
            b.pinRow.addView(box, lp);
            pinBoxes[i] = box;
            pinDots[i] = dot;
        }
    }

    private void buildKeypad() {
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"C", "0", "⌫"}};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            if (r > 0) rlp.topMargin = dp(8);
            for (int c = 0; c < 3; c++) {
                String k = rows[r][c];
                View key;
                if (k.equals("⌫")) {
                    ImageView iv = new ImageView(this);
                    iv.setImageResource(R.drawable.ic_back);
                    iv.setImageTintList(ContextCompat.getColorStateList(this, R.color.ink_2));
                    iv.setScaleType(ImageView.ScaleType.CENTER);
                    iv.setContentDescription("Hapus");
                    key = iv;
                } else {
                    TextView tv = new TextView(this);
                    tv.setText(k.equals("C") ? "Hapus" : k);
                    tv.setGravity(Gravity.CENTER);
                    tv.setTextColor(ContextCompat.getColor(this, k.equals("C") ? R.color.brand_700 : R.color.ink));
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, k.equals("C") ? 13 : 20);
                    tv.setTypeface(null, android.graphics.Typeface.BOLD);
                    key = tv;
                }
                key.setBackgroundResource(R.drawable.bg_key);
                key.setClickable(true);
                key.setOnClickListener(v -> onKey(k));
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                if (c > 0) klp.setMarginStart(dp(8));
                row.addView(key, klp);
            }
            b.keypad.addView(row, rlp);
        }
    }

    private void setEditingPin(boolean pinField) {
        editingPin = pinField;
        b.boxOpNo.setSelected(!pinField);
        render();
    }

    private void render() {
        b.tvOpNo.setText(opNo);
        for (int i = 0; i < PIN_LEN; i++) {
            pinDots[i].setVisibility(i < pin.length() ? View.VISIBLE : View.INVISIBLE);
            pinBoxes[i].setSelected(editingPin && i == Math.min(pin.length(), PIN_LEN - 1));
        }
        b.btnLogin.setEnabled(!busy && opNo.length() >= OP_MIN && pin.length() == PIN_LEN);
    }

    // ---------- input ----------

    private void onKey(String k) {
        b.tvError.setVisibility(View.GONE);
        StringBuilder target = editingPin ? pin : opNo;
        int max = editingPin ? PIN_LEN : OP_MAX;
        switch (k) {
            case "C": target.setLength(0); break;
            case "⌫":
                if (target.length() > 0) target.setLength(target.length() - 1);
                else if (editingPin) setEditingPin(false);
                break;
            default:
                if (target.length() < max) target.append(k);
                if (!editingPin && opNo.length() == OP_MAX) setEditingPin(true);
                else if (editingPin && pin.length() == PIN_LEN) { render(); attemptLogin(); return; }
        }
        render();
    }

    /** Keypad fisik handheld (angka, hapus, enter). */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (b != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            int code = e.getKeyCode();
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { onKey(String.valueOf(code - KeyEvent.KEYCODE_0)); return true; }
            if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) { onKey(String.valueOf(code - KeyEvent.KEYCODE_NUMPAD_0)); return true; }
            if (code == KeyEvent.KEYCODE_DEL) { onKey("⌫"); return true; }
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (!editingPin) { if (opNo.length() >= OP_MIN) setEditingPin(true); } else attemptLogin();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void attemptLogin() {
        if (busy || opNo.length() < OP_MIN || pin.length() != PIN_LEN) return;
        busy = true;
        render();
        b.btnLogin.setText("Memeriksa…");
        String no = opNo.toString(), p = pin.toString();
        pin.setLength(0);                                   // PIN tidak disimpan lebih lama dari perlu
        AppExecutors.netThenMain(() -> {
            Gateway gw = Gateways.get(this);
            String deviceKey = SecureStore.get(this).deviceKey();
            if (deviceKey == null) throw new ApiException(403, ApiException.FORBIDDEN, "handheld belum dipasangkan");
            Dtos.LoginResponse res = gw.login(deviceKey, no, p);
            Session.get(this).start(res);
            try { Session.get(this).setHandheld(gw.me().handheld); } catch (ApiException ignored) { /* nama perangkat opsional */ }
            return res;
        }, (res, err) -> {
            busy = false;
            b.btnLogin.setText(R.string.login_button);
            render();
            if (err == null && res != null) {
                beeper.success();
                SyncWorker.runNow(this);                    // antrean yang tertahan 401 dikirim ulang dengan id sama
                openHome();
                return;
            }
            beeper.error();
            String msg;
            if (err instanceof ApiException) {
                ApiException ae = (ApiException) err;
                msg = ae.userMessage();
                if (ae.status == 403) {
                    msg = "Handheld ini tidak dikenal atau dinonaktifkan di Site Gateway. Pasangkan ulang.";
                    b.btnLogin.postDelayed(() -> startActivity(new Intent(this, PairActivity.class)), 1500);
                }
            } else {
                msg = "Login gagal: " + (err == null ? "tidak ada jawaban" : err.getClass().getSimpleName());
            }
            b.tvError.setText(msg);
            b.tvError.setVisibility(View.VISIBLE);
            setEditingPin(true);
        });
    }

    private void openHome() {
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    private static String host(String url) {
        okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(url);
        return u == null ? url : u.host();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}

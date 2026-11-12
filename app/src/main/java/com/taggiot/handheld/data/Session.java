package com.taggiot.handheld.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.security.SecureStore;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Sesi operator yang sedang masuk. Satu operator per perangkat.
 * Token tgh_… disimpan terenkripsi (SecureStore), data operator di SharedPreferences biasa.
 * Sesi berakhir bila: token kedaluwarsa (12 jam dari server), 15 menit tanpa aktivitas, logout,
 * atau server menjawab 401 unauthenticated (expire(): token dibuang, operator masuk lagi lalu
 * antrean dikirim ulang dengan batch_id yang sama).
 */
public final class Session {
    public static final long IDLE_TIMEOUT_MS = 15 * 60 * 1000L;

    public static final String ROLE_VIEWER = "viewer";
    public static final String ROLE_OPERATOR = "operator";
    public static final String ROLE_SUPERVISOR = "supervisor";

    private static Session instance;
    private final SharedPreferences sp;
    private final SecureStore secure;

    private Session(Context ctx) {
        Context app = ctx.getApplicationContext();
        sp = app.getSharedPreferences("taggiot_session", Context.MODE_PRIVATE);
        secure = SecureStore.get(app);
    }

    public static synchronized Session get(Context ctx) {
        if (instance == null) instance = new Session(ctx);
        return instance;
    }

    public void start(Dtos.LoginResponse res) {
        sp.edit().putString("op_no", res.operator.operatorNo)
                .putString("name", res.operator.name == null ? "" : res.operator.name)
                .putString("role", res.operator.role == null ? ROLE_VIEWER : res.operator.role)
                .putLong("expires_at", parseIso(res.expiresAt))
                .putLong("last_active", System.currentTimeMillis())
                .putBoolean("expired", false)
                .apply();
        secure.setToken(res.token);
    }

    public void setHandheld(Dtos.Handheld h) {
        if (h == null) return;
        sp.edit().putString("hh_name", h.name == null ? "" : h.name).putString("hh_id", h.id == null ? "" : h.id).apply();
    }

    public boolean isActive() {
        if (sp.getString("op_no", null) == null || token() == null) return false;
        long now = System.currentTimeMillis();
        if (sp.getLong("expires_at", 0) <= now) return false;
        return now - sp.getLong("last_active", 0) < IDLE_TIMEOUT_MS;
    }

    public void touch() { sp.edit().putLong("last_active", System.currentTimeMillis()).apply(); }

    /** Token ditolak server (401): buang token, simpan nomor operator untuk prefill login. */
    public void expire() {
        secure.clearToken();
        sp.edit().putBoolean("expired", true).apply();
    }

    public boolean wasExpired() { return sp.getBoolean("expired", false); }

    public void end() {
        secure.clearToken();
        String hhName = sp.getString("hh_name", null), hhId = sp.getString("hh_id", null);
        sp.edit().clear().apply();
        if (hhName != null) sp.edit().putString("hh_name", hhName).putString("hh_id", hhId).apply();
    }

    public String opNo() { return sp.getString("op_no", ""); }
    public String displayName() { return sp.getString("name", ""); }
    public String role() { return sp.getString("role", ROLE_VIEWER); }
    public String token() { return secure.token(); }
    public long expiresAt() { return sp.getLong("expires_at", 0); }
    public String handheldName() { return sp.getString("hh_name", ""); }

    public String initials() {
        String n = displayName().trim();
        if (n.isEmpty()) return "?";
        String[] p = n.split("\\s+");
        return (p[0].substring(0, 1) + (p.length > 1 ? p[1].substring(0, 1) : "")).toUpperCase();
    }

    public String firstName() {
        String n = displayName().trim();
        return n.isEmpty() ? "" : n.split("\\s+")[0];
    }

    public static int rank(String role) {
        if (ROLE_SUPERVISOR.equals(role)) return 2;
        if (ROLE_OPERATOR.equals(role)) return 1;
        return 0;
    }

    /** operator atau supervisor: boleh menghitung (counts.write). */
    public boolean canCount() { return rank(role()) >= 1; }

    /** supervisor: review, resolve, reopen, close, cancel, bind. */
    public boolean isSupervisor() { return rank(role()) >= 2; }

    public static String roleLabel(String role) {
        switch (role == null ? "" : role) {
            case ROLE_SUPERVISOR: return "Supervisor";
            case ROLE_OPERATOR: return "Operator";
            default: return "Viewer";
        }
    }

    static long parseIso(String iso) {
        if (iso == null) return System.currentTimeMillis() + 12 * 3600_000L;
        try { return Instant.parse(iso).toEpochMilli(); }
        catch (DateTimeParseException e) { return System.currentTimeMillis() + 12 * 3600_000L; }
    }
}

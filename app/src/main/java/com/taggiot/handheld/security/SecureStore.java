package com.taggiot.handheld.security;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Rahasia perangkat: device key (hasil pairing) dan token sesi, disimpan terenkripsi lewat
 * {@link KeystoreCrypto}. Nilai dekripsi di-cache di memori proses. Tidak pernah ditulis ke log.
 */
public final class SecureStore {
    private static SecureStore instance;
    private final SharedPreferences sp;
    private String deviceKey, token;
    private boolean deviceKeyLoaded, tokenLoaded;

    private SecureStore(Context ctx) { sp = ctx.getApplicationContext().getSharedPreferences("taggiot_secure", Context.MODE_PRIVATE); }

    public static synchronized SecureStore get(Context ctx) {
        if (instance == null) instance = new SecureStore(ctx);
        return instance;
    }

    // ---------- device key ----------

    public synchronized boolean hasDeviceKey() { return deviceKey() != null; }

    public synchronized String deviceKey() {
        if (!deviceKeyLoaded) { deviceKey = read("device_key"); deviceKeyLoaded = true; }
        return deviceKey;
    }

    /** @throws IllegalStateException bila Keystore tidak bisa dipakai (device key tidak boleh disimpan polos). */
    public synchronized void setDeviceKey(String key) {
        write("device_key", key);
        deviceKey = key; deviceKeyLoaded = true;
    }

    public synchronized void clearDeviceKey() {
        sp.edit().remove("device_key").apply();
        deviceKey = null; deviceKeyLoaded = true;
    }

    public String pairedAt() { return sp.getString("paired_at", null); }
    public void setPairedAt(String iso) { sp.edit().putString("paired_at", iso).apply(); }

    // ---------- token ----------

    public synchronized String token() {
        if (!tokenLoaded) { token = read("token"); tokenLoaded = true; }
        return token;
    }

    public synchronized void setToken(String t) {
        if (t == null) { clearToken(); return; }
        try { write("token", t); } catch (IllegalStateException e) { sp.edit().remove("token").apply(); }   // tetap di memori saja
        token = t; tokenLoaded = true;
    }

    public synchronized void clearToken() {
        sp.edit().remove("token").apply();
        token = null; tokenLoaded = true;
    }

    // ---------- helper ----------

    private String read(String k) {
        String enc = sp.getString(k, null);
        if (enc == null) return null;
        try { return KeystoreCrypto.decrypt(enc); }
        catch (Exception e) { sp.edit().remove(k).apply(); return null; }   // kunci Keystore hilang → anggap tidak ada
    }

    private void write(String k, String v) {
        try { sp.edit().putString(k, KeystoreCrypto.encrypt(v)).apply(); }
        catch (Exception e) { throw new IllegalStateException("Android Keystore tidak tersedia", e); }
    }
}

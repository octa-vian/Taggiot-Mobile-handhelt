package com.taggiot.handheld.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.taggiot.handheld.BuildConfig;
import com.taggiot.handheld.reader.BarcodeReceiver;
import com.taggiot.handheld.reader.TriggerKeys;

import java.util.Set;
import java.util.UUID;

/** Setelan lokal perangkat (bukan rahasia; rahasia ada di security/SecureStore). */
public final class Prefs {
    private static Prefs instance;
    private final SharedPreferences sp;

    private Prefs(Context ctx) { sp = ctx.getApplicationContext().getSharedPreferences("taggiot_handheld", Context.MODE_PRIVATE); }

    public static synchronized Prefs get(Context ctx) {
        if (instance == null) instance = new Prefs(ctx);
        return instance;
    }

    /** Label lokal perangkat untuk layar (identitas resmi handheld adalah device key hasil pairing). */
    public String deviceId() {
        String id = sp.getString("device_id", null);
        if (id == null) {
            id = "hh-" + UUID.randomUUID().toString().substring(0, 8);
            sp.edit().putString("device_id", id).apply();
        }
        return id;
    }

    /** Base URL Site Gateway, selalu diakhiri "/" dan tanpa sufiks api/v1/handheld. */
    public String siteGatewayUrl() { return sp.getString("sgw_url", BuildConfig.DEFAULT_SITE_GATEWAY_URL); }

    public void setSiteGatewayUrl(String v) { sp.edit().putString("sgw_url", normalizeUrl(v)).apply(); }

    public static String normalizeUrl(String v) {
        String u = v == null ? "" : v.trim();
        if (!u.endsWith("/")) u += "/";
        for (String suffix : new String[]{"api/v1/handheld/", "api/v1/", "api/"}) {
            if (u.toLowerCase().endsWith(suffix)) { u = u.substring(0, u.length() - suffix.length()); break; }
        }
        return u;
    }

    /** Mode demo: Site Gateway tiruan di dalam proses, tanpa jaringan. */
    public boolean demoMode() { return sp.getBoolean("demo_mode", true); }
    public void setDemoMode(boolean v) { sp.edit().putBoolean("demo_mode", v).apply(); }

    /**
     * Site GMF: tag alat diikat di aplikasi GMF, jadi bind/replace/encode disembunyikan di handheld
     * dan handheld tidak pernah mengubah status tag.
     */
    public boolean gmfSite() { return sp.getBoolean("gmf_site", true); }
    public void setGmfSite(boolean v) { sp.edit().putBoolean("gmf_site", v).apply(); }

    public String siteName() { return sp.getString("site_name", "GMF Tool Crib"); }

    /** Area / kode laci terakhir yang dipakai saat menghitung. */
    public String lastArea() { return sp.getString("last_area", ""); }
    public void setLastArea(String v) { sp.edit().putString("last_area", v == null ? "" : v).apply(); }

    /** "auto" | "seuic" | "fake" */
    public String readerMode() { return sp.getString("reader_mode", "auto"); }
    public void setReaderMode(String v) { sp.edit().putString("reader_mode", v).apply(); }

    public int readPower() { return sp.getInt("read_power", 27); }
    public void setReadPower(int v) { sp.edit().putInt("read_power", v).apply(); }

    public int writePower() { return sp.getInt("write_power", 10); }
    public void setWritePower(int v) { sp.edit().putInt("write_power", v).apply(); }

    /** Abaikan bacaan lebih lemah dari ini di Inventory (dBm). */
    public int minRssi() { return sp.getInt("min_rssi", -70); }
    public void setMinRssi(int v) { sp.edit().putInt("min_rssi", v).apply(); }

    public Set<Integer> triggerKeys() { return TriggerKeys.fromCsv(sp.getString("trigger_keys", null)); }
    public void setTriggerKeys(Set<Integer> keys) { sp.edit().putString("trigger_keys", TriggerKeys.toCsv(keys)).apply(); }

    public String barcodeAction() { return sp.getString("bc_action", BarcodeReceiver.DEFAULT_ACTION); }
    public String barcodeExtra() { return sp.getString("bc_extra", BarcodeReceiver.DEFAULT_EXTRA); }
    public void setBarcode(String action, String extra) { sp.edit().putString("bc_action", action).putString("bc_extra", extra).apply(); }

    /** GS1 company prefix untuk encode EPC (hanya site non-GMF). Default = contoh dokumen GS1, WAJIB diganti. */
    public String gs1CompanyPrefix() { return sp.getString("gs1_prefix", "0614141"); }
    public void setGs1CompanyPrefix(String v) { sp.edit().putString("gs1_prefix", v).apply(); }

    public boolean soundEnabled() { return sp.getBoolean("sound", true); }
    public void setSoundEnabled(boolean v) { sp.edit().putBoolean("sound", v).apply(); }

    public long lastSyncAt() { return sp.getLong("last_sync", 0); }
    public void setLastSyncAt(long v) { sp.edit().putLong("last_sync", v).apply(); }

    /** Nomor seri berikutnya untuk encode (site non-GMF). */
    public long peekSerial(String sku) { return sp.getLong("serial_" + sku, 90000); }
    public void advanceSerial(String sku) { sp.edit().putLong("serial_" + sku, peekSerial(sku) + 1).apply(); }
}

package com.taggiot.handheld.net;

import android.content.Context;

import com.taggiot.handheld.data.Prefs;
import com.taggiot.handheld.data.Session;

/** Memilih gateway aktif: DemoGateway (mode demo, tanpa jaringan) atau HttpGateway (Site Gateway nyata). */
public final class Gateways {
    private static DemoGateway demo;
    private static HttpGateway http;

    private Gateways() {}

    public static synchronized Gateway get(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (Prefs.get(app).demoMode()) return demo(app);
        if (http == null) http = new HttpGateway(app);
        return http;
    }

    public static synchronized DemoGateway demo(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (demo == null) demo = new DemoGateway(() -> Session.get(app).token());
        return demo;
    }

    /** Dipanggil saat URL / mode demo diubah di Pengaturan. */
    public static synchronized void reset() { http = null; ApiClient.reset(); }
}

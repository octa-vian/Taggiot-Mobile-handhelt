package com.taggiot.handheld.reader;

import android.content.Context;
import android.util.Log;

import com.taggiot.handheld.BuildConfig;

/**
 * Mengambil alih tombol scan fisik dari layanan scanner barcode vendor selama layar reader UHF aktif,
 * supaya menekan shutter hanya memicu reader UHF dan scanner barcode tidak ikut menyala.
 * Di perangkat tanpa dukungan vendor, implementasinya tidak melakukan apa-apa.
 */
public interface ScanKeyClaim {

    /** Dipanggil di main thread. */
    interface Listener {
        void onDown();
        void onUp();
    }

    void claim(Listener listener);
    void release();

    ScanKeyClaim NONE = new ScanKeyClaim() {
        @Override public void claim(Listener listener) {}
        @Override public void release() {}
    };

    static ScanKeyClaim create(Context ctx) {
        if (!BuildConfig.HAS_SEUIC_SDK || !ReaderManager.isSeuicDevice()) return NONE;
        try {
            return (ScanKeyClaim) Class.forName("com.taggiot.handheld.reader.seuic.SeuicScanKey")
                    .getConstructor(Context.class).newInstance(ctx.getApplicationContext());
        } catch (Throwable t) {
            Log.w("ScanKeyClaim", "ScanKey SEUIC tidak tersedia, scanner barcode tetap ikut tombol shutter", t);
            return NONE;
        }
    }
}

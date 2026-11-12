package com.taggiot.handheld.reader.seuic;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.seuic.scankey.IKeyEventCallback;
import com.seuic.scankey.ScanKeyService;
import com.taggiot.handheld.reader.ScanKeyClaim;

/**
 * Klaim tombol shutter lewat ScanKey SEUIC (system library com.seuic.scankey, "ScanKey接口使用指南" v1.0).
 *
 * Menurut manual: aplikasi yang terakhir mendaftarkan callback untuk sebuah scancode adalah satu-satunya
 * yang menerima tombol itu, sehingga scanner barcode SEUIC tidak lagi menyala. Setelah unregister, tombol
 * kembali ke pendaftar sebelumnya (scanner barcode). Cookie berisi SCANCODE hardware, bukan KeyEvent.
 */
public class SeuicScanKey implements ScanKeyClaim {

    private static final String TAG = "SeuicScanKey";
    /** Shutter CRUISE2: scancode 250 (KeyEvent F12 = 142). */
    private static final String SHUTTER_SCANCODES = "250";

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;

    private final IKeyEventCallback callback = new IKeyEventCallback.Stub() {
        @Override public void onKeyDown(int keyCode) {
            main.post(() -> { Listener l = listener; if (l != null) l.onDown(); });
        }
        @Override public void onKeyUp(int keyCode) {
            main.post(() -> { Listener l = listener; if (l != null) l.onUp(); });
        }
    };

    public SeuicScanKey(Context ctx) {}

    @Override public void claim(Listener l) {
        listener = l;
        try {
            ScanKeyService.getInstance().registerCallback(callback, SHUTTER_SCANCODES);
            Log.i(TAG, "shutter diklaim (scancode " + SHUTTER_SCANCODES + ")");
        } catch (Throwable t) {
            Log.w(TAG, "registerCallback gagal", t);
        }
    }

    @Override public void release() {
        listener = null;
        try {
            ScanKeyService.getInstance().unregisterCallback(callback);
        } catch (Throwable t) {
            Log.w(TAG, "unregisterCallback gagal", t);
        }
    }
}

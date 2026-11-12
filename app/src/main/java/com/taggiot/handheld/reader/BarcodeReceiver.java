package com.taggiot.handheld.reader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.taggiot.handheld.data.Prefs;

/**
 * Menerima hasil scan barcode dari scanner bawaan handheld lewat broadcast.
 * Scanner SEUIC bisa diatur (aplikasi Scan Tool/ScannerSetting di perangkat) untuk mengirim
 * broadcast; action dan nama extra bisa diubah di Pengaturan aplikasi ini.
 * Fallback: mode keyboard wedge — kolom isian yang fokus menerima teks langsung.
 */
public final class BarcodeReceiver extends BroadcastReceiver {

    public interface Listener { void onBarcode(String data); }

    /** VERIFY di perangkat: nilai default broadcast scanner SEUIC. */
    public static final String DEFAULT_ACTION = "com.android.server.scannerservice.broadcast";
    public static final String DEFAULT_EXTRA = "scannerdata";

    private final Listener listener;
    private final String extraKey;

    private BarcodeReceiver(Listener l, String extraKey) {
        this.listener = l;
        this.extraKey = extraKey;
    }

    public static BarcodeReceiver register(Context ctx, Listener l) {
        Prefs p = Prefs.get(ctx);
        BarcodeReceiver r = new BarcodeReceiver(l, p.barcodeExtra());
        IntentFilter f = new IntentFilter(p.barcodeAction());
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED);
        return r;
    }

    public static void unregister(Context ctx, BarcodeReceiver r) {
        if (r == null) return;
        try { ctx.unregisterReceiver(r); } catch (IllegalArgumentException ignored) {}
    }

    @Override public void onReceive(Context context, Intent intent) {
        String data = intent.getStringExtra(extraKey);
        if (data == null) {
            byte[] raw = intent.getByteArrayExtra(extraKey);
            if (raw != null) data = new String(raw);
        }
        if (data != null) listener.onBarcode(data.trim());
    }
}

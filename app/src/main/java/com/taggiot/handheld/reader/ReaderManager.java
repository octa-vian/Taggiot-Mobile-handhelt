package com.taggiot.handheld.reader;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.taggiot.handheld.BuildConfig;
import com.taggiot.handheld.data.Prefs;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pemilik tunggal reader UHF di aplikasi.
 * - Memilih implementasi: SEUIC (bila SDK ada & perangkat SEUIC) atau simulasi.
 * - Semua operasi yang memblok dijalankan di satu thread "reader-io" agar tidak bentrok.
 * - Satu layar memegang reader pada satu waktu (acquire/release).
 */
public final class ReaderManager {

    private static final String TAG = "ReaderManager";
    private static final String SEUIC_CLASS = "com.taggiot.handheld.reader.seuic.SeuicUhfReader";

    private static ReaderManager instance;

    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "reader-io"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private UhfReader reader;
    private Object owner;
    private Collection<String> fakePopulation = Collections.emptyList();

    public interface Callback<T> { void onResult(T value); }

    private ReaderManager(Context ctx) { this.app = ctx.getApplicationContext(); }

    public static synchronized ReaderManager get(Context ctx) {
        if (instance == null) instance = new ReaderManager(ctx);
        return instance;
    }

    /** Populasi tag untuk reader simulasi (EPC dari master data contoh). */
    public synchronized void setFakePopulation(Collection<String> epcs) {
        this.fakePopulation = epcs;
        if (reader instanceof FakeUhfReader) ((FakeUhfReader) reader).setPopulation(epcs);
    }

    public static boolean isSeuicDevice() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.MODEL).toUpperCase();
        return m.contains("SEUIC") || m.contains("AUTOID") || m.contains("CRUISE");
    }

    /** Reader aktif (dibuat malas). */
    public synchronized UhfReader reader() {
        if (reader == null) reader = create();
        return reader;
    }

    private UhfReader create() {
        String mode = Prefs.get(app).readerMode();   // "auto" | "seuic" | "fake"
        boolean wantSeuic = "seuic".equals(mode) || ("auto".equals(mode) && isSeuicDevice());
        if (wantSeuic && BuildConfig.HAS_SEUIC_SDK) {
            try {
                return (UhfReader) Class.forName(SEUIC_CLASS).getConstructor(Context.class).newInstance(app);
            } catch (Throwable t) {
                Log.e(TAG, "Gagal memuat adaptor SEUIC, pakai simulasi", t);
            }
        } else if (wantSeuic) {
            Log.w(TAG, "Perangkat SEUIC terdeteksi tetapi app/libs/uhf.jar belum ada — memakai simulasi");
        }
        return new FakeUhfReader(fakePopulation);
    }

    /** Ganti implementasi (dipakai Pengaturan setelah mode reader diubah). */
    public synchronized void reset() {
        UhfReader old = reader;
        reader = null;
        owner = null;
        if (old != null) io.execute(old::close);
    }

    public boolean isSimulated() { return reader() instanceof FakeUhfReader; }

    /** Pegang reader untuk layar ini dan buka bila perlu. Callback di main thread. */
    public void acquire(Object who, Callback<Boolean> cb) {
        UhfReader r;
        synchronized (this) {
            if (owner != null && owner != who) {
                // Layar lama belum melepas (mis. transisi activity) — ambil alih dengan aman.
                r = reader();
                io.execute(r::stopInventory);
            }
            owner = who;
            r = reader();
        }
        final UhfReader fr = r;
        io.execute(() -> {
            // Selalu open(): driver vendor bisa menutup modul sendiri (SEUIC menutupnya saat layar mati).
            boolean ok = fr.open();
            if (ok) fr.setPower(Prefs.get(app).readPower());
            main.post(() -> cb.onResult(ok));
        });
    }

    /** Lepas reader: hentikan inventory. Modul tetap terbuka agar layar berikutnya cepat. */
    public void release(Object who) {
        synchronized (this) {
            if (owner != who) return;
            owner = null;
        }
        UhfReader r = reader();
        io.execute(r::stopInventory);
    }

    /** Tutup modul (aplikasi ke latar belakang lama / logout) untuk hemat baterai. */
    public void closeModule() {
        UhfReader r = reader();
        io.execute(r::close);
    }

    public <T> void run(java.util.concurrent.Callable<T> task, Callback<T> cb) {
        io.execute(() -> {
            T v;
            try { v = task.call(); } catch (Exception e) { Log.e(TAG, "operasi reader gagal", e); v = null; }
            final T out = v;
            if (cb != null) main.post(() -> cb.onResult(out));
        });
    }

    public void runIo(Runnable r) { io.execute(r); }
}

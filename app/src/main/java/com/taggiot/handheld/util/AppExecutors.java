package com.taggiot.handheld.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Thread database (satu, berurutan), thread jaringan (Site Gateway), dan jalur balik ke main thread. */
public final class AppExecutors {
    private static final ExecutorService DB = Executors.newSingleThreadExecutor(r -> new Thread(r, "db"));
    private static final ExecutorService NET = Executors.newFixedThreadPool(2, r -> new Thread(r, "net"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppExecutors() {}

    public interface Task<T> { T run() throws Exception; }
    public interface Done<T> { void accept(T value); }
    /** Hasil ATAU kesalahan (salah satunya null). */
    public interface Result<T> { void accept(T value, Exception error); }

    public static void db(Runnable r) { DB.execute(r); }

    public static void net(Runnable r) { NET.execute(r); }

    /** Jalankan di thread db, hasil dikirim ke main thread. Error → value null + log (tanpa data). */
    public static <T> void dbThenMain(Task<T> task, Done<T> done) {
        DB.execute(() -> {
            T v = null;
            try { v = task.run(); } catch (Exception e) { android.util.Log.e("AppExecutors", "task db gagal: " + e.getClass().getSimpleName()); }
            final T out = v;
            MAIN.post(() -> done.accept(out));
        });
    }

    /** Jalankan panggilan Site Gateway di thread jaringan; hasil atau exception dikirim ke main thread. */
    public static <T> void netThenMain(Task<T> task, Result<T> done) {
        NET.execute(() -> {
            T v = null; Exception err = null;
            try { v = task.run(); } catch (Exception e) { err = e; }
            final T out = v; final Exception e = err;
            MAIN.post(() -> done.accept(out, e));
        });
    }

    public static void main(Runnable r) { MAIN.post(r); }
}

package com.taggiot.handheld.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.taggiot.handheld.domain.Epc;
import com.taggiot.handheld.net.ApiException;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.util.AppExecutors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache jawaban finder per layar: EPC baru dipecahkan lewat POST /finder/lookup (≤ 200 per panggilan),
 * satu permintaan berjalan pada satu waktu, dan diam 5 detik setelah gagal jaringan (data tetap tampil sebagai EPC).
 */
public final class FinderCache {

    public interface Listener {
        void onResolved();
        void onError(ApiException e);
    }

    private static final int LOOKUP_MAX = 200;
    private static final long BACKOFF_MS = 5000;

    private final Context app;
    private final Listener listener;
    private final Map<String, Dtos.FinderItem> cache = new ConcurrentHashMap<>();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean inFlight;
    private volatile long quietUntil;

    public FinderCache(Context ctx, Listener listener) { this.app = ctx.getApplicationContext(); this.listener = listener; }

    public Dtos.FinderItem get(String epc) { return cache.get(epc); }

    public void put(Dtos.FinderItem f) { if (f != null && f.epc != null) cache.put(f.epc, f); }

    /** Minta info untuk EPC yang belum diketahui. Aman dipanggil sering. */
    public void request(Collection<String> epcs) {
        for (String e : epcs) if (!cache.containsKey(e)) queued.add(e);
        pump();
    }

    private void pump() {
        if (inFlight || queued.isEmpty() || System.currentTimeMillis() < quietUntil) return;
        List<String> batch = new ArrayList<>(LOOKUP_MAX);
        for (String e : queued) {
            if (Epc.isValid(e)) batch.add(e);
            if (batch.size() >= LOOKUP_MAX) break;
        }
        queued.removeAll(batch);
        if (batch.isEmpty()) { queued.clear(); return; }
        inFlight = true;
        AppExecutors.netThenMain(() -> Gateways.get(app).finderLookup(batch), (items, err) -> {
            inFlight = false;
            if (err == null && items != null) {
                for (Dtos.FinderItem f : items) put(f);
                listener.onResolved();
                pump();
                return;
            }
            queued.addAll(batch);
            quietUntil = System.currentTimeMillis() + BACKOFF_MS;
            main.postDelayed(this::pump, BACKOFF_MS + 50);
            if (err instanceof ApiException) listener.onError((ApiException) err);
        });
    }

    public void stop() { main.removeCallbacksAndMessages(null); queued.clear(); }
}

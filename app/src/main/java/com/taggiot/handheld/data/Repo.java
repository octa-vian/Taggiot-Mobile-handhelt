package com.taggiot.handheld.data;

import android.content.Context;

import com.google.gson.JsonObject;
import com.taggiot.handheld.data.db.AppDao;
import com.taggiot.handheld.data.db.AppDatabase;
import com.taggiot.handheld.data.db.CountReadEntity;
import com.taggiot.handheld.data.db.CountSessionEntity;
import com.taggiot.handheld.data.db.OutboxEntity;
import com.taggiot.handheld.domain.Epc;
import com.taggiot.handheld.net.Json;
import com.taggiot.handheld.net.SiteGatewayApi;
import com.taggiot.handheld.net.dto.Dtos;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Satu pintu ke data lokal. Semua method MEMBLOK — panggil dari AppExecutors.db().
 * Pola: hasil baca ditulis ke count_read + outbox dulu, SyncWorker mengirim menyusul (offline-first).
 */
public final class Repo {

    /** Batas server: 1.000 EPC per batch reads. Dikirim lebih awal tiap FLUSH_AT EPC baru. */
    public static final int BATCH_MAX = 1000;
    public static final int FLUSH_AT = 500;

    private static Repo instance;
    private final Context app;
    private final AppDao dao;

    private Repo(Context ctx) {
        app = ctx.getApplicationContext();
        dao = AppDatabase.get(app).dao();
    }

    public static synchronized Repo get(Context ctx) {
        if (instance == null) instance = new Repo(ctx);
        return instance;
    }

    public AppDao dao() { return dao; }

    // =============== hasil baca sesi hitung ===============

    /** Simpan EPC baru untuk sesi (dedup per sesi). @return jumlah EPC yang benar-benar baru. */
    public int addReads(String sessionId, String area, Collection<String> epcs, long seenAt) {
        int fresh = 0;
        for (String raw : epcs) {
            String e = Epc.normalize(raw);
            if (e == null) continue;
            CountReadEntity r = new CountReadEntity();
            r.sessionId = sessionId; r.epc = e; r.area = area == null ? "" : area; r.seenAt = seenAt;
            if (dao.insertRead(r) != -1) fresh++;
        }
        return fresh;
    }

    public int unbatchedCount(String sessionId) { return dao.unbatchedCount(sessionId); }

    public int distinctReads(String sessionId) { return dao.distinctReads(sessionId); }

    public List<String> readEpcs(String sessionId) { return dao.readEpcs(sessionId); }

    /**
     * Bungkus EPC yang belum terkirim menjadi batch outbox (≤ 1.000 per batch, satu area per batch,
     * batch_id UUID baru per batch). @return jumlah batch yang dibuat.
     */
    public int flushReads(String sessionId) {
        List<CountReadEntity> rows = dao.unbatchedReads(sessionId);
        if (rows.isEmpty()) return 0;
        Map<String, List<CountReadEntity>> byArea = new LinkedHashMap<>();
        for (CountReadEntity r : rows) byArea.computeIfAbsent(r.area, k -> new ArrayList<>()).add(r);
        int batches = 0;
        for (Map.Entry<String, List<CountReadEntity>> en : byArea.entrySet()) {
            for (List<CountReadEntity> chunk : Epc.chunk(en.getValue(), BATCH_MAX)) {
                Dtos.ReadsRequest req = new Dtos.ReadsRequest();
                req.id = sessionId;
                req.batchId = UUID.randomUUID().toString();
                req.area = en.getKey().isEmpty() ? null : en.getKey();
                req.reads = new ArrayList<>(chunk.size());
                List<Long> ids = new ArrayList<>(chunk.size());
                for (CountReadEntity r : chunk) {
                    Dtos.Read rd = new Dtos.Read();
                    rd.epc = r.epc; rd.seenAt = iso(r.seenAt);
                    req.reads.add(rd);
                    ids.add(r.id);
                }
                CountSessionEntity s = dao.session(sessionId);
                String title = (s == null ? "Hitung" : s.number) + (req.area == null ? "" : " · " + req.area);
                enqueue(req.batchId, OutboxEntity.KIND_READS, title, chunk.size() + " EPC", chunk.size(),
                        SiteGatewayApi.BASE + "counts/reads", null, sessionId, req);
                dao.markBatched(ids, req.batchId);
                batches++;
            }
        }
        return batches;
    }

    public int pendingBatches(String sessionId) { return dao.pendingBatches(sessionId); }

    public int failedBatches(String sessionId) { return dao.failedBatches(sessionId); }

    // =============== cache sesi hitung ===============

    public void cacheSession(Dtos.CountSession s) {
        if (s == null || s.id == null) return;
        dao.upsertSession(toEntity(s));
    }

    public void cacheSessions(List<Dtos.CountSession> list, boolean replaceAll) {
        List<CountSessionEntity> out = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Dtos.CountSession s : list) { if (s != null && s.id != null) { out.add(toEntity(s)); ids.add(s.id); } }
        if (replaceAll) { if (ids.isEmpty()) dao.clearSessions(); else dao.deleteSessionsExcept(ids); }
        if (!out.isEmpty()) dao.upsertSessions(out);
    }

    public Dtos.CountSession cachedSession(String id) {
        CountSessionEntity e = dao.session(id);
        return e == null ? null : parse(e);
    }

    public static Dtos.CountSession parse(CountSessionEntity e) {
        try { return Json.gson().fromJson(e.json, Dtos.CountSession.class); }
        catch (RuntimeException ex) { return null; }
    }

    /** Perbarui tally sesi dari jawaban counts/reads. */
    public void updateTally(String id, Dtos.Tally tally) {
        CountSessionEntity e = dao.session(id);
        if (e == null || tally == null) return;
        Dtos.CountSession s = parse(e);
        if (s == null) return;
        s.tally = tally;
        dao.upsertSession(toEntity(s));
    }

    private static CountSessionEntity toEntity(Dtos.CountSession s) {
        CountSessionEntity e = new CountSessionEntity();
        e.id = s.id;
        e.number = s.number == null ? "" : s.number;
        e.kind = s.kind == null ? "inventory" : s.kind;
        e.status = s.status == null ? "open" : s.status;
        e.area = s.area;
        e.createdBy = s.createdBy;
        e.json = Json.gson().toJson(s);
        e.updatedAt = System.currentTimeMillis();
        return e;
    }

    // =============== bind (site non-GMF) ===============

    public String enqueueBind(Dtos.BindRequest req, String title) {
        String key = "bind-" + UUID.randomUUID();
        enqueue(key, OutboxEntity.KIND_BIND, title, "SKU " + req.sku + (req.serial == null ? "" : " · S/N " + req.serial), 1,
                SiteGatewayApi.BASE + "items/bind", key, null, req);
        return key;
    }

    // =============== outbox ===============

    private void enqueue(String id, String kind, String title, String subtitle, int rows, String endpoint,
                         String idempotencyKey, String countId, Object payload) {
        OutboxEntity o = new OutboxEntity();
        o.id = id; o.kind = kind; o.title = title; o.subtitle = subtitle; o.rows = rows; o.endpoint = endpoint;
        o.idempotencyKey = idempotencyKey; o.countId = countId;
        o.payloadJson = Json.gson().toJson(payload);
        o.createdAt = System.currentTimeMillis();
        dao.insertOutbox(o);
    }

    public JsonObject payload(OutboxEntity o) { return Json.gson().fromJson(o.payloadJson, JsonObject.class); }

    public void purge() {
        long now = System.currentTimeMillis();
        dao.purgeSent(now - 7L * 24 * 3600 * 1000);
        dao.purgeReads(now - 30L * 24 * 3600 * 1000);
    }

    // =============== helper ===============

    /** RFC 3339 UTC dengan milidetik (server memvalidasi tetapi memakai waktunya sendiri). */
    public static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }
}

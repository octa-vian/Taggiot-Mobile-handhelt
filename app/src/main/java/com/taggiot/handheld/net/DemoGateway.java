package com.taggiot.handheld.net;

import com.google.gson.JsonObject;
import com.taggiot.handheld.domain.Epc;
import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.util.EpcCodec;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Site Gateway tiruan di dalam proses, untuk mode demo (emulator, demo tanpa jaringan, unit test).
 * Mengikuti aturan API yang sama dengan server: pairing sekali pakai, PIN 6 digit, kunci 15 menit
 * setelah 5 PIN salah, token 12 jam, peran viewer/operator/supervisor, batch_id idempoten,
 * status sesi open → review → closed, 409 count_state, nomor SO-/IC-yyyymmdd-nnn hari Asia/Jakarta.
 *
 * DATA CONTOH: tool crib GMF dengan laci D1–D4 (bukan data GMF sungguhan).
 * Murni Java (tanpa API Android) supaya bisa diuji unit.
 */
public final class DemoGateway implements Gateway {

    public interface TokenSource { String token(); }

    public static final String PAIRING_CODE = "DEMO1234";
    public static final String DEMO_PIN = "123456";
    public static final String OP_OPERATOR = "1042";
    public static final String OP_SUPERVISOR = "2001";
    public static final String OP_VIEWER = "3003";
    public static final String UNKNOWN_EPC = "E28068940000501A2B3C7A31";
    public static final long TOKEN_TTL_MS = 12L * 3600 * 1000;
    public static final int LOCK_SECONDS = 15 * 60;

    private static final String PREFIX = "0614141";   // contoh GS1, bukan prefix GMF

    public static final class Tool {
        public final String code, name, drawer, epc;
        public String tagStatus;
        Tool(String code, String name, String drawer, String epc, String tagStatus) {
            this.code = code; this.name = name; this.drawer = drawer; this.epc = epc; this.tagStatus = tagStatus;
        }
    }

    private static final class Op {
        final String no, name, role;
        int failed; long lockedUntil; boolean disabled;
        Op(String no, String name, String role) { this.no = no; this.name = name; this.role = role; }
    }

    private static final class Tok { final Op op; final long expiresAt; Tok(Op op, long exp) { this.op = op; this.expiresAt = exp; } }

    private static final class Sess {
        final Dtos.CountSession dto = new Dtos.CountSession();
        final Set<String> expected = new LinkedHashSet<>();          // EPC (opname)
        final LinkedHashMap<String, String> readArea = new LinkedHashMap<>();   // EPC → area
        final Map<String, Dtos.ReadsResponse> batches = new HashMap<>();
        final List<Dtos.Difference> diffs = new ArrayList<>();
        String creatorNo;
    }

    private final LongSupplier clock;
    private final TokenSource tokens;
    private final Map<String, Tool> toolsByEpc = new LinkedHashMap<>();
    private final Map<String, Tool> toolsByCode = new LinkedHashMap<>();
    private final Map<String, Op> ops = new HashMap<>();
    private final Map<String, Tok> live = new HashMap<>();
    private final Map<String, Sess> sessions = new LinkedHashMap<>();
    private final Map<String, Integer> seqPerDay = new HashMap<>();
    private final Set<String> deviceKeys = new LinkedHashSet<>();
    private final Set<String> revoked = new LinkedHashSet<>();
    private boolean pairingUsed;

    public DemoGateway(TokenSource tokens) { this(tokens, System::currentTimeMillis); }

    public DemoGateway(TokenSource tokens, LongSupplier clock) {
        this.tokens = tokens;
        this.clock = clock;
        seed();
    }

    // ================= data contoh =================

    private void seed() {
        String[][] t = {
                {"GMF-TL-0001", "Torque Wrench 20–100 Nm", "D1", "active"},
                {"GMF-TL-0002", "Torque Wrench 60–300 Nm", "D1", "active"},
                {"GMF-TL-0003", "Borescope Kit", "D1", "active"},
                {"GMF-TL-0004", "Hydraulic Jack 12 T", "D1", "active"},
                {"GMF-TL-0005", "Rivet Gun Pneumatic", "D1", "released"},
                {"GMF-TL-0006", "Micrometer 0–25 mm", "D2", "active"},
                {"GMF-TL-0007", "Dial Indicator", "D2", "active"},
                {"GMF-TL-0008", "Safety Wire Pliers", "D2", "active"},
                {"GMF-TL-0009", "Crimping Tool", "D2", "active"},
                {"GMF-TL-0010", "Bearing Puller Set", "D3", "active"},
                {"GMF-TL-0011", "Feeler Gauge Set", "D3", "active"},
                {"GMF-TL-0012", "Torque Screwdriver 1–6 Nm", "D3", "active"},
                {"GMF-TL-0013", "Multimeter", "D4", "active"},
                {"GMF-TL-0014", "Pitot Static Tester", "D4", "active"},
                {"GMF-TL-0015", "Cable Tensiometer", "D4", "active"},
                {"GMF-TL-0016", "Drill Set Aviation", "D4", "active"},
                {"GMF-TL-0017", "Inspection Mirror", "D4", "active"},
                {"GMF-TL-0018", "Hand Riveter", "D4", "active"},
        };
        for (int i = 0; i < t.length; i++) {
            Tool tool = new Tool(t[i][0], t[i][1], t[i][2], EpcCodec.encodeGiai96(0, PREFIX, 1001 + i), t[i][3]);
            toolsByEpc.put(tool.epc, tool);
            toolsByCode.put(tool.code, tool);
        }
        ops.put(OP_OPERATOR, new Op(OP_OPERATOR, "Budi Santoso", "operator"));
        ops.put(OP_SUPERVISOR, new Op(OP_SUPERVISOR, "Dewi Lestari", "supervisor"));
        ops.put(OP_VIEWER, new Op(OP_VIEWER, "Agus Pratama", "viewer"));

        // Drawer check D3 yang "dibuat GMF" lewat API key
        Sess s = newSession("opname", "D3", "items", "apikey:gmf", null);
        s.dto.baselineAt = iso(clock.getAsLong() - 10 * 60_000L);
        // Inventory count lama yang sudah ditutup
        Sess ic = newSession("inventory", "D1", null, "operator:" + OP_OPERATOR, OP_OPERATOR);
        for (Tool tool : toolsByCode.values()) if (tool.drawer.equals("D1")) ic.readArea.put(tool.epc, "D1");
        ic.dto.status = "closed"; ic.dto.closedBy = "operator:" + OP_SUPERVISOR; ic.dto.closedAt = iso(clock.getAsLong() - 3600_000L);
        ic.dto.comment = "Hitung laci D1 lengkap";
        ic.dto.tally = tally(ic);
    }

    public List<Tool> tools() { return new ArrayList<>(toolsByCode.values()); }

    /**
     * EPC yang "ada di laci" untuk reader simulasi: alat terakhir laci tidak ada (hilang), ada satu tag
     * tidak dikenal, dan di D3 ada satu alat nyasar dari D1 — supaya layar review menampilkan
     * missing, unknown, dan extra.
     */
    public List<String> fieldEpcs(String area) {
        List<String> out = new ArrayList<>();
        Tool last = null;
        for (Tool t : toolsByCode.values()) if (t.drawer.equalsIgnoreCase(area)) { out.add(t.epc); last = t; }
        if (last != null) out.remove(last.epc);
        if ("D3".equalsIgnoreCase(area)) out.add(toolsByCode.get("GMF-TL-0001").epc);
        out.add(UNKNOWN_EPC);
        return out;
    }

    // ================= autentikasi =================

    @Override public synchronized String pair(String pairingCode) throws ApiException {
        if (pairingCode == null || pairingCode.length() != 8)
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "pairing_code harus 8 karakter", null, 0, detail("/pairing_code"));
        if (!PAIRING_CODE.equalsIgnoreCase(pairingCode) || pairingUsed)
            throw new ApiException(400, ApiException.PAIRING_INVALID, "Kode pairing salah, kedaluwarsa, atau sudah dipakai");
        pairingUsed = true;
        String key = "demo-dk-" + UUID.randomUUID().toString().replace("-", "");
        deviceKeys.add(key);
        return key;
    }

    /** Mode demo: kode pairing bisa dipakai lagi setelah device key dihapus (pasang ulang). */
    public synchronized void resetPairing() { pairingUsed = false; }

    @Override public synchronized Dtos.LoginResponse login(String deviceKey, String operatorNo, String pin) throws ApiException {
        // Demo tanpa penyimpanan server: device key berawalan demo-dk- tetap berlaku setelah aplikasi di-restart
        if (deviceKey == null || !(deviceKeys.contains(deviceKey) || deviceKey.startsWith("demo-dk-")))
            throw new ApiException(403, ApiException.FORBIDDEN, "Handheld tidak dikenal atau dinonaktifkan");
        if (operatorNo == null || !operatorNo.matches("[0-9]{3,10}"))
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "operator_no 3–10 digit", null, 0, detail("/operator_no"));
        if (pin == null || !pin.matches("[0-9]{6}"))
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "pin 6 digit", null, 0, detail("/pin"));
        long now = clock.getAsLong();
        Op op = ops.get(operatorNo);
        if (op != null && op.lockedUntil > now)
            throw new ApiException(429, ApiException.RATE_LIMITED, "Terkunci", null, (int) ((op.lockedUntil - now + 999) / 1000), null);
        if (op == null || !DEMO_PIN.equals(pin)) {
            if (op != null && ++op.failed >= 5) { op.failed = 0; op.lockedUntil = now + LOCK_SECONDS * 1000L; }
            throw new ApiException(401, ApiException.INVALID_CREDENTIALS, "Nomor operator atau PIN salah");   // jawaban sama
        }
        if (op.disabled) throw new ApiException(423, ApiException.ACCOUNT_DISABLED, "Operator dinonaktifkan");
        op.failed = 0;
        // satu sesi per handheld: token lama handheld ini dicabut
        live.clear();
        long exp = now + TOKEN_TTL_MS;
        // nomor operator + kedaluwarsa ikut di token supaya sesi demo bertahan saat proses aplikasi dimulai ulang
        String token = "tgh_demo_" + op.no + "_" + exp + "_" + UUID.randomUUID().toString().replace("-", "");
        live.put(token, new Tok(op, exp));
        Dtos.LoginResponse res = new Dtos.LoginResponse();
        res.token = token; res.expiresAt = iso(exp);
        res.operator = operator(op);
        return res;
    }

    @Override public synchronized void logout() throws ApiException { auth(); live.remove(tokens.token()); revoked.add(tokens.token()); }

    @Override public synchronized Dtos.MeResponse me() throws ApiException {
        Tok t = auth();
        Dtos.MeResponse m = new Dtos.MeResponse();
        m.operator = operator(t.op);
        m.handheld = new Dtos.Handheld();
        m.handheld.id = "0b9f5c4e-6d0e-4c1a-9a3e-2f6c1d7a8b01"; m.handheld.name = "HH-Demo-01"; m.handheld.disabled = false;
        m.handheld.pairedAt = iso(clock.getAsLong() - 86_400_000L); m.handheld.lastSeenAt = iso(clock.getAsLong());
        m.expiresAt = iso(t.expiresAt);
        return m;
    }

    @Override public synchronized void changePin(String oldPin, String newPin) throws ApiException {
        Tok t = auth();
        if (!DEMO_PIN.equals(oldPin)) throw new ApiException(400, ApiException.VALIDATION_FAILED, "PIN lama salah", null, 0, detail("/old_pin"));
        if (newPin == null || !newPin.matches("[0-9]{6}") || isTrivial(newPin) || newPin.equals(t.op.no))
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "PIN baru terlalu mudah ditebak", null, 0, detail("/new_pin"));
        // Demo: PIN tidak benar-benar diganti supaya skenario demo tetap bisa diulang
    }

    static boolean isTrivial(String pin) {
        if (pin.chars().distinct().count() == 1) return true;
        boolean asc = true, desc = true;
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != 1) asc = false;
            if (pin.charAt(i - 1) - pin.charAt(i) != 1) desc = false;
        }
        return asc || desc;
    }

    // ================= finder =================

    @Override public synchronized Dtos.FinderItem finderEpc(String epc) throws ApiException {
        auth();
        String e = Epc.normalize(epc);
        if (e == null) throw new ApiException(400, ApiException.VALIDATION_FAILED, "EPC tidak valid", null, 0, detail("/epc"));
        return item(e);
    }

    @Override public synchronized List<Dtos.FinderItem> finderLookup(List<String> epcs) throws ApiException {
        auth();
        if (epcs == null || epcs.isEmpty() || epcs.size() > 200)
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "epcs 1–200", null, 0, detail("/epcs"));
        List<Dtos.FinderItem> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : epcs) {
            String e = Epc.normalize(raw);
            if (e == null) throw new ApiException(400, ApiException.VALIDATION_FAILED, "EPC tidak valid", null, 0, detail("/epcs"));
            if (seen.add(e)) out.add(item(e));
        }
        return out;
    }

    @Override public synchronized Dtos.ProductsResponse finderProducts(String q, String cursor, Integer limit) throws ApiException {
        auth();
        Dtos.ProductsResponse res = new Dtos.ProductsResponse();
        res.items = new ArrayList<>();
        String needle = q == null ? "" : q.trim().toLowerCase();
        int max = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        for (Tool t : toolsByCode.values()) {
            if (!needle.isEmpty() && !t.code.toLowerCase().contains(needle) && !t.name.toLowerCase().contains(needle)) continue;
            res.items.add(product(t));
            if (res.items.size() >= max) break;
        }
        return res;
    }

    @Override public synchronized Dtos.SkuResponse finderSku(String sku, String state, String cursor, Integer limit) throws ApiException {
        auth();
        Tool t = sku == null ? null : toolsByCode.get(sku.trim().toUpperCase());
        if (t == null) throw new ApiException(404, ApiException.NOT_FOUND, "SKU tidak ditemukan");
        Dtos.SkuResponse res = new Dtos.SkuResponse();
        res.product = product(t);
        res.items = new ArrayList<>();
        res.items.add(item(t.epc));
        return res;
    }

    private Dtos.Product product(Tool t) {
        Dtos.Product p = new Dtos.Product();
        p.productId = UUID.nameUUIDFromBytes(("tool:" + t.code).getBytes()).toString();
        p.sku = t.code; p.name = t.name;
        p.itemCounts = new HashMap<>();
        p.itemCounts.put("bound", 1);
        return p;
    }

    private Dtos.FinderItem item(String epc) {
        Dtos.FinderItem f = new Dtos.FinderItem();
        f.epc = epc;
        Tool t = toolsByEpc.get(epc);
        if (t == null) { f.known = false; return f; }
        f.known = true; f.sku = t.code; f.name = t.name; f.itemState = "bound"; f.tagStatus = t.tagStatus; f.itemRef = t.code;
        return f;
    }

    // ================= hitung =================

    @Override public synchronized Dtos.CountSession countCreate(Dtos.CountCreateRequest req) throws ApiException {
        Tok t = auth();
        if (req == null || req.kind == null || !(req.kind.equals("inventory") || req.kind.equals("opname")))
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "kind inventory|opname", null, 0, detail("/kind"));
        if (req.area != null && (req.area.isEmpty() || req.area.length() > 60))
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "area 1–60 karakter", null, 0, detail("/area"));
        requireRole(t, req.kind.equals("opname") ? "supervisor" : "operator");
        Sess s = newSession(req.kind, req.area, req.kind.equals("opname") ? (req.expectedSource == null ? "items" : req.expectedSource) : null,
                "operator:" + t.op.no, t.op.no);
        return copy(s.dto);
    }

    @Override public synchronized Dtos.CountListResponse countList(String kind, String status, String cursor, Integer limit) throws ApiException {
        requireRole(auth(), "operator");
        Dtos.CountListResponse res = new Dtos.CountListResponse();
        res.items = new ArrayList<>();
        List<Sess> all = new ArrayList<>(sessions.values());
        for (int i = all.size() - 1; i >= 0; i--) {   // terbaru dulu
            Sess s = all.get(i);
            if (kind != null && !kind.equals(s.dto.kind)) continue;
            if (status != null && !status.equals(s.dto.status)) continue;
            res.items.add(copy(s.dto));
        }
        return res;
    }

    @Override public synchronized Dtos.CountSession countGet(String id) throws ApiException {
        requireRole(auth(), "operator");
        return copy(sess(id).dto);
    }

    @Override public synchronized Dtos.ReadsResponse countReads(Dtos.ReadsRequest req) throws ApiException {
        requireRole(auth(), "operator");
        if (req == null || req.id == null) throw new ApiException(400, ApiException.VALIDATION_FAILED, "id wajib", null, 0, detail("/id"));
        Sess s = sess(req.id);
        if (req.batchId == null || req.batchId.length() < 8)
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "batch_id harus UUID", null, 0, detail("/batch_id"));
        Dtos.ReadsResponse first = s.batches.get(req.batchId);
        if (first != null) {
            Dtos.ReadsResponse r = new Dtos.ReadsResponse();
            r.accepted = first.accepted; r.duplicates = first.duplicates; r.replayed = true; r.tally = tally(s);
            return r;
        }
        if (!"open".equals(s.dto.status)) throw new ApiException(409, ApiException.COUNT_STATE, "Sesi tidak open (" + s.dto.status + ")");
        if (req.reads == null || req.reads.isEmpty() || req.reads.size() > 1000)
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "reads 1–1000", null, 0, detail("/reads"));
        if (req.area != null && req.area.length() > 60)
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "area maks 60", null, 0, detail("/area"));
        int accepted = 0, dup = 0;
        Set<String> inBatch = new LinkedHashSet<>();
        for (int i = 0; i < req.reads.size(); i++) {
            Dtos.Read rd = req.reads.get(i);
            String e = rd == null ? null : Epc.normalize(rd.epc);
            if (e == null) throw new ApiException(400, ApiException.VALIDATION_FAILED, "EPC tidak valid", null, 0, detail("/reads/" + i + "/epc"));
            if (!inBatch.add(e)) continue;
            if (s.readArea.containsKey(e)) dup++;
            else { s.readArea.put(e, req.area == null ? "" : req.area); accepted++; }
        }
        Dtos.ReadsResponse r = new Dtos.ReadsResponse();
        r.accepted = accepted; r.duplicates = dup; r.replayed = false; r.tally = tally(s);
        s.dto.tally = r.tally;
        Dtos.ReadsResponse keep = new Dtos.ReadsResponse();
        keep.accepted = accepted; keep.duplicates = dup;
        s.batches.put(req.batchId, keep);
        return r;
    }

    @Override public synchronized Dtos.ReviewResponse countReview(String id) throws ApiException {
        requireRole(auth(), "supervisor");
        Sess s = sess(id);
        if (!"open".equals(s.dto.status)) throw new ApiException(409, ApiException.COUNT_STATE, "Sesi tidak open (" + s.dto.status + ")");
        s.diffs.clear();
        Set<String> read = s.readArea.keySet();
        if ("opname".equals(s.dto.kind)) {
            for (String e : s.expected) if (!read.contains(e)) s.diffs.add(diff("missing", e, null));
        }
        for (String e : read) {
            Tool t = toolsByEpc.get(e);
            if (t == null) s.diffs.add(diff("unknown", e, s.readArea.get(e)));
            else if ("opname".equals(s.dto.kind) && !s.expected.contains(e)) s.diffs.add(diff("extra", e, s.readArea.get(e)));
        }
        s.dto.status = "review";
        s.dto.tally = tally(s);
        Dtos.ReviewResponse res = new Dtos.ReviewResponse();
        res.session = copy(s.dto);
        res.byKind = new LinkedHashMap<>();
        for (Dtos.Difference d : s.diffs) res.byKind.merge(d.kind, 1, Integer::sum);
        return res;
    }

    @Override public synchronized Dtos.DifferencesResponse countDifferences(String id, String kind, String resolution, String cursor, Integer limit) throws ApiException {
        requireRole(auth(), "supervisor");
        Sess s = sess(id);
        Dtos.DifferencesResponse res = new Dtos.DifferencesResponse();
        res.items = new ArrayList<>();
        for (Dtos.Difference d : s.diffs) {
            if (kind != null && !kind.equals(d.kind)) continue;
            if (resolution != null && !resolution.equals(d.resolution)) continue;
            res.items.add(d);
        }
        return res;
    }

    @Override public synchronized Dtos.Difference countResolve(String id, String differenceId, String resolution, String comment) throws ApiException {
        Tok t = auth();
        requireRole(t, "supervisor");
        Sess s = sess(id);
        if (!"review".equals(s.dto.status)) throw new ApiException(409, ApiException.COUNT_STATE, "Sesi tidak review");
        if (resolution == null || !Arrays.asList("open", "accepted", "recount").contains(resolution))
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "resolution", null, 0, detail("/resolution"));
        if (comment == null || comment.isEmpty() || comment.length() > 500)
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "comment 1–500", null, 0, detail("/comment"));
        for (Dtos.Difference d : s.diffs) {
            if (d.id.equals(differenceId)) { d.resolution = resolution; d.comment = comment; d.resolvedBy = "operator:" + t.op.no; return d; }
        }
        throw new ApiException(404, ApiException.NOT_FOUND, "Selisih tidak ditemukan");
    }

    @Override public synchronized Dtos.CountSession countReopen(String id) throws ApiException {
        requireRole(auth(), "supervisor");
        Sess s = sess(id);
        if (!"review".equals(s.dto.status)) throw new ApiException(409, ApiException.COUNT_STATE, "Sesi tidak review");
        s.dto.status = "open";
        s.diffs.clear();
        return copy(s.dto);
    }

    @Override public synchronized Dtos.CountSession countClose(String id, String comment) throws ApiException {
        Tok t = auth();
        Sess s = sess(id);
        canCloseOrCancel(t, s);
        checkComment(comment);
        if ("opname".equals(s.dto.kind) ? !"review".equals(s.dto.status) : !("open".equals(s.dto.status) || "review".equals(s.dto.status)))
            throw new ApiException(409, ApiException.COUNT_STATE, "Opname harus di-review dulu");
        s.dto.status = "closed"; s.dto.closedBy = "operator:" + t.op.no; s.dto.closedAt = iso(clock.getAsLong()); s.dto.comment = comment;
        return copy(s.dto);
    }

    @Override public synchronized Dtos.CountSession countCancel(String id, String comment) throws ApiException {
        Tok t = auth();
        Sess s = sess(id);
        canCloseOrCancel(t, s);
        checkComment(comment);
        if (!("open".equals(s.dto.status) || "review".equals(s.dto.status)))
            throw new ApiException(409, ApiException.COUNT_STATE, "Sesi sudah " + s.dto.status);
        s.dto.status = "cancelled"; s.dto.closedBy = "operator:" + t.op.no; s.dto.closedAt = iso(clock.getAsLong()); s.dto.comment = comment;
        return copy(s.dto);
    }

    @Override public synchronized JsonObject postOutbox(String path, JsonObject body, String idempotencyKey) throws ApiException {
        if (path.endsWith("counts/reads")) {
            Dtos.ReadsRequest req = Json.gson().fromJson(body, Dtos.ReadsRequest.class);
            return Json.gson().toJsonTree(countReads(req)).getAsJsonObject();
        }
        if (path.endsWith("items/bind")) {
            requireRole(auth(), "supervisor");
            if (idempotencyKey == null || idempotencyKey.length() < 8)
                throw new ApiException(400, ApiException.VALIDATION_FAILED, "Idempotency-Key 8–128", null, 0, null);
            Dtos.BindRequest req = Json.gson().fromJson(body, Dtos.BindRequest.class);
            String e = Epc.normalize(req.epc);
            if (e == null) throw new ApiException(400, ApiException.VALIDATION_FAILED, "EPC tidak valid", null, 0, detail("/epc"));
            Dtos.BindResponse res = new Dtos.BindResponse();
            res.id = UUID.nameUUIDFromBytes(idempotencyKey.getBytes()).toString();
            res.epc = e; res.sku = req.sku; res.serial = req.serial; res.state = "bound"; res.origin = "bind"; res.createdAt = iso(clock.getAsLong());
            return Json.gson().toJsonTree(res).getAsJsonObject();
        }
        throw new ApiException(404, ApiException.NOT_FOUND, "Endpoint tidak dikenal: " + path);
    }

    // ================= helper =================

    private Sess newSession(String kind, String area, String expectedSource, String createdBy, String creatorNo) {
        Sess s = new Sess();
        s.dto.id = UUID.randomUUID().toString();
        s.dto.kind = kind;
        s.dto.number = number(kind);
        s.dto.status = "open";
        s.dto.area = area;
        s.dto.expectedSource = expectedSource;
        s.dto.createdBy = createdBy;
        s.creatorNo = creatorNo;
        if ("opname".equals(kind)) {
            s.dto.baselineAt = iso(clock.getAsLong());
            for (Tool t : toolsByCode.values()) if (area == null || t.drawer.equalsIgnoreCase(area)) s.expected.add(t.epc);
        }
        s.dto.tally = tally(s);
        sessions.put(s.dto.id, s);
        return s;
    }

    private String number(String kind) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Jakarta"));
        String day = f.format(new Date(clock.getAsLong()));
        String key = kind + day;
        int n = seqPerDay.merge(key, 1, Integer::sum);
        return (kind.equals("opname") ? "SO-" : "IC-") + day + "-" + String.format(Locale.US, "%03d", n);
    }

    private Dtos.Tally tally(Sess s) {
        Dtos.Tally t = new Dtos.Tally();
        t.distinctEpcs = s.readArea.size();
        Map<String, Dtos.TallySku> bySku = new LinkedHashMap<>();
        for (String e : s.expected) {
            Tool tool = toolsByEpc.get(e);
            if (tool == null) continue;
            Dtos.TallySku row = bySku.computeIfAbsent(tool.code, k -> { Dtos.TallySku r = new Dtos.TallySku(); r.sku = k; r.expected = 0; return r; });
            row.expected++;
        }
        for (String e : s.readArea.keySet()) {
            Tool tool = toolsByEpc.get(e);
            if (tool == null) continue;
            Dtos.TallySku row = bySku.computeIfAbsent(tool.code, k -> { Dtos.TallySku r = new Dtos.TallySku(); r.sku = k; return r; });
            row.counted++;
        }
        t.bySku = new ArrayList<>(bySku.values());
        Map<String, Integer> byArea = new LinkedHashMap<>();
        for (String a : s.readArea.values()) byArea.merge(a == null ? "" : a, 1, Integer::sum);
        t.byArea = new ArrayList<>();
        for (Map.Entry<String, Integer> en : byArea.entrySet()) {
            Dtos.TallyArea a = new Dtos.TallyArea();
            a.area = en.getKey(); a.counted = en.getValue();
            t.byArea.add(a);
        }
        return t;
    }

    private Dtos.Difference diff(String kind, String epc, String area) {
        Dtos.Difference d = new Dtos.Difference();
        d.id = UUID.randomUUID().toString();
        d.kind = kind; d.epc = epc; d.resolution = "open";
        Tool t = toolsByEpc.get(epc);
        d.detail = new JsonObject();
        if (t != null) {
            d.sku = t.code;
            d.detail.addProperty("name", t.name);
            d.detail.addProperty("tag_status", t.tagStatus);
            d.detail.addProperty("item_state", "bound");
            d.detail.addProperty("drawer", t.drawer);
        }
        if (area != null) d.detail.addProperty("area", area);
        d.expectedQty = kind.equals("missing") ? 1 : 0;
        d.countedQty = kind.equals("missing") ? 0 : 1;
        return d;
    }

    private Sess sess(String id) throws ApiException {
        Sess s = id == null ? null : sessions.get(id);
        if (s == null) throw new ApiException(404, ApiException.NOT_FOUND, "Sesi hitung tidak ditemukan");
        return s;
    }

    private Tok auth() throws ApiException {
        String tk = tokens.token();
        Tok t = tk == null ? null : live.get(tk);
        if (t == null && tk != null && !revoked.contains(tk) && live.isEmpty()) {
            String[] p = tk.split("_");     // tgh, demo, opNo, exp, uuid
            if (p.length == 5 && "demo".equals(p[1]) && ops.containsKey(p[2])) {
                try { t = new Tok(ops.get(p[2]), Long.parseLong(p[3])); live.put(tk, t); } catch (NumberFormatException ignored) {}
            }
        }
        if (t == null || t.expiresAt <= clock.getAsLong()) {
            if (tk != null) live.remove(tk);
            throw new ApiException(401, ApiException.UNAUTHENTICATED, "Token tidak ada atau kedaluwarsa");
        }
        return t;
    }

    private static int rank(String role) {
        switch (role) { case "supervisor": return 2; case "operator": return 1; default: return 0; }
    }

    private static void requireRole(Tok t, String min) throws ApiException {
        if (rank(t.op.role) < rank(min)) throw new ApiException(403, ApiException.FORBIDDEN, "Peran " + t.op.role + " tidak boleh; perlu " + min);
    }

    private static void canCloseOrCancel(Tok t, Sess s) throws ApiException {
        boolean creator = "inventory".equals(s.dto.kind) && t.op.no.equals(s.creatorNo);
        if (!creator && rank(t.op.role) < 2) throw new ApiException(403, ApiException.FORBIDDEN, "Perlu supervisor atau pembuat sesi");
    }

    private static void checkComment(String c) throws ApiException {
        if (c == null || c.trim().length() < 3 || c.length() > 500)
            throw new ApiException(400, ApiException.VALIDATION_FAILED, "comment 3–500 karakter", null, 0, detail("/comment"));
    }

    private static List<Dtos.ErrorDetail> detail(String path) {
        Dtos.ErrorDetail d = new Dtos.ErrorDetail();
        d.path = path; d.code = "invalid"; d.message = "nilai tidak valid";
        return new ArrayList<>(Arrays.asList(d));
    }

    private static Dtos.Operator operator(Op op) {
        Dtos.Operator o = new Dtos.Operator();
        o.operatorNo = op.no; o.name = op.name; o.role = op.role;
        return o;
    }

    private static Dtos.CountSession copy(Dtos.CountSession s) {
        return Json.gson().fromJson(Json.gson().toJson(s), Dtos.CountSession.class);
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }
}

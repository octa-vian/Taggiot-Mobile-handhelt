package com.taggiot.handheld.net.dto;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * DTO Taggiot Handheld API v1.0 (api/site/openapi.yaml, repo taggiot-rfid).
 * Nama field JSON snake_case; Gson dipakai dengan FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES
 * (lihat {@link com.taggiot.handheld.net.Json}), jadi field Java ditulis camelCase.
 * Field opsional dibiarkan null supaya tidak ikut dikirim.
 */
public final class Dtos {
    private Dtos() {}

    // ---------- autentikasi ----------
    public static class PairRequest { public String pairingCode; }
    public static class PairResponse { public String deviceKey; }

    public static class LoginRequest { public String operatorNo, pin; }

    /** role: viewer | operator | supervisor (tenant_admin tetap peran web). */
    public static class Operator { public String operatorNo, name, role; }

    public static class LoginResponse { public String token, expiresAt; public Operator operator; }

    public static class Handheld { public String id, name; public boolean disabled; public String pairedAt, lastSeenAt, pairingExpiresAt; }

    public static class MeResponse { public Operator operator; public Handheld handheld; public String expiresAt; }

    public static class ChangePinRequest { public String oldPin, newPin; }

    // ---------- finder ----------
    public static class FinderEpcRequest { public String epc; }
    public static class FinderLookupRequest { public List<String> epcs; }

    /**
     * Jawaban finder untuk satu EPC. known=false bila EPC tidak dikenal Site Gateway.
     * tag_status: active (di crib, tidak boleh keluar) | released (dipinjam) | blocked (label lama).
     * item_ref: kode alat GMF (mis. GMF-TL-0042); ditambahkan RFID team per keputusan 2026-11-10, boleh null.
     */
    public static class FinderItem {
        public String epc;
        public boolean known;
        public String sku, name;
        public Long serial;
        public String itemState, tagStatus, itemRef;
    }

    public static class FinderLookupResponse { public List<FinderItem> items; }

    public static class ProductsRequest { public String q, cursor; public Integer limit; }
    public static class Product { public String productId, sku, name; public Map<String, Integer> itemCounts; }
    public static class ProductsResponse { public List<Product> items; public String nextCursor; }

    public static class SkuRequest { public String sku, state, cursor; public Integer limit; }
    public static class SkuResponse { public Product product; public List<FinderItem> items; public String nextCursor; }

    // ---------- hitung (inventory count & stock opname) ----------
    public static class Scope { public List<String> productIds, skus; }

    /** kind: inventory | opname. area: label area / kode laci (handover 2026-11-10). expected_source: items | upload. */
    public static class CountCreateRequest { public String kind, area, expectedSource; public Scope scope; }

    public static class TallySku { public String sku; public int counted; public Integer expected; }
    public static class TallyArea { public String area; public int counted; }
    public static class Tally { public int distinctEpcs; public List<TallySku> bySku; public List<TallyArea> byArea; }

    /** status: open → review → closed; review → open (reopen); open|review → cancelled. */
    public static class CountSession {
        public String id, kind, number, status, expectedSource, area, baselineAt, createdBy, closedBy, closedAt, comment;
        public Scope scope;
        public Tally tally;
    }

    public static class CountListRequest { public String kind, status, cursor; public Integer limit; }
    public static class CountListResponse { public List<CountSession> items; public String nextCursor; }

    public static class IdRequest { public String id; }

    public static class Read { public String epc, seenAt; }

    /** batch_id = UUID idempoten; ≤ 1.000 reads dan 256 KiB per batch. */
    public static class ReadsRequest { public String id, batchId, area; public List<Read> reads; }
    public static class ReadsResponse { public int accepted, duplicates; public boolean replayed; public Tally tally; }

    public static class ReviewResponse { public CountSession session; public Map<String, Integer> byKind; }

    public static class DifferencesRequest { public String id, kind, resolution, cursor; public Integer limit; }

    /** kind: missing | extra | unknown | short | over | area_conflict. resolution: open | accepted | recount. */
    public static class Difference {
        public String id, kind, epc, sku;
        public Integer expectedQty, countedQty;
        public JsonObject detail;
        public String resolution, resolvedBy, comment;
    }

    public static class DifferencesResponse { public List<Difference> items; public String nextCursor; }

    public static class ResolveRequest { public String id, differenceId, resolution, comment; }

    public static class CommentRequest { public String id, comment; }

    // ---------- bind (BUKAN untuk site GMF; tag alat diikat di aplikasi GMF) ----------
    public static class BindRequest { public String epc, productId, sku, gtin; public Long serial; public String barcodeValue, tid; }
    public static class BindResponse { public String id, productId, sku, epc, state, origin, createdBy, createdAt; public Long serial; }

    // ---------- error envelope ----------
    public static class ErrorDetail { public String path, code, message; }
    public static class ErrorBody { public String code, message, requestId; public List<ErrorDetail> details; }
    public static class ErrorEnvelope { public ErrorBody error; }
}

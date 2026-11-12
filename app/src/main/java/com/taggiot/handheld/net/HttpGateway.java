package com.taggiot.handheld.net;

import android.content.Context;

import com.google.gson.JsonObject;
import com.taggiot.handheld.net.dto.Dtos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/** Gateway nyata lewat Retrofit. Mengubah respons non-2xx menjadi {@link ApiException} dari error envelope. */
public final class HttpGateway implements Gateway {

    private final Context app;

    public HttpGateway(Context ctx) { this.app = ctx.getApplicationContext(); }

    private SiteGatewayApi api() throws ApiException {
        try {
            return ApiClient.get(app);
        } catch (IllegalStateException e) {
            throw new ApiException(0, ApiException.PLAIN_HTTP, "URL Site Gateway harus HTTPS");
        } catch (IllegalArgumentException e) {
            throw ApiException.network("URL Site Gateway tidak valid");
        }
    }

    private static JsonObject empty() { return new JsonObject(); }

    /** Jalankan panggilan; 2xx → body (boleh null untuk Void), selain itu lempar ApiException. */
    static <T> T call(Call<T> c) throws ApiException {
        Response<T> r;
        try {
            r = c.execute();
        } catch (IOException e) {
            String m = e.getMessage();
            if (m != null && m.contains(ApiException.PLAIN_HTTP)) throw new ApiException(0, ApiException.PLAIN_HTTP, "URL Site Gateway harus HTTPS");
            throw ApiException.network(e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            throw ApiException.network(e.getClass().getSimpleName());
        }
        if (r.isSuccessful()) return r.body();
        throw toException(r);
    }

    static ApiException toException(Response<?> r) {
        int status = r.code();
        String code = null, message = null, requestId = null;
        List<Dtos.ErrorDetail> details = null;
        try (ResponseBody eb = r.errorBody()) {
            if (eb != null) {
                Dtos.ErrorEnvelope env = Json.gson().fromJson(eb.charStream(), Dtos.ErrorEnvelope.class);
                if (env != null && env.error != null) {
                    code = env.error.code; message = env.error.message; requestId = env.error.requestId; details = env.error.details;
                }
            }
        } catch (RuntimeException ignored) {
            // body bukan envelope (mis. proxy) → pakai status saja
        }
        if (code == null) {
            switch (status) {
                case 401: code = ApiException.UNAUTHENTICATED; break;
                case 403: code = ApiException.FORBIDDEN; break;
                case 404: code = ApiException.NOT_FOUND; break;
                case 429: code = ApiException.RATE_LIMITED; break;
                default: code = "http_" + status;
            }
        }
        int retryAfter = 0;
        String ra = r.headers().get("Retry-After");
        if (ra != null) { try { retryAfter = Integer.parseInt(ra.trim()); } catch (NumberFormatException ignored) {} }
        return new ApiException(status, code, message, requestId, retryAfter, details);
    }

    // ---------- autentikasi ----------

    @Override public String pair(String pairingCode) throws ApiException {
        Dtos.PairRequest req = new Dtos.PairRequest();
        req.pairingCode = pairingCode;
        Dtos.PairResponse res = call(api().pair(req));
        if (res == null || res.deviceKey == null || res.deviceKey.isEmpty()) throw ApiException.network("Jawaban pairing kosong");
        return res.deviceKey;
    }

    @Override public Dtos.LoginResponse login(String deviceKey, String operatorNo, String pin) throws ApiException {
        Dtos.LoginRequest req = new Dtos.LoginRequest();
        req.operatorNo = operatorNo; req.pin = pin;
        Dtos.LoginResponse res = call(api().login(deviceKey, req));
        if (res == null || res.token == null) throw ApiException.network("Jawaban login kosong");
        return res;
    }

    @Override public void logout() throws ApiException { call(api().logout(empty())); }

    @Override public Dtos.MeResponse me() throws ApiException { return call(api().me(empty())); }

    @Override public void changePin(String oldPin, String newPin) throws ApiException {
        Dtos.ChangePinRequest req = new Dtos.ChangePinRequest();
        req.oldPin = oldPin; req.newPin = newPin;
        call(api().changePin(req));
    }

    // ---------- finder ----------

    @Override public Dtos.FinderItem finderEpc(String epc) throws ApiException {
        Dtos.FinderEpcRequest req = new Dtos.FinderEpcRequest();
        req.epc = epc;
        return call(api().finderEpc(req));
    }

    @Override public List<Dtos.FinderItem> finderLookup(List<String> epcs) throws ApiException {
        Dtos.FinderLookupRequest req = new Dtos.FinderLookupRequest();
        req.epcs = epcs;
        Dtos.FinderLookupResponse res = call(api().finderLookup(req));
        return res == null || res.items == null ? new ArrayList<>() : res.items;
    }

    @Override public Dtos.ProductsResponse finderProducts(String q, String cursor, Integer limit) throws ApiException {
        Dtos.ProductsRequest req = new Dtos.ProductsRequest();
        req.q = q; req.cursor = cursor; req.limit = limit;
        return call(api().finderProducts(req));
    }

    @Override public Dtos.SkuResponse finderSku(String sku, String state, String cursor, Integer limit) throws ApiException {
        Dtos.SkuRequest req = new Dtos.SkuRequest();
        req.sku = sku; req.state = state; req.cursor = cursor; req.limit = limit;
        return call(api().finderSku(req));
    }

    // ---------- hitung ----------

    @Override public Dtos.CountSession countCreate(Dtos.CountCreateRequest req) throws ApiException { return call(api().countCreate(req)); }

    @Override public Dtos.CountListResponse countList(String kind, String status, String cursor, Integer limit) throws ApiException {
        Dtos.CountListRequest req = new Dtos.CountListRequest();
        req.kind = kind; req.status = status; req.cursor = cursor; req.limit = limit;
        return call(api().countList(req));
    }

    @Override public Dtos.CountSession countGet(String id) throws ApiException { return call(api().countGet(id(id))); }

    @Override public Dtos.ReadsResponse countReads(Dtos.ReadsRequest req) throws ApiException { return call(api().countReads(req)); }

    @Override public Dtos.ReviewResponse countReview(String id) throws ApiException { return call(api().countReview(id(id))); }

    @Override public Dtos.DifferencesResponse countDifferences(String id, String kind, String resolution, String cursor, Integer limit) throws ApiException {
        Dtos.DifferencesRequest req = new Dtos.DifferencesRequest();
        req.id = id; req.kind = kind; req.resolution = resolution; req.cursor = cursor; req.limit = limit;
        return call(api().countDifferences(req));
    }

    @Override public Dtos.Difference countResolve(String id, String differenceId, String resolution, String comment) throws ApiException {
        Dtos.ResolveRequest req = new Dtos.ResolveRequest();
        req.id = id; req.differenceId = differenceId; req.resolution = resolution; req.comment = comment;
        return call(api().countResolve(req));
    }

    @Override public Dtos.CountSession countReopen(String id) throws ApiException { return call(api().countReopen(id(id))); }

    @Override public Dtos.CountSession countClose(String id, String comment) throws ApiException { return call(api().countClose(comment(id, comment))); }

    @Override public Dtos.CountSession countCancel(String id, String comment) throws ApiException { return call(api().countCancel(comment(id, comment))); }

    @Override public JsonObject postOutbox(String path, JsonObject body, String idempotencyKey) throws ApiException {
        JsonObject res = call(api().post(path, idempotencyKey, body));
        return res == null ? new JsonObject() : res;
    }

    private static Dtos.IdRequest id(String id) { Dtos.IdRequest r = new Dtos.IdRequest(); r.id = id; return r; }

    private static Dtos.CommentRequest comment(String id, String c) { Dtos.CommentRequest r = new Dtos.CommentRequest(); r.id = id; r.comment = c; return r; }
}

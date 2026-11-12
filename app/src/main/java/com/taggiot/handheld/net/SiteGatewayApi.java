package com.taggiot.handheld.net;

import com.google.gson.JsonObject;
import com.taggiot.handheld.net.dto.Dtos;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Url;

/**
 * Taggiot Handheld API v1.0: semua endpoint POST + body JSON ({} bila kosong),
 * di bawah https://<site-host>/api/v1/handheld/. Hanya pair dan login tanpa token.
 * Header Authorization: Bearer tgh_... dipasang ApiClient.
 */
public interface SiteGatewayApi {

    String BASE = "api/v1/handheld/";

    // ---------- autentikasi ----------
    @POST(BASE + "pair") Call<Dtos.PairResponse> pair(@Body Dtos.PairRequest req);

    @POST(BASE + "login") Call<Dtos.LoginResponse> login(@Header("X-Handheld-Key") String deviceKey, @Body Dtos.LoginRequest req);

    @POST(BASE + "logout") Call<Void> logout(@Body JsonObject empty);

    @POST(BASE + "me") Call<Dtos.MeResponse> me(@Body JsonObject empty);

    @POST(BASE + "pin") Call<Void> changePin(@Body Dtos.ChangePinRequest req);

    // ---------- finder (viewer+) ----------
    @POST(BASE + "finder/epc") Call<Dtos.FinderItem> finderEpc(@Body Dtos.FinderEpcRequest req);

    @POST(BASE + "finder/lookup") Call<Dtos.FinderLookupResponse> finderLookup(@Body Dtos.FinderLookupRequest req);

    @POST(BASE + "finder/products") Call<Dtos.ProductsResponse> finderProducts(@Body Dtos.ProductsRequest req);

    @POST(BASE + "finder/sku") Call<Dtos.SkuResponse> finderSku(@Body Dtos.SkuRequest req);

    // ---------- hitung ----------
    @POST(BASE + "counts/create") Call<Dtos.CountSession> countCreate(@Body Dtos.CountCreateRequest req);

    @POST(BASE + "counts/list") Call<Dtos.CountListResponse> countList(@Body Dtos.CountListRequest req);

    @POST(BASE + "counts/get") Call<Dtos.CountSession> countGet(@Body Dtos.IdRequest req);

    @POST(BASE + "counts/reads") Call<Dtos.ReadsResponse> countReads(@Body Dtos.ReadsRequest req);

    @POST(BASE + "counts/review") Call<Dtos.ReviewResponse> countReview(@Body Dtos.IdRequest req);

    @POST(BASE + "counts/differences") Call<Dtos.DifferencesResponse> countDifferences(@Body Dtos.DifferencesRequest req);

    @POST(BASE + "counts/resolve") Call<Dtos.Difference> countResolve(@Body Dtos.ResolveRequest req);

    @POST(BASE + "counts/reopen") Call<Dtos.CountSession> countReopen(@Body Dtos.IdRequest req);

    @POST(BASE + "counts/close") Call<Dtos.CountSession> countClose(@Body Dtos.CommentRequest req);

    @POST(BASE + "counts/cancel") Call<Dtos.CountSession> countCancel(@Body Dtos.CommentRequest req);

    // ---------- bind (supervisor+, bukan site GMF) ----------
    @POST(BASE + "items/bind") Call<Dtos.BindResponse> bind(@Header("Idempotency-Key") String idempotencyKey, @Body Dtos.BindRequest req);

    /** Antrean keluar dikirim generik: path dan body diambil dari baris outbox. */
    @POST Call<JsonObject> post(@Url String path, @Header("Idempotency-Key") String idempotencyKey, @Body JsonObject body);
}

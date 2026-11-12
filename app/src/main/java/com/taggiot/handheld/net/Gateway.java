package com.taggiot.handheld.net;

import com.google.gson.JsonObject;
import com.taggiot.handheld.net.dto.Dtos;

import java.util.List;

/**
 * Pintu tunggal aplikasi ke Site Gateway. Semua method MEMBLOK (panggil dari AppExecutors.net()).
 * Dua implementasi: {@link HttpGateway} (Retrofit, server nyata) dan {@link DemoGateway}
 * (in-memory, mode demo tanpa jaringan, untuk emulator dan pengembangan sebelum perangkat ada).
 */
public interface Gateway {

    // ---------- autentikasi ----------
    /** Tukar kode pairing 8 karakter dengan device key (hanya ditampilkan sekali oleh server). */
    String pair(String pairingCode) throws ApiException;

    Dtos.LoginResponse login(String deviceKey, String operatorNo, String pin) throws ApiException;

    void logout() throws ApiException;

    Dtos.MeResponse me() throws ApiException;

    void changePin(String oldPin, String newPin) throws ApiException;

    // ---------- finder ----------
    Dtos.FinderItem finderEpc(String epc) throws ApiException;

    /** ≤ 200 EPC per panggilan; urutan dipertahankan. */
    List<Dtos.FinderItem> finderLookup(List<String> epcs) throws ApiException;

    Dtos.ProductsResponse finderProducts(String q, String cursor, Integer limit) throws ApiException;

    Dtos.SkuResponse finderSku(String sku, String state, String cursor, Integer limit) throws ApiException;

    // ---------- hitung ----------
    Dtos.CountSession countCreate(Dtos.CountCreateRequest req) throws ApiException;

    Dtos.CountListResponse countList(String kind, String status, String cursor, Integer limit) throws ApiException;

    Dtos.CountSession countGet(String id) throws ApiException;

    Dtos.ReadsResponse countReads(Dtos.ReadsRequest req) throws ApiException;

    Dtos.ReviewResponse countReview(String id) throws ApiException;

    Dtos.DifferencesResponse countDifferences(String id, String kind, String resolution, String cursor, Integer limit) throws ApiException;

    Dtos.Difference countResolve(String id, String differenceId, String resolution, String comment) throws ApiException;

    Dtos.CountSession countReopen(String id) throws ApiException;

    Dtos.CountSession countClose(String id, String comment) throws ApiException;

    Dtos.CountSession countCancel(String id, String comment) throws ApiException;

    // ---------- antrean keluar ----------
    /**
     * Kirim satu baris outbox apa adanya. path relatif terhadap base URL (mis. api/v1/handheld/counts/reads).
     * idempotencyKey null bila endpoint memakai batch_id di body.
     */
    JsonObject postOutbox(String path, JsonObject body, String idempotencyKey) throws ApiException;
}

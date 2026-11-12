package com.taggiot.handheld.net;

import com.taggiot.handheld.net.dto.Dtos;

import java.util.List;

/**
 * Satu jenis kesalahan untuk semua panggilan Site Gateway.
 * status 0 = masalah jaringan (tidak sampai ke server); selain itu kode HTTP + {@code code}
 * dari envelope {"error":{code,message,request_id,details}}.
 * Pesan TIDAK pernah berisi PIN, token, device key, atau EPC.
 */
public class ApiException extends Exception {
    public static final String NETWORK = "network";
    public static final String VALIDATION_FAILED = "validation_failed";
    public static final String UNAUTHENTICATED = "unauthenticated";
    public static final String INVALID_CREDENTIALS = "invalid_credentials";
    public static final String FORBIDDEN = "forbidden";
    public static final String NOT_FOUND = "not_found";
    public static final String ACCOUNT_DISABLED = "account_disabled";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String PAIRING_INVALID = "pairing_invalid";
    public static final String COUNT_STATE = "count_state";
    public static final String NOT_IMPLEMENTED = "not_implemented";
    public static final String PLAIN_HTTP = "plain_http_refused";

    public final int status;
    public final String code;
    public final String requestId;
    /** Detik dari header Retry-After (429), 0 bila tidak ada. */
    public final int retryAfterSeconds;
    public final List<Dtos.ErrorDetail> details;

    public ApiException(int status, String code, String message, String requestId, int retryAfterSeconds, List<Dtos.ErrorDetail> details) {
        super(message == null ? code : message);
        this.status = status;
        this.code = code == null ? "" : code;
        this.requestId = requestId;
        this.retryAfterSeconds = retryAfterSeconds;
        this.details = details;
    }

    public ApiException(int status, String code, String message) { this(status, code, message, null, 0, null); }

    public static ApiException network(String message) { return new ApiException(0, NETWORK, message); }

    public boolean isNetwork() { return status == 0; }

    /** Token tidak ada / kedaluwarsa: operator harus masuk lagi lalu kirim ulang dengan id yang sama. */
    public boolean needsRelogin() { return status == 401 && UNAUTHENTICATED.equals(code); }

    /** Kesalahan yang tidak akan hilang dengan mengulang permintaan yang sama. */
    public boolean isPermanent() {
        if (status == 0) return false;
        if (status == 401 || status == 408 || status == 429) return false;
        return status >= 400 && status < 500;
    }

    /** Jalur JSON pointer field pertama yang salah (validation_failed), mis. "/pin". */
    public String firstDetailPath() {
        return details == null || details.isEmpty() ? null : details.get(0).path;
    }

    /** Pesan bahasa Indonesia untuk operator. */
    public String userMessage() {
        switch (code) {
            case NETWORK: return "Tidak terhubung ke Site Gateway. Data tetap aman di perangkat.";
            case PLAIN_HTTP: return "URL Site Gateway harus HTTPS. PIN tidak dikirim lewat HTTP biasa.";
            case INVALID_CREDENTIALS: return "Nomor operator atau PIN salah.";
            case UNAUTHENTICATED: return "Sesi berakhir. Masuk lagi.";
            case FORBIDDEN: return status == 403 && getMessage() != null && getMessage().toLowerCase().contains("handheld")
                    ? "Handheld ini tidak dikenal atau dinonaktifkan. Pasangkan ulang."
                    : "Peran Anda tidak diizinkan untuk tindakan ini.";
            case ACCOUNT_DISABLED: return "Akun operator dinonaktifkan. Hubungi admin site.";
            case RATE_LIMITED: {
                int min = Math.max(1, (retryAfterSeconds + 59) / 60);
                return "Terlalu banyak percobaan. Coba lagi dalam " + min + " menit.";
            }
            case PAIRING_INVALID: return "Kode pairing salah, kedaluwarsa, atau sudah dipakai.";
            case COUNT_STATE: return "Sesi hitung tidak dalam status yang tepat untuk tindakan ini.";
            case NOT_FOUND: return "Data tidak ditemukan di Site Gateway.";
            case VALIDATION_FAILED: {
                String p = firstDetailPath();
                return "Data ditolak server" + (p == null ? "." : " (" + p + ").");
            }
            case NOT_IMPLEMENTED: return "Fitur belum tersedia di Site Gateway ini.";
            default:
                return status >= 500 ? "Site Gateway bermasalah (HTTP " + status + "). Coba lagi."
                        : "Gagal (HTTP " + status + (code.isEmpty() ? "" : " " + code) + ").";
        }
    }
}

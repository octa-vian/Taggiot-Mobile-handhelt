package com.taggiot.handheld.net;

import android.content.Context;

import com.taggiot.handheld.BuildConfig;
import com.taggiot.handheld.data.Prefs;
import com.taggiot.handheld.data.Session;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Klien REST ke Site Gateway.
 * - HTTPS saja: URL http:// ditolak sebelum satu byte pun dikirim (PIN tidak boleh lewat HTTP biasa).
 * - Authorization: Bearer <token> dipasang otomatis bila sesi punya token.
 * - Log HTTP (debug) hanya method/URL/status; header Authorization dan X-Handheld-Key diredaksi,
 *   body tidak pernah dicatat (berisi EPC/PIN).
 * - Sertifikat: trust anchor dari network_security_config (system + user CA; root CA Taggiot bisa ditambah di res/raw).
 */
public final class ApiClient {
    private static SiteGatewayApi api;
    private static String builtFor;

    private ApiClient() {}

    public static boolean isHttps(String url) {
        return url != null && url.toLowerCase().startsWith("https://");
    }

    /** @throws IllegalStateException bila URL bukan HTTPS atau tidak valid. */
    public static synchronized SiteGatewayApi get(Context ctx) {
        String base = Prefs.get(ctx).siteGatewayUrl();
        if (!isHttps(base)) throw new IllegalStateException(ApiException.PLAIN_HTTP);
        if (api == null || !base.equals(builtFor)) {
            api = new Retrofit.Builder()
                    .baseUrl(base)
                    .client(http(ctx))
                    .addConverterFactory(GsonConverterFactory.create(Json.gson()))
                    .build()
                    .create(SiteGatewayApi.class);
            builtFor = base;
        }
        return api;
    }

    public static synchronized void reset() { api = null; builtFor = null; }

    private static OkHttpClient http(Context ctx) {
        Context app = ctx.getApplicationContext();
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .addInterceptor(new HttpsOnly())
                .addInterceptor(chain -> {
                    Request.Builder r = chain.request().newBuilder()
                            .header("Content-Type", "application/json")
                            .header("X-App-Version", BuildConfig.VERSION_NAME);
                    String token = Session.get(app).token();
                    if (token != null && chain.request().header("Authorization") == null) r.header("Authorization", "Bearer " + token);
                    return chain.proceed(r.build());
                });
        if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor log = new HttpLoggingInterceptor();
            log.setLevel(HttpLoggingInterceptor.Level.BASIC);   // tanpa header, tanpa body
            log.redactHeader("Authorization");
            log.redactHeader("X-Handheld-Key");
            log.redactHeader("Idempotency-Key");
            b.addInterceptor(log);
        }
        return b.build();
    }

    /** Lapis kedua setelah network_security_config: permintaan non-HTTPS tidak pernah keluar. */
    private static final class HttpsOnly implements Interceptor {
        @Override public okhttp3.Response intercept(Chain chain) throws IOException {
            if (!chain.request().isHttps()) throw new IOException(ApiException.PLAIN_HTTP);
            return chain.proceed(chain.request());
        }
    }
}

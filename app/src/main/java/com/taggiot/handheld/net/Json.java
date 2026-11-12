package com.taggiot.handheld.net;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Satu instance Gson untuk API dan antrean keluar: field Java camelCase ↔ JSON snake_case. */
public final class Json {
    private Json() {}

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    public static Gson gson() { return GSON; }
}

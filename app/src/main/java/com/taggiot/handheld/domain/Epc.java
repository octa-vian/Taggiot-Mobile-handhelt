package com.taggiot.handheld.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Aturan EPC Site Gateway: HEX huruf besar dalam kata 16-bit utuh, ^([0-9A-F]{4}){1,31}$
 * (4 sampai 124 karakter). Semua EPC dari reader di-trim, di-uppercase, dan dicek pola ini
 * SEBELUM dipakai di panggilan API apa pun.
 */
public final class Epc {
    private Epc() {}

    public static final Pattern PATTERN = Pattern.compile("^([0-9A-F]{4}){1,31}$");

    /** @return EPC ternormalisasi, atau null bila tidak memenuhi pola. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[\\s:-]", "").toUpperCase();
        return PATTERN.matcher(s).matches() ? s : null;
    }

    public static boolean isValid(String raw) { return normalize(raw) != null; }

    /** Normalisasi + buang duplikat, urutan dipertahankan; EPC tidak valid dilewati. */
    public static List<String> normalizeAll(Collection<String> raws) {
        Set<String> out = new LinkedHashSet<>();
        for (String r : raws) {
            String e = normalize(r);
            if (e != null) out.add(e);
        }
        return new ArrayList<>(out);
    }

    /** Potong daftar menjadi bagian berukuran maksimum {@code max} (batas API: 1.000 per batch reads, 200 per lookup). */
    public static <T> List<List<T>> chunk(List<T> list, int max) {
        if (max <= 0) throw new IllegalArgumentException("max harus > 0");
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += max) out.add(new ArrayList<>(list.subList(i, Math.min(list.size(), i + max))));
        return out;
    }
}

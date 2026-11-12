package com.taggiot.handheld.reader;

import java.util.HashSet;
import java.util.Set;

/**
 * Daftar keycode tombol pemicu fisik. Keycode berbeda per model handheld,
 * jadi bisa direkam ulang di Pengaturan ("Rekam tombol pemicu").
 */
public final class TriggerKeys {
    private TriggerKeys() {}

    /**
     * Default: keycode yang umum dipakai tombol scan samping/pistol pada handheld Android industri.
     * 142 (F12, scancode 250) = shutter SEUIC CRUISE2, terverifikasi di perangkat. Model lain: rekam di Pengaturan.
     */
    public static final int[] DEFAULTS = {142, 139, 280, 291, 293, 294, 301, 311, 312, 313, 315, 316, 520, 521, 522, 523};

    public static String toCsv(Set<Integer> codes) {
        StringBuilder sb = new StringBuilder();
        for (Integer c : codes) { if (sb.length() > 0) sb.append(','); sb.append(c); }
        return sb.toString();
    }

    public static Set<Integer> fromCsv(String csv) {
        Set<Integer> out = new HashSet<>();
        if (csv == null || csv.trim().isEmpty()) {
            for (int c : DEFAULTS) out.add(c);
            return out;
        }
        for (String p : csv.split(",")) {
            try { out.add(Integer.parseInt(p.trim())); } catch (NumberFormatException ignored) {}
        }
        return out;
    }
}

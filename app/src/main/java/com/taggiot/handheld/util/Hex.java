package com.taggiot.handheld.util;

public final class Hex {
    private Hex() {}
    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

    public static String encode(byte[] b) {
        if (b == null) return "";
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = DIGITS[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = DIGITS[b[i] & 0xF];
        }
        return new String(out);
    }

    public static byte[] decode(String hex) {
        String h = clean(hex);
        if (h.length() % 2 != 0) throw new IllegalArgumentException("Panjang HEX ganjil");
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    public static String clean(String hex) {
        return hex == null ? "" : hex.replaceAll("[\\s:-]", "").toUpperCase();
    }

    /** "E28068940000501200000000" → "E280 6894 0000 5012 0000 0000" */
    public static String group4(String hex) {
        String h = clean(hex);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length(); i += 4) {
            if (i > 0) sb.append(' ');
            sb.append(h, i, Math.min(h.length(), i + 4));
        }
        return sb.toString();
    }

    /** Seperti group4 tetapi dipatahkan jadi dua baris bila lebih dari 3 grup (untuk tempat sempit). */
    public static String group4Lines(String hex) {
        return group4(hex).replaceFirst("^(\\S+ \\S+ \\S+) (\\S)", "$1\n$2");
    }

    public static boolean isHex(String s) {
        return s != null && clean(s).matches("[0-9A-F]+");
    }
}

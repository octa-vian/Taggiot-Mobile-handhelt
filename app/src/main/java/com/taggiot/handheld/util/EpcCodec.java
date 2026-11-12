package com.taggiot.handheld.util;

import java.math.BigInteger;

/**
 * Encoder/decoder EPC GS1 yang dipakai fitur Encode:
 * - GIAI-96 (aset tetap/alat): header 0x34
 * - GRAI-96 (aset returnable): header 0x33
 * - SGTIN-96 (barang dagang): header 0x30
 * Mengikuti GS1 EPC Tag Data Standard (tabel partisi 96-bit).
 */
public final class EpcCodec {
    private EpcCodec() {}

    public static final int HEADER_SGTIN96 = 0x30;
    public static final int HEADER_GRAI96 = 0x33;
    public static final int HEADER_GIAI96 = 0x34;

    // {bit company prefix, digit company prefix} per partisi 0..6
    private static final int[][] CP = {{40, 12}, {37, 11}, {34, 10}, {30, 9}, {27, 8}, {24, 7}, {20, 6}};

    public static final class Giai {
        public final int filter;
        public final String companyPrefix;
        public final BigInteger assetReference;
        Giai(int filter, String companyPrefix, BigInteger assetReference) {
            this.filter = filter; this.companyPrefix = companyPrefix; this.assetReference = assetReference;
        }
        public String pureIdentityUri() { return "urn:epc:id:giai:" + companyPrefix + "." + assetReference; }
        public String tagUri() { return "urn:epc:tag:giai-96:" + filter + "." + companyPrefix + "." + assetReference; }
    }

    private static int partitionFor(String companyPrefix) {
        for (int p = 0; p < CP.length; p++) if (CP[p][1] == companyPrefix.length()) return p;
        throw new IllegalArgumentException("Company prefix GS1 harus 6–12 digit");
    }

    /** GIAI-96: asset reference numerik, tanpa nol di depan. */
    public static String encodeGiai96(int filter, String companyPrefix, long assetReference) {
        if (filter < 0 || filter > 7) throw new IllegalArgumentException("Filter 0–7");
        if (!companyPrefix.matches("\\d{6,12}")) throw new IllegalArgumentException("Company prefix harus 6–12 digit");
        int p = partitionFor(companyPrefix);
        int cpBits = CP[p][0];
        int refBits = 82 - cpBits;
        BigInteger ref = BigInteger.valueOf(assetReference);
        if (ref.signum() < 0 || ref.bitLength() > refBits)
            throw new IllegalArgumentException("Asset reference terlalu besar untuk partisi " + p);
        BigInteger v = BigInteger.valueOf(HEADER_GIAI96);
        v = v.shiftLeft(3).or(BigInteger.valueOf(filter));
        v = v.shiftLeft(3).or(BigInteger.valueOf(p));
        v = v.shiftLeft(cpBits).or(new BigInteger(companyPrefix));
        v = v.shiftLeft(refBits).or(ref);
        return toHex96(v);
    }

    public static Giai decodeGiai96(String hex) {
        BigInteger v = new BigInteger(Hex.clean(hex), 16);
        int header = v.shiftRight(88).intValue() & 0xFF;
        if (header != HEADER_GIAI96) throw new IllegalArgumentException("Bukan GIAI-96");
        int filter = v.shiftRight(85).intValue() & 0x7;
        int p = v.shiftRight(82).intValue() & 0x7;
        if (p > 6) throw new IllegalArgumentException("Partisi tidak valid");
        int cpBits = CP[p][0], refBits = 82 - cpBits;
        BigInteger cp = v.shiftRight(refBits).and(mask(cpBits));
        BigInteger ref = v.and(mask(refBits));
        String cpStr = String.format("%0" + CP[p][1] + "d", cp);
        return new Giai(filter, cpStr, ref);
    }

    /** SGTIN-96: GTIN-14 dipecah menjadi company prefix + item reference (dengan indicator digit). */
    public static String encodeSgtin96(int filter, String companyPrefix, String itemRefWithIndicator, long serial) {
        if (!companyPrefix.matches("\\d{6,12}")) throw new IllegalArgumentException("Company prefix harus 6–12 digit");
        int p = partitionFor(companyPrefix);
        int cpBits = CP[p][0];
        int irBits = 44 - cpBits;
        int irDigits = 13 - CP[p][1];
        if (!itemRefWithIndicator.matches("\\d{" + irDigits + "}"))
            throw new IllegalArgumentException("Item reference harus " + irDigits + " digit");
        BigInteger serialBi = BigInteger.valueOf(serial);
        if (serialBi.bitLength() > 38) throw new IllegalArgumentException("Serial maks 38 bit");
        BigInteger v = BigInteger.valueOf(HEADER_SGTIN96);
        v = v.shiftLeft(3).or(BigInteger.valueOf(filter));
        v = v.shiftLeft(3).or(BigInteger.valueOf(p));
        v = v.shiftLeft(cpBits).or(new BigInteger(companyPrefix));
        v = v.shiftLeft(irBits).or(new BigInteger(itemRefWithIndicator));
        v = v.shiftLeft(38).or(serialBi);
        return toHex96(v);
    }

    /** Nama skema dari header EPC, untuk label di UI. */
    public static String schemeOf(String hex) {
        String h = Hex.clean(hex);
        if (h.length() < 2) return "?";
        switch (Integer.parseInt(h.substring(0, 2), 16)) {
            case HEADER_SGTIN96: return "SGTIN-96";
            case HEADER_GRAI96: return "GRAI-96";
            case HEADER_GIAI96: return "GIAI-96";
            default: return "EPC " + (h.length() * 4) + "-bit";
        }
    }

    private static BigInteger mask(int bits) { return BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE); }

    private static String toHex96(BigInteger v) {
        String s = v.toString(16).toUpperCase();
        while (s.length() < 24) s = "0" + s;
        return s;
    }
}

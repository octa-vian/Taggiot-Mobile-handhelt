package com.taggiot.handheld.reader;

/** Hasil operasi baca/tulis/lock/kill satu tag. */
public final class TagOpResult {
    public final boolean success;
    public final String dataHex;   // untuk operasi baca
    public final String error;     // pesan untuk operator / log

    private TagOpResult(boolean success, String dataHex, String error) {
        this.success = success;
        this.dataHex = dataHex;
        this.error = error;
    }

    public static TagOpResult ok() { return new TagOpResult(true, null, null); }
    public static TagOpResult ok(String dataHex) { return new TagOpResult(true, dataHex, null); }
    public static TagOpResult fail(String error) { return new TagOpResult(false, null, error); }

    @Override public String toString() {
        return success ? "OK" + (dataHex != null ? " " + dataHex : "") : "FAIL " + error;
    }
}

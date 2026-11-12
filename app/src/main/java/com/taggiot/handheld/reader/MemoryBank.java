package com.taggiot.handheld.reader;

/** Bank memori EPC Gen2. */
public enum MemoryBank {
    RESERVED(0), EPC(1), TID(2), USER(3);

    public final int code;
    MemoryBank(int code) { this.code = code; }
}

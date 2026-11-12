package com.taggiot.handheld.reader;

/** Satu kali baca tag dari reader. EPC dan TID dalam HEX huruf besar tanpa spasi. */
public final class TagRead {
    public final String epc;
    public final String tid;      // null bila mode EPC saja
    public final int rssi;        // dBm, mis. -45
    public final int antenna;     // handheld biasanya 1
    public final long timestamp;  // System.currentTimeMillis()

    public TagRead(String epc, String tid, int rssi, int antenna, long timestamp) {
        this.epc = epc == null ? "" : epc.replace(" ", "").toUpperCase();
        this.tid = tid == null ? null : tid.replace(" ", "").toUpperCase();
        this.rssi = rssi;
        this.antenna = antenna;
        this.timestamp = timestamp;
    }

    public TagRead(String epc, int rssi) {
        this(epc, null, rssi, 1, System.currentTimeMillis());
    }

    @Override public String toString() {
        return "TagRead{" + epc + ", " + rssi + " dBm}";
    }
}

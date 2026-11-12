package com.taggiot.handheld.ui.inventory;

import com.taggiot.handheld.net.dto.Dtos;

/** Satu baris daftar EPC: RSSI terakhir (MIN_VALUE = tidak ada) + jawaban finder (null = belum diketahui). */
public final class TagRow {
    public final String epc;
    public final int rssi;
    public final Dtos.FinderItem info;

    public TagRow(String epc, int rssi, Dtos.FinderItem info) { this.epc = epc; this.rssi = rssi; this.info = info; }

    public boolean resolved() { return info != null; }

    public boolean known() { return info != null && info.known; }

    public boolean unknown() { return info != null && !info.known; }
}

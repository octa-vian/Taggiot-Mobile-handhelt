package com.taggiot.handheld.reader;

import java.util.List;

/**
 * Antarmuka reader UHF milik Taggiot. Modul bisnis HANYA memakai antarmuka ini,
 * tidak pernah memanggil SDK vendor (SEUIC, Chainway, dst.) secara langsung.
 *
 * Aturan:
 * - open()/close() boleh memblok (panggil dari thread latar).
 * - Listener inventory dipanggil dari thread reader, bukan main thread.
 * - Satu reader, satu pemilik: ReaderManager memastikan hanya satu layar yang memakai reader.
 */
public interface UhfReader {

    interface InventoryListener {
        /** Satu batch hasil baca (bisa berisi EPC yang sama berulang). */
        void onTags(List<TagRead> batch);
        void onError(String message);
    }

    /** Nama untuk ditampilkan di Pengaturan, mis. "SEUIC UHF" / "Simulasi". */
    String name();

    boolean open();
    void close();
    boolean isOpen();

    boolean startInventory(InventoryListener listener);
    void stopInventory();
    boolean isInventoryRunning();

    /** Baca satu tag terkuat dalam batas waktu, atau null. */
    TagRead readSingle(int timeoutMs);

    /** Daya antena dalam dBm. */
    boolean setPower(int dbm);
    int getPower();
    int minPower();
    int maxPower();

    /** Mode EPC + TID (lebih lambat). */
    boolean setReadTid(boolean enabled);

    TagOpResult readMemory(String epc, MemoryBank bank, int wordOffset, int wordCount, String accessPassword);
    TagOpResult writeMemory(String epc, MemoryBank bank, int wordOffset, String dataHex, String accessPassword);
    /** Tulis EPC baru (termasuk penyesuaian PC word bila panjang berubah). */
    TagOpResult writeEpc(String currentEpc, String newEpc, String accessPassword);
    TagOpResult lock(String epc, LockTarget target, LockAction action, String accessPassword);
    TagOpResult kill(String epc, String killPassword);

    /** Info firmware/model untuk diagnostik dan laporan status ke TCC. */
    String firmwareInfo();
}

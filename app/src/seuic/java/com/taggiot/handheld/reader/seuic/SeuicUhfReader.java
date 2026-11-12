package com.taggiot.handheld.reader.seuic;

import android.content.Context;
import android.util.Log;

import com.seuic.uhf.EPC;
import com.seuic.uhf.UHFService;
import com.taggiot.handheld.reader.LockAction;
import com.taggiot.handheld.reader.LockTarget;
import com.taggiot.handheld.reader.MemoryBank;
import com.taggiot.handheld.reader.TagOpResult;
import com.taggiot.handheld.reader.TagRead;
import com.taggiot.handheld.reader.UhfReader;
import com.taggiot.handheld.util.Hex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptor reader UHF SEUIC (CRUISE2 / AUTOID 9U / UTouch dan sejenisnya) di atas uhf.jar SEUIC.
 *
 * File ini hanya dikompilasi bila app/libs/uhf.jar ada (lihat app/build.gradle). Jar itu hanya
 * stub kompilasi; implementasi asli adalah system library com.seuic.uhf di firmware perangkat.
 *
 * API mengikuti "Android UHF Interface User Manual" v1.1 dan UHFDemo bawaan SEUIC:
 * - Bank memori: 0 password, 1 EPC, 2 TID, 3 user. Offset dan panjang dalam BYTE.
 * - Daya 0–30 dBm. inventoryOnce timeout 0–500 ms.
 * - getTagIDs() mengembalikan daftar kumulatif (EPC unik + count) sejak inventoryStart.
 *
 * Belum dipetakan karena SDK tidak mendokumentasikannya: kill dan lock (lockTag hanya menerima
 * satu kode int tanpa tabel arti). Keduanya mengembalikan gagal, bukan menebak operasi yang
 * tidak bisa dibatalkan pada tag.
 */
public class SeuicUhfReader implements UhfReader {

    private static final String TAG = "SeuicUhfReader";
    private static final int POLL_INTERVAL_MS = 80;
    private static final int MAX_REPEAT_PER_POLL = 50;
    private static final long ERROR_THROTTLE_MS = 2000;
    private static final int LOG_FIRST_TAGS = 5;

    private final UHFService device;
    private volatile boolean open;
    private volatile boolean running;
    private Thread poller;

    public SeuicUhfReader(Context ctx) {
        device = UHFService.getInstance();
    }

    @Override public String name() { return "SEUIC UHF"; }

    /**
     * Selalu memanggil device.open(), juga bila sudah terbuka (sama seperti UHFDemo SEUIC di onResume).
     * uhfserver menutup modul sendiri saat layar mati, jadi flag lokal saja tidak bisa dipercaya.
     */
    @Override public synchronized boolean open() {
        boolean wasOpen = open;
        try {
            open = device.open();
        } catch (Throwable t) {
            Log.e(TAG, "open gagal", t);
            open = false;
        }
        if (!wasOpen || !open) {
            Log.i(TAG, "open() = " + open);
            if (open) { applyInventoryParams(); logModuleInfo(); }
        }
        return open;
    }

    /**
     * Konfigurasi inventory disimpan uhfserver untuk SEMUA aplikasi dan tidak bisa dibaca balik.
     * Default uhfserver (CRUISE2 fw 5.25) adalah S2 + target A: tiap tag hanya menjawab sekali per
     * persistensi, sehingga meter RSSI di Cari Alat diam. S0 + target A membaca tag berulang kali.
     * TOGGLETARGET ditolak firmware ini, jadi tidak diset.
     */
    private void applyInventoryParams() {
        setParam(UHFService.PARAMETER_INVENTORY_SESSION, 0);
        setParam(UHFService.PARAMETER_INVENTORY_SESSION_TARGET, 0);
        setParam(UHFService.PARAMETER_EXTENSIONS_TAGFOCUS, 0);
        setParam(UHFService.PARAMETER_EXTENSIONS_FASTID, 0);
    }

    /** Diagnostik sekali per pembukaan: bila tag tidak terbaca, cek daya dan region di logcat. */
    private void logModuleInfo() {
        try {
            Log.i(TAG, "modul: fw=" + device.getFirmwareVersion() + " region=" + device.getRegion()
                    + " power=" + device.getPower() + " suhu=" + device.getTemperature());
        } catch (Throwable t) {
            Log.w(TAG, "info modul gagal", t);
        }
    }

    @Override public synchronized void close() {
        stopInventory();
        if (!open) return;
        try { device.close(); } catch (Throwable t) { Log.w(TAG, "close", t); }
        open = false;
    }

    @Override public boolean isOpen() { return open; }

    @Override public synchronized boolean startInventory(InventoryListener listener) {
        if (running) return false;
        boolean started = open && tryInventoryStart();
        if (!started) {
            // Modul bisa sudah ditutup uhfserver (layar mati) atau dipakai aplikasi lain: buka ulang, coba sekali lagi.
            started = open() && tryInventoryStart();
        }
        if (!started) {
            listener.onError("Reader UHF tidak bisa mulai membaca");
            return false;
        }
        running = true;
        poller = new Thread(() -> pollLoop(listener), "seuic-uhf-poll");
        poller.start();
        return true;
    }

    private boolean tryInventoryStart() {
        // Mulai dari daftar kosong supaya hitungan tidak terbawa sesi sebelumnya.
        setParam(UHFService.PARAMETER_CLEAR_EPCLIST, 1);
        try {
            boolean ok = device.inventoryStart();
            Log.i(TAG, "inventoryStart() = " + ok);
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "inventoryStart gagal", t);
            return false;
        }
    }

    private void pollLoop(InventoryListener listener) {
        Map<String, Integer> lastCount = new HashMap<>();
        long lastErrorAt = 0;
        int logged = 0;
        while (running) {
            try {
                List<EPC> raw = device.getTagIDs();
                if (raw != null && !raw.isEmpty()) {
                    List<TagRead> batch = new ArrayList<>();
                    long now = System.currentTimeMillis();
                    for (EPC e : new ArrayList<>(raw)) {
                        String id = e.getId();
                        if (id == null || id.isEmpty()) continue;
                        int count = Math.max(e.count, 1);
                        Integer prev = lastCount.get(id);
                        // Daftar kumulatif: kirim hanya selisih bacaan baru. Bila count turun
                        // (daftar di-reset SDK), anggap semuanya bacaan baru.
                        int delta = prev == null ? count : (count >= prev ? count - prev : count);
                        lastCount.put(id, count);
                        if (logged < LOG_FIRST_TAGS) {
                            Log.i(TAG, "tag len=" + e.len + " rssi=" + e.rssi + " count=" + e.count);   // EPC tidak boleh masuk log
                            logged++;
                        }
                        for (int i = 0, n = Math.min(delta, MAX_REPEAT_PER_POLL); i < n; i++) {
                            batch.add(new TagRead(id, null, e.rssi, 1, now));
                        }
                    }
                    if (!batch.isEmpty()) listener.onTags(batch);
                }
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                if (now - lastErrorAt > ERROR_THROTTLE_MS) {
                    lastErrorAt = now;
                    Log.w(TAG, "polling gagal", t);
                    listener.onError(String.valueOf(t.getMessage()));
                }
            }
        }
    }

    @Override public void stopInventory() {
        if (!running) return;
        running = false;
        Thread p = poller;
        if (p != null) {
            try { p.join(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        poller = null;
        try { Log.i(TAG, "inventoryStop() = " + device.inventoryStop()); }
        catch (Throwable t) { Log.w(TAG, "inventoryStop", t); }
    }

    @Override public boolean isInventoryRunning() { return running; }

    @Override public TagRead readSingle(int timeoutMs) {
        if (!open) return null;
        try {
            EPC e = new EPC();
            int timeout = Math.max(0, Math.min(500, timeoutMs));   // batas SDK 0–500 ms
            if (device.inventoryOnce(e, timeout)) {
                String id = e.getId();
                if (id != null && !id.isEmpty()) {
                    return new TagRead(id, null, e.rssi, 1, System.currentTimeMillis());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "inventoryOnce", t);
        }
        return null;
    }

    @Override public boolean setPower(int dbm) {
        int p = Math.max(minPower(), Math.min(maxPower(), dbm));
        try {
            boolean ok = device.setPower(p);
            Log.i(TAG, "setPower(" + p + ") = " + ok);
            return ok;
        } catch (Throwable t) { Log.w(TAG, "setPower", t); return false; }
    }

    @Override public int getPower() {
        try {
            int p = device.getPower();                             // 0 = gagal menurut manual
            return p == 0 ? -1 : p;
        } catch (Throwable t) { return -1; }
    }

    @Override public int minPower() { return 5; }
    @Override public int maxPower() { return 30; }                // manual: 0–30 dBm

    @Override public boolean setReadTid(boolean enabled) {
        // Mode EPC + TID belum dipetakan (kandidat: PARAMETER_EXTENSIONS_FASTID, perlu uji di tag nyata).
        return !enabled;
    }

    @Override public TagOpResult readMemory(String epc, MemoryBank bank, int wordOffset, int wordCount, String pwd) {
        try {
            byte[] out = new byte[wordCount * 2];
            boolean ok = device.readTagData(Hex.decode(epc), Hex.decode(pwdOrDefault(pwd)),
                    bank.code, wordOffset * 2, wordCount * 2, out);   // offset & panjang dalam byte
            return ok ? TagOpResult.ok(Hex.encode(out)) : TagOpResult.fail("Baca gagal");
        } catch (Throwable t) {
            return TagOpResult.fail(t.getMessage());
        }
    }

    @Override public TagOpResult writeMemory(String epc, MemoryBank bank, int wordOffset, String dataHex, String pwd) {
        try {
            byte[] data = Hex.decode(dataHex);
            boolean ok = device.writeTagData(Hex.decode(epc), Hex.decode(pwdOrDefault(pwd)),
                    bank.code, wordOffset * 2, data.length, data);
            return ok ? TagOpResult.ok() : TagOpResult.fail("Tulis gagal");
        } catch (Throwable t) {
            return TagOpResult.fail(t.getMessage());
        }
    }

    @Override public TagOpResult writeEpc(String currentEpc, String newEpc, String pwd) {
        String cur = Hex.clean(currentEpc), nw = Hex.clean(newEpc);
        if (nw.length() % 4 != 0) return TagOpResult.fail("EPC harus kelipatan 16 bit");
        if (nw.length() != cur.length()) {
            // Panjang berubah → tulis PC word (word 1 bank EPC): panjang EPC dalam word di 5 bit teratas.
            int words = nw.length() / 4;
            String pc = String.format("%04X", words << 11);
            TagOpResult pcRes = writeMemory(cur, MemoryBank.EPC, 1, pc, pwd);
            if (!pcRes.success) return pcRes;
        }
        return writeMemory(cur, MemoryBank.EPC, 2, nw, pwd);       // EPC mulai word 2 bank EPC
    }

    @Override public TagOpResult lock(String epc, LockTarget target, LockAction action, String pwd) {
        return TagOpResult.fail("Lock belum didukung adaptor SEUIC (kode lockTag tidak terdokumentasi)");
    }

    @Override public TagOpResult kill(String epc, String killPassword) {
        return TagOpResult.fail("Kill tidak tersedia di SDK SEUIC");
    }

    @Override public String firmwareInfo() {
        try {
            String fw = device.getFirmwareVersion();
            String region = device.getRegion();
            return "SEUIC " + (fw == null ? "(versi tidak diketahui)" : fw.trim())
                    + (region == null ? "" : " · " + region);
        } catch (Throwable t) {
            return "SEUIC (versi tidak diketahui)";
        }
    }

    private void setParam(int id, int value) {
        try {
            boolean ok = device.setParameters(id, value);
            if (!ok) Log.w(TAG, "setParameters(" + id + ", " + value + ") ditolak");
        } catch (Throwable t) {
            Log.w(TAG, "setParameters(" + id + ") tidak didukung", t);
        }
    }

    private static String pwdOrDefault(String pwd) {
        return pwd == null || pwd.isEmpty() ? "00000000" : pwd;
    }
}

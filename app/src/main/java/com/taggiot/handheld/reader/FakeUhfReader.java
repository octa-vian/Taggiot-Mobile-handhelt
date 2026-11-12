package com.taggiot.handheld.reader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Reader simulasi untuk emulator, demo, dan unit test. Tidak memakai API Android.
 *
 * - Populasi tag diisi dari data contoh DemoGateway + beberapa tag "tidak terdaftar".
 * - RSSI tiap tag bergerak acak pelan sehingga mode Cari Tag terlihat hidup.
 * - Daya antena berpengaruh: daya rendah → tag lemah tidak terbaca.
 * - Satu tag "kosong" (EPC pabrik) selalu ada di dekat antena untuk mencoba Encode.
 */
public class FakeUhfReader implements UhfReader {

    public static final String BLANK_TAG_EPC = "E28011702000000000000A0B";

    private static final class SimTag {
        String epc;
        final String tid;
        final double base;  // dBm pada daya 30
        double rssi;        // bergerak acak di sekitar base (± 4 dB)
        SimTag(String epc, String tid, double rssi) { this.epc = epc; this.tid = tid; this.base = rssi; this.rssi = rssi; }
    }

    private final Map<String, SimTag> population = new LinkedHashMap<>();
    private final Random random;
    private volatile boolean open;
    private volatile boolean running;
    private volatile int power = 27;
    private volatile boolean readTid;
    private Thread worker;

    public FakeUhfReader(Collection<String> epcs, long seed) {
        this.random = new Random(seed);
        for (String e : epcs) put(e, -46 - random.nextInt(26));
        put(BLANK_TAG_EPC, -34);
    }

    public FakeUhfReader(Collection<String> epcs) { this(epcs, System.nanoTime()); }

    public synchronized void addTag(String epc, int rssiAtFullPower) { put(epc, rssiAtFullPower); }

    private void put(String epc, int rssiAtFullPower) {
        String e = epc.toUpperCase();
        String tid = String.format("E2801170%016X", (long) e.hashCode() & 0xFFFFFFFFL);
        population.put(e, new SimTag(e, tid, rssiAtFullPower));
    }

    public synchronized void removeTag(String epc) { population.remove(epc.toUpperCase()); }

    /** Ganti tag "di lapangan" (mis. saat operator pindah rak di mode simulasi). Tag kosong tetap ada. */
    public synchronized void setPopulation(Collection<String> epcs) {
        population.clear();
        for (String e : epcs) addTag(e, -46 - random.nextInt(26));
        addTag(BLANK_TAG_EPC, -34);
    }

    @Override public String name() { return "Simulasi (tanpa hardware)"; }

    @Override public boolean open() { open = true; return true; }

    @Override public void close() { stopInventory(); open = false; }

    @Override public boolean isOpen() { return open; }

    @Override public synchronized boolean startInventory(InventoryListener listener) {
        if (!open || running) return false;
        running = true;
        worker = new Thread(() -> {
            while (running) {
                try {
                    listener.onTags(sweep());
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException re) {
                    listener.onError(re.getMessage());
                }
            }
        }, "fake-uhf-inventory");
        worker.start();
        return true;
    }

    /** Satu putaran baca: setiap tag punya peluang terbaca tergantung RSSI efektif. */
    synchronized List<TagRead> sweep() {
        long now = System.currentTimeMillis();
        List<TagRead> out = new ArrayList<>();
        double powerOffset = power - 30;
        for (SimTag t : population.values()) {
            t.rssi = Math.max(t.base - 4, Math.min(t.base + 4, t.rssi + random.nextGaussian() * 0.8));
            double eff = t.rssi + powerOffset;
            if (eff < -72) continue;
            double p = Math.min(0.95, Math.max(0.15, (eff + 75) / 35.0));
            if (random.nextDouble() < p) {
                int r = (int) Math.round(eff + random.nextGaussian() * 1.5);
                out.add(new TagRead(t.epc, readTid ? t.tid : null, r, 1, now));
            }
        }
        return out;
    }

    @Override public void stopInventory() {
        running = false;
        Thread w = worker;
        if (w != null) {
            try { w.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        worker = null;
    }

    @Override public boolean isInventoryRunning() { return running; }

    @Override public synchronized TagRead readSingle(int timeoutMs) {
        if (!open) return null;
        TagRead best = null;
        for (TagRead r : sweep()) if (best == null || r.rssi > best.rssi) best = r;
        return best;
    }

    /** Simulasi label baru ditempel (mode encode massal): tambah satu tag kosong lagi di dekat antena. */
    public synchronized void addBlankTag() {
        String epc = String.format("E2801170200000000%07X", random.nextInt(0xFFFFFFF));
        addTag(epc, -34);
    }

    @Override public boolean setPower(int dbm) {
        if (dbm < minPower() || dbm > maxPower()) return false;
        power = dbm;
        return true;
    }

    @Override public int getPower() { return power; }
    @Override public int minPower() { return 5; }
    @Override public int maxPower() { return 30; }

    @Override public boolean setReadTid(boolean enabled) { readTid = enabled; return true; }

    @Override public synchronized TagOpResult readMemory(String epc, MemoryBank bank, int wordOffset, int wordCount, String pwd) {
        SimTag t = population.get(epc.toUpperCase());
        if (t == null) return TagOpResult.fail("Tag tidak ditemukan");
        String src = bank == MemoryBank.EPC ? t.epc : bank == MemoryBank.TID ? t.tid : "0000000000000000";
        int from = Math.min(src.length(), wordOffset * 4);
        int to = Math.min(src.length(), from + wordCount * 4);
        return TagOpResult.ok(src.substring(from, to));
    }

    @Override public synchronized TagOpResult writeMemory(String epc, MemoryBank bank, int wordOffset, String dataHex, String pwd) {
        if (!population.containsKey(epc.toUpperCase())) return TagOpResult.fail("Tag tidak ditemukan");
        return TagOpResult.ok();
    }

    @Override public synchronized TagOpResult writeEpc(String currentEpc, String newEpc, String pwd) {
        SimTag t = population.remove(currentEpc.toUpperCase());
        if (t == null) return TagOpResult.fail("Tag tidak ditemukan");
        if (newEpc == null || newEpc.length() % 4 != 0 || !newEpc.matches("[0-9A-Fa-f]+"))
            return TagOpResult.fail("EPC harus HEX kelipatan 4 digit");
        t.epc = newEpc.toUpperCase();
        population.put(t.epc, t);
        return TagOpResult.ok();
    }

    @Override public synchronized TagOpResult lock(String epc, LockTarget target, LockAction action, String pwd) {
        return population.containsKey(epc.toUpperCase()) ? TagOpResult.ok() : TagOpResult.fail("Tag tidak ditemukan");
    }

    @Override public synchronized TagOpResult kill(String epc, String killPwd) {
        if (killPwd == null || killPwd.equals("00000000")) return TagOpResult.fail("Kill password tidak boleh 00000000");
        return population.remove(epc.toUpperCase()) != null ? TagOpResult.ok() : TagOpResult.fail("Tag tidak ditemukan");
    }

    @Override public String firmwareInfo() { return "fake-uhf 1.0"; }
}

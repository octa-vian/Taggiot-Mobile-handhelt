package com.taggiot.handheld.reader;

/**
 * Mengubah RSSI tag target menjadi nilai 0–100 yang halus untuk mode "Cari Tag",
 * plus interval bunyi (makin dekat makin cepat).
 */
public final class GeigerMeter {

    private final int rssiFloor;   // dBm yang dianggap 0
    private final int rssiCeil;    // dBm yang dianggap 100
    private final double alpha;    // faktor EMA 0..1
    private final long lostAfterMs;

    private double smoothed = 0;
    private long lastSeen = 0;
    private boolean seen;

    public GeigerMeter(int rssiFloor, int rssiCeil, double alpha, long lostAfterMs) {
        if (rssiCeil <= rssiFloor) throw new IllegalArgumentException("rssiCeil harus > rssiFloor");
        this.rssiFloor = rssiFloor;
        this.rssiCeil = rssiCeil;
        this.alpha = alpha;
        this.lostAfterMs = lostAfterMs;
    }

    /** Default yang wajar untuk handheld UHF: -75 dBm → 0, -35 dBm → 100. */
    public GeigerMeter() { this(-75, -35, 0.35, 1500); }

    public synchronized void onRead(int rssi, long now) {
        double v = (rssi - rssiFloor) * 100.0 / (rssiCeil - rssiFloor);
        v = Math.max(0, Math.min(100, v));
        smoothed = !seen ? v : smoothed + alpha * (v - smoothed);
        lastSeen = now;
        seen = true;
    }

    /** Panggil berkala (mis. tiap 100 ms) agar nilai turun pelan bila tag tidak terbaca lagi. */
    public synchronized int value(long now) {
        if (!seen) return 0;
        long age = now - lastSeen;
        if (age > lostAfterMs) {
            double decay = Math.min(1.0, (age - lostAfterMs) / 1000.0);
            return (int) Math.round(smoothed * (1 - decay));
        }
        return (int) Math.round(smoothed);
    }

    public synchronized long millisSinceLastRead(long now) {
        return !seen ? Long.MAX_VALUE : now - lastSeen;
    }

    /** Interval bunyi: 0 = diam, selain itu 60–900 ms. */
    public static int beepIntervalMs(int value) {
        if (value <= 0) return 0;
        return (int) Math.round(900 - (value / 100.0) * 840);
    }

    /** Label kedekatan untuk operator. */
    public static String proximityLabel(int value) {
        if (value >= 80) return "Sangat dekat";
        if (value >= 55) return "Dekat";
        if (value >= 25) return "Mendekat";
        if (value > 0) return "Jauh";
        return "Belum terbaca";
    }

    public synchronized void reset() { smoothed = 0; lastSeen = 0; seen = false; }
}

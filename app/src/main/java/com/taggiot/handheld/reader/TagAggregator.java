package com.taggiot.handheld.reader;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Menyaring hasil baca menjadi daftar EPC unik + statistik. Thread-safe.
 * Dipakai Inventory, Opname, dan Encode.
 */
public final class TagAggregator {

    public static final class TagStat {
        public final String epc;
        public String tid;
        public int count;
        public int rssiLast;
        public int rssiMax = Integer.MIN_VALUE;
        public long firstSeen;
        public long lastSeen;

        TagStat(String epc) { this.epc = epc; }

        TagStat copy() {
            TagStat c = new TagStat(epc);
            c.tid = tid; c.count = count; c.rssiLast = rssiLast; c.rssiMax = rssiMax;
            c.firstSeen = firstSeen; c.lastSeen = lastSeen;
            return c;
        }
    }

    private final Map<String, TagStat> tags = new LinkedHashMap<>();
    private final ArrayDeque<Long> readTimes = new ArrayDeque<>();
    private final int minRssi;
    private long totalReads;

    /** @param minRssi abaikan bacaan lebih lemah dari nilai ini (dBm), mis. -70. Integer.MIN_VALUE = tanpa filter. */
    public TagAggregator(int minRssi) { this.minRssi = minRssi; }

    public TagAggregator() { this(Integer.MIN_VALUE); }

    /** @return jumlah EPC BARU dalam batch ini (untuk bunyi "tag baru"). */
    public synchronized int add(Collection<TagRead> batch) {
        int fresh = 0;
        for (TagRead r : batch) {
            if (r.epc.isEmpty() || r.rssi < minRssi) continue;
            TagStat s = tags.get(r.epc);
            if (s == null) {
                s = new TagStat(r.epc);
                s.firstSeen = r.timestamp;
                tags.put(r.epc, s);
                fresh++;
            }
            s.count++;
            s.rssiLast = r.rssi;
            if (r.rssi > s.rssiMax) s.rssiMax = r.rssi;
            s.lastSeen = r.timestamp;
            if (r.tid != null) s.tid = r.tid;
            totalReads++;
            readTimes.addLast(r.timestamp);
        }
        trim(System.currentTimeMillis());
        return fresh;
    }

    private void trim(long now) {
        while (!readTimes.isEmpty() && now - readTimes.peekFirst() > 1000) readTimes.removeFirst();
    }

    public synchronized int uniqueCount() { return tags.size(); }

    public synchronized long totalReads() { return totalReads; }

    /** Jumlah baca dalam 1 detik terakhir. */
    public synchronized int readsPerSecond(long now) {
        trim(now);
        return readTimes.size();
    }

    public synchronized boolean contains(String epc) { return tags.containsKey(epc); }

    public synchronized TagStat get(String epc) {
        TagStat s = tags.get(epc);
        return s == null ? null : s.copy();
    }

    /** Salinan daftar, urutan pertama kali terbaca. */
    public synchronized List<TagStat> snapshot() {
        List<TagStat> out = new ArrayList<>(tags.size());
        for (TagStat s : tags.values()) out.add(s.copy());
        return out;
    }

    public synchronized List<String> epcs() { return new ArrayList<>(tags.keySet()); }

    public synchronized void clear() {
        tags.clear();
        readTimes.clear();
        totalReads = 0;
    }
}

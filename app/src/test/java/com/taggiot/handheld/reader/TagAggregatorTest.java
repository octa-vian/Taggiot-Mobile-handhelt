package com.taggiot.handheld.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class TagAggregatorTest {
    @Test public void dedupesAndTracksStats() {
        TagAggregator a = new TagAggregator();
        long now = System.currentTimeMillis();
        int fresh = a.add(Arrays.asList(
                new TagRead("AAAA", null, -50, 1, now),
                new TagRead("aaaa", null, -42, 1, now + 10),
                new TagRead("BBBB", null, -60, 1, now + 20)));
        assertEquals(2, fresh);
        assertEquals(2, a.uniqueCount());
        TagAggregator.TagStat s = a.get("AAAA");
        assertEquals(2, s.count);
        assertEquals(-42, s.rssiMax);
        assertEquals(-42, s.rssiLast);
        assertEquals(0, a.add(Arrays.asList(new TagRead("BBBB", -55))));
    }

    @Test public void rssiFilterDropsWeakReads() {
        TagAggregator a = new TagAggregator(-70);
        a.add(Arrays.asList(new TagRead("AAAA", -75), new TagRead("BBBB", -65)));
        assertFalse(a.contains("AAAA"));
        assertTrue(a.contains("BBBB"));
    }
}

package com.taggiot.handheld.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class EpcTest {
    @Test public void trimsUppercasesAndAcceptsWholeWords() {
        assertEquals("3034257BF7194E4000000001", Epc.normalize("  3034 257b f719 4e40 0000 0001 "));
        assertEquals("ABCD", Epc.normalize("abcd"));
        assertEquals(124, Epc.normalize(repeat("A", 124)).length());
    }

    @Test public void rejectsNonWordLengthsAndNonHex() {
        assertNull(Epc.normalize(""));
        assertNull(Epc.normalize("ABC"));                 // bukan kelipatan 4
        assertNull(Epc.normalize("ABCDE"));
        assertNull(Epc.normalize("GHIJ"));
        assertNull(Epc.normalize(repeat("A", 128)));      // > 31 kata
        assertNull(Epc.normalize(null));
        assertFalse(Epc.isValid("12345"));
        assertTrue(Epc.isValid("E2801191A5030061FFFF739C"));
    }

    @Test public void normalizeAllDedupesAndKeepsOrder() {
        List<String> out = Epc.normalizeAll(Arrays.asList("bbbb", "AAAA", "BBBB", "xyz", "aaaa"));
        assertEquals(Arrays.asList("BBBB", "AAAA"), out);
    }

    @Test public void chunkRespectsMax() {
        List<List<Integer>> c = Epc.chunk(Arrays.asList(1, 2, 3, 4, 5), 2);
        assertEquals(3, c.size());
        assertEquals(Arrays.asList(5), c.get(2));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}

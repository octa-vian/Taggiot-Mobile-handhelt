package com.taggiot.handheld.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HexTest {
    @Test public void roundTripAndGrouping() {
        byte[] b = {(byte) 0xE2, 0x00, 0x68, (byte) 0x94};
        assertEquals("E2006894", Hex.encode(b));
        assertArrayEquals(b, Hex.decode("e2 00-68:94"));
        assertEquals("E280 6894 0000 5012", Hex.group4("E2806894 00005012"));
    }

    @Test public void group4LinesBreaksAfterThreeGroups() {
        assertEquals("E200 4718 C570\n6426 4E41 00FE", Hex.group4Lines("E2004718C57064264E4100FE"));
        assertEquals("E280 6894 0000", Hex.group4Lines("E28068940000"));   // pendek: tetap satu baris
    }
}

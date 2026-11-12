package com.taggiot.handheld.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GeigerMeterTest {
    @Test public void strongerSignalGivesHigherValueAndFasterBeep() {
        GeigerMeter g = new GeigerMeter(-75, -35, 1.0, 1500);
        g.onRead(-75, 1000);
        assertEquals(0, g.value(1000));
        g.onRead(-55, 1100);
        assertEquals(50, g.value(1100));
        g.onRead(-30, 1200);
        assertEquals(100, g.value(1200));
        assertTrue(GeigerMeter.beepIntervalMs(100) < GeigerMeter.beepIntervalMs(20));
        assertEquals(0, GeigerMeter.beepIntervalMs(0));
    }

    @Test public void valueDecaysWhenTagLost() {
        GeigerMeter g = new GeigerMeter(-75, -35, 1.0, 1000);
        g.onRead(-35, 0);
        assertEquals(100, g.value(500));
        assertEquals(0, g.value(2500));
    }
}

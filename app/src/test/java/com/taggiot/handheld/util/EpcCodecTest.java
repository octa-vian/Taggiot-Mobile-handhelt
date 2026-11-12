package com.taggiot.handheld.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** Vektor uji dari contoh GS1 EPC Tag Data Standard. */
public class EpcCodecTest {

    @Test public void giai96_matchesGs1Example() {
        String hex = EpcCodec.encodeGiai96(3, "0614141", 5432);
        assertEquals("3474257BF400000000001538", hex);
        EpcCodec.Giai g = EpcCodec.decodeGiai96(hex);
        assertEquals("urn:epc:tag:giai-96:3.0614141.5432", g.tagUri());
        assertEquals("urn:epc:id:giai:0614141.5432", g.pureIdentityUri());
    }

    @Test public void sgtin96_matchesGs1Example() {
        assertEquals("3074257BF7194E4000001A85", EpcCodec.encodeSgtin96(3, "0614141", "812345", 6789));
    }

    @Test public void scheme_detectedFromHeader() {
        assertEquals("GIAI-96", EpcCodec.schemeOf("3474257BF400000000001538"));
        assertEquals("SGTIN-96", EpcCodec.schemeOf("3074257BF7194E4000001A85"));
        assertEquals("EPC 96-bit", EpcCodec.schemeOf("E28068940000501A2B3C7A31"));
    }

    @Test public void giai96_roundTripAllPartitions() {
        String[] prefixes = {"123456789012", "12345678901", "1234567890", "123456789", "12345678", "1234567", "123456"};
        for (String p : prefixes) {
            String hex = EpcCodec.encodeGiai96(0, p, 987654L);
            EpcCodec.Giai g = EpcCodec.decodeGiai96(hex);
            assertEquals(p, g.companyPrefix);
            assertEquals(987654L, g.assetReference.longValue());
        }
    }

    @Test public void invalidInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> EpcCodec.encodeGiai96(0, "12345", 1));
        assertThrows(IllegalArgumentException.class, () -> EpcCodec.encodeSgtin96(1, "0614141", "12345", 1));
        assertThrows(IllegalArgumentException.class, () -> EpcCodec.decodeGiai96("3074257BF7194E4000001A85"));
    }
}

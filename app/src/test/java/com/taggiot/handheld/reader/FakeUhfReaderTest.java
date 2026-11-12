package com.taggiot.handheld.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class FakeUhfReaderTest {
    @Test public void inventoryDeliversTagsAndStops() throws Exception {
        FakeUhfReader r = new FakeUhfReader(Arrays.asList("AAAA0000", "BBBB0000", "CCCC0000"), 7);
        assertTrue(r.open());
        TagAggregator agg = new TagAggregator();
        CountDownLatch got = new CountDownLatch(3);
        r.startInventory(new UhfReader.InventoryListener() {
            @Override public void onTags(List<TagRead> batch) { agg.add(batch); for (int i = 0; i < batch.size(); i++) got.countDown(); }
            @Override public void onError(String m) {}
        });
        assertTrue(got.await(3, TimeUnit.SECONDS));
        r.stopInventory();
        assertTrue(agg.uniqueCount() >= 1);
        r.close();
    }

    @Test public void blankTagCanBeWritten() {
        FakeUhfReader r = new FakeUhfReader(Arrays.asList("AAAA0000"), 1);
        r.open();
        r.setPower(10);
        TagRead single = r.readSingle(100);
        assertNotNull(single);
        assertEquals(FakeUhfReader.BLANK_TAG_EPC, single.epc);
        assertTrue(r.writeEpc(single.epc, "3474257BF400000000001538", null).success);
        assertEquals("3474257BF400000000001538", r.readSingle(100).epc);
    }
}

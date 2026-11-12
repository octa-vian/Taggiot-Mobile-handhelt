package com.taggiot.handheld.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.taggiot.handheld.net.dto.Dtos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

/**
 * Skenario end-to-end handover (bagian Testing and acceptance) dijalankan terhadap Site Gateway tiruan:
 * drawer check D3, baca sebagian, kirim ulang batch sama (replayed), review supervisor → 1 missing, tutup.
 */
public class DemoGatewayTest {

    private String token;
    private long now = 1_762_740_000_000L;   // 2025-11-10T02:00Z
    private DemoGateway gw;
    private String deviceKey;

    @Before public void setUp() throws Exception {
        gw = new DemoGateway(() -> token, () -> now);
        deviceKey = gw.pair(DemoGateway.PAIRING_CODE);
    }

    private void login(String no) throws ApiException {
        token = gw.login(deviceKey, no, DemoGateway.DEMO_PIN).token;
    }

    private static ApiException expect(ThrowingRunnable r) {
        try { r.run(); } catch (ApiException e) { return e; } catch (Exception e) { throw new AssertionError(e); }
        fail("ApiException diharapkan");
        return null;
    }

    interface ThrowingRunnable { void run() throws Exception; }

    @Test public void pairingCodeWorksOnce() {
        ApiException e = expect(() -> gw.pair(DemoGateway.PAIRING_CODE));
        assertEquals(400, e.status);
        assertEquals(ApiException.PAIRING_INVALID, e.code);
    }

    @Test public void loginWrongPinAndUnknownOperatorLookTheSame() {
        ApiException a = expect(() -> gw.login(deviceKey, DemoGateway.OP_OPERATOR, "000000"));
        ApiException b = expect(() -> gw.login(deviceKey, "99999", DemoGateway.DEMO_PIN));
        assertEquals(401, a.status);
        assertEquals(a.status, b.status);
        assertEquals(a.code, b.code);
        assertEquals(a.userMessage(), b.userMessage());
    }

    @Test public void fiveWrongPinsLockWithRetryAfter() {
        for (int i = 0; i < 5; i++) expect(() -> gw.login(deviceKey, DemoGateway.OP_OPERATOR, "000000"));
        ApiException e = expect(() -> gw.login(deviceKey, DemoGateway.OP_OPERATOR, DemoGateway.DEMO_PIN));
        assertEquals(429, e.status);
        assertTrue(e.retryAfterSeconds > 0 && e.retryAfterSeconds <= 15 * 60);
        assertTrue(e.userMessage().contains("15 menit"));
    }

    @Test public void unknownDeviceKeyIsForbidden() {
        ApiException e = expect(() -> gw.login("bogus", DemoGateway.OP_OPERATOR, DemoGateway.DEMO_PIN));
        assertEquals(403, e.status);
    }

    @Test public void tokenExpiresAfter12Hours() throws Exception {
        login(DemoGateway.OP_OPERATOR);
        gw.me();
        now += DemoGateway.TOKEN_TTL_MS + 1;
        ApiException e = expect(() -> gw.me());
        assertTrue(e.needsRelogin());
    }

    @Test public void trivialPinRefused() throws Exception {
        login(DemoGateway.OP_OPERATOR);
        assertEquals(400, expect(() -> gw.changePin(DemoGateway.DEMO_PIN, "111111")).status);
        assertEquals(400, expect(() -> gw.changePin(DemoGateway.DEMO_PIN, "654321")).status);
        gw.changePin(DemoGateway.DEMO_PIN, "730518");
    }

    @Test public void finderShowsToolCodeAndTagStatus() throws Exception {
        login(DemoGateway.OP_VIEWER);
        DemoGateway.Tool rivet = null;
        for (DemoGateway.Tool t : gw.tools()) if ("released".equals(t.tagStatus)) rivet = t;
        Dtos.FinderItem f = gw.finderEpc(rivet.epc.toLowerCase());
        assertTrue(f.known);
        assertEquals(rivet.code, f.itemRef);
        assertEquals("released", f.tagStatus);
        assertFalse(gw.finderEpc(DemoGateway.UNKNOWN_EPC).known);
        // viewer tidak boleh menghitung
        assertEquals(403, expect(() -> gw.countList(null, null, null, 50)).status);
    }

    @Test public void drawerCheckEndToEnd() throws Exception {
        login(DemoGateway.OP_OPERATOR);
        Dtos.CountSession found = null;
        for (Dtos.CountSession s : gw.countList("opname", "open", null, 50).items) if ("D3".equals(s.area)) found = s;
        final Dtos.CountSession d3 = found;
        assertTrue(d3.number.matches("SO-\\d{8}-\\d{3}"));
        int expected = 0;
        for (Dtos.TallySku t : d3.tally.bySku) expected += t.expected == null ? 0 : t.expected;
        assertEquals(3, expected);

        // baca 2 dari 3 alat
        List<String> field = gw.fieldEpcs("D3");
        List<String> tools = new ArrayList<>();
        for (DemoGateway.Tool t : gw.tools()) if (t.drawer.equals("D3")) tools.add(t.epc);
        Dtos.ReadsRequest req = new Dtos.ReadsRequest();
        req.id = d3.id; req.batchId = UUID.randomUUID().toString(); req.area = "D3";
        req.reads = new ArrayList<>();
        for (String e : Arrays.asList(tools.get(0), tools.get(1))) { Dtos.Read r = new Dtos.Read(); r.epc = e; req.reads.add(r); }
        Dtos.ReadsResponse first = gw.countReads(req);
        assertEquals(2, first.accepted);
        assertFalse(first.replayed);

        // Wi-Fi putus setelah server menerima → kirim ulang batch_id sama: replayed, tidak ganda
        Dtos.ReadsResponse again = gw.countReads(req);
        assertTrue(again.replayed);
        assertEquals(2, again.accepted);
        assertEquals(2, again.tally.distinctEpcs);

        // operator tidak boleh review
        assertEquals(403, expect(() -> gw.countReview(d3.id)).status);

        login(DemoGateway.OP_SUPERVISOR);
        Dtos.ReviewResponse rev = gw.countReview(d3.id);
        assertEquals("review", rev.session.status);
        assertEquals(Integer.valueOf(1), rev.byKind.get("missing"));

        // reads setelah review → 409 count_state (batch baru), tapi replay batch lama tetap jawaban pertama
        Dtos.ReadsRequest late = new Dtos.ReadsRequest();
        late.id = d3.id; late.batchId = UUID.randomUUID().toString();
        Dtos.Read r = new Dtos.Read(); r.epc = tools.get(2); late.reads = Arrays.asList(r);
        assertEquals(ApiException.COUNT_STATE, expect(() -> gw.countReads(late)).code);
        assertTrue(gw.countReads(req).replayed);

        Dtos.Difference missing = gw.countDifferences(d3.id, "missing", null, null, 50).items.get(0);
        assertEquals(tools.get(2), missing.epc);
        gw.countResolve(d3.id, missing.id, "accepted", "Dipinjam WO 1234");

        assertEquals(400, expect(() -> gw.countClose(d3.id, "ok")).status);   // komentar < 3
        Dtos.CountSession closed = gw.countClose(d3.id, "Drawer check D3 selesai");
        assertEquals("closed", closed.status);
        assertTrue(field.contains(DemoGateway.UNKNOWN_EPC));
    }

    @Test public void inventoryCountCreatorMayClose() throws Exception {
        login(DemoGateway.OP_OPERATOR);
        Dtos.CountCreateRequest c = new Dtos.CountCreateRequest();
        c.kind = "inventory"; c.area = "Rak A3";
        Dtos.CountSession s = gw.countCreate(c);
        assertTrue(s.number.startsWith("IC-"));
        c.kind = "opname";
        assertEquals(403, expect(() -> gw.countCreate(c)).status);   // opname perlu supervisor
        assertEquals("closed", gw.countClose(s.id, "Selesai hitung").status);
    }

    @Test public void readsValidateEpcAndBatchSize() throws Exception {
        login(DemoGateway.OP_OPERATOR);
        Dtos.CountCreateRequest c = new Dtos.CountCreateRequest();
        c.kind = "inventory";
        Dtos.CountSession s = gw.countCreate(c);
        Dtos.ReadsRequest req = new Dtos.ReadsRequest();
        req.id = s.id; req.batchId = UUID.randomUUID().toString();
        Dtos.Read bad = new Dtos.Read(); bad.epc = "XYZ";
        req.reads = Arrays.asList(bad);
        ApiException e = expect(() -> gw.countReads(req));
        assertEquals("/reads/0/epc", e.firstDetailPath());
        req.reads = new ArrayList<>();
        for (int i = 0; i < 1001; i++) { Dtos.Read r = new Dtos.Read(); r.epc = String.format("%024X", i + 1); req.reads.add(r); }
        assertEquals(400, expect(() -> gw.countReads(req)).status);
    }
}

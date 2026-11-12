package com.taggiot.handheld.ui.count;

import android.widget.TextView;

import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.ui.UiUtil;

/** Teks dan angka sesi hitung yang dipakai beberapa layar. */
public final class CountUi {
    private CountUi() {}

    public static int expected(Dtos.Tally t) {
        if (t == null || t.bySku == null) return 0;
        int n = 0;
        for (Dtos.TallySku s : t.bySku) if (s.expected != null) n += s.expected;
        return n;
    }

    /** Jumlah EPC terhitung yang memang diharapkan (untuk progres opname). */
    public static int countedExpected(Dtos.Tally t) {
        if (t == null || t.bySku == null) return 0;
        int n = 0;
        for (Dtos.TallySku s : t.bySku) if (s.expected != null) n += Math.min(s.counted, s.expected);
        return n;
    }

    public static int distinct(Dtos.Tally t) { return t == null ? 0 : t.distinctEpcs; }

    /**
     * Area sesi: field area (handover GMF) bila ada, selain itu satu-satunya area di tally.by_area.
     * null bila belum ditentukan.
     */
    public static String area(Dtos.CountSession s) {
        if (s == null) return null;
        if (s.area != null && !s.area.isEmpty()) return s.area;
        if (s.tally != null && s.tally.byArea != null && s.tally.byArea.size() == 1) {
            String a = s.tally.byArea.get(0).area;
            if (a != null && !a.isEmpty()) return a;
        }
        return null;
    }

    public static String kindLabel(Dtos.CountSession s) {
        if ("opname".equals(s.kind)) return area(s) != null ? "Drawer check" : "Stock opname";
        return "Inventory count";
    }

    public static String creatorLabel(String createdBy) {
        if (createdBy == null) return "";
        if (createdBy.startsWith("apikey:") || createdBy.startsWith("api_key:")) return "dibuat GMF";
        if (createdBy.startsWith("operator:")) return "dibuat operator " + createdBy.substring(9);
        return "dibuat " + createdBy;
    }

    public static boolean isCreator(Dtos.CountSession s, String opNo) {
        return s != null && s.createdBy != null && s.createdBy.equals("operator:" + opNo);
    }

    public static void statusBadge(TextView tv, String status) {
        switch (status == null ? "" : status) {
            case "open": UiUtil.badge(tv, UiUtil.Tone.INFO, "Terbuka"); break;
            case "review": UiUtil.badge(tv, UiUtil.Tone.WARN, "Review"); break;
            case "closed": UiUtil.badge(tv, UiUtil.Tone.OK, "Ditutup"); break;
            case "cancelled": UiUtil.badge(tv, UiUtil.Tone.IDLE, "Dibatalkan"); break;
            default: UiUtil.badge(tv, UiUtil.Tone.IDLE, status == null ? "?" : status);
        }
        tv.setCompoundDrawables(null, null, null, null);
    }

    public static String diffKindLabel(String kind) {
        switch (kind == null ? "" : kind) {
            case "missing": return "Hilang (tidak terbaca)";
            case "extra": return "Lebih (bukan isi laci ini)";
            case "unknown": return "Tag tidak dikenal";
            case "short": return "Kurang jumlah";
            case "over": return "Lebih jumlah";
            case "area_conflict": return "Terbaca di area lain";
            default: return kind;
        }
    }
}

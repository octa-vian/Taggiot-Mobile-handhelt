package com.taggiot.handheld.ui;

import android.content.Context;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.taggiot.handheld.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class UiUtil {
    private UiUtil() {}

    public enum Tone { OK, WARN, BAD, IDLE, INFO }

    private static final Locale ID = Locale.forLanguageTag("id-ID");

    /** Badge status: selalu warna + teks (ikon lewat drawable kiri). */
    public static void badge(TextView tv, Tone tone, String text) {
        Context c = tv.getContext();
        int bg, fg, icon;
        switch (tone) {
            case OK: bg = R.drawable.bg_badge_ok; fg = R.color.status_ok; icon = R.drawable.ic_check; break;
            case WARN: bg = R.drawable.bg_badge_warn; fg = R.color.status_warn; icon = R.drawable.ic_minus; break;
            case BAD: bg = R.drawable.bg_badge_bad; fg = R.color.status_bad; icon = R.drawable.ic_close; break;
            case INFO: bg = R.drawable.bg_badge_info; fg = R.color.status_info; icon = R.drawable.ic_plus; break;
            default: bg = R.drawable.bg_badge_idle; fg = R.color.status_idle; icon = 0; break;
        }
        tv.setBackgroundResource(bg);
        tv.setTextColor(ContextCompat.getColor(c, fg));
        tv.setText(text);
        if (icon != 0) {
            android.graphics.drawable.Drawable d = ContextCompat.getDrawable(c, icon);
            if (d != null) {
                d = d.mutate();
                int s = (int) (12 * c.getResources().getDisplayMetrics().density);
                d.setBounds(0, 0, s, s);
                d.setTint(ContextCompat.getColor(c, fg));
                tv.setCompoundDrawables(d, null, null, null);
                tv.setCompoundDrawablePadding((int) (4 * c.getResources().getDisplayMetrics().density));
            }
        } else {
            tv.setCompoundDrawables(null, null, null, null);
        }
    }

    public static String time(long ms) { return new SimpleDateFormat("HH:mm", ID).format(new Date(ms)); }

    public static String dayTime(long ms) { return new SimpleDateFormat("d MMM HH:mm", ID).format(new Date(ms)); }

    public static String number(long n) { return String.format(ID, "%,d", n); }

    public static String signed(int n) { return n > 0 ? "+" + n : String.valueOf(n).replace('-', '−'); }

    /** Kode alat GMF (item_ref) bila ada, selain itu SKU. */
    public static String toolCode(com.taggiot.handheld.net.dto.Dtos.FinderItem f) {
        if (f == null) return "";
        if (f.itemRef != null && !f.itemRef.isEmpty()) return f.itemRef;
        return f.sku == null ? "" : f.sku;
    }

    /** Label tag_status untuk operator: active = di crib, released = dipinjam, blocked = label lama. */
    public static String tagStatusLabel(String s) {
        if (s == null) return "Status tidak diketahui";
        switch (s) {
            case "active": return "Di crib (tidak boleh keluar)";
            case "released": return "Dipinjam (released)";
            case "blocked": return "Label lama / diblokir";
            default: return s;
        }
    }

    public static Tone tagStatusTone(String s) {
        if (s == null) return Tone.IDLE;
        switch (s) {
            case "active": return Tone.OK;
            case "released": return Tone.INFO;
            case "blocked": return Tone.BAD;
            default: return Tone.IDLE;
        }
    }

    public static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) return s + " dtk";
        if (s < 3600) return (s / 60) + " mnt";
        return (s / 3600) + " jam";
    }
}

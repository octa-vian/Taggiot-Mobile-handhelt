package com.taggiot.handheld.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.taggiot.handheld.R;

/** 4 batang kekuatan sinyal dari RSSI. */
public class SignalBarsView extends View {
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int bars;

    public SignalBarsView(Context c) { this(c, null); }

    public SignalBarsView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        on.setColor(ContextCompat.getColor(c, R.color.brand_500));
        off.setColor(ContextCompat.getColor(c, R.color.line));
    }

    /** -45 dBm atau lebih kuat = 4 batang, lebih lemah dari -70 = 1 batang. */
    public void setRssi(int rssi) {
        int b = rssi >= -45 ? 4 : rssi >= -55 ? 3 : rssi >= -65 ? 2 : 1;
        if (b != bars) { bars = b; invalidate(); }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float gap = w * 0.12f, bw = (w - 3 * gap) / 4f, rad = bw / 2f;
        for (int i = 0; i < 4; i++) {
            float bh = h * (0.4f + 0.2f * i);
            float x = i * (bw + gap);
            r.set(x, h - bh, x + bw, h);
            c.drawRoundRect(r, rad, rad, i < bars ? on : off);
        }
    }
}

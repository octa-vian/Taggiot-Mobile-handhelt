package com.taggiot.handheld.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.taggiot.handheld.R;

/** Pengukur sinyal berbentuk cincin 270° untuk mode Cari Tag. Nilai 0–100. */
public class GaugeView extends View {
    private static final float START = 135f, SWEEP = 270f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private float current;     // nilai yang digambar (dianimasikan)
    private float target;

    public GaugeView(Context c) { this(c, null); }

    public GaugeView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        float stroke = dp(18);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(ContextCompat.getColor(c, R.color.line));
        value.setStyle(Paint.Style.STROKE);
        value.setStrokeWidth(stroke);
        value.setStrokeCap(Paint.Cap.ROUND);
        core.setColor(Color.WHITE);
        core.setShadowLayer(dp(10), 0, dp(4), 0x1A0D1B2A);
        halo.setColor(ContextCompat.getColor(c, R.color.brand_500));
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // shadowLayer
    }

    public void setValue(int v) {
        target = Math.max(0, Math.min(100, v));
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = dp(18);
        arc.set(pad, pad, w - pad, h - pad);
        int[] colors = {
                ContextCompat.getColor(getContext(), R.color.brand_700),
                ContextCompat.getColor(getContext(), R.color.brand_500),
                ContextCompat.getColor(getContext(), R.color.brand_500),
                ContextCompat.getColor(getContext(), R.color.brand_300),
                ContextCompat.getColor(getContext(), R.color.brand_700)};
        float[] pos = {0f, 0.3f, 0.55f, 0.75f, 1f};
        SweepGradient g = new SweepGradient(w / 2f, h / 2f, colors, pos);
        Matrix m = new Matrix();
        m.setRotate(START - 5, w / 2f, h / 2f);
        g.setLocalMatrix(m);
        value.setShader(g);
    }

    @Override protected void onDraw(Canvas c) {
        current += (target - current) * 0.25f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        halo.setAlpha((int) (40 * current / 100f));
        c.drawCircle(cx, cy, Math.min(cx, cy), halo);
        c.drawArc(arc, START, SWEEP, false, track);
        if (current > 0.5f) c.drawArc(arc, START, SWEEP * current / 100f, false, value);
        c.drawCircle(cx, cy, arc.width() / 2f - dp(26), core);
        if (Math.abs(target - current) > 0.5f) postInvalidateOnAnimation();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}

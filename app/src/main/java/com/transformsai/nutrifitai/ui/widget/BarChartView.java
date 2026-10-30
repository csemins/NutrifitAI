package com.transformsai.nutrifitai.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Daily or monthly values as rounded bars, or as a line for longer periods ({@link #setLine}). Day labels
 * below, an optional dashed target line, and days with no data shown explicitly (a stub under a bar, a gap
 * in the line): missing is never drawn as zero.
 */
public class BarChartView extends View {
    public static final class Bar {
        public String label;
        @Nullable
        public Float value;      // null = no data
        public String valueLabel;
        public int color;
        public boolean highlight;
    }

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint missing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint target = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private List<Bar> bars = new ArrayList<>();
    @Nullable
    private Float targetValue;
    private boolean showValues = true;
    private boolean asLine;
    private float reveal = 1f;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint point = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public BarChartView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        text.setColor(ContextCompat.getColor(c, R.color.nl_text_3));
        text.setTextSize(Dp.sp(c, 11));
        text.setTextAlign(Paint.Align.CENTER);
        valueText.setColor(ContextCompat.getColor(c, R.color.nl_text_2));
        valueText.setTextSize(Dp.sp(c, 10.5f));
        valueText.setTextAlign(Paint.Align.CENTER);
        missing.setColor(ContextCompat.getColor(c, R.color.nl_surface_3));
        target.setStyle(Paint.Style.STROKE);
        target.setStrokeWidth(Dp.px(c, 1.2f));
        target.setColor(ContextCompat.getColor(c, R.color.nl_text_3));
        target.setPathEffect(new DashPathEffect(new float[]{Dp.px(c, 5), Dp.px(c, 4)}, 0));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Dp.px(c, 2.4f));
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        try {
            android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(c, R.font.jakarta_medium);
            text.setTypeface(tf);
            valueText.setTypeface(tf);
        } catch (Exception ignored) {
            // default font
        }
    }

    /** Line instead of bars (trends over a month or year read better as a line). */
    public void setLine(boolean line, int lineColor) {
        asLine = line;
        stroke.setColor(lineColor);
        invalidate();
    }

    public void setData(List<Bar> data, @Nullable Float target, boolean values) {
        bars = data;
        targetValue = target;
        showValues = values;
        if (ValueAnimator.areAnimatorsEnabled()) {
            ValueAnimator v = ValueAnimator.ofFloat(0, 1).setDuration(650);
            v.addUpdateListener(x -> {
                reveal = (float) x.getAnimatedValue();
                invalidate();
            });
            v.start();
        } else {
            reveal = 1;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bars.isEmpty()) return;
        float labelH = Dp.px(getContext(), 22);
        float valueH = showValues ? Dp.px(getContext(), 18) : Dp.px(getContext(), 4);
        float chartTop = valueH;
        float chartBottom = getHeight() - labelH;
        float chartH = chartBottom - chartTop;
        float max = targetValue == null ? 0 : targetValue * 1.15f;
        for (Bar b : bars) if (b.value != null) max = Math.max(max, b.value);
        if (max <= 0) max = 1;
        int n = bars.size();
        float slot = (float) getWidth() / n;
        if (asLine) {
            drawLine(c, slot, chartTop, chartBottom, max);
            return;
        }
        float bw = Math.min(slot * (n > 12 ? 0.6f : 0.74f), Dp.px(getContext(), 44));
        float radius = Math.min(bw / 2.6f, Dp.px(getContext(), 12));
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            float cx = slot * i + slot / 2f;
            if (b.label != null && (n <= 12 || i % Math.max(1, n / 6) == 0)) {
                c.drawText(b.label, cx, getHeight() - Dp.px(getContext(), 5), text);
            }
            if (b.value == null) {
                float stub = Dp.px(getContext(), 4);
                r.set(cx - bw / 2, chartBottom - stub, cx + bw / 2, chartBottom);
                c.drawRoundRect(r, stub / 2, stub / 2, missing);
                continue;
            }
            float h = Math.max(b.value / max * chartH * reveal, Dp.px(getContext(), 6));
            r.set(cx - bw / 2, chartBottom - h, cx + bw / 2, chartBottom);
            int top = b.highlight ? lighten(b.color) : b.color;
            bar.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, darken(b.color), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, radius, radius, bar);
            if (showValues && b.valueLabel != null && n <= 12) {
                c.drawText(b.valueLabel, cx, r.top - Dp.px(getContext(), 6), valueText);
            }
        }
        if (targetValue != null && targetValue > 0) {
            float y = chartBottom - targetValue / max * chartH;
            c.drawLine(0, y, getWidth(), y, target);
        }
    }

    /** Segments join consecutive days with data; a missing day breaks the line. */
    private void drawLine(Canvas c, float slot, float chartTop, float chartBottom, float max) {
        int n = bars.size();
        float chartH = chartBottom - chartTop;
        if (targetValue != null && targetValue > 0) {
            float y = chartBottom - targetValue / max * chartH;
            c.drawLine(0, y, getWidth(), y, target);
        }
        path.reset();
        boolean open = false;
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            float cx = slot * i + slot / 2f;
            if (b.label != null && (n <= 12 || i % Math.max(1, n / 6) == 0)) {
                c.drawText(b.label, cx, getHeight() - Dp.px(getContext(), 5), text);
            }
            if (b.value == null) {
                open = false;
                continue;
            }
            float y = chartBottom - b.value / max * chartH * reveal;
            if (open) path.lineTo(cx, y);
            else path.moveTo(cx, y);
            open = true;
        }
        c.drawPath(path, stroke);
        float r = Dp.px(getContext(), n > 12 ? 2.6f : 3.6f);
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            if (b.value == null) continue;
            float cx = slot * i + slot / 2f, y = chartBottom - b.value / max * chartH * reveal;
            point.setColor(b.color);
            c.drawCircle(cx, y, b.highlight ? r * 1.7f : r, point);
            if (showValues && b.valueLabel != null && n <= 12) c.drawText(b.valueLabel, cx, y - Dp.px(getContext(), 9), valueText);
        }
    }

    private static int lighten(int c) {
        return mix(c, 0xFFFFFFFF, 0.18f);
    }

    private static int darken(int c) {
        return mix(c, 0xFF000000, 0.35f);
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}

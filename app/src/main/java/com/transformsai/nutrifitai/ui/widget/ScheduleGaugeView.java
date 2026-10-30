package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;

/**
 * Half-dial of ticks (the "Training schedule" gauge): ticks fill in lime up to the time of day of the
 * scheduled workout and a needle marks it. The time and countdown are drawn by the layout below it.
 */
public class ScheduleGaugeView extends View {
    private static final int TICKS = 41;
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float fraction = 0.75f;
    private final int lime, dim;

    public ScheduleGaugeView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        lime = ContextCompat.getColor(c, R.color.nl_lime);
        dim = ContextCompat.getColor(c, R.color.nl_stroke_strong);
        tick.setStrokeCap(Paint.Cap.ROUND);
        needle.setStrokeCap(Paint.Cap.ROUND);
        needle.setColor(lime);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(Dp.px(c, 1.2f));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** @param minuteOfDay 0..1439 */
    public void setMinuteOfDay(int minuteOfDay) {
        fraction = Math.max(0, Math.min(1, minuteOfDay / 1440f));
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) (width * 0.5f));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h * 0.98f;
        float r = Math.min(w / 2f, h) * 0.86f;
        float len = r * 0.16f;
        // soft green glow under the dial
        glow.setShader(new RadialGradient(cx, cy, r * 0.9f, CalorieRingView.alpha(lime, 70), 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 0.9f, glow);
        arc.setShader(new LinearGradient(0, 0, w, 0, CalorieRingView.alpha(lime, 30), CalorieRingView.alpha(lime, 200), Shader.TileMode.CLAMP));
        c.drawArc(cx - r * 1.08f, cy - r * 1.08f, cx + r * 1.08f, cy + r * 1.08f, 180, 180, false, arc);
        int active = Math.round(fraction * (TICKS - 1));
        for (int i = 0; i < TICKS; i++) {
            double ang = Math.PI + Math.PI * i / (TICKS - 1);
            boolean major = i % 5 == 0;
            float l = major ? len * 1.35f : len;
            float x1 = cx + (float) Math.cos(ang) * r, y1 = cy + (float) Math.sin(ang) * r;
            float x2 = cx + (float) Math.cos(ang) * (r - l), y2 = cy + (float) Math.sin(ang) * (r - l);
            tick.setStrokeWidth(Dp.px(getContext(), major ? 2.4f : 1.8f));
            int d = Math.abs(i - active);
            tick.setColor(i <= active ? CalorieRingView.alpha(lime, Math.max(90, 255 - d * 10)) : dim);
            c.drawLine(x1, y1, x2, y2, tick);
        }
        double ang = Math.PI + Math.PI * fraction;
        needle.setStrokeWidth(Dp.px(getContext(), 2.6f));
        float nx = cx + (float) Math.cos(ang) * (r * 0.98f), ny = cy + (float) Math.sin(ang) * (r * 0.98f);
        float bx = cx + (float) Math.cos(ang) * (r * 0.45f), by = cy + (float) Math.sin(ang) * (r * 0.45f);
        needle.setShadowLayer(Dp.px(getContext(), 6), 0, 0, lime);
        c.drawLine(bx, by, nx, ny, needle);
        needle.clearShadowLayer();
    }
}

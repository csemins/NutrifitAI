package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.domain.Bmi;

/** A four-band BMI scale (15-40) with a marker that glides to the current value. */
public class BmiScaleView extends View {
    private static final float MIN = 15f, MAX = 40f;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG), marker = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int[] colors;
    private float value = Float.NaN, shown = Float.NaN;

    public BmiScaleView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        colors = new int[]{ContextCompat.getColor(c, R.color.nl_water), ContextCompat.getColor(c, R.color.nl_lime),
                ContextCompat.getColor(c, R.color.nl_carbs), ContextCompat.getColor(c, R.color.nl_protein)};
        marker.setColor(ContextCompat.getColor(c, R.color.nl_text));
        ring.setColor(ContextCompat.getColor(c, R.color.nl_surface));
    }

    public void setValue(double bmi) {
        value = (float) bmi;
        if (Float.isNaN(shown)) shown = value;
        animate().cancel();
        float from = shown;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, value);
        va.setDuration(350);
        va.addUpdateListener(x -> {
            shown = (float) x.getAnimatedValue();
            invalidate();
        });
        va.start();
    }

    private float x(float v, float left, float width) {
        return left + width * (Math.max(MIN, Math.min(MAX, v)) - MIN) / (MAX - MIN);
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float h = 8 * d, cy = getHeight() / 2f, left = 9 * d, width = getWidth() - 18 * d;
        float[] edges = {MIN, (float) Bmi.UNDER, (float) Bmi.OVER, (float) Bmi.OBESE, MAX};
        for (int i = 0; i < 4; i++) {
            band.setColor(colors[i]);
            r.set(x(edges[i], left, width) + (i == 0 ? 0 : d), cy - h / 2, x(edges[i + 1], left, width) - (i == 3 ? 0 : d), cy + h / 2);
            c.drawRoundRect(r, h / 2, h / 2, band);
        }
        if (Float.isNaN(shown)) return;
        float mx = x(shown, left, width);
        c.drawCircle(mx, cy, 9 * d, ring);
        c.drawCircle(mx, cy, 6 * d, marker);
    }
}

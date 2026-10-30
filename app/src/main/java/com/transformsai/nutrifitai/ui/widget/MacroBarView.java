package com.transformsai.nutrifitai.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;

/** Rounded progress bar. Values above 100% show a bright end-cap marker. */
public class MacroBarView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float barH;
    private float fraction;
    private float shown;

    public MacroBarView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        TypedArray t = c.obtainStyledAttributes(a, R.styleable.MacroBarView);
        fill.setColor(t.getColor(R.styleable.MacroBarView_barColor, ContextCompat.getColor(c, R.color.nl_lime)));
        barH = t.getDimension(R.styleable.MacroBarView_barHeight, Dp.px(c, 6));
        t.recycle();
        track.setColor(ContextCompat.getColor(c, R.color.nl_surface_3));
        marker.setColor(ContextCompat.getColor(c, R.color.nl_text));
    }

    public void setColor(int color) {
        fill.setColor(color);
        invalidate();
    }

    public void setFraction(float f) {
        fraction = Math.max(0, f);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            shown = fraction;
            invalidate();
            return;
        }
        ValueAnimator v = ValueAnimator.ofFloat(shown, fraction).setDuration(600);
        v.addUpdateListener(x -> {
            shown = (float) x.getAnimatedValue();
            invalidate();
        });
        v.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int height = resolveSize((int) Math.ceil(barH), h);
        setMeasuredDimension(MeasureSpec.getSize(w), height);
    }

    @Override
    protected void onDraw(Canvas c) {
        float top = (getHeight() - barH) / 2f;
        r.set(0, top, getWidth(), top + barH);
        c.drawRoundRect(r, barH / 2, barH / 2, track);
        float w = Math.min(shown, 1f) * getWidth();
        if (w > 0) {
            r.set(0, top, Math.max(w, barH), top + barH);
            c.drawRoundRect(r, barH / 2, barH / 2, fill);
        }
        if (shown > 1f) {
            float x = getWidth() - barH;
            c.drawCircle(x + barH / 2, top + barH / 2, barH * 0.35f, marker);
        }
    }
}

package com.transformsai.nutrifitai.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;

/**
 * Smooth progress ring with a lime sweep gradient, rounded caps and a soft glow. Progress above 1
 * draws the overflow in amber so "over target" is visible without relying on the number alone.
 */
public class CalorieRingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint over = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float stroke;
    private int startColor;
    private int endColor;
    private float progress;
    private float shown;
    private ValueAnimator anim;

    public CalorieRingView(Context c) {
        this(c, null);
    }

    public CalorieRingView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        TypedArray t = c.obtainStyledAttributes(a, R.styleable.CalorieRingView);
        stroke = t.getDimension(R.styleable.CalorieRingView_ringStroke, Dp.px(c, 16));
        startColor = t.getColor(R.styleable.CalorieRingView_ringStartColor, ContextCompat.getColor(c, R.color.nl_lime_deep));
        endColor = t.getColor(R.styleable.CalorieRingView_ringEndColor, ContextCompat.getColor(c, R.color.nl_lime_bright));
        int trackColor = t.getColor(R.styleable.CalorieRingView_ringTrackColor, ContextCompat.getColor(c, R.color.nl_surface_3));
        t.recycle();
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(trackColor);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(stroke * 1.8f);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setAlpha(24);
        over.setStyle(Paint.Style.STROKE);
        over.setStrokeWidth(stroke * 0.55f);
        over.setStrokeCap(Paint.Cap.ROUND);
        over.setColor(ContextCompat.getColor(c, R.color.nl_carbs));
    }

    public void setColors(int start, int end) {
        startColor = start;
        endColor = end;
        invalidate();
    }

    /** @param p fraction of target (may exceed 1). */
    public void setProgress(float p, boolean animate) {
        progress = Math.max(0, p);
        if (anim != null) anim.cancel();
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            shown = progress;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(shown, progress);
        anim.setDuration(900);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(v -> {
            shown = (float) v.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = stroke * 1.2f;
        float size = Math.min(w, h);
        float l = (w - size) / 2f + pad;
        float t = (h - size) / 2f + pad;
        box.set(l, t, l + size - 2 * pad, t + size - 2 * pad);
        SweepGradient sg = new SweepGradient(w / 2f, h / 2f, new int[]{startColor, endColor, endColor}, new float[]{0f, 0.75f, 1f});
        Matrix m = new Matrix();
        m.setRotate(-95, w / 2f, h / 2f);
        sg.setLocalMatrix(m);
        arc.setShader(sg);
        glow.setShader(null);
        glow.setColor(endColor);
        glow.setAlpha(24);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawArc(box, 0, 360, false, track);
        float main = Math.min(shown, 1f) * 360f;
        if (main > 0.5f) {
            c.drawArc(box, -90, main, false, glow);
            c.drawArc(box, -90, main, false, arc);
        }
        if (shown > 1f) {
            float extra = Math.min(shown - 1f, 1f) * 360f;
            c.drawArc(box, -90, extra, false, over);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    public static int alpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}

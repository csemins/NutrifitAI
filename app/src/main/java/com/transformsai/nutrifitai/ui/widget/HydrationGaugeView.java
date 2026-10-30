package com.transformsai.nutrifitai.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;

/**
 * Animated water glass: rounded vessel, gradient water with a gentle moving wave, a light surface
 * band and rising bubbles. The wave animator runs only while the view is attached and visible.
 */
public class HydrationGaugeView extends View {
    private final Paint vessel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint water = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path wave = new Path();
    private final RectF box = new RectF();
    private float level;
    private float shownLevel;
    private float phase;
    private ValueAnimator waveAnim;

    public HydrationGaugeView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        vessel.setColor(ContextCompat.getColor(c, R.color.nl_surface_2));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Dp.px(c, 1.5f));
        stroke.setColor(ContextCompat.getColor(c, R.color.nl_stroke_strong));
        surface.setColor(ContextCompat.getColor(c, R.color.nl_water_light));
        bubble.setColor(0xCCFFFFFF);
    }

    public void setLevel(float fraction) {
        level = Math.max(0, Math.min(1, fraction));
        if (!ValueAnimator.areAnimatorsEnabled()) {
            shownLevel = level;
            invalidate();
            return;
        }
        ValueAnimator v = ValueAnimator.ofFloat(shownLevel, level).setDuration(900);
        v.setInterpolator(new DecelerateInterpolator());
        v.addUpdateListener(x -> {
            shownLevel = (float) x.getAnimatedValue();
            invalidate();
        });
        v.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float inset = Dp.px(getContext(), 2);
        box.set(inset, inset, w - inset, h - inset);
        float rTop = Dp.px(getContext(), 22);
        float rBottom = Math.min(w, h) * 0.22f;
        clip.reset();
        clip.addRoundRect(box, new float[]{rTop, rTop, rTop, rTop, rBottom, rBottom, rBottom, rBottom}, Path.Direction.CW);
        water.setShader(new LinearGradient(0, 0, 0, h, ContextCompat.getColor(getContext(), R.color.nl_water_light),
                ContextCompat.getColor(getContext(), R.color.nl_water), Shader.TileMode.CLAMP));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startWave();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopWave();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) startWave();
        else stopWave();
    }

    private void startWave() {
        if (waveAnim != null || !ValueAnimator.areAnimatorsEnabled()) return;
        waveAnim = ValueAnimator.ofFloat(0, (float) (2 * Math.PI)).setDuration(3200);
        waveAnim.setRepeatCount(ValueAnimator.INFINITE);
        waveAnim.setInterpolator(new LinearInterpolator());
        waveAnim.addUpdateListener(x -> {
            phase = (float) x.getAnimatedValue();
            invalidate();
        });
        waveAnim.start();
    }

    private void stopWave() {
        if (waveAnim != null) {
            waveAnim.cancel();
            waveAnim = null;
        }
    }

    private void buildWave(float y0, float amp) {
        wave.reset();
        wave.moveTo(box.left, box.bottom);
        for (float x = box.left; x <= box.right + 4; x += 4) {
            float y = y0 + (float) Math.sin(phase + x / box.width() * 2 * Math.PI) * amp;
            wave.lineTo(x, y);
        }
        wave.lineTo(box.right, box.bottom);
        wave.close();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawPath(clip, vessel);
        c.save();
        c.clipPath(clip);
        float h = box.height();
        float top = box.bottom - h * shownLevel;
        if (shownLevel > 0.001f) {
            float amp = Dp.px(getContext(), shownLevel >= 0.999f ? 0 : 3.5f);
            float band = Dp.px(getContext(), 9);
            buildWave(top, amp);
            c.drawPath(wave, surface);           // light meniscus band following the wave
            buildWave(top + band, amp);
            c.drawPath(wave, water);             // water body below the band
            float baseY = box.bottom - Dp.px(getContext(), 20);
            float[] xs = {0.25f, 0.45f, 0.62f, 0.8f};
            float[] rs = {2.5f, 3.2f, 4.2f, 2.8f};
            for (int i = 0; i < xs.length; i++) {
                float rise = (float) ((Math.sin(phase + i * 1.3) + 1) / 2) * Dp.px(getContext(), 8);
                float y = baseY - rise;
                if (y > top + band * 2) c.drawCircle(box.left + box.width() * xs[i], y, Dp.px(getContext(), rs[i]), bubble);
            }
        }
        c.restore();
        c.drawPath(clip, stroke);
    }
}

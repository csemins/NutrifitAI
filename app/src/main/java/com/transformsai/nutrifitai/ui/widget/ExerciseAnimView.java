package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.pose.Pose;

/**
 * Moving demonstration of an exercise - an animated neon figure, never a photo or video. The figure
 * moves through the exercise's body-pose keyframes (landmarks extracted with MediaPipe from the
 * public-domain free-exercise-db photos; start -> end -> start, or several keyframes such as
 * stand -> squat -> plank for a burpee). Holds get a gentle breathing pulse.
 */
public class ExerciseAnimView extends android.view.View {
    private static final long PERIOD_MS = 2400;
    private static final int[][] LIMBS = {
            {11, 13}, {13, 15}, {12, 14}, {14, 16}, // arms
            {23, 25}, {25, 27}, {24, 26}, {26, 28}, // legs
            {27, 31}, {28, 32}};                    // feet
    private Exercise exercise;
    private boolean animate = true;
    @Nullable private float[] a, b; // x,y,v * 33 - the two keyframes being blended this frame
    @Nullable private float[][] keys; // all keyframes
    private final float[] cur = new float[Pose.COUNT * 3];
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint limb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path torso = new android.graphics.Path();
    private final int lime, limeBright, surface;
    private float minX, minY, maxX, maxY;
    private float bgShaderW = -1, bgShaderH = -1;
    private long lastFrameAt = -1, frameDelay = 40;

    public ExerciseAnimView(Context c, @Nullable AttributeSet attrs) {
        super(c, attrs);
        lime = ContextCompat.getColor(c, R.color.nl_lime);
        limeBright = ContextCompat.getColor(c, R.color.nl_lime_bright);
        surface = ContextCompat.getColor(c, R.color.nl_surface_2);
        for (Paint p : new Paint[]{glow, limb, core}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        fill.setStyle(Paint.Style.FILL);
    }

    public void setExercise(@Nullable Exercise x, boolean animate) {
        this.exercise = x;
        this.animate = animate;
        if (x != null) setContentDescription(x.name);
        float[][] m = x == null ? null : x.motion;
        keys = m != null && m.length > 0 ? m : null;
        a = keys == null ? null : keys[0];
        b = keys == null ? null : keys.length > 1 ? keys[1] : keys[0];
        bounds();
        invalidate();
    }

    private void bounds() {
        minX = minY = Float.MAX_VALUE;
        maxX = maxY = -Float.MAX_VALUE;
        if (keys == null) return;
        for (float[] p : keys) {
            if (p == null) continue;
            for (int i = 0; i < Pose.COUNT; i++) {
                if (i > 0 && i < 11) continue; // face points: the head is drawn from the nose
                if (p[i * 3 + 2] < 0.2f && i != 0) continue;
                minX = Math.min(minX, p[i * 3]);
                maxX = Math.max(maxX, p[i * 3]);
                minY = Math.min(minY, p[i * 3 + 1]);
                maxY = Math.max(maxY, p[i * 3 + 1]);
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (bgShaderW != w || bgShaderH != h) { // gradients are allocated only when the size changes
            bg.setShader(new RadialGradient(w / 2, h * 0.62f, Math.max(1, Math.max(w, h) * 0.75f),
                    CalorieRingView.alpha(lime, 46), surface, Shader.TileMode.CLAMP));
            bgShaderW = w;
            bgShaderH = h;
        }
        c.drawRect(0, 0, w, h, bg);
        if (a == null) return;
        long t = animate ? SystemClock.uptimeMillis() : 0;
        boolean hold = keys.length == 1;
        float phase, k;
        if (keys.length <= 2) {
            phase = (t % PERIOD_MS) / (float) PERIOD_MS;
            k = (float) (0.5 - 0.5 * Math.cos(phase * 2 * Math.PI)); // ease in-out, start -> end -> start
            a = keys[0];
            b = keys[keys.length > 1 ? 1 : 0];
        } else { // keyframe loop: 0 -> 1 -> ... -> n-1 -> 0
            long seg = PERIOD_MS / 2;
            long pos = t % (seg * keys.length);
            int i = (int) (pos / seg);
            phase = (pos % seg) / (float) seg;
            k = (float) (0.5 - 0.5 * Math.cos(phase * Math.PI));
            a = keys[i];
            b = keys[(i + 1) % keys.length];
        }
        for (int i = 0; i < cur.length; i++) cur[i] = a[i] + (b[i] - a[i]) * k;
        // fit the figure (both poses) into the view with padding
        float bw = Math.max(1e-3f, maxX - minX), bh = Math.max(1e-3f, maxY - minY);
        // extra room on top for the head (drawn around the nose) and below for the floor shadow
        float padX = w * 0.12f, padTop = h * 0.17f, padBottom = h * 0.10f;
        float s = Math.min((w - 2 * padX) / bw, (h - padTop - padBottom) / bh);
        if (hold) s *= 1f + 0.015f * (float) Math.sin(phase * 2 * Math.PI);
        float ox = (w - bw * s) / 2 - minX * s;
        float oy = padTop + ((h - padTop - padBottom) - bh * s) / 2 - minY * s;
        float unit = Math.max(2f, s * 0.035f);
        // floor shadow
        fill.setShader(null);
        fill.setColor(CalorieRingView.alpha(lime, 30));
        c.drawOval(w * 0.22f, oy + maxY * s - unit, w * 0.78f, oy + maxY * s + unit * 2, fill);
        // torso
        torso.reset();
        torso.moveTo(X(11, s, ox), Y(11, s, oy));
        torso.lineTo(X(12, s, ox), Y(12, s, oy));
        torso.lineTo(X(24, s, ox), Y(24, s, oy));
        torso.lineTo(X(23, s, ox), Y(23, s, oy));
        torso.close();
        fill.setShader(null);
        fill.setColor(CalorieRingView.alpha(lime, 80));
        c.drawPath(torso, fill);
        float pulse = hold ? 0.75f + 0.25f * (float) Math.sin(phase * 2 * Math.PI) : 1f;
        glow.setStrokeWidth(unit * 3.4f);
        glow.setColor(CalorieRingView.alpha(lime, (int) (60 * pulse)));
        limb.setStrokeWidth(unit * 1.7f);
        limb.setColor(lime);
        core.setStrokeWidth(unit * 0.55f);
        core.setColor(CalorieRingView.alpha(0xFFFFFFFF, 200));
        c.drawPath(torso, glow);
        for (int[] l : LIMBS) line(c, l[0], l[1], s, ox, oy, glow);
        for (int[] l : LIMBS) line(c, l[0], l[1], s, ox, oy, limb);
        line(c, 11, 12, s, ox, oy, limb);
        line(c, 23, 24, s, ox, oy, limb);
        for (int[] l : LIMBS) line(c, l[0], l[1], s, ox, oy, core);
        // head
        float hx = X(0, s, ox), hy = Y(0, s, oy);
        float shoulder = (float) Math.hypot(X(11, s, ox) - X(12, s, ox), Y(11, s, oy) - Y(12, s, oy));
        float r = Math.max(unit * 2.6f, Math.min(shoulder * 0.42f, unit * 4.5f));
        fill.setShader(null);
        fill.setColor(CalorieRingView.alpha(lime, 70));
        c.drawCircle(hx, hy, r * 1.5f, fill);
        fill.setColor(limeBright);
        c.drawCircle(hx, hy, r, fill);
        // joints
        for (int j : new int[]{11, 12, 13, 14, 23, 24, 25, 26}) {
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(X(j, s, ox), Y(j, s, oy), unit * 0.9f, fill);
        }
        // ~25 fps normally. If frames arrive late (slow GPU, software rendering) the interval grows so the
        // demo never starves the main thread; it recovers when the device keeps up again.
        long nowMs = SystemClock.uptimeMillis();
        if (lastFrameAt > 0) {
            long late = nowMs - lastFrameAt - frameDelay;
            frameDelay = late > frameDelay ? Math.min(1000, frameDelay * 2) : Math.max(40, frameDelay - 10);
        }
        lastFrameAt = nowMs;
        if (animate && isAttachedToWindow() && isShown() && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            postInvalidateDelayed(frameDelay);
        }
    }

    private void line(Canvas c, int i, int j, float s, float ox, float oy, Paint p) {
        c.drawLine(X(i, s, ox), Y(i, s, oy), X(j, s, ox), Y(j, s, oy), p);
    }

    private float X(int i, float s, float ox) {
        return ox + cur[i * 3] * s;
    }

    private float Y(int i, float s, float oy) {
        return oy + cur[i * 3 + 1] * s;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        invalidate();
    }

    @Override
    protected void onVisibilityChanged(@androidx.annotation.NonNull android.view.View v, int visibility) {
        super.onVisibilityChanged(v, visibility);
        if (visibility == VISIBLE) invalidate();
    }
}

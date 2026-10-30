package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.fitness.pose.Pose;
import com.transformsai.nutrifitai.fitness.pose.PoseFeatures;

/**
 * Neon skeleton drawn over the camera preview. The preview uses FILL_CENTER, so the analysed image is
 * scaled to cover the view; the same transform is applied here. Mirrors x for the front camera.
 */
public class PoseOverlayView extends View {
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint joint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile Pose pose;
    private volatile boolean mirror = true;
    private volatile boolean good = true;
    private volatile long faultUntil;
    private int coral;
    private final int lime, warn;

    public PoseOverlayView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        lime = ContextCompat.getColor(c, R.color.nl_lime);
        warn = ContextCompat.getColor(c, R.color.nl_carbs);
        coral = ContextCompat.getColor(c, R.color.nl_protein);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeWidth(Dp.px(c, 10));
        glow.setMaskFilter(new BlurMaskFilter(Dp.px(c, 8), BlurMaskFilter.Blur.NORMAL));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(Dp.px(c, 3.5f));
        joint.setStyle(Paint.Style.FILL);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // BlurMaskFilter needs software rendering
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Any thread. */
    /** Flashes the skeleton coral for a moment (a rep that did not count / a posture fault). */
    public void flashFault() {
        faultUntil = android.os.SystemClock.uptimeMillis() + 1200;
        postInvalidate();
    }

    public void setPose(@Nullable Pose p, boolean mirrorX, boolean inRange) {
        pose = p;
        mirror = mirrorX;
        good = inRange;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        Pose p = pose;
        if (p == null) return;
        float scale = Math.max(getWidth() / p.width, getHeight() / p.height);
        float ox = (getWidth() - p.width * scale) / 2f, oy = (getHeight() - p.height * scale) / 2f;
        int col = android.os.SystemClock.uptimeMillis() < faultUntil ? coral : good ? lime : warn;
        glow.setColor(CalorieRingView.alpha(col, 110));
        line.setColor(col);
        for (int[] b : Pose.BONES) {
            if (!p.visible(b[0], PoseFeatures.MIN_VIS) || !p.visible(b[1], PoseFeatures.MIN_VIS)) continue;
            float x1 = X(p, b[0], scale, ox), y1 = oy + p.y[b[0]] * scale;
            float x2 = X(p, b[1], scale, ox), y2 = oy + p.y[b[1]] * scale;
            c.drawLine(x1, y1, x2, y2, glow);
            c.drawLine(x1, y1, x2, y2, line);
        }
        float rOuter = Dp.px(getContext(), 6), rInner = Dp.px(getContext(), 3);
        for (int i = 11; i < Pose.COUNT; i++) {
            if (!p.visible(i, PoseFeatures.MIN_VIS) || (i >= 17 && i <= 22)) continue; // skip finger points
            float x = X(p, i, scale, ox), y = oy + p.y[i] * scale;
            joint.setColor(CalorieRingView.alpha(col, 90));
            c.drawCircle(x, y, rOuter, joint);
            joint.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, rInner, joint);
        }
    }

    private float X(Pose p, int i, float scale, float ox) {
        float x = mirror ? p.width - p.x[i] : p.x[i];
        return ox + x * scale;
    }
}

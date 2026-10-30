package com.transformsai.nutrifitai.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;

/** Energy split donut: protein / carbs / fat arcs with small gaps. Gray ring when empty. */
public class MacroDonutView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int[] colors;
    private float[] parts = {0, 0, 0};
    private float reveal = 1f;
    private final float stroke;

    public MacroDonutView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        stroke = Dp.px(c, 12);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.BUTT);
        colors = new int[]{ContextCompat.getColor(c, R.color.nl_protein), ContextCompat.getColor(c, R.color.nl_carbs),
                ContextCompat.getColor(c, R.color.nl_fat)};
    }

    /** Percentages (sum ~100) for protein, carbs, fat. */
    public void setSplit(int protein, int carbs, int fat) {
        parts = new float[]{protein, carbs, fat};
        if (ValueAnimator.areAnimatorsEnabled()) {
            ValueAnimator v = ValueAnimator.ofFloat(0f, 1f).setDuration(700);
            v.addUpdateListener(x -> {
                reveal = (float) x.getAnimatedValue();
                invalidate();
            });
            v.start();
        } else invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float s = Math.min(w, h);
        float half = stroke / 2f + 2;
        box.set((w - s) / 2f + half, (h - s) / 2f + half, (w + s) / 2f - half, (h + s) / 2f - half);
    }

    @Override
    protected void onDraw(Canvas c) {
        float total = parts[0] + parts[1] + parts[2];
        if (total <= 0) {
            p.setColor(ContextCompat.getColor(getContext(), R.color.nl_surface_3));
            c.drawArc(box, 0, 360, false, p);
            return;
        }
        float gap = 4f;
        float start = -90;
        for (int i = 0; i < 3; i++) {
            float sweep = parts[i] / total * 360f * reveal;
            if (sweep <= 0.1f) continue;
            p.setColor(colors[i]);
            c.drawArc(box, start + gap / 2, Math.max(sweep - gap, 0.5f), false, p);
            start += sweep;
        }
    }
}

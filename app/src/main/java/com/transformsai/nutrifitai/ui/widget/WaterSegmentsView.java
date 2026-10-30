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

/** Ten pill segments showing hydration progress (home "Water" card). */
public class WaterSegmentsView extends View {
    private static final int COUNT = 10;
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float fraction;

    public WaterSegmentsView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        on.setColor(ContextCompat.getColor(c, R.color.nl_water));
        off.setColor(ContextCompat.getColor(c, R.color.nl_surface_3));
    }

    public void setFraction(float f) {
        fraction = Math.max(0, Math.min(1, f));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float gap = Dp.px(getContext(), 5);
        float w = (getWidth() - gap * (COUNT - 1)) / COUNT;
        int filled = Math.round(fraction * COUNT);
        for (int i = 0; i < COUNT; i++) {
            float x = i * (w + gap);
            r.set(x, 0, x + w, getHeight());
            c.drawRoundRect(r, w / 2, w / 2, i < filled ? on : off);
        }
    }
}

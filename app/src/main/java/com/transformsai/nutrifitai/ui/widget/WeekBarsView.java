package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.transformsai.nutrifitai.R;

import java.util.ArrayList;
import java.util.List;

/**
 * "Weekly consistency" bars: each day is a set of three thin pill bars filled to the share of the daily
 * training goal reached (lime when met, amber when partial, empty when no workout). The percentage is
 * written above every bar so the chart never relies on colour alone.
 */
public class WeekBarsView extends View {
    public static final class Day {
        public String label;
        /** 0..1+ share of goal; null = no workout that day. */
        @Nullable public Float value;
        public boolean today;
    }

    private final List<Day> days = new ArrayList<>();
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int lime, amber, dim, textColor, textDim;

    public WeekBarsView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        lime = ContextCompat.getColor(c, R.color.nl_lime);
        amber = ContextCompat.getColor(c, R.color.nl_carbs);
        dim = ContextCompat.getColor(c, R.color.nl_surface_3);
        textColor = ContextCompat.getColor(c, R.color.nl_text);
        textDim = ContextCompat.getColor(c, R.color.nl_text_3);
        Typeface tf = ResourcesCompat.getFont(c, R.font.jakarta_semibold);
        text.setTypeface(tf);
        text.setTextSize(Dp.px(c, 10.5f));
        text.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(tf);
        label.setTextSize(Dp.px(c, 10.5f));
        label.setTextAlign(Paint.Align.CENTER);
        track.setColor(dim);
    }

    public void setDays(List<Day> d) {
        days.clear();
        days.addAll(d);
        StringBuilder cd = new StringBuilder();
        for (Day x : d) {
            cd.append(x.label).append(' ').append(x.value == null ? "no workout" : Math.round(x.value * 100) + " percent").append(", ");
        }
        setContentDescription(cd.toString());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (days.isEmpty()) return;
        float w = getWidth(), h = getHeight();
        float top = Dp.px(getContext(), 18), bottom = h - Dp.px(getContext(), 20);
        float slot = w / days.size();
        float bw = Dp.px(getContext(), 4), gap = Dp.px(getContext(), 3.5f);
        for (int i = 0; i < days.size(); i++) {
            Day d = days.get(i);
            float cx = slot * i + slot / 2;
            float v = d.value == null ? 0 : Math.min(1f, d.value);
            int col = d.value == null ? dim : v >= 0.999f ? lime : amber;
            float fillTop = bottom - (bottom - top) * Math.max(0.04f, v);
            for (int k = -1; k <= 1; k++) {
                float x = cx + k * (bw + gap);
                r.set(x - bw / 2, top, x + bw / 2, bottom);
                track.setShader(new LinearGradient(0, top, 0, bottom, 0x00000000, dim, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, bw, bw, track);
                if (d.value != null) {
                    r.set(x - bw / 2, fillTop, x + bw / 2, bottom);
                    bar.setShader(new LinearGradient(0, fillTop, 0, bottom, col, CalorieRingView.alpha(col, 40), Shader.TileMode.CLAMP));
                    c.drawRoundRect(r, bw, bw, bar);
                }
            }
            text.setColor(d.value == null ? textDim : col);
            String pct = d.value == null ? "–" : Math.round(Math.min(9.99f, d.value) * 100) + "%";
            c.drawText(pct, cx, fillTop - Dp.px(getContext(), 6), text);
            label.setColor(d.today ? textColor : textDim);
            c.drawText(d.label, cx, h - Dp.px(getContext(), 4), label);
        }
    }
}

package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;

import java.util.ArrayList;
import java.util.List;

/** Weight trend: line with gradient fill, end dot and up to four x-axis labels. */
public class LineChartView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();
    /** x in [0,1] (position in period), y = value. */
    private List<float[]> points = new ArrayList<>();
    private List<String> xLabels = new ArrayList<>();

    public LineChartView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        int lime = ContextCompat.getColor(c, R.color.nl_lime);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Dp.px(c, 2.2f));
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(lime);
        dot.setColor(lime);
        label.setColor(ContextCompat.getColor(c, R.color.nl_text_2));
        label.setTextSize(Dp.sp(c, 12));
        label.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(List<float[]> pts, List<String> labels) {
        points = pts;
        xLabels = labels;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float labelH = Dp.px(getContext(), 24);
        float top = Dp.px(getContext(), 10);
        float bottom = getHeight() - labelH;
        float w = getWidth();
        for (int i = 0; i < xLabels.size(); i++) {
            float x = xLabels.size() == 1 ? w / 2 : (w * 0.1f) + (w * 0.8f) * i / (xLabels.size() - 1);
            c.drawText(xLabels.get(i), x, getHeight() - Dp.px(getContext(), 4), label);
        }
        if (points.isEmpty()) return;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float[] p : points) {
            min = Math.min(min, p[1]);
            max = Math.max(max, p[1]);
        }
        float pad = Math.max((max - min) * 0.15f, 0.5f);
        min -= pad;
        max += pad;
        path.reset();
        area.reset();
        float lastX = 0, lastY = 0;
        for (int i = 0; i < points.size(); i++) {
            float x = points.get(i)[0] * (w - Dp.px(getContext(), 8)) + Dp.px(getContext(), 4);
            float y = bottom - (points.get(i)[1] - min) / (max - min) * (bottom - top);
            if (i == 0) {
                path.moveTo(x, y);
                area.moveTo(x, bottom);
                area.lineTo(x, y);
            } else {
                path.lineTo(x, y);
                area.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        area.lineTo(lastX, bottom);
        area.close();
        fill.setShader(new LinearGradient(0, top, 0, bottom, 0x553E8A22, 0x00000000, Shader.TileMode.CLAMP));
        c.drawPath(area, fill);
        c.drawPath(path, line);
        c.drawCircle(lastX, lastY, Dp.px(getContext(), 4.5f), dot);
    }
}

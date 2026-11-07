package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A walk's route drawn as art, without map tiles: a glowing gradient line (cyan to lime) on a faint grid, a white
 * start dot and a lime finish dot. Used for history thumbnails and share cards; it works offline and in any theme.
 */
public class RouteArtView extends View {
    private final List<double[]> route = new ArrayList<>();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean showGrid = true;

    public RouteArtView(Context c) {
        this(c, null);
    }

    public RouteArtView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // blur for the glow
        for (Paint p : new Paint[]{line, glow}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        grid.setColor(0x14FFFFFF);
        grid.setStrokeWidth(1);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setRoute(List<double[]> pts) {
        route.clear();
        route.addAll(pts);
        invalidate();
    }

    public void setShowGrid(boolean show) {
        showGrid = show;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        if (showGrid) {
            float step = 24 * d;
            for (float x = step; x < w; x += step) c.drawLine(x, 0, x, h, grid);
            for (float y = step; y < h; y += step) c.drawLine(0, y, w, y, grid);
        }
        if (route.size() < 2) return;
        // equirectangular projection, longitudes shrunk by cos(latitude), fitted with padding and kept in proportion
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (double[] p : route) {
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]);
            maxLon = Math.max(maxLon, p[1]);
        }
        double k = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        double spanX = Math.max(1e-6, (maxLon - minLon) * k), spanY = Math.max(1e-6, maxLat - minLat);
        float pad = Math.min(w, h) * 0.14f;
        double scale = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
        float offX = (float) ((w - spanX * scale) / 2), offY = (float) ((h - spanY * scale) / 2);
        path.reset();
        float sx = 0, sy = 0, ex = 0, ey = 0;
        for (int i = 0; i < route.size(); i++) {
            double[] p = route.get(i);
            float x = offX + (float) ((p[1] - minLon) * k * scale);
            float y = offY + (float) ((maxLat - p[0]) * scale);
            if (i == 0) {
                path.moveTo(x, y);
                sx = x;
                sy = y;
            } else path.lineTo(x, y);
            ex = x;
            ey = y;
        }
        float stroke = Math.max(2.5f * d, Math.min(w, h) * 0.022f);
        Shader shader = new LinearGradient(sx, sy, ex, ey, 0xFF5AD8FF, 0xFFB8F15A, Shader.TileMode.CLAMP);
        glow.setShader(shader);
        glow.setStrokeWidth(stroke * 3.2f);
        glow.setAlpha(110);
        glow.setMaskFilter(new BlurMaskFilter(stroke * 2.2f, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(path, glow);
        line.setShader(shader);
        line.setStrokeWidth(stroke);
        c.drawPath(path, line);
        dot.setColor(0xFF0B0D0B);
        c.drawCircle(sx, sy, stroke * 1.9f, dot);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(sx, sy, stroke * 1.2f, dot);
        dot.setColor(0xFF0B0D0B);
        c.drawCircle(ex, ey, stroke * 2.2f, dot);
        dot.setColor(0xFFB8F15A);
        c.drawCircle(ex, ey, stroke * 1.5f, dot);
    }
}

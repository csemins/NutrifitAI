package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.social.PoseCodec;

/**
 * A friend's live pose in a challenge tile: the skeleton streamed from their camera tracker (not video).
 * Joints are drawn in lime; the figure is fitted into the tile keeping its proportions.
 */
public class LivePoseView extends View {
    private final Paint bone = new Paint(Paint.ANTI_ALIAS_FLAG), joint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable private float[] pts;

    public LivePoseView(Context c) {
        this(c, null);
    }

    public LivePoseView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        int lime = ContextCompat.getColor(c, R.color.nl_lime);
        bone.setColor(lime);
        bone.setStrokeWidth(Dp.px(c, 3));
        bone.setStrokeCap(Paint.Cap.ROUND);
        joint.setColor(ContextCompat.getColor(c, R.color.nl_text));
    }

    public void setPose(@Nullable String encoded) {
        pts = PoseCodec.decode(encoded);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (pts == null) return;
        float minX = 1, minY = 1, maxX = 0, maxY = 0;
        for (int i = 0; i < pts.length; i += 2) {
            if (Float.isNaN(pts[i])) continue;
            minX = Math.min(minX, pts[i]);
            maxX = Math.max(maxX, pts[i]);
            minY = Math.min(minY, pts[i + 1]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        if (maxX <= minX || maxY <= minY) return;
        float pad = getWidth() * 0.12f;
        float scale = Math.min((getWidth() - 2 * pad) / (maxX - minX), (getHeight() - 2 * pad) / (maxY - minY));
        float ox = (getWidth() - (maxX - minX) * scale) / 2f - minX * scale;
        float oy = (getHeight() - (maxY - minY) * scale) / 2f - minY * scale;
        for (int[] b : PoseCodec.BONES) {
            float x1 = pts[b[0] * 2], y1 = pts[b[0] * 2 + 1], x2 = pts[b[1] * 2], y2 = pts[b[1] * 2 + 1];
            if (Float.isNaN(x1) || Float.isNaN(x2)) continue;
            c.drawLine(ox + x1 * scale, oy + y1 * scale, ox + x2 * scale, oy + y2 * scale, bone);
        }
        float r = Dp.px(getContext(), 2.5f);
        for (int i = 0; i < pts.length; i += 2) {
            if (!Float.isNaN(pts[i])) c.drawCircle(ox + pts[i] * scale, oy + pts[i + 1] * scale, i == 0 ? r * 2.2f : r, joint);
        }
    }
}

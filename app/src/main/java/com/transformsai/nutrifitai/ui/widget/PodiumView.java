package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.transformsai.nutrifitai.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Challenge podium: first place in the middle on the tallest block with a crown, second on the left, third on
 * the right, and everyone else listed underneath. Always drawn in the dark brand look (it is used on share cards
 * and on a dark panel), and the signed-in player is outlined in lime.
 */
public class PodiumView extends View {
    /** One finisher, in ranking order. */
    public static final class Entry {
        public final String name;
        public final int points;
        public final boolean me;
        /** Profile photo, drawn in the circle instead of the initial when present. */
        @Nullable public final android.graphics.Bitmap photo;

        public Entry(String name, int points, boolean me) {
            this(name, points, me, null);
        }

        public Entry(String name, int points, boolean me, @Nullable android.graphics.Bitmap photo) {
            this.name = name;
            this.points = points;
            this.me = me;
            this.photo = photo;
        }
    }

    private static final int GOLD_A = 0xFFFFE07A, GOLD_B = 0xFFE0A526, SILVER_A = 0xFFE9EEF2, SILVER_B = 0xFFA9B4BE,
            BRONZE_A = 0xFFF2B98A, BRONZE_B = 0xFFB86E3A, LIME = 0xFFB8F15A, TEXT = 0xFFFFFFFF, TEXT_2 = 0xFF9AA39B,
            TILE = 0xFF161A17, STROKE = 0xFF262C27;
    private static final float ROW_DP = 40f;

    private final List<Entry> entries = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Typeface bold, semi;

    public PodiumView(Context c) {
        this(c, null);
    }

    public PodiumView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        bold = ResourcesCompat.getFont(c, R.font.jakarta_extrabold);
        semi = ResourcesCompat.getFont(c, R.font.jakarta_semibold);
        ring.setStyle(Paint.Style.STROKE);
    }

    public void setEntries(List<Entry> list) {
        entries.clear();
        entries.addAll(list);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(i + 1).append(". ").append(list.get(i).name).append(", ").append(list.get(i).points).append(". ");
        }
        setContentDescription(sb.toString());
        requestLayout();
        invalidate();
    }

    private float dp(float v) {
        return Dp.px(getContext(), v);
    }

    /** Height for the podium itself plus one row per player beyond the top three. */
    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int rows = Math.max(0, entries.size() - 3);
        int want = (int) (dp(300) + rows * dp(ROW_DP) + (rows > 0 ? dp(12) : 0)) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, resolveSize(want, h));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (entries.isEmpty()) return;
        int rows = Math.max(0, entries.size() - 3);
        float listH = rows * dp(ROW_DP) + (rows > 0 ? dp(12) : 0);
        float need = dp(300) + listH;
        float pad = getPaddingLeft() + getPaddingRight();
        float availW = getWidth() - pad, availH = getHeight() - getPaddingTop() - getPaddingBottom();
        // shrink everything evenly when the space is shorter than the podium needs (e.g. on a share card)
        float scale = Math.min(1f, availH / need);
        c.save();
        c.translate(getPaddingLeft() + (availW - availW * scale) / 2f, getPaddingTop());
        c.scale(scale, scale);
        drawAll(c, availW, Math.max(availH / scale, need), listH);
        c.restore();
    }

    private void drawAll(Canvas c, float w, float h, float listH) {
        float podiumH = Math.max(dp(150), h - listH);
        float colW = w / 3f;
        float base = podiumH;
        // leave room above the tallest block for the avatar, name, points and crown
        float maxBlock = Math.max(dp(50), Math.min(podiumH * 0.45f, podiumH - dp(165)));
        // positions: 2nd left, 1st middle, 3rd right
        int[] order = {1, 0, 2};
        float[] heights = {0.72f, 1f, 0.55f};
        for (int slot = 0; slot < 3; slot++) {
            int rank = order[slot];
            if (rank >= entries.size()) continue;
            Entry e = entries.get(rank);
            float left = slot * colW + dp(6), right = (slot + 1) * colW - dp(6);
            float top = base - maxBlock * heights[slot];
            int a = rank == 0 ? GOLD_A : rank == 1 ? SILVER_A : BRONZE_A, b = rank == 0 ? GOLD_B : rank == 1 ? SILVER_B : BRONZE_B;
            fill.setShader(new LinearGradient(0, top, 0, base, a, b, Shader.TileMode.CLAMP));
            r.set(left, top, right, base + dp(14));
            c.drawRoundRect(r, dp(14), dp(14), fill);
            fill.setShader(null);
            // place number on the block
            text.setTypeface(bold);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(0xCC1A1A1A);
            text.setTextSize(Math.min(dp(40), (base - top) * 0.55f));
            c.drawText(String.valueOf(rank + 1), (left + right) / 2f, top + (base - top) / 2f + text.getTextSize() / 3f, text);

            // avatar circle with the initial
            float cx = (left + right) / 2f, rad = rank == 0 ? dp(30) : dp(24);
            float cy = top - dp(58) - rad;
            fill.setColor(TILE);
            c.drawCircle(cx, cy, rad, fill);
            if (e.photo != null) drawPhoto(c, e.photo, cx, cy, rad);
            ring.setStrokeWidth(dp(e.me ? 3 : 2));
            ring.setColor(e.me ? LIME : b);
            c.drawCircle(cx, cy, rad, ring);
            text.setColor(TEXT);
            text.setTextSize(rad * 0.95f);
            if (e.photo == null) c.drawText(initial(e.name), cx, cy + rad * 0.34f, text);
            if (rank == 0) { // crown
                text.setTypeface(Typeface.DEFAULT);
                text.setTextSize(dp(30));
                c.drawText("👑", cx, cy - rad - dp(4), text);
            }
            // name and points under the avatar
            text.setTypeface(semi);
            text.setTextSize(dp(rank == 0 ? 15 : 13));
            text.setColor(e.me ? LIME : TEXT);
            c.drawText(ellipsize(e.name, colW - dp(10)), cx, top - dp(30), text);
            text.setTypeface(bold);
            text.setTextSize(dp(rank == 0 ? 17 : 14));
            text.setColor(rank == 0 ? GOLD_A : TEXT_2);
            c.drawText(String.format(Locale.getDefault(), "%,d pts", Math.max(0, e.points)), cx, top - dp(10), text);
        }

        // everyone after third place
        float y = podiumH + dp(12);
        for (int i = 3; i < entries.size(); i++) {
            Entry e = entries.get(i);
            r.set(dp(4), y, w - dp(4), y + dp(ROW_DP) - dp(6));
            fill.setColor(TILE);
            c.drawRoundRect(r, dp(12), dp(12), fill);
            ring.setStrokeWidth(dp(1));
            ring.setColor(e.me ? LIME : STROKE);
            c.drawRoundRect(r, dp(12), dp(12), ring);
            float mid = r.centerY() + dp(5);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(bold);
            text.setTextSize(dp(14));
            text.setColor(TEXT_2);
            c.drawText(String.valueOf(i + 1), r.left + dp(14), mid, text);
            text.setTypeface(semi);
            text.setColor(e.me ? LIME : TEXT);
            c.drawText(ellipsize(e.name, w * 0.55f), r.left + dp(44), mid, text);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTypeface(bold);
            text.setColor(TEXT);
            c.drawText(String.format(Locale.getDefault(), "%,d", Math.max(0, e.points)), r.right - dp(14), mid, text);
            y += dp(ROW_DP);
        }
        text.setTextAlign(Paint.Align.CENTER);
    }


    /** The photo cropped to a circle (centre-crop). */
    private void drawPhoto(Canvas c, android.graphics.Bitmap bmp, float cx, float cy, float rad) {
        android.graphics.BitmapShader sh = new android.graphics.BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float s = 2 * rad / Math.min(bmp.getWidth(), bmp.getHeight());
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s);
        m.postTranslate(cx - bmp.getWidth() * s / 2f, cy - bmp.getHeight() * s / 2f);
        sh.setLocalMatrix(m);
        photoPaint.setShader(sh);
        c.drawCircle(cx, cy, rad, photoPaint);
        photoPaint.setShader(null);
    }

    private String ellipsize(String s, float max) {
        return TextUtils.ellipsize(s == null ? "" : s, text, max, TextUtils.TruncateAt.END).toString();
    }

    private static String initial(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        String t = name.trim();
        return new String(Character.toChars(t.codePointAt(0))).toUpperCase(Locale.getDefault());
    }
}

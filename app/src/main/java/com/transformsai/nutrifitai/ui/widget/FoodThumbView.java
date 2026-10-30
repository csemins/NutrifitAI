package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import android.net.Uri;

import com.transformsai.nutrifitai.util.FoodImages;
import com.bumptech.glide.Glide;

/**
 * Rounded food thumbnail. Priority: the user's own meal photo (set by the caller), then the food's
 * bundled photo or its food-group photo ({@link FoodImages}), then a tinted gradient tile with an emoji.
 */
public class FoodThumbView extends AppCompatImageView {
    private final Path clip = new Path();
    private final RectF r = new RectF();
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String emoji = "🍽";
    private int tint = 0xFF2B4A1E;
    private static final float CIRCLE = -1f;
    private float radius;

    public FoodThumbView(Context c) {
        this(c, null);
    }

    public FoodThumbView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        setScaleType(ScaleType.CENTER_CROP);
        emojiPaint.setTextAlign(Paint.Align.CENTER);
        radius = Dp.px(c, 18);
    }

    public void setRadius(float px) {
        radius = px;
        rebuildClip();
        invalidate();
    }

    /** Round avatar (people) instead of a rounded square (food). */
    public void setCircle() {
        if (radius != CIRCLE) setRadius(CIRCLE);
    }

    private void rebuildClip() {
        float rad = radius == CIRCLE ? Math.min(r.width(), r.height()) / 2f : radius;
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
    }

    /** Shows the bundled photo for this food when there is one, else the emoji tile. */
    public void setFood(@Nullable String foodId, @Nullable String name, @Nullable String emoji, @Nullable String category) {
        String uri = FoodImages.uri(getContext(), foodId, category, name);
        if (uri == null) {
            Glide.with(this).clear(this);
            setEmoji(emoji, category != null ? category : FoodImages.categoryOfId(foodId));
            return;
        }
        tint = tintFor(category);
        Glide.with(this).load(Uri.parse(uri)).centerCrop().into(this);
    }

    public void setEmoji(@Nullable String e, @Nullable String category) {
        emoji = e == null || e.isEmpty() ? "🍽" : e;
        tint = tintFor(category);
        setImageDrawable(null);
        invalidate();
    }

    public static int tintFor(@Nullable String category) {
        if (category == null) return 0xFF243B1C;
        switch (category) {
            case "grains": return 0xFF3D3420;
            case "curries": return 0xFF45261E;
            case "lentils": return 0xFF433A16;
            case "protein": return 0xFF3E2A22;
            case "dairy": return 0xFF26323E;
            case "vegetables": return 0xFF1F3A1D;
            case "fruits": return 0xFF41291F;
            case "snacks": return 0xFF3A2C1A;
            case "drinks": return 0xFF1B3140;
            case "sweets": return 0xFF3E2232;
            case "fats": return 0xFF3B3A18;
            default: return 0xFF243B1C;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        r.set(0, 0, w, h);
        rebuildClip();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.clipPath(clip);
        if (getDrawable() == null) {
            boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            // dark tiles on the dark theme, pastel tiles of the same hue on the light theme
            int from = night ? lighten(tint) : mixWhite(tint, 0.72f);
            int to = night ? 0xFF121314 : mixWhite(tint, 0.86f);
            bg.setShader(new RadialGradient(getWidth() * 0.3f, getHeight() * 0.25f, Math.max(getWidth(), getHeight()),
                    from, to, Shader.TileMode.CLAMP));
            c.drawRect(r, bg);
            emojiPaint.setTextSize(Math.min(getWidth(), getHeight()) * 0.46f);
            Paint.FontMetrics fm = emojiPaint.getFontMetrics();
            c.drawText(emoji, getWidth() / 2f, getHeight() / 2f - (fm.ascent + fm.descent) / 2f, emojiPaint);
        } else {
            super.onDraw(c);
        }
        c.restore();
    }

    private static int mixWhite(int c, float t) {
        int r = (int) (((c >> 16) & 0xFF) + (255 - ((c >> 16) & 0xFF)) * t);
        int g = (int) (((c >> 8) & 0xFF) + (255 - ((c >> 8) & 0xFF)) * t);
        int b = (int) ((c & 0xFF) + (255 - (c & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((c >> 8) & 0xFF) + 40);
        int b = Math.min(255, (c & 0xFF) + 30);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}

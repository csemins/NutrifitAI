package com.transformsai.nutrifitai.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.fitness.Muscles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Front and back anatomy figure whose muscle groups glow by intensity (0..1): dim lime for light work,
 * bright lime with a glow for the main targets. Used for an exercise's targets, a plan preview and the
 * post-workout "muscles trained" heat map. Optionally selectable: tap muscles to choose a focus.
 *
 * <p>Shapes are original vector paths in a 100 x 200 box per figure (left half drawn, right half mirrored).
 */
public class MuscleMapView extends View {
    public interface OnSelectionChanged {
        void onChanged(Set<String> selected);
    }

    public static final int SHOW_BOTH = 0, SHOW_FRONT = 1, SHOW_BACK = 2;
    private static final float FIG_W = 100, FIG_H = 200, GAP = 18, LABEL_H = 14;

    private static final class Part {
        final String muscle;
        final boolean front;
        final Path path = new Path();
        final Path screen = new Path();
        final Region region = new Region();

        Part(String muscle, boolean front) {
            this.muscle = muscle;
            this.front = front;
        }
    }

    private final List<Part> parts = new ArrayList<>();
    private final List<Path> silhouetteFront = new ArrayList<>(), silhouetteBack = new ArrayList<>();
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), bodyStroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            muscle = new Paint(Paint.ANTI_ALIAS_FLAG), seam = new Paint(Paint.ANTI_ALIAS_FLAG),
            glow = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG),
            select = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final Path tmp = new Path();
    private Map<String, Float> values = new HashMap<>();
    private Map<String, Float> shown = new HashMap<>();
    private final Set<String> selected = new HashSet<>();
    private boolean selectable;
    private int mode = SHOW_BOTH;
    private float anim = 1f;
    @Nullable private OnSelectionChanged listener;
    private final int cBody, cStroke, cIdle, cLow, cHigh, cText;
    private float lastScale = -1, lastOx, lastOy;
    @Nullable private Region clipRegion;

    public MuscleMapView(Context c, @Nullable AttributeSet attrs) {
        super(c, attrs);
        cBody = ContextCompat.getColor(c, R.color.nl_surface_2);
        cStroke = ContextCompat.getColor(c, R.color.nl_stroke);
        cIdle = ContextCompat.getColor(c, R.color.nl_surface_3);
        cLow = 0xFF3F5A17;
        cHigh = ContextCompat.getColor(c, R.color.nl_lime);
        cText = ContextCompat.getColor(c, R.color.nl_text_3);
                if (attrs != null) {
            TypedArray a = c.obtainStyledAttributes(attrs, R.styleable.MuscleMapView);
            mode = a.getInt(R.styleable.MuscleMapView_muscleMode, SHOW_BOTH);
            selectable = a.getBoolean(R.styleable.MuscleMapView_selectable, false);
            a.recycle();
        }
        body.setStyle(Paint.Style.FILL);
        body.setColor(cBody);
        bodyStroke.setStyle(Paint.Style.STROKE);
        bodyStroke.setColor(cStroke);
        bodyStroke.setStrokeJoin(Paint.Join.ROUND);
        muscle.setStyle(Paint.Style.FILL);
        seam.setStyle(Paint.Style.STROKE);
        seam.setColor(cBody);
        seam.setStrokeJoin(Paint.Join.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeJoin(Paint.Join.ROUND);
        select.setStyle(Paint.Style.STROKE);
        select.setColor(Color.WHITE);
        select.setStrokeJoin(Paint.Join.ROUND);
        label.setColor(cText);
        label.setTextAlign(Paint.Align.CENTER);
        label.setLetterSpacing(0.12f);
        label.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        build();
        setFocusable(selectable);
        setClickable(selectable);
    }

    // ---------------------------------------------------------------- public API
    /** Muscle id -> intensity 0..1. Animates in. */
    public void setIntensities(@Nullable Map<String, Float> v) {
        values = v == null ? new HashMap<>() : new HashMap<>(v);
        updateDescription();
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
            anim = 1f;
            shown = values;
            invalidate();
            return;
        }
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f).setDuration(650);
        va.setInterpolator(new DecelerateInterpolator());
        shown = values;
        va.addUpdateListener(x -> {
            anim = (float) x.getAnimatedValue();
            invalidate();
        });
        va.start();
    }

    public void setMode(int mode) {
        this.mode = mode;
        lastScale = -1;
        requestLayout();
        invalidate();
    }

    public void setSelectable(boolean s, @Nullable OnSelectionChanged l) {
        selectable = s;
        listener = l;
        setClickable(s);
        setFocusable(s);
        updateDescription();
    }

    public Set<String> selected() {
        return new HashSet<>(selected);
    }

    public void setSelected(Set<String> s) {
        selected.clear();
        if (s != null) selected.addAll(s);
        invalidate();
    }

    // ---------------------------------------------------------------- geometry
    private void build() {
        // ---- silhouettes (left halves, mirrored)
        for (boolean front : new boolean[]{true, false}) {
            List<Path> sil = front ? silhouetteFront : silhouetteBack;
            sil.add(circle(50, 14, 10.5f));
            sil.add(mirror(poly(45, 22, 45, 32, 50, 33)));
            sil.add(mirror(poly(46, 28, 35, 31, 26, 34, 31, 52, 33, 70, 33, 88, 32, 96, 50, 101)));      // torso
            sil.add(mirror(poly(27, 33, 21, 38, 19, 52, 18, 66, 17, 84, 18, 92, 24, 94, 27, 84, 30, 66, 32, 52))); // arm
            sil.add(mirror(circle(21, 97, 4f)));                                                           // hand
            sil.add(mirror(poly(32, 94, 50, 100, 49.6f, 112, 48, 140, 46, 150, 45, 186, 46, 196, 37, 197, 36, 186,
                    34, 150, 32, 140, 31, 116)));                                                          // leg
        }
        // ---- front muscles
        add(Muscles.SHOULDERS, true, curve(37, 33, 26, 33, 23, 47, 30, 49, 34, 41));
        add(Muscles.TRAPS, true, poly(45.5f, 27.5f, 37.5f, 32.2f, 45.5f, 32.5f));
        add(Muscles.CHEST, true, curve(37.5f, 34.5f, 49.5f, 34.5f, 49.5f, 53, 40, 57, 33.5f, 49));
        add(Muscles.BICEPS, true, curve(23.5f, 49.5f, 30.5f, 50.5f, 29.5f, 65, 22.5f, 63.5f, 22.5f, 56));
        add(Muscles.FOREARMS, true, curve(22, 66, 29, 67.5f, 26.5f, 87, 20, 85.5f, 20.5f, 75));
        // abs: three rows of the six-pack
        add(Muscles.ABS, true, rrect(43.5f, 56.5f, 49.4f, 64.5f));
        add(Muscles.ABS, true, rrect(43.5f, 66, 49.4f, 74.5f));
        add(Muscles.ABS, true, rrect(43.8f, 76, 49.4f, 87.5f));
        add(Muscles.OBLIQUES, true, poly(34.5f, 53.5f, 42, 57, 42, 87, 35.5f, 84, 33.8f, 70));
        add(Muscles.HIP_FLEXORS, true, poly(36, 88.5f, 48.5f, 93, 46.5f, 104, 34.5f, 96));
        add(Muscles.ABDUCTORS, true, poly(32.4f, 96, 34.6f, 98, 34.4f, 113, 31.6f, 110));
        add(Muscles.QUADS, true, curve(35.5f, 99.5f, 46, 105.5f, 45, 141, 37, 143, 33, 118));
        add(Muscles.ADDUCTORS, true, poly(47, 106, 49.5f, 107, 49, 125, 46, 131));
        add(Muscles.CALVES, true, curve(36, 151, 44.6f, 151, 43.8f, 182, 37.6f, 182, 35, 166));
        // ---- back muscles
        add(Muscles.TRAPS, false, poly(50, 23.5f, 37.5f, 32.5f, 43.5f, 37, 50, 47));
        add(Muscles.SHOULDERS, false, curve(37, 33, 26, 33, 23, 47, 30, 49, 34, 41));
        add(Muscles.MIDDLE_BACK, false, poly(43.8f, 38.2f, 50, 48.5f, 50, 58, 41.5f, 51));
        add(Muscles.LATS, false, poly(35, 39, 41, 51.5f, 49.5f, 60, 47, 77, 38, 66, 33.6f, 52));
        add(Muscles.TRICEPS, false, curve(23.5f, 49.5f, 30.5f, 50.5f, 29.5f, 65, 22.5f, 63.5f, 22.5f, 56));
        add(Muscles.FOREARMS, false, curve(22, 66, 29, 67.5f, 26.5f, 87, 20, 85.5f, 20.5f, 75));
        add(Muscles.LOWER_BACK, false, rrect(43.8f, 61.5f, 49.4f, 86));
        add(Muscles.GLUTES, false, curve(49.5f, 89.5f, 35, 87, 33.4f, 102, 37, 112.5f, 49.5f, 110.5f));
        add(Muscles.HAMSTRINGS, false, curve(34, 114.5f, 47.6f, 114.5f, 46, 142, 37.2f, 143, 33, 128));
        add(Muscles.ADDUCTORS, false, poly(48, 114.5f, 49.6f, 115, 49, 128, 47, 131));
        add(Muscles.CALVES, false, curve(35.6f, 149, 44.6f, 149, 45, 165, 42.5f, 180, 36.6f, 178, 34.2f, 163));
        add(Muscles.NECK, false, poly(45.5f, 22.5f, 50, 23, 50, 25, 44, 28));
    }

    private void add(String muscle, boolean front, Path left) {
        Part p = new Part(muscle, front);
        p.path.set(mirror(left));
        parts.add(p);
    }

    private static Path poly(float... xy) {
        Path p = new Path();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.close();
        return p;
    }

    /** Smooth closed shape through the points (quadratic curves between midpoints). */
    private static Path curve(float... xy) {
        int n = xy.length / 2;
        Path p = new Path();
        float mx = (xy[0] + xy[2 * (n - 1)]) / 2, my = (xy[1] + xy[2 * (n - 1) + 1]) / 2;
        p.moveTo(mx, my);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ex = (xy[2 * i] + xy[2 * j]) / 2, ey = (xy[2 * i + 1] + xy[2 * j + 1]) / 2;
            p.quadTo(xy[2 * i], xy[2 * i + 1], ex, ey);
        }
        p.close();
        return p;
    }

    private static Path rrect(float l, float t, float r, float b) {
        Path p = new Path();
        p.addRoundRect(new RectF(l, t, r, b), 2.2f, 2.2f, Path.Direction.CW);
        return p;
    }

    private static Path circle(float x, float y, float r) {
        Path p = new Path();
        p.addCircle(x, y, r, Path.Direction.CW);
        return p;
    }

    /** Left half + its mirror image around x = 50. */
    private static Path mirror(Path left) {
        Path out = new Path(left);
        Matrix mm = new Matrix();
        mm.setScale(-1, 1, 50, 0);
        Path right = new Path(left);
        right.transform(mm);
        out.addPath(right);
        return out;
    }

    // ---------------------------------------------------------------- drawing
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        float units = mode == SHOW_BOTH ? FIG_W * 2 + GAP : FIG_W;
        int want = (int) (w * (FIG_H + LABEL_H) / units);
        int h = resolveSize(Math.max(want, (int) (120 * getResources().getDisplayMetrics().density)), hSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight(), h = getHeight() - getPaddingTop() - getPaddingBottom();
        float units = mode == SHOW_BOTH ? FIG_W * 2 + GAP : FIG_W;
        float s = Math.min(w / units, h / (FIG_H + LABEL_H));
        float ox = getPaddingLeft() + (w - units * s) / 2, oy = getPaddingTop() + (h - (FIG_H + LABEL_H) * s) / 2;
        boolean relayout = s != lastScale || ox != lastOx || oy != lastOy;
        lastScale = s;
        lastOx = ox;
        lastOy = oy;
        bodyStroke.setStrokeWidth(Math.max(1f, s * 0.9f));
        seam.setStrokeWidth(Math.max(1f, s * 1.1f));
        select.setStrokeWidth(Math.max(1.5f, s * 1.2f));
        label.setTextSize(Math.max(9 * getResources().getDisplayMetrics().scaledDensity * 0.8f, s * 7.5f));
        if (mode != SHOW_BACK) figure(c, true, ox, oy, s, relayout);
        if (mode != SHOW_FRONT) figure(c, false, mode == SHOW_BOTH ? ox + (FIG_W + GAP) * s : ox, oy, s, relayout);
    }

    private void figure(Canvas c, boolean front, float ox, float oy, float s, boolean relayout) {
        m.setScale(s, s);
        m.postTranslate(ox, oy);
        for (Path p : front ? silhouetteFront : silhouetteBack) {
            tmp.set(p);
            tmp.transform(m);
            c.drawPath(tmp, body);
            c.drawPath(tmp, bodyStroke);
        }
        Region clip = relayout || clipRegion == null ? (clipRegion = new Region(0, 0, Math.max(1, getWidth()), Math.max(1, getHeight()))) : clipRegion;
        for (Part p : parts) {
            if (p.front != front) continue;
            if (relayout || p.screen.isEmpty()) {
                p.screen.set(p.path);
                p.screen.transform(m);
                p.region.setPath(p.screen, clip);
            }
            Float v = shown.get(p.muscle);
            float k = v == null ? 0f : Math.max(0f, Math.min(1f, v)) * anim;
            boolean sel = selectable && selected.contains(p.muscle);
            if (sel && k < 0.55f) k = 0.55f;
            if (k > 0.55f) { // soft halo around strong muscles (wide translucent strokes, hardware accelerated)
                glow.setColor(CalorieRingView.alpha(cHigh, (int) (70 * (k - 0.4f))));
                glow.setStrokeWidth(Math.max(3f, s * 4.5f));
                c.drawPath(p.screen, glow);
                glow.setColor(CalorieRingView.alpha(cHigh, (int) (110 * (k - 0.4f))));
                glow.setStrokeWidth(Math.max(2f, s * 2.2f));
                c.drawPath(p.screen, glow);
            }
            muscle.setColor(k <= 0.01f ? cIdle : k < 0.5f ? lerp(cLow, mid(), k / 0.5f) : lerp(mid(), cHigh, (k - 0.5f) / 0.5f));
            c.drawPath(p.screen, muscle);
            c.drawPath(p.screen, seam);
            if (sel) c.drawPath(p.screen, select);
        }
        label.setColor(cText);
        c.drawText(getContext().getString(front ? R.string.muscle_front : R.string.muscle_back).toUpperCase(Locale.getDefault()),
                ox + FIG_W / 2 * s, oy + (FIG_H + LABEL_H - 2) * s, label);
    }

    private int mid() {
        return 0xFF86B624;
    }

    private static int lerp(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        return Color.rgb((int) (ar + (Color.red(b) - ar) * t), (int) (ag + (Color.green(b) - ag) * t), (int) (ab + (Color.blue(b) - ab) * t));
    }

    // ---------------------------------------------------------------- touch + a11y
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!selectable) return super.onTouchEvent(e);
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int x = (int) e.getX(), y = (int) e.getY();
            for (Part p : parts) {
                if (p.region.contains(x, y)) {
                    toggle(p.muscle);
                    performClick();
                    return true;
                }
            }
            return true;
        }
        return e.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    public void toggle(String muscleId) {
        if (!selected.remove(muscleId)) selected.add(muscleId);
        invalidate();
        updateDescription();
        if (listener != null) listener.onChanged(selected());
    }

    private void updateDescription() {
        StringBuilder sb = new StringBuilder();
        if (selectable) {
            sb.append(getContext().getString(R.string.cd_muscle_select));
            if (!selected.isEmpty()) {
                List<String> l = new ArrayList<>();
                for (String sId : selected) l.add(Muscles.label(sId));
                sb.append(' ').append(getContext().getString(R.string.cd_muscle_selected, String.join(", ", l)));
            }
        } else {
            Map<String, Float> ordered = new LinkedHashMap<>();
            for (Map.Entry<String, Float> e : Muscles.ranked(values)) ordered.put(e.getKey(), e.getValue());
            if (ordered.isEmpty()) sb.append(getContext().getString(R.string.cd_muscle_none));
            else {
                List<String> l = new ArrayList<>();
                for (Map.Entry<String, Float> e : ordered.entrySet()) l.add(Muscles.label(e.getKey()) + " " + Math.round(e.getValue() * 100) + "%");
                sb.append(getContext().getString(R.string.cd_muscle_map, String.join(", ", l)));
            }
        }
        setContentDescription(sb.toString());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        lastScale = -1;
        for (Part p : parts) p.screen.reset();
    }

    /** Selectable map: lets TalkBack users pick muscles from a list instead of tapping shapes. */
    public static String[] muscleIds() {
        return Muscles.all().toArray(new String[0]);
    }
}

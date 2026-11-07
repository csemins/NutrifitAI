package com.transformsai.nutrifitai.ui.common;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.databinding.ViewShareCardBinding;
import com.transformsai.nutrifitai.databinding.ViewShareStatBinding;
import com.transformsai.nutrifitai.social.SocialModels.Post;
import com.transformsai.nutrifitai.ui.achievements.AchievementsActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Turns progress, workouts, achievements, challenge results and feed posts into a branded 1080×1350 image and
 * shares it through the Android chooser (FileProvider content URI, read permission granted to the receiver).
 */
public final class ShareCards {
    private static final int W = 1080, H = 1350;

    private ShareCards() { }

    /** What goes on a card. */
    public static final class Card {
        public String kicker = "", title = "";
        @Nullable public String subtitle, caption, big;
        @DrawableRes public int art;
        @Nullable public File photo;
        public boolean fitArt;
        /** Muscle heat map (muscle id -> 0..1) drawn in the art area with the top muscles listed beside it. */
        @Nullable public java.util.Map<String, Float> muscles;
        /** Walk route as [lat, lon] pairs, drawn as glowing route art in the art area. */
        @Nullable public List<double[]> route;
        /** Challenge podium (ranking order) drawn in the art area. */
        @Nullable public List<com.transformsai.nutrifitai.ui.widget.PodiumView.Entry> podium;
        public final List<String[]> stats = new ArrayList<>();

        public Card stat(String value, String label) {
            if (stats.size() < 4) stats.add(new String[]{value, label});
            return this;
        }
    }

    /** Renders and shares {@code card}; {@code chooserTitle} is shown on the chooser. */
    public static void share(Activity a, Card card, String chooserTitle) {
        AppGraph g = App.graph();
        String footer = footer(a);
        g.runIo(() -> {
            Bitmap photo = card.photo == null ? null : decode(card.photo);
            String handle = g.cards.myHandle();
            g.main.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                Bitmap out;
                try {
                    out = render(a, card, photo, handle == null ? footer : "@" + handle + " · " + footer);
                } catch (RuntimeException | OutOfMemoryError e) {
                    Ui.snack(a.findViewById(android.R.id.content), a.getString(R.string.share_failed));
                    return;
                }
                g.runIo(() -> {
                    Uri uri = write(a, out);
                    g.main.post(() -> {
                        if (uri == null) {
                            Ui.snack(a.findViewById(android.R.id.content), a.getString(R.string.share_failed));
                            return;
                        }
                        Intent send = new Intent(Intent.ACTION_SEND).setType("image/png")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_TEXT, card.title + (card.caption == null ? "" : "\n" + card.caption))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        send.setClipData(ClipData.newRawUri(card.title, uri));
                        a.startActivity(Intent.createChooser(send, chooserTitle));
                    });
                });
            });
        });
    }

    private static String footer(Context c) {
        return c.getString(R.string.share_footer);
    }

    /** A feed post as a card (stats, workout, challenge, achievement, meal photo or text). */
    public static void sharePost(Activity a, Post p, @Nullable File media) {
        Card c = new Card();
        c.kicker = typeKicker(a, p.type);
        c.title = p.authorName == null ? "" : p.authorName;
        c.caption = p.text == null || p.text.isEmpty() ? null : p.text;
        String type = p.type == null ? "" : p.type;
        switch (type) {
            case Post.STATS:
                c.subtitle = a.getString(R.string.share_sub_stats);
                c.stat(Ui.kcal(p.num("kcal")), a.getString(R.string.unit_kcal))
                        .stat(Ui.g(p.num("protein")), a.getString(R.string.protein))
                        .stat(String.format(Locale.getDefault(), "%.1f L", p.num("waterMl") / 1000), a.getString(R.string.water))
                        .stat(String.format(Locale.getDefault(), "%,d", (long) p.num("steps")), a.getString(R.string.steps_label));
                c.art = R.drawable.ic3d_streak;
                c.fitArt = true;
                break;
            case Post.WORKOUT:
                c.subtitle = p.str("title");
                c.art = R.drawable.ic3d_fit;
                c.fitArt = true;
                c.stat(String.valueOf((long) p.num("minutes")), a.getString(R.string.unit_min))
                        .stat(String.valueOf((long) p.num("reps")), a.getString(R.string.reps_label))
                        .stat(String.valueOf((long) p.num("exercises")), a.getString(R.string.exercises_label))
                        .stat(Ui.kcal(p.num("kcal")), a.getString(R.string.unit_kcal));
                break;
            case Post.CHALLENGE:
                c.subtitle = p.str("title");
                c.big = "#" + (long) p.num("place");
                c.stat(String.valueOf((long) p.num("score")), a.getString(R.string.challenge_points))
                        .stat(String.valueOf((long) p.num("reps")), a.getString(R.string.reps_label))
                        .stat(String.valueOf((long) p.num("faults")), a.getString(R.string.challenge_faults));
                break;
            case Post.ACHIEVEMENT:
                c.subtitle = p.str("title");
                c.art = AchievementsActivity.badgeImage(p.str("badge"));
                c.fitArt = true;
                break;
            default:
                if (media != null && !String.valueOf(p.media).endsWith(".mp4")) c.photo = media;
                else c.art = R.drawable.ill_meals;
        }
        share(a, c, a.getString(R.string.share));
    }

    private static String typeKicker(Context c, @Nullable String type) {
        if (type == null) return c.getString(R.string.app_name);
        switch (type) {
            case Post.STATS: return c.getString(R.string.post_type_stats);
            case Post.WORKOUT: return c.getString(R.string.post_type_workout);
            case Post.MEAL: return c.getString(R.string.post_type_meal);
            case Post.CHALLENGE: return c.getString(R.string.post_type_challenge);
            case Post.ACHIEVEMENT: return c.getString(R.string.post_type_achievement);
            case Post.CLIP: return c.getString(R.string.post_type_clip);
            default: return c.getString(R.string.post_type_text);
        }
    }

    // ------------------------------------------------------------------ rendering
    @Nullable
    private static Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= W) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    @androidx.annotation.VisibleForTesting
    static Bitmap render(Context base, Card card, @Nullable Bitmap photo, String footer) {
        // fixed 411dp-wide layout scaled to 1080 px, always in the dark palette whatever the app theme
        android.content.res.Configuration cfg = new android.content.res.Configuration(base.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        Context c = new ContextThemeWrapper(base.getApplicationContext().createConfigurationContext(cfg), R.style.Theme_NutriFit);
        ViewShareCardBinding v = ViewShareCardBinding.inflate(LayoutInflater.from(c));
        v.date.setText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date()));
        v.kicker.setText(card.kicker);
        v.title.setText(card.title);
        v.subtitle.setText(card.subtitle);
        v.subtitle.setVisibility(card.subtitle == null || card.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        v.caption.setText(card.caption == null ? "" : "“" + card.caption + "”");
        v.caption.setVisibility(card.caption == null ? View.GONE : View.VISIBLE);
        v.footer.setText(footer);
        if (photo != null) {
            v.art.setImageBitmap(photo);
        } else if (card.art != 0) {
            v.art.setImageResource(card.art);
            if (card.fitArt) {
                v.art.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                v.art.setBackground(null);
            }
        } else {
            v.art.setVisibility(View.GONE);
        }
        android.widget.FrameLayout artBox = (android.widget.FrameLayout) v.art.getParent();
        if (card.route != null && card.route.size() >= 2) {
            com.transformsai.nutrifitai.ui.widget.RouteArtView art = new com.transformsai.nutrifitai.ui.widget.RouteArtView(c);
            art.setRoute(card.route);
            art.setBackgroundResource(R.drawable.bg_share_tile);
            artBox.addView(art, new android.widget.FrameLayout.LayoutParams(-1, -1));
            v.art.setVisibility(View.GONE);
            card.big = null;
        } else if (card.podium != null && !card.podium.isEmpty()) {
            com.transformsai.nutrifitai.ui.widget.PodiumView pv = new com.transformsai.nutrifitai.ui.widget.PodiumView(c);
            pv.setEntries(card.podium);
            artBox.addView(pv, new android.widget.FrameLayout.LayoutParams(-1, -1));
            v.art.setVisibility(View.GONE);
            card.big = null;
        } else if (card.muscles != null && !card.muscles.isEmpty()) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(c);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            com.transformsai.nutrifitai.ui.widget.MuscleMapView map = new com.transformsai.nutrifitai.ui.widget.MuscleMapView(c, null);
            map.setIntensities(card.muscles);
            row.addView(map, new android.widget.LinearLayout.LayoutParams(0, -1, 1f));
            android.widget.LinearLayout list = new android.widget.LinearLayout(c);
            list.setOrientation(android.widget.LinearLayout.VERTICAL);
            list.setGravity(android.view.Gravity.CENTER_VERTICAL);
            List<java.util.Map.Entry<String, Float>> top = new ArrayList<>(card.muscles.entrySet());
            top.sort((a, b2) -> Float.compare(b2.getValue(), a.getValue()));
            for (int i = 0; i < Math.min(3, top.size()); i++) list.addView(muscleRow(c, top.get(i).getKey(), top.get(i).getValue()));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, -1, 1.1f);
            lp.setMarginStart((int) (12 * c.getResources().getDisplayMetrics().density));
            row.addView(list, lp);
            row.setBackgroundResource(R.drawable.bg_share_tile);
            int pad = (int) (12 * c.getResources().getDisplayMetrics().density);
            row.setPadding(pad, pad, pad, pad);
            artBox.addView(row, new android.widget.FrameLayout.LayoutParams(-1, -1));
            v.art.setVisibility(View.GONE);
        }
        v.big.setVisibility(card.big == null ? View.GONE : View.VISIBLE);
        v.big.setText(card.big);
        if (card.big != null) v.art.setVisibility(View.GONE);
        if (v.art.getVisibility() == View.GONE && card.big == null && artBox.getChildCount() <= 2) {
            artBox.setVisibility(View.GONE); // nothing to show: the stats move up instead of leaving a gap
        }
        LayoutInflater inf = LayoutInflater.from(c);
        for (int i = 0; i < card.stats.size(); i++) {
            ViewShareStatBinding s = ViewShareStatBinding.inflate(inf, v.stats, false);
            s.value.setText(card.stats.get(i)[0]);
            s.label.setText(card.stats.get(i)[1]);
            if (i > 0) ((android.widget.LinearLayout.LayoutParams) s.getRoot().getLayoutParams()).setMarginStart(
                    (int) (8 * c.getResources().getDisplayMetrics().density));
            v.stats.addView(s.getRoot());
        }
        v.stats.setVisibility(card.stats.isEmpty() ? View.GONE : View.VISIBLE);

        float density = c.getResources().getDisplayMetrics().density;
        float scale = W / (411f * density);
        int w = Math.round(W / scale), h = Math.round(H / scale);
        View root = v.getRoot();
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.scale(scale, scale);
        root.draw(canvas);
        return out;
    }

    /** Compact "Chest 100%" line with a thin bar under it, sized for the share card's narrow column. */
    private static View muscleRow(Context c, String muscle, float intensity) {
        float d = c.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout col = new android.widget.LinearLayout(c);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(0, (int) (4 * d), 0, (int) (4 * d));
        android.widget.LinearLayout top = new android.widget.LinearLayout(c);
        android.widget.TextView name = new android.widget.TextView(c);
        name.setText(com.transformsai.nutrifitai.fitness.Muscles.label(muscle));
        name.setTextSize(13);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.share_text));
        name.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(c, R.font.jakarta_semibold));
        top.addView(name, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.TextView pct = new android.widget.TextView(c);
        pct.setText(c.getString(R.string.percent, Math.round(intensity * 100)));
        pct.setTextSize(13);
        pct.setTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.share_lime));
        pct.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(c, R.font.jakarta_extrabold));
        top.addView(pct, new android.widget.LinearLayout.LayoutParams(-2, -2));
        col.addView(top);
        android.widget.LinearLayout bar = new android.widget.LinearLayout(c);
        View fill = new View(c), rest = new View(c);
        fill.setBackgroundColor(androidx.core.content.ContextCompat.getColor(c, R.color.share_lime));
        rest.setBackgroundColor(androidx.core.content.ContextCompat.getColor(c, R.color.share_stroke));
        float v = Math.max(0.03f, Math.min(1f, intensity));
        bar.addView(fill, new android.widget.LinearLayout.LayoutParams(0, -1, v));
        bar.addView(rest, new android.widget.LinearLayout.LayoutParams(0, -1, 1f - v + 0.0001f));
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(-1, (int) (5 * d));
        blp.topMargin = (int) (3 * d);
        col.addView(bar, blp);
        return col;
    }

    @Nullable
    private static Uri write(Context c, Bitmap bmp) {
        File dir = new File(c.getCacheDir(), "cards");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 3600_000L) f.delete();
        File f = new File(dir, "nutrifit_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            return null;
        } finally {
            bmp.recycle();
        }
        return FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
    }
}

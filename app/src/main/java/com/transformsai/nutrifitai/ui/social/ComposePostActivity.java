package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.databinding.ActivityComposePostBinding;
import com.transformsai.nutrifitai.databinding.ViewPostStatBinding;
import com.transformsai.nutrifitai.social.SocialModels.Post;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.ImageUtils;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Share to the feed: today's numbers, the latest workout, a meal photo or a short clip. Numbers come from the
 * user's own saved logs; photos and clips are picked with the system photo picker (or come from a workout
 * recording) and read through the ContentResolver.
 */
public class ComposePostActivity extends BaseActivity {
    private static final String EXTRA_TYPE = "type", EXTRA_MEDIA = "media";
    private static final long MAX_CLIP_BYTES = 25L * 1024 * 1024;

    public static Intent intent(Context c, String type, @Nullable String mediaPath) {
        return new Intent(c, ComposePostActivity.class).putExtra(EXTRA_TYPE, type).putExtra(EXTRA_MEDIA, mediaPath);
    }

    private ActivityComposePostBinding b;
    private String type;
    private final Map<String, Object> stats = new HashMap<>(), workout = new HashMap<>();
    @Nullable private File media;
    private boolean posting;

    private final ActivityResultLauncher<PickVisualMediaRequest> pick =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onPicked);

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        String uid = uidOrFinish();
        if (uid == null) return;
        b = ActivityComposePostBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, false);
        InsetsUtil.padding(b.bottom, false, true);
        b.bar.title.setText(R.string.share_title);
        b.bar.back.setOnClickListener(v -> finish());
        type = s != null ? s.getString(EXTRA_TYPE, Post.STATS) : getIntent().getStringExtra(EXTRA_TYPE);
        if (type == null) type = Post.STATS;
        String path = s != null ? s.getString(EXTRA_MEDIA) : getIntent().getStringExtra(EXTRA_MEDIA);
        if (path != null && new File(path).isFile()) media = new File(path);
        b.types.check(chipFor(type));
        b.types.setOnCheckedStateChangeListener((g, ids) -> {
            type = typeFor(g.getCheckedChipId());
            if (media != null && !mediaMatches()) media = null;
            render();
        });
        b.pick.setOnClickListener(v -> pick.launch(new PickVisualMediaRequest.Builder().setMediaType(Post.CLIP.equals(type)
                ? ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE : ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        b.media.setOnClickListener(v -> b.pick.performClick());
        Ui.onClick(b.share, v -> share());

        String today = DateUtil.today();
        graph().diary.observeTotals(uid, today).observe(this, t -> {
            stats.put("kcal", t == null ? 0 : Math.round(t.kcal));
            stats.put("protein", t == null ? 0 : Math.round(t.protein));
            render();
        });
        graph().water.observeTotal(uid, today).observe(this, ml -> {
            stats.put("waterMl", ml == null ? 0 : ml);
            render();
        });
        graph().steps.observeDay(uid, today).observe(this, d -> {
            stats.put("steps", d == null ? 0 : d.steps);
            render();
        });
        graph().workouts.observeRecent(uid, 1).observe(this, l -> {
            workout.clear();
            if (l != null && !l.isEmpty()) {
                WorkoutSessionEntity w = l.get(0);
                workout.put("title", w.title);
                workout.put("minutes", Math.max(1, Math.round(w.activeMs / 60000.0)));
                workout.put("reps", w.totalReps);
                workout.put("exercises", w.exercisesDone);
                workout.put("kcal", Math.round(w.kcalEstimate));
                workout.put("date", w.date);
            }
            render();
        });
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(EXTRA_TYPE, type);
        if (media != null) out.putString(EXTRA_MEDIA, media.getAbsolutePath());
    }

    private static int chipFor(String type) {
        switch (type) {
            case Post.WORKOUT: return R.id.tWorkout;
            case Post.MEAL: return R.id.tPhoto;
            case Post.CLIP: return R.id.tClip;
            default: return R.id.tStats;
        }
    }

    private static String typeFor(int chip) {
        if (chip == R.id.tWorkout) return Post.WORKOUT;
        if (chip == R.id.tPhoto) return Post.MEAL;
        if (chip == R.id.tClip) return Post.CLIP;
        return Post.STATS;
    }

    private boolean mediaMatches() {
        return media != null && (Post.CLIP.equals(type) == media.getName().endsWith(".mp4"));
    }

    private void render() {
        if (b == null) return;
        boolean numbers = Post.STATS.equals(type) || Post.WORKOUT.equals(type);
        b.stats.setVisibility(numbers ? View.VISIBLE : View.GONE);
        b.statsNote.setVisibility(Post.WORKOUT.equals(type) ? View.VISIBLE : View.GONE);
        b.mediaBox.setVisibility(numbers ? View.GONE : View.VISIBLE);
        ViewPostStatBinding[] cells = {b.s1, b.s2, b.s3, b.s4};
        if (Post.STATS.equals(type)) {
            set(cells[0], Ui.kcal(num(stats, "kcal")), R.string.unit_kcal);
            set(cells[1], Ui.g(num(stats, "protein")), R.string.protein);
            set(cells[2], String.format(Locale.getDefault(), "%.1f L", num(stats, "waterMl") / 1000), R.string.water);
            set(cells[3], String.format(Locale.getDefault(), "%,d", (long) num(stats, "steps")), R.string.steps_label);
        } else if (Post.WORKOUT.equals(type)) {
            set(cells[0], String.valueOf((long) num(workout, "minutes")), R.string.unit_min);
            set(cells[1], String.valueOf((long) num(workout, "reps")), R.string.reps_label);
            set(cells[2], String.valueOf((long) num(workout, "exercises")), R.string.exercises_label);
            set(cells[3], Ui.kcal(num(workout, "kcal")), R.string.unit_kcal);
            b.statsNote.setText(workout.isEmpty() ? getString(R.string.share_no_workout)
                    : getString(R.string.share_workout_note, String.valueOf(workout.get("title"))));
        } else {
            b.pick.setText(Post.CLIP.equals(type) ? R.string.share_pick_clip : R.string.share_pick_photo);
            b.pick.setVisibility(media == null ? View.VISIBLE : View.GONE);
            if (media != null) Glide.with(this).load(media).centerCrop().into(b.media);
            else b.media.setImageDrawable(null);
        }
        boolean ready = numbers ? !(Post.WORKOUT.equals(type) && workout.isEmpty()) : media != null;
        b.share.setEnabled(ready && !posting);
    }

    private static double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private void set(ViewPostStatBinding cell, String value, int label) {
        cell.value.setText(value);
        cell.label.setText(label);
    }

    /** Photos are resized and re-encoded (no EXIF location); clips are copied as they are (25 MB limit). */
    private void onPicked(@Nullable Uri uri) {
        if (uri == null) return;
        boolean clip = Post.CLIP.equals(type);
        graph().runIo(() -> {
            try {
                File dir = new File(getCacheDir(), "compose");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File out = new File(dir, "post_" + System.currentTimeMillis() + (clip ? ".mp4" : ".jpg"));
                if (clip) copy(uri, out);
                else ImageUtils.process(this, uri, out);
                graph().main.post(() -> {
                    media = out;
                    render();
                });
            } catch (Exception e) {
                graph().main.post(() -> Ui.snack(b.getRoot(), getString(clip ? R.string.share_clip_too_big : R.string.cam_error)));
            }
        });
    }

    private void copy(Uri uri, File out) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new java.io.IOException("unreadable");
            byte[] buf = new byte[65536];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_CLIP_BYTES) throw new java.io.IOException("too large");
                os.write(buf, 0, n);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
    }

    private void share() {
        if (posting) return;
        posting = true;
        b.progress.setVisibility(View.VISIBLE);
        b.share.setTextScaleX(0f);
        render();
        String text = b.text.getText() == null ? "" : b.text.getText().toString().trim();
        Map<String, Object> data = Post.STATS.equals(type) ? new HashMap<>(stats) : Post.WORKOUT.equals(type) ? new HashMap<>(workout) : new HashMap<>();
        if (text.isEmpty() && Post.WORKOUT.equals(type)) text = getString(R.string.share_workout_default, String.valueOf(workout.get("title")));
        graph().feed.post(type, text, data, Post.STATS.equals(type) || Post.WORKOUT.equals(type) ? null : media, r -> {
            if (b == null) return;
            posting = false;
            b.progress.setVisibility(View.GONE);
            b.share.setTextScaleX(1f);
            render();
            if (r.ok()) {
                Ui.snack(b.getRoot(), getString(R.string.share_done));
                b.getRoot().postDelayed(this::finish, 600);
            } else {
                Ui.snack(b.getRoot(), r.error);
            }
        });
    }

    @Override
    protected void onDestroy() {
        b = null;
        super.onDestroy();
    }
}

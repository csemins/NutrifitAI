package com.transformsai.nutrifitai.ui.achievements;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityAchievementsBinding;
import com.transformsai.nutrifitai.databinding.ItemBadgeBinding;
import com.transformsai.nutrifitai.databinding.ViewAchievementHeaderBinding;
import com.transformsai.nutrifitai.domain.Achievements;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;

/** Streak header and a grid of badges; locked badges are greyed out and show their progress. */
public class AchievementsActivity extends BaseActivity {
    private ActivityAchievementsBinding b;
    @Nullable private Achievements.Summary summary;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        String uid = uidOrFinish();
        if (uid == null) return;
        b = ActivityAchievementsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.bar.getRoot(), true, false);
        InsetsUtil.padding(b.list, false, true);
        b.bar.title.setText(R.string.achievements);
        b.bar.back.setOnClickListener(v -> finish());
        int span = getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 2;
        GridLayoutManager lm = new GridLayoutManager(this, span);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position == 0 ? span : 1;
            }
        });
        b.list.setLayoutManager(lm);
        Adapter a = new Adapter();
        b.list.setAdapter(a);
        graph().runIo(() -> {
            Achievements.Summary sum = Achievements.compute(graph().db, uid);
            graph().main.post(() -> {
                summary = sum;
                a.notifyDataSetChanged();
            });
        });
    }

    public static int badgeImage(String id) {
        switch (id) {
            case "first_log": return R.drawable.badge_first_log;
            case "streak_3": return R.drawable.badge_streak_3;
            case "streak_7": return R.drawable.badge_streak_7;
            case "streak_30": return R.drawable.badge_streak_30;
            case "hydration": return R.drawable.badge_hydration;
            case "protein": return R.drawable.badge_protein;
            case "balanced": return R.drawable.badge_balanced;
            case "first_workout": return R.drawable.badge_first_workout;
            case "workouts_10": return R.drawable.badge_workouts_10;
            case "steps_10k": return R.drawable.badge_steps_10k;
            case "ai_scan": return R.drawable.badge_ai_scan;
            case "fibre_fan": return R.drawable.ic3d_recipes;
            case "variety": return R.drawable.ic3d_plan;
            case "hydration_30": return R.drawable.ic3d_water;
            case "steps_15k": return R.drawable.ic3d_steps;
            case "workouts_50": return R.drawable.ic3d_muscle;
            case "logged_100": return R.drawable.ic3d_trophy;
            case "streak_100": return R.drawable.ic3d_streak;
            default: return R.drawable.badge_early_bird;
        }
    }

    /** An earned badge: share it as an image card, or post it to friends' feeds. */
    private void shareBadge(Achievements.Badge badge) {
        String[] items = {getString(R.string.badge_share_image), getString(R.string.badge_post_friends)};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(badge.title)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        com.transformsai.nutrifitai.ui.common.ShareCards.Card c = new com.transformsai.nutrifitai.ui.common.ShareCards.Card();
                        c.kicker = getString(R.string.badge_unlocked);
                        c.title = badge.title;
                        c.subtitle = badge.detail;
                        c.art = badgeImage(badge.id);
                        c.fitArt = true;
                        if (summary != null) {
                            c.stat(String.valueOf(summary.currentStreak), getString(R.string.share_day_streak))
                                    .stat(summary.earned() + "/" + summary.badges.size(), getString(R.string.achievements));
                        }
                        com.transformsai.nutrifitai.ui.common.ShareCards.share(this, c, getString(R.string.share));
                    } else {
                        java.util.Map<String, Object> data = new java.util.HashMap<>();
                        data.put("badge", badge.id);
                        data.put("title", badge.title);
                        graph().feed.post(com.transformsai.nutrifitai.social.SocialModels.Post.ACHIEVEMENT,
                                getString(R.string.badge_post_text, badge.title), data, null, r ->
                                        com.transformsai.nutrifitai.ui.common.Ui.snack(b.getRoot(), r.ok() ? getString(R.string.badge_posted) : r.error));
                    }
                }).show();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
            LayoutInflater inf = LayoutInflater.from(p.getContext());
            View v = type == 0 ? ViewAchievementHeaderBinding.inflate(inf, p, false).getRoot() : ItemBadgeBinding.inflate(inf, p, false).getRoot();
            return new RecyclerView.ViewHolder(v) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            if (summary == null) return;
            if (pos == 0) {
                ViewAchievementHeaderBinding hb = ViewAchievementHeaderBinding.bind(h.itemView);
                hb.streak.setText(getResources().getQuantityString(R.plurals.days, summary.currentStreak, summary.currentStreak));
                hb.best.setText(getString(R.string.best_streak, summary.bestStreak, summary.earned(), summary.badges.size()));
                h.itemView.setContentDescription(getString(R.string.cd_streak, summary.currentStreak, summary.bestStreak));
                return;
            }
            Achievements.Badge badge = summary.badges.get(pos - 1);
            ItemBadgeBinding bb = ItemBadgeBinding.bind(h.itemView);
            bb.image.setImageResource(badgeImage(badge.id));
            bb.title.setText(badge.title);
            bb.detail.setText(badge.detail);
            boolean earned = badge.earned();
            if (earned) {
                bb.image.clearColorFilter();
                bb.image.setAlpha(1f);
                bb.progress.setText(R.string.badge_earned);
            } else {
                ColorMatrix m = new ColorMatrix();
                m.setSaturation(0f);
                bb.image.setColorFilter(new ColorMatrixColorFilter(m));
                bb.image.setAlpha(0.35f);
                bb.progress.setText(getString(R.string.badge_progress, badge.progress, badge.goal));
            }
            bb.getRoot().setAlpha(earned ? 1f : 0.85f);
            bb.getRoot().setOnClickListener(earned ? v -> shareBadge(badge) : null);
            bb.getRoot().setContentDescription(badge.title + ". " + badge.detail + ". "
                    + (earned ? getString(R.string.badge_earned) : getString(R.string.badge_progress, badge.progress, badge.goal)));
        }

        @Override
        public int getItemCount() {
            return summary == null ? 0 : summary.badges.size() + 1;
        }
    }
}

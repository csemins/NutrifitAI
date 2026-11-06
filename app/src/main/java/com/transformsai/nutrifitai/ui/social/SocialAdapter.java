package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ItemPostBinding;
import com.transformsai.nutrifitai.databinding.ItemSocialEmptyBinding;
import com.transformsai.nutrifitai.databinding.ItemSocialRowBinding;
import com.transformsai.nutrifitai.databinding.ItemSocialSectionBinding;
import com.transformsai.nutrifitai.databinding.ViewPostStatBinding;
import com.transformsai.nutrifitai.social.SocialModels.Post;
import com.transformsai.nutrifitai.social.FeedRepository;
import com.transformsai.nutrifitai.ui.achievements.AchievementsActivity;
import com.transformsai.nutrifitai.ui.common.Avatars;
import com.transformsai.nutrifitai.ui.common.Ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One list for the Social tabs: section headers, posts, people/challenge rows and empty states. */
final class SocialAdapter extends ListAdapter<SocialAdapter.Row, RecyclerView.ViewHolder> {
    static final int SECTION = 0, POST = 1, ROW = 2, EMPTY = 3;

    /** Everything a row can show; built by SocialFragment from the live Firestore lists. */
    static final class Row {
        final int type;
        final String key;
        String title, subtitle, primary, avatarText;
        @Nullable String photo, uid;
        int unread;
        @DrawableRes int secondaryIcon, art;
        @Nullable String secondaryDescription;
        @Nullable Post post;
        @Nullable Runnable onClick, onPrimary, onSecondary;

        Row(int type, String key) {
            this.type = type;
            this.key = key;
        }

        static Row section(String text) {
            Row r = new Row(SECTION, "s:" + text);
            r.title = text;
            return r;
        }

        static Row empty(String key, @DrawableRes int art, String title, String body, @Nullable String action, @Nullable Runnable onAction) {
            Row r = new Row(EMPTY, "e:" + key);
            r.art = art;
            r.title = title;
            r.subtitle = body;
            r.primary = action;
            r.onPrimary = onAction;
            return r;
        }

        static Row post(Post p) {
            Row r = new Row(POST, "p:" + p.id);
            r.post = p;
            return r;
        }

        /** Content equality for DiffUtil (callbacks are not compared). */
        boolean same(Row o) {
            if (post != null && o.post != null) {
                return post.reactions.equals(o.post.reactions) && post.commentCount == o.post.commentCount
                        && Objects.equals(post.text, o.post.text) && Objects.equals(post.authorPhoto, o.post.authorPhoto);
            }
            return Objects.equals(title, o.title) && Objects.equals(subtitle, o.subtitle) && Objects.equals(primary, o.primary)
                    && secondaryIcon == o.secondaryIcon && Objects.equals(photo, o.photo) && unread == o.unread;
        }
    }

    /** Post interactions handled by the fragment. */
    interface PostActions {
        /** {@code reaction} null removes mine. */
        void react(Post p, @Nullable String reaction);

        void openComments(Post p);

        void share(Post p);

        void openMedia(Post p);

        void more(Post p, View anchor);

        void loadMedia(Post p, android.widget.ImageView into);

        boolean mine(Post p);

        @Nullable
        String myReaction(Post p);
    }

    private final PostActions actions;

    SocialAdapter(PostActions actions) {
        super(new DiffUtil.ItemCallback<Row>() {
            @Override
            public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) {
                return a.key.equals(b.key);
            }

            @Override
            public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) {
                return a.same(b);
            }
        });
        this.actions = actions;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
        LayoutInflater in = LayoutInflater.from(p.getContext());
        switch (type) {
            case SECTION: return new Holder<>(ItemSocialSectionBinding.inflate(in, p, false));
            case POST: return new Holder<>(ItemPostBinding.inflate(in, p, false));
            case EMPTY: return new Holder<>(ItemSocialEmptyBinding.inflate(in, p, false));
            default: return new Holder<>(ItemSocialRowBinding.inflate(in, p, false));
        }
    }

    static final class Holder<B extends androidx.viewbinding.ViewBinding> extends RecyclerView.ViewHolder {
        final B v;

        Holder(B v) {
            super(v.getRoot());
            this.v = v;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int i) {
        Row r = getItem(i);
        switch (r.type) {
            case SECTION:
                ((Holder<ItemSocialSectionBinding>) h).v.text.setText(r.title);
                break;
            case POST:
                bindPost(((Holder<ItemPostBinding>) h).v, Objects.requireNonNull(r.post));
                break;
            case EMPTY:
                bindEmpty(((Holder<ItemSocialEmptyBinding>) h).v, r);
                break;
            default:
                bindRow(((Holder<ItemSocialRowBinding>) h).v, r);
        }
    }

    private static void bindRow(ItemSocialRowBinding v, Row r) {
        if (r.avatarText != null) Avatars.initial(v.avatar, r.avatarText);
        else Avatars.of(v.avatar, r.uid, r.photo, r.title);
        v.title.setTypeface(v.title.getTypeface(), r.unread > 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        v.title.setText(r.title);
        v.subtitle.setText(r.subtitle);
        v.subtitle.setTextColor(androidx.core.content.ContextCompat.getColor(v.getRoot().getContext(),
                r.unread > 0 ? R.color.nl_lime : R.color.nl_text_3));
        v.subtitle.setVisibility(r.subtitle == null || r.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        v.primary.setVisibility(r.primary == null ? View.GONE : View.VISIBLE);
        v.primary.setText(r.primary);
        v.primary.setOnClickListener(r.onPrimary == null ? null : x -> r.onPrimary.run());
        v.secondary.setVisibility(r.secondaryIcon == 0 ? View.GONE : View.VISIBLE);
        if (r.secondaryIcon != 0) v.secondary.setImageResource(r.secondaryIcon);
        v.secondary.setContentDescription(r.secondaryDescription);
        v.secondary.setOnClickListener(r.onSecondary == null ? null : x -> r.onSecondary.run());
        v.getRoot().setOnClickListener(r.onClick == null ? null : x -> r.onClick.run());
        v.getRoot().setClickable(r.onClick != null);
    }

    private static void bindEmpty(ItemSocialEmptyBinding v, Row r) {
        v.art.setImageResource(r.art);
        v.title.setText(r.title);
        v.body.setText(r.subtitle);
        v.action.setVisibility(r.primary == null ? View.GONE : View.VISIBLE);
        v.action.setText(r.primary);
        v.action.setOnClickListener(r.onPrimary == null ? null : x -> r.onPrimary.run());
    }

    private void bindPost(ItemPostBinding v, Post p) {
        Context c = v.getRoot().getContext();
        Avatars.of(v.avatar, p.author, p.authorPhoto, p.authorName);
        v.name.setText(p.authorName);
        v.meta.setText(c.getString(R.string.post_meta, typeLabel(c, p.type), Ui.ago(c, p.createdAt)));
        v.text.setText(p.text);
        v.text.setVisibility(p.text == null || p.text.isEmpty() ? View.GONE : View.VISIBLE);
        bindStats(v, p);
        boolean badge = Post.ACHIEVEMENT.equals(p.type);
        boolean hasMedia = p.media != null;
        v.mediaBox.setVisibility(hasMedia || badge ? View.VISIBLE : View.GONE);
        v.media.setScaleType(badge ? android.widget.ImageView.ScaleType.FIT_CENTER : android.widget.ImageView.ScaleType.CENTER_CROP);
        v.media.setBackgroundResource(badge ? R.drawable.bg_card_neon : R.drawable.bg_photo_round);
        v.play.setVisibility(!badge && hasMedia && p.media.endsWith(".mp4") ? View.VISIBLE : View.GONE);
        v.media.setImageDrawable(null);
        v.mediaBox.setOnClickListener(null);
        if (badge) {
            v.media.setImageResource(AchievementsActivity.badgeImage(p.str("badge")));
        } else if (hasMedia) {
            actions.loadMedia(p, v.media);
            v.mediaBox.setOnClickListener(x -> actions.openMedia(p));
        }
        bindReactions(v, p);
        v.comment.setOnClickListener(x -> actions.openComments(p));
        v.commentSummary.setOnClickListener(x -> actions.openComments(p));
        v.share.setOnClickListener(x -> actions.share(p));
        v.more.setVisibility(actions.mine(p) ? View.VISIBLE : View.GONE);
        v.more.setOnClickListener(x -> actions.more(p, x));
    }

    /** "👍❤️ 5 · 2 comments", and my own reaction on the button (tap = like/unlike, hold = pick one). */
    private void bindReactions(ItemPostBinding v, Post p) {
        Context c = v.getRoot().getContext();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String r : p.reactions.values()) counts.merge(r, 1, Integer::sum);
        List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, top.size()); i++) sb.append(FeedRepository.emoji(top.get(i).getKey()));
        int total = p.reactions.size();
        v.reactSummary.setText(total == 0 ? "" : sb + "  " + total);
        v.reactSummary.setContentDescription(c.getResources().getQuantityString(R.plurals.reactions_count, total, total));
        v.commentSummary.setText(p.commentCount == 0 ? "" : c.getResources().getQuantityString(R.plurals.comments_count,
                (int) p.commentCount, (int) p.commentCount));
        v.summary.setVisibility(total == 0 && p.commentCount == 0 ? View.GONE : View.VISIBLE);

        String mine = actions.myReaction(p);
        if (mine == null) {
            v.react.setText(R.string.react_like);
            v.react.setIconResource(R.drawable.ic_heart_outline);
            v.react.setTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.nl_text_2));
        } else {
            v.react.setText(FeedRepository.emoji(mine) + "  " + ReactionPicker.label(c, mine));
            v.react.setIcon(null);
            v.react.setTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.nl_lime));
        }
        v.react.setContentDescription(c.getString(R.string.cd_react, ReactionPicker.label(c, mine)));
        v.react.setOnClickListener(x -> {
            Ui.haptic(x);
            actions.react(p, mine == null ? "like" : null);
        });
        v.react.setOnLongClickListener(x -> {
            Ui.haptic(x);
            ReactionPicker.show(x, mine, key -> actions.react(p, key));
            return true;
        });
    }

    private static void bindStats(ItemPostBinding v, Post p) {
        Context c = v.getRoot().getContext();
        ViewPostStatBinding[] cells = {v.s1, v.s2, v.s3, v.s4};
        String[][] shown;
        switch (p.type == null ? "" : p.type) {
            case Post.STATS:
                shown = new String[][]{{Ui.kcal(p.num("kcal")), c.getString(R.string.unit_kcal)},
                        {Ui.g(p.num("protein")), c.getString(R.string.protein)},
                        {String.format(Locale.getDefault(), "%.1f L", p.num("waterMl") / 1000), c.getString(R.string.water)},
                        {String.format(Locale.getDefault(), "%,d", (long) p.num("steps")), c.getString(R.string.steps_label)}};
                break;
            case Post.WORKOUT:
                shown = new String[][]{{String.valueOf((long) p.num("minutes")), c.getString(R.string.unit_min)},
                        {String.valueOf((long) p.num("reps")), c.getString(R.string.reps_label)},
                        {String.valueOf((long) p.num("exercises")), c.getString(R.string.exercises_label)},
                        {Ui.kcal(p.num("kcal")), c.getString(R.string.unit_kcal)}};
                break;
            case Post.CHALLENGE:
                shown = new String[][]{{"#" + (long) p.num("place"), c.getString(R.string.challenge_place)},
                        {String.valueOf((long) p.num("score")), c.getString(R.string.challenge_points)},
                        {String.valueOf((long) p.num("reps")), c.getString(R.string.reps_label)},
                        {String.valueOf((long) p.num("faults")), c.getString(R.string.challenge_faults)}};
                break;
            default:
                shown = null;
        }
        v.stats.setVisibility(shown == null ? View.GONE : View.VISIBLE);
        if (shown == null) return;
        for (int i = 0; i < cells.length; i++) {
            cells[i].value.setText(shown[i][0]);
            cells[i].label.setText(shown[i][1]);
        }
    }

    static String typeLabel(Context c, @Nullable String type) {
        if (type == null) return "";
        switch (type) {
            case Post.STATS: return c.getString(R.string.post_type_stats);
            case Post.WORKOUT: return c.getString(R.string.post_type_workout);
            case Post.MEAL: return c.getString(R.string.post_type_meal);
            case Post.CLIP: return c.getString(R.string.post_type_clip);
            case Post.CHALLENGE: return c.getString(R.string.post_type_challenge);
            case Post.ACHIEVEMENT: return c.getString(R.string.post_type_achievement);
            default: return c.getString(R.string.post_type_text);
        }
    }
}

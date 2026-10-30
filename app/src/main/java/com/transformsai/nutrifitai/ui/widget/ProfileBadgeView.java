package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.LifecycleOwner;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.ui.common.Avatars;

/**
 * The profile picture in every tab header, with a red badge showing unread notifications. Tapping it opens the
 * profile and settings tab.
 */
public class ProfileBadgeView extends FrameLayout {
    private final FoodThumbView avatar;
    private final TextView badge;

    public ProfileBadgeView(Context c) {
        this(c, null);
    }

    public ProfileBadgeView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        setClipChildren(false);
        avatar = new FoodThumbView(c);
        addView(avatar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        badge = new TextView(c);
        int size = (int) Dp.px(c, 22);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, size, Gravity.TOP | Gravity.END);
        lp.topMargin = (int) -Dp.px(c, 4);
        lp.setMarginEnd((int) -Dp.px(c, 4));
        badge.setMinWidth(size);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding((int) Dp.px(c, 6), 0, (int) Dp.px(c, 6), 0);
        badge.setBackgroundResource(R.drawable.bg_badge_count);
        badge.setTextColor(ContextCompat.getColor(c, android.R.color.white));
        badge.setTextSize(11);
        badge.setTypeface(ResourcesCompat.getFont(c, R.font.jakarta_bold));
        badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        badge.setVisibility(GONE);
        addView(badge, lp);
        setContentDescription(c.getString(R.string.cd_avatar));
    }

    /** Follows my profile photo and the unread notification count for as long as {@code owner} lives. */
    public void bind(LifecycleOwner owner) {
        String uid = App.graph().session.uid();
        if (uid == null) return;
        App.graph().profile.observe(uid).observe(owner, p -> Avatars.mine(avatar, p));
        App.graph().inbox.unread().observe(owner, n -> {
            int count = n == null ? 0 : n;
            badge.setVisibility(count > 0 ? VISIBLE : GONE);
            badge.setText(count > 99 ? "99+" : String.valueOf(count));
            setContentDescription(count > 0 ? getContext().getString(R.string.cd_avatar_unread, count)
                    : getContext().getString(R.string.cd_avatar));
        });
    }

    public FoodThumbView avatar() {
        return avatar;
    }

    @Override
    public void setOnClickListener(@Nullable View.OnClickListener l) {
        super.setOnClickListener(l);
        avatar.setOnClickListener(l);
    }
}

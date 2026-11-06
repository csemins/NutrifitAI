package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.social.FeedRepository;
import com.transformsai.nutrifitai.ui.widget.Dp;

import java.util.function.Consumer;

/** A floating row of reaction emoji that pop in one after another, like a messenger's reaction bar. */
public final class ReactionPicker {
    private ReactionPicker() { }

    /** Shows the bar above {@code anchor}; picking the current reaction again clears it ({@code null}). */
    public static void show(View anchor, @Nullable String current, Consumer<String> onPick) {
        show(anchor, current, FeedRepository.REACTIONS, onPick);
    }

    public static void show(View anchor, @Nullable String current, String[] keys, Consumer<String> onPick) {
        Context c = anchor.getContext();
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.bg_reaction_bar);
        int pad = (int) Dp.px(c, 6);
        bar.setPadding(pad, pad, pad, pad);
        PopupWindow w = new PopupWindow(bar, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        w.setBackgroundDrawable(new ColorDrawable(0));
        w.setElevation(Dp.px(c, 8));
        w.setOutsideTouchable(true);
        int size = (int) Dp.px(c, 48);
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            TextView t = new TextView(c);
            t.setText(FeedRepository.emoji(key));
            t.setTextSize(26);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundResource(key.equals(current) ? R.drawable.bg_reaction_selected : R.drawable.bg_reaction_item);
            t.setContentDescription(label(c, key));
            t.setOnClickListener(v -> {
                v.animate().scaleX(1.4f).scaleY(1.4f).setDuration(120).withEndAction(() -> {
                    w.dismiss();
                    onPick.accept(key.equals(current) ? null : key);
                }).start();
            });
            t.setScaleX(0f);
            t.setScaleY(0f);
            t.animate().scaleX(1f).scaleY(1f).setStartDelay(30L * i).setDuration(220).setInterpolator(new OvershootInterpolator(2.4f)).start();
            bar.addView(t, new LinearLayout.LayoutParams(size, size));
        }
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int x = Math.max((int) Dp.px(c, 8), loc[0] + anchor.getWidth() / 2 - bar.getMeasuredWidth() / 2);
        int maxX = anchor.getRootView().getWidth() - bar.getMeasuredWidth() - (int) Dp.px(c, 8);
        int y = loc[1] - bar.getMeasuredHeight() - (int) Dp.px(c, 6);
        if (y < Dp.px(c, 40)) y = loc[1] + anchor.getHeight();
        w.showAtLocation(anchor, Gravity.NO_GRAVITY, Math.min(x, Math.max(0, maxX)), y);
    }

    public static String label(Context c, @Nullable String key) {
        if (key == null) return c.getString(R.string.react_like);
        switch (key) {
            case "love": return c.getString(R.string.react_love);
            case "haha": return c.getString(R.string.react_haha);
            case "wow": return c.getString(R.string.react_wow);
            case "strong": return c.getString(R.string.react_strong);
            case "fire": return c.getString(R.string.react_fire);
            case "clap": return c.getString(R.string.react_clap);
            default: return c.getString(R.string.react_like);
        }
    }
}

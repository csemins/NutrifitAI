package com.transformsai.nutrifitai.ui.common;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.util.DateUtil;
import com.google.android.material.snackbar.Snackbar;

import java.util.Locale;

public final class Ui {
    private Ui() {
    }

    /** Click listener that ignores repeated taps within 700 ms (prevents duplicate saves). */
    public static void onClick(View v, View.OnClickListener l) {
        final long[] last = {0};
        v.setOnClickListener(x -> {
            long now = SystemClock.elapsedRealtime();
            if (now - last[0] < 700) return;
            last[0] = now;
            l.onClick(x);
        });
    }

    public static Snackbar snack(View anchor, CharSequence msg) {
        Snackbar s = Snackbar.make(anchor, msg, Snackbar.LENGTH_LONG);
        android.content.Context c = anchor.getContext();
        // inverse colours so the text is always readable (also on screens that are always dark)
        s.setBackgroundTint(androidx.core.content.ContextCompat.getColor(c, com.transformsai.nutrifitai.R.color.nl_text));
        s.setTextColor(androidx.core.content.ContextCompat.getColor(c, com.transformsai.nutrifitai.R.color.nl_bg));
        s.setActionTextColor(androidx.core.content.ContextCompat.getColor(c, com.transformsai.nutrifitai.R.color.snack_action));
        // never hide the message behind a floating nav bar or a pinned bottom bar
        View root = anchor.getRootView();
        for (int id : new int[]{com.transformsai.nutrifitai.R.id.nav, com.transformsai.nutrifitai.R.id.actions,
                com.transformsai.nutrifitai.R.id.startBar, com.transformsai.nutrifitai.R.id.inputBar, com.transformsai.nutrifitai.R.id.panel}) {
            View bar = root.findViewById(id);
            if (bar != null && bar.isShown()) {
                s.setAnchorView(bar);
                break;
            }
        }
        s.show();
        return s;
    }

    public static void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    public static void hideKeyboard(Activity a) {
        View f = a.getCurrentFocus();
        if (f == null) return;
        InputMethodManager imm = a.getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    /** First character of a name for an avatar, keeping emoji and other surrogate pairs whole. */
    public static String initial(@Nullable String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "?";
        return new String(Character.toChars(n.codePointAt(0))).toUpperCase(java.util.Locale.getDefault());
    }

    public static String kcal(double v) {
        return String.format(Locale.getDefault(), "%,d", Math.round(v));
    }

    public static String g(double v) {
        return Math.round(v) + "g";
    }

    /** "14P  52C  9F  8 fibre" with nutrient colours. */
    public static CharSequence macroChips(Context c, Nutrients n) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        append(b, Math.round(n.protein) + "P", ContextCompat.getColor(c, R.color.nl_protein));
        b.append("   ");
        append(b, Math.round(n.carbs) + "C", ContextCompat.getColor(c, R.color.nl_carbs));
        b.append("   ");
        append(b, Math.round(n.fat) + "F", ContextCompat.getColor(c, R.color.nl_fat));
        b.append("   ");
        append(b, (n.fibre == null ? "– " : Math.round(n.fibre) + " ") + c.getString(R.string.fibre).toLowerCase(Locale.getDefault()),
                ContextCompat.getColor(c, R.color.nl_fibre));
        return b;
    }

    private static void append(SpannableStringBuilder b, String s, int color) {
        int start = b.length();
        b.append(s);
        b.setSpan(new ForegroundColorSpan(color), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public static String provenance(Context c, @Nullable String p) {
        if (p == null) return c.getString(R.string.prov_curated);
        switch (p) {
            case FoodEntity.PROV_USER: return c.getString(R.string.prov_user);
            case FoodEntity.PROV_AI: return c.getString(R.string.prov_ai);
            case FoodEntity.PROV_RECIPE: return c.getString(R.string.prov_recipe);
            case FoodEntity.PROV_IMPORTED: return c.getString(R.string.prov_imported);
            default: return c.getString(R.string.prov_curated);
        }
    }

    public static String ago(Context c, long epoch) {
        long mins = (DateUtil.nowMillis() - epoch) / 60000;
        if (mins < 1) return c.getString(R.string.just_now);
        if (mins < 60) return c.getString(R.string.minutes_ago, (int) mins);
        if (mins < 48 * 60) return c.getString(R.string.hours_ago, (int) (mins / 60));
        return DateUtil.monthDay(DateUtil.dateOf(epoch, DateUtil.zone()));
    }

    public static void openAppSettings(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.getPackageName(), null));
        c.startActivity(i);
    }

    public static void openNotificationSettings(Context c) {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        c.startActivity(i);
    }

    public static double parse(@Nullable CharSequence s) {
        if (s == null) return Double.NaN;
        try {
            return Double.parseDouble(s.toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}

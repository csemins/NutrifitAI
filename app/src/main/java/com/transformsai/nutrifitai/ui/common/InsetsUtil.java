package com.transformsai.nutrifitai.ui.common;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Applies system-bar, cutout and keyboard insets as extra padding/margin (keeping XML values). */
public final class InsetsUtil {
    private InsetsUtil() {
    }

    public static void padding(View v, boolean top, boolean bottom) {
        final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, wi) -> {
            Insets bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = wi.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(l + bars.left, t + (top ? bars.top : 0), r + bars.right,
                    b + (bottom ? Math.max(bars.bottom, ime.bottom) : 0));
            return wi;
        });
        ViewCompat.requestApplyInsets(v);
    }

    public static void bottomMargin(View v) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        final int base = lp.bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, wi) -> {
            Insets bars = wi.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = wi.getInsets(WindowInsetsCompat.Type.ime());
            ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            p.bottomMargin = base + Math.max(bars.bottom, ime.bottom);
            view.setLayoutParams(p);
            return wi;
        });
        ViewCompat.requestApplyInsets(v);
    }

    public static void topMargin(View v) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        final int base = lp.topMargin;
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, wi) -> {
            Insets bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            p.topMargin = base + bars.top;
            view.setLayoutParams(p);
            return wi;
        });
        ViewCompat.requestApplyInsets(v);
    }
}

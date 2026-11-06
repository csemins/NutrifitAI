package com.transformsai.nutrifitai.ui.train;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.fitness.Muscles;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/** "Quads ███████░░ 82%" rows under a muscle map: the number is always shown, not only the colour. */
public final class MuscleRows {
    private MuscleRows() { }

    public static View row(Context c, String muscle, float intensity) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight((int) (30 * d));
        TextView name = new TextView(c, null, 0, R.style.NL_Text_Body);
        name.setText(Muscles.label(muscle));
        name.setLayoutParams(new LinearLayout.LayoutParams((int) (104 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearProgressIndicator bar = new LinearProgressIndicator(c);
        bar.setIndicatorColor(ContextCompat.getColor(c, intensity >= 0.6f ? R.color.nl_lime : R.color.nl_lime_mid));
        bar.setTrackColor(ContextCompat.getColor(c, R.color.nl_surface_3));
        bar.setTrackThickness((int) (6 * d));
        bar.setTrackCornerRadius((int) (3 * d));
        bar.setTrackStopIndicatorSize(0);
        bar.setIndicatorTrackGapSize(0);
        bar.setMax(100);
        bar.setProgressCompat(Math.round(intensity * 100), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginEnd((int) (10 * d));
        bar.setLayoutParams(lp);
        TextView pct = new TextView(c, null, 0, R.style.NL_Text_BodyStrong);
        pct.setText(c.getString(R.string.percent, Math.round(intensity * 100)));
        pct.setMinWidth((int) (40 * d));
        pct.setGravity(Gravity.END);
        l.addView(name);
        l.addView(bar);
        l.addView(pct);
        l.setContentDescription(Muscles.label(muscle) + " " + Math.round(intensity * 100) + "%");
        l.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return l;
    }
}

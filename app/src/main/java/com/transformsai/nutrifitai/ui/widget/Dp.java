package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.util.TypedValue;

public final class Dp {
    private Dp() {
    }

    public static float px(Context c, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics());
    }

    public static float sp(Context c, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, c.getResources().getDisplayMetrics());
    }
}

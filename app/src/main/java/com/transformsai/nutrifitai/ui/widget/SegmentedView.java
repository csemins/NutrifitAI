package com.transformsai.nutrifitai.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.transformsai.nutrifitai.R;

/** Pill segmented selector (Yesterday / Today / Tomorrow, Week / Month / Year). */
public class SegmentedView extends LinearLayout {
    public interface OnSelect {
        void onSelect(int index);
    }

    private int selected = 0;
    @Nullable
    private OnSelect listener;

    public SegmentedView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        setOrientation(HORIZONTAL);
        setBackgroundResource(R.drawable.bg_segment_track);
        int p = (int) Dp.px(c, 4);
        setPadding(p, p, p, p);
        TypedArray t = c.obtainStyledAttributes(a, R.styleable.SegmentedView);
        int arr = t.getResourceId(R.styleable.SegmentedView_segments, 0);
        t.recycle();
        if (arr != 0) setSegments(c.getResources().getStringArray(arr));
    }

    public void setSegments(String[] labels) {
        removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(getContext());
            tv.setText(labels[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(15);
            tv.setTypeface(ResourcesCompat.getFont(getContext(), R.font.jakarta_semibold));
            tv.setTextColor(ContextCompat.getColorStateList(getContext(), R.color.segment_text));
            tv.setBackgroundResource(R.drawable.bg_segment_selected);
            tv.setMaxLines(1);
            tv.setMinHeight((int) Dp.px(getContext(), 44));
            int pad = (int) Dp.px(getContext(), 6);
            tv.setPadding(pad, 0, pad, 0);
            final int idx = i;
            tv.setOnClickListener(v -> select(idx, true));
            ViewCompat.setAccessibilityDelegate(tv, new androidx.core.view.AccessibilityDelegateCompat() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.RadioButton");
                    info.setCheckable(true);
                    info.setChecked(host.isSelected());
                }
            });
            addView(tv, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        select(Math.min(selected, labels.length - 1), false);
    }

    public void setOnSelectListener(@Nullable OnSelect l) {
        listener = l;
    }

    public int getSelected() {
        return selected;
    }

    public void select(int index, boolean notify) {
        selected = index;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setSelected(i == index);
        if (notify && listener != null) listener.onSelect(index);
    }

    /**
     * Long labels ("Prefer not to say", "Challenges") shrink so every segment fits, all to the same size so the row
     * stays even. Text stays vertically centred because only the size changes, not the view height.
     */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        float maxSp = 15f, minSp = 10f;
        float best = maxSp;
        android.text.TextPaint paint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < getChildCount(); i++) {
            TextView tv = (TextView) getChildAt(i);
            int avail = tv.getMeasuredWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
            if (avail <= 0) continue;
            paint.setTypeface(tv.getTypeface());
            float sp = maxSp;
            while (sp > minSp) {
                paint.setTextSize(Dp.sp(getContext(), sp));
                if (paint.measureText(tv.getText().toString()) <= avail) break;
                sp -= 0.5f;
            }
            best = Math.min(best, sp);
        }
        boolean changed = false;
        for (int i = 0; i < getChildCount(); i++) {
            TextView tv = (TextView) getChildAt(i);
            if (Math.abs(tv.getTextSize() - Dp.sp(getContext(), best)) > 0.5f) {
                tv.setTextSize(best);
                changed = true;
            }
        }
        if (changed) super.onMeasure(widthSpec, heightSpec);
    }
}

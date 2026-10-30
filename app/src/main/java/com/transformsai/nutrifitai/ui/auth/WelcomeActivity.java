package com.transformsai.nutrifitai.ui.auth;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityWelcomeBinding;
import com.transformsai.nutrifitai.databinding.ItemWelcomeSlideBinding;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;

/** First screen when signed out: three slides that introduce the app, then sign up or sign in. */
public class WelcomeActivity extends BaseActivity {
    private static final int[][] SLIDES = {
            {R.drawable.hero_auth, R.string.welcome_1_title, R.string.welcome_1_body},
            {R.drawable.ill_workouts, R.string.welcome_2_title, R.string.welcome_2_body},
            {R.drawable.hero_ob_targets, R.string.welcome_3_title, R.string.welcome_3_body},
            {R.drawable.hero_social, R.string.welcome_4_title, R.string.welcome_4_body},
    };

    private ActivityWelcomeBinding b;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityWelcomeBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        b.pager.setAdapter(new SlideAdapter());
        buildDots();
        b.pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                selectDot(position);
            }
        });
        Ui.onClick(b.start, v -> startActivity(AuthActivity.forMode(this, AuthActivity.MODE_SIGN_UP)));
        Ui.onClick(b.haveAccount, v -> startActivity(AuthActivity.forMode(this, AuthActivity.MODE_SIGN_IN)));
        b.skip.setOnClickListener(v -> startActivity(AuthActivity.forMode(this, AuthActivity.MODE_SIGN_IN)));
    }

    private void buildDots() {
        int size = getResources().getDimensionPixelSize(R.dimen.welcome_dot);
        for (int i = 0; i < SLIDES.length; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(i == 0 ? 0 : size);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_page_dot);
            b.dots.addView(dot);
        }
        selectDot(b.pager.getCurrentItem());
    }

    /** The current page's dot widens into a lime pill. */
    private void selectDot(int position) {
        int size = getResources().getDimensionPixelSize(R.dimen.welcome_dot);
        for (int i = 0; i < b.dots.getChildCount(); i++) {
            View dot = b.dots.getChildAt(i);
            dot.setSelected(i == position);
            dot.getLayoutParams().width = i == position ? size * 3 : size;
            dot.requestLayout();
        }
        b.dots.setContentDescription(getString(R.string.welcome_page, position + 1, SLIDES.length));
    }

    private static final class SlideAdapter extends RecyclerView.Adapter<SlideAdapter.Holder> {
        static final class Holder extends RecyclerView.ViewHolder {
            final ItemWelcomeSlideBinding v;

            Holder(ItemWelcomeSlideBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(ItemWelcomeSlideBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int i) {
            h.v.art.setImageResource(SLIDES[i][0]);
            h.v.title.setText(SLIDES[i][1]);
            h.v.body.setText(SLIDES[i][2]);
        }

        @Override
        public int getItemCount() {
            return SLIDES.length;
        }
    }
}

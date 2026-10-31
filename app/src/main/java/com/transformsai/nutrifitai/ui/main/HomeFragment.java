package com.transformsai.nutrifitai.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.repo.WaterRepository;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.model.DayTotals;
import com.transformsai.nutrifitai.data.db.model.ItemRow;
import com.transformsai.nutrifitai.databinding.FragmentHomeBinding;
import com.transformsai.nutrifitai.databinding.ItemHomeMealBinding;
import com.transformsai.nutrifitai.databinding.ViewMacroTileBinding;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.domain.NutritionMath;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.analysis.VoiceLogActivity;
import com.transformsai.nutrifitai.ui.assistant.AssistantActivity;
import com.transformsai.nutrifitai.ui.camera.CameraActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.food.FoodDetailActivity;
import com.transformsai.nutrifitai.ui.food.FoodSearchActivity;
import com.transformsai.nutrifitai.ui.hydration.HydrationActivity;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Units;
import com.bumptech.glide.Glide;

import java.io.File;
import java.util.List;

public class HomeFragment extends Fragment implements MainActivity.ScrollToTop {
    private FragmentHomeBinding b;
    private HomeViewModel vm;
    private String units = "metric";
    private boolean firstRing = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentHomeBinding.inflate(inf, c, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        InsetsUtil.padding(b.content, true, false);
        vm = new ViewModelProvider(this).get(HomeViewModel.class);
        setupStatic();
        vm.profile.observe(getViewLifecycleOwner(), this::renderProfile);
        vm.today.observe(getViewLifecycleOwner(), d -> {
            b.date.setText(DateUtil.headerDate(DateUtil.parse(d)).toUpperCase(java.util.Locale.getDefault()));
            renderGreeting(vm.profile.getValue());
        });
        vm.goal.observe(getViewLifecycleOwner(), g -> render());
        vm.totals.observe(getViewLifecycleOwner(), t -> render());
        vm.water.observe(getViewLifecycleOwner(), w -> renderWater());
        vm.items.observe(getViewLifecycleOwner(), this::renderMeals);
        vm.training.observe(getViewLifecycleOwner(), this::renderTraining);
        vm.library.observe(getViewLifecycleOwner(), lib -> b.trainAnim.setExercise(lib.byId("jumping_jack"), true));
        vm.steps.observe(getViewLifecycleOwner(), st -> {
            int n = st == null ? 0 : st.steps;
            int goal = App.graph().prefs.stepGoal();
            java.text.NumberFormat nf = java.text.NumberFormat.getIntegerInstance();
            b.stepsNow.setText(nf.format(n));
            b.stepsGoal.setText(getString(R.string.steps_of_goal, nf.format(goal)));
            b.stepsProgress.setProgressCompat(goal <= 0 ? 0 : Math.min(100, n * 100 / goal), true);
            b.stepsCard.setContentDescription(getString(R.string.cd_steps_ring, n, goal));
        });
        vm.fast.observe(getViewLifecycleOwner(), f -> {
            fast = f;
            renderFast();
        });
        vm.streak.observe(getViewLifecycleOwner(), n -> {
            b.streak.setVisibility(n != null && n > 0 ? View.VISIBLE : View.GONE);
            if (n != null && n > 0) b.streak.setText(getResources().getQuantityString(R.plurals.streak_days, n, n));
        });
    }

    private void setupStatic() {
        b.statEaten.label.setText(R.string.eaten);
        b.statTarget.label.setText(R.string.daily_target);
        b.statFibre.label.setText(R.string.fibre);
        b.statFibre.divider.setVisibility(View.GONE);
        tile(b.macroP, R.string.protein, R.color.nl_protein);
        tile(b.macroC, R.string.carbs, R.color.nl_carbs);
        tile(b.macroF, R.string.fat, R.color.nl_fat);
        action(b.actScan, R.drawable.ic3d_scan, R.string.scan_food, () -> start(CameraActivity.class));
        action(b.actSearch, R.drawable.ic3d_search, R.string.search, () -> start(FoodSearchActivity.class));
        action(b.actVoice, R.drawable.ic3d_voice, R.string.voice_log, () -> start(VoiceLogActivity.class));
        action(b.actBarcode, R.drawable.ic3d_barcode, R.string.cam_mode_barcode, () -> startActivity(
                new Intent(requireContext(), CameraActivity.class).putExtra(CameraActivity.EXTRA_MODE, 2)));
        action(b.actQuick, R.drawable.ic3d_quick, R.string.quick_add, () -> QuickAdd.show(this));
        action(b.actAsk, R.drawable.ic3d_coach, R.string.coach_short, () -> start(AssistantActivity.class));
        Ui.onClick(b.challengeCard, x -> {
            com.transformsai.nutrifitai.ui.social.SocialFragment.showSection(com.transformsai.nutrifitai.ui.social.SocialFragment.SECTION_CHALLENGES);
            ((MainActivity) requireActivity()).select(MainActivity.TAB_SOCIAL);
        });
        Ui.onClick(b.stepsCard, x -> start(com.transformsai.nutrifitai.ui.steps.StepsActivity.class));
        Ui.onClick(b.fastCard, x -> start(com.transformsai.nutrifitai.ui.fasting.FastingActivity.class));
        b.streak.setOnClickListener(x -> start(com.transformsai.nutrifitai.ui.achievements.AchievementsActivity.class));
        b.legendP.setTextColor(ContextCompat.getColor(requireContext(), R.color.nl_text));
        Ui.onClick(b.addWater, x -> {
            vm.addWater(WaterRepository.GLASS_ML);
            Ui.haptic(x);
            Ui.snack(b.getRoot(), getString(R.string.water_added, Units.volumeSmall(WaterRepository.GLASS_ML, units)));
        });
        b.waterCard.setOnClickListener(x -> start(HydrationActivity.class));
        b.viewDetails.setOnClickListener(x -> ((MainActivity) requireActivity()).select(MainActivity.TAB_PROGRESS));
        b.seeAll.setOnClickListener(x -> ((MainActivity) requireActivity()).select(MainActivity.TAB_MEALS));
        b.avatar.bind(getViewLifecycleOwner());
        b.avatar.setOnClickListener(x -> ((MainActivity) requireActivity()).select(MainActivity.TAB_PROFILE));
        Ui.onClick(b.trainCard, x -> ((MainActivity) requireActivity()).select(MainActivity.TAB_TRAIN));
        Ui.onClick(b.trainGo, x -> ((MainActivity) requireActivity()).select(MainActivity.TAB_TRAIN));
    }

    private void tile(ViewMacroTileBinding t, int name, int color) {
        t.name.setText(getString(name));
        t.name.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(color), null, null, null);
        t.bar.setColor(ContextCompat.getColor(requireContext(), color));
    }

    private android.graphics.drawable.Drawable dot(int color) {
        android.graphics.drawable.Drawable d = ContextCompat.getDrawable(requireContext(), R.drawable.bg_dot).mutate();
        d.setTint(ContextCompat.getColor(requireContext(), color));
        return d;
    }

    private void action(com.transformsai.nutrifitai.databinding.ViewActionTileBinding t, int icon, int label, Runnable r) {
        t.icon.setImageResource(icon);
        t.label.setText(label);
        t.getRoot().setContentDescription(getString(label));
        Ui.onClick(t.getRoot(), x -> r.run());
    }

    @Nullable private com.transformsai.nutrifitai.data.db.entity.FastEntity fast;
    private final android.os.Handler fastTick = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable fastRunnable = new Runnable() {
        @Override
        public void run() {
            renderFast();
            fastTick.postDelayed(this, 30_000);
        }
    };

    private void renderFast() {
        if (b == null) return;
        if (fast == null) {
            b.fastNow.setText(R.string.fasting_off);
            b.fastSub.setText(R.string.fasting_tap_start);
            return;
        }
        long ms = System.currentTimeMillis() - fast.startAt;
        long m = Math.max(0, ms) / 60000;
        b.fastNow.setText(String.format(java.util.Locale.getDefault(), "%d:%02d", m / 60, m % 60));
        b.fastSub.setText(getString(R.string.fasting_of, fast.targetHours));
    }

    @Override
    public void onResume() {
        super.onResume();
        fastTick.post(fastRunnable);
        vm.refreshStreak();
    }

    @Override
    public void onPause() {
        fastTick.removeCallbacks(fastRunnable);
        super.onPause();
    }

    private void start(Class<?> c) {
        startActivity(new Intent(requireContext(), c));
    }

    private void renderProfile(@Nullable ProfileEntity p) {
        if (p == null) return;
        units = p.units;
        renderGreeting(p);
        renderWater();
    }

    private void renderGreeting(@Nullable ProfileEntity p) {
        String name = p == null || p.name == null || p.name.isEmpty() ? getString(R.string.greet_fallback) : p.name.split(" ")[0];
        String k = DateUtil.greetingKey();
        int res = "morning".equals(k) ? R.string.greet_morning : "afternoon".equals(k) ? R.string.greet_afternoon : R.string.greet_evening;
        b.greeting.setText(getString(res, name));
    }

    private void render() {
        GoalEntity g = vm.goal.getValue();
        DayTotals t = vm.totals.getValue();
        double eaten = t == null ? 0 : t.kcal;
        b.statEaten.value.setText(Ui.kcal(eaten));
        b.statFibre.value.setText(t == null ? "0 g" : Math.round(t.fibre) + " g");
        if (g == null) {
            b.statTarget.value.setText("—");
            b.left.setText(Ui.kcal(eaten));
            b.leftLabel.setText(R.string.eaten);
            b.ring.setProgress(0, false);
            b.ringBox.setContentDescription(getString(R.string.no_goal));
        } else {
            double left = g.kcal - eaten;
            b.statTarget.value.setText(Ui.kcal(g.kcal));
            b.left.setText(Ui.kcal(Math.abs(left)));
            b.leftLabel.setText(left >= 0 ? R.string.kcal_left : R.string.kcal_over);
            b.leftLabel.setTextColor(ContextCompat.getColor(requireContext(), left >= 0 ? R.color.nl_text_2 : R.color.nl_carbs));
            b.ring.setProgress((float) (eaten / g.kcal), true);
            b.ringBox.setContentDescription(getString(R.string.cd_ring, Math.round(eaten), g.kcal,
                    left >= 0 ? Math.round(left) + " left" : Math.round(-left) + " over target"));
        }
        macro(b.macroP, t == null ? 0 : t.protein, g == null ? 0 : g.proteinG, getString(R.string.protein));
        macro(b.macroC, t == null ? 0 : t.carbs, g == null ? 0 : g.carbsG, getString(R.string.carbs));
        macro(b.macroF, t == null ? 0 : t.fat, g == null ? 0 : g.fatG, getString(R.string.fat));
        int[] split = t == null ? new int[]{0, 0, 0} : NutritionMath.macroEnergySplit(t.protein, t.carbs, t.fat);
        b.donut.setSplit(split[0], split[1], split[2]);
        b.donut.setContentDescription(getString(R.string.macro_split_cd, split[0], split[1], split[2]));
        legend(b.legendP, R.string.protein, split[0], R.color.nl_protein);
        legend(b.legendC, R.string.carbs, split[1], R.color.nl_carbs);
        legend(b.legendF, R.string.fat, split[2], R.color.nl_fat);
        renderWater();
    }

    private void macro(ViewMacroTileBinding t, double value, int target, String name) {
        t.value.setText(String.valueOf(Math.round(value)));
        t.target.setText(target > 0 ? "/ " + target + "g" : "g");
        t.bar.setFraction(target > 0 ? (float) (value / target) : 0);
        t.getRoot().setContentDescription(name + " " + Math.round(value) + (target > 0 ? " of " + target : "") + " grams");
    }

    private void legend(android.widget.TextView tv, int name, int pct, int color) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(getString(name)).append("  ");
        int s = sb.length();
        sb.append(pct + "%");
        sb.setSpan(new android.text.style.ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.nl_text_3)), s, sb.length(), 0);
        tv.setText(sb);
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(color), null, null, null);
    }

    private void renderWater() {
        if (b == null) return;
        Integer ml = vm.water.getValue();
        int drank = ml == null ? 0 : ml;
        GoalEntity g = vm.goal.getValue();
        int goal = g == null ? 0 : g.waterMl;
        b.waterNow.setText(Units.volumeNumber(drank, units));
        b.waterGoal.setText(goal > 0 ? "/ " + Units.volumeNumber(goal, units) + " " + Units.volumeUnit(units) : Units.volumeUnit(units));
        b.waterBars.setFraction(goal > 0 ? (float) drank / goal : 0);
        b.waterCard.setContentDescription(getString(R.string.cd_gauge, Units.volumeSmall(drank, units), Units.volumeSmall(goal, units)));
        b.addWater.setText(Units.imperial(units) ? "+ " + Units.volumeSmall(WaterRepository.GLASS_ML, units) : getString(R.string.add_glass));
    }

    private void renderMeals(List<ItemRow> rows) {
        b.mealList.removeAllViews();
        int shown = 0;
        for (ItemRow r : rows) {
            if (!MealEntryEntity.STATUS_CONSUMED.equals(r.status)) continue;
            if (shown++ >= 4) break;
            ItemHomeMealBinding ib = ItemHomeMealBinding.inflate(getLayoutInflater(), b.mealList, false);
            ib.name.setText(r.item.name);
            Nutrients n = NutritionMath.forItem(r.item);
            ib.sub.setText(MealLabels.label(requireContext(), r.mealType) + " · " + Units.grams(r.item.grams()));
            ib.kcal.setText(Ui.kcal(n.kcal));
            if (r.imageLocalPath != null && new File(r.imageLocalPath).exists()) {
                Glide.with(this).load(new File(r.imageLocalPath)).centerCrop().into(ib.thumb);
            } else ib.thumb.setFood(r.item.foodId, r.item.name, r.item.emoji, null);
            ib.getRoot().setOnClickListener(x -> startActivity(FoodDetailActivity.forItem(requireContext(), r.item.id)));
            b.mealList.addView(ib.getRoot());
        }
        b.mealsEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private void renderTraining(@Nullable List<com.transformsai.nutrifitai.data.db.model.DayWorkout> days) {
        long ms = 0;
        if (days != null) for (com.transformsai.nutrifitai.data.db.model.DayWorkout d : days) ms += d.activeMs;
        int min = (int) Math.round(ms / 60000.0), goal = com.transformsai.nutrifitai.ui.train.TrainViewModel.DAILY_GOAL_MIN;
        b.trainSub.setText(getString(R.string.home_training_sub, min, goal));
        b.trainProgress.setProgressCompat(Math.min(100, min * 100 / goal), true);
        b.trainCard.setContentDescription(getString(R.string.home_training) + ", " + b.trainSub.getText());
    }

    @Override
    public void scrollToTop() {
        if (b != null) b.scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}

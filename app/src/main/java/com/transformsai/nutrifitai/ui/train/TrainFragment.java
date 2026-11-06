package com.transformsai.nutrifitai.ui.train;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutPlanEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.data.db.model.DayWorkout;
import com.transformsai.nutrifitai.data.repo.WorkoutRepository;
import com.transformsai.nutrifitai.databinding.FragmentTrainBinding;
import com.transformsai.nutrifitai.databinding.ItemProgramBinding;
import com.transformsai.nutrifitai.databinding.ItemWorkoutRowBinding;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.Programs;
import com.transformsai.nutrifitai.fitness.Muscles;
import com.transformsai.nutrifitai.fitness.WorkoutEngine;
import com.transformsai.nutrifitai.fitness.WorkoutMath;
import com.transformsai.nutrifitai.fitness.WorkoutPlan;
import com.transformsai.nutrifitai.reminders.Notifications;
import com.transformsai.nutrifitai.reminders.ReminderScheduler;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.main.MainActivity;
import com.transformsai.nutrifitai.ui.widget.WeekBarsView;
import com.transformsai.nutrifitai.util.DateUtil;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.io.File;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Train tab: schedule, today's program, consistency, programs, AI plans, library and history. */
public class TrainFragment extends Fragment implements MainActivity.ScrollToTop {
    private FragmentTrainBinding b;
    private TrainViewModel vm;
    private WorkoutPlan today;
    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            renderCountdown();
            tick.postDelayed(this, 1000);
        }
    };
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentTrainBinding.inflate(inf, c, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        InsetsUtil.padding(b.content, true, false);
        vm = new ViewModelProvider(this).get(TrainViewModel.class);
        b.programs.setAdapter(new ProgramAdapter());
        b.avatar.bind(getViewLifecycleOwner());
        b.avatar.setOnClickListener(x -> ((MainActivity) requireActivity()).select(MainActivity.TAB_PROFILE));
        Ui.onClick(b.level, x -> chooseLevel());
        Ui.onClick(b.editTime, x -> pickTime());
        Ui.onClick(b.todayCard, x -> openToday());
        Ui.onClick(b.start, x -> openToday());
        Ui.onClick(b.library, x -> startActivity(new Intent(requireContext(), ExerciseLibraryActivity.class)));
        Ui.onClick(b.aiBuilder, x -> startActivity(new Intent(requireContext(), AiWorkoutActivity.class)));
        vm.profile.observe(getViewLifecycleOwner(), this::renderProfile);
        vm.library.observe(getViewLifecycleOwner(), lib -> {
            renderToday();
            b.librarySub.setText(getString(R.string.exercise_library_sub_v3, lib.guided().size()));
            RecyclerView.Adapter<?> a = b.programs.getAdapter();
            if (a != null) a.notifyDataSetChanged();
            renderPlans(vm.plans.getValue());
            renderRecent(vm.recent.getValue());
            renderWeekMuscles(vm.weekSessions.getValue());
        });
        vm.weekSessions.observe(getViewLifecycleOwner(), this::renderWeekMuscles);
        vm.week.observe(getViewLifecycleOwner(), this::renderWeek);
        vm.recent.observe(getViewLifecycleOwner(), this::renderRecent);
        vm.plans.observe(getViewLifecycleOwner(), this::renderPlans);
        renderLevel();
        renderSchedule();
    }

    @Override
    public void onResume() {
        super.onResume();
        tick.post(countdown);
    }

    @Override
    public void onPause() {
        tick.removeCallbacks(countdown);
        super.onPause();
    }

    // ---------------------------------------------------------------- header
    private void renderProfile(@Nullable ProfileEntity p) {
        if (p == null) return;
        String first = p.name == null || p.name.isEmpty() ? getString(R.string.greet_fallback) : p.name.split(" ")[0];
        b.name.setText(getString(R.string.train_greeting, first));
        renderToday();
    }

    private String levelLabel(String l) {
        switch (l) {
            case "intermediate": return getString(R.string.level_intermediate);
            case "advanced": return getString(R.string.level_advanced);
            default: return getString(R.string.level_beginner);
        }
    }

    private void renderLevel() {
        b.level.setText(levelLabel(App.graph().prefs.fitnessLevel()));
        b.level.setContentDescription(getString(R.string.train_level, levelLabel(App.graph().prefs.fitnessLevel())));
    }

    private void chooseLevel() {
        String[] keys = {"beginner", "intermediate", "advanced"};
        String[] labels = {getString(R.string.level_beginner), getString(R.string.level_intermediate), getString(R.string.level_advanced)};
        int sel = java.util.Arrays.asList(keys).indexOf(App.graph().prefs.fitnessLevel());
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.choose_level)
                .setSingleChoiceItems(labels, Math.max(0, sel), (d, w) -> {
                    App.graph().prefs.setFitnessLevel(keys[w]);
                    renderLevel();
                    renderToday();
                    d.dismiss();
                }).show();
    }

    // ---------------------------------------------------------------- schedule
    private void renderSchedule() {
        int m = App.graph().prefs.trainingMinute();
        b.gauge.setMinuteOfDay(m);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, m / 60);
        c.set(Calendar.MINUTE, m % 60);
        b.time.setText(DateFormat.getTimeFormat(requireContext()).format(c.getTime()));
        renderCountdown();
    }

    private void renderCountdown() {
        if (b == null) return;
        List<DayWorkout> w = vm.week.getValue();
        String today = DateUtil.today();
        boolean done = false;
        if (w != null) for (DayWorkout d : w) if (today.equals(d.date) && d.sessions > 0) done = true;
        if (done) {
            b.countdown.setText(R.string.schedule_done_today);
            b.countdown.setTextColor(ContextCompat.getColor(requireContext(), R.color.nl_lime));
            return;
        }
        b.countdown.setTextColor(ContextCompat.getColor(requireContext(), R.color.nl_text_2));
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = ReminderScheduler.nextTraining(App.graph().prefs.trainingMinute(), now);
        long s = java.time.Duration.between(now, next).getSeconds();
        b.countdown.setText(getString(R.string.schedule_in, String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)));
    }

    private void pickTime() {
        int m = App.graph().prefs.trainingMinute();
        MaterialTimePicker p = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(requireContext()) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(m / 60).setMinute(m % 60).setTitleText(R.string.training_schedule).build();
        p.addOnPositiveButtonClickListener(x -> {
            App.graph().prefs.setTrainingMinute(p.getHour() * 60 + p.getMinute());
            ReminderScheduler.scheduleTraining(requireContext(), ZonedDateTime.now());
            renderSchedule();
        });
        p.show(getChildFragmentManager(), "time");
    }

    // ---------------------------------------------------------------- today
    private void renderToday() {
        ExerciseLibrary lib = vm.library.getValue();
        if (lib == null || b == null) return;
        today = Programs.forDay(LocalDate.now().getDayOfYear(), App.graph().prefs.fitnessLevel());
        int secs = today.estimatedSeconds(lib);
        b.todayTitle.setText(today.title);
        b.chipMin.setText(getString(R.string.chip_minutes, Math.max(1, Math.round(secs / 60f))));
        b.chipCount.setText(getString(R.string.chip_exercises, today.steps.size()));
        b.todayKcal.setText(getString(R.string.kcal_est, (int) Math.round(estimateKcal(today, lib))));
        List<String> names = new ArrayList<>();
        for (WorkoutPlan.Step st : today.steps) {
            Exercise x = lib.byId(st.exerciseId);
            if (x != null && !names.contains(x.name)) names.add(x.name);
        }
        b.todayMoves.setText(String.join("  ·  ", names));
        b.hero.setIntensities(Muscles.planned(today, lib));
    }

    /** Heat map of everything trained in the last 7 days. */
    private void renderWeekMuscles(@Nullable List<WorkoutSessionEntity> list) {
        ExerciseLibrary lib = vm.library.getValue();
        if (b == null || lib == null) return;
        List<WorkoutEngine.StepResult> all = new ArrayList<>();
        if (list != null) for (WorkoutSessionEntity s : list) all.addAll(WorkoutMath.parseSets(s.setsJson));
        java.util.Map<String, Float> m = Muscles.intensity(all, lib);
        b.weekMuscles.setIntensities(m);
        b.weekMusclesSub.setText(m.isEmpty() ? getString(R.string.muscles_week_empty)
                : getString(R.string.muscles_mostly, Muscles.summary(m, 3)));
    }

    private double estimateKcal(WorkoutPlan p, ExerciseLibrary lib) {
        double k = 0;
        for (WorkoutPlan.Step s : p.steps) {
            Exercise x = lib.byId(s.exerciseId);
            if (x == null) continue;
            long ms = (x.isHold() ? s.target : s.target * 3L) * 1000L;
            k += WorkoutMath.kcal(x.met, vm.bodyKg(), ms);
        }
        return k;
    }

    private void openToday() {
        if (today != null) startActivity(WorkoutDetailActivity.intent(requireContext(), today.id));
    }

    // ---------------------------------------------------------------- week
    private void renderWeek(@Nullable List<DayWorkout> list) {
        if (b == null) return;
        String todayIso = DateUtil.today();
        List<WeekBarsView.Day> days = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            String d = DateUtil.shift(todayIso, -i);
            WeekBarsView.Day day = new WeekBarsView.Day();
            day.label = DateUtil.parse(d).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()).toUpperCase(Locale.getDefault());
            day.today = i == 0;
            if (list != null) for (DayWorkout w : list) {
                if (d.equals(w.date) && w.sessions > 0) day.value = (float) (w.activeMs / 60000.0 / TrainViewModel.DAILY_GOAL_MIN);
            }
            days.add(day);
        }
        b.week.setDays(days);
        b.goalText.setText(getString(R.string.consistency_goal, TrainViewModel.DAILY_GOAL_MIN));
        renderCountdown();
    }

    // ---------------------------------------------------------------- plans and history
    private void renderPlans(@Nullable List<WorkoutPlanEntity> plans) {
        ExerciseLibrary lib = vm.library.getValue();
        if (b == null || lib == null) return;
        b.aiPlans.removeAllViews();
        b.aiPlansTitle.setVisibility(plans == null || plans.isEmpty() ? View.GONE : View.VISIBLE);
        if (plans == null) return;
        for (WorkoutPlanEntity e : plans) {
            WorkoutPlan p = WorkoutRepository.toPlan(e, lib);
            if (p.steps.isEmpty()) continue;
            ItemWorkoutRowBinding rb = ItemWorkoutRowBinding.inflate(getLayoutInflater(), b.aiPlans, false);
            rb.title.setText(p.title);
            rb.sub.setText(getString(R.string.chip_minutes, Math.max(1, Math.round(p.estimatedSeconds(lib) / 60f)))
                    + " · " + getString(R.string.chip_exercises, p.steps.size()));
            rb.image.setExercise(lib.byId(p.steps.get(0).exerciseId), false);
            rb.badge.setVisibility(View.VISIBLE);
            rb.badge.setText(R.string.ai_workout);
            rb.getRoot().setOnClickListener(x -> startActivity(WorkoutDetailActivity.intent(requireContext(), e.id)));
            b.aiPlans.addView(rb.getRoot());
        }
    }

    private void renderRecent(@Nullable List<WorkoutSessionEntity> list) {
        ExerciseLibrary lib = vm.library.getValue();
        if (b == null) return;
        b.recent.removeAllViews();
        b.recentEmpty.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
        if (list == null) return;
        for (WorkoutSessionEntity s : list) {
            ItemWorkoutRowBinding rb = ItemWorkoutRowBinding.inflate(getLayoutInflater(), b.recent, false);
            rb.title.setText(s.title);
            rb.sub.setText(getString(R.string.workout_row_sub, relLabel(s.date),
                    Math.max(1, Math.round(s.activeMs / 60000f)), (int) Math.round(s.kcalEstimate)));
            rb.badge.setVisibility(View.VISIBLE);
            rb.badge.setText(Math.round(s.completion * 100) + "%");
            if (lib != null) rb.image.setExercise(lib.byId(firstExercise(s)), false);
            rb.getRoot().setOnClickListener(x -> startActivity("walk".equals(s.mode)
                    ? com.transformsai.nutrifitai.ui.steps.WalkSummaryActivity.intent(requireContext(), s.id) // map, splits, route
                    : WorkoutSummaryActivity.forSession(requireContext(), s.id)));
            rb.getRoot().setOnLongClickListener(x -> {
                new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.delete_workout).setMessage(s.title)
                        .setPositiveButton(R.string.delete, (d, w) -> App.graph().workouts.deleteSession(s.id))
                        .setNegativeButton(R.string.cancel, null).show();
                return true;
            });
            b.recent.addView(rb.getRoot());
        }
    }

    private String relLabel(String iso) {
        String t = DateUtil.today();
        if (t.equals(iso)) return getString(R.string.today);
        if (DateUtil.shift(t, -1).equals(iso)) return getString(R.string.yesterday_short);
        return DateUtil.dayShort(iso) + " " + DateUtil.monthDay(iso);
    }

    static String firstExercise(WorkoutSessionEntity s) {
        try {
            return com.google.gson.JsonParser.parseString(s.setsJson).getAsJsonArray().get(0).getAsJsonObject().get("exercise_id").getAsString();
        } catch (Exception e) {
            return "squat";
        }
    }

    @Override
    public void scrollToTop() {
        if (b != null) b.scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onDestroyView() {
        tick.removeCallbacks(countdown);
        super.onDestroyView();
        b = null;
    }

    // ---------------------------------------------------------------- programs carousel
    private final class ProgramAdapter extends RecyclerView.Adapter<ProgramAdapter.VH> {
        private final List<WorkoutPlan> items = Programs.all();

        final class VH extends RecyclerView.ViewHolder {
            final ItemProgramBinding pb;

            VH(ItemProgramBinding pb) {
                super(pb.getRoot());
                this.pb = pb;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemProgramBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            WorkoutPlan p = items.get(position);
            ExerciseLibrary lib = vm.library.getValue();
            h.pb.title.setText(p.title);
            String min = lib == null ? "" : getString(R.string.chip_minutes, Math.max(1, Math.round(p.estimatedSeconds(lib) / 60f))) + " · ";
            h.pb.meta.setText(min + levelLabel(p.level));
            h.pb.image.setExercise(lib == null ? null : lib.byId(p.steps.get(0).exerciseId), position == 0);
            h.pb.getRoot().setContentDescription(p.title + ", " + h.pb.meta.getText());
            h.pb.getRoot().setOnClickListener(x -> startActivity(WorkoutDetailActivity.intent(requireContext(), p.id)));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}

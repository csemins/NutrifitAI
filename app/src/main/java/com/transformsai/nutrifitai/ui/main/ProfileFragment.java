package com.transformsai.nutrifitai.ui.main;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.BuildConfig;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.databinding.FragmentProfileBinding;
import com.transformsai.nutrifitai.databinding.ViewProfileStatBinding;
import com.transformsai.nutrifitai.databinding.ViewSettingRowBinding;
import com.transformsai.nutrifitai.domain.Achievements;
import com.transformsai.nutrifitai.reminders.ReminderScheduler;
import com.transformsai.nutrifitai.ui.common.Avatars;
import com.transformsai.nutrifitai.ui.profile.EditProfileActivity;
import com.transformsai.nutrifitai.ui.social.NotificationsActivity;
import com.transformsai.nutrifitai.util.Units;
import com.transformsai.nutrifitai.sync.SyncScheduler;
import com.transformsai.nutrifitai.ui.assistant.AssistantActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.food.FoodSearchActivity;
import com.transformsai.nutrifitai.ui.hydration.HydrationActivity;
import com.transformsai.nutrifitai.ui.launch.LauncherActivity;
import com.transformsai.nutrifitai.ui.onboarding.OnboardingActivity;
import com.transformsai.nutrifitai.ui.recipes.RecipesActivity;
import com.transformsai.nutrifitai.ui.settings.ExportImportActivity;
import com.transformsai.nutrifitai.ui.settings.PrivacyActivity;
import com.transformsai.nutrifitai.ui.weight.WeightActivity;
import com.transformsai.nutrifitai.util.ImageUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.time.ZonedDateTime;

/** Profile card, notifications, settings and data. */
public class ProfileFragment extends Fragment implements MainActivity.ScrollToTop {
    private FragmentProfileBinding b;
    private final AppGraph g = App.graph();
    private ProfileEntity profile;
    private int versionTaps;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickAvatar =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onAvatar);
    private final ActivityResultLauncher<String> notifPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> { });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentProfileBinding.inflate(inf, c, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        InsetsUtil.padding(b.content, true, false);
        String uid = g.session.requireUid();
        row(b.rowNotifications, R.drawable.ic_bell, R.string.notifications, R.string.notifications, R.color.nl_protein,
                () -> startActivity(new Intent(requireContext(), NotificationsActivity.class)));
        row(b.rowEdit, R.drawable.ic_person, R.string.edit_profile, R.string.edit_profile_sub, R.color.nl_lime,
                () -> startActivity(new Intent(requireContext(), EditProfileActivity.class)));
        row(b.rowGoals, R.drawable.ic_flame, R.string.goals_targets, R.string.goals_sub, R.color.nl_carbs,
                () -> startActivity(OnboardingActivity.edit(requireContext(), 4)));
        row(b.rowUnits, R.drawable.ic_tune, R.string.units, R.string.units_metric, R.color.nl_fat, this::chooseUnits);
        row(b.rowTheme, R.drawable.ic_moon, R.string.appearance, R.string.theme_system, R.color.nl_purple, this::chooseTheme);
        row(b.rowAssistant, R.drawable.ic_sparkle, R.string.assistant, R.string.ask_coach_sub, R.color.nl_lime,
                () -> startActivity(new Intent(requireContext(), AssistantActivity.class)));
        row(b.rowRecipes, R.drawable.ic_book, R.string.recipes, R.string.recipes_sub, R.color.nl_protein,
                () -> startActivity(new Intent(requireContext(), RecipesActivity.class)));
        row(b.rowCustom, R.drawable.ic_edit, R.string.filter_custom, R.string.custom_sub_row, R.color.nl_carbs,
                () -> startActivity(FoodSearchActivity.forFilter(requireContext(), "custom")));
        row(b.rowReminders, R.drawable.ic_bell, R.string.reminders, R.string.reminders_sub, R.color.nl_water,
                () -> startActivity(new Intent(requireContext(), HydrationActivity.class).putExtra(HydrationActivity.EXTRA_SCROLL_REMINDERS, true)));
        row(b.rowWeight, R.drawable.ic_scale, R.string.weight, R.string.weight_sub, R.color.nl_fibre,
                () -> startActivity(new Intent(requireContext(), WeightActivity.class)));
        row(b.rowExport, R.drawable.ic_download, R.string.data_export, R.string.export_row_sub, R.color.nl_lime,
                () -> startActivity(new Intent(requireContext(), ExportImportActivity.class)));
        row(b.rowPrivacy, R.drawable.ic_shield, R.string.privacy, R.string.privacy_sub, R.color.nl_fibre,
                () -> startActivity(new Intent(requireContext(), PrivacyActivity.class)));
        row(b.rowSystemEvents, R.drawable.ic_bolt, R.string.sys_events, R.string.sys_events_sub, R.color.nl_water, this::showSystemEvents);
        // activity & health
        row(b.rowSteps, R.drawable.ic_steps, R.string.walk_tracker, R.string.walk_tracker_sub, R.color.nl_lime,
                () -> startActivity(new Intent(requireContext(), com.transformsai.nutrifitai.ui.steps.StepsActivity.class)));
        row(b.rowFasting, R.drawable.ic_moon, R.string.fasting_title, R.string.fasting_sub, R.color.nl_carbs,
                () -> startActivity(new Intent(requireContext(), com.transformsai.nutrifitai.ui.fasting.FastingActivity.class)));
        row(b.rowAchievements, R.drawable.ic_trophy, R.string.achievements, R.string.achievements_sub, R.color.nl_carbs,
                () -> startActivity(new Intent(requireContext(), com.transformsai.nutrifitai.ui.achievements.AchievementsActivity.class)));
        row(b.rowHealth, R.drawable.ic_heart, R.string.hc_title, R.string.hc_row_sub, R.color.nl_protein,
                () -> startActivity(new Intent(requireContext(), com.transformsai.nutrifitai.ui.steps.StepsActivity.class)));
        // workout coach
        row(b.rowStrict, R.drawable.ic_shield, R.string.coach_strictness, R.string.coach_strictness_sub, R.color.nl_lime, this::chooseStrictness);
        row(b.rowRest, R.drawable.ic_timer, R.string.rest_between, R.string.rest_between_sub, R.color.nl_water, this::chooseRest);
        toggle(b.tVoice, R.drawable.ic_volume, R.string.voice_coach, R.string.voice_coach_sub, g.prefs.voiceCoach(), g.prefs::setVoiceCoach);
        toggle(b.tSkeleton, R.drawable.ic_body, R.string.show_skeleton, R.string.show_skeleton_sub, g.prefs.showSkeleton(), g.prefs::setShowSkeleton);
        toggle(b.tHaptics, R.drawable.ic_bolt, R.string.haptics, R.string.haptics_sub, g.prefs.haptics(), g.prefs::setHaptics);
        toggle(b.tTraining, R.drawable.ic_dumbbell, R.string.training_reminder, R.string.training_reminder, g.prefs.trainingReminder(),
                this::setTrainingReminder);
        // advanced
        row(b.rowModel, R.drawable.ic_body, R.string.pose_model, R.string.pose_model_sub, R.color.nl_purple, this::chooseModel);
        row(b.rowProcessor, R.drawable.ic_tune, R.string.pose_processor, R.string.pose_processor_sub, R.color.nl_purple, this::chooseProcessor);
        row(b.rowServer, R.drawable.ic_server, R.string.developer, R.string.developer_sub, R.color.nl_text_3, this::serverDialog);
        b.rowServer.getRoot().setVisibility(View.GONE);
        b.version.setText(getString(R.string.version, BuildConfig.VERSION_NAME));
        if (BuildConfig.DEBUG) { // developer tools exist only in debug builds
            b.version.setOnClickListener(x -> {
                if (++versionTaps < 7) return;
                versionTaps = 0;
                b.rowServer.getRoot().setVisibility(View.VISIBLE);
            });
        }
        renderCoachRows();
        b.avatar.setOnClickListener(x -> pickAvatar.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        Ui.onClick(b.signOut, x -> confirmSignOut());

        g.profile.observe(uid).observe(getViewLifecycleOwner(), this::renderProfile);
        g.inbox.unread().observe(getViewLifecycleOwner(), n -> {
            int count = n == null ? 0 : n;
            b.rowNotifications.subtitle.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            b.rowNotifications.subtitle.setText(getResources().getQuantityString(R.plurals.unread_notifications, count, count));
        });
        g.friends.friends().observe(getViewLifecycleOwner(), l -> stat(b.statFriends, l == null ? 0 : l.size(), R.plurals.stat_friends));
        g.runIo(() -> {
            Achievements.Summary sum = Achievements.compute(g.db, uid);
            g.main.post(() -> {
                if (b == null) return;
                stat(b.statStreak, sum.currentStreak, R.plurals.stat_streak);
                stat(b.statWorkouts, sum.workouts, R.plurals.stat_workouts);
            });
        });
    }

    private void stat(ViewProfileStatBinding v, int value, int plural) {
        v.value.setText(String.valueOf(value));
        v.label.setText(getResources().getQuantityString(plural, value));
    }

    /** Daily workout reminder (it used to be the bell on the Train tab). */
    private void setTrainingReminder(boolean on) {
        if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        g.prefs.setTrainingReminder(on);
        ReminderScheduler.scheduleTraining(requireContext(), ZonedDateTime.now());
    }

    private void row(ViewSettingRowBinding r, int icon, int title, int sub, int tint, Runnable action) {
        r.icon.setImageResource(icon);
        r.icon.setImageTintList(ContextCompat.getColorStateList(requireContext(), tint));
        r.title.setText(title);
        r.subtitle.setText(sub);
        r.subtitle.setVisibility(r == b.rowUnits || r == b.rowTheme || r == b.rowStrict || r == b.rowRest || r == b.rowModel
                || r == b.rowProcessor ? View.VISIBLE : View.GONE); // keep rows clean
        r.getRoot().setOnClickListener(x -> action.run());
    }

    private void renderProfile(@Nullable ProfileEntity p) {
        if (p == null) return;
        profile = p;
        b.name.setText(p.name == null ? "" : p.name);
        b.handle.setText(p.username == null ? getString(R.string.choose_username) : "@" + p.username);
        b.handle.setOnClickListener(p.username == null ? x -> startActivity(new Intent(requireContext(), EditProfileActivity.class)) : null);
        b.goalChip.setText(goalLine(p));
        b.rowUnits.subtitle.setText("imperial".equals(p.units) ? R.string.units_imperial : R.string.units_metric);
        Avatars.mine(b.avatar, p);
    }

    /** "Lose weight · 72 → 65 kg" */
    private String goalLine(ProfileEntity p) {
        String goal = getString("lose".equals(p.goal) ? R.string.goal_lose_short : "gain".equals(p.goal) ? R.string.goal_gain_short
                : R.string.goal_maintain_short);
        if (p.weightKg <= 0 || "maintain".equals(p.goal) || p.targetWeightKg <= 0) return goal;
        return getString(R.string.goal_line, goal, Units.weightNumber(p.weightKg, p.units), Units.weightNumber(p.targetWeightKg, p.units),
                Units.weightUnit(p.units));
    }

    /** What the app did for each system broadcast (airplane mode, battery saver, low battery). */
    private void showSystemEvents() {
        java.util.List<com.transformsai.nutrifitai.reminders.SystemEventLog.Entry> list =
                com.transformsai.nutrifitai.reminders.SystemEventLog.entries(requireContext());
        StringBuilder sb = new StringBuilder();
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        for (com.transformsai.nutrifitai.reminders.SystemEventLog.Entry e : list) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(eventLabel(e.event)).append(" · ").append(df.format(new java.util.Date(e.at))).append('\n').append(e.action);
        }
        androidx.appcompat.app.AlertDialog.Builder d = new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.sys_events)
                .setMessage(list.isEmpty() ? getString(R.string.sys_events_empty) : sb.toString())
                .setPositiveButton(R.string.close, null);
        if (!list.isEmpty()) {
            d.setNeutralButton(R.string.sys_events_clear, (x, w) -> com.transformsai.nutrifitai.reminders.SystemEventLog.clear(requireContext()));
        }
        d.show();
    }

    private String eventLabel(String event) {
        switch (event) {
            case "AIRPLANE_ON": return getString(R.string.sys_ev_airplane_on);
            case "AIRPLANE_OFF": return getString(R.string.sys_ev_airplane_off);
            case "BATTERY_LOW": return getString(R.string.sys_ev_battery_low);
            case "BATTERY_OKAY": return getString(R.string.sys_ev_battery_okay);
            case "SAVER_ON": return getString(R.string.sys_ev_saver_on);
            default: return getString(R.string.sys_ev_saver_off);
        }
    }

    private interface BoolSetter {
        void set(boolean v);
    }

    private void toggle(com.transformsai.nutrifitai.databinding.ViewToggleRowBinding t, int icon, int title, int sub, boolean on, BoolSetter set) {
        t.icon.setImageResource(icon);
        t.icon.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.nl_lime));
        t.title.setText(title);
        t.subtitle.setVisibility(View.GONE); // the switch and title say enough
        t.toggle.setTrackTintList(ContextCompat.getColorStateList(requireContext(), R.color.switch_track_lime));
        t.toggle.setChecked(on);
        t.toggle.setOnCheckedChangeListener((v, c) -> set.set(c));
        t.getRoot().setOnClickListener(v -> t.toggle.toggle());
    }

    private static final String[] THEMES = {"system", "light", "dark"};
    private static final int[] THEME_LABELS = {R.string.theme_system, R.string.theme_light, R.string.theme_dark};

    private void chooseTheme() {
        String[] labels = new String[THEMES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(THEME_LABELS[i]);
        int sel = Math.max(0, java.util.Arrays.asList(THEMES).indexOf(g.prefs.theme()));
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.appearance)
                .setSingleChoiceItems(labels, sel, (d, w) -> {
                    d.dismiss();
                    g.prefs.setTheme(THEMES[w]);
                    App.applyTheme(THEMES[w]); // recreates the screen in the new theme
                }).show();
    }

    private void renderCoachRows() {
        b.rowTheme.subtitle.setText(THEME_LABELS[Math.max(0, java.util.Arrays.asList(THEMES).indexOf(g.prefs.theme()))]);
        String st = g.prefs.coachStrictness();
        b.rowStrict.subtitle.setText("strict".equals(st) ? R.string.strict_strict_short : "relaxed".equals(st) ? R.string.strict_relaxed_short : R.string.strict_standard_short);
        b.rowRest.subtitle.setText(getString(R.string.rest_seconds_value, g.prefs.restSeconds()));
        String m = g.prefs.poseModel();
        boolean heavyReady = com.transformsai.nutrifitai.fitness.pose.PoseModelStore.heavyReady(requireContext());
        b.rowModel.subtitle.setText("lite".equals(m) ? getString(R.string.model_lite) : "full".equals(m) ? getString(R.string.model_full)
                : "heavy".equals(m) ? getString(heavyReady ? R.string.model_heavy : R.string.model_heavy_missing) : getString(R.string.model_auto));
        String d = g.prefs.poseDelegate();
        b.rowProcessor.subtitle.setText("gpu".equals(d) ? R.string.proc_gpu : "cpu".equals(d) ? R.string.proc_cpu : R.string.model_auto);
    }

    private void chooseStrictness() {
        String[] ids = {"relaxed", "standard", "strict"};
        String[] labels = {getString(R.string.strict_relaxed), getString(R.string.strict_standard), getString(R.string.strict_strict)};
        int sel = java.util.Arrays.asList(ids).indexOf(g.prefs.coachStrictness());
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.coach_strictness)
                .setSingleChoiceItems(labels, Math.max(0, sel), (dd, w) -> {
                    g.prefs.setCoachStrictness(ids[w]);
                    renderCoachRows();
                    dd.dismiss();
                }).show();
    }

    private void chooseRest() {
        int[] secs = {10, 15, 20, 30, 45, 60};
        String[] labels = new String[secs.length];
        int sel = 1;
        for (int i = 0; i < secs.length; i++) {
            labels[i] = getString(R.string.rest_seconds_value, secs[i]);
            if (secs[i] == g.prefs.restSeconds()) sel = i;
        }
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.rest_between)
                .setSingleChoiceItems(labels, sel, (dd, w) -> {
                    g.prefs.setRestSeconds(secs[w]);
                    renderCoachRows();
                    dd.dismiss();
                }).show();
    }

    private void chooseProcessor() {
        String[] ids = {"auto", "gpu", "cpu"};
        String[] labels = {getString(R.string.proc_auto), getString(R.string.proc_gpu), getString(R.string.proc_cpu)};
        int sel = java.util.Arrays.asList(ids).indexOf(g.prefs.poseDelegate());
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.pose_processor)
                .setSingleChoiceItems(labels, Math.max(0, sel), (dd, w) -> {
                    g.prefs.setPoseDelegate(ids[w]);
                    renderCoachRows();
                    dd.dismiss();
                }).show();
    }

    /** Pose model: auto / lite / full ship in the app; heavy is downloaded (and verified) on request. */
    private void chooseModel() {
        String[] ids = {"auto", "lite", "full", "heavy"};
        String[] labels = {getString(R.string.model_auto_long), getString(R.string.model_lite_long), getString(R.string.model_full_long),
                getString(R.string.model_heavy_long)};
        int sel = java.util.Arrays.asList(ids).indexOf(g.prefs.poseModel());
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.pose_model)
                .setSingleChoiceItems(labels, Math.max(0, sel), (dd, w) -> {
                    dd.dismiss();
                    if ("heavy".equals(ids[w]) && !com.transformsai.nutrifitai.fitness.pose.PoseModelStore.heavyReady(requireContext())) {
                        downloadHeavy();
                        return;
                    }
                    g.prefs.setPoseModel(ids[w]);
                    renderCoachRows();
                    if (!"heavy".equals(ids[w]) && com.transformsai.nutrifitai.fitness.pose.PoseModelStore.heavyReady(requireContext())) {
                        new MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.model_remove_heavy_q)
                                .setPositiveButton(R.string.remove, (x, y) -> {
                                    com.transformsai.nutrifitai.fitness.pose.PoseModelStore.deleteHeavy(requireContext());
                                    renderCoachRows();
                                })
                                .setNegativeButton(R.string.keep, null).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void downloadHeavy() {
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        com.google.android.material.progressindicator.LinearProgressIndicator bar =
                new com.google.android.material.progressindicator.LinearProgressIndicator(requireContext());
        bar.setMax(100);
        bar.setTrackStopIndicatorSize(0);
        FrameLayout box = new FrameLayout(requireContext());
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(bar);
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.model_downloading)
                .setMessage(R.string.model_download_body).setView(box).setCancelable(false)
                .setNegativeButton(R.string.cancel, (x, y) -> cancel.set(true)).show();
        g.runIo(() -> {
            try {
                com.transformsai.nutrifitai.fitness.pose.PoseModelStore.downloadHeavy(requireContext().getApplicationContext(),
                        new okhttp3.OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build(),
                        pct -> g.main.post(() -> bar.setProgressCompat(pct, true)), cancel);
                g.prefs.setPoseModel("heavy");
                g.main.post(() -> {
                    d.dismiss();
                    if (b == null) return;
                    renderCoachRows();
                    Ui.snack(b.getRoot(), getString(R.string.model_heavy_ready));
                });
            } catch (Exception e) {
                g.main.post(() -> {
                    d.dismiss();
                    if (b != null && !cancel.get()) Ui.snack(b.getRoot(), getString(R.string.model_download_failed));
                });
            }
        });
    }

    private void onAvatar(@Nullable Uri uri) {
        if (uri == null) return;
        g.runIo(() -> {
            try {
                File out = new File(ImageUtils.mealImageDir(requireContext()), "avatar_" + System.currentTimeMillis() + ".jpg");
                ImageUtils.process(requireContext(), uri, out);
                g.profile.setAvatar(out.getAbsolutePath(), null);
            } catch (Exception e) {
                g.main.post(() -> {
                    if (b != null) Ui.snack(b.getRoot(), getString(R.string.cam_error));
                });
            }
        });
    }

    private void chooseUnits() {
        if (profile == null) return;
        String[] opts = {getString(R.string.units_metric), getString(R.string.units_imperial)};
        int sel = "imperial".equals(profile.units) ? 1 : 0;
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.units)
                .setSingleChoiceItems(opts, sel, (d, w) -> {
                    profile.units = w == 1 ? "imperial" : "metric";
                    g.profile.save(profile, null);
                    d.dismiss();
                }).show();
    }

    private void serverDialog() {
        TextInputLayout til = new TextInputLayout(requireContext());
        TextInputEditText et = new TextInputEditText(til.getContext());
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(g.prefs.aiBaseUrl());
        til.setHint(getString(R.string.dev_ai_url));
        til.addView(et);
        FrameLayout box = new FrameLayout(requireContext());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(til);
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.developer).setMessage(R.string.ai_server_body).setView(box)
                .setPositiveButton(R.string.save, (x, w) -> {
                    String url = et.getText() == null ? "" : et.getText().toString().trim();
                    if (!BuildConfig.DEBUG && url.startsWith("http:")) {
                        Ui.snack(b.getRoot(), "HTTPS is required");
                        return;
                    }
                    g.prefs.setAiBaseUrl(url);
                })
                .setNeutralButton(R.string.test_connection, null)
                .setNegativeButton(R.string.sync_now, (x, w) -> SyncScheduler.requestSync(requireContext())).show();
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x -> {
            String before = g.prefs.aiBaseUrl();
            g.prefs.setAiBaseUrl(et.getText() == null ? before : et.getText().toString().trim());
            til.setHelperText(getString(R.string.loading));
            g.runIo(() -> {
                boolean ok = g.ai.health();
                g.prefs.setAiBaseUrl(before);
                g.main.post(() -> til.setHelperText(getString(ok ? R.string.ai_server_ok : R.string.ai_server_bad)));
            });
        });
    }

    private void confirmSignOut() {
        g.runIo(() -> {
            int n = g.auth.pendingOps();
            g.main.post(() -> {
                if (b == null) return;
                new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.sign_out_q)
                        .setMessage(n > 0 ? getString(R.string.sign_out_pending, n) : getString(R.string.sign_out_body))
                        .setPositiveButton(R.string.sign_out, (d, w) -> g.auth.signOut(r -> toLauncher()))
                        .setNegativeButton(R.string.cancel, null).show();
            });
        });
    }

    private void toLauncher() {
        startActivity(new Intent(requireContext(), LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        requireActivity().finish();
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

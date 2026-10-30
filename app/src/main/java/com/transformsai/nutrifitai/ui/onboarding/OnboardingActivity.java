package com.transformsai.nutrifitai.ui.onboarding;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.databinding.ActivityOnboardingBinding;
import com.transformsai.nutrifitai.databinding.ItemChoiceBinding;
import com.transformsai.nutrifitai.domain.GoalCalculator;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.main.MainActivity;
import com.transformsai.nutrifitai.ui.profile.ProfileForm;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Units;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Five-step profile + goals flow. Also used for "Edit profile" (EXTRA_EDIT) and "Goals & targets"
 * (EXTRA_START_STEP = 4). Saving writes the profile and a NEW goal snapshot effective today.
 */
public class OnboardingActivity extends BaseActivity {
    public static final String EXTRA_EDIT = "edit";
    public static final String EXTRA_START_STEP = "start_step";
    /** About, body, goal, food, targets, and (new accounts only) permissions. */
    private static final int STEPS = 6, TARGETS = 4;
    public static final String[] ACTIVITIES = {"sedentary", "light", "moderate", "active", "very_active"};
    public static final int[][] ACTIVITY_TEXT = {
            {R.string.act_sedentary, R.string.act_sedentary_d}, {R.string.act_light, R.string.act_light_d},
            {R.string.act_moderate, R.string.act_moderate_d}, {R.string.act_active, R.string.act_active_d},
            {R.string.act_very, R.string.act_very_d}};
    public static final String[] DIETS = {"Vegetarian", "Vegan", "Pescatarian", "Halal", "High protein", "Low sodium", "Lactose-free", "Gluten-free"};
    public static final String[] ALLERGENS = {"Peanuts", "Tree nuts", "Milk", "Eggs", "Fish", "Shellfish", "Wheat / gluten", "Soy", "Sesame"};

    public static Intent edit(Context c, int startStep) {
        return new Intent(c, OnboardingActivity.class).putExtra(EXTRA_EDIT, true).putExtra(EXTRA_START_STEP, startStep);
    }

    private ActivityOnboardingBinding b;
    private ProfileEntity profile = new ProfileEntity();
    private int step;
    private int activityIndex = 1;
    private boolean edit;
    private boolean targetsTouched;
    private final List<View> activityViews = new ArrayList<>();
    @Nullable private String birthDate;
    @Nullable private java.io.File photo;
    private ProfileForm.UsernameWatcher username;
    private boolean saving;

    private final androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> pickPhoto =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(), this::onPhoto);

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.getRoot(), true, true);
        edit = getIntent().getBooleanExtra(EXTRA_EDIT, false);
        buildChoices();
        bindPermissions();
        b.stepBody.units.setOnSelectListener(i -> {
            applyUnits();
            updateBmi();
        });
        if (s != null) {
            birthDate = s.getString("birth");
            String ph = s.getString("photo");
            if (ph != null && new java.io.File(ph).isFile()) photo = new java.io.File(ph);
        }
        username = new ProfileForm.UsernameWatcher(b.stepAbout.usernameLayout, b.stepAbout.username, null);
        b.stepAbout.birth.setOnClickListener(v -> ProfileForm.pickBirthDate(this, birthDate, d -> {
            birthDate = d;
            b.stepAbout.birth.setText(ProfileForm.formatDate(d));
            b.stepAbout.birthLayout.setError(ProfileForm.birthError(this, d));
        }));
        b.stepAbout.photoBox.setOnClickListener(v -> pickPhoto.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                .setMediaType(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        b.stepAbout.name.setOnFocusChangeListener((v, has) -> {
            if (has || username.name().length() > 0 || b.stepAbout.name.getText() == null) return;
            String sug = com.transformsai.nutrifitai.social.ProfileCards.suggestion(b.stepAbout.name.getText().toString());
            if (!sug.isEmpty()) b.stepAbout.username.setText(sug);
        });
        android.text.TextWatcher bmiWatch = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int n, int x) { }
            @Override public void onTextChanged(CharSequence c, int a, int n, int x) { }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                updateBmi();
            }
        };
        b.stepBody.heightCm.addTextChangedListener(bmiWatch);
        b.stepBody.heightFt.addTextChangedListener(bmiWatch);
        b.stepBody.heightIn.addTextChangedListener(bmiWatch);
        b.stepBody.weight.addTextChangedListener(bmiWatch);
        Ui.onClick(b.next, v -> next());
        b.back.setOnClickListener(v -> back());
        b.stepTargets.recalc.setOnClickListener(v -> {
            targetsTouched = false;
            fillTargets(true);
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                back();
            }
        });
        int start = s != null ? s.getInt("step", 0) : getIntent().getIntExtra(EXTRA_START_STEP, 0);
        graph().profile.get(r -> {
            if (r.value != null) profile = r.value;
            prefill();
            show(start);
            if (edit) graph().profile.activeGoal(DateUtil.today(), g -> {
                if (g.value != null) setTargets(g.value.kcal, g.value.proteinG, g.value.carbsG, g.value.fatG, g.value.fibreG, g.value.waterMl);
            });
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
        out.putString("birth", birthDate);
        if (photo != null) out.putString("photo", photo.getAbsolutePath());
    }

    private void buildChoices() {
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < ACTIVITIES.length; i++) {
            ItemChoiceBinding cb = ItemChoiceBinding.inflate(inf, b.stepGoal.activityList, false);
            cb.title.setText(ACTIVITY_TEXT[i][0]);
            cb.subtitle.setText(ACTIVITY_TEXT[i][1]);
            final int idx = i;
            cb.getRoot().setOnClickListener(v -> selectActivity(idx));
            b.stepGoal.activityList.addView(cb.getRoot());
            activityViews.add(cb.getRoot());
        }
        for (String d : DIETS) b.stepDiet.diet.addView(chip(d));
        for (String a : ALLERGENS) b.stepDiet.allergies.addView(chip(a));
    }

    private Chip chip(String text) {
        Chip c = new Chip(this);
        c.setText(text);
        c.setCheckable(true);
        return c;
    }

    private void selectActivity(int i) {
        activityIndex = i;
        for (int k = 0; k < activityViews.size(); k++) activityViews.get(k).setSelected(k == i);
    }

    private void prefill() {
        b.stepAbout.name.setText(profile.name);
        if (birthDate == null) birthDate = profile.birthDate;
        b.stepAbout.birth.setText(ProfileForm.formatDate(birthDate));
        if (profile.username != null) {
            b.stepAbout.username.setText(profile.username);
            b.stepAbout.usernameLayout.setEnabled(false); // changed from Edit profile
        }
        renderPhoto();
        b.stepAbout.sex.select("female".equals(profile.sex) ? 0 : "male".equals(profile.sex) ? 1 : 2, false);
        b.stepBody.units.select(Units.imperial(profile.units) ? 1 : 0, false);
        applyUnits();
        boolean imp = Units.imperial(profile.units);
        if (profile.heightCm > 0) {
            if (imp) {
                int totalIn = (int) Math.round(profile.heightCm / Units.CM_PER_IN);
                b.stepBody.heightFt.setText(String.valueOf(totalIn / 12));
                b.stepBody.heightIn.setText(String.valueOf(totalIn % 12));
            } else b.stepBody.heightCm.setText(Units.num(Math.round(profile.heightCm)));
        }
        if (profile.weightKg > 0) b.stepBody.weight.setText(Units.weightNumber(profile.weightKg, profile.units));
        if (profile.targetWeightKg > 0) b.stepBody.target.setText(Units.weightNumber(profile.targetWeightKg, profile.units));
        int act = Arrays.asList(ACTIVITIES).indexOf(profile.activityLevel);
        selectActivity(act < 0 ? 1 : act);
        b.stepGoal.goal.select("lose".equals(profile.goal) ? 0 : "gain".equals(profile.goal) ? 2 : 1, false);
        checkChips(b.stepDiet.diet, profile.dietPrefs);
        List<String> other = checkChips(b.stepDiet.allergies, profile.allergies);
        b.stepDiet.otherAllergies.setText(String.join(", ", other));
    }

    /** Checks chips whose label appears in csv; returns labels that matched no chip. */
    public static List<String> checkChips(ChipGroup g, @Nullable String csv) {
        List<String> rest = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return rest;
        for (String raw : csv.split(",")) {
            String v = raw.trim();
            if (v.isEmpty()) continue;
            boolean found = false;
            for (int i = 0; i < g.getChildCount(); i++) {
                Chip c = (Chip) g.getChildAt(i);
                if (c.getText().toString().equalsIgnoreCase(v)) {
                    c.setChecked(true);
                    found = true;
                }
            }
            if (!found) rest.add(v);
        }
        return rest;
    }

    private void applyUnits() {
        boolean imp = b.stepBody.units.getSelected() == 1;
        b.stepBody.heightCmLayout.setVisibility(imp ? View.GONE : View.VISIBLE);
        b.stepBody.heightImperial.setVisibility(imp ? View.VISIBLE : View.GONE);
        String u = imp ? "lb" : "kg";
        b.stepBody.weightLayout.setHint(getString(R.string.ob_weight, u));
        b.stepBody.targetLayout.setHint(getString(R.string.ob_target, u));
    }

    private void show(int s) {
        int next = Math.max(0, Math.min(last(), s));
        boolean forward = next >= step;
        b.flipper.setInAnimation(this, forward ? R.anim.ob_in_right : R.anim.ob_in_left);
        b.flipper.setOutAnimation(this, forward ? R.anim.ob_out_left : R.anim.ob_out_right);
        step = next;
        b.flipper.setDisplayedChild(step);
        animateStep(b.flipper.getChildAt(step));
        b.progress.setProgressCompat(step + 1, true);
        b.stepLabel.setText(getString(R.string.ob_step, step + 1, last() + 1));
        b.progress.setMax(last() + 1);
        b.next.setText(step == last() ? (edit ? R.string.ob_save : R.string.ob_finish) : R.string.next);
        b.back.setVisibility(step == 0 && !edit ? View.INVISIBLE : View.VISIBLE);
        b.scroll.scrollTo(0, 0);
        if (step == TARGETS) fillTargets(false);
        if (step == STEPS - 1) renderPermissions();
    }

    /** Hero pops in and the motivational line rises, so every step feels like a new page of a story. */
    private void animateStep(View page) {
        View hero = page.findViewById(R.id.hero) != null ? page.findViewById(R.id.hero) : page.findViewById(R.id.photoBox);
        View motto = page.findViewById(R.id.motto);
        if (hero != null) {
            hero.setScaleX(0.7f);
            hero.setScaleY(0.7f);
            hero.setAlpha(0f);
            hero.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(120).setDuration(420)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
        }
        if (motto != null) {
            motto.setTranslationY(24 * getResources().getDisplayMetrics().density);
            motto.setAlpha(0f);
            motto.animate().translationY(0).alpha(1f).setStartDelay(260).setDuration(380).start();
        }
    }

    /** Editing goals ends at the targets; signup continues to the permissions step. */
    private int last() {
        return edit ? TARGETS : STEPS - 1;
    }

    // ---------------------------------------------------------------- permissions step (signup)
    private final com.transformsai.nutrifitai.ui.common.Permissions.Asker asker =
            new com.transformsai.nutrifitai.ui.common.Permissions.Asker(this, () -> this);
    private final androidx.activity.result.ActivityResultLauncher<String[]> healthPerm = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(), r -> {
                boolean any = com.transformsai.nutrifitai.health.HealthConnect.canReadSteps(this);
                graph().prefs.setHealthConnect(any);
                renderPermissions();
            });

    private void bindPermissions() {
        bindPermission(b.stepPermissions.pNotifications, R.drawable.ic_bell, R.string.perm_notifications, R.string.perm_notifications_body, R.color.nl_protein,
                () -> {
                    if (com.transformsai.nutrifitai.ui.common.Permissions.NOTIFICATIONS.isEmpty()) {
                        com.transformsai.nutrifitai.ui.common.Permissions.openSettings(this, ""); // Android 12 and older: a settings switch
                    } else {
                        asker.run(com.transformsai.nutrifitai.ui.common.Permissions.NOTIFICATIONS, R.string.perm_why_notifications, this::renderPermissions,
                                this::renderPermissions);
                    }
                });
        bindPermission(b.stepPermissions.pCamera, R.drawable.ic_camera, R.string.perm_camera, R.string.perm_camera_body, R.color.nl_lime,
                () -> asker.run(com.transformsai.nutrifitai.ui.common.Permissions.CAMERA, R.string.perm_why_camera, this::renderPermissions, this::renderPermissions));
        bindPermission(b.stepPermissions.pMic, R.drawable.ic_mic, R.string.perm_mic, R.string.perm_mic_body, R.color.nl_carbs,
                () -> asker.run(com.transformsai.nutrifitai.ui.common.Permissions.MIC, R.string.perm_why_mic, this::renderPermissions, this::renderPermissions));
        bindPermission(b.stepPermissions.pActivity, R.drawable.ic_steps, R.string.perm_activity, R.string.perm_activity_body, R.color.nl_water,
                () -> asker.run(com.transformsai.nutrifitai.ui.common.Permissions.ACTIVITY, R.string.perm_why_activity, () -> {
                    graph().prefs.setStepTracking(true);
                    com.transformsai.nutrifitai.health.StepWorker.schedule(this);
                    // then Google Fit / Samsung Health steps through Health Connect (Android 14+)
                    if (com.transformsai.nutrifitai.health.HealthConnect.available(this)
                            && !com.transformsai.nutrifitai.health.HealthConnect.canReadSteps(this)) {
                        healthPerm.launch(com.transformsai.nutrifitai.health.HealthConnect.PERMISSIONS);
                    }
                    renderPermissions();
                }, this::renderPermissions));
    }

    private void bindPermission(com.transformsai.nutrifitai.databinding.ViewPermissionRowBinding r, int icon, int title, int body, int tint, Runnable allow) {
        r.icon.setImageResource(icon);
        r.icon.setImageTintList(androidx.core.content.ContextCompat.getColorStateList(this, tint));
        r.title.setText(title);
        r.body.setText(body);
        r.allow.setOnClickListener(v -> allow.run());
    }

    private void renderPermissions() {
        if (b == null) return;
        showRow(b.stepPermissions.pNotifications, com.transformsai.nutrifitai.ui.common.Permissions.notificationsOn(this));
        showRow(b.stepPermissions.pCamera, com.transformsai.nutrifitai.ui.common.Permissions.granted(this, com.transformsai.nutrifitai.ui.common.Permissions.CAMERA));
        showRow(b.stepPermissions.pMic, com.transformsai.nutrifitai.ui.common.Permissions.granted(this, com.transformsai.nutrifitai.ui.common.Permissions.MIC));
        showRow(b.stepPermissions.pActivity, com.transformsai.nutrifitai.ui.common.Permissions.granted(this, com.transformsai.nutrifitai.ui.common.Permissions.ACTIVITY));
    }

    /** Allowed rows show a check and can't be tapped; the rest offer Allow. */
    private void showRow(com.transformsai.nutrifitai.databinding.ViewPermissionRowBinding r, boolean on) {
        r.allow.setEnabled(!on);
        r.allow.setText(on ? R.string.perm_allowed : R.string.perm_allow);
        r.allow.setIconResource(on ? R.drawable.ic_check : 0);
        r.getRoot().setContentDescription(r.title.getText() + ", " + r.allow.getText());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderPermissions(); // the user may have changed something in Android settings meanwhile
    }

    private void back() {
        if (step == 0 || (edit && step == getIntent().getIntExtra(EXTRA_START_STEP, 0))) {
            if (edit) finish();
            else moveTaskToBack(true);
            return;
        }
        show(step - 1);
    }

    private void next() {
        Ui.hideKeyboard(this);
        if (!validateStep(step)) return;
        if (step < last()) {
            show(step + 1);
            return;
        }
        save();
    }

    private static double num(EditText e) {
        return Ui.parse(e.getText());
    }

    private boolean validateStep(int s) {
        switch (s) {
            case 0: {
                String name = b.stepAbout.name.getText() == null ? "" : b.stepAbout.name.getText().toString().trim();
                boolean ok = true;
                if (name.length() < 2) {
                    b.stepAbout.nameLayout.setError(getString(R.string.err_name));
                    ok = false;
                } else b.stepAbout.nameLayout.setError(null);
                String birthErr = ProfileForm.birthError(this, birthDate);
                b.stepAbout.birthLayout.setError(birthErr);
                if (birthErr != null) ok = false;
                if (profile.username == null) { // claimed once at signup; changed later from Edit profile
                    String u = username.name();
                    if (u.isEmpty()) {
                        b.stepAbout.usernameLayout.setError(getString(R.string.username_needed));
                        ok = false;
                    } else if (!com.transformsai.nutrifitai.social.ProfileCards.USERNAME.matcher(u).matches()) {
                        b.stepAbout.usernameLayout.setError(getString(R.string.username_invalid));
                        ok = false;
                    } else if (username.state() == com.transformsai.nutrifitai.social.ProfileCards.Check.TAKEN) {
                        b.stepAbout.usernameLayout.setError(getString(R.string.username_taken));
                        ok = false;
                    }
                }
                return ok;
            }
            case 1: {
                boolean ok = true;
                double h = heightCm();
                if (Double.isNaN(h) || h < 120 || h > 230) {
                    (b.stepBody.units.getSelected() == 1 ? b.stepBody.heightFtLayout : b.stepBody.heightCmLayout).setError(getString(R.string.err_height));
                    ok = false;
                } else {
                    b.stepBody.heightCmLayout.setError(null);
                    b.stepBody.heightFtLayout.setError(null);
                }
                double w = kg(num(b.stepBody.weight));
                if (Double.isNaN(w) || w < 30 || w > 300) {
                    b.stepBody.weightLayout.setError(getString(R.string.err_weight));
                    ok = false;
                } else b.stepBody.weightLayout.setError(null);
                double t = kg(num(b.stepBody.target));
                if (Double.isNaN(t) || t < 30 || t > 300) {
                    b.stepBody.targetLayout.setError(getString(R.string.err_weight));
                    ok = false;
                } else b.stepBody.targetLayout.setError(null);
                return ok;
            }
            case 4: {
                int[] v = targets();
                if (v == null || !GoalCalculator.manualTargetsValid(v[0], v[1], v[2], v[3], v[4], v[5])) {
                    b.stepTargets.kcalLayout.setError(getString(R.string.err_targets));
                    return false;
                }
                b.stepTargets.kcalLayout.setError(null);
                return true;
            }
            default:
                return true;
        }
    }

    private int age() {
        return com.transformsai.nutrifitai.domain.Bmi.age(birthDate, DateUtil.todayDate());
    }

    private void updateBmi() {
        ProfileForm.bindBmi(b.stepBody.bmi, heightCm(), kg(num(b.stepBody.weight)));
    }

    private void onPhoto(@Nullable android.net.Uri uri) {
        if (uri == null) return;
        graph().runIo(() -> {
            try {
                java.io.File out = new java.io.File(com.transformsai.nutrifitai.util.ImageUtils.mealImageDir(this),
                        "avatar_" + System.currentTimeMillis() + ".jpg");
                com.transformsai.nutrifitai.util.ImageUtils.process(this, uri, out);
                graph().main.post(() -> {
                    photo = out;
                    renderPhoto();
                });
            } catch (Exception e) {
                graph().main.post(() -> Ui.snack(b.getRoot(), getString(R.string.cam_error)));
            }
        });
    }

    private void renderPhoto() {
        b.stepAbout.photo.setCircle();
        if (photo != null) {
            com.bumptech.glide.Glide.with(this).load(photo).centerCrop().into(b.stepAbout.photo);
            b.stepAbout.photoHint.setText(R.string.ob_photo_change);
        } else if (profile.avatarLocalPath != null) {
            com.transformsai.nutrifitai.ui.common.Avatars.mine(b.stepAbout.photo, profile);
        } else {
            b.stepAbout.photo.setEmoji("📷", "vegetables");
        }
    }

    private double kg(double v) {
        return b.stepBody.units.getSelected() == 1 ? Units.lbToKg(v) : v;
    }

    private double heightCm() {
        if (b.stepBody.units.getSelected() == 1) {
            double ft = num(b.stepBody.heightFt);
            double in = num(b.stepBody.heightIn);
            if (Double.isNaN(ft)) return Double.NaN;
            return (ft * 12 + (Double.isNaN(in) ? 0 : in)) * Units.CM_PER_IN;
        }
        return num(b.stepBody.heightCm);
    }

    private GoalCalculator.Input input() {
        GoalCalculator.Input in = new GoalCalculator.Input();
        int sx = b.stepAbout.sex.getSelected();
        in.sex = sx == 0 ? "female" : sx == 1 ? "male" : "unspecified";
        in.age = age();
        in.heightCm = heightCm();
        in.weightKg = kg(num(b.stepBody.weight));
        in.targetWeightKg = kg(num(b.stepBody.target));
        in.activity = ACTIVITIES[activityIndex];
        int gsel = b.stepGoal.goal.getSelected();
        in.goal = gsel == 0 ? "lose" : gsel == 2 ? "gain" : "maintain";
        return in;
    }

    private void fillTargets(boolean force) {
        GoalCalculator.Input in = input();
        if (!GoalCalculator.validate(in).isEmpty()) return;
        GoalCalculator.Plan p = GoalCalculator.calculate(in);
        String extra = getString("lose".equals(in.goal) ? R.string.ob_explain_lose
                : "gain".equals(in.goal) ? R.string.ob_explain_gain : R.string.ob_explain_maintain);
        b.stepTargets.explain.setText(getString(R.string.ob_explain, p.bmr, p.tdee, extra));
        if (force || (!targetsTouched && (!edit || targets() == null))) {
            setTargets(p.kcal, p.proteinG, p.carbsG, p.fatG, p.fibreG, p.waterMl);
        }
        int[] t = targets();
        b.stepTargets.bigKcal.setText(Ui.kcal(t == null ? p.kcal : t[0]));
    }

    private void setTargets(int kcal, int p, int c, int f, int fib, int water) {
        b.stepTargets.kcal.setText(String.valueOf(kcal));
        b.stepTargets.protein.setText(String.valueOf(p));
        b.stepTargets.carbs.setText(String.valueOf(c));
        b.stepTargets.fat.setText(String.valueOf(f));
        b.stepTargets.fibre.setText(String.valueOf(fib));
        b.stepTargets.water.setText(String.valueOf(water));
        b.stepTargets.bigKcal.setText(Ui.kcal(kcal));
        if (edit) targetsTouched = true;
    }

    @Nullable
    private int[] targets() {
        double[] v = {num(b.stepTargets.kcal), num(b.stepTargets.protein), num(b.stepTargets.carbs),
                num(b.stepTargets.fat), num(b.stepTargets.fibre), num(b.stepTargets.water)};
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) {
            if (Double.isNaN(v[i])) return null;
            out[i] = (int) Math.round(v[i]);
        }
        return out;
    }

    public static String chipsCsv(ChipGroup g, @Nullable String extra) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < g.getChildCount(); i++) {
            Chip c = (Chip) g.getChildAt(i);
            if (c.isChecked()) out.add(c.getText().toString());
        }
        if (extra != null) {
            for (String s : extra.split(",")) {
                String t = s.trim();
                if (!t.isEmpty() && t.length() <= 30) out.add(t);
            }
        }
        return String.join(",", out);
    }

    private void save() {
        for (int s = 0; s <= TARGETS; s++) {
            if (!validateStep(s)) {
                show(s);
                return;
            }
        }
        GoalCalculator.Input in = input();
        profile.name = b.stepAbout.name.getText() == null ? "" : b.stepAbout.name.getText().toString().trim();
        profile.birthDate = birthDate;
        profile.birthYear = birthDate == null ? DateUtil.todayDate().getYear() - in.age : java.time.LocalDate.parse(birthDate).getYear();
        profile.sex = in.sex;
        profile.units = b.stepBody.units.getSelected() == 1 ? "imperial" : "metric";
        profile.heightCm = in.heightCm;
        profile.weightKg = NutritionRound.r1(in.weightKg);
        profile.targetWeightKg = NutritionRound.r1(in.targetWeightKg);
        profile.activityLevel = in.activity;
        profile.goal = in.goal;
        profile.dietPrefs = chipsCsv(b.stepDiet.diet, null);
        profile.allergies = chipsCsv(b.stepDiet.allergies,
                b.stepDiet.otherAllergies.getText() == null ? null : b.stepDiet.otherAllergies.getText().toString());
        profile.onboarded = true;
        if (profile.email == null) profile.email = graph().session.email();
        int[] t = targets();
        GoalEntity g = new GoalEntity();
        g.kcal = t[0];
        g.proteinG = t[1];
        g.carbsG = t[2];
        g.fatG = t[3];
        g.fibreG = t[4];
        g.waterMl = t[5];
        GoalCalculator.Plan plan = GoalCalculator.calculate(in);
        g.method = plan.kcal == t[0] && plan.proteinG == t[1] ? "mifflin_st_jeor" : "manual";
        if (saving) return;
        saving = true;
        b.next.setEnabled(false);
        boolean firstWeight = !edit;
        String handle = profile.username == null ? username.name() : null;
        java.io.File pic = photo;
        graph().profile.saveProfileAndGoal(profile, g, r -> {
            if (firstWeight) graph().weights.save(null, DateUtil.today(), profile.weightKg, null);
            if (edit) {
                if (pic != null) graph().profile.setAvatar(pic.getAbsolutePath(), null);
                Ui.snack(b.getRoot(), getString(R.string.saved));
                finish();
                return;
            }
            if (handle == null || handle.isEmpty()) {
                afterClaim(pic, t[0]);
                return;
            }
            graph().cards.claim(handle, c -> {
                // taken meanwhile, or offline: the Social tab asks again later; everything else is saved
                if (!c.ok()) Ui.snack(b.getRoot(), c.error);
                afterClaim(pic, t[0]);
            });
        });
    }

    private void afterClaim(@Nullable java.io.File pic, int kcal) {
        if (pic != null) graph().profile.setAvatar(pic.getAbsolutePath(), null);
        celebrate(kcal);
    }

    /** The finish line: the check pops, the daily target counts up, then into the app. */
    private void celebrate(int kcal) {
        Ui.hideKeyboard(this);
        b.ready.setVisibility(View.VISIBLE);
        b.ready.setAlpha(0f);
        b.ready.animate().alpha(1f).setDuration(250).start();
        b.readyCheck.setScaleX(0f);
        b.readyCheck.setScaleY(0f);
        b.readyCheck.animate().scaleX(1f).scaleY(1f).setStartDelay(150).setDuration(500)
                .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
        b.readyGlow.animate().scaleX(1.25f).scaleY(1.25f).alpha(0f).setStartDelay(400).setDuration(900).start();
        b.readyTitle.setText(getString(R.string.ob_ready_title_name, profile.name));
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(0, kcal);
        va.setDuration(1100);
        va.setStartDelay(350);
        va.addUpdateListener(x -> b.readyKcal.setText(Ui.kcal((int) x.getAnimatedValue())));
        va.start();
        b.readyGo.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (username != null) username.release();
        super.onDestroy();
    }

    /** Local helper to avoid pulling domain classes into the activity for one rounding call. */
    static final class NutritionRound {
        static double r1(double v) {
            return Math.round(v * 10.0) / 10.0;
        }
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}

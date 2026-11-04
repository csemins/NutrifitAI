package com.transformsai.nutrifitai.ui.profile;

import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.databinding.ActivityEditProfileBinding;
import com.transformsai.nutrifitai.domain.Bmi;
import com.transformsai.nutrifitai.domain.GoalCalculator;
import com.transformsai.nutrifitai.social.ProfileCards;
import com.transformsai.nutrifitai.ui.common.Avatars;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.onboarding.OnboardingActivity;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.ImageUtils;
import com.transformsai.nutrifitai.util.Units;
import com.bumptech.glide.Glide;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Everything about me on one page: photo, name, @username (changeable every 30 days), birth date, body, goal,
 * activity and food preferences. When something that drives the energy estimate changes, it offers to update the
 * daily targets (a new goal snapshot from today; past days keep their old targets).
 */
public class EditProfileActivity extends BaseActivity {
    private ActivityEditProfileBinding b;
    @Nullable private ProfileEntity profile;
    @Nullable private String birthDate;
    @Nullable private File newPhoto;
    private boolean removePhoto, saving, loaded;
    private int activityIndex = 1;
    private ProfileForm.UsernameWatcher username;

    private final ActivityResultLauncher<PickVisualMediaRequest> pick =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onPhoto);

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        String uid = uidOrFinish();
        if (uid == null) return;
        b = ActivityEditProfileBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, false);
        InsetsUtil.padding(b.bottom, false, true);
        b.bar.title.setText(R.string.edit_profile);
        b.bar.back.setOnClickListener(v -> finish());
        for (String d : OnboardingActivity.DIETS) b.diet.addView(chip(d));
        for (String a : OnboardingActivity.ALLERGENS) b.allergies.addView(chip(a));
        String[] acts = new String[OnboardingActivity.ACTIVITIES.length];
        for (int i = 0; i < acts.length; i++) {
            acts[i] = getString(OnboardingActivity.ACTIVITY_TEXT[i][0]) + " · " + getString(OnboardingActivity.ACTIVITY_TEXT[i][1]);
        }
        b.activity.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, acts));
        b.activity.setOnItemClickListener((p, v, pos, id) -> activityIndex = pos);
        b.units.setOnSelectListener(i -> convertUnits(i == 1));
        b.photoBox.setOnClickListener(v -> pick.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        b.removePhoto.setOnClickListener(v -> {
            newPhoto = null;
            removePhoto = true;
            renderPhoto();
        });
        b.birth.setOnClickListener(v -> ProfileForm.pickBirthDate(this, birthDate, d -> {
            birthDate = d;
            b.birth.setText(ProfileForm.formatDate(d));
            b.birthLayout.setError(ProfileForm.birthError(this, d));
        }));
        TextWatcher bmi = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int n, int x) { }
            @Override public void onTextChanged(CharSequence c, int a, int n, int x) { }

            @Override
            public void afterTextChanged(Editable e) {
                ProfileForm.bindBmi(b.bmi, heightCm(), kg(num(b.weight)));
            }
        };
        b.heightCm.addTextChangedListener(bmi);
        b.heightFt.addTextChangedListener(bmi);
        b.heightIn.addTextChangedListener(bmi);
        b.weight.addTextChangedListener(bmi);
        Ui.onClick(b.save, v -> save());
        if (s != null) {
            birthDate = s.getString("birth");
            removePhoto = s.getBoolean("removePhoto");
            String ph = s.getString("photo");
            if (ph != null && new File(ph).isFile()) newPhoto = new File(ph);
        }

        graph().profile.observe(uid).observe(this, p -> {
            if (p == null || loaded) return; // fill once; later syncs don't overwrite what is being edited
            loaded = true;
            profile = p;
            username = new ProfileForm.UsernameWatcher(b.usernameLayout, b.username, p.username);
            if (s == null) fill(p);
            renderPhoto();
            renderUsernameLock();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("birth", birthDate);
        out.putBoolean("removePhoto", removePhoto);
        if (newPhoto != null) out.putString("photo", newPhoto.getAbsolutePath());
    }

    private Chip chip(String text) {
        Chip c = new Chip(this);
        c.setText(text);
        c.setCheckable(true);
        return c;
    }

    private void fill(ProfileEntity p) {
        b.name.setText(p.name);
        if (p.username != null) b.username.setText(p.username);
        birthDate = p.birthDate;
        b.birth.setText(ProfileForm.formatDate(birthDate));
        b.sex.select("female".equals(p.sex) ? 0 : "male".equals(p.sex) ? 1 : 2, false);
        boolean imp = Units.imperial(p.units);
        b.units.select(imp ? 1 : 0, false);
        applyUnitViews(imp);
        if (p.heightCm > 0) {
            if (imp) {
                int totalIn = (int) Math.round(p.heightCm / Units.CM_PER_IN);
                b.heightFt.setText(String.valueOf(totalIn / 12));
                b.heightIn.setText(String.valueOf(totalIn % 12));
            } else b.heightCm.setText(Units.num(Math.round(p.heightCm)));
        }
        if (p.weightKg > 0) b.weight.setText(Units.weightNumber(p.weightKg, p.units));
        if (p.targetWeightKg > 0) b.target.setText(Units.weightNumber(p.targetWeightKg, p.units));
        b.goal.select("lose".equals(p.goal) ? 0 : "gain".equals(p.goal) ? 2 : 1, false);
        int act = Arrays.asList(OnboardingActivity.ACTIVITIES).indexOf(p.activityLevel);
        activityIndex = act < 0 ? 1 : act;
        b.activity.setText(b.activity.getAdapter().getItem(activityIndex).toString(), false);
        OnboardingActivity.checkChips(b.diet, p.dietPrefs);
        List<String> other = OnboardingActivity.checkChips(b.allergies, p.allergies);
        b.otherAllergies.setText(String.join(", ", other));
        ProfileForm.bindBmi(b.bmi, p.heightCm, p.weightKg);
    }

    private void renderUsernameLock() {
        long days = ProfileCards.daysUntilChange(profile);
        if (profile != null && profile.username != null && days > 0) {
            b.usernameLayout.setEnabled(false);
            b.usernameLayout.setHelperText(getResources().getQuantityString(R.plurals.username_locked, (int) days, (int) days));
        } else {
            b.usernameLayout.setEnabled(true);
        }
    }

    // ------------------------------------------------------------------ photo
    private void onPhoto(@Nullable Uri uri) {
        if (uri == null) return;
        graph().runIo(() -> {
            try {
                File out = new File(ImageUtils.mealImageDir(this), "avatar_" + System.currentTimeMillis() + ".jpg");
                ImageUtils.process(this, uri, out);
                graph().main.post(() -> {
                    newPhoto = out;
                    removePhoto = false;
                    renderPhoto();
                });
            } catch (Exception e) {
                graph().main.post(() -> Ui.snack(b.getRoot(), getString(R.string.cam_error)));
            }
        });
    }

    private void renderPhoto() {
        b.photo.setCircle();
        boolean has;
        if (newPhoto != null) {
            Glide.with(this).load(newPhoto).centerCrop().into(b.photo);
            has = true;
        } else if (!removePhoto && profile != null && profile.avatarLocalPath != null) {
            Avatars.mine(b.photo, profile);
            has = true;
        } else {
            Avatars.initial(b.photo, profile == null ? null : profile.name);
            has = false;
        }
        b.removePhoto.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ units
    private static double num(EditText e) {
        return Ui.parse(e.getText());
    }

    private boolean imperial() {
        return b.units.getSelected() == 1;
    }

    private void applyUnitViews(boolean imp) {
        b.heightCmLayout.setVisibility(imp ? View.GONE : View.VISIBLE);
        b.heightImperial.setVisibility(imp ? View.VISIBLE : View.GONE);
        String u = imp ? "lb" : "kg";
        b.weightLayout.setHint(getString(R.string.ob_weight, u));
        b.targetLayout.setHint(getString(R.string.ob_target, u));
    }

    /** Switching units converts what is typed, so nothing is lost. */
    private void convertUnits(boolean toImperial) {
        double h = heightCmIn(!toImperial), w = num(b.weight), t = num(b.target);
        applyUnitViews(toImperial);
        if (!Double.isNaN(h)) {
            if (toImperial) {
                int totalIn = (int) Math.round(h / Units.CM_PER_IN);
                b.heightFt.setText(String.valueOf(totalIn / 12));
                b.heightIn.setText(String.valueOf(totalIn % 12));
            } else b.heightCm.setText(Units.num(Math.round(h)));
        }
        if (!Double.isNaN(w)) b.weight.setText(Units.num(Math.round((toImperial ? Units.kgToLb(w) : Units.lbToKg(w)) * 10) / 10.0));
        if (!Double.isNaN(t)) b.target.setText(Units.num(Math.round((toImperial ? Units.kgToLb(t) : Units.lbToKg(t)) * 10) / 10.0));
    }

    private double kg(double v) {
        return imperial() ? Units.lbToKg(v) : v;
    }

    private double heightCm() {
        return heightCmIn(imperial());
    }

    private double heightCmIn(boolean imp) {
        if (imp) {
            double ft = num(b.heightFt), in = num(b.heightIn);
            if (Double.isNaN(ft)) return Double.NaN;
            return (ft * 12 + (Double.isNaN(in) ? 0 : in)) * Units.CM_PER_IN;
        }
        return num(b.heightCm);
    }

    // ------------------------------------------------------------------ save
    private boolean validate() {
        boolean ok = true;
        String name = text(b.name);
        b.nameLayout.setError(name.length() < 2 ? getString(R.string.err_name) : null);
        ok &= name.length() >= 2;
        String birthErr = ProfileForm.birthError(this, birthDate);
        b.birthLayout.setError(birthErr);
        ok &= birthErr == null;
        double h = heightCm();
        boolean hOk = !Double.isNaN(h) && h >= 120 && h <= 230;
        (imperial() ? b.heightFtLayout : b.heightCmLayout).setError(hOk ? null : getString(R.string.err_height));
        ok &= hOk;
        double w = kg(num(b.weight)), t = kg(num(b.target));
        boolean wOk = !Double.isNaN(w) && w >= 30 && w <= 300, tOk = !Double.isNaN(t) && t >= 30 && t <= 300;
        b.weightLayout.setError(wOk ? null : getString(R.string.err_weight));
        b.targetLayout.setError(tOk ? null : getString(R.string.err_weight));
        ok &= wOk && tOk;
        String u = username.name();
        if (b.usernameLayout.isEnabled() && !u.isEmpty() && !ProfileCards.USERNAME.matcher(u).matches()) {
            b.usernameLayout.setError(getString(R.string.username_invalid));
            ok = false;
        } else if (username.state() == ProfileCards.Check.TAKEN) {
            b.usernameLayout.setError(getString(R.string.username_taken));
            ok = false;
        }
        return ok;
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private void save() {
        if (profile == null || saving) return;
        Ui.hideKeyboard(this);
        if (!validate()) {
            Ui.snack(b.getRoot(), getString(R.string.ep_fix_errors));
            return;
        }
        ProfileEntity p = profile;
        String goal = b.goal.getSelected() == 0 ? "lose" : b.goal.getSelected() == 2 ? "gain" : "maintain";
        String sex = b.sex.getSelected() == 0 ? "female" : b.sex.getSelected() == 1 ? "male" : "unspecified";
        double h = heightCm(), w = Math.round(kg(num(b.weight)) * 10) / 10.0, t = Math.round(kg(num(b.target)) * 10) / 10.0;
        String activity = OnboardingActivity.ACTIVITIES[activityIndex];
        boolean energyChanged = Math.abs(h - p.heightCm) > 0.5 || Math.abs(w - p.weightKg) > 0.05 || !goal.equals(p.goal)
                || !activity.equals(p.activityLevel) || !sex.equals(p.sex) || !String.valueOf(birthDate).equals(String.valueOf(p.birthDate));
        boolean weightChanged = Math.abs(w - p.weightKg) > 0.05;

        p.name = text(b.name);
        p.birthDate = birthDate;
        if (birthDate != null) p.birthYear = java.time.LocalDate.parse(birthDate).getYear();
        p.sex = sex;
        p.units = imperial() ? "imperial" : "metric";
        p.heightCm = h;
        p.weightKg = w;
        p.targetWeightKg = t;
        p.goal = goal;
        p.activityLevel = activity;
        p.dietPrefs = OnboardingActivity.chipsCsv(b.diet, null);
        p.allergies = OnboardingActivity.chipsCsv(b.allergies, text(b.otherAllergies));
        if (removePhoto) {
            p.avatarLocalPath = null;
            p.avatarRemotePath = null;
        }
        saving = true;
        b.save.setEnabled(false);
        String newName = username.name();
        boolean claim = b.usernameLayout.isEnabled() && !newName.isEmpty() && !newName.equals(p.username);
        graph().profile.save(p, r -> {
            if (weightChanged) graph().weights.save(null, DateUtil.today(), w, null);
            if (newPhoto != null) graph().profile.setAvatar(newPhoto.getAbsolutePath(), null);
            graph().runIo(() -> {
                try {
                    graph().cards.refreshCardBlocking(); // new name for friends
                } catch (Exception ignored) {
                    // offline: refreshed next time
                }
            });
            if (claim) {
                graph().cards.claim(newName, c -> {
                    if (!c.ok()) {
                        saving = false;
                        b.save.setEnabled(true);
                        b.usernameLayout.setError(c.error);
                        Ui.snack(b.getRoot(), getString(R.string.ep_saved_but_username));
                        return;
                    }
                    afterSave(p, energyChanged);
                });
            } else {
                afterSave(p, energyChanged);
            }
        });
    }

    /** Offers new daily targets when the inputs to the estimate changed. */
    private void afterSave(ProfileEntity p, boolean energyChanged) {
        if (!energyChanged) {
            Ui.snack(b.getRoot(), getString(R.string.saved));
            finish();
            return;
        }
        GoalCalculator.Input in = new GoalCalculator.Input();
        in.sex = p.sex;
        in.age = Bmi.age(p.birthDate, DateUtil.todayDate());
        in.heightCm = p.heightCm;
        in.weightKg = p.weightKg;
        in.targetWeightKg = p.targetWeightKg;
        in.activity = p.activityLevel;
        in.goal = p.goal;
        if (!GoalCalculator.validate(in).isEmpty()) {
            finish();
            return;
        }
        GoalCalculator.Plan plan = GoalCalculator.calculate(in);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.ep_update_targets_q)
                .setMessage(getString(R.string.ep_update_targets_body, Ui.kcal(plan.kcal), plan.proteinG, plan.carbsG, plan.fatG))
                .setPositiveButton(R.string.ep_update_targets, (d, w) -> {
                    GoalEntity g = new GoalEntity();
                    g.kcal = plan.kcal;
                    g.proteinG = plan.proteinG;
                    g.carbsG = plan.carbsG;
                    g.fatG = plan.fatG;
                    g.fibreG = plan.fibreG;
                    g.waterMl = plan.waterMl;
                    g.method = "mifflin_st_jeor";
                    graph().profile.saveProfileAndGoal(p, g, r -> finish());
                })
                .setNegativeButton(R.string.ep_keep_targets, (d, w) -> finish())
                .setCancelable(false).show();
    }

    @Override
    protected void onDestroy() {
        if (username != null) username.release();
        super.onDestroy();
    }
}

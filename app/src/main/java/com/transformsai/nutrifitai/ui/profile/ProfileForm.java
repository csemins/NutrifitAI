package com.transformsai.nutrifitai.ui.profile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ViewBmiCardBinding;
import com.transformsai.nutrifitai.domain.Bmi;
import com.transformsai.nutrifitai.domain.GoalCalculator;
import com.transformsai.nutrifitai.social.ProfileCards;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.function.Consumer;

/** Pieces shared by signup and Edit profile: birth date picker, live BMI card and the username availability check. */
public final class ProfileForm {
    private ProfileForm() { }

    // ------------------------------------------------------------------ birth date
    public static String formatDate(@Nullable String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            return LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
        } catch (Exception e) {
            return iso;
        }
    }

    /** Material date picker limited to past dates, opening on the current value (or 25 years ago). */
    public static void pickBirthDate(FragmentActivity a, @Nullable String current, Consumer<String> onPicked) {
        LocalDate start;
        try {
            start = current == null || current.isEmpty() ? LocalDate.now().minusYears(25) : LocalDate.parse(current);
        } catch (Exception e) {
            start = LocalDate.now().minusYears(25);
        }
        long sel = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        CalendarConstraints cc = new CalendarConstraints.Builder()
                .setEnd(MaterialDatePicker.todayInUtcMilliseconds())
                .setOpenAt(sel)
                .setValidator(DateValidatorPointBackward.now()).build();
        MaterialDatePicker<Long> p = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.ob_birth_date)
                .setInputMode(MaterialDatePicker.INPUT_MODE_CALENDAR)
                .setSelection(sel)
                .setCalendarConstraints(cc).build();
        p.addOnPositiveButtonClickListener(ms -> onPicked.accept(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()));
        p.show(a.getSupportFragmentManager(), "birth");
    }

    /** Error text for a birth date, or null when the age is within the supported range. */
    @Nullable
    public static String birthError(Context c, @Nullable String iso) {
        int age = Bmi.age(iso, LocalDate.now());
        if (age < 0) return c.getString(R.string.err_birth);
        if (age < GoalCalculator.MIN_AGE || age > GoalCalculator.MAX_AGE) return c.getString(R.string.err_age);
        return null;
    }

    // ------------------------------------------------------------------ BMI
    /** Shows BMI for the given height and weight (hidden while either is missing or implausible). */
    public static void bindBmi(ViewBmiCardBinding v, double heightCm, double weightKg) {
        double bmi = Bmi.of(heightCm, weightKg);
        boolean show = !Double.isNaN(bmi);
        if (show && v.bmiCard.getVisibility() != View.VISIBLE) {
            v.bmiCard.setAlpha(0f);
            v.bmiCard.setVisibility(View.VISIBLE);
            v.bmiCard.animate().alpha(1f).setDuration(250).start();
        } else if (!show) {
            v.bmiCard.setVisibility(View.GONE);
        }
        if (!show) return;
        Context c = v.getRoot().getContext();
        Bmi.Category cat = Bmi.category(bmi);
        int label, color;
        switch (cat) {
            case UNDERWEIGHT:
                label = R.string.bmi_under;
                color = R.color.nl_water;
                break;
            case HEALTHY:
                label = R.string.bmi_healthy;
                color = R.color.nl_lime;
                break;
            case OVERWEIGHT:
                label = R.string.bmi_over;
                color = R.color.nl_carbs;
                break;
            default:
                label = R.string.bmi_obese;
                color = R.color.nl_protein;
        }
        v.bmiValue.setText(String.format(java.util.Locale.getDefault(), "%.1f", bmi));
        v.bmiValue.setTextColor(ContextCompat.getColor(c, color));
        v.bmiCategory.setText(label);
        v.bmiScale.setValue(bmi);
        v.bmiCard.setContentDescription(c.getString(R.string.cd_bmi, v.bmiValue.getText(), c.getString(label)));
    }

    // ------------------------------------------------------------------ username
    /**
     * Live availability check while typing (debounced). {@code mine} is the user's current username, which is
     * always "available" to them.
     */
    public static final class UsernameWatcher implements TextWatcher {
        private final TextInputLayout layout;
        private final Handler main = new Handler(Looper.getMainLooper());
        @Nullable private final String mine;
        private ProfileCards.Check last = ProfileCards.Check.INVALID;
        private String checked = "";
        private final Runnable run = this::check;

        public UsernameWatcher(TextInputLayout layout, TextInputEditText field, @Nullable String mine) {
            this.layout = layout;
            this.mine = mine;
            field.addTextChangedListener(this);
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int n, int x) { }
        @Override public void onTextChanged(CharSequence s, int a, int n, int x) { }

        @Override
        public void afterTextChanged(Editable e) {
            main.removeCallbacks(run);
            if (!layout.isEnabled()) return;
            layout.setError(null);
            layout.setHelperText(layout.getContext().getString(R.string.username_help));
            last = ProfileCards.Check.INVALID;
            main.postDelayed(run, 450);
        }

        private String value() {
            return ProfileCards.normalize(layout.getEditText() == null || layout.getEditText().getText() == null ? ""
                    : layout.getEditText().getText().toString());
        }

        private void check() {
            String name = value();
            Context c = layout.getContext();
            if (name.isEmpty() || !layout.isEnabled()) return; // locked: keep the "change again in N days" note
            if (!ProfileCards.USERNAME.matcher(name).matches()) {
                layout.setError(c.getString(R.string.username_invalid));
                return;
            }
            if (name.equals(mine)) {
                last = ProfileCards.Check.OK;
                layout.setHelperText(c.getString(R.string.username_current));
                return;
            }
            layout.setHelperText(c.getString(R.string.username_checking));
            App.graph().cards.check(name, r -> {
                if (!name.equals(value())) return; // typed on meanwhile
                checked = name;
                last = r.value == null ? ProfileCards.Check.OFFLINE : r.value;
                switch (last) {
                    case OK:
                        layout.setHelperText(c.getString(R.string.username_free, name));
                        break;
                    case TAKEN:
                        layout.setError(c.getString(R.string.username_taken));
                        break;
                    case OFFLINE:
                        layout.setHelperText(c.getString(R.string.username_offline));
                        break;
                    default:
                        layout.setError(c.getString(R.string.username_invalid));
                }
            });
        }

        /** The normalized name, or "" when nothing was entered. */
        public String name() {
            return value();
        }

        /** Last known result for the current text (INVALID until checked). */
        public ProfileCards.Check state() {
            return value().equals(checked) || value().equals(mine) ? last : ProfileCards.Check.INVALID;
        }

        public void release() {
            main.removeCallbacks(run);
        }
    }
}

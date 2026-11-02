package com.transformsai.nutrifitai.ui.analysis;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityVoiceLogBinding;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.common.VoiceInput;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.ui.common.AiAccessSheet;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.LocalTime;
import java.util.ArrayList;

/**
 * Voice (Android SpeechRecognizer, English or Bangla) or typed meal description. The transcript is
 * always editable and goes through the same validated AI analysis + confirmation as photos.
 * The recognizer is destroyed in onStop so the microphone is never held in the background.
 */
public class VoiceLogActivity extends BaseActivity {
    private ActivityVoiceLogBinding b;
    private VoiceInput voice;
    private boolean listening;
    private boolean askedOnce;
    private ObjectAnimator pulse;
    private String meal;

    private final ActivityResultLauncher<String> micPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                askedOnce = true;
                if (granted) startListening();
                else denied();
            });

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityVoiceLogBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        AiAccessSheet.attach(this);
        InsetsUtil.padding(b.root, true, true);
        b.bar.title.setText(R.string.voice_title);
        b.bar.back.setOnClickListener(v -> finish());
        meal = s != null ? s.getString("meal") : null;
        if (meal == null) meal = MealLabels.forHour(LocalTime.now().getHour());
        askedOnce = s != null && s.getBoolean("asked");
        b.lang.select("bn-BD".equals(graph().prefs.speechLanguage()) ? 1 : 0, false);
        b.lang.setOnSelectListener(i -> graph().prefs.setSpeechLanguage(i == 1 ? "bn-BD" : "en-US"));
        b.mealChip.setText(MealLabels.label(this, meal));
        b.mealChip.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle(R.string.choose_meal)
                .setItems(getResources().getStringArray(R.array.meal_types), (d, w) -> {
                    meal = MealLabels.TYPES[w];
                    b.mealChip.setText(MealLabels.label(this, meal));
                }).show());
        b.mic.setOnClickListener(v -> toggle());
        Ui.onClick(b.analyse, v -> analyse());
        if (!VoiceInput.available(this)) {
            b.mic.setEnabled(false);
            b.mic.setAlpha(0.4f);
            b.status.setText(R.string.voice_unavailable);
            b.statusSub.setVisibility(View.GONE);
        }
        pulse = ObjectAnimator.ofPropertyValuesHolder(b.pulse,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1.15f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1.15f));
        pulse.setDuration(900);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("meal", meal);
        out.putBoolean("asked", askedOnce);
    }

    private void toggle() {
        if (listening) {
            stopListening();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else if (askedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            denied();
        } else {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.voice_title).setMessage(R.string.voice_perm)
                    .setPositiveButton(R.string.next, (d, w) -> micPermission.launch(Manifest.permission.RECORD_AUDIO))
                    .setNegativeButton(R.string.cancel, null).show();
        }
    }

    private void denied() {
        b.status.setText(R.string.voice_perm_denied);
        Ui.snack(b.getRoot(), getString(R.string.voice_perm_denied)).setAction(R.string.open_settings, v -> Ui.openAppSettings(this));
        b.text.requestFocus();
    }

    private void startListening() {
        if (voice == null) voice = new VoiceInput(this, voiceCb);
        voice.start(graph().prefs.speechLanguage());
    }

    private void stopListening() {
        if (voice != null) voice.stop();
    }

    private final VoiceInput.Callback voiceCb = new VoiceInput.Callback() {
        @Override
        public void onListening(boolean on) {
            listening = on;
            if (b == null) return;
            b.mic.setActivated(on);
            b.mic.setContentDescription(getString(on ? R.string.cd_mic_stop : R.string.cd_mic));
            if (on) {
                b.status.setText(R.string.voice_listening);
                if (ValueAnimator.areAnimatorsEnabled()) pulse.start();
            } else {
                pulse.cancel();
                b.pulse.setScaleX(1f);
                b.pulse.setScaleY(1f);
            }
        }

        @Override
        public void onPartial(String text) {
            if (b != null) b.status.setText(text);
        }

        @Override
        public void onResult(String text) {
            if (b == null) return;
            String prev = b.text.getText() == null ? "" : b.text.getText().toString().trim();
            String joined = prev.isEmpty() ? text : prev + ", " + text;
            b.text.setText(joined.length() > 500 ? joined.substring(0, 500) : joined);
            b.text.setSelection(b.text.getText().length());
            b.status.setText(R.string.voice_tap);
        }

        @Override
        public void onError(int message) {
            if (b != null) b.status.setText(message);
        }
    };

    private void analyse() {
        String t = b.text.getText() == null ? "" : b.text.getText().toString().trim();
        if (t.length() < 3) {
            b.textLayout.setError(getString(R.string.err_text_short));
            return;
        }
        b.textLayout.setError(null);
        stopListening();
        b.analyse.setEnabled(false);
        graph().analysis.createText(t, meal, DateUtil.today(), r -> {
            startActivity(AnalysisActivity.intent(this, r.value));
            finish();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (voice != null) voice.release(); // release the microphone
    }
}

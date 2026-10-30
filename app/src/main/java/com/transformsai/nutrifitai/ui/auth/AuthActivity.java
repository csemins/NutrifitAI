package com.transformsai.nutrifitai.ui.auth;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.lifecycle.ViewModelProvider;

import com.transformsai.nutrifitai.BuildConfig;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.Prefs;
import com.transformsai.nutrifitai.databinding.ActivityAuthBinding;
import com.transformsai.nutrifitai.databinding.SheetResetBinding;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.main.MainActivity;
import com.transformsai.nutrifitai.ui.onboarding.OnboardingActivity;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Email/password sign-in & sign-up, password reset, and Google sign-in via Credential Manager. */
public class AuthActivity extends BaseActivity {
    public static final int MODE_SIGN_IN = 0, MODE_SIGN_UP = 1;
    private static final String EXTRA_MODE = "mode";

    public static Intent forMode(Context c, int mode) {
        return new Intent(c, AuthActivity.class).putExtra(EXTRA_MODE, mode);
    }

    private ActivityAuthBinding b;
    private AuthViewModel vm;
    private CancellationSignal googleCancel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityAuthBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.content, true, false);
        InsetsUtil.bottomMargin(b.scroll); // shrink the viewport above the keyboard so the focused field scrolls into view
        vm = new ViewModelProvider(this).get(AuthViewModel.class);
        if (savedInstanceState == null) vm.setMode(getIntent().getIntExtra(EXTRA_MODE, MODE_SIGN_IN));
        b.back.setOnClickListener(v -> finish());

        b.mode.select(vm.mode(), false);
        applyMode(vm.mode());
        b.mode.setOnSelectListener(i -> {
            vm.setMode(i);
            applyMode(i);
        });
        Ui.onClick(b.primary, v -> submit());
        Ui.onClick(b.google, v -> google());
        b.forgot.setOnClickListener(v -> showReset());
        b.password.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE && vm.mode() == MODE_SIGN_IN) {
                submit();
                return true;
            }
            return false;
        });
        b.confirm.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
        if (BuildConfig.ALLOW_EMULATORS) {
            b.logo.setOnLongClickListener(v -> {
                showDeveloperDialog();
                return true;
            });
        }
        vm.state().observe(this, this::render);
    }

    private void applyMode(int mode) {
        boolean signUp = mode == MODE_SIGN_UP;
        b.title.setText(signUp ? R.string.auth_title_signup : R.string.auth_title_signin);
        b.subtitle.setText(signUp ? R.string.auth_sub_signup : R.string.auth_sub_signin);
        b.nameLayout.setVisibility(signUp ? View.VISIBLE : View.GONE);
        b.confirmLayout.setVisibility(signUp ? View.VISIBLE : View.GONE);
        b.forgot.setVisibility(signUp ? View.GONE : View.VISIBLE);
        b.primary.setText(signUp ? R.string.action_signup : R.string.action_signin);
        b.password.setImeOptions(signUp ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        b.nameLayout.setError(null);
        b.emailLayout.setError(null);
        b.passwordLayout.setError(null);
        b.confirmLayout.setError(null);
    }

    private void render(AuthViewModel.State s) {
        b.progress.setVisibility(s.busy ? View.VISIBLE : View.GONE);
        b.primary.setEnabled(!s.busy);
        b.primary.setTextScaleX(s.busy ? 0f : 1f);
        b.google.setEnabled(!s.busy);
        b.mode.setEnabled(!s.busy);
        b.error.setVisibility(s.error == null ? View.GONE : View.VISIBLE);
        b.error.setText(s.error);
        if (s.onboarded != null) {
            Intent i = new Intent(this, s.onboarded ? MainActivity.class : OnboardingActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        }
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    static boolean validEmail(String e) {
        return !TextUtils.isEmpty(e) && Patterns.EMAIL_ADDRESS.matcher(e).matches();
    }

    static boolean strongEnough(String p) {
        return p.length() >= 8 && p.matches(".*[A-Za-z].*") && p.matches(".*\\d.*");
    }

    private void submit() {
        if (vm.isBusy()) return;
        Ui.hideKeyboard(this);
        boolean signUp = vm.mode() == MODE_SIGN_UP;
        String name = text(b.name);
        String email = text(b.email);
        String pw = b.password.getText() == null ? "" : b.password.getText().toString();
        boolean ok = true;
        if (signUp && (name.length() < 2 || name.length() > 40)) {
            b.nameLayout.setError(getString(R.string.err_name));
            ok = false;
        } else b.nameLayout.setError(null);
        if (!validEmail(email)) {
            b.emailLayout.setError(getString(R.string.err_email));
            ok = false;
        } else b.emailLayout.setError(null);
        if (signUp ? !strongEnough(pw) : pw.isEmpty()) {
            b.passwordLayout.setError(getString(R.string.err_password_short));
            ok = false;
        } else b.passwordLayout.setError(null);
        if (signUp && !pw.equals(b.confirm.getText() == null ? "" : b.confirm.getText().toString())) {
            b.confirmLayout.setError(getString(R.string.err_password_match));
            ok = false;
        } else b.confirmLayout.setError(null);
        if (!ok) return;
        if (signUp) vm.signUp(name, email, pw);
        else vm.signIn(email, pw);
    }

    private void google() {
        if (vm.isBusy()) return;
        String clientId;
        try {
            clientId = getString(R.string.default_web_client_id);
        } catch (Exception e) {
            vm.error(getString(R.string.err_google_config));
            return;
        }
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(clientId).build();
        GetCredentialRequest req = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager cm = CredentialManager.create(this);
        googleCancel = new CancellationSignal();
        cm.getCredentialAsync(this, req, googleCancel, ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse r) {
                        if (r.getCredential() instanceof CustomCredential
                                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(r.getCredential().getType())) {
                            GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(r.getCredential().getData());
                            vm.google(g.getIdToken());
                        } else {
                            vm.error(getString(R.string.err_google_unavailable));
                        }
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) return;
                        if (e instanceof NoCredentialException) vm.error(getString(R.string.err_google_unavailable));
                        else vm.error(getString(R.string.err_google_config));
                    }
                });
    }

    private void showReset() {
        BottomSheetDialog d = new BottomSheetDialog(this);
        SheetResetBinding sb = SheetResetBinding.inflate(getLayoutInflater());
        sb.email.setText(text(b.email));
        d.setContentView(sb.getRoot());
        Ui.onClick(sb.send, v -> {
            String email = text(sb.email);
            if (!validEmail(email)) {
                sb.emailLayout.setError(getString(R.string.err_email));
                return;
            }
            sb.send.setEnabled(false);
            graph().auth.sendPasswordReset(email, r -> {
                sb.send.setEnabled(true);
                if (r.ok()) {
                    d.dismiss();
                    Ui.snack(b.getRoot(), getString(R.string.reset_sent, email));
                } else sb.emailLayout.setError(r.error);
            });
        });
        d.show();
    }

    /** Debug builds only: point Firebase at the local Emulator Suite and choose the AI proxy URL. */
    private void showDeveloperDialog() {
        Prefs prefs = graph().prefs;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setText(R.string.dev_emulators);
        sw.setChecked(prefs.useEmulators());
        EditText url = new EditText(this);
        url.setHint(R.string.dev_ai_url);
        url.setText(prefs.aiBaseUrl());
        box.addView(sw);
        box.addView(url);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Developer")
                .setMessage(R.string.dev_emulators_body)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    prefs.setAiBaseUrl(url.getText().toString());
                    boolean changed = sw.isChecked() != prefs.useEmulators();
                    prefs.setUseEmulators(sw.isChecked());
                    if (changed) restart();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void restart() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        }
        Runtime.getRuntime().exit(0);
    }

    @Override
    protected void onDestroy() {
        if (googleCancel != null) googleCancel.cancel();
        super.onDestroy();
    }
}

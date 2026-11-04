package com.transformsai.nutrifitai.ui.settings;

import android.app.Activity;
import android.os.CancellationSignal;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.function.Consumer;

/** Fresh Google ID token for re-authentication before account deletion. */
final class GoogleReauth {
    private GoogleReauth() {
    }

    static void request(Activity a, View root, Consumer<String> onToken) {
        GetSignInWithGoogleOption opt = new GetSignInWithGoogleOption.Builder(a.getString(R.string.default_web_client_id)).build();
        GetCredentialRequest req = new GetCredentialRequest.Builder().addCredentialOption(opt).build();
        CredentialManager.create(a).getCredentialAsync(a, req, new CancellationSignal(),
                ContextCompat.getMainExecutor(a), new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse r) {
                        if (r.getCredential() instanceof CustomCredential) {
                            onToken.accept(GoogleIdTokenCredential.createFrom(r.getCredential().getData()).getIdToken());
                        }
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        if (!a.isFinishing()) Ui.snack(root, a.getString(R.string.err_google_config));
                    }
                });
    }
}

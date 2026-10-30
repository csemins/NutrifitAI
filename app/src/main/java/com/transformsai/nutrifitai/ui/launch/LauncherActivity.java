package com.transformsai.nutrifitai.ui.launch;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.ui.auth.WelcomeActivity;
import com.transformsai.nutrifitai.ui.main.MainActivity;
import com.transformsai.nutrifitai.ui.onboarding.OnboardingActivity;

/** Splash + routing: signed out -> Welcome, not onboarded -> Onboarding, else Main. */
public class LauncherActivity extends AppCompatActivity {
    private volatile boolean ready;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        splash.setKeepOnScreenCondition(() -> !ready);
        String uid = App.graph().session.uid();
        if (uid == null) {
            go(new Intent(this, WelcomeActivity.class));
            return;
        }
        App.graph().runIo(() -> {
            ProfileEntity p = App.graph().db.profiles().get(uid);
            boolean onboarded = p != null && p.onboarded;
            runOnUiThread(() -> go(new Intent(this, onboarded ? MainActivity.class : OnboardingActivity.class)));
        });
    }

    private void go(Intent i) {
        ready = true;
        startActivity(i);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}

package com.transformsai.nutrifitai.ui.common;

import android.graphics.Color;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;

/** Edge-to-edge; system bar icons follow the light/dark theme. Screens apply insets themselves. */
public abstract class BaseActivity extends AppCompatActivity {
    /**
     * Dark-only screens pick night mode before the base context is attached, so they get their own night
     * resources instead of switching (and possibly updating shared resources) after creation.
     */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        if (alwaysDark()) getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SystemBarStyle bars = alwaysDark() ? SystemBarStyle.dark(Color.TRANSPARENT)
                : SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT);
        EdgeToEdge.enable(this, bars, bars);
        super.onCreate(savedInstanceState);
    }

    /** Camera screens stay dark in the light theme: the preview and overlays are designed for it. */
    protected boolean alwaysDark() {
        return false;
    }

    protected AppGraph graph() {
        return App.graph();
    }

    /** Signed-in uid; screens behind the auth gate finish if the session disappeared. */
    @Nullable
    protected String uidOrFinish() {
        String uid = App.graph().session.uid();
        if (uid == null) {
            startActivity(new android.content.Intent(this, com.transformsai.nutrifitai.ui.launch.LauncherActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        }
        return uid;
    }
}

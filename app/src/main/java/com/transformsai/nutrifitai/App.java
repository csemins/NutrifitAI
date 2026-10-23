package com.transformsai.nutrifitai;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.remote.FirebaseConfig;
import com.transformsai.nutrifitai.reminders.Notifications;

/** Application entry point: wires Firebase, notification channels and the dependency graph. */
public class App extends Application {
    private static App instance;
    private AppGraph graph;

    public static App get() {
        return instance;
    }

    public static AppGraph graph() {
        return instance.graph;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        FirebaseConfig.init(this);
        Notifications.createChannels(this);
        graph = new AppGraph(this);
        applyTheme(graph.prefs.theme());
        graph.start();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                graph.onForeground();
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                graph.onBackground();
            }
        });
    }

    /** Light, dark or follow the system; recreates open activities when it changes. */
    public static void applyTheme(String theme) {
        AppCompatDelegate.setDefaultNightMode("light".equals(theme) ? AppCompatDelegate.MODE_NIGHT_NO
                : "dark".equals(theme) ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}

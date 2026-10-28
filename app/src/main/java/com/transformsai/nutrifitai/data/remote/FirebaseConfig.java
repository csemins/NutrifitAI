package com.transformsai.nutrifitai.data.remote;

import android.content.Context;
import android.util.Log;

import com.transformsai.nutrifitai.BuildConfig;
import com.transformsai.nutrifitai.data.Prefs;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.MemoryCacheSettings;

/**
 * Firebase bootstrap. Room is the only offline source of truth, so Firestore's own persistent cache
 * is disabled (memory cache only) to avoid two competing local copies.
 * Debug builds can opt in to the local Emulator Suite (127.0.0.1 via adb reverse).
 */
public final class FirebaseConfig {
    public static final String EMULATOR_HOST = "127.0.0.1";
    private static boolean emulators;

    private FirebaseConfig() {
    }

    public static void init(Context c) {
        FirebaseApp.initializeApp(c);
        FirebaseFirestore fs = FirebaseFirestore.getInstance();
        Prefs prefs = new Prefs(c);
        emulators = prefs.useEmulators();
        if (emulators) {
            try {
                FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, 9099);
                fs.useEmulator(EMULATOR_HOST, 8085);
                database().useEmulator(EMULATOR_HOST, 9000);
                Log.i("NutriFit AI", "Using Firebase Emulator Suite");
            } catch (IllegalStateException e) {
                Log.w("NutriFit AI", "Emulator config skipped: " + e.getMessage());
            }
        }
        fs.setFirestoreSettings(new FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build());
    }

    public static boolean usingEmulators() {
        return emulators;
    }

    /**
     * The one Realtime Database instance. It is created once and kept: FirebaseDatabase.getInstance() does not
     * keep earlier instances alive, so a later call could return a new, unconfigured instance (in debug builds one
     * that ignores the emulator setting and opens a second connection).
     */
    private static volatile FirebaseDatabase rtdb;

    public static FirebaseDatabase database() {
        FirebaseDatabase d = rtdb;
        if (d != null) return d;
        synchronized (FirebaseConfig.class) {
            if (rtdb == null) {
                String url = BuildConfig.RTDB_URL;
                rtdb = url == null || url.isEmpty() ? FirebaseDatabase.getInstance() : FirebaseDatabase.getInstance(url);
            }
            return rtdb;
        }
    }
}

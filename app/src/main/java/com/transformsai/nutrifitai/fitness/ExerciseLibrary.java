package com.transformsai.nutrifitai.fitness;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only exercise reference data from assets: the coached exercises (guided.json) and the full
 * public-domain free-exercise-db library (library.json.gz, ~870 exercises). Loaded once, lazily, off
 * the main thread by callers.
 */
public final class ExerciseLibrary {
    private static volatile ExerciseLibrary instance;

    private final Map<String, Exercise> guided = new LinkedHashMap<>();
    private final Map<String, Exercise> library = new LinkedHashMap<>();

    public static ExerciseLibrary get(Context c) {
        ExerciseLibrary l = instance;
        if (l == null) {
            synchronized (ExerciseLibrary.class) {
                if (instance == null) {
                    ExerciseLibrary x = new ExerciseLibrary();
                    try {
                        x.load(c.getApplicationContext());
                    } catch (IOException e) {
                        android.util.Log.e("NutriFit AI", "Exercise assets unavailable", e); // empty library, no crash
                    }
                    instance = x;
                }
                l = instance;
            }
        }
        return l;
    }

    /** For unit tests. */
    public static ExerciseLibrary fromStreams(InputStream guidedJson, @Nullable InputStream libraryGz) throws IOException {
        ExerciseLibrary x = new ExerciseLibrary();
        x.parse(guidedJson, libraryGz);
        return x;
    }

    private void load(Context c) throws IOException {
        try (InputStream g = c.getAssets().open("exercises/guided.json");
             InputStream l = com.transformsai.nutrifitai.util.AssetFiles.open(c, "exercises/library.json.gz")) {
            parse(g, l);
        }
        try (InputStream m = com.transformsai.nutrifitai.util.AssetFiles.open(c, "exercises/motion.json.gz")) {
            attachMotion(m);
        } catch (IOException ignored) {
            // no motion data: the views show the soft background only
        }
    }

    private void parse(InputStream guidedJson, @Nullable InputStream libraryGz) throws IOException {
        if (libraryGz != null) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(com.transformsai.nutrifitai.util.AssetFiles.maybeGunzip(libraryGz), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("exercises")) {
                JsonObject o = e.getAsJsonObject();
                Exercise x = new Exercise();
                x.id = "lib:" + o.get("id").getAsString();
                x.libraryId = o.get("id").getAsString();
                x.name = o.get("name").getAsString();
                x.level = str(o, "level");
                x.equipment = str(o, "equipment");
                x.category = str(o, "category");
                x.muscles = join(o.getAsJsonArray("primary"));
                for (String m : list(o.getAsJsonArray("primary"))) putTarget(x, Muscles.fromDb(m), 1f);
                if (o.has("secondary")) for (String m : list(o.getAsJsonArray("secondary"))) putTarget(x, Muscles.fromDb(m), 0.45f);
                x.steps = list(o.getAsJsonArray("steps"));
                boolean stretch = "stretching".equals(x.category);
                x.kind = stretch ? Exercise.Kind.HOLD : Exercise.Kind.REPS;
                x.defaultTarget = stretch ? 30 : 10;
                x.role = stretch ? "cooldown" : "main";
                x.met = stretch ? 2.3 : "cardio".equals(x.category) || "plyometrics".equals(x.category) ? 7.0 : 3.5;
                library.put(x.id, x);
            }
        }
        JsonObject g = JsonParser.parseReader(new InputStreamReader(guidedJson, StandardCharsets.UTF_8)).getAsJsonObject();
        for (JsonElement e : g.getAsJsonArray("exercises")) {
            JsonObject o = e.getAsJsonObject();
            Exercise x = new Exercise();
            x.id = o.get("id").getAsString();
            x.name = o.get("name").getAsString();
            x.kind = "hold".equals(o.get("kind").getAsString()) ? Exercise.Kind.HOLD : Exercise.Kind.REPS;
            x.camera = o.get("camera").getAsBoolean();
            x.met = o.get("met").getAsDouble();
            x.defaultTarget = o.get("default").getAsInt();
            x.muscles = o.get("muscles").getAsString();
            x.cues = list(o.getAsJsonArray("cues"));
            x.steps = list(o.getAsJsonArray("steps"));
            x.libraryId = str(o, "db");
            x.trackAs = str(o, "track_as");
            if (o.has("camera_spec") && o.get("camera_spec").isJsonObject()) registerSpec(x.id, o.getAsJsonObject("camera_spec"));
            x.archived = o.has("archived") && o.get("archived").getAsBoolean();
            x.needs = str(o, "needs");
            String role = str(o, "role");
            if (role != null) x.role = role;
            if (o.has("targets") && o.get("targets").isJsonObject()) {
                for (Map.Entry<String, JsonElement> t : o.getAsJsonObject("targets").entrySet()) {
                    putTarget(x, t.getKey(), t.getValue().getAsFloat());
                }
            }
            x.guided = true;
            Exercise lib = x.libraryId == null ? null : library.get("lib:" + x.libraryId);
            if (lib != null) {
                x.level = lib.level;
                x.equipment = x.archived ? x.needs : "body only";
                x.category = lib.category;
            }
            guided.put(x.id, x);
        }
    }

    /** Attaches start/end poses (motion.json.gz) to every exercise that has them. */
    public void attachMotion(InputStream gz) throws IOException {
        JsonObject root = JsonParser.parseReader(new InputStreamReader(com.transformsai.nutrifitai.util.AssetFiles.maybeGunzip(gz), StandardCharsets.UTF_8)).getAsJsonObject();
        for (Exercise x : library.values()) x.motion = poses(root, x.libraryId);
        for (Exercise x : guided.values()) {
            float[][] m = poses(root, "g:" + x.id);
            if (m == null && x.libraryId != null) m = poses(root, x.libraryId);
            x.motion = m;
        }
    }

    @Nullable
    private static float[][] poses(JsonObject root, @Nullable String key) {
        if (key == null || !root.has(key)) return null;
        JsonArray arr = root.getAsJsonArray(key);
        float[][] out = new float[arr.size()][];
        for (int i = 0; i < arr.size(); i++) {
            JsonArray p = arr.get(i).getAsJsonArray();
            out[i] = new float[p.size()];
            for (int j = 0; j < p.size(); j++) out[i][j] = p.get(j).getAsFloat();
        }
        return out.length == 0 ? null : out;
    }

    @Nullable
    public Exercise byId(String id) {
        Exercise x = guided.get(id);
        return x != null ? x : library.get(id);
    }

    /** Coached, instrument-free exercises (archived equipment exercises excluded). */
    public List<Exercise> guided() {
        List<Exercise> out = new ArrayList<>();
        for (Exercise x : guided.values()) if (!x.archived) out.add(x);
        return out;
    }

    /** Exercises that need equipment; kept so old sessions and plans still show their names. */
    public List<Exercise> archived() {
        List<Exercise> out = new ArrayList<>();
        for (Exercise x : guided.values()) if (x.archived) out.add(x);
        return out;
    }

    public List<Exercise> cameraExercises() {
        List<Exercise> out = new ArrayList<>();
        for (Exercise x : guided.values()) if (x.camera && !x.archived) out.add(x);
        return out;
    }

    /** Searches coached exercises first, then the library. filter: all | camera | core | legs | upper | stretching | cardio | archived */
    public List<Exercise> search(String query, String filter, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Exercise> out = new ArrayList<>();
        // the app only offers camera-tracked, equipment-free moves; the reference library is not listed
        List<Exercise> all = "archived".equals(filter) ? archived() : guided();
        for (Exercise x : all) {
            if (out.size() >= limit) break;
            if (!q.isEmpty() && !x.name.toLowerCase(Locale.ROOT).contains(q) && !x.muscles.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (!matches(x, filter)) continue;
            out.add(x);
        }
        return out;
    }

    private static boolean matches(Exercise x, String filter) {
        switch (filter == null ? "all" : filter) {
            case "camera": return x.camera;
            case "core": return hits(x, Muscles.ABS, Muscles.OBLIQUES, Muscles.LOWER_BACK);
            case "legs": return hits(x, Muscles.QUADS, Muscles.GLUTES, Muscles.HAMSTRINGS, Muscles.CALVES);
            case "upper": return hits(x, Muscles.CHEST, Muscles.SHOULDERS, Muscles.TRICEPS, Muscles.BICEPS, Muscles.LATS, Muscles.MIDDLE_BACK);
            case "archived": return x.archived;
            case "strength": return "strength".equals(x.category) || "powerlifting".equals(x.category);
            case "stretching": return "stretching".equals(x.category);
            case "cardio": return "cardio".equals(x.category) || "plyometrics".equals(x.category);
            default: return true;
        }
    }

    /** True when one of the muscles is a main target (weight >= 0.7). */
    private static boolean hits(Exercise x, String... muscles) {
        for (String m : muscles) {
            Float w = x.targets.get(m);
            if (w != null && w >= 0.7f) return true;
        }
        return false;
    }

    /** Camera model from guided.json "camera_spec" (see tools/pose/derive_specs.py). */
    static void registerSpec(String id, JsonObject s) {
        try {
            boolean hold = "hold".equals(s.get("kind").getAsString());
            JsonArray fa = s.getAsJsonArray("features");
            com.transformsai.nutrifitai.fitness.pose.PoseFeatures.F[] f = new com.transformsai.nutrifitai.fitness.pose.PoseFeatures.F[fa.size()];
            for (int i = 0; i < f.length; i++) f[i] = com.transformsai.nutrifitai.fitness.pose.PoseFeatures.F.valueOf(fa.get(i).getAsString());
            double[] a = nums(s.getAsJsonArray(hold ? "centre" : "rest")), b = nums(s.getAsJsonArray(hold ? "tolerance" : "peak"));
            com.transformsai.nutrifitai.fitness.pose.PoseFeatures.F gate = null;
            double gMin = 0, gMax = 0;
            if (s.has("gate")) {
                JsonObject g = s.getAsJsonObject("gate");
                gate = com.transformsai.nutrifitai.fitness.pose.PoseFeatures.F.valueOf(g.get("feature").getAsString());
                gMin = g.get("min").getAsDouble();
                gMax = g.get("max").getAsDouble();
            }
            com.transformsai.nutrifitai.fitness.pose.ExerciseSpec.register(id, hold, f, a, b, gate, gMin, gMax, str(s, "cue"), str(s, "view"));
        } catch (RuntimeException ignored) {
            // malformed spec: the move stays manual-only
        }
    }

    private static double[] nums(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    private static void putTarget(Exercise x, @Nullable String muscle, float w) {
        if (muscle == null || !Muscles.known(muscle)) return;
        Float old = x.targets.get(muscle);
        x.targets.put(muscle, Math.max(old == null ? 0f : old, Math.max(0f, Math.min(1f, w))));
    }

    public int librarySize() {
        return library.size();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static List<String> list(@Nullable JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    private static String join(@Nullable JsonArray a) {
        List<String> l = list(a);
        List<String> cap = new ArrayList<>();
        for (String s : l) cap.add(s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1));
        return String.join(" · ", cap);
    }

    public static List<String> unmodifiable(List<String> l) {
        return Collections.unmodifiableList(l);
    }
}

package com.transformsai.nutrifitai.fitness;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** An ordered list of steps. Built-in programs, AI-generated plans and saved plans share this model. */
public final class WorkoutPlan {
    public static final class Step {
        public String exerciseId;
        /** Target reps (REPS exercises) or seconds (HOLD exercises). */
        public int target;
        public int restSeconds = Programs.REST;
        public String cue = "";
        /** warmup | main | cooldown */
        public String phase = "main";

        public Step() { }

        public Step(String exerciseId, int target, int restSeconds) {
            this.exerciseId = exerciseId;
            this.target = target;
            this.restSeconds = restSeconds;
        }
    }

    public String id;
    public String title;
    public String summary = "";
    /** builtin | ai | user */
    public String source = "builtin";
    public String level = "beginner";
    public String focus = "";
    public final List<Step> steps = new ArrayList<>();

    public WorkoutPlan add(String exerciseId, int target, int rest) {
        steps.add(new Step(exerciseId, target, rest));
        return this;
    }

    /** Adds a step with the default rest. */
    public WorkoutPlan add(String exerciseId, int target) {
        return add(exerciseId, target, Programs.REST);
    }

    /** No rest after the final step. */
    public WorkoutPlan last() {
        if (!steps.isEmpty()) steps.get(steps.size() - 1).restSeconds = 0;
        return this;
    }

    /** Applies the user's rest preference to every step except the last. */
    public void applyRest(int seconds) {
        for (int i = 0; i < steps.size() - 1; i++) steps.get(i).restSeconds = Math.max(5, Math.min(120, seconds));
    }

    /** Estimated duration: ~3 s per rep, hold time, rest, plus a 5 s transition per step. */
    public int estimatedSeconds(ExerciseLibrary lib) {
        int s = 0;
        for (Step st : steps) {
            Exercise x = lib.byId(st.exerciseId);
            s += (x != null && x.isHold() ? st.target : st.target * 3) + st.restSeconds + 5;
        }
        return s;
    }

    public int cameraSteps(ExerciseLibrary lib) {
        int n = 0;
        for (Step st : steps) {
            Exercise x = lib.byId(st.exerciseId);
            if (x != null && x.camera) n++;
        }
        return n;
    }

    public String stepsJson() {
        JsonArray a = new JsonArray();
        for (Step s : steps) {
            JsonObject o = new JsonObject();
            o.addProperty("exercise_id", s.exerciseId);
            o.addProperty("target", s.target);
            o.addProperty("rest_seconds", s.restSeconds);
            o.addProperty("cue", s.cue);
            o.addProperty("phase", s.phase);
            a.add(o);
        }
        return a.toString();
    }

    /** Parses stored steps, dropping steps whose exercise is unknown to this app version. */
    public static void parseSteps(@Nullable String json, WorkoutPlan into, @Nullable ExerciseLibrary lib) {
        if (json == null) return;
        for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            Step s = new Step();
            s.exerciseId = o.get("exercise_id").getAsString();
            s.target = Math.max(1, Math.min(600, o.get("target").getAsInt()));
            s.restSeconds = o.has("rest_seconds") ? Math.max(0, Math.min(300, o.get("rest_seconds").getAsInt())) : Programs.REST;
            s.cue = o.has("cue") && !o.get("cue").isJsonNull() ? o.get("cue").getAsString() : "";
            s.phase = o.has("phase") && !o.get("phase").isJsonNull() ? o.get("phase").getAsString() : "main";
            if (lib == null || lib.byId(s.exerciseId) != null) into.steps.add(s);
        }
    }
}

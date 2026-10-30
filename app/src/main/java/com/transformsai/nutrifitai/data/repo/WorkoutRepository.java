package com.transformsai.nutrifitai.data.repo;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutPlanEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.data.db.model.DayWorkout;
import com.transformsai.nutrifitai.data.remote.AiHttpException;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.Programs;
import com.transformsai.nutrifitai.fitness.WorkoutEngine;
import com.transformsai.nutrifitai.fitness.WorkoutMath;
import com.transformsai.nutrifitai.fitness.WorkoutPlan;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

/** Workout sessions and saved plans (Room first, synced through the outbox) plus AI plan generation. */
public class WorkoutRepository {
    private final AppGraph g;

    public WorkoutRepository(AppGraph g) {
        this.g = g;
    }

    public LiveData<List<WorkoutSessionEntity>> observeRecent(String uid, int limit) {
        return g.db.workouts().observeRecent(uid, limit);
    }

    public LiveData<List<DayWorkout>> observeDaily(String uid, String from, String to) {
        return g.db.workouts().observeDaily(uid, from, to);
    }

    public LiveData<List<WorkoutPlanEntity>> observePlans(String uid) {
        return g.db.workouts().observePlans(uid);
    }

    /** Body weight used for calorie estimates: latest logged weight, else the profile weight. */
    public double bodyWeightKg(String uid) {
        WeightEntity w = g.db.weights().latest(uid);
        if (w != null && w.kg > 0) return w.kg;
        ProfileEntity p = g.db.profiles().get(uid);
        return p != null && p.weightKg > 0 ? p.weightKg : 65;
    }

    /** Saves a finished workout; results with no work at all are not saved. */
    public void saveSession(WorkoutPlan plan, List<WorkoutEngine.StepResult> results, long startedAt, long endedAt,
                            Result.Callback<WorkoutSessionEntity> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            long active = WorkoutMath.activeMs(results);
            if (active < 5000) {
                g.postResult(cb, Result.fail("Nothing to save yet."));
                return;
            }
            WorkoutSessionEntity s = new WorkoutSessionEntity();
            s.id = Ids.newId();
            s.uid = uid;
            s.date = DateUtil.dateOf(startedAt);
            s.startedAt = startedAt;
            s.endedAt = endedAt;
            s.title = plan.title == null ? "Workout" : plan.title;
            s.planId = plan.id;
            s.activeMs = active;
            s.totalReps = WorkoutMath.totalReps(results);
            s.kcalEstimate = Math.round(WorkoutMath.totalKcal(results, bodyWeightKg(uid)) * 10) / 10.0;
            s.completion = Math.round(WorkoutMath.completion(results) * 100) / 100.0;
            int done = 0, cam = 0;
            JsonArray sets = new JsonArray();
            for (WorkoutEngine.StepResult r : results) {
                if (!r.skipped) done++;
                if (r.camera) cam++;
                JsonObject o = new JsonObject();
                o.addProperty("exercise_id", r.exerciseId);
                o.addProperty("name", r.name);
                o.addProperty("hold", r.hold);
                o.addProperty("target", r.target);
                o.addProperty("reps", r.reps);
                o.addProperty("rejected", r.rejected);
                o.addProperty("held_ms", r.heldMs);
                o.addProperty("active_ms", r.activeMs);
                o.addProperty("camera", r.camera);
                o.addProperty("skipped", r.skipped);
                sets.add(o);
            }
            s.exercisesDone = done;
            s.mode = cam == 0 ? "manual" : cam == results.size() ? "camera" : "mixed";
            s.setsJson = sets.toString();
            s.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.workouts().upsertSession(s);
                g.outbox.enqueue(uid, Collections.WORKOUTS, s.id);
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(s));
        });
    }

    /** Saves a walk session (from the walk tracker) as a workout with mode "walk". */
    /**
     * Saves a finished walk with its GPS route. Steps are stored on the walk only: they are already part of the day's
     * total (phone counter / Health Connect), so nothing is added to the daily steps here.
     */
    public void saveWalk(long startedAt, long endedAt, int steps, @Nullable com.transformsai.nutrifitai.fitness.RouteTracker route,
                         @Nullable Result.Callback<WorkoutSessionEntity> cb) {
        g.runIo(() -> {
            String uid = g.session.uid();
            if (uid == null) return;
            WorkoutSessionEntity s = new WorkoutSessionEntity();
            s.id = Ids.newId();
            s.uid = uid;
            s.date = DateUtil.dateOf(startedAt);
            s.startedAt = startedAt;
            s.endedAt = endedAt;
            s.title = walkTitle(startedAt, route != null && route.distanceM() > 0);
            s.planId = null;
            s.mode = "walk";
            s.activeMs = Math.max(0, endedAt - startedAt);
            s.totalReps = 0;
            s.exercisesDone = 1;
            double kg = bodyWeightKg(uid);
            double km = route == null ? 0 : route.distanceM() / 1000.0;
            // steps when the phone counted them; otherwise about 0.5 kcal per kg per km walked
            s.kcalEstimate = Math.round(Math.max(StepsRepository.kcalForSteps(steps, kg), 0.5 * kg * km) * 10) / 10.0;
            s.steps = steps;
            if (route != null && route.size() >= 2) {
                s.routePolyline = route.encoded();
                s.distanceM = Math.round(route.distanceM() * 10) / 10.0;
                s.movingMs = route.movingMs();
                s.maxSpeedMps = route.maxSpeedMps();
                s.elevGainM = Math.round(route.elevationGainM() * 10) / 10.0;
                s.splitsJson = new com.google.gson.Gson().toJson(route.splitsMs());
            }
            s.completion = 1;
            JsonArray sets = new JsonArray();
            JsonObject o = new JsonObject();
            o.addProperty("exercise_id", "walk");
            o.addProperty("name", "Walk");
            o.addProperty("hold", false);
            o.addProperty("target", 0);
            o.addProperty("reps", 0);
            o.addProperty("steps", steps);
            o.addProperty("active_ms", s.activeMs);
            o.addProperty("camera", false);
            o.addProperty("skipped", false);
            sets.add(o);
            s.setsJson = sets.toString();
            s.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.workouts().upsertSession(s);
                g.outbox.enqueue(uid, Collections.WORKOUTS, s.id);
            });
            g.outbox.kick();
            if (cb != null) g.postResult(cb, Result.ok(s));
        });
    }

    private String walkTitle(long at, boolean gps) {
        int h = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).getHour();
        int res = h < 5 ? com.transformsai.nutrifitai.R.string.walk_title_night : h < 12 ? com.transformsai.nutrifitai.R.string.walk_title_morning
                : h < 17 ? com.transformsai.nutrifitai.R.string.walk_title_afternoon : h < 21 ? com.transformsai.nutrifitai.R.string.walk_title_evening
                : com.transformsai.nutrifitai.R.string.walk_title_night;
        return g.context.getString(res);
    }

    public LiveData<List<WorkoutSessionEntity>> observeWalks(String uid) {
        return g.db.workouts().observeWalks(uid, 50);
    }

    public LiveData<WorkoutSessionEntity> observeSession(String id) {
        return g.db.workouts().observeSession(id);
    }

    public void deleteSession(String id) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                WorkoutSessionEntity s = g.db.workouts().session(id);
                if (s == null) return;
                s.deleted = true;
                s.updatedAt = DateUtil.nowMillis();
                g.db.workouts().upsertSession(s);
                g.outbox.enqueue(uid, Collections.WORKOUTS, id);
            });
            g.outbox.kick();
        });
    }

    /** Built-in program id or a saved plan id. */
    public void loadPlan(String id, Result.Callback<WorkoutPlan> cb) {
        WorkoutPlan builtin = Programs.byId(id);
        if (builtin != null) {
            g.postResult(cb, Result.ok(builtin));
            return;
        }
        g.runIo(() -> {
            WorkoutPlanEntity e = g.db.workouts().plan(id);
            if (e == null || (e.deleted && !e.id.startsWith("single_"))) {
                g.postResult(cb, Result.fail("This plan no longer exists."));
                return;
            }
            g.postResult(cb, Result.ok(toPlan(e, ExerciseLibrary.get(g.context))));
        });
    }

    public static WorkoutPlan toPlan(WorkoutPlanEntity e, ExerciseLibrary lib) {
        WorkoutPlan p = new WorkoutPlan();
        p.id = e.id;
        p.title = e.title;
        p.summary = e.summary == null ? "" : e.summary;
        p.source = e.source;
        p.level = e.level;
        p.focus = e.focus == null ? "" : e.focus;
        WorkoutPlan.parseSteps(e.stepsJson, p, lib);
        return p;
    }

    public void deletePlan(String id) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                WorkoutPlanEntity e = g.db.workouts().plan(id);
                if (e == null) return;
                e.deleted = true;
                e.updatedAt = DateUtil.nowMillis();
                g.db.workouts().upsertPlan(e);
                g.outbox.enqueue(uid, Collections.WORKOUT_PLANS, id);
            });
            g.outbox.kick();
        });
    }

    /**
     * Asks the NutriFit AI server for a plan built only from this app's coached exercises, validates it
     * again here and saves it. Fails with an actionable message when offline; never invents a plan.
     */
    /** What the user asked the AI builder for. */
    public static final class AiRequest {
        public int minutes = 15;
        public String focus = "full body";
        public String level = "beginner";
        /** easy | moderate | hard */
        public String intensity = "moderate";
        @Nullable public String notes;
        public final java.util.Set<String> targetMuscles = new java.util.LinkedHashSet<>();
        public boolean warmup = true, cooldown = true, lowImpact, cameraOnly;
    }

    /** Exercises that involve jumping (left out of low-impact plans). */
    static final java.util.Set<String> HIGH_IMPACT = new java.util.HashSet<>(java.util.Arrays.asList(
            "jumping_jack", "jump_squat", "star_jump", "burpee", "tuck_jump", "high_knees", "butt_kicks"));

    public void generateAiPlan(int minutes, String focus, String level, @Nullable String notes, Result.Callback<WorkoutPlan> cb) {
        AiRequest r = new AiRequest();
        r.minutes = minutes;
        r.focus = focus;
        r.level = level;
        r.notes = notes;
        generateAiPlan(r, cb);
    }

    /**
     * Sends only the request options, the goal, a one-line weekly muscle summary and a one-line recent
     * performance summary - no health records or meal history.
     */
    public void generateAiPlan(AiRequest req, Result.Callback<WorkoutPlan> cb) {
        String level = req.level;
        String focus = req.focus;
        g.runIo(() -> {
            String uid = g.session.requireUid();
            ExerciseLibrary lib = ExerciseLibrary.get(g.context);
            JsonObject body = new JsonObject();
            body.addProperty("minutes", req.minutes);
            body.addProperty("focus", req.focus);
            body.addProperty("level", req.level);
            body.addProperty("intensity", req.intensity);
            body.addProperty("include_warmup", req.warmup);
            body.addProperty("include_cooldown", req.cooldown);
            body.addProperty("low_impact", req.lowImpact);
            body.addProperty("camera_only", req.cameraOnly);
            if (!req.targetMuscles.isEmpty()) {
                JsonArray tm = new JsonArray();
                for (String m : req.targetMuscles) tm.add(m);
                body.add("target_muscles", tm);
            }
            ProfileEntity p = g.db.profiles().get(uid);
            if (p != null) body.addProperty("goal", p.goal);
            if (req.notes != null && !req.notes.trim().isEmpty()) body.addProperty("notes", req.notes.trim());
            body.addProperty("weekly_muscles", weeklyMuscleSummary(uid, lib));
            body.addProperty("recent_performance", recentPerformance(uid));
            JsonArray ex = new JsonArray();
            for (Exercise x : lib.guided()) {
                if (req.lowImpact && HIGH_IMPACT.contains(x.id)) continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", x.id);
                o.addProperty("name", x.name);
                o.addProperty("kind", x.isHold() ? "hold" : "reps");
                o.addProperty("muscles", targetsLine(x));
                o.addProperty("role", x.role);
                o.addProperty("impact", HIGH_IMPACT.contains(x.id) ? "high" : "low");
                o.addProperty("camera", x.camera);
                ex.add(o);
            }
            body.add("exercises", ex);
            try {
                String raw = g.ai.workout(body, null);
                WorkoutPlan plan = parseAiPlan(raw, lib);
                if (req.lowImpact) plan.steps.removeIf(s -> HIGH_IMPACT.contains(s.exerciseId));
                if (plan.steps.isEmpty()) throw new IllegalArgumentException("no usable steps");
                plan.applyRest(g.prefs.restSeconds()); // the user's rest setting wins over the model's choice
                if (!plan.steps.isEmpty()) plan.steps.get(plan.steps.size() - 1).restSeconds = 0;
                plan.level = level;
                plan.focus = focus;
                WorkoutPlanEntity e = new WorkoutPlanEntity();
                e.id = Ids.newId();
                e.uid = uid;
                e.title = plan.title;
                e.summary = plan.summary;
                e.source = "ai";
                e.level = level;
                e.focus = focus;
                e.stepsJson = plan.stepsJson();
                e.createdAt = DateUtil.nowMillis();
                e.updatedAt = e.createdAt;
                plan.id = e.id;
                plan.source = "ai";
                g.db.runInTransaction(() -> {
                    g.db.workouts().upsertPlan(e);
                    g.outbox.enqueue(uid, Collections.WORKOUT_PLANS, e.id);
                });
                g.outbox.kick();
                g.postResult(cb, Result.ok(plan));
            } catch (AiHttpException e) {
                g.postResult(cb, Result.fail(e.getMessage()));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("The AI plan could not be read. Please try again."));
            }
        });
    }

    /** Saves a workout the coach suggested (after the user confirmed) as an AI plan. */
    public void saveSuggestedPlan(String json, Result.Callback<WorkoutPlan> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            try {
                WorkoutPlan plan = parseAiPlan(json, ExerciseLibrary.get(g.context));
                plan.applyRest(g.prefs.restSeconds());
                plan.steps.get(plan.steps.size() - 1).restSeconds = 0;
                WorkoutPlanEntity e = new WorkoutPlanEntity();
                e.id = Ids.newId();
                e.uid = uid;
                e.title = plan.title;
                e.summary = plan.summary;
                e.source = "ai";
                e.level = g.prefs.fitnessLevel();
                e.focus = "coach";
                e.stepsJson = plan.stepsJson();
                e.createdAt = DateUtil.nowMillis();
                e.updatedAt = e.createdAt;
                plan.id = e.id;
                plan.source = "ai";
                g.db.runInTransaction(() -> {
                    g.db.workouts().upsertPlan(e);
                    g.outbox.enqueue(uid, Collections.WORKOUT_PLANS, e.id);
                });
                g.outbox.kick();
                g.postResult(cb, Result.ok(plan));
            } catch (Exception ex) {
                g.postResult(cb, Result.fail("This workout could not be saved."));
            }
        });
    }

    /** Client-side validation of the server's workout JSON (unknown exercises dropped, ranges clamped). */
    public static WorkoutPlan parseAiPlan(String raw, ExerciseLibrary lib) {
        JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
        WorkoutPlan p = new WorkoutPlan();
        String title = o.has("title") && o.get("title").isJsonPrimitive() ? o.get("title").getAsString().trim() : "";
        p.title = title.isEmpty() ? "AI workout" : title.length() > 60 ? title.substring(0, 60) : title;
        p.summary = o.has("summary") && o.get("summary").isJsonPrimitive() ? o.get("summary").getAsString() : "";
        if (p.summary.length() > 240) p.summary = p.summary.substring(0, 240);
        for (JsonElement e : o.getAsJsonArray("steps")) {
            if (p.steps.size() >= 24 || !e.isJsonObject()) continue;
            JsonObject s = e.getAsJsonObject();
            if (!s.has("exercise_id")) continue;
            Exercise x = lib.byId(s.get("exercise_id").getAsString());
            if (x == null || !x.guided || x.archived) continue;
            WorkoutPlan.Step st = new WorkoutPlan.Step();
            st.exerciseId = x.id;
            if (x.isHold()) {
                if (!s.has("seconds")) continue;
                st.target = Math.max(5, Math.min(300, s.get("seconds").getAsInt()));
            } else {
                if (!s.has("reps")) continue;
                st.target = Math.max(1, Math.min(100, s.get("reps").getAsInt()));
            }
            st.restSeconds = s.has("rest_seconds") ? Math.max(0, Math.min(90, s.get("rest_seconds").getAsInt())) : Programs.REST;
            st.cue = s.has("cue") && s.get("cue").isJsonPrimitive() ? s.get("cue").getAsString() : "";
            String phase = s.has("phase") && s.get("phase").isJsonPrimitive() ? s.get("phase").getAsString() : "main";
            st.phase = "warmup".equals(phase) || "cooldown".equals(phase) ? phase : "main";
            if (st.cue.length() > 120) st.cue = st.cue.substring(0, 120);
            p.steps.add(st);
        }
        if (p.steps.isEmpty()) throw new IllegalArgumentException("no usable steps");
        return p;
    }

    /** "quads 1.0, glutes 0.9" - muscle weights for the planner. */
    static String targetsLine(Exercise x) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Float> t : x.targets.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.getKey()).append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", t.getValue()));
        }
        return sb.toString();
    }

    /** "quads 80%, glutes 60%, abs 30%" for the last 7 days, or "nothing yet". */
    public String weeklyMuscleSummary(String uid, ExerciseLibrary lib) {
        String today = DateUtil.today();
        java.util.List<WorkoutEngine.StepResult> all = new java.util.ArrayList<>();
        for (WorkoutSessionEntity s : g.db.workouts().range(uid, DateUtil.shift(today, -6), today)) {
            all.addAll(WorkoutMath.parseSets(s.setsJson));
        }
        java.util.Map<String, Float> m = com.transformsai.nutrifitai.fitness.Muscles.intensity(all, lib);
        if (m.isEmpty()) return "nothing trained in the last 7 days";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Float> e : com.transformsai.nutrifitai.fitness.Muscles.ranked(m)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(' ').append(Math.round(e.getValue() * 100)).append('%');
            if (sb.length() > 260) break;
        }
        return sb.toString();
    }

    /** "last 3 workouts: completion 100%, 85%, 90%; camera form 92%" (bounded, no personal data). */
    public String recentPerformance(String uid) {
        String today = DateUtil.today();
        java.util.List<WorkoutSessionEntity> l = g.db.workouts().range(uid, DateUtil.shift(today, -27), today);
        if (l.isEmpty()) return "no recent workouts";
        StringBuilder sb = new StringBuilder("last workouts: completion ");
        int ok = 0, bad = 0, shown = 0;
        for (int i = l.size() - 1; i >= 0 && shown < 4; i--, shown++) {
            WorkoutSessionEntity s = l.get(i);
            if (shown > 0) sb.append(", ");
            sb.append(Math.round(s.completion * 100)).append('%');
            int[] f = WorkoutMath.formCounts(WorkoutMath.parseSets(s.setsJson));
            ok += f[0];
            bad += f[1];
        }
        if (ok + bad > 0) sb.append("; camera form ").append(Math.round(100f * ok / (ok + bad))).append('%');
        return sb.toString();
    }

    /** "3 workouts, 42 active min in the last 7 days" - a bounded summary for the AI coach. */
    public String recentTrainingSummary(String uid) {
        String today = DateUtil.today();
        List<DayWorkout> days = g.db.workouts().daily(uid, DateUtil.shift(today, -6), today);
        int sessions = 0;
        long ms = 0;
        for (DayWorkout d : days) {
            sessions += d.sessions;
            ms += d.activeMs;
        }
        return sessions == 0 ? "No workouts logged in the last 7 days"
                : sessions + " workouts, " + Math.round(ms / 60000.0) + " active minutes in the last 7 days";
    }
}

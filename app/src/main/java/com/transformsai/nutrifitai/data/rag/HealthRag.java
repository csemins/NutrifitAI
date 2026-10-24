package com.transformsai.nutrifitai.data.rag;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.data.db.AppDatabase;
import com.transformsai.nutrifitai.data.db.entity.FastEntity;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.entity.StepDayEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.data.db.model.ItemRow;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.domain.NutritionMath;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.Muscles;
import com.transformsai.nutrifitai.fitness.WorkoutMath;
import com.transformsai.nutrifitai.util.DateUtil;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Retrieval for the AI coach (RAG over the user's own records, on the device). For each question it
 * reads only what the question needs from Room - meals with portions and macros, planned meals, water,
 * steps, workouts with trained muscles, weight, fasting, the goal snapshot that applied on each day,
 * and matching foods from the food database - and writes it as compact, dated text within a fixed
 * character budget. The text and a list of human-readable sources go to the coach; name, email and
 * photos never do.
 *
 * <p>Days with no records are listed as "nothing logged", never as zero intake.
 */
public final class HealthRag {
    public static final int BUDGET = 9000;
    private static final int DETAIL_DAYS = 10;

    public static final class Context {
        public String text;
        public final List<String> sources = new ArrayList<>();
    }

    private final AppDatabase db;
    @Nullable private final ExerciseLibrary lib;

    public HealthRag(AppDatabase db, @Nullable ExerciseLibrary lib) {
        this.db = db;
        this.lib = lib;
    }

    /** Call off the main thread. */
    public Context build(String uid, String question, int stepGoal) {
        String today = DateUtil.today();
        RagQuery q = RagQuery.analyse(question, today);
        Context out = new Context();
        StringBuilder sb = new StringBuilder();
        LocalDate t = DateUtil.parse(today);
        sb.append("TODAY: ").append(today).append(" (").append(day(t)).append("), local time ")
                .append(DateUtil.time(DateUtil.nowMillis())).append('\n');

        // 1) profile and the current targets (always, small)
        ProfileEntity p = db.profiles().get(uid);
        WeightEntity latestW = db.weights().latest(uid);
        if (p != null) {
            sb.append("PROFILE: goal ").append(p.goal);
            if (p.birthYear > 1900) sb.append("; age ").append(t.getYear() - p.birthYear);
            if (p.sex != null && !"unspecified".equals(p.sex)) sb.append("; sex ").append(p.sex);
            if (p.heightCm > 0) sb.append("; height ").append(r0(p.heightCm)).append(" cm");
            double kg = latestW != null ? latestW.kg : p.weightKg;
            if (kg > 0) sb.append("; current weight ").append(r1(kg)).append(" kg");
            if (latestW != null) sb.append(" (logged ").append(latestW.date).append(')');
            if (p.targetWeightKg > 0) sb.append("; target weight ").append(r1(p.targetWeightKg)).append(" kg");
            sb.append("; activity ").append(p.activityLevel);
            if (p.dietPrefs != null && !p.dietPrefs.isEmpty()) sb.append("; diet ").append(p.dietPrefs);
            if (p.allergies != null && !p.allergies.isEmpty()) sb.append("; declared allergies ").append(p.allergies);
            if (p.heightCm > 0 && kg > 0) sb.append("; BMI ").append(r1(WorkoutMath.bmi(kg, p.heightCm)));
            sb.append('\n');
            out.sources.add("Profile & goals");
        }
        GoalEntity g = db.goals().active(uid, today);
        if (g != null) {
            sb.append("CURRENT DAILY TARGETS (since ").append(g.effectiveDate).append("): ").append(g.kcal).append(" kcal, protein ")
                    .append(g.proteinG).append(" g, carbs ").append(g.carbsG).append(" g, fat ").append(g.fatG).append(" g, fibre ")
                    .append(g.fibreG).append(" g, water ").append(g.waterMl).append(" ml, steps ").append(stepGoal).append('\n');
        }

        // 2) the days the question is about (default: today, plus a 7-day overview)
        String from = q.from, to = q.to;
        List<String> days = DateUtil.range(from, to);
        boolean detailed = days.size() <= DETAIL_DAYS;
        Data d = load(uid, q.explicitDate ? from : DateUtil.shift(today, -6), maxOf(to, today));
        StringBuilder dayText = new StringBuilder();
        for (int i = days.size() - 1; i >= 0; i--) { // newest first so the budget keeps recent days
            String day = days.get(i);
            String block = dayBlock(uid, day, today, q, d, detailed);
            if (sb.length() + dayText.length() + block.length() > BUDGET - 1800) {
                dayText.append("(older days omitted for length)\n");
                break;
            }
            dayText.append(block);
        }
        sb.append(dayText);
        out.sources.add(label(q, from, to, today));

        // 3) a multi-day overview (averages over logged days only)
        if (!q.explicitDate || days.size() > 1) {
            String a = q.explicitDate ? from : DateUtil.shift(today, -6);
            String b = q.explicitDate ? to : today;
            sb.append(overview(uid, a, b, d, stepGoal));
            if (!q.explicitDate) out.sources.add("7-day trends");
        }

        // 4) food knowledge for foods named in the question
        if (q.wants(RagQuery.FOOD) && !q.terms.isEmpty() && sb.length() < BUDGET - 600) {
            String foods = foodFacts(uid, q.terms);
            if (!foods.isEmpty()) {
                sb.append(foods);
                out.sources.add("Food database");
            }
        }
        out.text = sb.length() > BUDGET ? sb.substring(0, BUDGET) : sb.toString();
        return out;
    }

    // ------------------------------------------------------------------ loading
    private static final class Data {
        final Map<String, List<ItemRow>> items = new LinkedHashMap<>();
        final Map<String, List<WaterEntity>> water = new LinkedHashMap<>();
        final Map<String, StepDayEntity> steps = new LinkedHashMap<>();
        final Map<String, List<WorkoutSessionEntity>> workouts = new LinkedHashMap<>();
        final Map<String, WeightEntity> weight = new LinkedHashMap<>();
        final Map<String, List<FastEntity>> fasts = new LinkedHashMap<>();
    }

    private Data load(String uid, String from, String to) {
        Data d = new Data();
        for (ItemRow r : db.diary().range(uid, from, to)) d.items.computeIfAbsent(r.date, k -> new ArrayList<>()).add(r);
        for (WaterEntity w : db.water().entries(uid, from, to)) d.water.computeIfAbsent(w.date, k -> new ArrayList<>()).add(w);
        for (StepDayEntity s : db.activity().stepsRange(uid, from, to)) d.steps.put(s.date, s);
        for (WorkoutSessionEntity s : db.workouts().range(uid, from, to)) d.workouts.computeIfAbsent(s.date, k -> new ArrayList<>()).add(s);
        for (WeightEntity w : db.weights().range(uid, from, to)) d.weight.put(w.date, w);
        for (FastEntity f : db.activity().fastsRange(uid, from, to)) d.fasts.computeIfAbsent(f.date, k -> new ArrayList<>()).add(f);
        return d;
    }

    // ------------------------------------------------------------------ one day
    private String dayBlock(String uid, String date, String today, RagQuery q, Data d, boolean detailed) {
        StringBuilder sb = new StringBuilder();
        LocalDate ld = DateUtil.parse(date);
        sb.append("DAY ").append(date).append(" (").append(day(ld));
        if (date.equals(today)) sb.append(", today");
        else if (date.equals(DateUtil.shift(today, -1))) sb.append(", yesterday");
        else if (date.equals(DateUtil.shift(today, 1))) sb.append(", tomorrow");
        sb.append("):\n");
        boolean any = false;
        List<ItemRow> rows = d.items.get(date);
        GoalEntity goal = db.goals().active(uid, date);
        if (q.wants(RagQuery.FOOD) || q.wants(RagQuery.GOALS) || q.wants(RagQuery.PLANNED)) {
            List<Nutrients> eaten = new ArrayList<>();
            Map<String, StringBuilder> byMeal = new LinkedHashMap<>(), planned = new LinkedHashMap<>();
            if (rows != null) for (ItemRow r : rows) {
                Nutrients n = NutritionMath.forItem(r.item);
                boolean isPlanned = !"consumed".equals(r.status);
                if (!isPlanned) eaten.add(n);
                Map<String, StringBuilder> m = isPlanned ? planned : byMeal;
                StringBuilder line = m.computeIfAbsent(r.mealType, k -> new StringBuilder());
                if (line.length() > 0) line.append("; ");
                line.append(r.item.name);
                if (detailed) {
                    line.append(' ').append(r1(r.item.servings)).append(' ').append(r.item.servingLabel == null ? "serving" : r.item.servingLabel)
                            .append(" (").append(r0(r.item.grams())).append(" g) ").append(r0(n.kcal)).append(" kcal P")
                            .append(r0(n.protein)).append(" C").append(r0(n.carbs)).append(" F").append(r0(n.fat));
                    if (r.consumedAt != null && r.consumedAt > 0) line.append(" at ").append(DateUtil.time(r.consumedAt));
                    if ("ai_estimate".equals(r.item.provenance)) line.append(" [AI estimate]");
                }
            }
            if (!byMeal.isEmpty()) {
                any = true;
                if (detailed) for (Map.Entry<String, StringBuilder> e : byMeal.entrySet()) {
                    sb.append("  Eaten ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                }
                Nutrients tot = NutritionMath.sum(eaten);
                sb.append("  Eaten total: ").append(r0(tot.kcal)).append(" kcal, protein ").append(r0(tot.protein)).append(" g, carbs ")
                        .append(r0(tot.carbs)).append(" g, fat ").append(r0(tot.fat)).append(" g");
                if (tot.fibre != null) sb.append(", fibre ").append(r0(tot.fibre)).append(" g");
                if (goal != null) {
                    long left = Math.round(goal.kcal - tot.kcal);
                    sb.append(" | target that day ").append(goal.kcal).append(" kcal, P").append(goal.proteinG).append(" C")
                            .append(goal.carbsG).append(" F").append(goal.fatG).append(left >= 0 ? " -> " + left + " kcal left" : " -> " + (-left) + " kcal over");
                }
                sb.append('\n');
            } else if (!DateUtil.isFuture(date)) {
                sb.append("  Meals: nothing logged (unknown, not zero)\n");
            }
            if (!planned.isEmpty()) {
                any = true;
                for (Map.Entry<String, StringBuilder> e : planned.entrySet()) {
                    sb.append("  Planned, not eaten ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                }
            }
        }
        if (q.wants(RagQuery.WATER) || q.wants(RagQuery.GOALS)) {
            List<WaterEntity> w = d.water.get(date);
            if (w != null && !w.isEmpty()) {
                int ml = 0;
                long last = 0;
                for (WaterEntity x : w) {
                    ml += x.ml;
                    last = Math.max(last, x.loggedAt);
                }
                sb.append("  Water: ").append(ml).append(" ml in ").append(w.size()).append(" drinks, last at ").append(DateUtil.time(last));
                if (goal != null) sb.append(" (target ").append(goal.waterMl).append(" ml)");
                sb.append('\n');
                any = true;
            }
        }
        if (q.wants(RagQuery.STEPS)) {
            StepDayEntity s = d.steps.get(date);
            if (s != null && s.steps > 0) {
                sb.append("  Steps: ").append(s.steps).append(" (~").append(r1(s.distanceM / 1000)).append(" km)\n");
                any = true;
            }
        }
        if (q.wants(RagQuery.WORKOUT)) {
            List<WorkoutSessionEntity> ws = d.workouts.get(date);
            if (ws != null) for (WorkoutSessionEntity w : ws) {
                sb.append("  Workout: ").append(w.title).append(", ").append(Math.max(1, Math.round(w.activeMs / 60000.0))).append(" min, ")
                        .append(w.totalReps).append(" reps, ~").append(r0(w.kcalEstimate)).append(" kcal (estimate), ")
                        .append(Math.round(w.completion * 100)).append("% complete, ").append(w.mode);
                List<com.transformsai.nutrifitai.fitness.WorkoutEngine.StepResult> res = WorkoutMath.parseSets(w.setsJson);
                if (lib != null) {
                    Map<String, Float> m = Muscles.intensity(res, lib);
                    if (!m.isEmpty()) sb.append("; muscles: ").append(Muscles.summary(m, 4));
                }
                int[] f = WorkoutMath.formCounts(res);
                if (f[0] + f[1] > 0) sb.append("; camera form ").append(Math.round(100f * f[0] / (f[0] + f[1]))).append("% (")
                        .append(f[1]).append(" reps not counted)");
                if (detailed) {
                    List<String> ex = new ArrayList<>();
                    for (com.transformsai.nutrifitai.fitness.WorkoutEngine.StepResult r : res) {
                        if (r.skipped) continue;
                        ex.add(r.name + " " + (r.hold ? (r.heldMs / 1000) + "s" : r.reps + "/" + r.target));
                    }
                    if (!ex.isEmpty()) sb.append("; sets: ").append(String.join(", ", ex));
                }
                sb.append('\n');
                any = true;
            }
        }
        if (q.wants(RagQuery.WEIGHT)) {
            WeightEntity w = d.weight.get(date);
            if (w != null) {
                sb.append("  Weight: ").append(r1(w.kg)).append(" kg\n");
                any = true;
            }
        }
        if (q.wants(RagQuery.FASTING)) {
            List<FastEntity> fs = d.fasts.get(date);
            if (fs != null) for (FastEntity f : fs) {
                long end = f.endAt == 0 ? DateUtil.nowMillis() : f.endAt;
                sb.append("  Fast: started ").append(DateUtil.time(f.startAt)).append(", ")
                        .append(r1((end - f.startAt) / 3_600_000.0)).append(" h of ").append(f.targetHours).append(" h target")
                        .append(f.endAt == 0 ? " (running)" : "").append('\n');
                any = true;
            }
        }
        if (!any && !sb.toString().contains("nothing logged")) sb.append("  Nothing logged.\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ overview
    private String overview(String uid, String from, String to, Data d, int stepGoal) {
        int loggedDays = 0, waterDays = 0, stepDays = 0, sessions = 0;
        double kcal = 0, protein = 0, carbs = 0, fat = 0, water = 0, steps = 0;
        long activeMs = 0;
        List<String> range = DateUtil.range(from, to);
        for (String day : range) {
            List<ItemRow> rows = d.items.get(day);
            if (rows != null) {
                List<Nutrients> eaten = new ArrayList<>();
                for (ItemRow r : rows) if ("consumed".equals(r.status)) eaten.add(NutritionMath.forItem(r.item));
                if (!eaten.isEmpty()) {
                    Nutrients n = NutritionMath.sum(eaten);
                    loggedDays++;
                    kcal += n.kcal;
                    protein += n.protein;
                    carbs += n.carbs;
                    fat += n.fat;
                }
            }
            List<WaterEntity> w = d.water.get(day);
            if (w != null && !w.isEmpty()) {
                waterDays++;
                for (WaterEntity x : w) water += x.ml;
            }
            StepDayEntity s = d.steps.get(day);
            if (s != null && s.steps > 0) {
                stepDays++;
                steps += s.steps;
            }
            List<WorkoutSessionEntity> ws = d.workouts.get(day);
            if (ws != null) for (WorkoutSessionEntity x : ws) {
                sessions++;
                activeMs += x.activeMs;
            }
        }
        StringBuilder sb = new StringBuilder("OVERVIEW ").append(from).append(" to ").append(to).append(" (").append(range.size()).append(" days):\n");
        if (loggedDays > 0) sb.append("  Food logged on ").append(loggedDays).append(" days; average on logged days ").append(r0(kcal / loggedDays))
                .append(" kcal, P").append(r0(protein / loggedDays)).append(" C").append(r0(carbs / loggedDays)).append(" F").append(r0(fat / loggedDays)).append('\n');
        else sb.append("  No food logged in this period.\n");
        if (waterDays > 0) sb.append("  Water logged on ").append(waterDays).append(" days, average ").append(r0(water / waterDays)).append(" ml\n");
        if (stepDays > 0) sb.append("  Steps on ").append(stepDays).append(" days, average ").append(r0(steps / stepDays)).append(" (goal ").append(stepGoal).append(")\n");
        sb.append("  Workouts: ").append(sessions).append(" sessions, ").append(Math.round(activeMs / 60000.0)).append(" active min\n");
        List<WeightEntity> ws = db.weights().range(uid, DateUtil.shift(from, -30), to);
        if (ws.size() >= 2) {
            WeightEntity first = ws.get(0), last = ws.get(ws.size() - 1);
            sb.append("  Weight: ").append(r1(first.kg)).append(" kg (").append(first.date).append(") -> ").append(r1(last.kg))
                    .append(" kg (").append(last.date).append(")\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ food knowledge
    private String foodFacts(String uid, List<String> terms) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        int n = 0;
        for (String term : terms) {
            if (n >= 5) break;
            for (FoodEntity f : db.foods().search(uid, term, "", false, false, 2)) {
                if (n >= 5 || !seen.add(f.id)) continue;
                if (sb.length() == 0) sb.append("FOOD DATABASE (per 100 g unless stated):\n");
                sb.append("  ").append(f.name);
                if (f.nameBn != null && !f.nameBn.isEmpty()) sb.append(" / ").append(f.nameBn);
                sb.append(": ").append(r0(f.kcal100)).append(" kcal, P").append(r1(f.protein100)).append(" C").append(r1(f.carbs100))
                        .append(" F").append(r1(f.fat100));
                if (f.fibre100 != null) sb.append(", fibre ").append(r1(f.fibre100));
                if (f.sugar100 != null) sb.append(", sugar ").append(r1(f.sugar100));
                if (f.sodiumMg100 != null) sb.append(", sodium ").append(r0(f.sodiumMg100)).append(" mg");
                if (f.servingLabel != null) sb.append("; 1 ").append(f.servingLabel).append(" = ").append(r0(f.servingGrams)).append(" g");
                sb.append(" [").append(f.provenance).append("]\n");
                n++;
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers
    private static String label(RagQuery q, String from, String to, String today) {
        if (!q.explicitDate) return "Today's logs";
        if (from.equals(to)) {
            if (from.equals(today)) return "Today's logs";
            if (from.equals(DateUtil.shift(today, -1))) return "Yesterday's logs";
            return "Logs for " + DateUtil.monthDay(from);
        }
        return "Logs " + DateUtil.monthDay(from) + " to " + DateUtil.monthDay(to);
    }

    private static String day(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String maxOf(String a, String b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    static String r0(double v) {
        return String.valueOf(Math.round(v));
    }

    static String r1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}

package com.transformsai.nutrifitai.fitness;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in programs: no equipment, and every move is camera-tracked (rep counting and form checks). Every exercise is coached by voice; camera-tracked ones count
 * reps (with form checks) automatically. Rest is {@link #REST} seconds between exercises.
 */
public final class Programs {
    private Programs() { }

    /** Default rest between exercises. */
    public static final int REST = 15;

    public static List<WorkoutPlan> all() {
        List<WorkoutPlan> l = new ArrayList<>();
        l.add(plan("p_starter", "Full-body starter", "beginner", "Full body",
                "A gentle all-round session to build the habit.")
                .add("arm_circles", 20).add("jumping_jack", 20).add("squat", 12).add("pushup", 8).add("glute_bridge", 12)
                .add("plank", 20).add("lunge", 10).add("crunch", 12).add("childs_pose", 30).last());
        l.add(plan("p_hiit", "Cardio burn", "intermediate", "Cardio",
                "Short bursts to raise your heart rate. Land softly.")
                .add("jumping_jack", 30).add("high_knees", 30).add("mountain_climber", 24).add("jump_squat", 12)
                .add("butt_kicks", 30).add("burpee", 8).add("tuck_jump", 8).add("star_jump", 10).add("plank", 30).last());
        l.add(plan("p_core", "Core crusher", "beginner", "Core",
                "Abs, obliques and lower back.")
                .add("dead_bug", 12).add("crunch", 15).add("russian_twist", 16).add("plank", 30).add("side_plank", 20)
                .add("reverse_crunch", 12).add("flutter_kicks", 20).add("elbow_to_knee", 16).add("superman", 20).last());
        l.add(plan("p_legs", "Lower body", "intermediate", "Legs",
                "Quads, glutes, hamstrings and calves.")
                .add("butt_kicks", 30).add("squat", 15).add("lunge", 12).add("glute_bridge", 15).add("wall_sit", 30)
                .add("split_squat", 10).add("calf_raise", 15).add("jump_squat", 10).add("hamstring_stretch", 30).last());
        l.add(plan("p_upper", "Upper body", "beginner", "Arms & chest",
                "Push-up variations and plank work.")
                .add("arm_circles", 20).add("pushup", 10).add("wide_pushup", 8).add("plank", 30)
                .add("diamond_pushup", 6).add("inchworm", 5).add("mountain_climber", 20).add("pushup", 8).last());
        l.add(plan("p_quick", "7-minute express", "beginner", "Full body",
                "When time is short: seven moves, little rest.")
                .add("jumping_jack", 25).add("wall_sit", 30).add("pushup", 10).add("crunch", 15)
                .add("squat", 15).add("plank", 30).add("high_knees", 24).last());
        l.add(plan("p_mobility", "Morning mobility", "beginner", "Mobility",
                "Wake up stiff joints with gentle movement and stretches.")
                .add("arm_circles", 30).add("cat_cow", 30).add("worlds_greatest", 6).add("hip_flexor_stretch", 30)
                .add("glute_bridge", 10).add("hamstring_stretch", 30).add("childs_pose", 40).last());
        l.add(plan("p_glutes", "Glutes & hips", "intermediate", "Glutes",
                "Hip strength and stability for everyday movement.")
                .add("glute_bridge", 15).add("glute_kickback", 12).add("side_leg_raise", 12).add("single_leg_bridge", 10)
                .add("squat", 15).add("split_squat", 10).add("hip_flexor_stretch", 30).last());
        l.add(plan("p_posture", "Posture & core", "beginner", "Core",
                "Steady holds and bridges for a strong, upright back.")
                .add("cat_cow", 30).add("plank", 20).add("superman", 20).add("side_plank", 15).add("glute_bridge", 12)
                .add("dead_bug", 10).add("childs_pose", 30).last());
        return l;
    }

    public static WorkoutPlan byId(String id) {
        for (WorkoutPlan p : all()) if (p.id.equals(id)) return p;
        return null;
    }

    /** Today's suggested program rotates through the list by day of year. */
    public static WorkoutPlan forDay(int dayOfYear, String level) {
        List<WorkoutPlan> all = all();
        List<WorkoutPlan> fit = new ArrayList<>();
        for (WorkoutPlan p : all) if ("beginner".equals(level) ? "beginner".equals(p.level) : true) fit.add(p);
        return fit.get(Math.floorMod(dayOfYear, fit.size()));
    }

    /** A single-exercise session (from the exercise detail screen). */
    public static WorkoutPlan single(Exercise x, int sets) {
        WorkoutPlan p = new WorkoutPlan();
        p.id = "single_" + x.id;
        p.title = x.name;
        p.source = "single";
        for (int i = 0; i < sets; i++) p.add(x.id, x.defaultTarget, i == sets - 1 ? 0 : REST);
        return p;
    }

    private static WorkoutPlan plan(String id, String title, String level, String focus, String summary) {
        WorkoutPlan p = new WorkoutPlan();
        p.id = id;
        p.title = title;
        p.level = level;
        p.focus = focus;
        p.summary = summary;
        return p;
    }
}

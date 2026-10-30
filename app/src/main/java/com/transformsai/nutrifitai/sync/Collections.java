package com.transformsai.nutrifitai.sync;

/** Firestore sub-collections under users/{uid}. "profile" is the users/{uid} document itself. */
public final class Collections {
    public static final String PROFILE = "profile";
    public static final String GOALS = "goals";
    public static final String FOODS = "foods";
    public static final String FAVORITES = "favorites";
    public static final String RECIPES = "recipes";
    public static final String MEALS = "mealEntries";
    public static final String WATER = "water";
    public static final String WEIGHTS = "weights";
    public static final String SETTINGS = "settings";
    public static final String CHAT = "chatMessages";
    public static final String CONVERSATIONS = "conversations";
    public static final String AI = "aiAnalyses";
    public static final String WORKOUTS = "workouts";
    public static final String WORKOUT_PLANS = "workoutPlans";
    public static final String STEPS = "steps";
    public static final String FASTS = "fasts";

    public static final String[] PULL_ORDER = {GOALS, FOODS, FAVORITES, RECIPES, MEALS, WATER, WEIGHTS, SETTINGS,
            CONVERSATIONS, CHAT, AI, WORKOUTS, WORKOUT_PLANS, STEPS, FASTS};

    private Collections() {
    }
}

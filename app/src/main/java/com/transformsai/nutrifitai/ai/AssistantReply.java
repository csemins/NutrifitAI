package com.transformsai.nutrifitai.ai;

import java.util.ArrayList;
import java.util.List;

/** Parsed assistant answer: free text, optional structured meal suggestions, an optional workout, and the data sources used. */
public class AssistantReply {
    public String reply;
    public List<Suggestion> suggestions = new ArrayList<>();
    /** What the coach was shown from the user's own data ("Today's logs", "Food database"...). */
    public List<String> sources = new ArrayList<>();
    /** Validated workout suggestion JSON ({title, summary, steps}), or null. Saved only on the user's tap. */
    public String workoutJson;
    /** App actions the coach proposed (each runs only after the user confirms). */
    public List<Action> actions = new ArrayList<>();

    public static class Action {
        public String type;
        public int ml, hours, steps;
        public double kg;
        public String text, mealType, screen;
    }

    public static class Suggestion {
        public String title;
        public String mealType;
        public List<AiItem> items = new ArrayList<>();
        public String rationale;

        public double kcal() {
            double t = 0;
            for (AiItem i : items) t += i.kcal;
            return t;
        }
    }
}

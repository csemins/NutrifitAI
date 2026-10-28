package com.transformsai.nutrifitai.data.repo;

import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.ai.AiItem;
import com.transformsai.nutrifitai.ai.AssistantReply;
import com.transformsai.nutrifitai.ai.AssistantReplyParser;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.entity.ChatMessageEntity;
import com.transformsai.nutrifitai.data.db.entity.ConversationEntity;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.model.DayTotals;
import com.transformsai.nutrifitai.data.rag.HealthRag;
import com.transformsai.nutrifitai.data.remote.AiHttpException;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Calendar;
import java.util.List;

/**
 * AI coach chat with retrieval (RAG) over the user's own records. For every question
 * {@link HealthRag} reads the relevant days from Room (meals, water, steps, workouts, weight, fasting,
 * goal snapshots) and matching foods, within a fixed size budget; that text goes to the proxy as data.
 * History is bounded (last 10 messages). Name, email and photos are never sent.
 */
public class AssistantRepository {
    public static final int CONTEXT_MESSAGES = 10;
    public static final int MAX_INPUT = 600;
    private final AppGraph g;

    public AssistantRepository(AppGraph g) {
        this.g = g;
    }

    public void conversationId(Result.Callback<String> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(ensureConversation())));
    }

    private String ensureConversation() {
        String uid = g.session.requireUid();
        String id = g.prefs.conversationId(uid);
        if (id != null && g.db.chat().conversation(id) != null) return id;
        ConversationEntity latest = g.db.chat().latestConversation(uid);
        if (latest != null) {
            g.prefs.setConversationId(uid, latest.id);
            return latest.id;
        }
        return newConversationBlocking();
    }

    private String newConversationBlocking() {
        String uid = g.session.requireUid();
        ConversationEntity c = new ConversationEntity();
        c.id = Ids.newId();
        c.uid = uid;
        c.title = "New chat";
        c.createdAt = DateUtil.nowMillis();
        c.updatedAt = c.createdAt;
        g.db.runInTransaction(() -> {
            g.db.chat().upsertConversation(c);
            g.outbox.enqueue(uid, Collections.CONVERSATIONS, c.id);
        });
        g.prefs.setConversationId(uid, c.id);
        return c.id;
    }

    public void newConversation(Result.Callback<String> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(newConversationBlocking())));
    }

    public LiveData<List<ChatMessageEntity>> observe(String uid, String conversationId) {
        return g.db.chat().observe(uid, conversationId);
    }

    /** Which tab the question came from (home, meals, train, progress, profile); sent as context. */
    private volatile String screen = "home";

    public void setScreen(String s) {
        if (s != null && s.matches("[a-z_]{2,20}")) screen = s;
    }

    public java.util.List<ConversationEntity> historyBlocking(String uid) {
        return g.db.chat().history(uid);
    }

    public void openConversation(String id) {
        g.runIo(() -> g.prefs.setConversationId(g.session.requireUid(), id));
    }

    public void deleteConversation(String id, Result.Callback<Void> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            ConversationEntity c = g.db.chat().conversation(id);
            if (c == null) return;
            c.deleted = true;
            c.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.chat().upsertConversation(c);
                g.outbox.enqueue(uid, Collections.CONVERSATIONS, c.id);
            });
            g.outbox.kick();
            if (id.equals(g.prefs.conversationId(uid))) g.prefs.setConversationId(uid, null);
            g.postResult(cb, Result.ok(null));
        });
    }

    public void send(String conversationId, String text, Result.Callback<Void> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            String clean = text.trim();
            if (clean.length() > MAX_INPUT) clean = clean.substring(0, MAX_INPUT);
            ChatMessageEntity user = message(uid, conversationId, ChatMessageEntity.ROLE_USER, clean);
            user.status = "pending";
            g.db.chat().upsert(user);
            touchConversation(uid, conversationId, clean);
            sendPending(user, cb);
        });
    }

    public void retry(String messageId, Result.Callback<Void> cb) {
        g.runIo(() -> {
            ChatMessageEntity m = g.db.chat().get(messageId);
            if (m == null) return;
            m.status = "pending";
            g.db.chat().upsert(m);
            sendPending(m, cb);
        });
    }

    private void sendPending(ChatMessageEntity user, Result.Callback<Void> cb) {
        String uid = user.uid;
        List<ChatMessageEntity> history = g.db.chat().recent(uid, user.conversationId, CONTEXT_MESSAGES - 1);
        JsonArray msgs = new JsonArray();
        for (int k = history.size() - 1; k >= 0; k--) {
            ChatMessageEntity h = history.get(k);
            JsonObject o = new JsonObject();
            o.addProperty("role", h.role);
            o.addProperty("content", h.text);
            msgs.add(o);
        }
        JsonObject last = new JsonObject();
        last.addProperty("role", "user");
        last.addProperty("content", user.text);
        msgs.add(last);
        try {
            HealthRag.Context rag = new HealthRag(g.db, ExerciseLibrary.get(g.context)).build(uid, user.text, g.prefs.stepGoal());
            String raw = g.ai.assistant(msgs, buildContext(uid, rag), null);
            AssistantReply reply = AssistantReplyParser.parse(raw);
            reply.sources = rag.sources;
            ChatMessageEntity bot = message(uid, user.conversationId, ChatMessageEntity.ROLE_ASSISTANT, reply.reply);
            bot.suggestionsJson = suggestionsToJson(reply);
            bot.createdAt = Math.max(bot.createdAt, user.createdAt + 1);
            user.status = "sent";
            user.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.chat().upsert(user);
                g.db.chat().upsert(bot);
                g.outbox.enqueue(uid, Collections.CHAT, user.id);
                g.outbox.enqueue(uid, Collections.CHAT, bot.id);
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(null));
        } catch (AiHttpException e) {
            if (com.transformsai.nutrifitai.BuildConfig.DEBUG) android.util.Log.w("NutriFit AI", "assistant http " + e.getMessage());
            markError(user);
            g.postResult(cb, Result.fail(e.getMessage()));
        } catch (Exception e) {
            if (com.transformsai.nutrifitai.BuildConfig.DEBUG) android.util.Log.w("NutriFit AI", "assistant failed", e);
            markError(user);
            g.postResult(cb, Result.fail("The assistant reply could not be read. Please try again."));
        }
    }

    /** Titles a new conversation after its first question and keeps history ordered by last use. */
    private void touchConversation(String uid, String id, String firstText) {
        ConversationEntity c = g.db.chat().conversation(id);
        if (c == null) return;
        if (c.title == null || c.title.isEmpty() || "Nutrition chat".equals(c.title) || "New chat".equals(c.title)) {
            String t = firstText.replace('\n', ' ').trim();
            c.title = t.length() > 48 ? t.substring(0, 47) + "…" : t;
        }
        c.updatedAt = DateUtil.nowMillis();
        g.db.runInTransaction(() -> {
            g.db.chat().upsertConversation(c);
            g.outbox.enqueue(uid, Collections.CONVERSATIONS, c.id);
        });
    }

    private void markError(ChatMessageEntity m) {
        m.status = "error";
        m.updatedAt = DateUtil.nowMillis();
        g.db.chat().upsert(m);
    }

    private ChatMessageEntity message(String uid, String cid, String role, String text) {
        ChatMessageEntity m = new ChatMessageEntity();
        m.id = Ids.newId();
        m.uid = uid;
        m.conversationId = cid;
        m.role = role;
        m.text = text;
        m.status = "sent";
        m.createdAt = DateUtil.nowMillis();
        m.updatedAt = m.createdAt;
        return m;
    }

    private JsonObject buildContext(String uid, HealthRag.Context rag) {
        JsonObject c = new JsonObject();
        c.addProperty("user_data", rag.text);
        c.addProperty("screen", screen);
        StringBuilder allowed = new StringBuilder();
        for (Exercise x : ExerciseLibrary.get(g.context).guided()) {
            if (allowed.length() > 0) allowed.append(',');
            allowed.append(x.id).append(':').append(x.isHold() ? "hold" : "reps");
        }
        c.addProperty("allowed_exercises", allowed.toString());
        String today = DateUtil.today();
        ProfileEntity p = g.db.profiles().get(uid);
        GoalEntity goal = g.db.goals().active(uid, today);
        List<DayTotals> t = g.db.diary().totalsRange(uid, today, today);
        DayTotals tot = t.isEmpty() ? null : t.get(0);
        if (p != null) {
            c.addProperty("goal", p.goal);
            c.addProperty("diet_preferences", p.dietPrefs == null ? "" : p.dietPrefs);
            c.addProperty("declared_allergies", p.allergies == null ? "" : p.allergies);
        }
        if (goal != null) {
            JsonObject r = new JsonObject();
            r.addProperty("calories_kcal", Math.round(goal.kcal - (tot == null ? 0 : tot.kcal)));
            r.addProperty("protein_g", Math.round(goal.proteinG - (tot == null ? 0 : tot.protein)));
            r.addProperty("carbohydrates_g", Math.round(goal.carbsG - (tot == null ? 0 : tot.carbs)));
            r.addProperty("fat_g", Math.round(goal.fatG - (tot == null ? 0 : tot.fat)));
            c.add("remaining_today", r);
        }
        c.addProperty("recent_training", g.workouts.recentTrainingSummary(uid));
        c.addProperty("local_hour", Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
        c.addProperty("region", "Bangladesh");
        return c;
    }

    static String suggestionsToJson(AssistantReply reply) {
        JsonArray arr = new JsonArray();
        for (AssistantReply.Suggestion s : reply.suggestions) {
            JsonObject o = new JsonObject();
            o.addProperty("title", s.title);
            o.addProperty("meal_type", s.mealType);
            o.addProperty("rationale", s.rationale);
            com.transformsai.nutrifitai.ai.AiResult wrapper = new com.transformsai.nutrifitai.ai.AiResult();
            wrapper.schemaVersion = "1.0";
            wrapper.status = "success";
            wrapper.inputType = "text";
            wrapper.isFood = true;
            wrapper.items = s.items;
            JsonObject full = com.google.gson.JsonParser.parseString(
                    com.transformsai.nutrifitai.ai.AiResultParser.toJson(wrapper)).getAsJsonObject();
            o.add("items", full.getAsJsonArray("items"));
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("reply", "-");
        root.add("suggestions", arr);
        JsonArray src = new JsonArray();
        for (String s : reply.sources) src.add(s);
        root.add("sources", src);
        if (reply.workoutJson != null) root.add("workout", com.google.gson.JsonParser.parseString(reply.workoutJson));
        JsonArray acts = new JsonArray();
        for (AssistantReply.Action a : reply.actions) {
            JsonObject o = new JsonObject();
            o.addProperty("type", a.type);
            switch (a.type) {
                case "log_water": o.addProperty("ml", a.ml); break;
                case "log_weight": o.addProperty("kg", a.kg); break;
                case "start_fast": o.addProperty("hours", a.hours); break;
                case "set_step_goal": o.addProperty("steps", a.steps); break;
                case "log_food": o.addProperty("text", a.text); o.addProperty("meal_type", a.mealType); break;
                case "open": o.addProperty("screen", a.screen); break;
                default: break;
            }
            acts.add(o);
        }
        root.add("actions", acts);
        return root.toString();
    }

    public static double kcalOf(List<AiItem> items) {
        double k = 0;
        for (AiItem i : items) k += i.kcal;
        return k;
    }
}

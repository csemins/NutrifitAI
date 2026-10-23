package com.transformsai.nutrifitai.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.transformsai.nutrifitai.BuildConfig;
import com.transformsai.nutrifitai.util.Ids;

/** Small device-level settings (not user records). */
public final class Prefs {
    private static final String FILE = "nutrifit_prefs";
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Prefs of(Context c) {
        return new Prefs(c);
    }

    public String installId() {
        String id = sp.getString("install_id", null);
        if (id == null) {
            id = Ids.newId();
            sp.edit().putString("install_id", id).apply();
        }
        return id;
    }

    public boolean useEmulators() {
        return BuildConfig.ALLOW_EMULATORS && sp.getBoolean("dev_use_emulators", false);
    }

    public void setUseEmulators(boolean v) {
        sp.edit().putBoolean("dev_use_emulators", v).commit();
    }

    /** Server URL: the built-in one unless changed in the hidden developer options. */
    public String aiBaseUrl() {
        String v = sp.getString("ai_base_url", null);
        return v == null || v.trim().isEmpty() ? BuildConfig.AI_BASE_URL : v.trim();
    }

    public void setAiBaseUrl(String url) {
        sp.edit().putString("ai_base_url", url).apply();
    }

    public int catalogVersion() {
        return sp.getInt("catalog_version", 0);
    }

    public void setCatalogVersion(int v) {
        sp.edit().putInt("catalog_version", v).apply();
    }

    public int usdaVersion() {
        return sp.getInt("usda_version", 0);
    }

    public void setUsdaVersion(int v) {
        sp.edit().putInt("usda_version", v).apply();
    }

    /** Per-device workout preferences (not account data). */
    public boolean voiceCoach() {
        return sp.getBoolean("voice_coach", true);
    }

    public void setVoiceCoach(boolean on) {
        sp.edit().putBoolean("voice_coach", on).apply();
    }

    public boolean workoutCamera() {
        return sp.getBoolean("workout_camera", true);
    }

    public void setWorkoutCamera(boolean on) {
        sp.edit().putBoolean("workout_camera", on).apply();
    }

    public String fitnessLevel() {
        return sp.getString("fitness_level", "beginner");
    }

    public void setFitnessLevel(String level) {
        sp.edit().putString("fitness_level", level).apply();
    }

    /** Daily training time as minutes after midnight, or -1 when not scheduled. */
    public int trainingMinute() {
        return sp.getInt("training_minute", 18 * 60);
    }

    public void setTrainingMinute(int m) {
        sp.edit().putInt("training_minute", m).apply();
    }

    public boolean trainingReminder() {
        return sp.getBoolean("training_reminder", false);
    }

    public void setTrainingReminder(boolean on) {
        sp.edit().putBoolean("training_reminder", on).apply();
    }

    public String speechLanguage() {
        return sp.getString("speech_lang", "en-US");
    }

    public void setSpeechLanguage(String tag) {
        sp.edit().putString("speech_lang", tag).apply();
    }

    public boolean notifRationaleShown() {
        return sp.getBoolean("notif_rationale", false);
    }

    public void setNotifRationaleShown() {
        sp.edit().putBoolean("notif_rationale", true).apply();
    }

    public String conversationId(String uid) {
        return sp.getString("conv_" + uid, null);
    }

    public void setConversationId(String uid, String id) {
        sp.edit().putString("conv_" + uid, id).apply();
    }

    // ---------------------------------------------------------------- coach + advanced
    /** relaxed | standard | strict - how strictly the camera coach judges form. */
    public String coachStrictness() {
        return sp.getString("coach_strictness", "standard");
    }

    public void setCoachStrictness(String v) {
        sp.edit().putString("coach_strictness", v).apply();
    }

    /** Rest between exercises, seconds. */
    public int restSeconds() {
        return sp.getInt("rest_seconds", 15);
    }

    public void setRestSeconds(int s) {
        sp.edit().putInt("rest_seconds", s).apply();
    }

    /** Pose model: auto | lite | full | heavy. */
    /** AI access was requested from this account (the request is sent only once). */
    public boolean aiAccessRequested(String uid) {
        return sp.getBoolean("ai_req_" + uid, false);
    }

    public void setAiAccessRequested(String uid) {
        sp.edit().putBoolean("ai_req_" + uid, true).apply();
    }

    /** createdAt of the newest inbox item already shown as a phone notification. */
    /** Newest notification already shown; the first call fixes "now" as the start so later items are newer. */
    public long inboxNotifiedAt(String uid) {
        String key = "inbox_seen_" + uid;
        if (!sp.contains(key)) {
            long now = System.currentTimeMillis() - 60_000L; // small allowance for clock differences between phones
            sp.edit().putLong(key, now).apply();
            return now;
        }
        return sp.getLong(key, 0);
    }

    public void setInboxNotifiedAt(String uid, long t) {
        sp.edit().putLong("inbox_seen_" + uid, t).apply();
    }

    /** When I last opened a chat (for its unread dot). */
    public long chatReadAt(String chatId) {
        return sp.getLong("chat_read_" + chatId, 0);
    }

    public void setChatReadAt(String chatId, long t) {
        sp.edit().putLong("chat_read_" + chatId, t).apply();
    }

    /** Appearance: system | light | dark. */
    public String theme() {
        return sp.getString("theme", "system");
    }

    public void setTheme(String v) {
        sp.edit().putString("theme", v).apply();
    }

    public String poseModel() {
        return sp.getString("pose_model", "auto");
    }

    public void setPoseModel(String v) {
        sp.edit().putString("pose_model", v).apply();
    }

    /** Pose processor: auto | gpu | cpu. */
    public String poseDelegate() {
        return sp.getString("pose_delegate", "auto");
    }

    public void setPoseDelegate(String v) {
        sp.edit().putString("pose_delegate", v).apply();
    }

    public boolean showSkeleton() {
        return sp.getBoolean("show_skeleton", true);
    }

    public void setShowSkeleton(boolean on) {
        sp.edit().putBoolean("show_skeleton", on).apply();
    }

    public boolean haptics() {
        return sp.getBoolean("haptics", true);
    }

    public void setHaptics(boolean on) {
        sp.edit().putBoolean("haptics", on).apply();
    }

    /** Hidden developer options (unlocked by tapping the version 7 times). */
    public boolean devOptions() {
        return sp.getBoolean("dev_options", false);
    }

    public void setDevOptions(boolean on) {
        sp.edit().putBoolean("dev_options", on).apply();
    }

    // ---------------------------------------------------------------- steps + health connect
    public int stepGoal() {
        return sp.getInt("step_goal", 8000);
    }

    public void setStepGoal(int g) {
        sp.edit().putInt("step_goal", g).apply();
    }

    public boolean stepTracking() {
        return sp.getBoolean("step_tracking", false);
    }

    public void setStepTracking(boolean on) {
        sp.edit().putBoolean("step_tracking", on).apply();
    }

    public boolean healthConnect() {
        return sp.getBoolean("health_connect", false);
    }

    public void setHealthConnect(boolean on) {
        sp.edit().putBoolean("health_connect", on).apply();
    }

    public long healthLastSync() {
        return sp.getLong("health_last_sync", 0);
    }

    public void setHealthLastSync(long t) {
        sp.edit().putLong("health_last_sync", t).apply();
    }

    /** Step counter (counts since boot) baseline for a date: "date|value|bootCount". */
    public String stepBaseline() {
        return sp.getString("step_baseline", null);
    }

    public void setStepBaseline(String v) {
        sp.edit().putString("step_baseline", v).apply();
    }

    // ---------------------------------------------------------------- fasting
    /** Fasting window start (epoch ms), 0 when not fasting. */
    public long fastingStart() {
        return sp.getLong("fast_start", 0);
    }

    public void setFastingStart(long t) {
        sp.edit().putLong("fast_start", t).apply();
    }

    public int fastingHours() {
        return sp.getInt("fast_hours", 16);
    }

    public void setFastingHours(int h) {
        sp.edit().putInt("fast_hours", h).apply();
    }

    public void clearAccount(String uid) {
        sp.edit().remove("conv_" + uid).apply();
    }
}

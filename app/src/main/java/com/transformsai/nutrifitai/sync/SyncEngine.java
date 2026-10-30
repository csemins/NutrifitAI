package com.transformsai.nutrifitai.sync;

import android.util.Log;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.data.db.AppDatabase;
import com.transformsai.nutrifitai.data.db.entity.AiAnalysisEntity;
import com.transformsai.nutrifitai.data.db.entity.ChatMessageEntity;
import com.transformsai.nutrifitai.data.db.entity.ConversationEntity;
import com.transformsai.nutrifitai.data.db.entity.FavoriteEntity;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.entity.RecipeEntity;
import com.transformsai.nutrifitai.data.db.entity.RecipeIngredientEntity;
import com.transformsai.nutrifitai.data.db.entity.ReminderSettingsEntity;
import com.transformsai.nutrifitai.data.db.entity.SyncMetaEntity;
import com.transformsai.nutrifitai.data.db.entity.SyncOpEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutPlanEntity;
import com.transformsai.nutrifitai.data.db.entity.FastEntity;
import com.transformsai.nutrifitai.data.db.entity.StepDayEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.data.remote.BucketClient;
import com.transformsai.nutrifitai.data.remote.FirestoreMapper;
import com.transformsai.nutrifitai.util.ImageUtils;
import com.transformsai.nutrifitai.util.DateUtil;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Room <-> Firestore synchronisation. Strategy (see docs/SYNC.md):
 *  - Room is the source of truth for the UI; every local write enqueues an outbox op.
 *  - PUSH: each op uploads the full current record with set() at a stable id => idempotent.
 *  - Deletes are tombstones (deleted=true + updatedAt), so other devices learn about them.
 *  - PULL: per collection, fetch docs with updatedAt > watermark and apply via ConflictResolver.
 *  - Images upload to Storage before the meal doc; Firestore stores only the storage path.
 */
public final class SyncEngine {
    private static final String TAG = "NutriFitSync";
    private static final long TIMEOUT_S = 30;
    private final AppDatabase db;
    private final FirebaseFirestore fs = FirebaseFirestore.getInstance();
    private final String uid;
    private final BucketClient bucket;
    private final android.content.Context context;
    /** Meal entries whose photo exists in the bucket but not on this device (pulled from another device). */
    private final List<String> missingImages = new ArrayList<>();

    public SyncEngine(android.content.Context context, AppDatabase db, String uid) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.uid = uid;
        this.bucket = new BucketClient(new com.transformsai.nutrifitai.data.Prefs(context));
    }

    private DocumentReference userDoc() {
        return fs.collection("users").document(uid);
    }

    private CollectionReference col(String name) {
        return userDoc().collection(name);
    }

    // ================= PUSH =================
    /** @return number of ops uploaded. Throws on the first failure so WorkManager can back off. */
    public int push() throws Exception {
        int done = 0;
        while (true) {
            List<SyncOpEntity> ops = db.sync().pending(uid, 50);
            if (ops.isEmpty()) return done;
            for (SyncOpEntity op : ops) {
                try {
                    pushOne(op);
                    db.sync().ack(op.seq);
                    done++;
                } catch (Exception e) {
                    db.sync().fail(op.seq, e.getClass().getSimpleName());
                    throw e;
                }
            }
        }
    }

    private void pushOne(SyncOpEntity op) throws Exception {
        Map<String, Object> data;
        DocumentReference ref;
        switch (op.collection) {
            case Collections.PROFILE: {
                ProfileEntity p = db.profiles().get(uid);
                if (p == null) return;
                data = FirestoreMapper.toMap(p);
                ref = userDoc();
                break;
            }
            case Collections.GOALS: {
                GoalEntity g = db.goals().byId(op.docId);
                if (g == null) return;
                data = FirestoreMapper.toMap(g);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.FOODS: {
                FoodEntity f = db.foods().byId(op.docId);
                if (f == null || f.ownerUid == null) return;
                data = FirestoreMapper.toMap(f);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.FAVORITES: {
                FavoriteEntity f = db.foods().favorite(uid, op.docId);
                if (f == null) return;
                data = FirestoreMapper.toMap(f);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.RECIPES: {
                RecipeEntity r = db.recipes().get(op.docId);
                if (r == null) return;
                data = FirestoreMapper.toMap(r);
                List<Map<String, Object>> ing = new ArrayList<>();
                for (RecipeIngredientEntity i : db.recipes().ingredients(r.id)) ing.add(FirestoreMapper.toMap(i));
                data.put("ingredients", ing);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.MEALS: {
                MealEntryEntity e = db.diary().entry(op.docId);
                if (e == null) return;
                uploadMealImageIfNeeded(e);
                data = FirestoreMapper.toMap(e);
                List<Map<String, Object>> items = new ArrayList<>();
                for (MealItemEntity i : db.diary().liveItemsOf(e.id)) items.add(FirestoreMapper.toMap(i));
                data.put("items", items);
                ref = col(op.collection).document(op.docId);
                if (e.deleted && e.imageRemotePath != null) deleteStorageQuietly(e.imageRemotePath);
                break;
            }
            case Collections.WATER: {
                WaterEntity w = db.water().byId(op.docId);
                if (w == null) return;
                data = FirestoreMapper.toMap(w);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.WEIGHTS: {
                WeightEntity w = db.weights().byId(op.docId);
                if (w == null) return;
                data = FirestoreMapper.toMap(w);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.WORKOUTS: {
                WorkoutSessionEntity w = db.workouts().session(op.docId);
                if (w == null) return;
                data = FirestoreMapper.toMap(w);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.WORKOUT_PLANS: {
                WorkoutPlanEntity w = db.workouts().plan(op.docId);
                if (w == null) return;
                data = FirestoreMapper.toMap(w);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.STEPS: {
                StepDayEntity w = db.activity().stepsById(op.docId);
                if (w == null) return;
                data = FirestoreMapper.toMap(w);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.FASTS: {
                FastEntity w = db.activity().fast(op.docId);
                if (w == null) return;
                data = FirestoreMapper.toMap(w);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.SETTINGS: {
                ReminderSettingsEntity s = db.reminders().get(uid);
                if (s == null) return;
                data = FirestoreMapper.toMap(s);
                ref = col(op.collection).document("reminders");
                break;
            }
            case Collections.CHAT: {
                ChatMessageEntity m = db.chat().get(op.docId);
                if (m == null) return;
                data = FirestoreMapper.toMap(m);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.CONVERSATIONS: {
                ConversationEntity c = db.chat().conversation(op.docId);
                if (c == null) return;
                data = FirestoreMapper.toMap(c);
                ref = col(op.collection).document(op.docId);
                break;
            }
            case Collections.AI: {
                AiAnalysisEntity a = db.ai().get(op.docId);
                if (a == null) return;
                data = FirestoreMapper.toMap(a);
                ref = col(op.collection).document(op.docId);
                break;
            }
            default:
                Log.w(TAG, "Unknown collection " + op.collection);
                return;
        }
        Tasks.await(ref.set(data), TIMEOUT_S, TimeUnit.SECONDS);
    }

    private void uploadMealImageIfNeeded(MealEntryEntity e) throws Exception {
        if (e.deleted || e.imageRemotePath != null || e.imageLocalPath == null) return;
        File f = new File(e.imageLocalPath);
        if (!f.exists()) return;
        String path = BucketClient.mealPath(uid, e.id);
        bucket.upload(path, f); // idempotent PUT to a stable path
        e.imageRemotePath = path;
        // Persist the remote path without creating a new outbox op (we are uploading it right now).
        MealEntryEntity cur = db.diary().entry(e.id);
        if (cur != null) {
            cur.imageRemotePath = path;
            db.diary().upsertEntry(cur);
        }
    }

    private void deleteStorageQuietly(String path) {
        try {
            bucket.delete(path);
        } catch (Exception ignored) {
            // already gone or offline; the tombstone still propagates
        }
    }

    /** Download photos referenced by pulled meals so they show on this device too. */
    private void fetchMissingImages() {
        for (String entryId : missingImages) {
            MealEntryEntity e = db.diary().entry(entryId);
            if (e == null || e.imageRemotePath == null || e.deleted) continue;
            try {
                File out = new File(ImageUtils.mealImageDir(context), e.id + ".jpg");
                if (bucket.download(e.imageRemotePath, out)) {
                    e.imageLocalPath = out.getAbsolutePath();
                    db.diary().upsertEntry(e);
                }
            } catch (Exception ex) {
                Log.w(TAG, "Photo download deferred: " + ex.getClass().getSimpleName());
            }
        }
        missingImages.clear();
    }

    // ================= PULL =================
    public int pull() throws Exception {
        int applied = 0;
        DocumentSnapshot profile = Tasks.await(userDoc().get(Source.SERVER), TIMEOUT_S, TimeUnit.SECONDS);
        if (profile.exists() && profile.getData() != null) {
            ProfileEntity remote = FirestoreMapper.fromMap(profile.getData(), ProfileEntity.class);
            ProfileEntity local = db.profiles().get(uid);
            boolean pending = db.sync().hasPending(uid, Collections.PROFILE, uid) > 0;
            if (remote != null && ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remote.updatedAt)) {
                remote.uid = uid;
                if (local != null) remote.avatarLocalPath = local.avatarLocalPath;
                db.profiles().upsert(remote);
                applied++;
            }
        }
        for (String c : Collections.PULL_ORDER) applied += pullCollection(c);
        fetchMissingImages();
        return applied;
    }

    private int pullCollection(String name) throws Exception {
        SyncMetaEntity meta = db.sync().meta(uid, name);
        long watermark = meta == null ? 0 : meta.lastPulledUpdatedAt;
        int applied = 0;
        while (true) {
            QuerySnapshot snap = Tasks.await(col(name).whereGreaterThan("updatedAt", watermark)
                    .orderBy("updatedAt", Query.Direction.ASCENDING).limit(200).get(Source.SERVER), TIMEOUT_S, TimeUnit.SECONDS);
            if (snap.isEmpty()) break;
            for (DocumentSnapshot d : snap.getDocuments()) {
                Map<String, Object> m = d.getData();
                if (m == null) continue;
                try {
                    if (applyRemote(name, d.getId(), m)) applied++;
                } catch (Exception ex) {
                    Log.w(TAG, "Skipping malformed remote doc in " + name);
                }
                Object u = m.get("updatedAt");
                if (u instanceof Number) watermark = Math.max(watermark, ((Number) u).longValue());
            }
            if (snap.size() < 200) break;
        }
        SyncMetaEntity nm = new SyncMetaEntity();
        nm.uid = uid;
        nm.collection = name;
        nm.lastPulledUpdatedAt = watermark;
        nm.lastSyncAt = DateUtil.nowMillis();
        db.sync().upsertMeta(nm);
        return applied;
    }

    @SuppressWarnings("unchecked")
    private boolean applyRemote(String name, String docId, Map<String, Object> m) {
        long remoteUpdated = m.get("updatedAt") instanceof Number ? ((Number) m.get("updatedAt")).longValue() : 0;
        boolean pending = db.sync().hasPending(uid, name, docId) > 0;
        switch (name) {
            case Collections.GOALS: {
                GoalEntity local = db.goals().byId(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                GoalEntity r = FirestoreMapper.fromMap(m, GoalEntity.class);
                r.uid = uid;
                db.goals().upsert(r);
                return true;
            }
            case Collections.FOODS: {
                FoodEntity local = db.foods().byId(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                FoodEntity r = FirestoreMapper.fromMap(m, FoodEntity.class);
                r.ownerUid = uid;
                db.foods().upsertFood(r);
                return true;
            }
            case Collections.FAVORITES: {
                FavoriteEntity local = db.foods().favorite(uid, docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                FavoriteEntity r = FirestoreMapper.fromMap(m, FavoriteEntity.class);
                r.uid = uid;
                db.foods().upsertFavorite(r);
                return true;
            }
            case Collections.RECIPES: {
                RecipeEntity local = db.recipes().get(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                Object ing = m.remove("ingredients");
                RecipeEntity r = FirestoreMapper.fromMap(m, RecipeEntity.class);
                r.uid = uid;
                db.runInTransaction(() -> {
                    db.recipes().upsert(r);
                    db.recipes().deleteIngredients(r.id);
                    List<RecipeIngredientEntity> list = new ArrayList<>();
                    if (ing instanceof List) {
                        for (Object o : (List<Object>) ing) {
                            RecipeIngredientEntity x = FirestoreMapper.fromMap((Map<String, Object>) o, RecipeIngredientEntity.class);
                            x.recipeId = r.id;
                            list.add(x);
                        }
                    }
                    db.recipes().upsertIngredients(list);
                });
                return true;
            }
            case Collections.MEALS: {
                MealEntryEntity local = db.diary().entry(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                Object items = m.remove("items");
                MealEntryEntity r = FirestoreMapper.fromMap(m, MealEntryEntity.class);
                r.uid = uid;
                r.imageLocalPath = local == null ? null : local.imageLocalPath;
                if (r.imageRemotePath != null && (r.imageLocalPath == null || !new File(r.imageLocalPath).exists())) {
                    missingImages.add(r.id);
                }
                db.runInTransaction(() -> {
                    db.diary().upsertEntry(r);
                    db.diary().deleteItemsOf(r.id);
                    List<MealItemEntity> list = new ArrayList<>();
                    if (items instanceof List) {
                        for (Object o : (List<Object>) items) {
                            MealItemEntity x = FirestoreMapper.fromMap((Map<String, Object>) o, MealItemEntity.class);
                            x.entryId = r.id;
                            list.add(x);
                        }
                    }
                    db.diary().upsertItems(list);
                });
                return true;
            }
            case Collections.WATER: {
                WaterEntity local = db.water().byId(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                WaterEntity r = FirestoreMapper.fromMap(m, WaterEntity.class);
                r.uid = uid;
                db.water().upsert(r);
                return true;
            }
            case Collections.WEIGHTS: {
                WeightEntity local = db.weights().byId(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                WeightEntity r = FirestoreMapper.fromMap(m, WeightEntity.class);
                r.uid = uid;
                db.weights().upsert(r);
                return true;
            }
            case Collections.WORKOUTS: {
                WorkoutSessionEntity local = db.workouts().session(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                WorkoutSessionEntity r = FirestoreMapper.fromMap(m, WorkoutSessionEntity.class);
                r.uid = uid;
                db.workouts().upsertSession(r);
                return true;
            }
            case Collections.WORKOUT_PLANS: {
                WorkoutPlanEntity local = db.workouts().plan(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                WorkoutPlanEntity r = FirestoreMapper.fromMap(m, WorkoutPlanEntity.class);
                r.uid = uid;
                db.workouts().upsertPlan(r);
                return true;
            }
            case Collections.STEPS: {
                StepDayEntity local = db.activity().stepsById(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                StepDayEntity r = FirestoreMapper.fromMap(m, StepDayEntity.class);
                r.uid = uid;
                // never lower a day's count: this phone's own sensor may have counted more since
                if (local != null && !r.deleted && local.steps > r.steps) return false;
                db.activity().upsertSteps(r);
                return true;
            }
            case Collections.FASTS: {
                FastEntity local = db.activity().fast(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                FastEntity r = FirestoreMapper.fromMap(m, FastEntity.class);
                r.uid = uid;
                db.activity().upsertFast(r);
                return true;
            }
            case Collections.SETTINGS: {
                if (!"reminders".equals(docId)) return false;
                ReminderSettingsEntity local = db.reminders().get(uid);
                boolean p = db.sync().hasPending(uid, Collections.SETTINGS, "reminders") > 0;
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, p, remoteUpdated)) return false;
                ReminderSettingsEntity r = FirestoreMapper.fromMap(m, ReminderSettingsEntity.class);
                r.uid = uid;
                db.reminders().upsert(r);
                return true;
            }
            case Collections.CHAT: {
                ChatMessageEntity local = db.chat().get(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                ChatMessageEntity r = FirestoreMapper.fromMap(m, ChatMessageEntity.class);
                r.uid = uid;
                db.chat().upsert(r);
                return true;
            }
            case Collections.CONVERSATIONS: {
                ConversationEntity local = db.chat().conversation(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                ConversationEntity r = FirestoreMapper.fromMap(m, ConversationEntity.class);
                r.uid = uid;
                db.chat().upsertConversation(r);
                return true;
            }
            case Collections.AI: {
                AiAnalysisEntity local = db.ai().get(docId);
                if (!ConflictResolver.applyRemote(local == null ? null : local.updatedAt, pending, remoteUpdated)) return false;
                AiAnalysisEntity r = FirestoreMapper.fromMap(m, AiAnalysisEntity.class);
                r.uid = uid;
                r.imageLocalPath = local == null ? null : local.imageLocalPath;
                db.ai().upsert(r);
                return true;
            }
            default:
                return false;
        }
    }

    // ================= ACCOUNT DELETION =================
    /** Deletes every cloud document and image owned by the user. Requires network. */
    public void deleteAllRemote() throws Exception {
        for (String c : Collections.PULL_ORDER) {
            while (true) {
                QuerySnapshot snap = Tasks.await(col(c).limit(300).get(Source.SERVER), TIMEOUT_S, TimeUnit.SECONDS);
                if (snap.isEmpty()) break;
                com.google.firebase.firestore.WriteBatch b = fs.batch();
                for (DocumentSnapshot d : snap.getDocuments()) b.delete(d.getReference());
                Tasks.await(b.commit(), TIMEOUT_S, TimeUnit.SECONDS);
            }
        }
        bucket.deleteUser(uid);
        Tasks.await(userDoc().delete(), TIMEOUT_S, TimeUnit.SECONDS);
    }

    @Nullable
    public static String describe(Exception e) {
        String n = e.getClass().getSimpleName();
        if (e.getCause() != null) n += "/" + e.getCause().getClass().getSimpleName();
        return n;
    }

    public static Map<String, Object> debugCounts() {
        return new HashMap<>();
    }
}

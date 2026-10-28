package com.transformsai.nutrifitai.data.repo;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.ai.AiItem;
import com.transformsai.nutrifitai.ai.AiResult;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.model.DayTotals;
import com.transformsai.nutrifitai.data.db.model.ItemRow;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;

import java.util.ArrayList;
import java.util.List;

/**
 * Meal diary. Entries hold items; totals are always computed by SQL from canonical items, so there
 * are no cached aggregates to drift. Every mutation bumps the entry's updatedAt and enqueues it.
 */
public class DiaryRepository {
    private final AppGraph g;

    public DiaryRepository(AppGraph g) {
        this.g = g;
    }

    public LiveData<List<ItemRow>> observeDay(String uid, String date) {
        return g.db.diary().observeDay(uid, date);
    }

    public LiveData<DayTotals> observeTotals(String uid, String date) {
        return g.db.diary().observeTotals(uid, date);
    }

    public static String statusFor(String date, boolean planned) {
        return planned || DateUtil.isFuture(date) ? MealEntryEntity.STATUS_PLANNED : MealEntryEntity.STATUS_CONSUMED;
    }

    private MealEntryEntity newEntry(String uid, String date, String mealType, String status, String source) {
        MealEntryEntity e = new MealEntryEntity();
        long now = DateUtil.nowMillis();
        e.id = Ids.newId();
        e.uid = uid;
        e.date = date;
        e.mealType = mealType;
        e.status = status;
        e.source = source;
        e.zoneId = DateUtil.zone().getId();
        e.consumedAt = MealEntryEntity.STATUS_CONSUMED.equals(status) ? now : null;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public static MealItemEntity itemFromFood(FoodEntity f, double servings, String servingLabel, double servingGrams) {
        MealItemEntity i = new MealItemEntity();
        i.id = Ids.newId();
        i.foodId = f.id;
        i.name = f.name;
        i.nameBn = f.nameBn;
        i.servings = servings;
        i.servingLabel = servingLabel;
        i.servingGrams = servingGrams;
        i.kcal100 = f.kcal100;
        i.protein100 = f.protein100;
        i.carbs100 = f.carbs100;
        i.fat100 = f.fat100;
        i.fibre100 = f.fibre100;
        i.sugar100 = f.sugar100;
        i.sodiumMg100 = f.sodiumMg100;
        i.provenance = f.provenance;
        i.emoji = f.emoji;
        i.updatedAt = DateUtil.nowMillis();
        return i;
    }

    /** Adds one or more items as a single entry. */
    public void addItems(String date, String mealType, boolean planned, String source, List<MealItemEntity> items,
                         @Nullable String imagePath, @Nullable String analysisId, Result.Callback<String> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            MealEntryEntity e = newEntry(uid, date, mealType, statusFor(date, planned), source);
            e.imageLocalPath = imagePath;
            e.aiAnalysisId = analysisId;
            for (int i = 0; i < items.size(); i++) {
                items.get(i).entryId = e.id;
                items.get(i).position = i;
                items.get(i).updatedAt = e.updatedAt;
            }
            g.db.runInTransaction(() -> {
                g.db.diary().upsertEntry(e);
                g.db.diary().upsertItems(items);
                g.outbox.enqueue(uid, Collections.MEALS, e.id);
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(e.id));
        });
    }

    /** Converts a confirmed AI result into diary items (user has reviewed every item). */
    public static List<MealItemEntity> itemsFromAi(AiResult r) {
        List<MealItemEntity> out = new ArrayList<>();
        for (AiItem a : r.activeItems()) {
            MealItemEntity i = new MealItemEntity();
            i.id = Ids.newId();
            i.name = a.name;
            i.nameBn = a.nameBn;
            i.servings = 1;
            i.servingLabel = a.unit == null || a.unit.isEmpty() ? "portion" : (com.transformsai.nutrifitai.util.Units.num(a.quantity) + " " + a.unit);
            i.servingGrams = a.weightG;
            i.kcal100 = a.kcal100();
            i.protein100 = a.protein100();
            i.carbs100 = a.carbs100();
            i.fat100 = a.fat100();
            i.fibre100 = a.fibre100();
            i.sugar100 = a.sugar100();
            i.sodiumMg100 = a.sodium100();
            i.provenance = FoodEntity.PROV_AI;
            i.confidence = a.confidence;
            i.emoji = "✨";
            out.add(i);
        }
        return out;
    }

    private void touchEntry(String uid, String entryId) {
        MealEntryEntity e = g.db.diary().entry(entryId);
        if (e == null) return;
        if (g.db.diary().liveItemsOf(entryId).isEmpty()) e.deleted = true; // tombstone empty entries
        e.updatedAt = DateUtil.nowMillis();
        g.db.diary().upsertEntry(e);
        g.outbox.enqueue(uid, Collections.MEALS, entryId);
    }

    /** Change portion: servings of a serving size (use label "g" and grams 1 for gram entry). */
    public void updatePortion(String itemId, double servings, String servingLabel, double servingGrams) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                MealItemEntity i = g.db.diary().item(itemId);
                if (i == null) return;
                i.servings = servings;
                i.servingLabel = servingLabel;
                i.servingGrams = servingGrams;
                i.updatedAt = DateUtil.nowMillis();
                g.db.diary().upsertItem(i);
                touchEntry(uid, i.entryId);
            });
            g.outbox.kick();
        });
    }

    /** Replace an item's stored per-100 g values and name (manual correction). */
    public void updateItem(MealItemEntity edited) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                edited.updatedAt = DateUtil.nowMillis();
                g.db.diary().upsertItem(edited);
                touchEntry(uid, edited.entryId);
            });
            g.outbox.kick();
        });
    }

    public void deleteItem(String itemId, Result.Callback<MealItemEntity> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            MealItemEntity[] removed = new MealItemEntity[1];
            g.db.runInTransaction(() -> {
                MealItemEntity i = g.db.diary().item(itemId);
                if (i == null) return;
                i.deleted = true;
                i.updatedAt = DateUtil.nowMillis();
                g.db.diary().upsertItem(i);
                touchEntry(uid, i.entryId);
                removed[0] = i;
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(removed[0]));
        });
    }

    /** Undo for delete. */
    public void restoreItem(String itemId) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                MealItemEntity i = g.db.diary().item(itemId);
                if (i == null) return;
                i.deleted = false;
                i.updatedAt = DateUtil.nowMillis();
                g.db.diary().upsertItem(i);
                MealEntryEntity e = g.db.diary().entry(i.entryId);
                if (e != null) {
                    e.deleted = false;
                    e.updatedAt = i.updatedAt;
                    g.db.diary().upsertEntry(e);
                    g.outbox.enqueue(uid, Collections.MEALS, e.id);
                }
            });
            g.outbox.kick();
        });
    }

    /** Copy one item into a new entry at date/meal (duplicate = same date & meal). */
    public void copyItem(String itemId, String date, String mealType, boolean move, Result.Callback<Void> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                MealItemEntity src = g.db.diary().item(itemId);
                if (src == null) return;
                MealEntryEntity srcEntry = g.db.diary().entry(src.entryId);
                MealEntryEntity e = newEntry(uid, date, mealType, statusFor(date, false), "copy");
                if (srcEntry != null && move) {
                    e.imageLocalPath = srcEntry.imageLocalPath;
                    e.imageRemotePath = null;
                }
                MealItemEntity c = cloneItem(src, e.id, 0);
                g.db.diary().upsertEntry(e);
                g.db.diary().upsertItem(c);
                g.outbox.enqueue(uid, Collections.MEALS, e.id);
                if (move) {
                    src.deleted = true;
                    src.updatedAt = DateUtil.nowMillis();
                    g.db.diary().upsertItem(src);
                    touchEntry(uid, src.entryId);
                }
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(null));
        });
    }

    /** Copy every item of a meal (date + type) to another date/meal. Future targets become planned. */
    public void copyMeal(String fromDate, String mealType, String toDate, String toMealType, Result.Callback<Integer> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            int[] count = {0};
            g.db.runInTransaction(() -> {
                List<MealItemEntity> all = new ArrayList<>();
                for (MealEntryEntity src : g.db.diary().entriesFor(uid, fromDate, mealType)) {
                    all.addAll(g.db.diary().liveItemsOf(src.id));
                }
                if (all.isEmpty()) return;
                MealEntryEntity e = newEntry(uid, toDate, toMealType, statusFor(toDate, false), "copy");
                List<MealItemEntity> copies = new ArrayList<>();
                for (int k = 0; k < all.size(); k++) copies.add(cloneItem(all.get(k), e.id, k));
                g.db.diary().upsertEntry(e);
                g.db.diary().upsertItems(copies);
                g.outbox.enqueue(uid, Collections.MEALS, e.id);
                count[0] = copies.size();
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(count[0]));
        });
    }

    /** Planned -> consumed. Logged on the entry's own date (must not be in the future). */
    public void markEaten(String itemId, Result.Callback<Void> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            String[] err = {null};
            g.db.runInTransaction(() -> {
                MealItemEntity i = g.db.diary().item(itemId);
                if (i == null) return;
                MealEntryEntity e = g.db.diary().entry(i.entryId);
                if (e == null) return;
                if (DateUtil.isFuture(e.date)) {
                    err[0] = "future";
                    return;
                }
                List<MealItemEntity> siblings = g.db.diary().liveItemsOf(e.id);
                if (siblings.size() == 1) {
                    e.status = MealEntryEntity.STATUS_CONSUMED;
                    e.consumedAt = DateUtil.nowMillis();
                    e.updatedAt = e.consumedAt;
                    g.db.diary().upsertEntry(e);
                    g.outbox.enqueue(uid, Collections.MEALS, e.id);
                } else {
                    // Split the eaten item into its own consumed entry.
                    MealEntryEntity n = newEntry(uid, e.date, e.mealType, MealEntryEntity.STATUS_CONSUMED, "plan");
                    g.db.diary().upsertEntry(n);
                    g.db.diary().upsertItem(cloneItem(i, n.id, 0));
                    g.outbox.enqueue(uid, Collections.MEALS, n.id);
                    i.deleted = true;
                    i.updatedAt = DateUtil.nowMillis();
                    g.db.diary().upsertItem(i);
                    touchEntry(uid, e.id);
                }
            });
            g.outbox.kick();
            g.postResult(cb, err[0] == null ? Result.ok(null) : Result.fail(err[0]));
        });
    }

    private static MealItemEntity cloneItem(MealItemEntity s, String entryId, int pos) {
        MealItemEntity c = new MealItemEntity();
        c.id = Ids.newId();
        c.entryId = entryId;
        c.foodId = s.foodId;
        c.name = s.name;
        c.nameBn = s.nameBn;
        c.servings = s.servings;
        c.servingLabel = s.servingLabel;
        c.servingGrams = s.servingGrams;
        c.kcal100 = s.kcal100;
        c.protein100 = s.protein100;
        c.carbs100 = s.carbs100;
        c.fat100 = s.fat100;
        c.fibre100 = s.fibre100;
        c.sugar100 = s.sugar100;
        c.sodiumMg100 = s.sodiumMg100;
        c.provenance = s.provenance;
        c.confidence = s.confidence;
        c.emoji = s.emoji;
        c.position = pos;
        c.updatedAt = DateUtil.nowMillis();
        return c;
    }

    public void row(String itemId, Result.Callback<ItemRow> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(g.db.diary().row(g.session.requireUid(), itemId))));
    }

    public void hasConsumed(String uid, String date, String mealType, Result.Callback<Boolean> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(g.db.diary().consumedEntryCount(uid, date, mealType) > 0)));
    }
}

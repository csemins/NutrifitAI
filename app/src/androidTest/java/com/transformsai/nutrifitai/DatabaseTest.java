package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.transformsai.nutrifitai.data.db.AppDatabase;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.SyncOpEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.model.DayTotals;
import com.transformsai.nutrifitai.data.db.model.ItemRow;
import com.transformsai.nutrifitai.data.repo.AuthRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

/** Room CRUD, planned-vs-consumed totals, outbox de-duplication and per-account isolation. */
@RunWith(AndroidJUnit4.class)
public class DatabaseTest {
    private AppDatabase db;

    @Before
    public void open() {
        Context c = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(c, AppDatabase.class).allowMainThreadQueries().build();
    }

    @After
    public void close() {
        db.close();
    }

    private void entry(String id, String uid, String date, String status, double grams, double kcal100) {
        MealEntryEntity e = new MealEntryEntity();
        e.id = id;
        e.uid = uid;
        e.date = date;
        e.mealType = "lunch";
        e.status = status;
        db.diary().upsertEntry(e);
        MealItemEntity i = new MealItemEntity();
        i.id = id + "-i";
        i.entryId = id;
        i.name = "Rice";
        i.servings = 1;
        i.servingGrams = grams;
        i.kcal100 = kcal100;
        i.protein100 = 2.7;
        i.carbs100 = 28;
        i.fat100 = 0.3;
        i.fibre100 = null;
        db.diary().upsertItems(Collections.singletonList(i));
    }

    @Test
    public void plannedMealsDoNotCountAsConsumed() {
        entry("a", "u1", "2026-11-05", MealEntryEntity.STATUS_CONSUMED, 200, 130);
        entry("b", "u1", "2026-11-05", MealEntryEntity.STATUS_PLANNED, 300, 130);
        List<DayTotals> t = db.diary().totalsRange("u1", "2026-11-05", "2026-11-05");
        assertEquals(1, t.size());
        assertEquals(260, t.get(0).kcal, 1e-6);
        assertEquals(2, db.diary().day("u1", "2026-11-05").size());
    }

    @Test
    public void editAndSoftDeleteUpdateTotals() {
        entry("a", "u1", "2026-11-05", MealEntryEntity.STATUS_CONSUMED, 100, 130);
        MealItemEntity i = db.diary().item("a-i");
        i.servings = 2;
        db.diary().upsertItem(i);
        assertEquals(260, db.diary().totalsRange("u1", "2026-11-05", "2026-11-05").get(0).kcal, 1e-6);
        i.deleted = true;
        db.diary().upsertItem(i);
        assertTrue(db.diary().totalsRange("u1", "2026-11-05", "2026-11-05").isEmpty());
        ItemRow row = db.diary().row("u1", "a-i");
        assertNull(row); // deleted items are hidden from queries
    }

    @Test
    public void updatingAnEntryKeepsItsItems() {
        // Regression: REPLACE on the parent row cascaded and deleted every item.
        entry("a", "u1", "2026-11-05", MealEntryEntity.STATUS_CONSUMED, 100, 130);
        MealEntryEntity e = db.diary().entry("a");
        e.updatedAt = 99;
        db.diary().upsertEntry(e);
        assertEquals(1, db.diary().liveItemsOf("a").size());
        MealItemEntity i = db.diary().item("a-i");
        i.servings = 1.5;
        db.diary().upsertItem(i);
        assertEquals(1.5, db.diary().item("a-i").servings, 1e-9);
        assertEquals(195, db.diary().totalsRange("u1", "2026-11-05", "2026-11-05").get(0).kcal, 1e-6);
    }

    @Test
    public void outboxCoalescesRepeatedEdits() {
        for (int k = 0; k < 5; k++) {
            SyncOpEntity op = new SyncOpEntity();
            op.uid = "u1";
            op.collection = "water";
            op.docId = "w1";
            op.queuedAt = k;
            db.sync().enqueue(op);
        }
        assertEquals(1, db.sync().pendingCount("u1"));
        SyncOpEntity latest = db.sync().pending("u1", 10).get(0);
        assertEquals(4, latest.queuedAt);
        db.sync().ack(latest.seq);
        assertEquals(0, db.sync().pendingCount("u1"));
    }

    @Test
    public void accountsAreIsolatedAndWipedOnLogout() {
        entry("a", "u1", "2026-11-05", MealEntryEntity.STATUS_CONSUMED, 100, 130);
        entry("b", "u2", "2026-11-05", MealEntryEntity.STATUS_CONSUMED, 100, 130);
        WaterEntity w = new WaterEntity();
        w.id = "w";
        w.uid = "u1";
        w.date = "2026-11-05";
        w.ml = 300;
        db.water().upsert(w);
        assertEquals(1, db.diary().day("u2", "2026-11-05").size());
        assertEquals(0, db.water().dayTotal("u2", "2026-11-05"));
        AuthRepository.wipeLocal(ApplicationProvider.getApplicationContext(), db, "u1");
        assertTrue(db.diary().day("u1", "2026-11-05").isEmpty());
        assertEquals(0, db.water().dayTotal("u1", "2026-11-05"));
        assertEquals(1, db.diary().day("u2", "2026-11-05").size()); // other account untouched
    }

    @Test
    public void activeGoalIsHistorical() {
        GoalEntity g1 = new GoalEntity();
        g1.id = "g1";
        g1.uid = "u1";
        g1.effectiveDate = "2026-10-01";
        g1.kcal = 2500;
        GoalEntity g2 = new GoalEntity();
        g2.id = "g2";
        g2.uid = "u1";
        g2.effectiveDate = "2026-11-04";
        g2.kcal = 2000;
        db.goals().upsert(g1);
        db.goals().upsert(g2);
        assertEquals(2500, db.goals().active("u1", "2026-11-03").kcal);
        assertEquals(2000, db.goals().active("u1", "2026-11-05").kcal);
        assertNull(db.goals().active("u1", "2026-09-01"));
    }

    @Test
    public void searchFindsBanglaAndAliasesOffline() {
        FoodEntity f = new FoodEntity();
        f.id = "cat_rice";
        f.name = "White rice, cooked";
        f.nameBn = "সাদা ভাত";
        f.aliases = "white rice, cooked|bhat|rice";
        f.category = "grains";
        db.foods().upsertFood(f);
        FoodEntity mine = new FoodEntity();
        mine.id = "usr_x";
        mine.ownerUid = "u2";
        mine.name = "Secret bhat";
        mine.aliases = "secret bhat";
        db.foods().upsertFood(mine);
        assertEquals(1, db.foods().search("u1", "bhat", "", false, false, 50).size());   // u2's custom food hidden
        assertEquals(1, db.foods().search("u1", "ভাত", "", false, false, 50).size());
        assertEquals(2, db.foods().search("u2", "bhat", "", false, false, 50).size());
        assertNotNull(db.foods().byId("cat_rice"));
    }
}

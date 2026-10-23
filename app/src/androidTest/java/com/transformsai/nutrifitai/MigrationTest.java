package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;

import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.transformsai.nutrifitai.data.db.AppDatabase;
import com.transformsai.nutrifitai.data.db.Migrations;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies hand-written migrations keep data and match the exported schemas. */
@RunWith(AndroidJUnit4.class)
public class MigrationTest {
    private static final String DB = "migration-test.db";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase.class);

    @Test
    public void migrate1To2KeepsWeights() throws Exception {
        SupportSQLiteDatabase v1 = helper.createDatabase(DB, 1);
        v1.execSQL("INSERT INTO weight_entries (id, uid, date, loggedAt, kg, updatedAt, deleted) VALUES ('w1','u1','2026-11-05',1,70.5,1,0)");
        v1.close();
        SupportSQLiteDatabase v2 = helper.runMigrationsAndValidate(DB, 2, true, Migrations.MIGRATION_1_2);
        try (Cursor c = v2.query("SELECT kg, note FROM weight_entries WHERE id = 'w1'")) {
            c.moveToFirst();
            assertEquals(70.5, c.getDouble(0), 1e-9);
            assertEquals(true, c.isNull(1));
        }
        v2.execSQL("INSERT INTO import_batches (id, uid, fileName, formatVersion, importedAt, added, replaced, skipped) VALUES ('b','u1','f.xml',1,1,1,0,0)");
        v2.close();
    }

    /** 5 -> 6 adds the walk route columns; an existing walk keeps its data and gets zero distance. */
    @Test
    public void migrate5To6AddsRouteColumns() throws Exception {
        String db = "migration-test-6.db";
        SupportSQLiteDatabase v5 = helper.createDatabase(db, 5);
        v5.execSQL("INSERT INTO workout_sessions (id, uid, date, startedAt, endedAt, title, mode, activeMs, totalReps, exercisesDone, "
                + "kcalEstimate, completion, updatedAt, deleted) VALUES ('s1','u1','2026-11-05',1,2,'Walk','walk',60000,0,1,12.5,1,3,0)");
        v5.close();
        SupportSQLiteDatabase v6 = helper.runMigrationsAndValidate(db, 6, true, Migrations.MIGRATION_5_6);
        try (Cursor c = v6.query("SELECT title, distanceM, steps, routePolyline FROM workout_sessions WHERE id = 's1'")) {
            c.moveToFirst();
            assertEquals("Walk", c.getString(0));
            assertEquals(0.0, c.getDouble(1), 1e-9);
            assertEquals(0, c.getInt(2));
            assertEquals(true, c.isNull(3));
        }
        v6.close();
    }
}

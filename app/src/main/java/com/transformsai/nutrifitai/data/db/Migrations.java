package com.transformsai.nutrifitai.data.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Hand-written, non-destructive migrations. Verified by MigrationTest (androidTest). */
public final class Migrations {
    private Migrations() {
    }

    /** v1 -> v2: weight notes and XML import history. */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `weight_entries` ADD COLUMN `note` TEXT");
            db.execSQL("CREATE TABLE IF NOT EXISTS `import_batches` (`id` TEXT NOT NULL, `uid` TEXT NOT NULL, "
                    + "`fileName` TEXT, `formatVersion` INTEGER NOT NULL, `importedAt` INTEGER NOT NULL, "
                    + "`added` INTEGER NOT NULL, `replaced` INTEGER NOT NULL, `skipped` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_batches_uid` ON `import_batches` (`uid`)");
        }
    };

    /** v2 -> v3: fitness (workout sessions and saved workout plans). */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `workout_sessions` (`id` TEXT NOT NULL, `uid` TEXT NOT NULL, `date` TEXT NOT NULL, "
                    + "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `planId` TEXT, `mode` TEXT, "
                    + "`activeMs` INTEGER NOT NULL, `totalReps` INTEGER NOT NULL, `exercisesDone` INTEGER NOT NULL, "
                    + "`kcalEstimate` REAL NOT NULL, `completion` REAL NOT NULL, `setsJson` TEXT, `updatedAt` INTEGER NOT NULL, "
                    + "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_sessions_uid_date` ON `workout_sessions` (`uid`, `date`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `workout_plans` (`id` TEXT NOT NULL, `uid` TEXT NOT NULL, `title` TEXT NOT NULL, "
                    + "`summary` TEXT, `source` TEXT, `level` TEXT, `focus` TEXT, `stepsJson` TEXT, `createdAt` INTEGER NOT NULL, "
                    + "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_plans_uid` ON `workout_plans` (`uid`)");
        }
    };

    /** v4: daily steps (walk tracker / Health Connect) and fasting windows. */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `daily_steps` (`id` TEXT NOT NULL, `uid` TEXT NOT NULL, `date` TEXT NOT NULL, "
                    + "`steps` INTEGER NOT NULL, `distanceM` REAL NOT NULL, `source` TEXT, `updatedAt` INTEGER NOT NULL, "
                    + "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_steps_uid_date` ON `daily_steps` (`uid`, `date`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `fasts` (`id` TEXT NOT NULL, `uid` TEXT NOT NULL, `date` TEXT NOT NULL, "
                    + "`startAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL, `targetHours` INTEGER NOT NULL, "
                    + "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_fasts_uid_startAt` ON `fasts` (`uid`, `startAt`)");
        }
    };

    /** v5: birth date, unique username and a shareable avatar path on the profile. */
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `birthDate` TEXT");
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `username` TEXT");
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `usernameChangedAt` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `avatarPublicPath` TEXT");
        }
    };

    /** 6: GPS walk routes and moving stats on workout sessions. */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `workout_sessions` ADD COLUMN `routePolyline` TEXT");
            db.execSQL("ALTER TABLE `workout_sessions` ADD COLUMN `distanceM` REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `workout_sessions` ADD COLUMN `movingMs` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `workout_sessions` ADD COLUMN `maxSpeedMps` REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `workout_sessions` ADD COLUMN `elevGainM` REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `workout_sessions` ADD COLUMN `steps` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `workout_sessions` ADD COLUMN `splitsJson` TEXT");
        }
    };

    public static final Migration[] ALL = {MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6};
}

package com.transformsai.nutrifitai.data.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.transformsai.nutrifitai.data.db.dao.AiDao;
import com.transformsai.nutrifitai.data.db.dao.ChatDao;
import com.transformsai.nutrifitai.data.db.dao.DiaryDao;
import com.transformsai.nutrifitai.data.db.dao.FoodDao;
import com.transformsai.nutrifitai.data.db.dao.GoalDao;
import com.transformsai.nutrifitai.data.db.dao.ImportDao;
import com.transformsai.nutrifitai.data.db.dao.ProfileDao;
import com.transformsai.nutrifitai.data.db.dao.RecipeDao;
import com.transformsai.nutrifitai.data.db.dao.ReminderDao;
import com.transformsai.nutrifitai.data.db.dao.SyncDao;
import com.transformsai.nutrifitai.data.db.dao.WaterDao;
import com.transformsai.nutrifitai.data.db.dao.WeightDao;
import com.transformsai.nutrifitai.data.db.entity.AiAnalysisEntity;
import com.transformsai.nutrifitai.data.db.entity.ChatMessageEntity;
import com.transformsai.nutrifitai.data.db.entity.ConversationEntity;
import com.transformsai.nutrifitai.data.db.entity.FavoriteEntity;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ImportBatchEntity;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.entity.RecipeEntity;
import com.transformsai.nutrifitai.data.db.entity.RecipeIngredientEntity;
import com.transformsai.nutrifitai.data.db.entity.ReminderSettingsEntity;
import com.transformsai.nutrifitai.data.db.entity.ServingEntity;
import com.transformsai.nutrifitai.data.db.entity.SyncMetaEntity;
import com.transformsai.nutrifitai.data.db.entity.SyncOpEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutPlanEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.data.db.dao.WorkoutDao;
import com.transformsai.nutrifitai.data.db.dao.ActivityDao;
import com.transformsai.nutrifitai.data.db.entity.FastEntity;
import com.transformsai.nutrifitai.data.db.entity.StepDayEntity;

@Database(version = 6, exportSchema = true, entities = {
        ProfileEntity.class, GoalEntity.class, FoodEntity.class, ServingEntity.class, FavoriteEntity.class,
        RecipeEntity.class, RecipeIngredientEntity.class, MealEntryEntity.class, MealItemEntity.class,
        WaterEntity.class, WeightEntity.class, ReminderSettingsEntity.class, AiAnalysisEntity.class,
        ChatMessageEntity.class, ConversationEntity.class, SyncOpEntity.class, SyncMetaEntity.class, ImportBatchEntity.class,
        WorkoutSessionEntity.class, WorkoutPlanEntity.class, StepDayEntity.class, FastEntity.class})
public abstract class AppDatabase extends RoomDatabase {
    public static final String NAME = "nutrifit.db";

    public abstract ProfileDao profiles();

    public abstract GoalDao goals();

    public abstract FoodDao foods();

    public abstract DiaryDao diary();

    public abstract WaterDao water();

    public abstract WeightDao weights();

    public abstract ReminderDao reminders();

    public abstract AiDao ai();

    public abstract ChatDao chat();

    public abstract RecipeDao recipes();

    public abstract SyncDao sync();

    public abstract ImportDao imports();

    public abstract WorkoutDao workouts();

    public abstract ActivityDao activity();

    public static AppDatabase build(@NonNull Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, NAME)
                .addMigrations(Migrations.ALL)
                .build();
    }
}

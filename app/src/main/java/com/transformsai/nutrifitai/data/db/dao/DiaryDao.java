package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;

import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.model.DayTotals;
import com.transformsai.nutrifitai.data.db.model.ItemRow;

import java.util.List;

@Dao
public interface DiaryDao {
    String ROW = "SELECT i.*, e.date AS date, e.mealType AS mealType, e.status AS status, e.source AS source, "
            + "e.imageLocalPath AS imageLocalPath, e.imageRemotePath AS imageRemotePath, e.consumedAt AS consumedAt "
            + "FROM meal_items i JOIN meal_entries e ON e.id = i.entryId "
            + "WHERE e.uid = :uid AND e.deleted = 0 AND i.deleted = 0 ";

    /** Portion grams = servings * servingGrams. Only CONSUMED entries count toward totals. */
    String TOTALS = "SELECT e.date AS date, "
            + "SUM(i.kcal100 * i.servings * i.servingGrams / 100.0) AS kcal, "
            + "SUM(i.protein100 * i.servings * i.servingGrams / 100.0) AS protein, "
            + "SUM(i.carbs100 * i.servings * i.servingGrams / 100.0) AS carbs, "
            + "SUM(i.fat100 * i.servings * i.servingGrams / 100.0) AS fat, "
            + "SUM(COALESCE(i.fibre100, 0) * i.servings * i.servingGrams / 100.0) AS fibre, "
            + "COUNT(i.id) AS itemCount "
            + "FROM meal_items i JOIN meal_entries e ON e.id = i.entryId "
            + "WHERE e.uid = :uid AND e.deleted = 0 AND i.deleted = 0 AND e.status = 'consumed' ";

    @Query(ROW + "AND e.date = :date ORDER BY CASE e.mealType WHEN 'breakfast' THEN 0 WHEN 'lunch' THEN 1 "
            + "WHEN 'dinner' THEN 2 ELSE 3 END, e.createdAt, i.position")
    LiveData<List<ItemRow>> observeDay(String uid, String date);

    @Query(ROW + "AND e.date = :date ORDER BY e.createdAt, i.position")
    List<ItemRow> day(String uid, String date);

    @Query(ROW + "AND e.date BETWEEN :from AND :to ORDER BY e.date, e.createdAt, i.position")
    List<ItemRow> range(String uid, String from, String to);

    @Query(ROW + "AND i.id = :itemId")
    ItemRow row(String uid, String itemId);

    @Query(TOTALS + "AND e.date = :date GROUP BY e.date")
    LiveData<DayTotals> observeTotals(String uid, String date);

    @Query(TOTALS + "AND e.date BETWEEN :from AND :to GROUP BY e.date ORDER BY e.date")
    List<DayTotals> totalsRange(String uid, String from, String to);

    @Query("SELECT COUNT(*) FROM meal_entries WHERE uid = :uid AND date = :date AND mealType = :mealType "
            + "AND status = 'consumed' AND deleted = 0")
    int consumedEntryCount(String uid, String date, String mealType);

    @Query("SELECT * FROM meal_entries WHERE id = :id")
    MealEntryEntity entry(String id);

    @Query("SELECT * FROM meal_entries WHERE uid = :uid AND date = :date AND mealType = :mealType AND deleted = 0")
    List<MealEntryEntity> entriesFor(String uid, String date, String mealType);

    @Query("SELECT * FROM meal_entries WHERE uid = :uid ORDER BY date, createdAt")
    List<MealEntryEntity> allEntries(String uid);

    @Query("SELECT * FROM meal_items WHERE entryId = :entryId ORDER BY position")
    List<MealItemEntity> itemsOf(String entryId);

    @Query("SELECT * FROM meal_items WHERE entryId = :entryId AND deleted = 0 ORDER BY position")
    List<MealItemEntity> liveItemsOf(String entryId);

    @Query("SELECT * FROM meal_items WHERE id = :id")
    MealItemEntity item(String id);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsertEntry(MealEntryEntity e);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsertItems(List<MealItemEntity> items);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsertItem(MealItemEntity item);

    @Query("DELETE FROM meal_items WHERE entryId = :entryId")
    void deleteItemsOf(String entryId);

    @Query("SELECT imageLocalPath FROM meal_entries WHERE uid = :uid AND imageLocalPath IS NOT NULL")
    List<String> localImages(String uid);

    @Query("DELETE FROM meal_entries WHERE uid = :uid")
    void wipe(String uid);
}

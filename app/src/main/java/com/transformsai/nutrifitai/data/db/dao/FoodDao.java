package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;
import androidx.room.Transaction;

import com.transformsai.nutrifitai.data.db.entity.FavoriteEntity;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.ServingEntity;
import com.transformsai.nutrifitai.data.db.model.FrequentFood;

import java.util.List;

@Dao
public interface FoodDao {
    String VISIBLE = "(f.ownerUid IS NULL OR f.ownerUid = :uid) AND f.deleted = 0";

    /** Offline search over English names, Bangla names and aliases. :q is already lower-case. */
    @Query("SELECT f.* FROM foods f WHERE " + VISIBLE
            + " AND (:q = '' OR lower(f.name) LIKE '%' || :q || '%' OR f.nameBn LIKE '%' || :q || '%'"
            + " OR f.aliases LIKE '%' || :q || '%')"
            + " AND (:category = '' OR f.category = :category)"
            + " AND (:bdOnly = 0 OR f.bangladeshi = 1)"
            + " AND (:customOnly = 0 OR f.ownerUid = :uid)"
            + " ORDER BY CASE WHEN f.provenance = 'sourced' THEN 1 ELSE 0 END,"
            + " CASE WHEN lower(f.name) LIKE :q || '%' THEN 0 ELSE 1 END, length(f.name), f.name LIMIT :limit")
    List<FoodEntity> search(String uid, String q, String category, boolean bdOnly, boolean customOnly, int limit);

    @Query("SELECT f.* FROM foods f JOIN favorites fav ON fav.foodId = f.id WHERE fav.uid = :uid AND fav.deleted = 0 AND "
            + VISIBLE + " ORDER BY fav.updatedAt DESC")
    List<FoodEntity> favorites(String uid);

    @Query("SELECT f.* FROM foods f JOIN (SELECT i.foodId AS fid, MAX(i.updatedAt) AS lastUsed FROM meal_items i "
            + "JOIN meal_entries e ON e.id = i.entryId WHERE e.uid = :uid AND i.deleted = 0 AND i.foodId IS NOT NULL "
            + "GROUP BY i.foodId) r ON r.fid = f.id WHERE " + VISIBLE + " ORDER BY r.lastUsed DESC LIMIT :limit")
    List<FoodEntity> recent(String uid, int limit);

    @Query("SELECT i.foodId AS foodId, i.name AS name, COUNT(*) AS uses FROM meal_items i JOIN meal_entries e "
            + "ON e.id = i.entryId WHERE e.uid = :uid AND e.deleted = 0 AND i.deleted = 0 AND i.foodId IS NOT NULL "
            + "AND e.date >= :fromDate GROUP BY i.foodId ORDER BY uses DESC LIMIT :limit")
    List<FrequentFood> frequent(String uid, String fromDate, int limit);

    @Query("SELECT * FROM foods WHERE id = :id")
    FoodEntity byId(String id);

    @Query("SELECT * FROM foods WHERE id = :id")
    LiveData<FoodEntity> observe(String id);

    @Query("SELECT * FROM foods WHERE ownerUid = :uid AND deleted = 0 ORDER BY name")
    LiveData<List<FoodEntity>> observeCustom(String uid);

    @Query("SELECT * FROM servings WHERE foodId = :foodId ORDER BY grams")
    List<ServingEntity> servings(String foodId);

    @Query("SELECT COUNT(*) FROM foods WHERE ownerUid IS NULL AND provenance = 'curated'")
    int catalogCount();

    @Query("SELECT COUNT(*) FROM foods WHERE provenance = :provenance")
    int countByProvenance(String provenance);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsertFoods(List<FoodEntity> foods);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsertFood(FoodEntity food);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsertServings(List<ServingEntity> servings);

    @Query("DELETE FROM servings WHERE foodId = :foodId")
    void deleteServings(String foodId);

    @Transaction
    default void replaceCatalog(List<FoodEntity> foods, List<ServingEntity> servings) {
        upsertFoods(foods);
        upsertServings(servings);
    }

    // ----- favorites -----
    @Query("SELECT COUNT(*) > 0 FROM favorites WHERE uid = :uid AND foodId = :foodId AND deleted = 0")
    LiveData<Boolean> observeIsFavorite(String uid, String foodId);

    @Query("SELECT * FROM favorites WHERE uid = :uid AND foodId = :foodId")
    FavoriteEntity favorite(String uid, String foodId);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsertFavorite(FavoriteEntity f);

    @Query("SELECT foodId FROM favorites WHERE uid = :uid AND deleted = 0")
    List<String> favoriteIds(String uid);

    @Query("DELETE FROM favorites WHERE uid = :uid")
    void wipeFavorites(String uid);

    @Query("DELETE FROM foods WHERE ownerUid = :uid")
    void wipeCustom(String uid);
}

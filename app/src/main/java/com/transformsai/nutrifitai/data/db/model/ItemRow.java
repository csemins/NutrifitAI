package com.transformsai.nutrifitai.data.db.model;

import androidx.room.Embedded;

import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;

/** Meal item joined with the fields of its parent entry the diary needs. */
public class ItemRow {
    @Embedded
    public MealItemEntity item;
    public String date;
    public String mealType;
    public String status;
    public String source;
    public String imageLocalPath;
    public String imageRemotePath;
    public Long consumedAt;
}

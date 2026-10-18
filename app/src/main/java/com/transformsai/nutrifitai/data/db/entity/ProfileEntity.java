package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.LocalOnly;
import com.transformsai.nutrifitai.data.db.Syncable;

/** One row per signed-in account. Firestore: users/{uid}. */
@Entity(tableName = "profiles")
public class ProfileEntity implements Syncable {
    @PrimaryKey
    @NonNull
    public String uid = "";
    public String name;
    public String email;
    public int birthYear;
    /** yyyy-MM-dd; birthYear is kept in step with it for older code paths and exports. */
    public String birthDate;
    /** Unique @username (claimed in Firestore usernames/), changeable once every 30 days. */
    public String username;
    public long usernameChangedAt;
    /** female | male | unspecified (only used by the Mifflin-St Jeor formula). */
    public String sex = "unspecified";
    public double heightCm;
    public double weightKg;
    public double targetWeightKg;
    /** sedentary | light | moderate | active | very_active */
    public String activityLevel = "light";
    /** lose | maintain | gain */
    public String goal = "maintain";
    /** Comma separated tags, e.g. "vegetarian,halal". */
    public String dietPrefs = "";
    /** Comma separated, user-declared. */
    public String allergies = "";
    /** metric | imperial */
    public String units = "metric";
    @LocalOnly
    public String avatarLocalPath;
    public String avatarRemotePath;
    /** Shared copy of the avatar that friends can load (users/{uid}/posts/{random}.jpg). */
    public String avatarPublicPath;
    public boolean onboarded;
    public long createdAt;
    public long updatedAt;

    @Override
    public String syncId() {
        return uid;
    }

    @Override
    public long syncUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean syncDeleted() {
        return false;
    }
}

package com.transformsai.nutrifitai.data.remote;

import com.transformsai.nutrifitai.data.db.LocalOnly;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Entity <-> Firestore map conversion. Fields annotated {@link LocalOnly} (device file paths) are
 * never uploaded. Units are explicit in field names (kcal100, grams, ml, kg).
 */
public final class FirestoreMapper {
    private static final Gson GSON = new GsonBuilder()
            .setExclusionStrategies(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    return f.getAnnotation(LocalOnly.class) != null;
                }

                @Override
                public boolean shouldSkipClass(Class<?> c) {
                    return false;
                }
            })
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
            .create();
    private static final Type MAP = new TypeToken<Map<String, Object>>() {
    }.getType();

    private FirestoreMapper() {
    }

    public static Map<String, Object> toMap(Object entity) {
        JsonElement tree = GSON.toJsonTree(entity);
        return GSON.fromJson(tree, MAP);
    }

    public static <T> T fromMap(Map<String, Object> map, Class<T> cls) {
        return GSON.fromJson(GSON.toJsonTree(map), cls);
    }

    public static Gson gson() {
        return GSON;
    }
}

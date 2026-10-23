package com.transformsai.nutrifitai.util;

import java.util.UUID;

/** Stable client-generated identifiers. The same id is used in Room, Firestore and exports. */
public final class Ids {
    private Ids() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}

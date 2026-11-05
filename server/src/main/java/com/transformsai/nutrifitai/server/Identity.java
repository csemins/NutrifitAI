package com.transformsai.nutrifitai.server;

/** Who is calling, from a verified Firebase ID token. Email may be null (e.g. anonymous accounts). */
public record Identity(String uid, String email, boolean emailVerified, String name) {
    public static Identity of(String uid) {
        return new Identity(uid, null, false, null);
    }
}

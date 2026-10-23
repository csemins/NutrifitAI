package com.transformsai.nutrifitai.data;

import androidx.annotation.Nullable;

/** Minimal async result for UI callbacks. */
public final class Result<T> {
    @Nullable
    public final T value;
    @Nullable
    public final String error;

    private Result(@Nullable T v, @Nullable String e) {
        value = v;
        error = e;
    }

    public static <T> Result<T> ok(@Nullable T v) {
        return new Result<>(v, null);
    }

    public static <T> Result<T> fail(String e) {
        return new Result<>(null, e);
    }

    public boolean ok() {
        return error == null;
    }

    public interface Callback<T> {
        void onResult(Result<T> r);
    }
}

package com.transformsai.nutrifitai.data.remote;

/** Network/proxy failure with a user-facing message and whether retrying may help. */
public class AiHttpException extends Exception {
    public final String code;
    public final boolean retryable;

    public AiHttpException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }
}

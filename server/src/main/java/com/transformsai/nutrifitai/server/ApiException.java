package com.transformsai.nutrifitai.server;

/** An error that maps directly to an HTTP status and a stable machine-readable code. */
public class ApiException extends Exception {
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}

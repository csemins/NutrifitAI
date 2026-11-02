package com.transformsai.nutrifitai.ai;

/** Thrown when an AI response cannot be recovered into a valid contract. Message is user-safe. */
public class AiParseException extends Exception {
    public final String code;

    public AiParseException(String code, String message) {
        super(message);
        this.code = code;
    }
}

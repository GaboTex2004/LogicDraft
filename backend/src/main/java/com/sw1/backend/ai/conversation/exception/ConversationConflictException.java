package com.sw1.backend.ai.conversation.exception;

public class ConversationConflictException extends RuntimeException {
    private final String code;

    public ConversationConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

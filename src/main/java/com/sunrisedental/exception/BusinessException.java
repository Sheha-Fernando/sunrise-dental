package com.sunrisedental.exception;

/**
 * A user-facing error with a message safe to display as-is
 * (never wraps raw SQL/driver text).
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.sunrisedental.exception;

/**
 * The user is authenticated but not permitted to perform this action.
 * Servlets map this to HTTP 403, distinct from BusinessException's 400/404.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}

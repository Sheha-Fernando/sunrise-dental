package com.sunrisedental.model;

import com.sunrisedental.exception.BusinessException;

public enum UserRole {
    ADMIN,
    RECEPTIONIST,
    DENTIST,
    BILLING;

    /**
     * Parses a client-supplied role value. Never accepts anything outside
     * the four known roles - rejects made-up values like "SUPER_ADMIN".
     */
    public static UserRole fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Invalid user role.");
        }
        try {
            return UserRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid user role.");
        }
    }
}

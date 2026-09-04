package com.sunrisedental.util;

import org.mindrot.jbcrypt.BCrypt;

public final class PasswordUtil {

    private PasswordUtil() {
    }

    public static String hash(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(10));
    }

    public static boolean verify(String plainPassword, String storedHash) {
        if (storedHash == null || plainPassword == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // Malformed or incompatible hash (e.g. wrong bcrypt revision) - treat as no match.
            return false;
        }
    }
}

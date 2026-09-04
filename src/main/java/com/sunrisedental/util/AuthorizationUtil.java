package com.sunrisedental.util;

import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Central place for role checks so servlets never compare role strings
 * directly. The (UserRole...) overloads are pure functions - deliberately
 * free of any servlet dependency - so they can be unit tested directly.
 * The HttpServletRequest overloads are thin convenience wrappers used by
 * servlets, reading whatever AuthServlet stored in the session at login.
 */
public final class AuthorizationUtil {

    private AuthorizationUtil() {
    }

    public static boolean hasAnyRole(UserRole current, UserRole... allowed) {
        if (current == null) {
            return false;
        }
        for (UserRole role : allowed) {
            if (role == current) {
                return true;
            }
        }
        return false;
    }

    public static void requireAnyRole(UserRole current, UserRole... allowed) {
        if (!hasAnyRole(current, allowed)) {
            throw new ForbiddenException("You do not have permission to perform this action.");
        }
    }

    public static UserRole currentRole(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute("role");
        return value instanceof UserRole ? (UserRole) value : null;
    }

    public static Integer currentUserId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute("userId");
        return value instanceof Integer ? (Integer) value : null;
    }

    public static Integer currentDentistId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute("dentistId");
        return value instanceof Integer ? (Integer) value : null;
    }

    public static void requireAnyRole(HttpServletRequest req, UserRole... allowed) {
        requireAnyRole(currentRole(req), allowed);
    }
}

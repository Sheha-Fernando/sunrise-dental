package com.sunrisedental.service;

import com.sunrisedental.dao.UserDAO;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.util.PasswordUtil;

import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AuthService {

    private static final Logger LOGGER = Logger.getLogger(AuthService.class.getName());

    private final UserDAO userDAO;

    public AuthService() {
        this(new UserDAO());
    }

    public AuthService(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /**
     * Verifies credentials and returns the authenticated user with its
     * password hash cleared - never expose the hash beyond this point.
     */
    public User authenticate(String username, String plainPassword) {
        if (username == null || username.isBlank() || plainPassword == null || plainPassword.isBlank()) {
            throw new BusinessException("Invalid username or password.");
        }
        try {
            Optional<User> found = userDAO.findByUsername(username.trim());

            // Every failure path below returns the same generic message -
            // never reveal whether the username exists, whether the account
            // is inactive, or whether the password was merely wrong.
            if (found.isEmpty()
                    || !found.get().isActive()
                    || !PasswordUtil.verify(plainPassword, found.get().getPasswordHash())
                    || (found.get().getRole() == UserRole.DENTIST && found.get().getDentistId() == null)) {
                throw new BusinessException("Invalid username or password.");
            }

            User user = found.get();
            user.setPasswordHash(null);
            return user;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database error during authentication", e);
            throw new BusinessException("Unable to process login right now. Please try again later.");
        }
    }
}

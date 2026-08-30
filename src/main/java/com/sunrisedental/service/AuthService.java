package com.sunrisedental.service;

import com.sunrisedental.dao.UserDAO;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.User;
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
            if (found.isEmpty() || !PasswordUtil.verify(plainPassword, found.get().getPasswordHash())) {
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

package com.sunrisedental.service;

import com.sunrisedental.dao.DentistDAO;
import com.sunrisedental.dao.UserDAO;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.util.PasswordUtil;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Admin-only staff account management. Authorization (is the caller an
 * ADMIN?) is enforced by the servlet before these methods are ever called -
 * this class assumes that check already passed and focuses purely on the
 * account business rules.
 */
public class StaffService {

    private static final Logger LOGGER = Logger.getLogger(StaffService.class.getName());

    private final UserDAO userDAO;
    private final DentistDAO dentistDAO;

    public StaffService() {
        this(new UserDAO(), new DentistDAO());
    }

    public StaffService(UserDAO userDAO, DentistDAO dentistDAO) {
        this.userDAO = userDAO;
        this.dentistDAO = dentistDAO;
    }

    public List<User> listStaff() {
        try {
            return userDAO.findAll();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list staff", e);
            throw new BusinessException("Unable to retrieve staff right now.");
        }
    }

    public User createStaff(String fullName, String username, String plainPassword,
                             UserRole role, Integer dentistId, Integer assignedDentistId) {
        if (fullName == null || fullName.isBlank()) {
            throw new BusinessException("Full name is required.");
        }
        if (username == null || username.isBlank()) {
            throw new BusinessException("Username is required.");
        }
        if (plainPassword == null || plainPassword.isBlank()) {
            throw new BusinessException("Password is required.");
        }
        validateRoleDentistPair(role, dentistId);
        validateAssignedDentistPair(role, assignedDentistId);

        try {
            if (userDAO.existsByUsername(username.trim())) {
                throw new BusinessException("Username is already in use.");
            }

            String hash = PasswordUtil.hash(plainPassword);
            int userId;
            try {
                userId = userDAO.create(username.trim(), hash, fullName.trim(), role, dentistId, assignedDentistId);
            } catch (SQLIntegrityConstraintViolationException e) {
                throw new BusinessException("Username is already in use.");
            }
            return userDAO.findById(userId).orElseThrow(() -> new BusinessException("Unable to create staff account."));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to create staff account", e);
            throw new BusinessException("Unable to create staff account right now.");
        }
    }

    public User updateRole(int userId, UserRole newRole, Integer newDentistId, Integer newAssignedDentistId) {
        validateRoleDentistPair(newRole, newDentistId);
        validateAssignedDentistPair(newRole, newAssignedDentistId);
        try {
            User target = userDAO.findById(userId)
                    .orElseThrow(() -> new BusinessException("Staff member not found."));

            if (target.getRole() == UserRole.ADMIN && target.isActive() && newRole != UserRole.ADMIN) {
                ensureNotLastActiveAdmin();
            }

            userDAO.updateRole(userId, newRole, newDentistId, newAssignedDentistId);
            return userDAO.findById(userId).orElseThrow(() -> new BusinessException("Staff member not found."));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update staff role", e);
            throw new BusinessException("Unable to update staff account right now.");
        }
    }

    public User updateActiveStatus(int userId, boolean active) {
        try {
            User target = userDAO.findById(userId)
                    .orElseThrow(() -> new BusinessException("Staff member not found."));

            if (!active && target.getRole() == UserRole.ADMIN && target.isActive()) {
                ensureNotLastActiveAdmin();
            }

            userDAO.updateActive(userId, active);
            return userDAO.findById(userId).orElseThrow(() -> new BusinessException("Staff member not found."));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update staff status", e);
            throw new BusinessException("Unable to update staff account right now.");
        }
    }

    private void ensureNotLastActiveAdmin() throws SQLException {
        if (userDAO.countActiveByRole(UserRole.ADMIN) <= 1) {
            throw new BusinessException("The system must have at least one active administrator.");
        }
    }

    private void validateRoleDentistPair(UserRole role, Integer dentistId) {
        if (role == UserRole.DENTIST) {
            if (dentistId == null) {
                throw new BusinessException("Dentist ID is required for a dentist account.");
            }
            try {
                Dentist dentist = dentistDAO.findById(dentistId)
                        .orElseThrow(() -> new BusinessException("Selected dentist is not available."));
                if (!dentist.isActive()) {
                    throw new BusinessException("Selected dentist is not available.");
                }
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "Failed to validate dentist association", e);
                throw new BusinessException("Unable to validate dentist association right now.");
            }
        } else if (dentistId != null) {
            throw new BusinessException("Dentist ID must not be provided for this role.");
        }
    }

    private void validateAssignedDentistPair(UserRole role, Integer assignedDentistId) {
        if (role == UserRole.CLINICAL_ASSISTANT) {
            if (assignedDentistId == null) {
                throw new BusinessException("An assigned dentist is required for a Clinical Assistant account.");
            }
            try {
                Dentist dentist = dentistDAO.findById(assignedDentistId)
                        .orElseThrow(() -> new BusinessException("Selected dentist is not available."));
                if (!dentist.isActive()) {
                    throw new BusinessException("Selected dentist is not available.");
                }
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "Failed to validate assigned dentist", e);
                throw new BusinessException("Unable to validate assigned dentist right now.");
            }
        } else if (assignedDentistId != null) {
            throw new BusinessException("Assigned dentist is only applicable to Clinical Assistant accounts.");
        }
    }
}

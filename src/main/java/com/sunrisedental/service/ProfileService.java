package com.sunrisedental.service;

import com.sunrisedental.dao.DentistDAO;
import com.sunrisedental.dao.UserDAO;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.util.PasswordUtil;

import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Self-service "My Profile" concerns: viewing your own account, editing your
 * own contact details, and changing your own password. Every method is keyed
 * off the caller-supplied userId, which the servlet must always source from
 * the authenticated session - never from a client-supplied parameter.
 *
 * Editing name/username/contact/email reuses StaffService.updateProfile
 * directly (same validation, uniqueness check and dentist-row sync it
 * already provides for admin edits) rather than duplicating that logic -
 * this class always passes the user's own current specialty/workingDays/
 * assignedDentistId back unchanged, so a profile edit can never alter role,
 * dentist assignment or clinical scheduling data.
 */
public class ProfileService {

    private static final Logger LOGGER = Logger.getLogger(ProfileService.class.getName());

    private final UserDAO userDAO;
    private final DentistDAO dentistDAO;
    private final StaffService staffService;

    public ProfileService() {
        this(new UserDAO(), new DentistDAO(), new StaffService());
    }

    public ProfileService(UserDAO userDAO, DentistDAO dentistDAO, StaffService staffService) {
        this.userDAO = userDAO;
        this.dentistDAO = dentistDAO;
        this.staffService = staffService;
    }

    public User getOwnProfile(int userId) {
        try {
            return userDAO.findById(userId).orElseThrow(() -> new BusinessException("Profile not found."));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to load profile", e);
            throw new BusinessException("Unable to load your profile right now.");
        }
    }

    public Optional<Dentist> getDentist(int dentistId) {
        try {
            return dentistDAO.findById(dentistId);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to load dentist info for profile", e);
            throw new BusinessException("Unable to load your profile right now.");
        }
    }

    public User updateOwnProfile(int userId, String fullName, String username, String contactNumber, String email) {
        User current = getOwnProfile(userId);

        String specialty = null;
        String workingDays = null;
        if (current.getRole() == UserRole.DENTIST && current.getDentistId() != null) {
            Dentist dentist = getDentist(current.getDentistId()).orElse(null);
            if (dentist != null) {
                specialty = dentist.getSpecialty();
                workingDays = dentist.getWorkingDays();
            }
        }

        // Passing null here (rather than current.getAssignedDentistId()) means
        // StaffService.updateProfile's CLINICAL_ASSISTANT branch never fires -
        // a profile edit can never move an assistant to a different dentist.
        return staffService.updateProfile(userId, fullName, username, null, contactNumber, email,
                specialty, workingDays, null);
    }

    public void changePassword(int userId, String currentPassword, String newPassword) {
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new BusinessException("Current password is required.");
        }
        if (newPassword == null || newPassword.isBlank() || newPassword.length() < 6) {
            throw new BusinessException("New password must be at least 6 characters.");
        }
        try {
            User user = userDAO.findById(userId).orElseThrow(() -> new BusinessException("Profile not found."));
            if (!PasswordUtil.verify(currentPassword, user.getPasswordHash())) {
                throw new BusinessException("Current password is incorrect.");
            }
            userDAO.updatePassword(userId, PasswordUtil.hash(newPassword));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to change password", e);
            throw new BusinessException("Unable to change your password right now.");
        }
    }
}

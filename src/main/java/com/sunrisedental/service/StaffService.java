package com.sunrisedental.service;

import com.sunrisedental.dao.DentistDAO;
import com.sunrisedental.dao.UserDAO;
import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.util.PasswordUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Admin-only staff account management. Authorization (is the caller an
 * ADMIN?) is enforced by the servlet before these methods are ever called -
 * this class assumes that check already passed and focuses purely on the
 * account business rules.
 *
 * A DENTIST account always represents a brand-new dentist: creating one also
 * creates its backing `dentists` row in the same transaction (there is no
 * concept of picking an existing dentist "profile" for a new dentist
 * account). A CLINICAL_ASSISTANT, by contrast, is always linked to an
 * existing active dentist via assigned_dentist_id.
 */
public class StaffService {

    private static final Logger LOGGER = Logger.getLogger(StaffService.class.getName());
    private static final Pattern CONTACT_PATTERN = Pattern.compile("^[0-9+()\\s-]{7,20}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern WORKING_DAYS_PATTERN = Pattern.compile("^[01]{7}$");
    private static final String DEFAULT_WORKING_DAYS = "0111110"; // Mon-Fri

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

    /**
     * Creates a staff account. For role=DENTIST, dentistName/specialty are
     * required and a new dentists row is created alongside the user in the
     * same transaction; dentistId from the caller is ignored (a new dentist
     * account is never linked to an existing dentist). For
     * role=CLINICAL_ASSISTANT, assignedDentistId must reference an existing
     * active dentist. Other roles carry no dentist link at all.
     */
    public User createStaff(String fullName, String username, String plainPassword, UserRole role,
                             String contactNumber, String email,
                             String specialty, String workingDays, Integer assignedDentistId) {
        String cleanFullName = requireText(fullName, "Full name is required.");
        String cleanUsername = requireText(username, "Username is required.");
        if (plainPassword == null || plainPassword.isBlank()) {
            throw new BusinessException("Password is required.");
        }
        String cleanContact = validateOptionalContact(contactNumber);
        String cleanEmail = validateOptionalEmail(email);

        if (role == UserRole.CLINICAL_ASSISTANT) {
            validateAssignedDentist(assignedDentistId);
        } else if (assignedDentistId != null) {
            throw new BusinessException("Assigned dentist is only applicable to Clinical Assistant accounts.");
        }

        String cleanSpecialty = null;
        String cleanWorkingDays = null;
        if (role == UserRole.DENTIST) {
            cleanSpecialty = requireText(specialty, "Specialty is required for a dentist account.");
            cleanWorkingDays = validateWorkingDays(workingDays);
        }

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (userDAO.existsByUsername(conn, cleanUsername)) {
                    throw new BusinessException("Username is already in use.");
                }

                Integer dentistId = null;
                if (role == UserRole.DENTIST) {
                    dentistId = dentistDAO.create(conn, cleanFullName, cleanSpecialty, cleanContact, cleanEmail, cleanWorkingDays);
                }

                String hash = PasswordUtil.hash(plainPassword);
                int userId;
                try {
                    userId = userDAO.create(conn, cleanUsername, hash, cleanFullName, cleanContact, cleanEmail,
                            role, dentistId, assignedDentistId);
                } catch (SQLIntegrityConstraintViolationException e) {
                    throw new BusinessException("Username is already in use.");
                }

                conn.commit();
                return userDAO.findById(userId).orElseThrow(() -> new BusinessException("Unable to create staff account."));
            } catch (BusinessException e) {
                conn.rollback();
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                LOGGER.log(Level.SEVERE, "Failed to create staff account", e);
                throw new BusinessException("Unable to create staff account right now.");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database connection error while creating staff account", e);
            throw new BusinessException("Unable to create staff account right now.");
        }
    }

    /**
     * The "Edit" workflow: name/username/contact/email always; password only
     * if a new one was actually typed; specialty/workingDays for a DENTIST
     * (keeps the linked dentists row in sync); assignedDentistId for a
     * CLINICAL_ASSISTANT.
     */
    public User updateProfile(int userId, String fullName, String username, String plainPassword,
                               String contactNumber, String email,
                               String specialty, String workingDays, Integer assignedDentistId) {
        String cleanFullName = requireText(fullName, "Full name is required.");
        String cleanUsername = requireText(username, "Username is required.");
        String cleanContact = validateOptionalContact(contactNumber);
        String cleanEmail = validateOptionalEmail(email);

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                User target = userDAO.findById(conn, userId)
                        .orElseThrow(() -> new BusinessException("Staff member not found."));

                if (userDAO.existsByUsernameExcludingId(conn, cleanUsername, userId)) {
                    throw new BusinessException("Username is already in use.");
                }

                String hash = (plainPassword == null || plainPassword.isBlank()) ? null : PasswordUtil.hash(plainPassword);
                try {
                    userDAO.updateProfile(conn, userId, cleanFullName, cleanUsername, hash, cleanContact, cleanEmail);
                } catch (SQLIntegrityConstraintViolationException e) {
                    throw new BusinessException("Username is already in use.");
                }

                if (target.getRole() == UserRole.DENTIST && target.getDentistId() != null) {
                    String cleanSpecialty = requireText(specialty, "Specialty is required for a dentist account.");
                    String cleanWorkingDays = validateWorkingDays(workingDays);
                    dentistDAO.update(conn, target.getDentistId(), cleanFullName, cleanSpecialty,
                            cleanContact, cleanEmail, cleanWorkingDays);
                } else if (target.getRole() == UserRole.CLINICAL_ASSISTANT && assignedDentistId != null) {
                    if (!assignedDentistId.equals(target.getAssignedDentistId())) {
                        validateAssignedDentist(assignedDentistId);
                        userDAO.updateAssignedDentist(conn, userId, assignedDentistId);
                    }
                }

                conn.commit();
                return userDAO.findById(userId).orElseThrow(() -> new BusinessException("Staff member not found."));
            } catch (BusinessException e) {
                conn.rollback();
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                LOGGER.log(Level.SEVERE, "Failed to update staff account", e);
                throw new BusinessException("Unable to update staff account right now.");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database connection error while updating staff account", e);
            throw new BusinessException("Unable to update staff account right now.");
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

            // Moving a DENTIST account to a different role leaves its old
            // dentists row without any account behind it - keep the row (for
            // historical appointments/bills) but mark it unavailable for new
            // bookings rather than leaving a dangling "active" dentist.
            if (target.getRole() == UserRole.DENTIST && newRole != UserRole.DENTIST && target.getDentistId() != null) {
                dentistDAO.updateActiveStatus(target.getDentistId(), false);
            }

            return userDAO.findById(userId).orElseThrow(() -> new BusinessException("Staff member not found."));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update staff role", e);
            throw new BusinessException("Unable to update staff account right now.");
        }
    }

    /**
     * Activating/deactivating a DENTIST account also flips the linked
     * dentists row's own status in the same transaction, so "Active"/
     * "Inactive" in Doctor Staff always agrees with whether that person can
     * actually log in - and an inactive dentist is correctly excluded from
     * the active-dentist list new appointments are booked against.
     */
    public User updateActiveStatus(int userId, boolean active) {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                User target = userDAO.findById(conn, userId)
                        .orElseThrow(() -> new BusinessException("Staff member not found."));

                if (!active && target.getRole() == UserRole.ADMIN && target.isActive()) {
                    ensureNotLastActiveAdmin(conn);
                }

                userDAO.updateActive(conn, userId, active);
                if (target.getRole() == UserRole.DENTIST && target.getDentistId() != null) {
                    dentistDAO.updateActiveStatus(conn, target.getDentistId(), active);
                }

                conn.commit();
                return userDAO.findById(userId).orElseThrow(() -> new BusinessException("Staff member not found."));
            } catch (BusinessException e) {
                conn.rollback();
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                LOGGER.log(Level.SEVERE, "Failed to update staff status", e);
                throw new BusinessException("Unable to update staff account right now.");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database connection error while updating staff status", e);
            throw new BusinessException("Unable to update staff account right now.");
        }
    }

    private void ensureNotLastActiveAdmin() throws SQLException {
        if (userDAO.countActiveByRole(UserRole.ADMIN) <= 1) {
            throw new BusinessException("The system must have at least one active administrator.");
        }
    }

    private void ensureNotLastActiveAdmin(Connection conn) throws SQLException {
        if (userDAO.countActiveByRole(conn, UserRole.ADMIN) <= 1) {
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
            validateAssignedDentist(assignedDentistId);
        } else if (assignedDentistId != null) {
            throw new BusinessException("Assigned dentist is only applicable to Clinical Assistant accounts.");
        }
    }

    private void validateAssignedDentist(Integer assignedDentistId) {
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
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private String validateOptionalContact(String contactNumber) {
        if (contactNumber == null || contactNumber.isBlank()) {
            return null;
        }
        String trimmed = contactNumber.trim();
        if (!CONTACT_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException("Please enter a valid contact number.");
        }
        return trimmed;
    }

    private String validateOptionalEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.length() > 150 || !EMAIL_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException("Please enter a valid email address.");
        }
        return trimmed;
    }

    private String validateWorkingDays(String workingDays) {
        if (workingDays == null || workingDays.isBlank()) {
            return DEFAULT_WORKING_DAYS;
        }
        String trimmed = workingDays.trim();
        if (!WORKING_DAYS_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException("Please select valid working days.");
        }
        return trimmed;
    }
}

package com.sunrisedental.service;

import com.sunrisedental.dao.NotificationDAO;
import com.sunrisedental.dao.UserDAO;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.Bill;
import com.sunrisedental.model.Notification;
import com.sunrisedental.model.NotificationType;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Notification/messaging business logic: automatic system events (fired
 * best-effort from AppointmentService/BillingService, after their own
 * transaction has already committed - see {@link #safely}) and manual staff
 * messages (validated against a server-computed eligible-recipient set, so
 * the frontend's recipient list is only ever a display hint, never trusted).
 */
public class NotificationService {

    private static final Logger LOGGER = Logger.getLogger(NotificationService.class.getName());
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final NotificationDAO notificationDAO;
    private final UserDAO userDAO;

    public NotificationService() {
        this(new NotificationDAO(), new UserDAO());
    }

    public NotificationService(NotificationDAO notificationDAO, UserDAO userDAO) {
        this.notificationDAO = notificationDAO;
        this.userDAO = userDAO;
    }

    // ---------------------------------------------------------------- reads

    public List<Notification> listForUser(int userId, int limit, boolean unreadOnly) {
        try {
            return notificationDAO.findForUser(userId, limit, unreadOnly);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list notifications", e);
            throw new BusinessException("Unable to retrieve notifications right now.");
        }
    }

    public int unreadCount(int userId) {
        try {
            return notificationDAO.countUnread(userId);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to count unread notifications", e);
            throw new BusinessException("Unable to retrieve notifications right now.");
        }
    }

    /** No matching row (wrong id, or someone else's notification) is silently a no-op. */
    public void markRead(int notificationId, int userId) {
        try {
            notificationDAO.markRead(notificationId, userId);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to mark notification read", e);
            throw new BusinessException("Unable to update this notification right now.");
        }
    }

    public void markAllRead(int userId) {
        try {
            notificationDAO.markAllRead(userId);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to mark all notifications read", e);
            throw new BusinessException("Unable to update notifications right now.");
        }
    }

    // ------------------------------------------------------- system events

    public void notifyAppointmentCreated(Appointment appointment, Integer actingUserId) {
        safely(() -> {
            List<User> activeUsers = userDAO.findActive();
            List<Integer> recipients = clinicalRecipients(activeUsers, appointment.getDentist().getDentistId(), actingUserId);
            String title = "New appointment created";
            String message = appointment.getPatient().getPatientName() + " has a new "
                    + appointment.getTreatment().getTreatmentName() + " appointment with "
                    + appointment.getDentist().getDentistName() + " on " + appointment.getAppointmentDate()
                    + " at " + appointment.getAppointmentTime() + ".";
            createSystemNotifications(recipients, NotificationType.APPOINTMENT_CREATED, title, message,
                    "APPOINTMENT", appointment.getAppointmentId());
        });
    }

    public void notifyAppointmentCancelled(Appointment appointment, Integer actingUserId) {
        safely(() -> {
            List<User> activeUsers = userDAO.findActive();
            List<Integer> recipients = clinicalRecipients(activeUsers, appointment.getDentist().getDentistId(), actingUserId);
            String title = "Appointment cancelled";
            String message = appointment.getPatient().getPatientName() + "'s " + appointment.getAppointmentTime()
                    + " appointment with " + appointment.getDentist().getDentistName() + " has been cancelled.";
            createSystemNotifications(recipients, NotificationType.APPOINTMENT_CANCELLED, title, message,
                    "APPOINTMENT", appointment.getAppointmentId());
        });
    }

    public void notifyAppointmentRescheduled(Appointment appointment, Integer actingUserId) {
        safely(() -> {
            List<User> activeUsers = userDAO.findActive();
            List<Integer> recipients = clinicalRecipients(activeUsers, appointment.getDentist().getDentistId(), actingUserId);
            String title = "Appointment rescheduled";
            String message = appointment.getPatient().getPatientName() + "'s appointment with "
                    + appointment.getDentist().getDentistName() + " has been rescheduled to "
                    + appointment.getAppointmentDate() + " at " + appointment.getAppointmentTime() + ".";
            createSystemNotifications(recipients, NotificationType.APPOINTMENT_RESCHEDULED, title, message,
                    "APPOINTMENT", appointment.getAppointmentId());
        });
    }

    /** Fired when a SCHEDULED appointment becomes CHECKED_IN (a real status now, not inferred from actor role). */
    public void notifyPatientCheckedIn(Appointment appointment, Integer actingUserId) {
        safely(() -> {
            List<User> activeUsers = userDAO.findActive();
            List<Integer> recipients = new ArrayList<>();
            recipients.addAll(byRoleAndDentist(activeUsers, UserRole.DENTIST, appointment.getDentist().getDentistId()));
            recipients.addAll(byRoles(activeUsers, UserRole.RECEPTIONIST, UserRole.ADMIN));
            String title = "Patient checked in";
            String message = appointment.getPatient().getPatientName() + " has checked in for their "
                    + appointment.getAppointmentTime() + " appointment.";
            createSystemNotifications(dedupeExcluding(recipients, actingUserId), NotificationType.PATIENT_CHECKED_IN,
                    title, message, "APPOINTMENT", appointment.getAppointmentId());
        });
    }

    /** Fired when an appointment becomes COMPLETED (from SCHEDULED or CHECKED_IN) - what Billing waits for. */
    public void notifyAppointmentCompleted(Appointment appointment, Integer actingUserId) {
        safely(() -> {
            List<User> activeUsers = userDAO.findActive();
            List<Integer> recipients = byRoles(activeUsers, UserRole.BILLING, UserRole.RECEPTIONIST, UserRole.ADMIN);
            String title = "Appointment completed";
            String message = appointment.getPatient().getPatientName() + "'s appointment has been completed.";
            createSystemNotifications(dedupeExcluding(recipients, actingUserId), NotificationType.APPOINTMENT_COMPLETED,
                    title, message, "APPOINTMENT", appointment.getAppointmentId());
        });
    }

    public void notifyBillGenerated(Bill bill, String patientName, Integer actingUserId) {
        safely(() -> {
            List<User> activeUsers = userDAO.findActive();
            List<Integer> recipients = byRoles(activeUsers, UserRole.BILLING, UserRole.RECEPTIONIST, UserRole.ADMIN);
            String title = "Bill generated";
            String message = "A bill of Rs. " + bill.getTotalAmount() + " has been generated for " + patientName + ".";
            // Reference the underlying appointment (not the bill row) so the
            // existing appointment/bill join in NotificationDAO can resolve
            // display context and navigation with a single join, the same as
            // every other appointment-related notification type.
            createSystemNotifications(dedupeExcluding(recipients, actingUserId), NotificationType.BILL_GENERATED,
                    title, message, "APPOINTMENT", bill.getAppointmentId());
        });
    }

    // ------------------------------------------------------ manual messages

    public List<User> eligibleRecipients(UserRole senderRole, Integer senderOwnDentistId,
                                          Integer senderAssignedDentistId, int senderUserId) {
        try {
            List<User> eligible = new ArrayList<>();
            for (User candidate : userDAO.findActive()) {
                if (candidate.getUserId() == senderUserId) {
                    continue;
                }
                if (isEligibleRecipient(candidate, senderRole, senderOwnDentistId, senderAssignedDentistId)) {
                    eligible.add(candidate);
                }
            }
            return eligible;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to resolve eligible recipients", e);
            throw new BusinessException("Unable to retrieve recipients right now.");
        }
    }

    public void sendMessage(int senderUserId, UserRole senderRole, Integer senderOwnDentistId,
                             Integer senderAssignedDentistId, NotificationType type, String messageText,
                             Integer appointmentId, List<Integer> recipientUserIds) {
        if (!isManualType(type)) {
            throw new BusinessException("Invalid message type.");
        }
        if (messageText == null || messageText.isBlank()) {
            throw new BusinessException("Message is required.");
        }
        String trimmed = messageText.trim();
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw new BusinessException("Message is too long.");
        }
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            throw new BusinessException("At least one recipient is required.");
        }

        Set<Integer> eligibleIds = new LinkedHashSet<>();
        for (User u : eligibleRecipients(senderRole, senderOwnDentistId, senderAssignedDentistId, senderUserId)) {
            eligibleIds.add(u.getUserId());
        }
        for (Integer recipientId : recipientUserIds) {
            if (!eligibleIds.contains(recipientId)) {
                throw new ForbiddenException("You are not authorized to message one or more of the selected recipients.");
            }
        }

        try {
            String title = manualTitleFor(type);
            String referenceType = appointmentId != null ? "APPOINTMENT" : null;
            for (Integer recipientId : new LinkedHashSet<>(recipientUserIds)) {
                notificationDAO.create(recipientId, senderUserId, type, title, trimmed, referenceType, appointmentId);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to send message", e);
            throw new BusinessException("Unable to send this message right now.");
        }
    }

    // ------------------------------------------------------------- helpers

    private boolean isEligibleRecipient(User candidate, UserRole senderRole, Integer senderOwnDentistId,
                                         Integer senderAssignedDentistId) {
        return switch (senderRole) {
            case ADMIN -> true;
            case RECEPTIONIST -> candidate.getRole() == UserRole.DENTIST || candidate.getRole() == UserRole.CLINICAL_ASSISTANT
                    || candidate.getRole() == UserRole.RECEPTIONIST || candidate.getRole() == UserRole.ADMIN;
            case DENTIST -> candidate.getRole() == UserRole.RECEPTIONIST || candidate.getRole() == UserRole.ADMIN
                    || (candidate.getRole() == UserRole.CLINICAL_ASSISTANT
                        && Objects.equals(candidate.getAssignedDentistId(), senderOwnDentistId));
            case CLINICAL_ASSISTANT -> candidate.getRole() == UserRole.RECEPTIONIST || candidate.getRole() == UserRole.ADMIN
                    || (candidate.getRole() == UserRole.DENTIST
                        && Objects.equals(candidate.getDentistId(), senderAssignedDentistId));
            case BILLING -> candidate.getRole() == UserRole.RECEPTIONIST || candidate.getRole() == UserRole.ADMIN
                    || candidate.getRole() == UserRole.BILLING;
        };
    }

    private boolean isManualType(NotificationType type) {
        return type == NotificationType.PATIENT_RUNNING_LATE || type == NotificationType.DENTIST_RUNNING_LATE
                || type == NotificationType.PATIENT_ARRIVED || type == NotificationType.APPOINTMENT_CANCELLED
                || type == NotificationType.APPOINTMENT_RESCHEDULED || type == NotificationType.GENERAL_MESSAGE;
    }

    private String manualTitleFor(NotificationType type) {
        return switch (type) {
            case PATIENT_RUNNING_LATE -> "Patient running late";
            case DENTIST_RUNNING_LATE -> "Dentist running late";
            case PATIENT_ARRIVED -> "Patient arrived";
            case APPOINTMENT_CANCELLED -> "Appointment cancelled";
            case APPOINTMENT_RESCHEDULED -> "Appointment rescheduled";
            case GENERAL_MESSAGE -> "General message";
            default -> throw new BusinessException("Invalid message type.");
        };
    }

    /** Assigned dentist's user account + that dentist's assistant(s) + reception + admin. */
    private List<Integer> clinicalRecipients(List<User> activeUsers, int dentistId, Integer actingUserId) {
        List<Integer> recipients = new ArrayList<>();
        recipients.addAll(byRoleAndDentist(activeUsers, UserRole.DENTIST, dentistId));
        recipients.addAll(byRoleAndAssignedDentist(activeUsers, dentistId));
        recipients.addAll(byRoles(activeUsers, UserRole.RECEPTIONIST, UserRole.ADMIN));
        return dedupeExcluding(recipients, actingUserId);
    }

    private List<Integer> byRoles(List<User> activeUsers, UserRole... roles) {
        List<Integer> ids = new ArrayList<>();
        for (User u : activeUsers) {
            for (UserRole role : roles) {
                if (u.getRole() == role) {
                    ids.add(u.getUserId());
                    break;
                }
            }
        }
        return ids;
    }

    private List<Integer> byRoleAndDentist(List<User> activeUsers, UserRole role, int dentistId) {
        List<Integer> ids = new ArrayList<>();
        for (User u : activeUsers) {
            if (u.getRole() == role && u.getDentistId() != null && u.getDentistId() == dentistId) {
                ids.add(u.getUserId());
            }
        }
        return ids;
    }

    private List<Integer> byRoleAndAssignedDentist(List<User> activeUsers, int dentistId) {
        List<Integer> ids = new ArrayList<>();
        for (User u : activeUsers) {
            if (u.getRole() == UserRole.CLINICAL_ASSISTANT && u.getAssignedDentistId() != null
                    && u.getAssignedDentistId() == dentistId) {
                ids.add(u.getUserId());
            }
        }
        return ids;
    }

    private List<Integer> dedupeExcluding(List<Integer> ids, Integer excludeUserId) {
        Set<Integer> deduped = new LinkedHashSet<>(ids);
        if (excludeUserId != null) {
            deduped.remove(excludeUserId);
        }
        return new ArrayList<>(deduped);
    }

    private void createSystemNotifications(List<Integer> recipients, NotificationType type, String title,
                                            String message, String referenceType, Integer referenceId) throws SQLException {
        // sender_user_id is always null here - this is what marks a row as a
        // SYSTEM notification rather than a staff MESSAGE (see NotificationType).
        for (int recipientId : recipients) {
            notificationDAO.create(recipientId, null, type, title, message, referenceType, referenceId);
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    /**
     * Notification creation is a best-effort side effect of a real
     * appointment/billing event - it must never fail or roll back the
     * caller's actual operation, so every failure is only logged here.
     */
    private void safely(ThrowingAction action) {
        try {
            action.run();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create notification(s) for a system event - continuing without them", e);
        }
    }
}

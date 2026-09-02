package com.sunrisedental.model;

/**
 * Controlled set of notification types. APPOINTMENT_CREATED/CANCELLED/
 * RESCHEDULED/COMPLETED, PATIENT_CHECKED_IN and BILL_GENERATED are only ever
 * created by the backend itself (system events). PATIENT_RUNNING_LATE,
 * DENTIST_RUNNING_LATE, PATIENT_ARRIVED and GENERAL_MESSAGE are only ever
 * created by a staff member composing a message. APPOINTMENT_CANCELLED and
 * APPOINTMENT_RESCHEDULED are the two exceptions - a staff member can also
 * send one of these as a manual heads-up (e.g. phoned in ahead of the actual
 * cancellation being processed), independent of the system-fired one that
 * happens when the real status change goes through.
 * Whether a given row is a SYSTEM event or a staff MESSAGE is not stored as
 * its own column - it is derived from sender_user_id being null (system) or
 * not (message), which is enough to distinguish them and avoids a redundant
 * column.
 */
public enum NotificationType {
    APPOINTMENT_CREATED,
    APPOINTMENT_CANCELLED,
    APPOINTMENT_RESCHEDULED,
    APPOINTMENT_COMPLETED,
    PATIENT_CHECKED_IN,
    BILL_GENERATED,
    PATIENT_RUNNING_LATE,
    DENTIST_RUNNING_LATE,
    PATIENT_ARRIVED,
    GENERAL_MESSAGE
}

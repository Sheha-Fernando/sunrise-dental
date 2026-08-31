package com.sunrisedental.service;

import com.sunrisedental.dao.AppointmentDAO;
import com.sunrisedental.dao.DentistDAO;
import com.sunrisedental.dao.PatientDAO;
import com.sunrisedental.dao.TreatmentDAO;
import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.Patient;
import com.sunrisedental.model.Treatment;
import com.sunrisedental.model.UserRole;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AppointmentService {

    private static final Logger LOGGER = Logger.getLogger(AppointmentService.class.getName());

    public static final String APPOINTMENT_NOT_FOUND = "We couldn't find that appointment.";
    private static final int MAX_CANCELLATION_REASON_LENGTH = 255;

    private final PatientDAO patientDAO;
    private final DentistDAO dentistDAO;
    private final TreatmentDAO treatmentDAO;
    private final AppointmentDAO appointmentDAO;

    public AppointmentService() {
        this(new PatientDAO(), new DentistDAO(), new TreatmentDAO(), new AppointmentDAO());
    }

    public AppointmentService(PatientDAO patientDAO, DentistDAO dentistDAO,
                               TreatmentDAO treatmentDAO, AppointmentDAO appointmentDAO) {
        this.patientDAO = patientDAO;
        this.dentistDAO = dentistDAO;
        this.treatmentDAO = treatmentDAO;
        this.appointmentDAO = appointmentDAO;
    }

    public List<Dentist> listActiveDentists() {
        try {
            return dentistDAO.findActive();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list active dentists", e);
            throw new BusinessException("Unable to retrieve dentists right now.");
        }
    }

    public List<Treatment> listActiveTreatments() {
        try {
            return treatmentDAO.findActive();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list active treatments", e);
            throw new BusinessException("Unable to retrieve treatments right now.");
        }
    }

    public boolean isDentistBooked(int dentistId, java.time.LocalDate date, java.time.LocalTime time) {
        try {
            return appointmentDAO.isDentistBooked(dentistId, date, time);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to check dentist availability", e);
            throw new BusinessException("Unable to check dentist availability right now.");
        }
    }

    /**
     * A DENTIST may only access their own appointments; a CLINICAL_ASSISTANT
     * is scoped the same way, but to their assigned dentist rather than a
     * dentist_id of their own. In both cases the caller (servlet) resolves
     * the correct session value into scopeDentistId before calling this -
     * this method just enforces "does the appointment's dentist match the
     * scope this session is limited to". Every other role granted access to
     * this endpoint (ADMIN/RECEPTIONIST/BILLING) passes through unrestricted.
     */
    public void verifyDentistOwnership(Appointment appointment, UserRole role, Integer scopeDentistId) {
        if (role != UserRole.DENTIST && role != UserRole.CLINICAL_ASSISTANT) {
            return;
        }
        if (scopeDentistId == null || appointment.getDentist().getDentistId() != scopeDentistId) {
            throw new ForbiddenException("You are not authorized to access this appointment.");
        }
    }

    /**
     * Schedule/dashboard listing. A DENTIST or CLINICAL_ASSISTANT session is
     * always forced to its resolved scopeDentistId, regardless of any
     * dentistId the caller passed in - this is the server-side enforcement
     * of data isolation for the list endpoint (the single-appointment
     * endpoint enforces it via verifyDentistOwnership instead).
     */
    public List<Appointment> listAppointments(LocalDate date, com.sunrisedental.model.AppointmentStatus status,
                                               Integer requestedDentistId, UserRole role, Integer scopeDentistId) {
        boolean isScopedRole = (role == UserRole.DENTIST || role == UserRole.CLINICAL_ASSISTANT);
        Integer effectiveDentistId = isScopedRole ? scopeDentistId : requestedDentistId;
        try {
            return appointmentDAO.findAll(date, status, effectiveDentistId);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list appointments", e);
            throw new BusinessException("Unable to retrieve appointments right now.");
        }
    }

    public List<Appointment> listByPatient(int patientId) {
        try {
            return appointmentDAO.findByPatientId(patientId);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list appointments for patient", e);
            throw new BusinessException("Unable to retrieve appointment history right now.");
        }
    }

    public Appointment findByAppointmentNumber(String appointmentNumber) {
        try {
            return appointmentDAO.findByAppointmentNumber(appointmentNumber)
                    .orElseThrow(() -> new BusinessException("Appointment not found."));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to search for appointment", e);
            throw new BusinessException("Unable to search for the appointment right now.");
        }
    }

    /**
     * Validates input, checks dentist/treatment availability, optionally
     * creates a new patient, and inserts the appointment - all inside a
     * single JDBC transaction so a partial failure leaves no orphan rows.
     */
    public Appointment createAppointment(NewAppointmentRequest request) {
        validate(request);

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Dentist dentist = dentistDAO.findById(conn, request.dentistId())
                        .filter(Dentist::isActive)
                        .orElseThrow(() -> new BusinessException("Dentist is unavailable."));

                Treatment treatment = treatmentDAO.findById(conn, request.treatmentId())
                        .filter(Treatment::isActive)
                        .orElseThrow(() -> new BusinessException("Treatment not found."));

                if (appointmentDAO.isDentistBooked(conn, request.dentistId(),
                        request.appointmentDate(), request.appointmentTime())) {
                    throw new BusinessException("This dentist is already booked at this date and time. Please select another time.");
                }

                Patient patient;
                if (request.patientId() != null) {
                    patient = patientDAO.findById(conn, request.patientId())
                            .orElseThrow(() -> new BusinessException("Patient not found."));
                } else {
                    patient = new Patient();
                    patient.setPatientName(request.patientName());
                    patient.setAddress(request.address());
                    patient.setContactNumber(request.contactNumber());
                    patientDAO.create(conn, patient);
                }

                // The appointment number is not staff input: insert with a
                // throwaway placeholder (unique-enough, never shown to
                // anyone), then assign the real "APT-NNNNNN" number derived
                // from the real appointment_id once it's known - unique by
                // construction, no guessing, no separate sequence table.
                String placeholder = "TMP" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 15);
                int appointmentId;
                try {
                    appointmentId = appointmentDAO.create(conn, patient.getPatientId(), dentist.getDentistId(),
                            treatment.getTreatmentId(), placeholder,
                            request.appointmentDate(), request.appointmentTime(), request.createdByUserId());
                } catch (SQLIntegrityConstraintViolationException e) {
                    throw translateDuplicateKey(e);
                }

                String appointmentNumber = String.format("APT-%06d", appointmentId);
                appointmentDAO.updateAppointmentNumber(conn, appointmentId, appointmentNumber);

                Appointment appointment = new Appointment();
                appointment.setAppointmentId(appointmentId);
                appointment.setAppointmentNumber(appointmentNumber);
                appointment.setPatient(patient);
                appointment.setDentist(dentist);
                appointment.setTreatment(treatment);
                appointment.setAppointmentDate(request.appointmentDate());
                appointment.setAppointmentTime(request.appointmentTime());
                appointment.setStatus(com.sunrisedental.model.AppointmentStatus.SCHEDULED);
                appointment.setCreatedBy(request.createdByUserId());

                conn.commit();
                return appointment;
            } catch (BusinessException e) {
                conn.rollback();
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                LOGGER.log(Level.SEVERE, "Failed to register appointment", e);
                throw new BusinessException("Unable to register the appointment right now. Please try again.");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database connection error while registering appointment", e);
            throw new BusinessException("Unable to connect to the database.");
        }
    }

    /**
     * Cancels or completes a SCHEDULED appointment. Role gating (who may call
     * this at all) happens in the servlet; this method enforces the status
     * transition rules and, for COMPLETED, dentist/assistant ownership.
     */
    public Appointment updateStatus(String appointmentNumber, com.sunrisedental.model.AppointmentStatus newStatus,
                                     String cancellationReason, UserRole role, Integer scopeDentistId) {
        if (newStatus != com.sunrisedental.model.AppointmentStatus.COMPLETED
                && newStatus != com.sunrisedental.model.AppointmentStatus.CANCELLED) {
            throw new BusinessException("This appointment status cannot be changed.");
        }
        String reason = normalizeCancellationReason(cancellationReason);

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Appointment appointment = appointmentDAO.findByAppointmentNumber(conn, appointmentNumber)
                        .orElseThrow(() -> new BusinessException(APPOINTMENT_NOT_FOUND));

                if (newStatus == com.sunrisedental.model.AppointmentStatus.COMPLETED) {
                    verifyDentistOwnership(appointment, role, scopeDentistId);
                }

                switch (appointment.getStatus()) {
                    case COMPLETED:
                        throw new BusinessException("This appointment has already been completed and cannot be cancelled.");
                    case CANCELLED:
                        throw new BusinessException("This appointment has already been cancelled.");
                    default:
                        break;
                }

                appointmentDAO.updateStatus(conn, appointment.getAppointmentId(), newStatus,
                        newStatus == com.sunrisedental.model.AppointmentStatus.CANCELLED ? reason : null);
                conn.commit();

                appointment.setStatus(newStatus);
                appointment.setCancellationReason(newStatus == com.sunrisedental.model.AppointmentStatus.CANCELLED ? reason : null);
                return appointment;
            } catch (BusinessException | ForbiddenException e) {
                conn.rollback();
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                LOGGER.log(Level.SEVERE, "Failed to update appointment status", e);
                throw new BusinessException("We couldn't update the appointment right now. Please try again.");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database connection error while updating appointment status", e);
            throw new BusinessException("Unable to connect to the database.");
        }
    }

    /**
     * Reschedules a SCHEDULED appointment to a new dentist/date/time.
     * Role gating happens in the servlet (ADMIN/RECEPTIONIST only); the
     * dentist's availability check and the database's active_slot_key
     * constraint remain the authoritative double-booking protection.
     */
    public Appointment reschedule(String appointmentNumber, int newDentistId, LocalDate newDate, LocalTime newTime) {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Appointment appointment = appointmentDAO.findByAppointmentNumber(conn, appointmentNumber)
                        .orElseThrow(() -> new BusinessException(APPOINTMENT_NOT_FOUND));

                switch (appointment.getStatus()) {
                    case COMPLETED:
                        throw new BusinessException("This appointment has already been completed and cannot be rescheduled.");
                    case CANCELLED:
                        throw new BusinessException("This appointment has already been cancelled and cannot be rescheduled.");
                    default:
                        break;
                }

                Dentist dentist = dentistDAO.findById(conn, newDentistId)
                        .filter(Dentist::isActive)
                        .orElseThrow(() -> new BusinessException("Dentist is unavailable."));

                if (appointmentDAO.isDentistBookedExcluding(conn, newDentistId, newDate, newTime, appointment.getAppointmentId())) {
                    throw new BusinessException("This time is already booked for the selected dentist.");
                }

                try {
                    appointmentDAO.reschedule(conn, appointment.getAppointmentId(), newDentistId, newDate, newTime);
                } catch (SQLIntegrityConstraintViolationException e) {
                    throw translateDuplicateKey(e);
                }
                conn.commit();

                appointment.setDentist(dentist);
                appointment.setAppointmentDate(newDate);
                appointment.setAppointmentTime(newTime);
                return appointment;
            } catch (BusinessException e) {
                conn.rollback();
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                LOGGER.log(Level.SEVERE, "Failed to reschedule appointment", e);
                throw new BusinessException("We couldn't update the appointment right now. Please try again.");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database connection error while rescheduling appointment", e);
            throw new BusinessException("Unable to connect to the database.");
        }
    }

    private String normalizeCancellationReason(String cancellationReason) {
        if (cancellationReason == null || cancellationReason.isBlank()) {
            return null;
        }
        String trimmed = cancellationReason.trim();
        if (trimmed.length() > MAX_CANCELLATION_REASON_LENGTH) {
            throw new BusinessException("Cancellation reason is too long.");
        }
        return trimmed;
    }

    private BusinessException translateDuplicateKey(SQLIntegrityConstraintViolationException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("uq_appointments_dentist_slot")) {
            return new BusinessException("This dentist is already booked at this date and time. Please select another time.");
        }
        LOGGER.log(Level.SEVERE, "Unrecognized duplicate key on appointment insert", e);
        return new BusinessException("Unable to register the appointment right now. Please try again.");
    }

    private void validate(NewAppointmentRequest request) {
        if (request.appointmentDate() == null) {
            throw new BusinessException("Appointment date is required.");
        }
        if (request.appointmentTime() == null) {
            throw new BusinessException("Appointment time is required.");
        }
        if (request.patientId() == null) {
            if (request.patientName() == null || request.patientName().isBlank()) {
                throw new BusinessException("Patient name is required.");
            }
            if (request.address() == null || request.address().isBlank()) {
                throw new BusinessException("Address is required.");
            }
            if (request.contactNumber() == null || request.contactNumber().isBlank()) {
                throw new BusinessException("Contact number is required.");
            }
        }
    }
}

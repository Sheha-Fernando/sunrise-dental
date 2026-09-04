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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AppointmentService {

    private static final Logger LOGGER = Logger.getLogger(AppointmentService.class.getName());

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
     * A DENTIST user may only access appointments assigned to their own
     * dentist record - never another dentist's patients. Every other role
     * granted access to this endpoint (ADMIN/RECEPTIONIST/BILLING per the
     * authorization matrix) passes through unrestricted.
     */
    public void verifyDentistOwnership(Appointment appointment, UserRole role, Integer sessionDentistId) {
        if (role != UserRole.DENTIST) {
            return;
        }
        if (sessionDentistId == null || appointment.getDentist().getDentistId() != sessionDentistId) {
            throw new ForbiddenException("You are not authorized to access this appointment.");
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

                if (appointmentDAO.existsByAppointmentNumber(conn, request.appointmentNumber())) {
                    throw new BusinessException("Appointment number is already in use.");
                }

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

                int appointmentId;
                try {
                    appointmentId = appointmentDAO.create(conn, patient.getPatientId(), dentist.getDentistId(),
                            treatment.getTreatmentId(), request.appointmentNumber(),
                            request.appointmentDate(), request.appointmentTime(), request.createdByUserId());
                } catch (SQLIntegrityConstraintViolationException e) {
                    throw translateDuplicateKey(e);
                }

                Appointment appointment = new Appointment();
                appointment.setAppointmentId(appointmentId);
                appointment.setAppointmentNumber(request.appointmentNumber());
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

    private BusinessException translateDuplicateKey(SQLIntegrityConstraintViolationException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("uq_appointments_number")) {
            return new BusinessException("Appointment number is already in use.");
        }
        if (message.contains("uq_appointments_dentist_slot")) {
            return new BusinessException("This dentist is already booked at this date and time. Please select another time.");
        }
        LOGGER.log(Level.SEVERE, "Unrecognized duplicate key on appointment insert", e);
        return new BusinessException("Unable to register the appointment right now. Please try again.");
    }

    private void validate(NewAppointmentRequest request) {
        if (request.appointmentNumber() == null || request.appointmentNumber().isBlank()) {
            throw new BusinessException("Appointment number is required.");
        }
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

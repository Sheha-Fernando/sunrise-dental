package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.AppointmentStatus;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.Patient;
import com.sunrisedental.model.Treatment;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AppointmentDAO {

    private static final String JOIN_SELECT =
            "SELECT a.appointment_id, a.appointment_number, a.appointment_date, a.appointment_time, "
          + "       a.status, a.cancellation_reason, a.created_by, a.created_at, "
          + "       p.patient_id, p.patient_name, p.address, p.contact_number, p.created_at AS patient_created_at, "
          + "       d.dentist_id, d.dentist_name, d.contact_number AS dentist_contact, d.is_active AS dentist_active, "
          + "       t.treatment_id, t.treatment_name, t.cost, t.is_active AS treatment_active "
          + "FROM appointments a "
          + "JOIN patients p ON a.patient_id = p.patient_id "
          + "JOIN dentists d ON a.dentist_id = d.dentist_id "
          + "JOIN treatments t ON a.treatment_id = t.treatment_id ";

    /**
     * Lists appointments for the schedule/dashboard views. All filters are
     * optional; callers (AppointmentService) are responsible for forcing
     * dentistId when the caller is a DENTIST - this method itself applies
     * no authorization, only the filters it is given.
     */
    public List<Appointment> findAll(LocalDate date, AppointmentStatus status, Integer dentistId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findAll(conn, date, status, dentistId);
        }
    }

    public List<Appointment> findAll(Connection conn, LocalDate date, AppointmentStatus status, Integer dentistId)
            throws SQLException {
        StringBuilder sql = new StringBuilder(JOIN_SELECT).append("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (date != null) {
            sql.append("AND a.appointment_date = ? ");
            params.add(Date.valueOf(date));
        }
        if (status != null) {
            sql.append("AND a.status = ? ");
            params.add(status.name());
        }
        if (dentistId != null) {
            sql.append("AND a.dentist_id = ? ");
            params.add(dentistId);
        }
        sql.append("ORDER BY a.appointment_date, a.appointment_time");

        List<Appointment> appointments = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    appointments.add(mapRow(rs));
                }
            }
        }
        return appointments;
    }

    /**
     * Lists appointments within an inclusive date range for report generation.
     * Same filter/authorization contract as findAll: callers (ReportService)
     * are responsible for forcing dentistId when the caller is scoped.
     */
    public List<Appointment> findByDateRange(LocalDate from, LocalDate to, Integer dentistId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findByDateRange(conn, from, to, dentistId);
        }
    }

    public List<Appointment> findByDateRange(Connection conn, LocalDate from, LocalDate to, Integer dentistId)
            throws SQLException {
        StringBuilder sql = new StringBuilder(JOIN_SELECT).append("WHERE a.appointment_date BETWEEN ? AND ? ");
        List<Object> params = new ArrayList<>();
        params.add(Date.valueOf(from));
        params.add(Date.valueOf(to));
        if (dentistId != null) {
            sql.append("AND a.dentist_id = ? ");
            params.add(dentistId);
        }
        sql.append("ORDER BY a.appointment_date, a.appointment_time");

        List<Appointment> appointments = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    appointments.add(mapRow(rs));
                }
            }
        }
        return appointments;
    }

    public List<Appointment> findByPatientId(int patientId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            String sql = JOIN_SELECT + "WHERE a.patient_id = ? ORDER BY a.appointment_date DESC, a.appointment_time DESC";
            List<Appointment> appointments = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, patientId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        appointments.add(mapRow(rs));
                    }
                }
            }
            return appointments;
        }
    }

    public int create(Connection conn, int patientId, int dentistId, int treatmentId,
                       String appointmentNumber, LocalDate appointmentDate, LocalTime appointmentTime,
                       Integer createdBy) throws SQLException {
        String sql = "INSERT INTO appointments "
                + "(appointment_number, patient_id, dentist_id, treatment_id, appointment_date, appointment_time, status, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'SCHEDULED', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, appointmentNumber);
            ps.setInt(2, patientId);
            ps.setInt(3, dentistId);
            ps.setInt(4, treatmentId);
            ps.setDate(5, Date.valueOf(appointmentDate));
            ps.setTime(6, Time.valueOf(appointmentTime));
            if (createdBy != null) {
                ps.setInt(7, createdBy);
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new SQLException("Failed to obtain generated appointment_id");
            }
        }
    }

    /**
     * Assigns the final, staff-facing appointment number once the real
     * appointment_id is known - see AppointmentService.createAppointment for
     * why: the number is derived from the id, so it is unique by definition.
     */
    public void updateAppointmentNumber(Connection conn, int appointmentId, String appointmentNumber) throws SQLException {
        String sql = "UPDATE appointments SET appointment_number = ? WHERE appointment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, appointmentNumber);
            ps.setInt(2, appointmentId);
            ps.executeUpdate();
        }
    }

    public boolean existsByAppointmentNumber(String appointmentNumber) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return existsByAppointmentNumber(conn, appointmentNumber);
        }
    }

    public boolean existsByAppointmentNumber(Connection conn, String appointmentNumber) throws SQLException {
        String sql = "SELECT 1 FROM appointments WHERE appointment_number = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, appointmentNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean isDentistBooked(int dentistId, LocalDate date, LocalTime time) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return isDentistBooked(conn, dentistId, date, time);
        }
    }

    public boolean isDentistBooked(Connection conn, int dentistId, LocalDate date, LocalTime time) throws SQLException {
        String sql = "SELECT 1 FROM appointments "
                + "WHERE dentist_id = ? AND appointment_date = ? AND appointment_time = ? AND status <> 'CANCELLED' "
                + "LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, dentistId);
            ps.setDate(2, Date.valueOf(date));
            ps.setTime(3, Time.valueOf(time));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean isDentistBookedExcluding(Connection conn, int dentistId, LocalDate date, LocalTime time,
                                             int excludeAppointmentId) throws SQLException {
        String sql = "SELECT 1 FROM appointments "
                + "WHERE dentist_id = ? AND appointment_date = ? AND appointment_time = ? AND status <> 'CANCELLED' "
                + "AND appointment_id <> ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, dentistId);
            ps.setDate(2, Date.valueOf(date));
            ps.setTime(3, Time.valueOf(time));
            ps.setInt(4, excludeAppointmentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Cancel or complete a SCHEDULED appointment. cancellationReason is only ever set for CANCELLED. */
    public void updateStatus(Connection conn, int appointmentId, AppointmentStatus status, String cancellationReason)
            throws SQLException {
        String sql = "UPDATE appointments SET status = ?, cancellation_reason = ? WHERE appointment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            if (cancellationReason != null) {
                ps.setString(2, cancellationReason);
            } else {
                ps.setNull(2, Types.VARCHAR);
            }
            ps.setInt(3, appointmentId);
            ps.executeUpdate();
        }
    }

    /** Moves a SCHEDULED appointment to a new dentist/date/time; the generated active_slot_key recomputes automatically. */
    public void reschedule(Connection conn, int appointmentId, int dentistId, LocalDate date, LocalTime time)
            throws SQLException {
        String sql = "UPDATE appointments SET dentist_id = ?, appointment_date = ?, appointment_time = ? WHERE appointment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, dentistId);
            ps.setDate(2, Date.valueOf(date));
            ps.setTime(3, Time.valueOf(time));
            ps.setInt(4, appointmentId);
            ps.executeUpdate();
        }
    }

    public Optional<Appointment> findByAppointmentNumber(String appointmentNumber) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findByAppointmentNumber(conn, appointmentNumber);
        }
    }

    public Optional<Appointment> findByAppointmentNumber(Connection conn, String appointmentNumber) throws SQLException {
        String sql = "SELECT a.appointment_id, a.appointment_number, a.appointment_date, a.appointment_time, "
                + "       a.status, a.cancellation_reason, a.created_by, a.created_at, "
                + "       p.patient_id, p.patient_name, p.address, p.contact_number, p.created_at AS patient_created_at, "
                + "       d.dentist_id, d.dentist_name, d.contact_number AS dentist_contact, d.is_active AS dentist_active, "
                + "       t.treatment_id, t.treatment_name, t.cost, t.is_active AS treatment_active "
                + "FROM appointments a "
                + "JOIN patients p ON a.patient_id = p.patient_id "
                + "JOIN dentists d ON a.dentist_id = d.dentist_id "
                + "JOIN treatments t ON a.treatment_id = t.treatment_id "
                + "WHERE a.appointment_number = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, appointmentNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private Appointment mapRow(ResultSet rs) throws SQLException {
        Patient patient = new Patient();
        patient.setPatientId(rs.getInt("patient_id"));
        patient.setPatientName(rs.getString("patient_name"));
        patient.setAddress(rs.getString("address"));
        patient.setContactNumber(rs.getString("contact_number"));
        Timestamp patientCreatedAt = rs.getTimestamp("patient_created_at");
        patient.setCreatedAt(patientCreatedAt != null ? patientCreatedAt.toLocalDateTime() : null);

        Dentist dentist = new Dentist();
        dentist.setDentistId(rs.getInt("dentist_id"));
        dentist.setDentistName(rs.getString("dentist_name"));
        dentist.setContactNumber(rs.getString("dentist_contact"));
        dentist.setActive(rs.getBoolean("dentist_active"));

        Treatment treatment = new Treatment();
        treatment.setTreatmentId(rs.getInt("treatment_id"));
        treatment.setTreatmentName(rs.getString("treatment_name"));
        treatment.setCost(rs.getBigDecimal("cost"));
        treatment.setActive(rs.getBoolean("treatment_active"));

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(rs.getInt("appointment_id"));
        appointment.setAppointmentNumber(rs.getString("appointment_number"));
        appointment.setPatient(patient);
        appointment.setDentist(dentist);
        appointment.setTreatment(treatment);
        Date date = rs.getDate("appointment_date");
        appointment.setAppointmentDate(date != null ? date.toLocalDate() : null);
        Time time = rs.getTime("appointment_time");
        appointment.setAppointmentTime(time != null ? time.toLocalTime() : null);
        appointment.setStatus(AppointmentStatus.valueOf(rs.getString("status")));
        appointment.setCancellationReason(rs.getString("cancellation_reason"));
        int createdBy = rs.getInt("created_by");
        appointment.setCreatedBy(rs.wasNull() ? null : createdBy);
        Timestamp createdAt = rs.getTimestamp("created_at");
        appointment.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);
        return appointment;
    }
}

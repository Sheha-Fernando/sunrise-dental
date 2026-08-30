package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.Patient;
import com.sunrisedental.model.PatientSummary;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PatientDAO {

    /**
     * Patients list screen: name/contact search, plus the dentist and date
     * of the patient's most recent non-cancelled visit ("assigned dentist" /
     * "last visit" - there is no dedicated dentist assignment column, since
     * a patient can see several dentists over time), and their nearest
     * upcoming scheduled appointment ("next appointment"), if any.
     * search may be null/blank for "all".
     */
    public List<PatientSummary> findAllSummaries(String search) throws SQLException {
        String sql = "SELECT p.patient_id, p.patient_name, p.contact_number, p.created_at, "
                + "       lv.appointment_date AS last_visit_date, lv.dentist_name AS last_visit_dentist, "
                + "       na.appointment_date AS next_appointment_date, na.appointment_time AS next_appointment_time "
                + "FROM patients p "
                + "LEFT JOIN ("
                + "    SELECT x.patient_id, x.appointment_date, x.dentist_name FROM ("
                + "        SELECT a.patient_id, a.appointment_date, d.dentist_name, "
                + "               ROW_NUMBER() OVER (PARTITION BY a.patient_id "
                + "                   ORDER BY a.appointment_date DESC, a.appointment_time DESC) AS rn "
                + "        FROM appointments a JOIN dentists d ON a.dentist_id = d.dentist_id "
                + "        WHERE a.status <> 'CANCELLED'"
                + "    ) x WHERE x.rn = 1"
                + ") lv ON lv.patient_id = p.patient_id "
                + "LEFT JOIN ("
                + "    SELECT y.patient_id, y.appointment_date, y.appointment_time FROM ("
                + "        SELECT a.patient_id, a.appointment_date, a.appointment_time, "
                + "               ROW_NUMBER() OVER (PARTITION BY a.patient_id "
                + "                   ORDER BY a.appointment_date ASC, a.appointment_time ASC) AS rn "
                + "        FROM appointments a "
                + "        WHERE a.status = 'SCHEDULED' "
                + "          AND TIMESTAMP(a.appointment_date, a.appointment_time) >= NOW()"
                + "    ) y WHERE y.rn = 1"
                + ") na ON na.patient_id = p.patient_id "
                + (search != null && !search.isBlank()
                        ? "WHERE p.patient_name LIKE ? OR p.contact_number LIKE ? OR p.patient_id = ? "
                        : "")
                + "ORDER BY p.patient_name";

        List<PatientSummary> summaries = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (search != null && !search.isBlank()) {
                String trimmed = search.trim();
                String like = "%" + trimmed + "%";
                ps.setString(1, like);
                ps.setString(2, like);
                // A non-numeric search should never match patient_id = ? (which would
                // otherwise throw); -1 is a safe sentinel no real patient_id can equal.
                int idCandidate = -1;
                if (trimmed.matches("\\d+")) {
                    try {
                        idCandidate = Integer.parseInt(trimmed);
                    } catch (NumberFormatException ignored) {
                        // trimmed matched \d+ so this can't actually happen; keep -1.
                    }
                }
                ps.setInt(3, idCandidate);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PatientSummary summary = new PatientSummary();
                    summary.setPatientId(rs.getInt("patient_id"));
                    summary.setPatientName(rs.getString("patient_name"));
                    summary.setContactNumber(rs.getString("contact_number"));
                    summary.setAssignedDentistName(rs.getString("last_visit_dentist"));
                    Date lastVisit = rs.getDate("last_visit_date");
                    summary.setLastVisitDate(lastVisit != null ? lastVisit.toLocalDate() : null);
                    Date nextDate = rs.getDate("next_appointment_date");
                    summary.setNextAppointmentDate(nextDate != null ? nextDate.toLocalDate() : null);
                    Time nextTime = rs.getTime("next_appointment_time");
                    summary.setNextAppointmentTime(nextTime != null ? nextTime.toLocalTime() : null);
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    summary.setRegisteredDate(createdAt != null ? createdAt.toLocalDateTime().toLocalDate() : null);
                    summaries.add(summary);
                }
            }
        }
        return summaries;
    }

    public int create(Patient patient) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return create(conn, patient);
        }
    }

    public int create(Connection conn, Patient patient) throws SQLException {
        String sql = "INSERT INTO patients (patient_name, address, contact_number) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, patient.getPatientName());
            ps.setString(2, patient.getAddress());
            ps.setString(3, patient.getContactNumber());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int patientId = keys.getInt(1);
                    patient.setPatientId(patientId);
                    return patientId;
                }
                throw new SQLException("Failed to obtain generated patient_id");
            }
        }
    }

    public void update(int patientId, String patientName, String address, String contactNumber) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            update(conn, patientId, patientName, address, contactNumber);
        }
    }

    public void update(Connection conn, int patientId, String patientName, String address, String contactNumber)
            throws SQLException {
        String sql = "UPDATE patients SET patient_name = ?, address = ?, contact_number = ? WHERE patient_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, patientName);
            ps.setString(2, address);
            ps.setString(3, contactNumber);
            ps.setInt(4, patientId);
            ps.executeUpdate();
        }
    }

    public Optional<Patient> findById(int patientId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findById(conn, patientId);
        }
    }

    public Optional<Patient> findById(Connection conn, int patientId) throws SQLException {
        String sql = "SELECT patient_id, patient_name, address, contact_number, created_at "
                + "FROM patients WHERE patient_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, patientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private Patient mapRow(ResultSet rs) throws SQLException {
        Patient patient = new Patient();
        patient.setPatientId(rs.getInt("patient_id"));
        patient.setPatientName(rs.getString("patient_name"));
        patient.setAddress(rs.getString("address"));
        patient.setContactNumber(rs.getString("contact_number"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        patient.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);
        return patient;
    }
}

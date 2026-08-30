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
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PatientDAO {

    /**
     * Patients list screen: name/contact search plus last-visit and visit
     * count derived from appointments. search may be null/blank for "all".
     */
    public List<PatientSummary> findAllSummaries(String search) throws SQLException {
        String sql = "SELECT p.patient_id, p.patient_name, p.contact_number, "
                + "       MAX(a.appointment_date) AS last_appointment_date, "
                + "       COUNT(a.appointment_id) AS appointment_count "
                + "FROM patients p "
                + "LEFT JOIN appointments a ON a.patient_id = p.patient_id "
                + (search != null && !search.isBlank()
                        ? "WHERE p.patient_name LIKE ? OR p.contact_number LIKE ? "
                        : "")
                + "GROUP BY p.patient_id, p.patient_name, p.contact_number "
                + "ORDER BY p.patient_name";

        List<PatientSummary> summaries = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim() + "%";
                ps.setString(1, like);
                ps.setString(2, like);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PatientSummary summary = new PatientSummary();
                    summary.setPatientId(rs.getInt("patient_id"));
                    summary.setPatientName(rs.getString("patient_name"));
                    summary.setContactNumber(rs.getString("contact_number"));
                    Date lastDate = rs.getDate("last_appointment_date");
                    summary.setLastAppointmentDate(lastDate != null ? lastDate.toLocalDate() : null);
                    summary.setAppointmentCount(rs.getInt("appointment_count"));
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

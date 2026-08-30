package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.Patient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;

public class PatientDAO {

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

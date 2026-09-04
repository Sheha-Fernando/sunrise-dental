package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.Treatment;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TreatmentDAO {

    public List<Treatment> findActive() throws SQLException {
        String sql = "SELECT treatment_id, treatment_name, cost, is_active "
                + "FROM treatments WHERE is_active = TRUE ORDER BY treatment_name";
        List<Treatment> treatments = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                treatments.add(mapRow(rs));
            }
        }
        return treatments;
    }

    /** Every treatment regardless of status - the admin management view. */
    public List<Treatment> findAll() throws SQLException {
        String sql = "SELECT treatment_id, treatment_name, cost, is_active "
                + "FROM treatments ORDER BY treatment_name";
        List<Treatment> treatments = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                treatments.add(mapRow(rs));
            }
        }
        return treatments;
    }

    public Optional<Treatment> findById(int treatmentId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findById(conn, treatmentId);
        }
    }

    public Optional<Treatment> findById(Connection conn, int treatmentId) throws SQLException {
        String sql = "SELECT treatment_id, treatment_name, cost, is_active "
                + "FROM treatments WHERE treatment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, treatmentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Case-insensitive name collision check (the "cleaning" vs "Cleaning" rule). */
    public boolean existsByName(Connection conn, String treatmentName) throws SQLException {
        String sql = "SELECT 1 FROM treatments WHERE LOWER(treatment_name) = LOWER(?) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, treatmentName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean existsByNameExcludingId(Connection conn, String treatmentName, int excludeId) throws SQLException {
        String sql = "SELECT 1 FROM treatments WHERE LOWER(treatment_name) = LOWER(?) AND treatment_id <> ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, treatmentName);
            ps.setInt(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** New treatments always start active - callers never pass a status in. */
    public int create(Connection conn, String treatmentName, BigDecimal cost) throws SQLException {
        String sql = "INSERT INTO treatments (treatment_name, cost, is_active) VALUES (?, ?, TRUE)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, treatmentName);
            ps.setBigDecimal(2, cost);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new SQLException("Failed to obtain generated treatment_id");
            }
        }
    }

    public void update(Connection conn, int treatmentId, String treatmentName, BigDecimal cost, boolean active)
            throws SQLException {
        String sql = "UPDATE treatments SET treatment_name = ?, cost = ?, is_active = ? WHERE treatment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, treatmentName);
            ps.setBigDecimal(2, cost);
            ps.setBoolean(3, active);
            ps.setInt(4, treatmentId);
            ps.executeUpdate();
        }
    }

    public Optional<Treatment> findByName(String treatmentName) throws SQLException {
        String sql = "SELECT treatment_id, treatment_name, cost, is_active "
                + "FROM treatments WHERE treatment_name = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, treatmentName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private Treatment mapRow(ResultSet rs) throws SQLException {
        Treatment treatment = new Treatment();
        treatment.setTreatmentId(rs.getInt("treatment_id"));
        treatment.setTreatmentName(rs.getString("treatment_name"));
        treatment.setCost(rs.getBigDecimal("cost"));
        treatment.setActive(rs.getBoolean("is_active"));
        return treatment;
    }
}

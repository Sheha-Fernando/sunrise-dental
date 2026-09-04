package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.Treatment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

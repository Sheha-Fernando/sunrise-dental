package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.Dentist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DentistDAO {

    public List<Dentist> findActive() throws SQLException {
        String sql = "SELECT dentist_id, dentist_name, specialty, contact_number, email, is_active "
                + "FROM dentists WHERE is_active = TRUE ORDER BY dentist_name";
        List<Dentist> dentists = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                dentists.add(mapRow(rs));
            }
        }
        return dentists;
    }

    public Optional<Dentist> findById(int dentistId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findById(conn, dentistId);
        }
    }

    public Optional<Dentist> findById(Connection conn, int dentistId) throws SQLException {
        String sql = "SELECT dentist_id, dentist_name, specialty, contact_number, email, is_active "
                + "FROM dentists WHERE dentist_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, dentistId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private Dentist mapRow(ResultSet rs) throws SQLException {
        Dentist dentist = new Dentist();
        dentist.setDentistId(rs.getInt("dentist_id"));
        dentist.setDentistName(rs.getString("dentist_name"));
        dentist.setSpecialty(rs.getString("specialty"));
        dentist.setContactNumber(rs.getString("contact_number"));
        dentist.setEmail(rs.getString("email"));
        dentist.setActive(rs.getBoolean("is_active"));
        return dentist;
    }
}

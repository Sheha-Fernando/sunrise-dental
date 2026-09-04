package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.Dentist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DentistDAO {

    private static final String SELECT_COLUMNS =
            "dentist_id, dentist_name, specialty, contact_number, email, working_days, is_active ";

    public List<Dentist> findActive() throws SQLException {
        String sql = "SELECT " + SELECT_COLUMNS + "FROM dentists WHERE is_active = TRUE ORDER BY dentist_name";
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

    /** Every dentist regardless of status - the Staff page's Doctor Staff table. */
    public List<Dentist> findAll() throws SQLException {
        String sql = "SELECT " + SELECT_COLUMNS + "FROM dentists ORDER BY dentist_name";
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
        String sql = "SELECT " + SELECT_COLUMNS + "FROM dentists WHERE dentist_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, dentistId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Creates the dentists row backing a new DENTIST staff account - a
     * dentist account always represents a brand-new dentist, never an
     * existing one, so this is always called alongside UserDAO.create.
     */
    public int create(Connection conn, String dentistName, String specialty, String contactNumber,
                       String email, String workingDays) throws SQLException {
        String sql = "INSERT INTO dentists (dentist_name, specialty, contact_number, email, working_days, is_active) "
                + "VALUES (?, ?, ?, ?, ?, TRUE)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, dentistName);
            ps.setString(2, specialty);
            ps.setString(3, contactNumber);
            ps.setString(4, email);
            ps.setString(5, workingDays);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new SQLException("Failed to obtain generated dentist_id");
            }
        }
    }

    public void updateActiveStatus(int dentistId, boolean active) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            updateActiveStatus(conn, dentistId, active);
        }
    }

    public void updateActiveStatus(Connection conn, int dentistId, boolean active) throws SQLException {
        String sql = "UPDATE dentists SET is_active = ? WHERE dentist_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, active);
            ps.setInt(2, dentistId);
            ps.executeUpdate();
        }
    }

    public void update(Connection conn, int dentistId, String dentistName, String specialty,
                        String contactNumber, String email, String workingDays) throws SQLException {
        String sql = "UPDATE dentists SET dentist_name = ?, specialty = ?, contact_number = ?, "
                + "email = ?, working_days = ? WHERE dentist_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dentistName);
            ps.setString(2, specialty);
            ps.setString(3, contactNumber);
            ps.setString(4, email);
            ps.setString(5, workingDays);
            ps.setInt(6, dentistId);
            ps.executeUpdate();
        }
    }

    private Dentist mapRow(ResultSet rs) throws SQLException {
        Dentist dentist = new Dentist();
        dentist.setDentistId(rs.getInt("dentist_id"));
        dentist.setDentistName(rs.getString("dentist_name"));
        dentist.setSpecialty(rs.getString("specialty"));
        dentist.setContactNumber(rs.getString("contact_number"));
        dentist.setEmail(rs.getString("email"));
        dentist.setWorkingDays(rs.getString("working_days"));
        dentist.setActive(rs.getBoolean("is_active"));
        return dentist;
    }
}

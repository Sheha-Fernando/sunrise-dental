package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UserDAO {

    private static final String SELECT_COLUMNS =
            "user_id, username, password_hash, full_name, contact_number, email, "
          + "role, dentist_id, assigned_dentist_id, is_active, created_at ";

    public Optional<User> findByUsername(String username) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findByUsername(conn, username);
        }
    }

    public Optional<User> findByUsername(Connection conn, String username) throws SQLException {
        String sql = "SELECT " + SELECT_COLUMNS + "FROM users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public Optional<User> findById(int userId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findById(conn, userId);
        }
    }

    public Optional<User> findById(Connection conn, int userId) throws SQLException {
        String sql = "SELECT " + SELECT_COLUMNS + "FROM users WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public List<User> findAll() throws SQLException {
        String sql = "SELECT " + SELECT_COLUMNS + "FROM users ORDER BY user_id";
        List<User> users = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(mapRow(rs));
            }
        }
        return users;
    }

    /** Every active user, regardless of role - the notification recipient-resolution source list. */
    public List<User> findActive() throws SQLException {
        String sql = "SELECT " + SELECT_COLUMNS + "FROM users WHERE is_active = TRUE ORDER BY full_name";
        List<User> users = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(mapRow(rs));
            }
        }
        return users;
    }

    public boolean existsByUsername(String username) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return existsByUsername(conn, username);
        }
    }

    public boolean existsByUsername(Connection conn, String username) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE username = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean existsByUsernameExcludingId(Connection conn, String username, int excludeUserId) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE username = ? AND user_id <> ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setInt(2, excludeUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int create(String username, String passwordHash, String fullName, String contactNumber, String email,
                       UserRole role, Integer dentistId, Integer assignedDentistId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return create(conn, username, passwordHash, fullName, contactNumber, email, role, dentistId, assignedDentistId);
        }
    }

    public int create(Connection conn, String username, String passwordHash, String fullName,
                       String contactNumber, String email,
                       UserRole role, Integer dentistId, Integer assignedDentistId) throws SQLException {
        String sql = "INSERT INTO users (username, password_hash, full_name, contact_number, email, "
                + "role, dentist_id, assigned_dentist_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, fullName);
            ps.setString(4, contactNumber);
            ps.setString(5, email);
            ps.setString(6, role.name());
            setNullableInt(ps, 7, dentistId);
            setNullableInt(ps, 8, assignedDentistId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new SQLException("Failed to obtain generated user_id");
            }
        }
    }

    public void updateRole(int userId, UserRole role, Integer dentistId, Integer assignedDentistId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            updateRole(conn, userId, role, dentistId, assignedDentistId);
        }
    }

    public void updateRole(Connection conn, int userId, UserRole role, Integer dentistId, Integer assignedDentistId)
            throws SQLException {
        String sql = "UPDATE users SET role = ?, dentist_id = ?, assigned_dentist_id = ? WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role.name());
            setNullableInt(ps, 2, dentistId);
            setNullableInt(ps, 3, assignedDentistId);
            ps.setInt(4, userId);
            ps.executeUpdate();
        }
    }

    /** Profile edit: full name/username/contact/email always; password only when a new one was actually provided. */
    public void updateProfile(Connection conn, int userId, String fullName, String username,
                               String passwordHash, String contactNumber, String email) throws SQLException {
        String sql = passwordHash != null
                ? "UPDATE users SET full_name = ?, username = ?, password_hash = ?, contact_number = ?, email = ? WHERE user_id = ?"
                : "UPDATE users SET full_name = ?, username = ?, contact_number = ?, email = ? WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, fullName);
            ps.setString(i++, username);
            if (passwordHash != null) {
                ps.setString(i++, passwordHash);
            }
            ps.setString(i++, contactNumber);
            ps.setString(i++, email);
            ps.setInt(i, userId);
            ps.executeUpdate();
        }
    }

    public void updateAssignedDentist(Connection conn, int userId, Integer assignedDentistId) throws SQLException {
        String sql = "UPDATE users SET assigned_dentist_id = ? WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableInt(ps, 1, assignedDentistId);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    private void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value != null) {
            ps.setInt(index, value);
        } else {
            ps.setNull(index, Types.INTEGER);
        }
    }

    public void updateActive(int userId, boolean active) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            updateActive(conn, userId, active);
        }
    }

    public void updateActive(Connection conn, int userId, boolean active) throws SQLException {
        String sql = "UPDATE users SET is_active = ? WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, active);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    public int countActiveByRole(UserRole role) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return countActiveByRole(conn, role);
        }
    }

    public int countActiveByRole(Connection conn, UserRole role) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE role = ? AND is_active = TRUE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        User user = new User();
        user.setUserId(rs.getInt("user_id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setFullName(rs.getString("full_name"));
        user.setContactNumber(rs.getString("contact_number"));
        user.setEmail(rs.getString("email"));
        user.setRole(UserRole.valueOf(rs.getString("role")));
        int dentistId = rs.getInt("dentist_id");
        user.setDentistId(rs.wasNull() ? null : dentistId);
        int assignedDentistId = rs.getInt("assigned_dentist_id");
        user.setAssignedDentistId(rs.wasNull() ? null : assignedDentistId);
        user.setActive(rs.getBoolean("is_active"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        user.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);
        return user;
    }
}

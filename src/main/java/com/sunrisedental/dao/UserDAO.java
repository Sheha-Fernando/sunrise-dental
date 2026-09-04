package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

public class UserDAO {

    public Optional<User> findByUsername(String username) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findByUsername(conn, username);
        }
    }

    public Optional<User> findByUsername(Connection conn, String username) throws SQLException {
        String sql = "SELECT user_id, username, password_hash, full_name, created_at "
                + "FROM users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        User user = new User();
        user.setUserId(rs.getInt("user_id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setFullName(rs.getString("full_name"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        user.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);
        return user;
    }
}

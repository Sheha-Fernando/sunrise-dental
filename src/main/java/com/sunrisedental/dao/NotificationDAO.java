package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.Notification;
import com.sunrisedental.model.NotificationType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * reference_type/reference_id is a loose, polymorphic pointer (currently only
 * "APPOINTMENT" is used) rather than a foreign key, since a notification can
 * point at different tables depending on its type - the LEFT JOIN below only
 * resolves appointment context when reference_type = 'APPOINTMENT', and
 * simply leaves those columns null otherwise.
 */
public class NotificationDAO {

    private static final String LIST_SELECT =
            "SELECT n.notification_id, n.recipient_user_id, n.sender_user_id, n.notification_type, "
          + "       n.title, n.message, n.reference_type, n.reference_id, n.is_read, n.created_at, "
          + "       su.full_name AS sender_name, "
          + "       a.appointment_number, a.appointment_time, p.patient_name, d.dentist_name "
          + "FROM notifications n "
          + "LEFT JOIN users su ON n.sender_user_id = su.user_id "
          + "LEFT JOIN appointments a ON n.reference_type = 'APPOINTMENT' AND n.reference_id = a.appointment_id "
          + "LEFT JOIN patients p ON a.patient_id = p.patient_id "
          + "LEFT JOIN dentists d ON a.dentist_id = d.dentist_id ";

    public int create(int recipientUserId, Integer senderUserId, NotificationType type, String title,
                       String message, String referenceType, Integer referenceId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return create(conn, recipientUserId, senderUserId, type, title, message, referenceType, referenceId);
        }
    }

    public int create(Connection conn, int recipientUserId, Integer senderUserId, NotificationType type,
                       String title, String message, String referenceType, Integer referenceId) throws SQLException {
        String sql = "INSERT INTO notifications "
                + "(recipient_user_id, sender_user_id, notification_type, title, message, reference_type, reference_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, recipientUserId);
            if (senderUserId != null) {
                ps.setInt(2, senderUserId);
            } else {
                ps.setNull(2, Types.INTEGER);
            }
            ps.setString(3, type.name());
            ps.setString(4, title);
            ps.setString(5, message);
            if (referenceType != null) {
                ps.setString(6, referenceType);
            } else {
                ps.setNull(6, Types.VARCHAR);
            }
            if (referenceId != null) {
                ps.setInt(7, referenceId);
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new SQLException("Failed to obtain generated notification_id");
            }
        }
    }

    /** Most recent notifications for a user, optionally unread-only. */
    public List<Notification> findForUser(int userId, int limit, boolean unreadOnly) throws SQLException {
        String sql = LIST_SELECT + "WHERE n.recipient_user_id = ? "
                + (unreadOnly ? "AND n.is_read = FALSE " : "")
                + "ORDER BY n.created_at DESC LIMIT ?";
        List<Notification> notifications = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    notifications.add(mapRow(rs));
                }
            }
        }
        return notifications;
    }

    public int countUnread(int userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND is_read = FALSE";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Marks one notification read - scoped to recipient_user_id so a user
     * can never mark (or even affect) another user's notification, no
     * matter what id is requested. Returns whether a row actually matched.
     */
    public boolean markRead(int notificationId, int userId) throws SQLException {
        String sql = "UPDATE notifications SET is_read = TRUE WHERE notification_id = ? AND recipient_user_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, notificationId);
            ps.setInt(2, userId);
            return ps.executeUpdate() > 0;
        }
    }

    public void markAllRead(int userId) throws SQLException {
        String sql = "UPDATE notifications SET is_read = TRUE WHERE recipient_user_id = ? AND is_read = FALSE";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    private Notification mapRow(ResultSet rs) throws SQLException {
        Notification n = new Notification();
        n.setNotificationId(rs.getInt("notification_id"));
        n.setRecipientUserId(rs.getInt("recipient_user_id"));
        int senderId = rs.getInt("sender_user_id");
        n.setSenderUserId(rs.wasNull() ? null : senderId);
        n.setType(NotificationType.valueOf(rs.getString("notification_type")));
        n.setTitle(rs.getString("title"));
        n.setMessage(rs.getString("message"));
        n.setReferenceType(rs.getString("reference_type"));
        int referenceId = rs.getInt("reference_id");
        n.setReferenceId(rs.wasNull() ? null : referenceId);
        n.setRead(rs.getBoolean("is_read"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        n.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);
        n.setSenderName(rs.getString("sender_name"));
        n.setAppointmentNumber(rs.getString("appointment_number"));
        n.setPatientName(rs.getString("patient_name"));
        n.setDentistName(rs.getString("dentist_name"));
        Time appointmentTime = rs.getTime("appointment_time");
        n.setAppointmentTime(appointmentTime != null ? appointmentTime.toLocalTime() : null);
        return n;
    }
}

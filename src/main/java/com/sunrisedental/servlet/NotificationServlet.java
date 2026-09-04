package com.sunrisedental.servlet;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Notification;
import com.sunrisedental.model.NotificationType;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.AppointmentService;
import com.sunrisedental.service.NotificationService;
import com.sunrisedental.util.AuthorizationUtil;
import com.sunrisedental.util.JsonUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GET  /api/notifications?limit=&unreadOnly=  - current user's notifications
 * GET  /api/notifications/unread-count        - current user's unread count
 * GET  /api/notifications/recipients          - who the current user may message
 * PUT  /api/notifications/{id}/read           - mark one notification read
 * PUT  /api/notifications/read-all            - mark all of the current user's notifications read
 * POST /api/notifications/messages            - send a manual staff message
 * The recipient/sender is always resolved from the authenticated session -
 * never from a client-supplied id.
 */
@WebServlet("/api/notifications/*")
public class NotificationServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(NotificationServlet.class.getName());
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final NotificationService notificationService = new NotificationService();
    private final AppointmentService appointmentService = new AppointmentService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            Integer userId = AuthorizationUtil.currentUserId(req);
            if (userId == null) {
                writeError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Your session has expired. Please log in again.");
                return;
            }

            String pathInfo = req.getPathInfo();
            if (pathInfo == null || pathInfo.equals("/")) {
                listNotifications(req, resp, userId);
            } else if (pathInfo.equals("/unread-count")) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("count", notificationService.unreadCount(userId));
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(JsonUtil.write(body));
            } else if (pathInfo.equals("/recipients")) {
                listRecipients(req, resp, userId);
            } else {
                writeError(resp, HttpServletResponse.SC_NOT_FOUND, "Not found.");
            }
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error handling notifications request", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to retrieve notifications right now.");
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            Integer userId = AuthorizationUtil.currentUserId(req);
            if (userId == null) {
                writeError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Your session has expired. Please log in again.");
                return;
            }

            String pathInfo = req.getPathInfo();
            if (pathInfo != null && pathInfo.equals("/read-all")) {
                notificationService.markAllRead(userId);
                writeSuccess(resp, "All notifications marked as read.");
                return;
            }

            String[] segments = (pathInfo == null) ? new String[0] : pathInfo.split("/");
            if (segments.length == 3 && "read".equals(segments[2])) {
                int notificationId;
                try {
                    notificationId = Integer.parseInt(segments[1]);
                } catch (NumberFormatException e) {
                    writeError(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid notification id.");
                    return;
                }
                notificationService.markRead(notificationId, userId);
                writeSuccess(resp, "Notification marked as read.");
                return;
            }

            writeError(resp, HttpServletResponse.SC_NOT_FOUND, "Not found.");
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error updating notification", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to update this notification right now.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json");
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || !pathInfo.equals("/messages")) {
            writeError(resp, HttpServletResponse.SC_NOT_FOUND, "Not found.");
            return;
        }
        try {
            Integer userId = AuthorizationUtil.currentUserId(req);
            UserRole role = AuthorizationUtil.currentRole(req);
            if (userId == null || role == null) {
                writeError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Your session has expired. Please log in again.");
                return;
            }

            NotificationType type = parseType(req.getParameter("type"));
            String messageText = req.getParameter("message");

            Integer appointmentId = null;
            String appointmentNumber = req.getParameter("appointmentNumber");
            if (appointmentNumber != null && !appointmentNumber.isBlank()) {
                appointmentId = appointmentService.findByAppointmentNumber(appointmentNumber).getAppointmentId();
            }

            List<Integer> recipientIds = new ArrayList<>();
            String recipientsParam = req.getParameter("recipientUserIds");
            if (recipientsParam != null && !recipientsParam.isBlank()) {
                for (String part : recipientsParam.split(",")) {
                    try {
                        recipientIds.add(Integer.parseInt(part.trim()));
                    } catch (NumberFormatException ignored) {
                        // skip malformed values rather than failing the whole request
                    }
                }
            }

            notificationService.sendMessage(userId, role,
                    AuthorizationUtil.currentDentistId(req), AuthorizationUtil.currentAssignedDentistId(req),
                    type, messageText, appointmentId, recipientIds);

            writeSuccess(resp, "Message sent successfully.");
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error sending message", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to send this message right now.");
        }
    }

    private void listNotifications(HttpServletRequest req, HttpServletResponse resp, int userId) throws IOException {
        int limit = parseLimit(req.getParameter("limit"));
        boolean unreadOnly = "true".equalsIgnoreCase(req.getParameter("unreadOnly"));
        List<Notification> notifications = notificationService.listForUser(userId, limit, unreadOnly);
        List<Map<String, Object>> body = new ArrayList<>();
        for (Notification n : notifications) {
            body.add(toJson(n));
        }
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(JsonUtil.write(body));
    }

    private void listRecipients(HttpServletRequest req, HttpServletResponse resp, int userId) throws IOException {
        UserRole role = AuthorizationUtil.currentRole(req);
        List<User> recipients = notificationService.eligibleRecipients(role,
                AuthorizationUtil.currentDentistId(req), AuthorizationUtil.currentAssignedDentistId(req), userId);
        List<Map<String, Object>> body = new ArrayList<>();
        for (User u : recipients) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", u.getUserId());
            item.put("fullName", u.getFullName());
            item.put("role", u.getRole().name());
            body.add(item);
        }
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(JsonUtil.write(body));
    }

    private int parseLimit(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(value), MAX_LIMIT));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private NotificationType parseType(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Message type is required.");
        }
        try {
            return NotificationType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid message type.");
        }
    }

    private Map<String, Object> toJson(Notification n) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("notificationId", n.getNotificationId());
        json.put("type", n.getType().name());
        json.put("category", n.getSenderUserId() == null ? "SYSTEM" : "MESSAGE");
        json.put("title", n.getTitle());
        json.put("message", n.getMessage());
        json.put("referenceType", n.getReferenceType());
        json.put("referenceId", n.getReferenceId());
        json.put("isRead", n.isRead());
        json.put("createdAt", n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);
        json.put("senderName", n.getSenderName());
        json.put("appointmentNumber", n.getAppointmentNumber());
        json.put("patientName", n.getPatientName());
        json.put("dentistName", n.getDentistName());
        json.put("appointmentTime", n.getAppointmentTime() != null ? n.getAppointmentTime().toString() : null);
        return json;
    }

    private void writeSuccess(HttpServletResponse resp, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("message", message);
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(JsonUtil.write(body));
    }

    private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        resp.getWriter().write(JsonUtil.write(body));
    }
}

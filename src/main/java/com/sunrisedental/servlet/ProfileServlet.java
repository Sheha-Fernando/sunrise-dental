package com.sunrisedental.servlet;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.ProfileService;
import com.sunrisedental.util.AuthorizationUtil;
import com.sunrisedental.util.JsonUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * "My Profile" self-service:
 *   GET /api/profile              - the caller's own profile (identity from
 *                                    session only - a userId in the URL is
 *                                    never accepted or trusted)
 *   PUT /api/profile              - edit own fullName/username/contactNumber/
 *                                    email (role and dentist assignment are
 *                                    never editable through this endpoint)
 *   PUT /api/profile/password     - change own password (currentPassword +
 *                                    newPassword)
 */
@WebServlet("/api/profile/*")
public class ProfileServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(ProfileServlet.class.getName());
    private static final DateTimeFormatter LAST_LOGIN_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH);

    private final ProfileService profileService = new ProfileService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            int userId = requireCurrentUserId(req);
            Map<String, Object> body = buildProfileJson(userId, req);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error loading profile", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to load your profile right now.");
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            int userId = requireCurrentUserId(req);
            String pathInfo = req.getPathInfo();

            if (pathInfo != null && pathInfo.equals("/password")) {
                profileService.changePassword(userId, req.getParameter("currentPassword"), req.getParameter("newPassword"));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "success");
                body.put("message", "Password changed successfully.");
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(JsonUtil.write(body));
                return;
            }

            profileService.updateOwnProfile(userId, req.getParameter("fullName"), req.getParameter("username"),
                    req.getParameter("contactNumber"), req.getParameter("email"));

            Map<String, Object> body = buildProfileJson(userId, req);
            body.put("message", "Profile updated successfully.");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error updating profile", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to update your profile right now.");
        }
    }

    private int requireCurrentUserId(HttpServletRequest req) {
        Integer userId = AuthorizationUtil.currentUserId(req);
        if (userId == null) {
            throw new BusinessException("Not authenticated.");
        }
        return userId;
    }

    private Map<String, Object> buildProfileJson(int userId, HttpServletRequest req) {
        User user = profileService.getOwnProfile(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("userId", user.getUserId());
        body.put("username", user.getUsername());
        body.put("fullName", user.getFullName());
        body.put("contactNumber", user.getContactNumber());
        body.put("email", user.getEmail());
        body.put("role", user.getRole().name());
        body.put("lastLogin", lastLoginDisplay(req));

        if (user.getRole() == UserRole.DENTIST && user.getDentistId() != null) {
            Dentist dentist = profileService.getDentist(user.getDentistId()).orElse(null);
            if (dentist != null) {
                body.put("specialty", dentist.getSpecialty());
                body.put("workingDays", dentist.getWorkingDays());
            }
        } else if (user.getRole() == UserRole.CLINICAL_ASSISTANT && user.getAssignedDentistId() != null) {
            Dentist dentist = profileService.getDentist(user.getAssignedDentistId()).orElse(null);
            if (dentist != null) {
                body.put("assignedDentistName", dentist.getDentistName());
            }
        }
        return body;
    }

    /**
     * There is no persisted "last login" column - a fresh session is always
     * created on login (AuthServlet invalidates any prior one), so the
     * current session's own creation time already is this login's timestamp,
     * with no schema change needed.
     */
    private String lastLoginDisplay(HttpServletRequest req) {
        var session = req.getSession(false);
        if (session == null) {
            return "Not available";
        }
        Instant createdAt = Instant.ofEpochMilli(session.getCreationTime());
        return LAST_LOGIN_FORMAT.format(createdAt.atZone(ZoneId.systemDefault()));
    }

    private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        resp.getWriter().write(JsonUtil.write(body));
    }
}

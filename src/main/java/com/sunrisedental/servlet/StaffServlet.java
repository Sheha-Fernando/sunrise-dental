package com.sunrisedental.servlet;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.StaffService;
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
 * Admin-only staff account management:
 *   GET  /api/staff              - list all staff accounts
 *   POST /api/staff              - create a staff account
 *   PUT  /api/staff/{id}         - update role/dentistId and/or active status
 *                                  (?role=..&dentistId=..&active=.. as query params -
 *                                  PUT bodies aren't auto-parsed as form params by
 *                                  the Servlet API, so this test API takes them on
 *                                  the query string, same as /api/bills does today)
 */
@WebServlet("/api/staff/*")
public class StaffServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(StaffServlet.class.getName());
    private final StaffService staffService = new StaffService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN);

            List<User> staff = staffService.listStaff();
            List<Map<String, Object>> body = new ArrayList<>();
            for (User user : staff) {
                body.add(toJson(user));
            }
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error listing staff", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to retrieve staff right now.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN);

            String fullName = req.getParameter("fullName");
            String username = req.getParameter("username");
            String password = req.getParameter("password");
            UserRole role = UserRole.fromString(req.getParameter("role"));
            String dentistIdParam = req.getParameter("dentistId");
            Integer dentistId = (dentistIdParam == null || dentistIdParam.isBlank())
                    ? null : Integer.valueOf(dentistIdParam);

            User created = staffService.createStaff(fullName, username, password, role, dentistId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", "Staff account created successfully.");
            body.put("staff", toJson(created));
            resp.setStatus(HttpServletResponse.SC_CREATED);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (NumberFormatException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid request data.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error creating staff account", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to create staff account right now.");
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN);

            String pathInfo = req.getPathInfo();
            if (pathInfo == null || pathInfo.length() <= 1) {
                throw new BusinessException("Staff member ID is required.");
            }
            int userId;
            try {
                userId = Integer.parseInt(pathInfo.substring(1));
            } catch (NumberFormatException e) {
                throw new BusinessException("Invalid staff member ID.");
            }

            String rolePart = req.getParameter("role");
            String activePart = req.getParameter("active");
            if (rolePart == null && activePart == null) {
                throw new BusinessException("Nothing to update.");
            }

            User updated = null;
            if (rolePart != null) {
                UserRole role = UserRole.fromString(rolePart);
                String dentistIdParam = req.getParameter("dentistId");
                Integer dentistId = (dentistIdParam == null || dentistIdParam.isBlank())
                        ? null : Integer.valueOf(dentistIdParam);
                updated = staffService.updateRole(userId, role, dentistId);
            }
            if (activePart != null) {
                updated = staffService.updateActiveStatus(userId, Boolean.parseBoolean(activePart));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", "Staff account updated successfully.");
            body.put("staff", toJson(updated));
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (NumberFormatException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid request data.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error updating staff account", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to update staff account right now.");
        }
    }

    private Map<String, Object> toJson(User user) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("userId", user.getUserId());
        json.put("username", user.getUsername());
        json.put("fullName", user.getFullName());
        json.put("role", user.getRole().name());
        json.put("dentistId", user.getDentistId());
        json.put("active", user.isActive());
        return json;
    }

    private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        resp.getWriter().write(JsonUtil.write(body));
    }
}

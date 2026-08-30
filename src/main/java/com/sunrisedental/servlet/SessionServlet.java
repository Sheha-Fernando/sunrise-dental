package com.sunrisedental.servlet;

import com.sunrisedental.model.UserRole;
import com.sunrisedental.util.JsonUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets the frontend check "am I still logged in?" on page load
 * (e.g. before showing a protected page) without duplicating auth logic.
 */
@WebServlet("/api/auth/session")
public class SessionServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.getWriter().write(JsonUtil.write(Map.of("status", "error", "message", "Not authenticated.")));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("userId", session.getAttribute("userId"));
        body.put("username", session.getAttribute("username"));
        body.put("fullName", session.getAttribute("fullName"));
        UserRole role = (UserRole) session.getAttribute("role");
        body.put("role", role != null ? role.name() : null);
        body.put("dentistId", session.getAttribute("dentistId"));
        body.put("assignedDentistId", session.getAttribute("assignedDentistId"));
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(JsonUtil.write(body));
    }
}

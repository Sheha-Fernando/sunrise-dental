package com.sunrisedental.servlet;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.User;
import com.sunrisedental.service.AuthService;
import com.sunrisedental.util.JsonUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@WebServlet("/api/auth/login")
public class AuthServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(AuthServlet.class.getName());
    private final AuthService authService = new AuthService();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        String username = req.getParameter("username");
        String password = req.getParameter("password");

        try {
            User user = authService.authenticate(username, password);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", "Login successful.");
            body.put("userId", user.getUserId());
            body.put("username", user.getUsername());
            body.put("fullName", user.getFullName());
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error during login", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to process login right now.");
        }
    }

    private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        resp.getWriter().write(JsonUtil.write(body));
    }
}

package com.sunrisedental.servlet;

import com.sunrisedental.util.JsonUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Map;

/**
 * Enforces that appointment/billing/reference-data endpoints are only
 * reachable by a logged-in session - the login/logout/session-check and
 * plain DB-health endpoints stay open.
 */
@WebFilter(urlPatterns = {"/api/appointments/*", "/api/bills", "/api/dentists", "/api/treatments", "/api/staff/*", "/api/patients/*"})
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            resp.setContentType("application/json");
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.getWriter().write(JsonUtil.write(
                    Map.of("status", "error", "message", "Your session has expired. Please log in again.")));
            return;
        }
        chain.doFilter(request, response);
    }
}

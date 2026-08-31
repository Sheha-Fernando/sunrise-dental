package com.sunrisedental.servlet;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Treatment;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.TreatmentService;
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
 * Treatment catalog:
 *   GET  /api/treatments            - active treatments only (New Appointment dropdown, general use)
 *   GET  /api/treatments?all=true   - every treatment regardless of status (admin management table)
 *   POST /api/treatments            - create a treatment (ADMIN only)
 *   PUT  /api/treatments/{id}       - update name/cost/status (ADMIN only)
 */
@WebServlet("/api/treatments/*")
public class TreatmentServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(TreatmentServlet.class.getName());
    private final TreatmentService treatmentService = new TreatmentService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            boolean includeInactive = "true".equalsIgnoreCase(req.getParameter("all"));
            List<Treatment> treatments = includeInactive ? treatmentService.listAll() : treatmentService.listActive();
            List<Map<String, Object>> body = new ArrayList<>();
            for (Treatment t : treatments) {
                body.add(toJson(t));
            }
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (BusinessException e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write(JsonUtil.write(Map.of("status", "error", "message", e.getMessage())));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error listing treatments", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write(JsonUtil.write(Map.of("status", "error", "message", "Unable to retrieve treatments.")));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN);

            Treatment treatment = treatmentService.create(req.getParameter("treatmentName"), req.getParameter("cost"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", "Treatment added successfully.");
            body.put("treatment", toJson(treatment));
            resp.setStatus(HttpServletResponse.SC_CREATED);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, "You don't have permission to modify treatments.");
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error creating treatment", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to create treatment right now.");
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
                throw new BusinessException("Treatment ID is required.");
            }
            int treatmentId;
            try {
                treatmentId = Integer.parseInt(pathInfo.substring(1));
            } catch (NumberFormatException e) {
                throw new BusinessException("Invalid treatment ID.");
            }

            boolean active = !"false".equalsIgnoreCase(req.getParameter("isActive"));
            Treatment updated = treatmentService.update(treatmentId,
                    req.getParameter("treatmentName"), req.getParameter("cost"), active);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", "Treatment updated successfully.");
            body.put("treatment", toJson(updated));
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, "You don't have permission to modify treatments.");
        } catch (BusinessException e) {
            int code = "Treatment not found.".equals(e.getMessage())
                    ? HttpServletResponse.SC_NOT_FOUND : HttpServletResponse.SC_BAD_REQUEST;
            writeError(resp, code, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error updating treatment", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to update treatment right now.");
        }
    }

    private Map<String, Object> toJson(Treatment t) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("treatmentId", t.getTreatmentId());
        item.put("treatmentName", t.getTreatmentName());
        item.put("cost", t.getCost());
        item.put("isActive", t.isActive());
        return item;
    }

    private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        resp.getWriter().write(JsonUtil.write(body));
    }
}

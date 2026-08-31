package com.sunrisedental.servlet;

import com.sunrisedental.dao.DentistDAO;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.service.AppointmentService;
import com.sunrisedental.util.JsonUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GET /api/dentists            - active dentists only (appointment dropdown, general use)
 * GET /api/dentists?all=true   - every dentist regardless of status (Staff page's Doctor Staff table)
 */
@WebServlet("/api/dentists")
public class DentistServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(DentistServlet.class.getName());
    private final AppointmentService appointmentService = new AppointmentService();
    private final DentistDAO dentistDAO = new DentistDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            boolean includeInactive = "true".equalsIgnoreCase(req.getParameter("all"));
            List<Dentist> dentists = includeInactive ? findAllDentists() : appointmentService.listActiveDentists();
            List<Map<String, Object>> body = new ArrayList<>();
            for (Dentist d : dentists) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("dentistId", d.getDentistId());
                item.put("dentistName", d.getDentistName());
                item.put("specialty", d.getSpecialty());
                item.put("contactNumber", d.getContactNumber());
                item.put("email", d.getEmail());
                item.put("workingDays", d.getWorkingDays());
                item.put("isActive", d.isActive());
                body.add(item);
            }
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (BusinessException e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write(JsonUtil.write(Map.of("status", "error", "message", e.getMessage())));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error listing dentists", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write(JsonUtil.write(Map.of("status", "error", "message", "Unable to retrieve dentists.")));
        }
    }

    private List<Dentist> findAllDentists() {
        try {
            return dentistDAO.findAll();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list all dentists", e);
            throw new BusinessException("Unable to retrieve dentists right now.");
        }
    }
}

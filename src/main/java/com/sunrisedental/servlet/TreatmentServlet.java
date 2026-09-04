package com.sunrisedental.servlet;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Treatment;
import com.sunrisedental.service.AppointmentService;
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

@WebServlet("/api/treatments")
public class TreatmentServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(TreatmentServlet.class.getName());
    private final AppointmentService appointmentService = new AppointmentService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            List<Treatment> treatments = appointmentService.listActiveTreatments();
            List<Map<String, Object>> body = new ArrayList<>();
            for (Treatment t : treatments) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("treatmentId", t.getTreatmentId());
                item.put("treatmentName", t.getTreatmentName());
                item.put("cost", t.getCost());
                body.add(item);
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
}

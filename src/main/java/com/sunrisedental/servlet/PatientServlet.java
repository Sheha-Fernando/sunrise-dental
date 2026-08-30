package com.sunrisedental.servlet;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.Patient;
import com.sunrisedental.model.PatientSummary;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.AppointmentService;
import com.sunrisedental.service.PatientService;
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
 * Patient directory - per the authorization matrix, general patient search
 * is not available to DENTIST accounts (they only ever see patients through
 * their own scoped appointment list, enforced in AppointmentServlet):
 *   GET  /api/patients?q=            - search/list (ADMIN/RECEPTIONIST/BILLING)
 *   GET  /api/patients/{id}          - profile + appointment history
 *   POST /api/patients               - register a new patient (ADMIN/RECEPTIONIST)
 */
@WebServlet("/api/patients/*")
public class PatientServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(PatientServlet.class.getName());
    private final PatientService patientService = new PatientService();
    private final AppointmentService appointmentService = new AppointmentService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN, UserRole.RECEPTIONIST, UserRole.BILLING);

            String pathInfo = req.getPathInfo();
            if (pathInfo == null || pathInfo.length() <= 1) {
                List<PatientSummary> summaries = patientService.list(req.getParameter("q"));
                List<Map<String, Object>> body = new ArrayList<>();
                for (PatientSummary summary : summaries) {
                    body.add(toJson(summary));
                }
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(JsonUtil.write(body));
                return;
            }

            int patientId;
            try {
                patientId = Integer.parseInt(pathInfo.substring(1));
            } catch (NumberFormatException e) {
                throw new BusinessException("Invalid patient ID.");
            }

            Patient patient = patientService.findById(patientId);
            List<Appointment> history = appointmentService.listByPatient(patientId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("patient", toJson(patient));
            List<Map<String, Object>> historyJson = new ArrayList<>();
            for (Appointment appointment : history) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("appointmentNumber", appointment.getAppointmentNumber());
                item.put("dentistName", appointment.getDentist().getDentistName());
                item.put("treatmentType", appointment.getTreatment().getTreatmentName());
                item.put("appointmentDate", appointment.getAppointmentDate().toString());
                item.put("appointmentTime", appointment.getAppointmentTime().toString());
                item.put("status", appointment.getStatus().name());
                historyJson.add(item);
            }
            body.put("appointmentHistory", historyJson);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error retrieving patients", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to retrieve patients right now.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN, UserRole.RECEPTIONIST);

            Patient patient = patientService.register(
                    req.getParameter("patientName"), req.getParameter("address"), req.getParameter("contactNumber"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", "Patient registered successfully.");
            body.put("patient", toJson(patient));
            resp.setStatus(HttpServletResponse.SC_CREATED);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error registering patient", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to register patient right now.");
        }
    }

    private Map<String, Object> toJson(PatientSummary summary) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("patientId", summary.getPatientId());
        json.put("patientName", summary.getPatientName());
        json.put("contactNumber", summary.getContactNumber());
        json.put("assignedDentistName", summary.getAssignedDentistName());
        json.put("lastVisitDate",
                summary.getLastVisitDate() != null ? summary.getLastVisitDate().toString() : null);
        json.put("nextAppointmentDate",
                summary.getNextAppointmentDate() != null ? summary.getNextAppointmentDate().toString() : null);
        json.put("nextAppointmentTime",
                summary.getNextAppointmentTime() != null ? summary.getNextAppointmentTime().toString() : null);
        return json;
    }

    private Map<String, Object> toJson(Patient patient) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("patientId", patient.getPatientId());
        json.put("patientName", patient.getPatientName());
        json.put("address", patient.getAddress());
        json.put("contactNumber", patient.getContactNumber());
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

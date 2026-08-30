package com.sunrisedental.servlet;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.AppointmentService;
import com.sunrisedental.service.NewAppointmentRequest;
import com.sunrisedental.util.AuthorizationUtil;
import com.sunrisedental.util.JsonUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Test API for appointment registration and search:
 *   POST /api/appointments               - create
 *   GET  /api/appointments/{number}       - search by appointment number
 */
@WebServlet("/api/appointments/*")
public class AppointmentServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(AppointmentServlet.class.getName());
    private final AppointmentService appointmentService = new AppointmentService();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        try {
            AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN, UserRole.RECEPTIONIST);

            String appointmentNumber = req.getParameter("appointmentNumber");
            String patientIdParam = req.getParameter("patientId");
            Integer patientId = (patientIdParam == null || patientIdParam.isBlank())
                    ? null : Integer.valueOf(patientIdParam);
            String patientName = req.getParameter("patientName");
            String address = req.getParameter("address");
            String contactNumber = req.getParameter("contactNumber");
            int dentistId = parseInt(req.getParameter("dentistId"), "Dentist is required.");
            int treatmentId = parseInt(req.getParameter("treatmentId"), "Treatment is required.");
            LocalDate date = parseDate(req.getParameter("appointmentDate"));
            LocalTime time = parseTime(req.getParameter("appointmentTime"));
            jakarta.servlet.http.HttpSession session = req.getSession(false);
            Integer createdBy = (session != null) ? (Integer) session.getAttribute("userId") : null;

            NewAppointmentRequest request = new NewAppointmentRequest(
                    appointmentNumber, patientId, patientName, address, contactNumber,
                    dentistId, treatmentId, date, time, createdBy);

            Appointment appointment = appointmentService.createAppointment(request);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", "Appointment registered successfully.");
            body.put("appointment", toJson(appointment));
            resp.setStatus(HttpServletResponse.SC_CREATED);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (NumberFormatException | DateTimeParseException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid request data.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error registering appointment", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to register appointment right now.");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.length() <= 1) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, "Appointment number is required.");
            return;
        }
        String appointmentNumber = pathInfo.substring(1);

        try {
            Appointment appointment = appointmentService.findByAppointmentNumber(appointmentNumber);

            // DENTIST users may only see their own appointments - never
            // another dentist's patient data, even via a direct API call.
            appointmentService.verifyDentistOwnership(appointment,
                    AuthorizationUtil.currentRole(req), AuthorizationUtil.currentDentistId(req));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("appointment", toJson(appointment));
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error searching for appointment", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to search for appointment right now.");
        }
    }

    private Map<String, Object> toJson(Appointment appointment) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("appointmentNumber", appointment.getAppointmentNumber());
        json.put("patientName", appointment.getPatient().getPatientName());
        json.put("address", appointment.getPatient().getAddress());
        json.put("contactNumber", appointment.getPatient().getContactNumber());
        json.put("dentistName", appointment.getDentist().getDentistName());
        json.put("treatmentType", appointment.getTreatment().getTreatmentName());
        json.put("appointmentDate", appointment.getAppointmentDate().toString());
        json.put("appointmentTime", appointment.getAppointmentTime().toString());
        json.put("status", appointment.getStatus().name());
        return json;
    }

    private int parseInt(String value, String requiredMessage) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(requiredMessage);
        }
        return Integer.parseInt(value);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Appointment date is required.");
        }
        return LocalDate.parse(value);
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Appointment time is required.");
        }
        return LocalTime.parse(value);
    }

    private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        resp.getWriter().write(JsonUtil.write(body));
    }
}

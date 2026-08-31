package com.sunrisedental.servlet;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.AppointmentStatus;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * API for appointment registration, search and scheduling:
 *   POST /api/appointments                    - create
 *   GET  /api/appointments/{number}            - search by appointment number
 *   GET  /api/appointments?date=&status=&dentistId= - schedule/dashboard listing
 *        (a DENTIST session is always forced to their own dentistId)
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
                    patientId, patientName, address, contactNumber,
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
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        String pathInfo = req.getPathInfo();
        String[] segments = (pathInfo == null) ? new String[0] : pathInfo.split("/");
        // pathInfo "/{number}/status" or "/{number}/reschedule" splits to ["", number, action]
        if (segments.length != 3) {
            writeError(resp, HttpServletResponse.SC_NOT_FOUND, "We couldn't find that appointment.");
            return;
        }
        String appointmentNumber = segments[1];
        String action = segments[2];

        if ("status".equals(action)) {
            handleStatusUpdate(req, resp, appointmentNumber);
        } else if ("reschedule".equals(action)) {
            handleReschedule(req, resp, appointmentNumber);
        } else {
            writeError(resp, HttpServletResponse.SC_NOT_FOUND, "We couldn't find that appointment.");
        }
    }

    private void handleStatusUpdate(HttpServletRequest req, HttpServletResponse resp, String appointmentNumber)
            throws IOException {
        try {
            AppointmentStatus target = parseRequiredStatus(req.getParameter("status"));

            if (target == AppointmentStatus.CANCELLED) {
                AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN, UserRole.RECEPTIONIST);
            } else if (target == AppointmentStatus.COMPLETED) {
                AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN, UserRole.DENTIST);
            } else {
                throw new BusinessException("This appointment status cannot be changed.");
            }

            String reason = target == AppointmentStatus.CANCELLED ? req.getParameter("reason") : null;

            Appointment appointment = appointmentService.updateStatus(appointmentNumber, target, reason,
                    AuthorizationUtil.currentRole(req), AuthorizationUtil.currentScopeDentistId(req));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", target == AppointmentStatus.CANCELLED
                    ? "Appointment " + appointment.getAppointmentNumber() + " has been cancelled successfully."
                    : "Appointment " + appointment.getAppointmentNumber() + " has been marked as completed.");
            body.put("appointment", toJson(appointment));
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            int code = com.sunrisedental.service.AppointmentService.APPOINTMENT_NOT_FOUND.equals(e.getMessage())
                    ? HttpServletResponse.SC_NOT_FOUND : HttpServletResponse.SC_BAD_REQUEST;
            writeError(resp, code, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error updating appointment status", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "We couldn't update the appointment right now. Please try again.");
        }
    }

    private void handleReschedule(HttpServletRequest req, HttpServletResponse resp, String appointmentNumber)
            throws IOException {
        try {
            AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN, UserRole.RECEPTIONIST);

            int dentistId = parseInt(req.getParameter("dentistId"), "Dentist is required.");
            LocalDate date = parseDate(req.getParameter("appointmentDate"));
            LocalTime time = parseTime(req.getParameter("appointmentTime"));

            Appointment appointment = appointmentService.reschedule(appointmentNumber, dentistId, date, time);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", "Appointment " + appointment.getAppointmentNumber() + " has been rescheduled successfully.");
            body.put("appointment", toJson(appointment));
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            int code = com.sunrisedental.service.AppointmentService.APPOINTMENT_NOT_FOUND.equals(e.getMessage())
                    ? HttpServletResponse.SC_NOT_FOUND : HttpServletResponse.SC_BAD_REQUEST;
            writeError(resp, code, e.getMessage());
        } catch (NumberFormatException | DateTimeParseException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid request data.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error rescheduling appointment", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "We couldn't update the appointment right now. Please try again.");
        }
    }

    private AppointmentStatus parseRequiredStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Status is required.");
        }
        try {
            return AppointmentStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid appointment status.");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        String pathInfo = req.getPathInfo();

        if (pathInfo == null || pathInfo.length() <= 1) {
            listAppointments(req, resp);
            return;
        }
        String appointmentNumber = pathInfo.substring(1);

        try {
            Appointment appointment = appointmentService.findByAppointmentNumber(appointmentNumber);

            // DENTIST users may only see their own appointments - never
            // another dentist's patient data, even via a direct API call.
            appointmentService.verifyDentistOwnership(appointment,
                    AuthorizationUtil.currentRole(req), AuthorizationUtil.currentScopeDentistId(req));

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

    private void listAppointments(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            LocalDate date = parseOptionalDate(req.getParameter("date"));
            AppointmentStatus status = parseOptionalStatus(req.getParameter("status"));
            String dentistIdParam = req.getParameter("dentistId");
            Integer dentistId = (dentistIdParam == null || dentistIdParam.isBlank())
                    ? null : Integer.valueOf(dentistIdParam);

            List<Appointment> appointments = appointmentService.listAppointments(date, status, dentistId,
                    AuthorizationUtil.currentRole(req), AuthorizationUtil.currentScopeDentistId(req));

            List<Map<String, Object>> body = new ArrayList<>();
            for (Appointment appointment : appointments) {
                body.add(toJson(appointment));
            }
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (NumberFormatException | DateTimeParseException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid request data.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error listing appointments", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to retrieve appointments right now.");
        }
    }

    private LocalDate parseOptionalDate(String value) {
        return (value == null || value.isBlank()) ? null : LocalDate.parse(value);
    }

    private AppointmentStatus parseOptionalStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AppointmentStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid appointment status.");
        }
    }

    private Map<String, Object> toJson(Appointment appointment) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("appointmentNumber", appointment.getAppointmentNumber());
        json.put("patientId", appointment.getPatient().getPatientId());
        json.put("patientName", appointment.getPatient().getPatientName());
        json.put("address", appointment.getPatient().getAddress());
        json.put("contactNumber", appointment.getPatient().getContactNumber());
        json.put("dentistId", appointment.getDentist().getDentistId());
        json.put("dentistName", appointment.getDentist().getDentistName());
        json.put("treatmentId", appointment.getTreatment().getTreatmentId());
        json.put("treatmentType", appointment.getTreatment().getTreatmentName());
        json.put("appointmentDate", appointment.getAppointmentDate().toString());
        json.put("appointmentTime", appointment.getAppointmentTime().toString());
        json.put("status", appointment.getStatus().name());
        json.put("cancellationReason", appointment.getCancellationReason());
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

package com.sunrisedental.servlet;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Bill;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.BillingService;
import com.sunrisedental.util.AuthorizationUtil;
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

/**
 * Test API for billing:
 *   POST /api/bills?appointmentNumber=...  - generate a bill
 *   GET  /api/bills?appointmentNumber=...  - retrieve an existing bill
 */
@WebServlet("/api/bills")
public class BillServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(BillServlet.class.getName());
    private final BillingService billingService = new BillingService();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        String appointmentNumber = req.getParameter("appointmentNumber");
        try {
            AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN, UserRole.RECEPTIONIST, UserRole.BILLING);

            if (appointmentNumber == null || appointmentNumber.isBlank()) {
                throw new BusinessException("Appointment number is required.");
            }
            Bill bill = billingService.generateBill(appointmentNumber);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", "Bill generated successfully.");
            body.put("bill", toJson(bill));
            resp.setStatus(HttpServletResponse.SC_CREATED);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error generating bill", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to generate bill.");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        String appointmentNumber = req.getParameter("appointmentNumber");
        try {
            AuthorizationUtil.requireAnyRole(req, UserRole.ADMIN, UserRole.RECEPTIONIST, UserRole.BILLING);

            if (appointmentNumber == null || appointmentNumber.isBlank()) {
                throw new BusinessException("Appointment number is required.");
            }
            Bill bill = billingService.findByAppointmentNumber(appointmentNumber);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("bill", toJson(bill));
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(JsonUtil.write(body));
        } catch (ForbiddenException e) {
            writeError(resp, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (BusinessException e) {
            writeError(resp, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error retrieving bill", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to retrieve bill.");
        }
    }

    private Map<String, Object> toJson(Bill bill) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("billId", bill.getBillId());
        json.put("appointmentId", bill.getAppointmentId());
        json.put("consultationFee", bill.getConsultationFee());
        json.put("treatmentCost", bill.getTreatmentCost());
        json.put("totalAmount", bill.getTotalAmount());
        json.put("billDate", bill.getBillDate().toString());
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

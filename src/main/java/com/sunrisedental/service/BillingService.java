package com.sunrisedental.service;

import com.sunrisedental.dao.AppointmentDAO;
import com.sunrisedental.dao.BillDAO;
import com.sunrisedental.dao.TreatmentDAO;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.Bill;
import com.sunrisedental.model.Treatment;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BillingService {

    private static final Logger LOGGER = Logger.getLogger(BillingService.class.getName());
    private static final String CONSULTATION_TREATMENT_NAME = "Consultation";

    private final AppointmentDAO appointmentDAO;
    private final TreatmentDAO treatmentDAO;
    private final BillDAO billDAO;

    public BillingService() {
        this(new AppointmentDAO(), new TreatmentDAO(), new BillDAO());
    }

    public BillingService(AppointmentDAO appointmentDAO, TreatmentDAO treatmentDAO, BillDAO billDAO) {
        this.appointmentDAO = appointmentDAO;
        this.treatmentDAO = treatmentDAO;
        this.billDAO = billDAO;
    }

    /**
     * Generates a bill for an appointment: consultation fee comes from the
     * "Consultation" treatment row, the treatment cost from the appointment's
     * own treatment - both read from the database, never hard-coded.
     */
    public Bill generateBill(String appointmentNumber) {
        try {
            Appointment appointment = appointmentDAO.findByAppointmentNumber(appointmentNumber)
                    .orElseThrow(() -> new BusinessException("Appointment not found."));

            if (billDAO.findByAppointmentId(appointment.getAppointmentId()).isPresent()) {
                throw new BusinessException("A bill has already been generated for this appointment.");
            }

            BigDecimal treatmentCost = appointment.getTreatment().getCost();

            Treatment consultation = treatmentDAO.findByName(CONSULTATION_TREATMENT_NAME)
                    .orElseThrow(() -> {
                        LOGGER.severe("Consultation treatment row is missing - cannot calculate consultation fee");
                        return new BusinessException("Unable to generate bill.");
                    });

            Bill bill = new Bill();
            bill.setAppointmentId(appointment.getAppointmentId());
            bill.setConsultationFee(consultation.getCost());
            bill.setTreatmentCost(treatmentCost);

            try {
                billDAO.create(bill);
            } catch (SQLIntegrityConstraintViolationException e) {
                throw new BusinessException("A bill has already been generated for this appointment.");
            }

            return billDAO.findByAppointmentId(appointment.getAppointmentId())
                    .orElseThrow(() -> new BusinessException("Unable to generate bill."));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to generate bill", e);
            throw new BusinessException("Unable to generate bill.");
        }
    }

    public Bill findByAppointmentNumber(String appointmentNumber) {
        try {
            Appointment appointment = appointmentDAO.findByAppointmentNumber(appointmentNumber)
                    .orElseThrow(() -> new BusinessException("Appointment not found."));
            return billDAO.findByAppointmentId(appointment.getAppointmentId())
                    .orElseThrow(() -> new BusinessException("Bill not found for this appointment."));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve bill", e);
            throw new BusinessException("Unable to retrieve bill right now.");
        }
    }
}

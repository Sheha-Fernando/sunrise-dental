package com.sunrisedental.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only projection for the billing list/dashboard screens - a bill
 * plus the appointment context needed to display it without a second
 * round trip. Not a persisted entity in its own right.
 */
public class BillSummary {

    private int billId;
    private String appointmentNumber;
    private String patientName;
    private String dentistName;
    private BigDecimal totalAmount;
    private LocalDateTime billDate;

    public int getBillId() {
        return billId;
    }

    public void setBillId(int billId) {
        this.billId = billId;
    }

    public String getAppointmentNumber() {
        return appointmentNumber;
    }

    public void setAppointmentNumber(String appointmentNumber) {
        this.appointmentNumber = appointmentNumber;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public String getDentistName() {
        return dentistName;
    }

    public void setDentistName(String dentistName) {
        this.dentistName = dentistName;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public LocalDateTime getBillDate() {
        return billDate;
    }

    public void setBillDate(LocalDateTime billDate) {
        this.billDate = billDate;
    }
}

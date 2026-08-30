package com.sunrisedental.model;

import java.time.LocalDate;

/**
 * Read-only projection for the patients list screen - a patient plus
 * derived appointment stats. Not a persisted entity in its own right.
 */
public class PatientSummary {

    private int patientId;
    private String patientName;
    private String contactNumber;
    private LocalDate lastAppointmentDate;
    private int appointmentCount;

    public int getPatientId() {
        return patientId;
    }

    public void setPatientId(int patientId) {
        this.patientId = patientId;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public String getContactNumber() {
        return contactNumber;
    }

    public void setContactNumber(String contactNumber) {
        this.contactNumber = contactNumber;
    }

    public LocalDate getLastAppointmentDate() {
        return lastAppointmentDate;
    }

    public void setLastAppointmentDate(LocalDate lastAppointmentDate) {
        this.lastAppointmentDate = lastAppointmentDate;
    }

    public int getAppointmentCount() {
        return appointmentCount;
    }

    public void setAppointmentCount(int appointmentCount) {
        this.appointmentCount = appointmentCount;
    }
}

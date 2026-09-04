package com.sunrisedental.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Read-only projection for the patients list screen - a patient plus
 * derived appointment info. Not a persisted entity in its own right.
 *
 * "Assigned dentist" has no dedicated column on patients (a patient can see
 * several dentists over time) - it is derived here as the dentist from the
 * patient's most recent non-cancelled visit, which is what the previous
 * appointment history actually shows.
 */
public class PatientSummary {

    private int patientId;
    private String patientName;
    private String contactNumber;
    private String assignedDentistName;
    private LocalDate lastVisitDate;
    private LocalDate nextAppointmentDate;
    private LocalTime nextAppointmentTime;
    private LocalDate registeredDate;
    private String status;

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

    public String getAssignedDentistName() {
        return assignedDentistName;
    }

    public void setAssignedDentistName(String assignedDentistName) {
        this.assignedDentistName = assignedDentistName;
    }

    public LocalDate getLastVisitDate() {
        return lastVisitDate;
    }

    public void setLastVisitDate(LocalDate lastVisitDate) {
        this.lastVisitDate = lastVisitDate;
    }

    public LocalDate getNextAppointmentDate() {
        return nextAppointmentDate;
    }

    public void setNextAppointmentDate(LocalDate nextAppointmentDate) {
        this.nextAppointmentDate = nextAppointmentDate;
    }

    public LocalTime getNextAppointmentTime() {
        return nextAppointmentTime;
    }

    public void setNextAppointmentTime(LocalTime nextAppointmentTime) {
        this.nextAppointmentTime = nextAppointmentTime;
    }

    public LocalDate getRegisteredDate() {
        return registeredDate;
    }

    public void setRegisteredDate(LocalDate registeredDate) {
        this.registeredDate = registeredDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

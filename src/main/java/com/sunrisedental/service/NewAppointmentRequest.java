package com.sunrisedental.service;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Input for registering a new appointment. Pass an existing patientId to
 * attach the visit to a returning patient, or leave it null and supply the
 * patient fields to have AppointmentService create a new patient record
 * in the same transaction.
 */
public record NewAppointmentRequest(
        String appointmentNumber,
        Integer patientId,
        String patientName,
        String address,
        String contactNumber,
        int dentistId,
        int treatmentId,
        LocalDate appointmentDate,
        LocalTime appointmentTime,
        Integer createdByUserId
) {
}

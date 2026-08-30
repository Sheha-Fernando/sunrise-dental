package com.sunrisedental.service;

import com.sunrisedental.dao.PatientDAO;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Patient;
import com.sunrisedental.model.PatientSummary;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PatientService {

    private static final Logger LOGGER = Logger.getLogger(PatientService.class.getName());

    private final PatientDAO patientDAO;

    public PatientService() {
        this(new PatientDAO());
    }

    public PatientService(PatientDAO patientDAO) {
        this.patientDAO = patientDAO;
    }

    public List<PatientSummary> list(String search) {
        try {
            return patientDAO.findAllSummaries(search);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list patients", e);
            throw new BusinessException("Unable to retrieve patients right now.");
        }
    }

    public Patient findById(int patientId) {
        try {
            return patientDAO.findById(patientId)
                    .orElseThrow(() -> new BusinessException("Patient not found."));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve patient", e);
            throw new BusinessException("Unable to retrieve patient right now.");
        }
    }

    public Patient register(String patientName, String address, String contactNumber) {
        if (patientName == null || patientName.isBlank()) {
            throw new BusinessException("Patient name is required.");
        }
        if (address == null || address.isBlank()) {
            throw new BusinessException("Address is required.");
        }
        if (contactNumber == null || contactNumber.isBlank()) {
            throw new BusinessException("Contact number is required.");
        }

        Patient patient = new Patient();
        patient.setPatientName(patientName.trim());
        patient.setAddress(address.trim());
        patient.setContactNumber(contactNumber.trim());

        try {
            patientDAO.create(patient);
            return patient;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to register patient", e);
            throw new BusinessException("Unable to register patient right now.");
        }
    }
}

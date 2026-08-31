package com.sunrisedental.service;

import com.sunrisedental.dao.TreatmentDAO;
import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Treatment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Treatment catalog management (ADMIN-only writes; role gating happens in
 * TreatmentServlet, same convention as every other servlet in this project).
 * Existing appointments/bills reference a treatment_id and copy the cost at
 * billing time (see BillDAO) - editing or deactivating a treatment here never
 * touches those historical rows.
 */
public class TreatmentService {

    private static final Logger LOGGER = Logger.getLogger(TreatmentService.class.getName());
    private static final int MAX_NAME_LENGTH = 100;
    private static final String DUPLICATE_NAME_MESSAGE = "A treatment with this name already exists.";

    private final TreatmentDAO treatmentDAO;

    public TreatmentService() {
        this(new TreatmentDAO());
    }

    public TreatmentService(TreatmentDAO treatmentDAO) {
        this.treatmentDAO = treatmentDAO;
    }

    public List<Treatment> listActive() {
        try {
            return treatmentDAO.findActive();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list active treatments", e);
            throw new BusinessException("Unable to retrieve treatments right now.");
        }
    }

    /** Every treatment regardless of status - the admin management table. */
    public List<Treatment> listAll() {
        try {
            return treatmentDAO.findAll();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list treatments", e);
            throw new BusinessException("Unable to retrieve treatments right now.");
        }
    }

    public Treatment create(String treatmentName, String costInput) {
        String name = validateName(treatmentName);
        BigDecimal cost = validateCost(costInput);

        try (Connection conn = DatabaseConfig.getConnection()) {
            if (treatmentDAO.existsByName(conn, name)) {
                throw new BusinessException(DUPLICATE_NAME_MESSAGE);
            }
            int id;
            try {
                id = treatmentDAO.create(conn, name, cost);
            } catch (SQLIntegrityConstraintViolationException e) {
                throw new BusinessException(DUPLICATE_NAME_MESSAGE);
            }
            Treatment treatment = new Treatment();
            treatment.setTreatmentId(id);
            treatment.setTreatmentName(name);
            treatment.setCost(cost);
            treatment.setActive(true);
            return treatment;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to create treatment", e);
            throw new BusinessException("Unable to create treatment right now. Please try again.");
        }
    }

    public Treatment update(int treatmentId, String treatmentName, String costInput, boolean active) {
        String name = validateName(treatmentName);
        BigDecimal cost = validateCost(costInput);

        try (Connection conn = DatabaseConfig.getConnection()) {
            Treatment existing = treatmentDAO.findById(conn, treatmentId)
                    .orElseThrow(() -> new BusinessException("Treatment not found."));

            if (treatmentDAO.existsByNameExcludingId(conn, name, treatmentId)) {
                throw new BusinessException(DUPLICATE_NAME_MESSAGE);
            }
            try {
                treatmentDAO.update(conn, treatmentId, name, cost, active);
            } catch (SQLIntegrityConstraintViolationException e) {
                throw new BusinessException(DUPLICATE_NAME_MESSAGE);
            }

            existing.setTreatmentName(name);
            existing.setCost(cost);
            existing.setActive(active);
            return existing;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update treatment", e);
            throw new BusinessException("Unable to update treatment right now. Please try again.");
        }
    }

    private String validateName(String treatmentName) {
        if (treatmentName == null || treatmentName.trim().isEmpty()) {
            throw new BusinessException("Treatment name is required.");
        }
        String trimmed = treatmentName.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("Treatment name is too long.");
        }
        return trimmed;
    }

    private BigDecimal validateCost(String costInput) {
        if (costInput == null || costInput.isBlank()) {
            throw new BusinessException("Please enter a valid treatment price.");
        }
        BigDecimal cost;
        try {
            cost = new BigDecimal(costInput.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("Please enter a valid treatment price.");
        }
        if (cost.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Treatment price must be greater than zero.");
        }
        if (cost.compareTo(new BigDecimal("999999.99")) > 0) {
            throw new BusinessException("Please enter a valid treatment price.");
        }
        return cost.setScale(2, RoundingMode.HALF_UP);
    }
}

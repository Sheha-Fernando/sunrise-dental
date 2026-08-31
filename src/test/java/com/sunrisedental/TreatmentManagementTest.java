package com.sunrisedental;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.Bill;
import com.sunrisedental.model.Treatment;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.AppointmentService;
import com.sunrisedental.service.BillingService;
import com.sunrisedental.service.NewAppointmentRequest;
import com.sunrisedental.service.TreatmentService;
import com.sunrisedental.util.AuthorizationUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin treatment management verification against the real sunrise_dental
 * database. All treatments/appointments/patients created here use a
 * "ZZ Test" prefix / far-future date and are removed in cleanupTestData();
 * the five real seed treatments (Cleaning, Consultation, etc.) are never
 * modified.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TreatmentManagementTest {

    private static final TreatmentService treatmentService = new TreatmentService();
    private static final AppointmentService appointmentService = new AppointmentService();
    private static final BillingService billingService = new BillingService();

    private static final int DENTIST_ID = 1;
    private static final String TREATMENT_NAME = "ZZ Test Treatment Alpha";
    private static final String TREATMENT_NAME_2 = "ZZ Test Treatment Beta";

    private static Integer createdTreatmentId;
    private static Integer createdTreatmentId2;
    private static Integer createdAppointmentId;
    private static String createdAppointmentNumber;
    private static Integer createdPatientId;

    @Test
    @Order(1)
    void testActiveAndAllListingsWork() {
        List<Treatment> active = treatmentService.listActive();
        List<Treatment> all = treatmentService.listAll();
        assertFalse(active.isEmpty());
        assertFalse(all.isEmpty());
        assertTrue(active.stream().allMatch(Treatment::isActive));
        // The seeded catalog must still be present and untouched.
        assertTrue(all.stream().anyMatch(t -> "Cleaning".equals(t.getTreatmentName())
                && 0 == new BigDecimal("2000.00").compareTo(t.getCost())));
    }

    @Test
    @Order(2)
    void testModificationRequiresAdminRole() {
        AuthorizationUtil.requireAnyRole(UserRole.ADMIN, UserRole.ADMIN); // must not throw
        assertThrows(ForbiddenException.class, () -> AuthorizationUtil.requireAnyRole(UserRole.RECEPTIONIST, UserRole.ADMIN));
        assertThrows(ForbiddenException.class, () -> AuthorizationUtil.requireAnyRole(UserRole.DENTIST, UserRole.ADMIN));
        assertThrows(ForbiddenException.class, () -> AuthorizationUtil.requireAnyRole(UserRole.BILLING, UserRole.ADMIN));
        assertThrows(ForbiddenException.class, () -> AuthorizationUtil.requireAnyRole(UserRole.CLINICAL_ASSISTANT, UserRole.ADMIN));
    }

    @Test
    @Order(3)
    void testAdminCanCreateTreatment() {
        Treatment created = treatmentService.create(TREATMENT_NAME, "1500.00");
        createdTreatmentId = created.getTreatmentId();
        assertEquals(TREATMENT_NAME, created.getTreatmentName());
        assertEquals(0, new BigDecimal("1500.00").compareTo(created.getCost()));
        assertTrue(created.isActive(), "A newly created treatment must start active");
    }

    @Test
    @Order(4)
    void testCreateRejectsDuplicateNameCaseInsensitive() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> treatmentService.create(TREATMENT_NAME.toLowerCase(), "999.00"));
        assertEquals("A treatment with this name already exists.", ex.getMessage());
    }

    @Test
    @Order(5)
    void testCreateRejectsBlankName() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> treatmentService.create("   ", "100.00"));
        assertEquals("Treatment name is required.", ex.getMessage());
    }

    @Test
    @Order(6)
    void testCreateRejectsInvalidPrices() {
        assertEquals("Treatment price must be greater than zero.",
                assertThrows(BusinessException.class, () -> treatmentService.create("ZZ Zero Price", "0")).getMessage());
        assertEquals("Treatment price must be greater than zero.",
                assertThrows(BusinessException.class, () -> treatmentService.create("ZZ Negative Price", "-10.00")).getMessage());
        assertEquals("Please enter a valid treatment price.",
                assertThrows(BusinessException.class, () -> treatmentService.create("ZZ Bad Price", "not-a-number")).getMessage());
        assertEquals("Please enter a valid treatment price.",
                assertThrows(BusinessException.class, () -> treatmentService.create("ZZ Blank Price", "")).getMessage());
    }

    @Test
    @Order(7)
    void testAdminCanEditNameAndPrice() {
        String renamed = TREATMENT_NAME + " Renamed";
        Treatment updated = treatmentService.update(createdTreatmentId, renamed, "1750.00", true);
        assertEquals(renamed, updated.getTreatmentName());
        assertEquals(0, new BigDecimal("1750.00").compareTo(updated.getCost()));
        assertTrue(updated.isActive());
    }

    @Test
    @Order(8)
    void testEditRejectsDuplicateNameAgainstAnotherTreatment() {
        Treatment second = treatmentService.create(TREATMENT_NAME_2, "800.00");
        createdTreatmentId2 = second.getTreatmentId();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> treatmentService.update(createdTreatmentId2, (TREATMENT_NAME + " Renamed").toUpperCase(), "500.00", true));
        assertEquals("A treatment with this name already exists.", ex.getMessage());
    }

    @Test
    @Order(9)
    void testUpdateOnUnknownTreatmentFails() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> treatmentService.update(9_999_999, "Whatever", "100.00", true));
        assertEquals("Treatment not found.", ex.getMessage());
    }

    @Test
    @Order(10)
    void testPriceChangeDoesNotAffectHistoricalBill() {
        // Book an appointment against our test treatment (still Rs. 1750.00) and bill it.
        NewAppointmentRequest request = new NewAppointmentRequest(
                null, "ZZ Billing Safety Patient", "1 Test Lane, Colombo", "0790000001",
                DENTIST_ID, createdTreatmentId, LocalDate.of(2027, 8, 1), LocalTime.of(9, 0), null);
        Appointment appointment = appointmentService.createAppointment(request);
        createdAppointmentId = appointment.getAppointmentId();
        createdAppointmentNumber = appointment.getAppointmentNumber();
        createdPatientId = appointment.getPatient().getPatientId();

        Bill bill = billingService.generateBill(createdAppointmentNumber);
        assertEquals(0, new BigDecimal("1750.00").compareTo(bill.getTreatmentCost()));
        BigDecimal originalTotal = bill.getTotalAmount();

        // Now change the treatment's price - the already-generated bill must not move.
        treatmentService.update(createdTreatmentId, TREATMENT_NAME + " Renamed", "5000.00", true);

        Bill reloaded = billingService.findByAppointmentNumber(createdAppointmentNumber);
        assertEquals(0, new BigDecimal("1750.00").compareTo(reloaded.getTreatmentCost()),
                "Editing a treatment's price must never retroactively change an existing bill");
        assertEquals(0, originalTotal.compareTo(reloaded.getTotalAmount()));
    }

    @Test
    @Order(11)
    void testAdminCanDeactivateTreatment() {
        Treatment deactivated = treatmentService.update(createdTreatmentId, TREATMENT_NAME + " Renamed", "5000.00", false);
        assertFalse(deactivated.isActive());

        List<Treatment> active = treatmentService.listActive();
        assertTrue(active.stream().noneMatch(t -> t.getTreatmentId() == createdTreatmentId),
                "A deactivated treatment must not appear in the active list used for new appointments");

        List<Treatment> all = treatmentService.listAll();
        assertTrue(all.stream().anyMatch(t -> t.getTreatmentId() == createdTreatmentId),
                "Deactivating must not delete the treatment");
    }

    @Test
    @Order(12)
    void testDeactivatedTreatmentRejectedForNewAppointments() {
        NewAppointmentRequest request = new NewAppointmentRequest(
                null, "ZZ Rejected Patient", "Nowhere", "0790000002",
                DENTIST_ID, createdTreatmentId, LocalDate.of(2027, 8, 2), LocalTime.of(9, 30), null);
        assertThrows(BusinessException.class, () -> appointmentService.createAppointment(request));
    }

    @Test
    @Order(13)
    void testHistoricalAppointmentStillShowsDeactivatedTreatment() {
        Appointment reloaded = appointmentService.findByAppointmentNumber(createdAppointmentNumber);
        assertEquals(TREATMENT_NAME + " Renamed", reloaded.getTreatment().getTreatmentName());
        assertFalse(reloaded.getTreatment().isActive());
    }

    @Test
    @Order(14)
    void testAdminCanReactivateTreatment() {
        Treatment reactivated = treatmentService.update(createdTreatmentId, TREATMENT_NAME + " Renamed", "5000.00", true);
        assertTrue(reactivated.isActive());

        List<Treatment> active = treatmentService.listActive();
        assertTrue(active.stream().anyMatch(t -> t.getTreatmentId() == createdTreatmentId));
    }

    @AfterAll
    static void cleanupTestData() throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement deleteBill = conn.prepareStatement("DELETE FROM bills WHERE appointment_id = ?")) {
                if (createdAppointmentId != null) {
                    deleteBill.setInt(1, createdAppointmentId);
                    deleteBill.executeUpdate();
                }
            }
            try (PreparedStatement deleteAppt = conn.prepareStatement("DELETE FROM appointments WHERE appointment_id = ?")) {
                if (createdAppointmentId != null) {
                    deleteAppt.setInt(1, createdAppointmentId);
                    deleteAppt.executeUpdate();
                }
            }
            try (PreparedStatement deletePatient = conn.prepareStatement("DELETE FROM patients WHERE patient_id = ?")) {
                if (createdPatientId != null) {
                    deletePatient.setInt(1, createdPatientId);
                    deletePatient.executeUpdate();
                }
            }
            try (PreparedStatement deleteTreatment = conn.prepareStatement("DELETE FROM treatments WHERE treatment_id = ?")) {
                if (createdTreatmentId != null) {
                    deleteTreatment.setInt(1, createdTreatmentId);
                    deleteTreatment.executeUpdate();
                }
                if (createdTreatmentId2 != null) {
                    deleteTreatment.setInt(1, createdTreatmentId2);
                    deleteTreatment.executeUpdate();
                }
            }
            conn.commit();
        }
    }
}

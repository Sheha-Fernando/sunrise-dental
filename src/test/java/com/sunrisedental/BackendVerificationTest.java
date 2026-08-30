package com.sunrisedental;

import com.sunrisedental.dao.AppointmentDAO;
import com.sunrisedental.dao.BillDAO;
import com.sunrisedental.dao.DentistDAO;
import com.sunrisedental.dao.PatientDAO;
import com.sunrisedental.dao.TreatmentDAO;
import com.sunrisedental.dao.UserDAO;
import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.AppointmentStatus;
import com.sunrisedental.model.Bill;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.Patient;
import com.sunrisedental.model.Treatment;
import com.sunrisedental.model.User;
import com.sunrisedental.service.AppointmentService;
import com.sunrisedental.service.AuthService;
import com.sunrisedental.service.BillingService;
import com.sunrisedental.service.NewAppointmentRequest;
import com.sunrisedental.service.PatientService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end backend verification against the real sunrise_dental database.
 * All rows created here are tracked and deleted in cleanupTestData().
 * Reference data (users/dentists/treatments seeded at schema setup) is never modified.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackendVerificationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Admin@123";
    private static final int DENTIST_ID = 1;       // Dr. Nimal Perera (seed data)
    private static final int TREATMENT_ID = 2;     // Cleaning, 2000.00 (seed data)
    private static final LocalDate TEST_DATE = LocalDate.of(2026, 12, 15);
    private static final LocalTime TEST_TIME = LocalTime.of(10, 0);

    private static final AppointmentService appointmentService = new AppointmentService();
    private static final BillingService billingService = new BillingService();
    private static final AuthService authService = new AuthService();
    private static final PatientService patientService = new PatientService();

    private static Integer createdPatientId;
    private static Integer createdAppointmentId;
    private static Integer createdAppointmentId2;
    private static String createdAppointmentNumber;

    @Test
    @Order(1)
    void testDatabaseConnection() throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @Order(2)
    void testUserLookup() throws SQLException {
        Optional<User> user = new UserDAO().findByUsername(ADMIN_USERNAME);
        assertTrue(user.isPresent());
        assertEquals(ADMIN_USERNAME, user.get().getUsername());
        assertNotNull(user.get().getPasswordHash());
        assertTrue(user.get().getPasswordHash().startsWith("$2"));
    }

    @Test
    @Order(3)
    void testBCryptAuthenticationSuccess() {
        User user = authService.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertEquals(ADMIN_USERNAME, user.getUsername());
        assertNull(user.getPasswordHash(), "Password hash must never be exposed after authentication");
    }

    @Test
    @Order(4)
    void testBCryptAuthenticationFailure() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.authenticate(ADMIN_USERNAME, "WrongPassword123"));
        assertEquals("Invalid username or password.", ex.getMessage());
    }

    @Test
    @Order(5)
    void testInvalidLoginBlankFields() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.authenticate("", ""));
        assertEquals("Invalid username or password.", ex.getMessage());
    }

    @Test
    @Order(6)
    void testPatientCreation() throws SQLException {
        Patient patient = new Patient();
        patient.setPatientName("Test Patient - Backend Verification");
        patient.setAddress("123 Test Street, Colombo");
        patient.setContactNumber("0700000001");

        int patientId = new PatientDAO().create(patient);
        createdPatientId = patientId;

        Optional<Patient> found = new PatientDAO().findById(patientId);
        assertTrue(found.isPresent());
        assertEquals("Test Patient - Backend Verification", found.get().getPatientName());
    }

    @Test
    @Order(7)
    void testDentistRetrieval() throws SQLException {
        List<Dentist> dentists = new DentistDAO().findActive();
        assertFalse(dentists.isEmpty());
        assertTrue(dentists.stream().allMatch(Dentist::isActive));

        Optional<Dentist> found = new DentistDAO().findById(DENTIST_ID);
        assertTrue(found.isPresent());
    }

    @Test
    @Order(8)
    void testTreatmentRetrieval() throws SQLException {
        List<Treatment> treatments = new TreatmentDAO().findActive();
        assertFalse(treatments.isEmpty());

        Optional<Treatment> consultation = new TreatmentDAO().findByName("Consultation");
        assertTrue(consultation.isPresent());
        assertEquals(0, new BigDecimal("500.00").compareTo(consultation.get().getCost()));
    }

    @Test
    @Order(9)
    void testAppointmentCreation() {
        NewAppointmentRequest request = new NewAppointmentRequest(
                null,
                "Test Patient For Appointment", "456 Test Road, Colombo", "0700000002",
                DENTIST_ID, TREATMENT_ID, TEST_DATE, TEST_TIME, null);

        Appointment appointment = appointmentService.createAppointment(request);
        createdAppointmentId = appointment.getAppointmentId();
        createdAppointmentNumber = appointment.getAppointmentNumber();

        // Server-generated, staff never types or guesses this.
        assertTrue(createdAppointmentNumber.matches("APT-\\d{6}"),
                "Expected an auto-generated APT-NNNNNN number, got: " + createdAppointmentNumber);
        assertEquals(AppointmentStatus.SCHEDULED, appointment.getStatus());
        assertEquals(DENTIST_ID, appointment.getDentist().getDentistId());
        assertEquals(TREATMENT_ID, appointment.getTreatment().getTreatmentId());
    }

    @Test
    @Order(10)
    void testAppointmentSearch() {
        Appointment found = appointmentService.findByAppointmentNumber(createdAppointmentNumber);
        assertEquals("Test Patient For Appointment", found.getPatient().getPatientName());
        assertEquals("456 Test Road, Colombo", found.getPatient().getAddress());
        assertEquals("0700000002", found.getPatient().getContactNumber());
        assertEquals("Cleaning", found.getTreatment().getTreatmentName());
        assertEquals(TEST_DATE, found.getAppointmentDate());
        assertEquals(TEST_TIME, found.getAppointmentTime());
    }

    @Test
    @Order(11)
    void testAppointmentNotFound() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> appointmentService.findByAppointmentNumber("DOES-NOT-EXIST-99999"));
        assertEquals("Appointment not found.", ex.getMessage());
    }

    @Test
    @Order(12)
    void testAppointmentNumbersAreUniquelyGenerated() {
        NewAppointmentRequest request = new NewAppointmentRequest(
                null,
                "Another Patient", "Other Address", "0700000003",
                DENTIST_ID, TREATMENT_ID, TEST_DATE.plusDays(1), LocalTime.of(11, 0), null);

        Appointment second = appointmentService.createAppointment(request);
        createdAppointmentId2 = second.getAppointmentId();

        assertTrue(second.getAppointmentNumber().matches("APT-\\d{6}"));
        assertNotEquals(createdAppointmentNumber, second.getAppointmentNumber(),
                "Two appointments must never receive the same server-generated number.");
    }

    @Test
    @Order(13)
    void testDoubleBookingPrevention() {
        NewAppointmentRequest request = new NewAppointmentRequest(
                null,
                "Double Booking Patient", "Some Address", "0700000004",
                DENTIST_ID, TREATMENT_ID, TEST_DATE, TEST_TIME, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> appointmentService.createAppointment(request));
        assertEquals("This dentist is already booked at this date and time. Please select another time.",
                ex.getMessage());
    }

    @Test
    @Order(14)
    void testValidationErrors() {
        NewAppointmentRequest blankPatientName = new NewAppointmentRequest(
                null, "", "Address", "0700000006",
                DENTIST_ID, TREATMENT_ID, TEST_DATE, TEST_TIME, null);
        assertEquals("Patient name is required.",
                assertThrows(BusinessException.class, () -> appointmentService.createAppointment(blankPatientName)).getMessage());

        NewAppointmentRequest missingDate = new NewAppointmentRequest(
                null, "Name", "Address", "0700000007",
                DENTIST_ID, TREATMENT_ID, null, TEST_TIME, null);
        assertEquals("Appointment date is required.",
                assertThrows(BusinessException.class, () -> appointmentService.createAppointment(missingDate)).getMessage());
    }

    @Test
    @Order(15)
    void testBillCreation() {
        Bill bill = billingService.generateBill(createdAppointmentNumber);
        assertEquals(0, new BigDecimal("500.00").compareTo(bill.getConsultationFee()));
        assertEquals(0, new BigDecimal("2000.00").compareTo(bill.getTreatmentCost()));
        assertEquals(0, new BigDecimal("2500.00").compareTo(bill.getTotalAmount()));
    }

    @Test
    @Order(16)
    void testBillRetrieval() {
        Bill bill = billingService.findByAppointmentNumber(createdAppointmentNumber);
        assertEquals(0, new BigDecimal("2500.00").compareTo(bill.getTotalAmount()));
    }

    @Test
    @Order(17)
    void testDuplicateBillRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> billingService.generateBill(createdAppointmentNumber));
        assertEquals("A bill has already been generated for this appointment.", ex.getMessage());
    }

    @Test
    @Order(18)
    void testPatientUpdateSucceeds() {
        com.sunrisedental.model.Patient updated = patientService.update(createdPatientId,
                "Test Patient - Updated Name", "789 Updated Road, Colombo", "0700000009");
        assertEquals("Test Patient - Updated Name", updated.getPatientName());
        assertEquals("789 Updated Road, Colombo", updated.getAddress());
        assertEquals("0700000009", updated.getContactNumber());
    }

    @Test
    @Order(19)
    void testPatientUpdateInvalidContactRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> patientService.update(createdPatientId, "Name", "Address", "not-a-phone-number!!"));
        assertEquals("Please enter a valid contact number.", ex.getMessage());
    }

    @Test
    @Order(20)
    void testPatientUpdateNotFound() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> patientService.update(9_999_999, "Name", "Address", "0700000000"));
        assertEquals("Patient not found.", ex.getMessage());
    }

    @AfterAll
    static void cleanupTestData() throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement deleteBill = conn.prepareStatement(
                    "DELETE FROM bills WHERE appointment_id = ?")) {
                if (createdAppointmentId != null) {
                    deleteBill.setInt(1, createdAppointmentId);
                    deleteBill.executeUpdate();
                }
            }
            try (PreparedStatement deleteAppt = conn.prepareStatement(
                    "DELETE FROM appointments WHERE appointment_id = ?")) {
                if (createdAppointmentId != null) {
                    deleteAppt.setInt(1, createdAppointmentId);
                    deleteAppt.executeUpdate();
                }
                if (createdAppointmentId2 != null) {
                    deleteAppt.setInt(1, createdAppointmentId2);
                    deleteAppt.executeUpdate();
                }
            }
            try (PreparedStatement deletePatientByAppt = conn.prepareStatement(
                    "DELETE FROM patients WHERE contact_number IN (?, ?, ?, ?, ?, ?, ?)")) {
                deletePatientByAppt.setString(1, "0700000001");
                deletePatientByAppt.setString(2, "0700000002");
                deletePatientByAppt.setString(3, "0700000003");
                deletePatientByAppt.setString(4, "0700000004");
                deletePatientByAppt.setString(5, "0700000005");
                deletePatientByAppt.setString(6, "0700000006");
                deletePatientByAppt.setString(7, "0700000007");
                deletePatientByAppt.executeUpdate();
            }
            // testPatientUpdateSucceeds changes createdPatientId's contact number away from
            // "0700000001", so it must also be removed by its tracked ID directly.
            try (PreparedStatement deletePatientById = conn.prepareStatement(
                    "DELETE FROM patients WHERE patient_id = ?")) {
                if (createdPatientId != null) {
                    deletePatientById.setInt(1, createdPatientId);
                    deletePatientById.executeUpdate();
                }
            }
            conn.commit();
        }
    }
}

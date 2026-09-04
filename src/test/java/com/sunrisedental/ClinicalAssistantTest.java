package com.sunrisedental;

import com.sunrisedental.dao.AppointmentDAO;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.AppointmentStatus;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.AppointmentService;
import com.sunrisedental.service.AuthService;
import com.sunrisedental.service.NewAppointmentRequest;
import com.sunrisedental.service.StaffService;
import com.sunrisedental.util.AuthorizationUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the CLINICAL_ASSISTANT role: parsing, staff-account validation,
 * authentication, authorization boundaries, and assigned-dentist data
 * isolation (parallel to DENTIST isolation, but scoped via
 * assigned_dentist_id rather than dentist_id). Reuses dentist 1/2 (seeded
 * reference dentists) - creates only throwaway "catest.*" accounts, cleaned
 * up in @AfterAll.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClinicalAssistantTest {

    private static final AuthService authService = new AuthService();
    private static final StaffService staffService = new StaffService();
    private static final AppointmentService appointmentService = new AppointmentService();
    private static final AppointmentDAO appointmentDAO = new AppointmentDAO();

    private static final int DENTIST1_ID = 1; // Dr. Nimal Perera (seed data)
    private static final int DENTIST2_ID = 2; // Dr. Anusha Fernando (seed data)
    private static final int TREATMENT_ID = 2; // Cleaning (seed data)
    private static final LocalDate CHECKIN_TEST_DATE = LocalDate.of(2027, 4, 1);

    private static final List<Integer> createdAppointmentIds = new ArrayList<>();
    private static final List<String> createdContactNumbers = new ArrayList<>();

    private static Appointment createAppointment(int dentistId, LocalTime time, String contact) {
        NewAppointmentRequest request = new NewAppointmentRequest(
                null, "Check-In Test Patient", "1 Check-In Road, Colombo", contact,
                dentistId, TREATMENT_ID, CHECKIN_TEST_DATE, time, null);
        Appointment appointment = appointmentService.createAppointment(request);
        createdAppointmentIds.add(appointment.getAppointmentId());
        createdContactNumbers.add(contact);
        return appointment;
    }

    @Test
    @Order(1)
    void testRoleParsing() {
        assertEquals(UserRole.CLINICAL_ASSISTANT, UserRole.fromString("CLINICAL_ASSISTANT"));
        assertEquals(UserRole.CLINICAL_ASSISTANT, UserRole.fromString("clinical_assistant"));
    }

    @Test
    @Order(2)
    void testCreateClinicalAssistantSucceeds() {
        User user = staffService.createStaff("CA Test Assistant", "catest.assistant",
                "Temp@12345", UserRole.CLINICAL_ASSISTANT, null, null, null, null, 1);
        assertEquals(UserRole.CLINICAL_ASSISTANT, user.getRole());
        assertNull(user.getDentistId());
        assertEquals(1, user.getAssignedDentistId());
    }

    @Test
    @Order(3)
    void testClinicalAssistantWithoutAssignedDentistRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.createStaff(
                "CA No Link", "catest.nolink", "Temp@12345", UserRole.CLINICAL_ASSISTANT, null, null, null, null, null));
        assertEquals("An assigned dentist is required for a Clinical Assistant account.", ex.getMessage());
    }

    @Test
    @Order(4)
    void testNonClinicalAssistantWithAssignedDentistRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.createStaff(
                "CA Bad Link", "catest.badlink", "Temp@12345", UserRole.RECEPTIONIST, null, null, null, null, 1));
        assertEquals("Assigned dentist is only applicable to Clinical Assistant accounts.", ex.getMessage());
    }

    @Test
    @Order(5)
    void testInvalidAssignedDentistRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.createStaff(
                "CA Bad Dentist", "catest.baddentist", "Temp@12345", UserRole.CLINICAL_ASSISTANT, null, null, null, null, 99999));
        assertEquals("Selected dentist is not available.", ex.getMessage());
    }

    @Test
    @Order(6)
    void testClinicalAssistantLoginSucceeds() {
        User user = authService.authenticate("catest.assistant", "Temp@12345");
        assertEquals(UserRole.CLINICAL_ASSISTANT, user.getRole());
        assertEquals(1, user.getAssignedDentistId());
        assertNull(user.getDentistId());
        assertNull(user.getPasswordHash());
    }

    @Test
    @Order(7)
    void testStaffManagementDeniedForClinicalAssistant() {
        assertThrows(ForbiddenException.class,
                () -> AuthorizationUtil.requireAnyRole(UserRole.CLINICAL_ASSISTANT, UserRole.ADMIN));
    }

    @Test
    @Order(8)
    void testBillingDeniedForClinicalAssistant() {
        assertThrows(ForbiddenException.class, () -> AuthorizationUtil.requireAnyRole(
                UserRole.CLINICAL_ASSISTANT, UserRole.ADMIN, UserRole.RECEPTIONIST, UserRole.BILLING));
    }

    @Test
    @Order(9)
    void testCreateAppointmentDeniedForClinicalAssistant() {
        assertThrows(ForbiddenException.class, () -> AuthorizationUtil.requireAnyRole(
                UserRole.CLINICAL_ASSISTANT, UserRole.ADMIN, UserRole.RECEPTIONIST));
    }

    @Test
    @Order(10)
    void testAssignedDentistIsolation() {
        Dentist dentist2 = new Dentist();
        dentist2.setDentistId(2);
        Appointment otherDentistsAppointment = new Appointment();
        otherDentistsAppointment.setDentist(dentist2);

        // Assistant assigned to dentist 1 must not access dentist 2's appointment.
        ForbiddenException ex = assertThrows(ForbiddenException.class, () -> appointmentService
                .verifyDentistOwnership(otherDentistsAppointment, UserRole.CLINICAL_ASSISTANT, 1));
        assertEquals("You are not authorized to access this appointment.", ex.getMessage());

        Dentist dentist1 = new Dentist();
        dentist1.setDentistId(1);
        Appointment ownDentistsAppointment = new Appointment();
        ownDentistsAppointment.setDentist(dentist1);

        // Assistant assigned to dentist 1 CAN access dentist 1's appointment.
        appointmentService.verifyDentistOwnership(ownDentistsAppointment, UserRole.CLINICAL_ASSISTANT, 1);
    }

    @Test
    @Order(11)
    void testAssignedDentistScopesAppointmentList() {
        List<Appointment> forDentist1 = appointmentService.listAppointments(
                null, null, null, UserRole.CLINICAL_ASSISTANT, 1);
        assertTrue(forDentist1.stream().allMatch(a -> a.getDentist().getDentistId() == 1));

        List<Appointment> forDentist2 = appointmentService.listAppointments(
                null, null, null, UserRole.CLINICAL_ASSISTANT, 2);
        assertTrue(forDentist2.stream().allMatch(a -> a.getDentist().getDentistId() == 2));
    }

    // --- Check-in workflow (SCHEDULED -> CHECKED_IN) ---------------------------

    @Test
    @Order(13)
    void testCheckInSucceedsForAssignedDentist() {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(8, 30), "0710000101");

        Appointment checkedIn = appointmentService.updateStatus(appointment.getAppointmentNumber(),
                AppointmentStatus.CHECKED_IN, null, UserRole.CLINICAL_ASSISTANT, DENTIST1_ID);

        assertEquals(AppointmentStatus.CHECKED_IN, checkedIn.getStatus());
    }

    @Test
    @Order(14)
    void testCheckInDeniedForWrongDentist() throws SQLException {
        Appointment appointment = createAppointment(DENTIST2_ID, LocalTime.of(8, 30), "0710000102");

        // Assistant assigned to dentist 1 attempting to check in dentist 2's appointment.
        assertThrows(ForbiddenException.class, () -> appointmentService.updateStatus(
                appointment.getAppointmentNumber(), AppointmentStatus.CHECKED_IN, null,
                UserRole.CLINICAL_ASSISTANT, DENTIST1_ID));

        Appointment reloaded = appointmentDAO.findByAppointmentNumber(appointment.getAppointmentNumber()).orElseThrow();
        assertEquals(AppointmentStatus.SCHEDULED, reloaded.getStatus());
    }

    @Test
    @Order(15)
    void testCheckInDeniedForBillingRole() {
        // Mirrors AppointmentServlet's role gate for the CHECKED_IN target: BILLING is not included.
        assertThrows(ForbiddenException.class, () -> AuthorizationUtil.requireAnyRole(
                UserRole.BILLING, UserRole.ADMIN, UserRole.RECEPTIONIST, UserRole.CLINICAL_ASSISTANT));
    }

    @Test
    @Order(16)
    void testCheckInAlreadyCheckedInFails() throws SQLException {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(9, 0), "0710000103");
        appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.CHECKED_IN,
                null, UserRole.CLINICAL_ASSISTANT, DENTIST1_ID);

        BusinessException ex = assertThrows(BusinessException.class, () -> appointmentService.updateStatus(
                appointment.getAppointmentNumber(), AppointmentStatus.CHECKED_IN, null,
                UserRole.CLINICAL_ASSISTANT, DENTIST1_ID));
        assertEquals("Patient is already checked in.", ex.getMessage());

        Appointment reloaded = appointmentDAO.findByAppointmentNumber(appointment.getAppointmentNumber()).orElseThrow();
        assertEquals(AppointmentStatus.CHECKED_IN, reloaded.getStatus());
    }

    @Test
    @Order(17)
    void testCheckInCancelledAppointmentFails() throws SQLException {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(9, 30), "0710000104");
        appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.CANCELLED,
                null, UserRole.ADMIN, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> appointmentService.updateStatus(
                appointment.getAppointmentNumber(), AppointmentStatus.CHECKED_IN, null,
                UserRole.CLINICAL_ASSISTANT, DENTIST1_ID));
        assertEquals("Cancelled appointments cannot be checked in.", ex.getMessage());

        Appointment reloaded = appointmentDAO.findByAppointmentNumber(appointment.getAppointmentNumber()).orElseThrow();
        assertEquals(AppointmentStatus.CANCELLED, reloaded.getStatus());
    }

    @Test
    @Order(18)
    void testCheckInCompletedAppointmentFails() throws SQLException {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(10, 0), "0710000105");
        appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.COMPLETED,
                null, UserRole.DENTIST, DENTIST1_ID);

        BusinessException ex = assertThrows(BusinessException.class, () -> appointmentService.updateStatus(
                appointment.getAppointmentNumber(), AppointmentStatus.CHECKED_IN, null,
                UserRole.CLINICAL_ASSISTANT, DENTIST1_ID));
        assertEquals("This appointment has already been completed and cannot be checked in.", ex.getMessage());

        Appointment reloaded = appointmentDAO.findByAppointmentNumber(appointment.getAppointmentNumber()).orElseThrow();
        assertEquals(AppointmentStatus.COMPLETED, reloaded.getStatus());
    }

    @Test
    @Order(19)
    void testCheckInPersistsAndDentistCanSeeIt() throws SQLException {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(10, 30), "0710000106");
        appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.CHECKED_IN,
                null, UserRole.CLINICAL_ASSISTANT, DENTIST1_ID);

        // Persistence: a fresh DAO read (simulating a page reload) still shows CHECKED_IN.
        Appointment reloaded = appointmentDAO.findByAppointmentNumber(appointment.getAppointmentNumber()).orElseThrow();
        assertEquals(AppointmentStatus.CHECKED_IN, reloaded.getStatus());

        // Dentist visibility: the assigned dentist's own scoped list shows the same row as CHECKED_IN.
        List<Appointment> dentistsOwnList = appointmentService.listAppointments(
                CHECKIN_TEST_DATE, null, null, UserRole.DENTIST, DENTIST1_ID);
        Appointment fromDentistView = dentistsOwnList.stream()
                .filter(a -> a.getAppointmentNumber().equals(appointment.getAppointmentNumber()))
                .findFirst().orElseThrow();
        assertEquals(AppointmentStatus.CHECKED_IN, fromDentistView.getStatus());
    }

    @Test
    @Order(20)
    void testCheckedInAppointmentCanStillBeCompletedByDentist() {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(11, 0), "0710000107");
        appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.CHECKED_IN,
                null, UserRole.CLINICAL_ASSISTANT, DENTIST1_ID);

        Appointment completed = appointmentService.updateStatus(appointment.getAppointmentNumber(),
                AppointmentStatus.COMPLETED, null, UserRole.DENTIST, DENTIST1_ID);
        assertEquals(AppointmentStatus.COMPLETED, completed.getStatus());
    }

    @Test
    @Order(21)
    void testExistingRolesUnaffected() {
        // Sanity check that adding the 5th role didn't disturb existing role parsing.
        assertEquals(UserRole.ADMIN, UserRole.fromString("ADMIN"));
        assertEquals(UserRole.DENTIST, UserRole.fromString("DENTIST"));
        User admin = authService.authenticate("admin", "Admin@123");
        assertEquals(UserRole.ADMIN, admin.getRole());
    }

    @AfterAll
    static void cleanupTestData() throws SQLException {
        try (Connection conn = com.sunrisedental.db.DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM users WHERE username LIKE 'catest.%'");
            }
            try (PreparedStatement deleteAppt = conn.prepareStatement(
                    "DELETE FROM appointments WHERE appointment_id = ?")) {
                for (Integer id : createdAppointmentIds) {
                    deleteAppt.setInt(1, id);
                    deleteAppt.executeUpdate();
                }
            }
            try (PreparedStatement deletePatients = conn.prepareStatement(
                    "DELETE FROM patients WHERE contact_number = ?")) {
                for (String contact : createdContactNumbers) {
                    deletePatients.setString(1, contact);
                    deletePatients.executeUpdate();
                }
            }
            conn.commit();
        }
    }
}

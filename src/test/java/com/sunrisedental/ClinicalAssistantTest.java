package com.sunrisedental;

import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.AppointmentService;
import com.sunrisedental.service.AuthService;
import com.sunrisedental.service.StaffService;
import com.sunrisedental.util.AuthorizationUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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

    @Test
    @Order(12)
    void testExistingRolesUnaffected() {
        // Sanity check that adding the 5th role didn't disturb existing role parsing.
        assertEquals(UserRole.ADMIN, UserRole.fromString("ADMIN"));
        assertEquals(UserRole.DENTIST, UserRole.fromString("DENTIST"));
        User admin = authService.authenticate("admin", "Admin@123");
        assertEquals(UserRole.ADMIN, admin.getRole());
    }

    @AfterAll
    static void cleanupTestData() throws SQLException {
        try (Connection conn = com.sunrisedental.db.DatabaseConfig.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM users WHERE username LIKE 'catest.%'");
        }
    }
}

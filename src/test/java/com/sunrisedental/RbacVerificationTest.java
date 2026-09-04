package com.sunrisedental;

import com.sunrisedental.dao.DentistDAO;
import com.sunrisedental.dao.UserDAO;
import com.sunrisedental.db.DatabaseConfig;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC verification against the real sunrise_dental database. Reuses the
 * standing reference accounts seeded during migration (admin, reception,
 * dentist.nimal, dentist.anusha, billing) - those are never modified or
 * deleted here. Any additional throwaway account this test creates is
 * prefixed "rbactest." and removed in cleanupTestData().
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RbacVerificationTest {

    private static final AuthService authService = new AuthService();
    private static final StaffService staffService = new StaffService();
    private static final AppointmentService appointmentService = new AppointmentService();
    private static final UserDAO userDAO = new UserDAO();
    private static final DentistDAO dentistDAO = new DentistDAO();

    // --- Authentication ---------------------------------------------------

    @Test
    @Order(1)
    void testAdminLoginSucceeds() {
        User user = authService.authenticate("admin", "Admin@123");
        assertEquals(UserRole.ADMIN, user.getRole());
        assertNotNull(user.getUsername());
    }

    @Test
    @Order(2)
    void testReceptionistLoginSucceeds() {
        User user = authService.authenticate("reception", "Reception@123");
        assertEquals(UserRole.RECEPTIONIST, user.getRole());
    }

    @Test
    @Order(3)
    void testDentistLoginSucceeds() {
        User user = authService.authenticate("dentist.nimal", "Dentist@123");
        assertEquals(UserRole.DENTIST, user.getRole());
        assertEquals(1, user.getDentistId());
    }

    @Test
    @Order(4)
    void testBillingLoginSucceeds() {
        User user = authService.authenticate("billing", "Billing@123");
        assertEquals(UserRole.BILLING, user.getRole());
    }

    @Test
    @Order(5)
    void testWrongPasswordFails() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.authenticate("admin", "WrongPassword"));
        assertEquals("Invalid username or password.", ex.getMessage());
    }

    @Test
    @Order(6)
    void testUnknownUsernameFails() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.authenticate("no.such.user", "whatever"));
        assertEquals("Invalid username or password.", ex.getMessage());
    }

    @Test
    @Order(7)
    void testInactiveUserCannotLogin() {
        User created = staffService.createStaff("RBAC Test Receptionist", "rbactest.inactive",
                "Temp@12345", UserRole.RECEPTIONIST, null);
        staffService.updateActiveStatus(created.getUserId(), false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.authenticate("rbactest.inactive", "Temp@12345"));
        assertEquals("Invalid username or password.", ex.getMessage());
    }

    // --- Role parsing -------------------------------------------------------

    @Test
    @Order(8)
    void testRoleParsingValidValues() {
        assertEquals(UserRole.ADMIN, UserRole.fromString("ADMIN"));
        assertEquals(UserRole.RECEPTIONIST, UserRole.fromString("receptionist"));
        assertEquals(UserRole.DENTIST, UserRole.fromString("Dentist"));
        assertEquals(UserRole.BILLING, UserRole.fromString("BILLING"));
    }

    @Test
    @Order(9)
    void testRoleParsingInvalidRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> UserRole.fromString("SUPER_ADMIN"));
        assertEquals("Invalid user role.", ex.getMessage());
        assertThrows(BusinessException.class, () -> UserRole.fromString(""));
        assertThrows(BusinessException.class, () -> UserRole.fromString(null));
    }

    // --- Authorization matrix -------------------------------------------------

    @Test
    @Order(10)
    void testStaffManagementAuthorization() {
        AuthorizationUtil.requireAnyRole(UserRole.ADMIN, UserRole.ADMIN); // allowed, no throw
        assertThrows(ForbiddenException.class,
                () -> AuthorizationUtil.requireAnyRole(UserRole.RECEPTIONIST, UserRole.ADMIN));
        assertThrows(ForbiddenException.class,
                () -> AuthorizationUtil.requireAnyRole(UserRole.DENTIST, UserRole.ADMIN));
        assertThrows(ForbiddenException.class,
                () -> AuthorizationUtil.requireAnyRole(UserRole.BILLING, UserRole.ADMIN));
    }

    @Test
    @Order(11)
    void testCreateAppointmentAuthorization() {
        AuthorizationUtil.requireAnyRole(UserRole.ADMIN, UserRole.ADMIN, UserRole.RECEPTIONIST);
        AuthorizationUtil.requireAnyRole(UserRole.RECEPTIONIST, UserRole.ADMIN, UserRole.RECEPTIONIST);
        assertThrows(ForbiddenException.class,
                () -> AuthorizationUtil.requireAnyRole(UserRole.DENTIST, UserRole.ADMIN, UserRole.RECEPTIONIST));
        assertThrows(ForbiddenException.class,
                () -> AuthorizationUtil.requireAnyRole(UserRole.BILLING, UserRole.ADMIN, UserRole.RECEPTIONIST));
    }

    @Test
    @Order(12)
    void testBillingAuthorization() {
        AuthorizationUtil.requireAnyRole(UserRole.BILLING, UserRole.ADMIN, UserRole.RECEPTIONIST, UserRole.BILLING);
        assertThrows(ForbiddenException.class, () -> AuthorizationUtil.requireAnyRole(
                UserRole.DENTIST, UserRole.ADMIN, UserRole.RECEPTIONIST, UserRole.BILLING));
    }

    // --- Dentist data isolation -----------------------------------------------

    private Appointment appointmentForDentist(int dentistId) {
        Dentist dentist = new Dentist();
        dentist.setDentistId(dentistId);
        dentist.setDentistName("Test Dentist " + dentistId);
        Appointment appointment = new Appointment();
        appointment.setDentist(dentist);
        return appointment;
    }

    @Test
    @Order(13)
    void testDentistCannotAccessAnotherDentistsAppointment() {
        Appointment appointment = appointmentForDentist(2); // belongs to dentist 2
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> appointmentService.verifyDentistOwnership(appointment, UserRole.DENTIST, 1));
        assertEquals("You are not authorized to access this appointment.", ex.getMessage());
    }

    @Test
    @Order(14)
    void testDentistCanAccessOwnAppointment() {
        Appointment appointment = appointmentForDentist(1);
        appointmentService.verifyDentistOwnership(appointment, UserRole.DENTIST, 1); // must not throw
    }

    @Test
    @Order(15)
    void testNonDentistRolesBypassOwnershipCheck() {
        Appointment appointment = appointmentForDentist(2);
        appointmentService.verifyDentistOwnership(appointment, UserRole.ADMIN, null);
        appointmentService.verifyDentistOwnership(appointment, UserRole.RECEPTIONIST, null);
        appointmentService.verifyDentistOwnership(appointment, UserRole.BILLING, null);
    }

    // --- Staff management -------------------------------------------------------

    @Test
    @Order(16)
    void testCreateReceptionistSucceeds() {
        User user = staffService.createStaff("RBAC Test Reception", "rbactest.reception",
                "Temp@12345", UserRole.RECEPTIONIST, null);
        assertEquals(UserRole.RECEPTIONIST, user.getRole());
        assertNull(user.getDentistId());
    }

    @Test
    @Order(17)
    void testCreateDentistSucceeds() {
        User user = staffService.createStaff("RBAC Test Dentist", "rbactest.dentist",
                "Temp@12345", UserRole.DENTIST, 1);
        assertEquals(UserRole.DENTIST, user.getRole());
        assertEquals(1, user.getDentistId());
    }

    @Test
    @Order(18)
    void testDuplicateUsernameRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.createStaff(
                "Another Name", "rbactest.reception", "Temp@12345", UserRole.RECEPTIONIST, null));
        assertEquals("Username is already in use.", ex.getMessage());
    }

    @Test
    @Order(19)
    void testDentistWithoutDentistIdRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.createStaff(
                "No Dentist Link", "rbactest.dentist.nolink", "Temp@12345", UserRole.DENTIST, null));
        assertEquals("Dentist ID is required for a dentist account.", ex.getMessage());
    }

    @Test
    @Order(20)
    void testNonDentistWithDentistIdRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.createStaff(
                "Bad Admin Link", "rbactest.admin.badlink", "Temp@12345", UserRole.ADMIN, 1));
        assertEquals("Dentist ID must not be provided for this role.", ex.getMessage());
    }

    @Test
    @Order(21)
    void testInvalidDentistAssociationRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.createStaff(
                "Bad Dentist Link", "rbactest.dentist.badlink", "Temp@12345", UserRole.DENTIST, 99999));
        assertEquals("Selected dentist is not available.", ex.getMessage());
    }

    @Test
    @Order(22)
    void testDeactivateUserSucceeds() {
        User user = staffService.createStaff("RBAC Test Deactivate", "rbactest.deactivate",
                "Temp@12345", UserRole.BILLING, null);
        User updated = staffService.updateActiveStatus(user.getUserId(), false);
        assertFalse(updated.isActive());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.authenticate("rbactest.deactivate", "Temp@12345"));
        assertEquals("Invalid username or password.", ex.getMessage());
    }

    // --- Admin protection ---------------------------------------------------------
    // These two tests operate on the real seed "admin" account, relying on the
    // implementation checking-before-mutating (so a correct implementation never
    // touches the row). The finally-blocks are a defensive safety net in case a
    // regression ever makes the guard fail open.

    @Test
    @Order(23)
    void testCannotDeactivateLastActiveAdmin() throws SQLException {
        try {
            int adminId = userDAO.findByUsername("admin").orElseThrow().getUserId();
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> staffService.updateActiveStatus(adminId, false));
            assertEquals("The system must have at least one active administrator.", ex.getMessage());
        } finally {
            restoreAdminAccount();
        }
    }

    @Test
    @Order(24)
    void testCannotDemoteLastActiveAdmin() throws SQLException {
        try {
            int adminId = userDAO.findByUsername("admin").orElseThrow().getUserId();
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> staffService.updateRole(adminId, UserRole.RECEPTIONIST, null));
            assertEquals("The system must have at least one active administrator.", ex.getMessage());
        } finally {
            restoreAdminAccount();
        }
    }

    @Test
    @Order(25)
    void testAdminProtectionDoesNotBlockWhenAnotherAdminExists() {
        User secondAdmin = staffService.createStaff("RBAC Test Admin 2", "rbactest.admin2",
                "Temp@12345", UserRole.ADMIN, null);
        // With two active admins, demoting one of them (not the real seed admin) must succeed.
        User demoted = staffService.updateRole(secondAdmin.getUserId(), UserRole.RECEPTIONIST, null);
        assertEquals(UserRole.RECEPTIONIST, demoted.getRole());
    }

    private static void restoreAdminAccount() {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET role = 'ADMIN', is_active = TRUE WHERE username = 'admin'")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to restore admin account after test", e);
        }
    }

    @AfterAll
    static void cleanupTestData() throws SQLException {
        restoreAdminAccount();
        try (Connection conn = DatabaseConfig.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM users WHERE username LIKE 'rbactest.%'");
        }
    }
}

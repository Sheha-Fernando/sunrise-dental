package com.sunrisedental;

import com.sunrisedental.dao.DentistDAO;
import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.User;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.StaffService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Staff-management refinements: a DENTIST account always
 * creates/owns its own dentists row (never links to an existing one),
 * editing a dentist keeps the two rows in sync, and activation/deactivation
 * (both the direct toggle and moving a dentist to a different role via
 * Change Role) keeps the dentists row's own status consistent with the
 * account. All accounts/dentists created here use "smtest.*" prefixes and
 * are removed in cleanupTestData().
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StaffManagementTest {

    private static final StaffService staffService = new StaffService();
    private static final DentistDAO dentistDAO = new DentistDAO();

    private static Integer createdUserId;
    private static Integer createdDentistId;

    @Test
    @Order(1)
    void testCreateDentistCreatesItsOwnNewDentistRow() throws SQLException {
        User user = staffService.createStaff("Dr. Staff Mgmt Test", "smtest.dentist1", "Temp@12345",
                UserRole.DENTIST, "0790000101", "smtest.dentist1@sunrisedental.lk",
                "Orthodontics", "0111110", null);
        createdUserId = user.getUserId();
        createdDentistId = user.getDentistId();

        assertNotNull(createdDentistId);
        Dentist dentist = dentistDAO.findById(createdDentistId).orElseThrow();
        assertEquals("Dr. Staff Mgmt Test", dentist.getDentistName());
        assertEquals("Orthodontics", dentist.getSpecialty());
        assertEquals("0111110", dentist.getWorkingDays());
        assertTrue(dentist.isActive());
    }

    @Test
    @Order(2)
    void testEditKeepsDentistRowInSync() throws SQLException {
        User updated = staffService.updateProfile(createdUserId, "Dr. Staff Mgmt Renamed", "smtest.dentist1",
                null, "0790000102", "renamed@sunrisedental.lk", "Endodontics", "1111111", null);
        assertEquals("Dr. Staff Mgmt Renamed", updated.getFullName());

        Dentist dentist = dentistDAO.findById(createdDentistId).orElseThrow();
        assertEquals("Dr. Staff Mgmt Renamed", dentist.getDentistName());
        assertEquals("Endodontics", dentist.getSpecialty());
        assertEquals("0790000102", dentist.getContactNumber());
        assertEquals("1111111", dentist.getWorkingDays());
    }

    @Test
    @Order(3)
    void testEditWithBlankPasswordKeepsExistingPassword() throws SQLException {
        User before = staffService.updateProfile(createdUserId, "Dr. Staff Mgmt Renamed", "smtest.dentist1",
                "", "0790000102", "renamed@sunrisedental.lk", "Endodontics", "1111111", null);
        // Original password ("Temp@12345") must still work - a blank password must not have cleared it.
        User authenticated = new com.sunrisedental.service.AuthService().authenticate("smtest.dentist1", "Temp@12345");
        assertEquals(createdUserId, authenticated.getUserId());
    }

    @Test
    @Order(4)
    void testDeactivateStaffAlsoDeactivatesLinkedDentist() throws SQLException {
        staffService.updateActiveStatus(createdUserId, false);
        Dentist dentist = dentistDAO.findById(createdDentistId).orElseThrow();
        assertFalse(dentist.isActive(), "Deactivating a dentist's staff account must deactivate the dentist too");
    }

    @Test
    @Order(5)
    void testReactivateStaffAlsoReactivatesLinkedDentist() throws SQLException {
        staffService.updateActiveStatus(createdUserId, true);
        Dentist dentist = dentistDAO.findById(createdDentistId).orElseThrow();
        assertTrue(dentist.isActive());
    }

    @Test
    @Order(6)
    void testChangingRoleAwayFromDentistDeactivatesOldDentistRow() throws SQLException {
        staffService.updateRole(createdUserId, UserRole.RECEPTIONIST, null, null);
        Dentist dentist = dentistDAO.findById(createdDentistId).orElseThrow();
        assertFalse(dentist.isActive(),
                "A dentist's old dentists row must become unavailable for new bookings once the account is no longer a dentist");
    }

    @Test
    @Order(7)
    void testCreateDentistWithoutSpecialtyRejected() throws SQLException {
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.createStaff(
                "Dr. No Specialty Test", "smtest.nospecialty", "Temp@12345", UserRole.DENTIST,
                null, null, null, null, null));
        assertEquals("Specialty is required for a dentist account.", ex.getMessage());
    }

    @Test
    @Order(8)
    void testInvalidContactNumberRejected() throws SQLException {
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.createStaff(
                "Staff Mgmt Bad Contact", "smtest.badcontact", "Temp@12345", UserRole.RECEPTIONIST,
                "abc", null, null, null, null));
        assertEquals("Please enter a valid contact number.", ex.getMessage());
    }

    @Test
    @Order(9)
    void testInvalidEmailRejected() throws SQLException {
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.createStaff(
                "Staff Mgmt Bad Email", "smtest.bademail", "Temp@12345", UserRole.RECEPTIONIST,
                null, "not-an-email", null, null, null));
        assertEquals("Please enter a valid email address.", ex.getMessage());
    }

    @Test
    @Order(10)
    void testUsernameUniquenessEnforcedOnEdit() throws SQLException {
        User other = staffService.createStaff("Staff Mgmt Other", "smtest.other", "Temp@12345",
                UserRole.BILLING, null, null, null, null, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> staffService.updateProfile(
                other.getUserId(), "Staff Mgmt Other", "smtest.dentist1", null, null, null, null, null, null));
        assertEquals("Username is already in use.", ex.getMessage());
    }

    @AfterAll
    static void cleanupTestData() throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM users WHERE username LIKE 'smtest.%'");
            if (createdDentistId != null) {
                st.executeUpdate("DELETE FROM dentists WHERE dentist_id = " + createdDentistId);
            }
        }
    }
}

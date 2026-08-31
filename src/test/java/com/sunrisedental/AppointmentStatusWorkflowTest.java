package com.sunrisedental;

import com.sunrisedental.dao.AppointmentDAO;
import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.exception.ForbiddenException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.AppointmentStatus;
import com.sunrisedental.model.UserRole;
import com.sunrisedental.service.AppointmentService;
import com.sunrisedental.service.NewAppointmentRequest;
import com.sunrisedental.util.AuthorizationUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Appointment status workflow verification (cancel / mark completed /
 * reschedule) against the real sunrise_dental database. All appointments and
 * patients created here use a dedicated far-future date and are removed in
 * cleanupTestData(); existing clinic data is never modified.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppointmentStatusWorkflowTest {

    private static final AppointmentService appointmentService = new AppointmentService();
    private static final AppointmentDAO appointmentDAO = new AppointmentDAO();

    private static final int DENTIST1_ID = 1; // Dr. Nimal Perera (seed data)
    private static final int DENTIST2_ID = 2; // Dr. Anusha Fernando (seed data)
    private static final int TREATMENT_ID = 2; // Cleaning (seed data)
    private static final LocalDate TEST_DATE = LocalDate.of(2027, 3, 1);

    private static final List<Integer> createdAppointmentIds = new ArrayList<>();
    private static final List<String> createdContactNumbers = new ArrayList<>();

    private static Appointment createAppointment(int dentistId, LocalTime time, String contact) {
        NewAppointmentRequest request = new NewAppointmentRequest(
                null, "Status Workflow Test Patient", "1 Workflow Road, Colombo", contact,
                dentistId, TREATMENT_ID, TEST_DATE, time, null);
        Appointment appointment = appointmentService.createAppointment(request);
        createdAppointmentIds.add(appointment.getAppointmentId());
        createdContactNumbers.add(contact);
        return appointment;
    }

    // --- Role gating (mirrors AppointmentServlet's requireAnyRole calls) -------

    @Test
    @Order(1)
    void testCancelAllowedForAdminAndReceptionist() {
        AuthorizationUtil.requireAnyRole(UserRole.ADMIN, UserRole.ADMIN, UserRole.RECEPTIONIST);
        AuthorizationUtil.requireAnyRole(UserRole.RECEPTIONIST, UserRole.ADMIN, UserRole.RECEPTIONIST);
    }

    @Test
    @Order(2)
    void testCancelForbiddenForDentistAndBillingAndAssistant() {
        assertThrows(ForbiddenException.class, () ->
                AuthorizationUtil.requireAnyRole(UserRole.DENTIST, UserRole.ADMIN, UserRole.RECEPTIONIST));
        assertThrows(ForbiddenException.class, () ->
                AuthorizationUtil.requireAnyRole(UserRole.BILLING, UserRole.ADMIN, UserRole.RECEPTIONIST));
        assertThrows(ForbiddenException.class, () ->
                AuthorizationUtil.requireAnyRole(UserRole.CLINICAL_ASSISTANT, UserRole.ADMIN, UserRole.RECEPTIONIST));
    }

    @Test
    @Order(3)
    void testCompleteAllowedForAdminAndDentist() {
        AuthorizationUtil.requireAnyRole(UserRole.ADMIN, UserRole.ADMIN, UserRole.DENTIST);
        AuthorizationUtil.requireAnyRole(UserRole.DENTIST, UserRole.ADMIN, UserRole.DENTIST);
    }

    @Test
    @Order(4)
    void testCompleteForbiddenForReceptionistAndBillingAndAssistant() {
        assertThrows(ForbiddenException.class, () ->
                AuthorizationUtil.requireAnyRole(UserRole.RECEPTIONIST, UserRole.ADMIN, UserRole.DENTIST));
        assertThrows(ForbiddenException.class, () ->
                AuthorizationUtil.requireAnyRole(UserRole.BILLING, UserRole.ADMIN, UserRole.DENTIST));
        assertThrows(ForbiddenException.class, () ->
                AuthorizationUtil.requireAnyRole(UserRole.CLINICAL_ASSISTANT, UserRole.ADMIN, UserRole.DENTIST));
    }

    // --- Cancellation --------------------------------------------------------

    @Test
    @Order(5)
    void testAdminCanCancelScheduledAppointment() {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(9, 0), "0720000001");

        Appointment cancelled = appointmentService.updateStatus(appointment.getAppointmentNumber(),
                AppointmentStatus.CANCELLED, "Patient requested cancellation", UserRole.ADMIN, null);

        assertEquals(AppointmentStatus.CANCELLED, cancelled.getStatus());
        assertEquals("Patient requested cancellation", cancelled.getCancellationReason());

        Appointment reloaded = appointmentService.findByAppointmentNumber(appointment.getAppointmentNumber());
        assertEquals(AppointmentStatus.CANCELLED, reloaded.getStatus());
        assertEquals("Patient requested cancellation", reloaded.getCancellationReason());
    }

    @Test
    @Order(6)
    void testReceptionistCanCancelScheduledAppointment() {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(9, 30), "0720000002");

        Appointment cancelled = appointmentService.updateStatus(appointment.getAppointmentNumber(),
                AppointmentStatus.CANCELLED, null, UserRole.RECEPTIONIST, null);

        assertEquals(AppointmentStatus.CANCELLED, cancelled.getStatus());
        assertNull(cancelled.getCancellationReason(), "Reason is optional and must not be fabricated");
    }

    @Test
    @Order(7)
    void testCancelledSlotBecomesAvailableForReuse() throws SQLException {
        LocalTime slot = LocalTime.of(10, 0);
        Appointment appointment = createAppointment(DENTIST1_ID, slot, "0720000003");

        assertTrue(appointmentDAO.isDentistBooked(DENTIST1_ID, TEST_DATE, slot));

        appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.CANCELLED,
                null, UserRole.ADMIN, null);

        assertFalse(appointmentDAO.isDentistBooked(DENTIST1_ID, TEST_DATE, slot),
                "A cancelled appointment must free its slot for reuse");

        // The freed slot can now be booked by a brand new appointment.
        Appointment reuse = createAppointment(DENTIST1_ID, slot, "0720000004");
        assertEquals(AppointmentStatus.SCHEDULED, reuse.getStatus());
    }

    @Test
    @Order(8)
    void testCompletedAppointmentCannotBeCancelled() {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(10, 30), "0720000005");
        appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.COMPLETED,
                null, UserRole.ADMIN, null);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.CANCELLED,
                        null, UserRole.ADMIN, null));
        assertEquals("This appointment has already been completed and cannot be cancelled.", ex.getMessage());
    }

    @Test
    @Order(9)
    void testCancelledAppointmentCannotBeCompleted() {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(11, 0), "0720000006");
        appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.CANCELLED,
                null, UserRole.ADMIN, null);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.COMPLETED,
                        null, UserRole.ADMIN, null));
        assertEquals("This appointment has already been cancelled.", ex.getMessage());
    }

    @Test
    @Order(10)
    void testUpdateStatusRejectsNonTerminalTarget() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                appointmentService.updateStatus("APT-000001", AppointmentStatus.SCHEDULED, null, UserRole.ADMIN, null));
        assertEquals("This appointment status cannot be changed.", ex.getMessage());
    }

    @Test
    @Order(11)
    void testUpdateStatusOnUnknownAppointmentFails() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                appointmentService.updateStatus("DOES-NOT-EXIST-99999", AppointmentStatus.CANCELLED,
                        null, UserRole.ADMIN, null));
        assertEquals(AppointmentService.APPOINTMENT_NOT_FOUND, ex.getMessage());
    }

    // --- Completion / dentist ownership ---------------------------------------

    @Test
    @Order(12)
    void testDentistCanCompleteOwnAppointment() {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(13, 30), "0720000007");

        Appointment completed = appointmentService.updateStatus(appointment.getAppointmentNumber(),
                AppointmentStatus.COMPLETED, null, UserRole.DENTIST, DENTIST1_ID);

        assertEquals(AppointmentStatus.COMPLETED, completed.getStatus());
    }

    @Test
    @Order(13)
    void testDentistCannotCompleteAnotherDentistsAppointment() {
        Appointment appointment = createAppointment(DENTIST2_ID, LocalTime.of(14, 0), "0720000008");

        assertThrows(ForbiddenException.class, () ->
                appointmentService.updateStatus(appointment.getAppointmentNumber(),
                        AppointmentStatus.COMPLETED, null, UserRole.DENTIST, DENTIST1_ID));

        // Must remain untouched after the rejected attempt.
        Appointment reloaded = appointmentService.findByAppointmentNumber(appointment.getAppointmentNumber());
        assertEquals(AppointmentStatus.SCHEDULED, reloaded.getStatus());
    }

    // --- Rescheduling ----------------------------------------------------------

    @Test
    @Order(14)
    void testRescheduleSucceeds() {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(14, 30), "0720000009");
        LocalDate newDate = TEST_DATE.plusDays(1);
        LocalTime newTime = LocalTime.of(9, 0);

        Appointment rescheduled = appointmentService.reschedule(appointment.getAppointmentNumber(),
                DENTIST2_ID, newDate, newTime);

        assertEquals(DENTIST2_ID, rescheduled.getDentist().getDentistId());
        assertEquals(newDate, rescheduled.getAppointmentDate());
        assertEquals(newTime, rescheduled.getAppointmentTime());
        assertEquals(AppointmentStatus.SCHEDULED, rescheduled.getStatus());
    }

    @Test
    @Order(15)
    void testRescheduleToOccupiedSlotFails() {
        LocalTime occupiedTime = LocalTime.of(15, 0);
        createAppointment(DENTIST1_ID, occupiedTime, "0720000010");
        Appointment toMove = createAppointment(DENTIST1_ID, LocalTime.of(15, 30), "0720000011");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                appointmentService.reschedule(toMove.getAppointmentNumber(), DENTIST1_ID, TEST_DATE, occupiedTime));
        assertEquals("This time is already booked for the selected dentist.", ex.getMessage());

        // Must remain at its original time after the rejected reschedule.
        Appointment reloaded = appointmentService.findByAppointmentNumber(toMove.getAppointmentNumber());
        assertEquals(LocalTime.of(15, 30), reloaded.getAppointmentTime());
    }

    @Test
    @Order(16)
    void testRescheduleOwnCurrentSlotSucceeds() {
        // Re-submitting the same dentist/date/time the appointment already
        // holds must not be rejected as "booked" by itself.
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(16, 0), "0720000012");

        Appointment result = appointmentService.reschedule(appointment.getAppointmentNumber(),
                DENTIST1_ID, TEST_DATE, LocalTime.of(16, 0));
        assertEquals(LocalTime.of(16, 0), result.getAppointmentTime());
    }

    @Test
    @Order(17)
    void testRescheduleOnUnknownAppointmentFails() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                appointmentService.reschedule("DOES-NOT-EXIST-99999", DENTIST1_ID, TEST_DATE, LocalTime.of(8, 30)));
        assertEquals(AppointmentService.APPOINTMENT_NOT_FOUND, ex.getMessage());
    }

    @Test
    @Order(18)
    void testRescheduleCompletedAppointmentFails() {
        Appointment appointment = createAppointment(DENTIST1_ID, LocalTime.of(16, 30), "0720000013");
        appointmentService.updateStatus(appointment.getAppointmentNumber(), AppointmentStatus.COMPLETED,
                null, UserRole.ADMIN, null);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                appointmentService.reschedule(appointment.getAppointmentNumber(), DENTIST1_ID,
                        TEST_DATE.plusDays(2), LocalTime.of(8, 30)));
        assertEquals("This appointment has already been completed and cannot be rescheduled.", ex.getMessage());
    }

    @AfterAll
    static void cleanupTestData() throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
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

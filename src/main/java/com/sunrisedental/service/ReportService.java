package com.sunrisedental.service;

import com.sunrisedental.dao.AppointmentDAO;
import com.sunrisedental.dao.BillDAO;
import com.sunrisedental.dao.DentistDAO;
import com.sunrisedental.dao.PatientDAO;
import com.sunrisedental.exception.BusinessException;
import com.sunrisedental.model.Appointment;
import com.sunrisedental.model.AppointmentStatus;
import com.sunrisedental.model.BillSummary;
import com.sunrisedental.model.Dentist;
import com.sunrisedental.model.UserRole;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the Reports page payload for a given date range and role. All
 * section inclusion is decided here (backend-authoritative) - the frontend
 * only ever renders whatever keys are present in the response. A DENTIST or
 * CLINICAL_ASSISTANT session is always forced to its own scopeDentistId,
 * exactly like AppointmentService.listAppointments - a dentist can never see
 * another dentist's data by manipulating query parameters.
 */
public class ReportService {

    private static final Logger LOGGER = Logger.getLogger(ReportService.class.getName());
    private static final int DAILY_BUCKET_MAX_DAYS = 31;

    private final AppointmentDAO appointmentDAO;
    private final BillDAO billDAO;
    private final PatientDAO patientDAO;
    private final DentistDAO dentistDAO;

    public ReportService() {
        this(new AppointmentDAO(), new BillDAO(), new PatientDAO(), new DentistDAO());
    }

    public ReportService(AppointmentDAO appointmentDAO, BillDAO billDAO, PatientDAO patientDAO, DentistDAO dentistDAO) {
        this.appointmentDAO = appointmentDAO;
        this.billDAO = billDAO;
        this.patientDAO = patientDAO;
        this.dentistDAO = dentistDAO;
    }

    public Map<String, Object> buildReport(String rangeParam, String fromParam, String toParam,
                                            UserRole role, Integer scopeDentistId) {
        String range = normalizeRange(rangeParam);
        LocalDate today = LocalDate.now();
        LocalDate from;
        LocalDate to;

        switch (range) {
            case "week" -> {
                from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                to = today;
            }
            case "month" -> {
                from = today.withDayOfMonth(1);
                to = today;
            }
            case "custom" -> {
                from = parseRequiredDate(fromParam, "From date is required.");
                to = parseRequiredDate(toParam, "To date is required.");
                if (from.isAfter(to)) {
                    throw new BusinessException("From date cannot be after To date.");
                }
            }
            default -> {
                range = "today";
                from = today;
                to = today;
            }
        }

        boolean isScopedRole = (role == UserRole.DENTIST || role == UserRole.CLINICAL_ASSISTANT);
        Integer effectiveDentistId = isScopedRole ? scopeDentistId : null;

        try {
            List<Appointment> appointments = appointmentDAO.findByDateRange(from, to, effectiveDentistId);
            List<BillSummary> bills = billDAO.findByDateRange(from, to, effectiveDentistId);

            boolean canSeeRevenue = role == UserRole.ADMIN || role == UserRole.BILLING || role == UserRole.DENTIST;

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("range", range);
            report.put("from", from.toString());
            report.put("to", to.toString());

            report.put("summary", buildSummary(appointments, bills, canSeeRevenue));
            report.put("statusBreakdown", buildStatusBreakdown(appointments));

            List<Bucket> buckets = buildBuckets(range, from, to);
            report.put("appointmentOverview", buildAppointmentOverview(buckets, appointments));
            if (canSeeRevenue) {
                report.put("revenueTrend", buildRevenueTrend(buckets, bills));
                report.put("treatmentRevenue", buildTreatmentRevenue(bills));
            }

            if (role == UserRole.ADMIN) {
                report.put("dentistPerformance", buildDentistPerformance(appointments, bills));
            }

            if (role == UserRole.ADMIN || role == UserRole.RECEPTIONIST) {
                report.put("patientActivity", buildPatientActivity(from, to));
            }

            if ((range.equals("today") || range.equals("week"))
                    && (role == UserRole.ADMIN || role == UserRole.RECEPTIONIST || role == UserRole.DENTIST)) {
                List<Appointment> todaysAppointments = appointmentDAO.findAll(today, null, effectiveDentistId);
                report.put("dailyOperational", buildDailyOperational(todaysAppointments));
            }

            return report;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to build report", e);
            throw new BusinessException("Unable to generate report right now.");
        }
    }

    private String normalizeRange(String value) {
        if (value == null) {
            return "today";
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return switch (trimmed) {
            case "today", "week", "month", "custom" -> trimmed;
            default -> "today";
        };
    }

    private LocalDate parseRequiredDate(String value, String requiredMessage) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(requiredMessage);
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("Invalid date format.");
        }
    }

    private Map<String, Object> buildSummary(List<Appointment> appointments, List<BillSummary> bills,
                                              boolean canSeeRevenue) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAppointments", appointments.size());
        summary.put("scheduled", countByStatus(appointments, AppointmentStatus.SCHEDULED));
        summary.put("completed", countByStatus(appointments, AppointmentStatus.COMPLETED));
        summary.put("cancelled", countByStatus(appointments, AppointmentStatus.CANCELLED));
        if (canSeeRevenue) {
            summary.put("revenue", sumBills(bills));
        }
        return summary;
    }

    private Map<String, Object> buildStatusBreakdown(List<Appointment> appointments) {
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("scheduled", countByStatus(appointments, AppointmentStatus.SCHEDULED));
        breakdown.put("completed", countByStatus(appointments, AppointmentStatus.COMPLETED));
        breakdown.put("cancelled", countByStatus(appointments, AppointmentStatus.CANCELLED));
        breakdown.put("total", appointments.size());
        return breakdown;
    }

    private Map<String, Object> buildAppointmentOverview(List<Bucket> buckets, List<Appointment> appointments) {
        List<String> labels = new ArrayList<>();
        List<Integer> scheduled = new ArrayList<>();
        List<Integer> completed = new ArrayList<>();
        List<Integer> cancelled = new ArrayList<>();
        for (Bucket bucket : buckets) {
            labels.add(bucket.label);
            List<Appointment> inBucket = appointments.stream()
                    .filter(a -> !a.getAppointmentDate().isBefore(bucket.from) && !a.getAppointmentDate().isAfter(bucket.to))
                    .toList();
            scheduled.add((int) countByStatus(inBucket, AppointmentStatus.SCHEDULED));
            completed.add((int) countByStatus(inBucket, AppointmentStatus.COMPLETED));
            cancelled.add((int) countByStatus(inBucket, AppointmentStatus.CANCELLED));
        }
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("labels", labels);
        overview.put("scheduled", scheduled);
        overview.put("completed", completed);
        overview.put("cancelled", cancelled);
        return overview;
    }

    private Map<String, Object> buildRevenueTrend(List<Bucket> buckets, List<BillSummary> bills) {
        List<String> labels = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        for (Bucket bucket : buckets) {
            labels.add(bucket.label);
            BigDecimal total = bills.stream()
                    .filter(b -> b.getAppointmentDate() != null
                            && !b.getAppointmentDate().isBefore(bucket.from) && !b.getAppointmentDate().isAfter(bucket.to))
                    .map(BillSummary::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            values.add(total);
        }
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("labels", labels);
        trend.put("values", values);
        return trend;
    }

    private Map<String, Object> buildTreatmentRevenue(List<BillSummary> bills) {
        Map<Integer, String> names = new LinkedHashMap<>();
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        Map<Integer, BigDecimal> revenue = new LinkedHashMap<>();
        for (BillSummary bill : bills) {
            int treatmentId = bill.getTreatmentId();
            names.putIfAbsent(treatmentId, bill.getTreatmentName());
            counts.merge(treatmentId, 1, Integer::sum);
            revenue.merge(treatmentId, bill.getTotalAmount(), BigDecimal::add);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Integer treatmentId : names.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("treatmentName", names.get(treatmentId));
            row.put("appointments", counts.get(treatmentId));
            row.put("revenue", revenue.get(treatmentId));
            rows.add(row);
        }
        rows.sort((a, b) -> ((BigDecimal) b.get("revenue")).compareTo((BigDecimal) a.get("revenue")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("totalRevenue", sumBills(bills));
        return result;
    }

    private Map<String, Object> buildDentistPerformance(List<Appointment> appointments, List<BillSummary> bills) {
        List<Dentist> dentists;
        try {
            dentists = dentistDAO.findAll();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to list dentists for report", e);
            throw new BusinessException("Unable to generate report right now.");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Dentist dentist : dentists) {
            List<Appointment> ownAppointments = appointments.stream()
                    .filter(a -> a.getDentist().getDentistId() == dentist.getDentistId())
                    .toList();
            BigDecimal dentistRevenue = bills.stream()
                    .filter(b -> b.getDentistId() != null && b.getDentistId() == dentist.getDentistId())
                    .map(BillSummary::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dentistName", dentist.getDentistName());
            row.put("appointments", ownAppointments.size());
            row.put("completed", countByStatus(ownAppointments, AppointmentStatus.COMPLETED));
            row.put("cancelled", countByStatus(ownAppointments, AppointmentStatus.CANCELLED));
            row.put("revenue", dentistRevenue);
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (BigDecimal) r.get("revenue")).reversed());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> buildPatientActivity(LocalDate from, LocalDate to) {
        try {
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("totalPatients", patientDAO.countTotal());
            activity.put("newPatients", patientDAO.countNewInRange(from, to));
            activity.put("returningPatients", patientDAO.countReturningInRange(from, to));
            return activity;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to compute patient activity for report", e);
            throw new BusinessException("Unable to generate report right now.");
        }
    }

    private Map<String, Object> buildDailyOperational(List<Appointment> todaysAppointments) {
        Map<String, Object> daily = new LinkedHashMap<>();
        List<Appointment> sorted = todaysAppointments.stream()
                .sorted(Comparator.comparing(Appointment::getAppointmentTime))
                .toList();
        daily.put("firstAppointmentTime", sorted.isEmpty() ? null : sorted.get(0).getAppointmentTime().toString());
        daily.put("lastAppointmentTime", sorted.isEmpty() ? null : sorted.get(sorted.size() - 1).getAppointmentTime().toString());
        daily.put("total", todaysAppointments.size());
        long completed = countByStatus(todaysAppointments, AppointmentStatus.COMPLETED);
        long cancelled = countByStatus(todaysAppointments, AppointmentStatus.CANCELLED);
        daily.put("completed", completed);
        daily.put("cancelled", cancelled);
        daily.put("remaining", todaysAppointments.size() - completed - cancelled);
        return daily;
    }

    private long countByStatus(List<Appointment> appointments, AppointmentStatus status) {
        return appointments.stream().filter(a -> a.getStatus() == status).count();
    }

    private BigDecimal sumBills(List<BillSummary> bills) {
        return bills.stream().map(BillSummary::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Splits [from, to] into chart buckets: a single "Today" bucket for the
     * today range, weekly buckets for month/long custom ranges, otherwise one
     * bucket per day - matches the labelling conventions in the report spec
     * (Mon-Sun for a week, Week 1..N for a month).
     */
    private List<Bucket> buildBuckets(String range, LocalDate from, LocalDate to) {
        List<Bucket> buckets = new ArrayList<>();
        if (range.equals("today")) {
            buckets.add(new Bucket("Today", from, to));
            return buckets;
        }

        long totalDays = ChronoUnit.DAYS.between(from, to) + 1;
        boolean weekly = range.equals("month") || totalDays > DAILY_BUCKET_MAX_DAYS;

        if (weekly) {
            LocalDate cursor = from;
            int weekNumber = 1;
            while (!cursor.isAfter(to)) {
                LocalDate bucketEnd = cursor.plusDays(6);
                if (bucketEnd.isAfter(to)) {
                    bucketEnd = to;
                }
                buckets.add(new Bucket("Week " + weekNumber, cursor, bucketEnd));
                cursor = bucketEnd.plusDays(1);
                weekNumber++;
            }
        } else {
            LocalDate cursor = from;
            while (!cursor.isAfter(to)) {
                String label = cursor.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
                buckets.add(new Bucket(label, cursor, cursor));
                cursor = cursor.plusDays(1);
            }
        }
        return buckets;
    }

    private static final class Bucket {
        final String label;
        final LocalDate from;
        final LocalDate to;

        Bucket(String label, LocalDate from, LocalDate to) {
            this.label = label;
            this.from = from;
            this.to = to;
        }
    }
}

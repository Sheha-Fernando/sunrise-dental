package com.sunrisedental.dao;

import com.sunrisedental.db.DatabaseConfig;
import com.sunrisedental.model.Bill;
import com.sunrisedental.model.BillSummary;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BillDAO {

    /** Recent bills for the billing list/dashboard, most recent first. */
    public List<BillSummary> findRecent(int limit) throws SQLException {
        String sql = "SELECT b.bill_id, a.appointment_number, p.patient_name, d.dentist_name, "
                + "       t.treatment_name, b.total_amount, b.bill_date "
                + "FROM bills b "
                + "JOIN appointments a ON b.appointment_id = a.appointment_id "
                + "JOIN patients p ON a.patient_id = p.patient_id "
                + "JOIN dentists d ON a.dentist_id = d.dentist_id "
                + "JOIN treatments t ON a.treatment_id = t.treatment_id "
                + "ORDER BY b.bill_date DESC "
                + "LIMIT ?";
        List<BillSummary> summaries = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BillSummary summary = new BillSummary();
                    summary.setBillId(rs.getInt("bill_id"));
                    summary.setAppointmentNumber(rs.getString("appointment_number"));
                    summary.setPatientName(rs.getString("patient_name"));
                    summary.setDentistName(rs.getString("dentist_name"));
                    summary.setTreatmentName(rs.getString("treatment_name"));
                    summary.setTotalAmount(rs.getBigDecimal("total_amount"));
                    Timestamp billDate = rs.getTimestamp("bill_date");
                    summary.setBillDate(billDate != null ? billDate.toLocalDateTime() : null);
                    summaries.add(summary);
                }
            }
        }
        return summaries;
    }

    /**
     * Bills within an inclusive appointment-date range for report generation,
     * enriched with the ids needed to group by dentist/treatment/patient.
     * dentistId narrows to a single dentist's billed appointments (report
     * scoping for DENTIST/CLINICAL_ASSISTANT sessions); null means all.
     */
    public List<BillSummary> findByDateRange(java.time.LocalDate from, java.time.LocalDate to, Integer dentistId)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT b.bill_id, a.appointment_number, a.appointment_date, "
              + "       p.patient_id, p.patient_name, d.dentist_id, d.dentist_name, "
              + "       t.treatment_id, t.treatment_name, b.total_amount, b.bill_date "
              + "FROM bills b "
              + "JOIN appointments a ON b.appointment_id = a.appointment_id "
              + "JOIN patients p ON a.patient_id = p.patient_id "
              + "JOIN dentists d ON a.dentist_id = d.dentist_id "
              + "JOIN treatments t ON a.treatment_id = t.treatment_id "
              + "WHERE a.appointment_date BETWEEN ? AND ? ");
        if (dentistId != null) {
            sql.append("AND d.dentist_id = ? ");
        }
        sql.append("ORDER BY a.appointment_date, b.bill_date");

        List<BillSummary> summaries = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setObject(1, java.sql.Date.valueOf(from));
            ps.setObject(2, java.sql.Date.valueOf(to));
            if (dentistId != null) {
                ps.setInt(3, dentistId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BillSummary summary = new BillSummary();
                    summary.setBillId(rs.getInt("bill_id"));
                    summary.setAppointmentNumber(rs.getString("appointment_number"));
                    summary.setPatientId(rs.getInt("patient_id"));
                    summary.setPatientName(rs.getString("patient_name"));
                    summary.setDentistId(rs.getInt("dentist_id"));
                    summary.setDentistName(rs.getString("dentist_name"));
                    summary.setTreatmentId(rs.getInt("treatment_id"));
                    summary.setTreatmentName(rs.getString("treatment_name"));
                    summary.setTotalAmount(rs.getBigDecimal("total_amount"));
                    java.sql.Date apptDate = rs.getDate("appointment_date");
                    summary.setAppointmentDate(apptDate != null ? apptDate.toLocalDate() : null);
                    Timestamp billDate = rs.getTimestamp("bill_date");
                    summary.setBillDate(billDate != null ? billDate.toLocalDateTime() : null);
                    summaries.add(summary);
                }
            }
        }
        return summaries;
    }

    /** Sum of bill totals for a single calendar day (for "Today's Revenue"). */
    public BigDecimal sumTotalForDate(java.time.LocalDate date) throws SQLException {
        String sql = "SELECT COALESCE(SUM(total_amount), 0) FROM bills WHERE DATE(bill_date) = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    public int create(Bill bill) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return create(conn, bill);
        }
    }

    public int create(Connection conn, Bill bill) throws SQLException {
        // total_amount is a MySQL GENERATED ALWAYS AS column - never inserted directly.
        String sql = "INSERT INTO bills (appointment_id, consultation_fee, treatment_cost) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, bill.getAppointmentId());
            ps.setBigDecimal(2, bill.getConsultationFee());
            ps.setBigDecimal(3, bill.getTreatmentCost());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int billId = keys.getInt(1);
                    bill.setBillId(billId);
                    return billId;
                }
                throw new SQLException("Failed to obtain generated bill_id");
            }
        }
    }

    public Optional<Bill> findByAppointmentId(int appointmentId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findByAppointmentId(conn, appointmentId);
        }
    }

    public Optional<Bill> findByAppointmentId(Connection conn, int appointmentId) throws SQLException {
        String sql = "SELECT bill_id, appointment_id, consultation_fee, treatment_cost, total_amount, bill_date "
                + "FROM bills WHERE appointment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, appointmentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private Bill mapRow(ResultSet rs) throws SQLException {
        Bill bill = new Bill();
        bill.setBillId(rs.getInt("bill_id"));
        bill.setAppointmentId(rs.getInt("appointment_id"));
        bill.setConsultationFee(rs.getBigDecimal("consultation_fee"));
        bill.setTreatmentCost(rs.getBigDecimal("treatment_cost"));
        BigDecimal total = rs.getBigDecimal("total_amount");
        bill.setTotalAmount(total);
        Timestamp billDate = rs.getTimestamp("bill_date");
        bill.setBillDate(billDate != null ? billDate.toLocalDateTime() : null);
        return bill;
    }
}

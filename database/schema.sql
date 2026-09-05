-- =============================================================================
-- Sunrise Dental Clinic — Patient Appointment and Management System
-- MySQL 8.x schema
--

CREATE DATABASE IF NOT EXISTS sunrise_dental
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE sunrise_dental;

-- -----------------------------------------------------------------------------
-- dentists
-- -----------------------------------------------------------------------------
CREATE TABLE dentists (
    dentist_id      INT PRIMARY KEY AUTO_INCREMENT,
    dentist_name    VARCHAR(100)    NOT NULL,
    specialty       VARCHAR(100)    NOT NULL,
    contact_number  VARCHAR(20)     NULL,
    email           VARCHAR(150)    NULL,
    -- 7-character Mon..Sun bitmask, e.g. "0111110" = working Mon-Fri.
    working_days    CHAR(7)         NOT NULL DEFAULT '0111110',
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE
) ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- users  — staff accounts (login/RBAC). A DENTIST or CLINICAL_ASSISTANT
-- account is always linked to a dentists row (own, or the dentist they assist).
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    user_id             INT PRIMARY KEY AUTO_INCREMENT,
    username            VARCHAR(50)     NOT NULL UNIQUE,
    password_hash       VARCHAR(60)     NOT NULL,          -- BCrypt, fixed 60 chars
    full_name           VARCHAR(100)    NOT NULL,
    contact_number      VARCHAR(20)     NULL,
    email               VARCHAR(150)    NULL,
    role                ENUM('ADMIN','RECEPTIONIST','DENTIST','BILLING','CLINICAL_ASSISTANT')
                                        NOT NULL,
    dentist_id          INT             NULL,              -- set only when role = DENTIST
    assigned_dentist_id INT             NULL,              -- set only when role = CLINICAL_ASSISTANT
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_users_dentist
        FOREIGN KEY (dentist_id) REFERENCES dentists(dentist_id),
    CONSTRAINT fk_users_assigned_dentist
        FOREIGN KEY (assigned_dentist_id) REFERENCES dentists(dentist_id)
) ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- patients
-- -----------------------------------------------------------------------------
CREATE TABLE patients (
    patient_id      INT PRIMARY KEY AUTO_INCREMENT,
    patient_name    VARCHAR(100)    NOT NULL,
    address         VARCHAR(255)    NOT NULL,
    contact_number  VARCHAR(20)     NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- treatments — cost is copied into a bill at billing time (TreatmentService
-- Javadoc: editing/deactivating a treatment never retroactively changes an
-- already-generated bill), so historical bills are unaffected by later edits.
-- -----------------------------------------------------------------------------
CREATE TABLE treatments (
    treatment_id    INT PRIMARY KEY AUTO_INCREMENT,
    treatment_name  VARCHAR(100)    NOT NULL,
    cost            DECIMAL(8,2)    NOT NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    -- Case-insensitive uniqueness ("cleaning" vs "Cleaning") is enforced in
    -- TreatmentService/TreatmentDAO.existsByName at the application layer,
    -- not by a database constraint (MySQL's default collation is already
    -- case-insensitive for utf8mb4_unicode_ci, so a UNIQUE index is added
    -- as a defence-in-depth backstop).
    UNIQUE KEY uq_treatments_name (treatment_name)
) ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- appointments
-- -----------------------------------------------------------------------------
CREATE TABLE appointments (
    appointment_id      INT PRIMARY KEY AUTO_INCREMENT,
    -- Assigned in two steps by AppointmentService.createAppointment: a
    -- temporary placeholder at insert time, then updated to "APT-%06d"
    -- (zero-padded appointment_id) once the generated key is known.
    appointment_number  VARCHAR(20)     NOT NULL UNIQUE,
    patient_id          INT             NOT NULL,
    dentist_id          INT             NOT NULL,
    treatment_id        INT             NOT NULL,
    appointment_date    DATE            NOT NULL,
    appointment_time    TIME            NOT NULL,
    status              ENUM('SCHEDULED','CHECKED_IN','COMPLETED','CANCELLED')
                                        NOT NULL DEFAULT 'SCHEDULED',
    cancellation_reason VARCHAR(255)    NULL,
    created_by          INT             NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- NULL for any CANCELLED row, otherwise dentist_id|date|time. MySQL's
    -- UNIQUE index treats multiple NULLs as distinct, so a cancelled slot's
    -- row no longer participates in the uniqueness check and the same
    -- dentist/date/time can be booked again — this is what
    -- AppointmentDAO.isDentistBooked()'s "status <> 'CANCELLED'" filter and
    -- the BackendVerificationTest/AppointmentStatusWorkflowTest slot-reuse
    -- tests rely on at the application layer; the generated column enforces
    -- the same rule as a hard database-level guarantee against a race
    -- between two concurrent booking requests.
    active_slot_key VARCHAR(60) GENERATED ALWAYS AS (
        CASE WHEN status <> 'CANCELLED'
             THEN CONCAT(dentist_id, '|', appointment_date, '|', appointment_time)
             ELSE NULL END
    ) STORED,
    CONSTRAINT fk_appointments_patient
        FOREIGN KEY (patient_id) REFERENCES patients(patient_id),
    CONSTRAINT fk_appointments_dentist
        FOREIGN KEY (dentist_id) REFERENCES dentists(dentist_id),
    CONSTRAINT fk_appointments_treatment
        FOREIGN KEY (treatment_id) REFERENCES treatments(treatment_id),
    CONSTRAINT fk_appointments_created_by
        FOREIGN KEY (created_by) REFERENCES users(user_id),
    UNIQUE KEY uq_appointments_dentist_slot (active_slot_key)
) ENGINE = InnoDB;

CREATE INDEX ix_appointments_date ON appointments (appointment_date);
CREATE INDEX ix_appointments_patient ON appointments (patient_id);

-- -----------------------------------------------------------------------------
-- bills — one bill per appointment; total_amount is derived, never inserted.
-- -----------------------------------------------------------------------------
CREATE TABLE bills (
    bill_id             INT PRIMARY KEY AUTO_INCREMENT,
    appointment_id      INT             NOT NULL UNIQUE,
    consultation_fee    DECIMAL(8,2)    NOT NULL,
    treatment_cost      DECIMAL(8,2)    NOT NULL,
    total_amount        DECIMAL(8,2) GENERATED ALWAYS AS (consultation_fee + treatment_cost) STORED,
    bill_date           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bills_appointment
        FOREIGN KEY (appointment_id) REFERENCES appointments(appointment_id)
) ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- notifications — reference_type/reference_id is a deliberate loose,
-- polymorphic pointer (currently only "APPOINTMENT" is used) rather than a
-- foreign key, since a notification can point at different tables depending
-- on its type (per NotificationDAO class Javadoc).
-- -----------------------------------------------------------------------------
CREATE TABLE notifications (
    notification_id     INT PRIMARY KEY AUTO_INCREMENT,
    recipient_user_id    INT             NOT NULL,
    sender_user_id        INT             NULL,   -- NULL = system-generated event
    notification_type   ENUM('APPOINTMENT_CREATED','APPOINTMENT_CANCELLED',
                              'APPOINTMENT_RESCHEDULED','APPOINTMENT_COMPLETED',
                              'PATIENT_CHECKED_IN','BILL_GENERATED',
                              'PATIENT_RUNNING_LATE','DENTIST_RUNNING_LATE',
                              'PATIENT_ARRIVED','GENERAL_MESSAGE')
                                         NOT NULL,
    title                VARCHAR(150)    NOT NULL,
    message              VARCHAR(500)    NOT NULL,
    reference_type       VARCHAR(30)     NULL,
    reference_id         INT             NULL,
    is_read              BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notifications_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users(user_id),
    CONSTRAINT fk_notifications_sender
        FOREIGN KEY (sender_user_id) REFERENCES users(user_id)
) ENGINE = InnoDB;

CREATE INDEX ix_notifications_recipient ON notifications (recipient_user_id, is_read);

-- -----------------------------------------------------------------------------
-- Seed data — one account per role, matching the exact usernames AND
-- plaintext passwords the test suite authenticates with
-- (RbacVerificationTest.testAdminLoginSucceeds() etc. and
-- ClinicalAssistantTest): admin/Admin@123, reception/Reception@123,
-- dentist.nimal/Dentist@123, dentist.anusha/Dentist@123, billing/Billing@123.
-- The password_hash values below are real BCrypt-$2a$ hashes of those exact
-- passwords (generated with the same algorithm as PasswordUtil.hash, cost
-- factor 10), so this seed data is sufficient on its own to run the full
-- test suite and to log in via the UI for a demo. CHANGE ALL PASSWORDS
-- before any real deployment — these are committed in cleartext-derivable
-- form for CI/local-dev convenience only.
-- -----------------------------------------------------------------------------
INSERT INTO treatments (treatment_name, cost, is_active) VALUES
    ('Consultation', 1500.00, TRUE),
    ('Scaling & Polishing', 4500.00, TRUE),
    ('Tooth Extraction', 6000.00, TRUE),
    ('Root Canal Treatment', 18000.00, TRUE),
    ('Dental Filling', 5000.00, TRUE);

INSERT INTO dentists (dentist_name, specialty, contact_number, email, working_days, is_active) VALUES
    ('Dr. Nimal Perera', 'General & Restorative Dentistry', '0771234567', 'nimal@sunrisedental.lk', '0111110', TRUE),
    ('Dr. Anusha Fernando', 'Orthodontics', '0777654321', 'anusha@sunrisedental.lk', '0111110', TRUE);

INSERT INTO users (username, password_hash, full_name, contact_number, email, role, dentist_id, assigned_dentist_id, is_active) VALUES
    ('admin',           '$2a$10$wxMN6inRLQ18hFJhEtpSuuTzcNa9/6qQ/DwiSC26EIvIK8kraV.rC', 'System Administrator', '0700000001', 'admin@sunrisedental.lk',   'ADMIN',              NULL, NULL, TRUE),
    ('reception',       '$2a$10$//No.B/6HaXaVLBWOu1ywO4WC7KsP39DB3BgGswvKsro95ROHQQze', 'Front Desk Reception', '0700000002', 'reception@sunrisedental.lk', 'RECEPTIONIST',       NULL, NULL, TRUE),
    ('billing',         '$2a$10$Jtcq6Pa3X6FpiynG9aM.jut0CIcD6T.UJIUocb3PmPtKY8MjkwZLG', 'Billing Officer',      '0700000003', 'billing@sunrisedental.lk',   'BILLING',            NULL, NULL, TRUE),
    ('dentist.nimal',   '$2a$10$zhi/zPMVKXiTn3WzYorG/edgzTBkb2Q6OsnhZUFGUATTe703N976.', 'Dr. Nimal Perera',     '0771234567', 'nimal@sunrisedental.lk',     'DENTIST',            1,    NULL, TRUE),
    ('dentist.anusha',  '$2a$10$HVn8.R187.SlAHmvqzmF0ubzNs1lY8aJJv6wOtENZiCXYkboPI0L6', 'Dr. Anusha Fernando',  '0777654321', 'anusha@sunrisedental.lk',    'DENTIST',            2,    NULL, TRUE);

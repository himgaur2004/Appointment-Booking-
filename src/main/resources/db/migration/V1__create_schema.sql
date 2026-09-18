-- =====================================================
-- V1: Core schema for Appointment & Reminder Service
-- =====================================================

-- Dealership reference table
CREATE TABLE dealerships (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    address     VARCHAR(500),
    timezone    VARCHAR(50) NOT NULL DEFAULT 'UTC',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Core appointments table
CREATE TABLE appointments (
    id              BIGSERIAL PRIMARY KEY,
    dealership_id   BIGINT NOT NULL REFERENCES dealerships(id),
    customer_name   VARCHAR(255) NOT NULL,
    customer_email  VARCHAR(255),
    customer_phone  VARCHAR(20),
    vehicle_info    VARCHAR(500),
    service_type    VARCHAR(255) NOT NULL,
    scheduled_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    -- At least one contact method required (enforced at app level too)
    CONSTRAINT chk_contact CHECK (customer_email IS NOT NULL OR customer_phone IS NOT NULL)
);

-- Reminders table with idempotency guarantee
CREATE TABLE reminders (
    id              BIGSERIAL PRIMARY KEY,
    appointment_id  BIGINT NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    reminder_type   VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    scheduled_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at         TIMESTAMP WITH TIME ZONE,
    retry_count     INT NOT NULL DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    -- IDEMPOTENCY GUARANTEE: Prevents duplicate reminders for the same appointment + type
    CONSTRAINT uq_appointment_reminder UNIQUE (appointment_id, reminder_type)
);

-- Performance indexes
CREATE INDEX idx_reminders_pending_scheduled ON reminders(status, scheduled_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_reminders_failed ON reminders(status, retry_count)
    WHERE status = 'FAILED';

CREATE INDEX idx_appointments_scheduled ON appointments(scheduled_at, status)
    WHERE status = 'SCHEDULED';

CREATE INDEX idx_appointments_dealership ON appointments(dealership_id);

CREATE INDEX idx_appointments_status ON appointments(status);

-- =====================================================
-- V4: Add Idempotency-Key support on Appointments
-- =====================================================

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS uq_appointment_idempotency_key 
ON appointments(idempotency_key) 
WHERE idempotency_key IS NOT NULL;

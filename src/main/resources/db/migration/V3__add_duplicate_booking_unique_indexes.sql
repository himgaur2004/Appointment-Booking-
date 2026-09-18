-- =====================================================
-- V3: Enforce No Duplicate Bookings
-- Prevents duplicate active bookings for the same customer
-- at the same dealership and scheduled time.
-- =====================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_active_appointment_email 
ON appointments(dealership_id, scheduled_at, customer_email) 
WHERE status = 'SCHEDULED' AND customer_email IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_active_appointment_phone 
ON appointments(dealership_id, scheduled_at, customer_phone) 
WHERE status = 'SCHEDULED' AND customer_phone IS NOT NULL;

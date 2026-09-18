-- =====================================================
-- V5: Optimize sequences for Hibernate batch inserts
-- Switch from increment-by-1 to increment-by-50 to enable
-- Hibernate's pooled sequence optimizer (hi-lo allocation).
-- This dramatically reduces DB roundtrips during inserts.
-- =====================================================

-- Advance sequences well beyond current max to create a safe gap
-- then set increment to 50 for pooled allocation
ALTER SEQUENCE appointments_id_seq INCREMENT BY 50;
SELECT setval('appointments_id_seq', (SELECT MAX(id) + 100 FROM appointments));

ALTER SEQUENCE reminders_id_seq INCREMENT BY 50;
SELECT setval('reminders_id_seq', (SELECT MAX(id) + 100 FROM reminders));

ALTER SEQUENCE dealerships_id_seq INCREMENT BY 50;
SELECT setval('dealerships_id_seq', (SELECT MAX(id) + 100 FROM dealerships));

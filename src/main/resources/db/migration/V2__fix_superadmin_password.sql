-- Fix superadmin password hash (V1 used the Laravel demo hash for "password", not "Admin@123")
-- Correct BCrypt hash for Admin@123
UPDATE users
SET password = '$2b$10$XfwXIywd8rLPKTSR4y2e8.R9OxxOVCGi97FFBzMkPwypmlZ2VcSiO'
WHERE username = 'superadmin';

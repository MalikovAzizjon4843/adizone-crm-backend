-- V1 legacy seed may already exist; V18 inserts are no-ops then. Normalize contact and labels.

UPDATE users SET email = 'superadmin@adizone.uz' WHERE username = 'superadmin';

UPDATE settings SET setting_value = 'Adizone', description = 'Center name' WHERE setting_key = 'school_name';
UPDATE settings SET description = 'Currency' WHERE setting_key = 'currency';
UPDATE settings SET description = 'Payment cycle' WHERE setting_key = 'payment_cycle_days';
UPDATE settings SET description = 'Max debt days' WHERE setting_key = 'max_debt_days';

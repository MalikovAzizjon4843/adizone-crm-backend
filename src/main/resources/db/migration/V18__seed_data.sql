-- Idempotent seed: fills gaps if V1 DEFAULT DATA was removed in a fresh DB only.
-- Do not edit V1 after Flyway has applied it (checksum); use new migrations instead.

INSERT INTO users (username, email, password, first_name,
                   last_name, phone, role, is_active)
SELECT 'superadmin', 'superadmin@adizone.uz',
       '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.',
       'Super', 'Admin', '+998901234567', 'SUPER_ADMIN', true
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE username = 'superadmin'
);

INSERT INTO settings (setting_key, setting_value, description)
VALUES
    ('school_name', 'Adizone', 'Center name'),
    ('currency', 'UZS', 'Currency'),
    ('payment_cycle_days', '30', 'Payment cycle'),
    ('max_debt_days', '7', 'Max debt days')
ON CONFLICT (setting_key) DO NOTHING;

-- Testlar uchun: prodda bu obyektni db/migration/V52__billing_v2.sql yaratadi.
CREATE SEQUENCE IF NOT EXISTS payment_receipt_seq START WITH 1;
-- Shartnoma raqami (CTR-YYYY-NNNNN): prodda db/migration/V58__phase5_security.sql yaratadi.
CREATE SEQUENCE IF NOT EXISTS contract_number_seq START WITH 1;
-- Shartnoma raqami yil bo'yicha hisoblagichi: prodda db/migration/V59__contract_number_per_year.sql yaratadi.
CREATE TABLE IF NOT EXISTS contract_number_counters (
    contract_year INTEGER PRIMARY KEY,
    last_value    BIGINT  NOT NULL CHECK (last_value >= 0)
);

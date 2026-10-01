-- Billing v2 — ACCRUAL modeli (docs/design/billing-v2.md §10.1).
--
-- Loyihada Flyway YO'Q: sxemani `ddl-auto: update` quradi va bu fayl
-- QO'LDA bajariladi (cutover oynasi, §9.6). Hammasi IF NOT EXISTS bilan,
-- bir necha marta ishga tushirishga bardoshli va faqat QO'SHIMCHA (additive):
-- eski jar shu sxemada ham ishlaydi.
--
-- Nega kerak: `ddl-auto: update`
--   * sequence yaratmaydi (chek raqami — §5.3);
--   * mavjud jadvalga qo'shilgan ustundagi UNIQUE ni kafolatlamaydi
--     (payments.idempotency_key — §7.3);
--   * to'la jadvalga NOT NULL ustun qo'sha olmaydi (effective_date backfill).
-- BillingSchemaGuard startup'da shu obyektlarni tekshiradi; birortasi yo'q
-- bo'lsa app.billing.enabled majburan false bo'ladi.

BEGIN;

-- ── billing_periods (§3.3, I4) ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS billing_periods (
    id                   BIGSERIAL PRIMARY KEY,
    student_group_id     BIGINT        NOT NULL REFERENCES student_groups(id),
    period_start         DATE          NOT NULL,
    period_end           DATE          NOT NULL,
    fee                  NUMERIC(12,2),
    discount_percentage  NUMERIC(5,2),
    amount               NUMERIC(12,2),
    status               VARCHAR(20)   NOT NULL,
    charge_tx_id         BIGINT,
    refunded_amount      NUMERIC(12,2) NOT NULL DEFAULT 0,
    migration_run_id     BIGINT,
    created_at           TIMESTAMP     NOT NULL DEFAULT now()
);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_billing_periods_sg_start') THEN
        ALTER TABLE billing_periods
            ADD CONSTRAINT uk_billing_periods_sg_start UNIQUE (student_group_id, period_start);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_billing_periods_migration ON billing_periods (migration_run_id);

-- ── balance_transactions (§2.3) ─────────────────────────────────────
ALTER TABLE balance_transactions ADD COLUMN IF NOT EXISTS effective_date    DATE;
ALTER TABLE balance_transactions ADD COLUMN IF NOT EXISTS related_tx_id     BIGINT;
ALTER TABLE balance_transactions ADD COLUMN IF NOT EXISTS billing_period_id BIGINT;
ALTER TABLE balance_transactions ADD COLUMN IF NOT EXISTS migration_run_id  BIGINT;
UPDATE balance_transactions SET effective_date = created_at::date WHERE effective_date IS NULL;
ALTER TABLE balance_transactions ALTER COLUMN effective_date SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_balance_tx_sg_effective ON balance_transactions (student_group_id, effective_date, id);
CREATE INDEX IF NOT EXISTS idx_balance_tx_related     ON balance_transactions (related_tx_id);
CREATE INDEX IF NOT EXISTS idx_balance_tx_migration   ON balance_transactions (migration_run_id);

-- ── payments: idempotentlik (§7.3) ──────────────────────────────────
ALTER TABLE payments ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
        WHERE c.conrelid = 'payments'::regclass AND c.contype = 'u' AND a.attname = 'idempotency_key'
    ) THEN
        ALTER TABLE payments ADD CONSTRAINT uk_payments_idempotency_key UNIQUE (idempotency_key);
    END IF;
END $$;

ALTER TABLE payments ADD COLUMN IF NOT EXISTS idempotency_hash    VARCHAR(64);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS cash_transaction_id BIGINT;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS cancelled_at        TIMESTAMP;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS cancelled_by_id     BIGINT REFERENCES users(id);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS cancel_reason       VARCHAR(500);

-- ── cash_transactions: to'lov bog'lanishi va REVERSAL (§6.4, I5) ───
ALTER TABLE cash_transactions ADD COLUMN IF NOT EXISTS payment_id    BIGINT;
ALTER TABLE cash_transactions ADD COLUMN IF NOT EXISTS related_tx_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_cash_transactions_payment ON cash_transactions (payment_id);
-- type ustuni varchar(20): REVERSAL qiymati uchun DDL kerak emas
-- (EnumCheckConstraintCleaner eski CHECK larni startup'da olib tashlaydi).

-- ── bonus_penalties (§6.5) ──────────────────────────────────────────
ALTER TABLE bonus_penalties ADD COLUMN IF NOT EXISTS student_group_id BIGINT;
ALTER TABLE bonus_penalties ADD COLUMN IF NOT EXISTS ledger_tx_id     BIGINT;
ALTER TABLE bonus_penalties ADD COLUMN IF NOT EXISTS cancel_reason    VARCHAR(500);

-- ── Chek raqami sequence (§5.3) ─────────────────────────────────────
-- Sequence mavjud chek raqamlarining eng kattasidan davom etadi.
-- Qayta ishga tushirilsa ham to'g'ri: max(receipt) — oxirgi berilgan raqam.
CREATE SEQUENCE IF NOT EXISTS payment_receipt_seq;
SELECT setval('payment_receipt_seq',
    COALESCE((SELECT MAX(SUBSTRING(receipt_number FROM 5)::BIGINT)
              FROM payments WHERE receipt_number ~ '^RCP-[0-9]+$'), 0) + 1,
    false);

-- ── student_groups: muzlatish sanasi (§6.7) ─────────────────────────
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS frozen_from DATE;
-- §9.7: migratsiyadan chetlatilgan / qaytarilgan SG (MIGRATION_PENDING) — accrual o'tkazib yuboradi
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS billing_hold BOOLEAN;

-- ── Snapshot ustunlari (§4.4, §8) ───────────────────────────────────
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS debt_since          DATE;
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS next_payment_amount NUMERIC(12,2);
ALTER TABLE students       ADD COLUMN IF NOT EXISTS debt                NUMERIC(12,2);
ALTER TABLE students       ADD COLUMN IF NOT EXISTS next_payment_amount NUMERIC(12,2);
-- §3.5: chegirma null → 0 (accrual ham null ni 0 deb oladi, bu faqat tozalik uchun)
UPDATE student_groups SET discount_percentage = 0 WHERE discount_percentage IS NULL;
-- payment_status endi enum (varchar o'zgarmaydi); eski erkin qiymatlar enum ichida:
-- TRIAL, PENDING, PAID, OVERDUE, SUSPENDED, ARCHIVED, FROZEN. Snapshot refresh ularni qayta yozadi.

-- ── billing_job_runs (§3.7) ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS billing_job_runs (
    id               BIGSERIAL PRIMARY KEY,
    job_name         VARCHAR(40)  NOT NULL,
    trigger_source   VARCHAR(20)  NOT NULL,
    run_date         DATE         NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    candidates       INTEGER,
    processed        INTEGER,
    periods_created  INTEGER,
    failed           INTEGER,
    catch_up_limited INTEGER,
    errors           TEXT,
    started_at       TIMESTAMP    NOT NULL,
    finished_at      TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_billing_job_runs_started ON billing_job_runs (started_at);

-- ── billing_migration_runs (§9.4–§9.7, §13 #20) ─────────────────────
-- Dry-run hech narsa yozmaydi; bu jadvalga faqat egasi TASDIQLAGAN hisobot (hash) va
-- uning qo'llanishi yoziladi. Apply faqat APPROVED run bo'yicha.
CREATE TABLE IF NOT EXISTS billing_migration_runs (
    id                 BIGSERIAL PRIMARY KEY,
    status             VARCHAR(30)  NOT NULL,
    cutover_date       DATE         NOT NULL,
    go_live_date       DATE         NOT NULL,
    a14_use_payable    BOOLEAN      NOT NULL DEFAULT FALSE,
    report_hash        VARCHAR(64)  NOT NULL,
    max_tx_id          BIGINT,
    max_payment_id     BIGINT,
    sg_total           INTEGER,
    approved_by_owner  VARCHAR(200) NOT NULL,
    approval_note      TEXT,
    approved_by        VARCHAR(100),
    approved_at        TIMESTAMP    NOT NULL,
    applied_by         VARCHAR(100),
    applied_at         TIMESTAMP,
    rollback_deadline  TIMESTAMP,
    excluded_sg_ids    TEXT,
    clear_overrides    BOOLEAN,
    migrated           INTEGER,
    held               INTEGER,
    failed             INTEGER,
    errors             TEXT,
    summary_json       TEXT
);

COMMIT;

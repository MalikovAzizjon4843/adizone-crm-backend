-- Kassa va ruxsatlar — 1-bosqich (docs/design/cash-and-books.md §1.2, §1.7).
-- FAQAT sxema + kategoriyalar seed'i. Mavjud yozuvlar ma'lumotiga tegmaydi:
--   * kategoriya backfill — docs/ops/cash-category-backfill.sql (preview → UPDATE, alohida);
--   * a'zolar (moderator_id) va Direktor kassasi — docs/ops/cash-cutover.sql (preview → apply, alohida).
--
-- Nima qo'shiladi:
--   * cash_registers.kind (ADMIN | DIRECTOR, standart ADMIN) va float_amount (razmen, ≥ 0);
--   * cash_register_members — kassa a'zolari (ADMIN / ACCOUNTANT faqat a'zo bo'lgan ADMIN kassani ko'radi);
--   * cash_categories — sozlanadigan kategoriyalar (SYSTEM / DAILY / STRATEGIC) + boshlang'ich ro'yxat
--     (CashCategories.defaults() bilan AYNAN bir xil);
--   * cash_transactions.category (FK → cash_categories) va handover_id (FK → cash_handovers);
--   * expenses.category → FK cash_categories (NOT VALID: eski qatorlar tekshirilmaydi, yangilari — ha);
--   * cash_handovers, cash_handover_lines — kunlik topshirish; bir kassada bitta SUBMITTED (partial UNIQUE).
--
-- Enum CHECK (IN ro'yxati) qo'yilmaydi — EnumCheckConstraintCleaner ularni startup'da o'chiradi
-- (qiymat Java enum'ida tekshiriladi). Faqat summa CHECK lari.
--
-- V85 dan KEYIN, ILOVANI YANGI JAR BILAN ISHGA TUSHIRISHDAN OLDIN, QO'LDA, crm_user bilan:
--   psql -X -v ON_ERROR_STOP=1 -f V86__cash_registers_access_categories_handover.sql
-- (oldin qo'llanmasa Hibernate ddl-auto: update NOT NULL ustunni to'ldirilgan jadvalga qo'sha olmaydi).
-- Idempotent (IF NOT EXISTS / pg_constraint tekshiruvi) — qayta bajarish xavfsiz.
-- Tekshirish: docs/ops/prod-schema-check.sql — V86 bo'lagi "TO'LIQ".

BEGIN;

-- ── 1. Kassa turi va razmen ─────────────────────────────────────────────────────────
ALTER TABLE cash_registers ADD COLUMN IF NOT EXISTS kind VARCHAR(20);
UPDATE cash_registers SET kind = 'ADMIN' WHERE kind IS NULL;
ALTER TABLE cash_registers ALTER COLUMN kind SET DEFAULT 'ADMIN';
ALTER TABLE cash_registers ALTER COLUMN kind SET NOT NULL;

ALTER TABLE cash_registers ADD COLUMN IF NOT EXISTS float_amount NUMERIC(15,2);
UPDATE cash_registers SET float_amount = 0 WHERE float_amount IS NULL;
ALTER TABLE cash_registers ALTER COLUMN float_amount SET DEFAULT 0;
ALTER TABLE cash_registers ALTER COLUMN float_amount SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_cash_registers_float_nonneg') THEN
        ALTER TABLE cash_registers ADD CONSTRAINT ck_cash_registers_float_nonneg CHECK (float_amount >= 0);
    END IF;
END $$;

-- ── 2. Kassa a'zolari ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cash_register_members (
    id                BIGSERIAL PRIMARY KEY,
    cash_register_id  BIGINT    NOT NULL,
    user_id           BIGINT    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_cash_register_members_register_user
    ON cash_register_members (cash_register_id, user_id);
CREATE INDEX IF NOT EXISTS idx_cash_register_members_user ON cash_register_members (user_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cash_register_members_register') THEN
        ALTER TABLE cash_register_members ADD CONSTRAINT fk_cash_register_members_register
            FOREIGN KEY (cash_register_id) REFERENCES cash_registers(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cash_register_members_user') THEN
        ALTER TABLE cash_register_members ADD CONSTRAINT fk_cash_register_members_user
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
END $$;

-- ── 3. Kategoriyalar ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cash_categories (
    code        VARCHAR(40)  PRIMARY KEY,
    direction   VARCHAR(3)   NOT NULL,      -- IN | OUT
    name        VARCHAR(100) NOT NULL,
    scope       VARCHAR(20)  NOT NULL,      -- SYSTEM | DAILY | STRATEGIC
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INTEGER      NOT NULL DEFAULT 0
);

INSERT INTO cash_categories (code, direction, name, scope, is_active, sort_order) VALUES
    ('STUDENT_PAYMENT', 'IN',  'O''quvchi to''lovi',      'SYSTEM',    TRUE, 10),
    ('EXAM',            'IN',  'Imtihon',                 'SYSTEM',    TRUE, 20),
    ('BOOK_SALE',       'IN',  'Kitob sotuvi',            'SYSTEM',    TRUE, 30),
    ('OTHER_INCOME',    'IN',  'Boshqa kirim',            'DAILY',     TRUE, 40),
    ('CASH_SURPLUS',    'IN',  'Topshirishda ortiqcha',   'SYSTEM',    TRUE, 90),
    ('SALARY',          'OUT', 'Oylik',                   'SYSTEM',    TRUE, 10),
    ('REFUND',          'OUT', 'O''quvchiga qaytarildi',  'SYSTEM',    TRUE, 20),
    ('BOOK_PURCHASE',   'OUT', 'Kitob xaridi',            'SYSTEM',    TRUE, 30),
    ('CASH_SHORTAGE',   'OUT', 'Topshirishda kamomad',    'SYSTEM',    TRUE, 40),
    ('DELIVERY',        'OUT', 'Dostavka',                'DAILY',     TRUE, 100),
    ('STATIONERY',      'OUT', 'Kanselyariya, qog''oz',   'DAILY',     TRUE, 110),
    ('HOUSEHOLD',       'OUT', 'Xo''jalik mollari',       'DAILY',     TRUE, 120),
    ('OTHER_DAILY',     'OUT', 'Boshqa mayda xarajat',    'DAILY',     TRUE, 190),
    ('RENT',            'OUT', 'Ijara',                   'STRATEGIC', TRUE, 200),
    ('MARKETING',       'OUT', 'Reklama (target)',        'STRATEGIC', TRUE, 210),
    ('UTILITIES',       'OUT', 'Kommunal',                'STRATEGIC', TRUE, 220),
    ('EQUIPMENT',       'OUT', 'Jihozlar',                'STRATEGIC', TRUE, 230),
    ('OTHER',           'OUT', 'Boshqa yirik xarajat',    'STRATEGIC', TRUE, 290)
ON CONFLICT (code) DO NOTHING;

-- ── 4. Kunlik topshirish ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cash_handovers (
    id                       BIGSERIAL     PRIMARY KEY,
    uuid                     VARCHAR(36)   NOT NULL,
    cash_register_id         BIGINT        NOT NULL,
    target_cash_register_id  BIGINT,
    business_date            DATE          NOT NULL,
    status                   VARCHAR(20)   NOT NULL,     -- SUBMITTED | ACCEPTED | REJECTED
    float_amount             NUMERIC(15,2) NOT NULL,
    expected_cash            NUMERIC(15,2) NOT NULL,
    declared_cash            NUMERIC(15,2) NOT NULL,
    counted_cash             NUMERIC(15,2),
    cash_diff                NUMERIC(15,2),
    note                     TEXT,
    diff_note                TEXT,
    reject_reason            TEXT,
    submitted_by             BIGINT,
    submitted_at             TIMESTAMP     NOT NULL,
    decided_by               BIGINT,
    decided_at               TIMESTAMP,
    legacy                   BOOLEAN       NOT NULL DEFAULT FALSE,
    idempotency_key          VARCHAR(100)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_cash_handovers_uuid ON cash_handovers (uuid);
CREATE UNIQUE INDEX IF NOT EXISTS ux_cash_handovers_idempotency_key ON cash_handovers (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_cash_handovers_register ON cash_handovers (cash_register_id);
CREATE INDEX IF NOT EXISTS idx_cash_handovers_status ON cash_handovers (status);
-- Bir kassada bir vaqtda bitta ochiq (SUBMITTED) topshirish
CREATE UNIQUE INDEX IF NOT EXISTS ux_cash_handovers_one_submitted
    ON cash_handovers (cash_register_id) WHERE status = 'SUBMITTED';

CREATE TABLE IF NOT EXISTS cash_handover_lines (
    id              BIGSERIAL     PRIMARY KEY,
    handover_id     BIGINT        NOT NULL,
    payment_method  VARCHAR(20)   NOT NULL,
    expected_in     NUMERIC(15,2) NOT NULL,
    expected_out    NUMERIC(15,2) NOT NULL,
    expected_net    NUMERIC(15,2) NOT NULL,
    actual          NUMERIC(15,2),
    diff            NUMERIC(15,2)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_cash_handover_lines_method ON cash_handover_lines (handover_id, payment_method);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cash_handovers_register') THEN
        ALTER TABLE cash_handovers ADD CONSTRAINT fk_cash_handovers_register
            FOREIGN KEY (cash_register_id) REFERENCES cash_registers(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cash_handovers_target') THEN
        ALTER TABLE cash_handovers ADD CONSTRAINT fk_cash_handovers_target
            FOREIGN KEY (target_cash_register_id) REFERENCES cash_registers(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cash_handover_lines_handover') THEN
        ALTER TABLE cash_handover_lines ADD CONSTRAINT fk_cash_handover_lines_handover
            FOREIGN KEY (handover_id) REFERENCES cash_handovers(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_cash_handovers_amounts_nonneg') THEN
        ALTER TABLE cash_handovers ADD CONSTRAINT ck_cash_handovers_amounts_nonneg
            CHECK (float_amount >= 0 AND declared_cash >= 0 AND (counted_cash IS NULL OR counted_cash >= 0));
    END IF;
END $$;

-- ── 5. cash_transactions: kategoriya va topshirish ──────────────────────────────────
ALTER TABLE cash_transactions ADD COLUMN IF NOT EXISTS category VARCHAR(40);
ALTER TABLE cash_transactions ADD COLUMN IF NOT EXISTS handover_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_cash_transactions_category_date ON cash_transactions (category, transaction_date);
CREATE INDEX IF NOT EXISTS idx_cash_transactions_handover ON cash_transactions (handover_id);
-- Topshirish preview'i: kassaning hali topshirilmagan yozuvlari
CREATE INDEX IF NOT EXISTS idx_cash_transactions_unhanded
    ON cash_transactions (cash_register_id) WHERE handover_id IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cash_transactions_category') THEN
        ALTER TABLE cash_transactions ADD CONSTRAINT fk_cash_transactions_category
            FOREIGN KEY (category) REFERENCES cash_categories(code);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cash_transactions_handover') THEN
        ALTER TABLE cash_transactions ADD CONSTRAINT fk_cash_transactions_handover
            FOREIGN KEY (handover_id) REFERENCES cash_handovers(id);
    END IF;
END $$;

-- ── 6. expenses.category → cash_categories ──────────────────────────────────────────
-- Avval enum edi (SALARY, RENT, MARKETING, UTILITIES, EQUIPMENT, OTHER — hammasi seed'da).
-- Hibernate yaratgan eski enum CHECK (bo'lsa) olib tashlanadi — endi kategoriya jadvaldan.
ALTER TABLE expenses DROP CONSTRAINT IF EXISTS expenses_category_check;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_expenses_category') THEN
        ALTER TABLE expenses ADD CONSTRAINT fk_expenses_category
            FOREIGN KEY (category) REFERENCES cash_categories(code) NOT VALID;
    END IF;
END $$;

COMMIT;

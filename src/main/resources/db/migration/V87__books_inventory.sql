-- Kitoblar ombori — 2-bosqich (docs/design/cash-and-books.md §2, buyurtmachi javoblari 2026-10-18).
-- FAQAT sxema. Ma'lumotga tegmaydi (kategoriyalar BOOK_SALE / BOOK_PURCHASE — V86 seed'ida bor).
--
-- Nima qo'shiladi:
--   * study_levels — sozlanadigan daraja (til + kod + nom, masalan "Turk A1"), har darajaga bitta bepul kitob;
--     groups.level_id (haqiqiy daraja), courses.level_id (yangi guruh uchun standart);
--   * book_items — katalog: STUDY (daraja bilan) / FICTION, narx, qoldiq (CHECK ≥ 0), o'rtacha tannarx (faqat SA),
--     kam qolgan chegarasi (standart: o'quv 30, badiiy 3 — ilova qo'yadi, kitobda o'zgaradi);
--   * book_suppliers, book_receipts (+ lines) — kirim; book_supplier_payments — yetkazib beruvchiga to'lov;
--   * book_sales (+ lines) — sotuv (savat, bitta chek); book_gifts — bepul kitob (student_id UNIQUE);
--   * book_stock_movements — ombor harakati jurnali (RECEIPT / SALE / GIFT / ADJUSTMENT, qty_after ≥ 0);
--   * cash_transactions.book_sale_id, book_supplier_payment_id.
--
-- Enum CHECK (IN ro'yxati) qo'yilmaydi — EnumCheckConstraintCleaner ularni startup'da o'chiradi. Summa/son CHECK lari bor.
--
-- V86 dan KEYIN, ILOVANI YANGI JAR BILAN ISHGA TUSHIRISHDAN OLDIN, QO'LDA, crm_user bilan:
--   psql -X -v ON_ERROR_STOP=1 -f V87__books_inventory.sql
-- Idempotent (IF NOT EXISTS / pg_constraint tekshiruvi) — qayta bajarish xavfsiz.
-- Tekshirish: docs/ops/prod-schema-check.sql — V87 bo'lagi "TO'LIQ".

BEGIN;

-- ── 1. Darajalar ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS study_levels (
    id                 BIGSERIAL    PRIMARY KEY,
    language           VARCHAR(50)  NOT NULL,
    code               VARCHAR(20)  NOT NULL,
    name               VARCHAR(80)  NOT NULL,
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    gift_book_item_id  BIGINT,
    created_at         TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_study_levels_language_code ON study_levels (lower(language), lower(code));

ALTER TABLE groups  ADD COLUMN IF NOT EXISTS level_id BIGINT;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS level_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_groups_level ON groups (level_id);

-- ── 2. Katalog ──────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS book_items (
    id          BIGSERIAL     PRIMARY KEY,
    uuid        VARCHAR(36)   NOT NULL,
    kind        VARCHAR(20)   NOT NULL,            -- STUDY | FICTION
    title       VARCHAR(200)  NOT NULL,
    author      VARCHAR(150),
    level_id    BIGINT,
    sale_price  NUMERIC(12,2) NOT NULL,
    stock_qty   INTEGER       NOT NULL DEFAULT 0,
    avg_cost    NUMERIC(12,2),
    min_stock   INTEGER       NOT NULL,
    is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_book_items_uuid ON book_items (uuid);
CREATE UNIQUE INDEX IF NOT EXISTS ux_book_items_title_kind_level
    ON book_items (lower(title), kind, COALESCE(level_id, 0));
CREATE INDEX IF NOT EXISTS idx_book_items_level ON book_items (level_id);

-- ── 3. Yetkazib beruvchi, kirim, to'lov ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS book_suppliers (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    phone       VARCHAR(30),
    note        TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_book_suppliers_name ON book_suppliers (lower(name));

CREATE TABLE IF NOT EXISTS book_receipts (
    id              BIGSERIAL     PRIMARY KEY,
    uuid            VARCHAR(36)   NOT NULL,
    supplier_id     BIGINT        NOT NULL,
    receipt_date    DATE          NOT NULL,
    status          VARCHAR(20)   NOT NULL,        -- COST_PENDING | UNPAID | PARTIAL | PAID
    total_cost      NUMERIC(14,2),
    paid_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    note            TEXT,
    created_by      BIGINT,
    cost_filled_by  BIGINT,
    cost_filled_at  TIMESTAMP,
    created_at      TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_book_receipts_uuid ON book_receipts (uuid);
CREATE INDEX IF NOT EXISTS idx_book_receipts_supplier ON book_receipts (supplier_id);

CREATE TABLE IF NOT EXISTS book_receipt_lines (
    id            BIGSERIAL     PRIMARY KEY,
    receipt_id    BIGINT        NOT NULL,
    book_item_id  BIGINT        NOT NULL,
    qty           INTEGER       NOT NULL,
    unit_cost     NUMERIC(12,2)
);
CREATE INDEX IF NOT EXISTS idx_book_receipt_lines_receipt ON book_receipt_lines (receipt_id);

CREATE TABLE IF NOT EXISTS book_supplier_payments (
    id                   BIGSERIAL     PRIMARY KEY,
    uuid                 VARCHAR(36)   NOT NULL,
    receipt_id           BIGINT        NOT NULL,
    supplier_id          BIGINT        NOT NULL,
    amount               NUMERIC(14,2) NOT NULL,
    cash_register_id     BIGINT        NOT NULL,
    payment_method       VARCHAR(20)   NOT NULL,
    cash_part            NUMERIC(14,2),
    card_part            NUMERIC(14,2),
    cash_transaction_id  BIGINT,
    paid_at              DATE          NOT NULL,
    note                 TEXT,
    idempotency_key      VARCHAR(100),
    created_by           BIGINT,
    created_at           TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_book_supplier_payments_uuid ON book_supplier_payments (uuid);
CREATE UNIQUE INDEX IF NOT EXISTS ux_book_supplier_payments_idempotency_key ON book_supplier_payments (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_book_supplier_payments_receipt ON book_supplier_payments (receipt_id);
CREATE INDEX IF NOT EXISTS idx_book_supplier_payments_supplier ON book_supplier_payments (supplier_id);

-- ── 4. Sotuv, sovg'a, harakatlar ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS book_sales (
    id                   BIGSERIAL     PRIMARY KEY,
    uuid                 VARCHAR(36)   NOT NULL,
    receipt_number       VARCHAR(32)   NOT NULL,
    student_id           BIGINT,
    buyer_name           VARCHAR(150),
    buyer_phone          VARCHAR(30),
    total_amount         NUMERIC(14,2) NOT NULL,
    cash_register_id     BIGINT        NOT NULL,
    payment_method       VARCHAR(20)   NOT NULL,
    cash_part            NUMERIC(14,2),
    card_part            NUMERIC(14,2),
    cash_transaction_id  BIGINT,
    sale_date            DATE          NOT NULL,
    idempotency_key      VARCHAR(100),
    created_by           BIGINT,
    created_at           TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_book_sales_uuid ON book_sales (uuid);
CREATE UNIQUE INDEX IF NOT EXISTS ux_book_sales_receipt_number ON book_sales (receipt_number);
CREATE UNIQUE INDEX IF NOT EXISTS ux_book_sales_idempotency_key ON book_sales (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_book_sales_date ON book_sales (sale_date);
CREATE INDEX IF NOT EXISTS idx_book_sales_register ON book_sales (cash_register_id);
CREATE INDEX IF NOT EXISTS idx_book_sales_student ON book_sales (student_id);

CREATE TABLE IF NOT EXISTS book_sale_lines (
    id            BIGSERIAL     PRIMARY KEY,
    sale_id       BIGINT        NOT NULL,
    book_item_id  BIGINT        NOT NULL,
    kind          VARCHAR(20)   NOT NULL,
    qty           INTEGER       NOT NULL,
    unit_price    NUMERIC(12,2) NOT NULL,
    unit_cost     NUMERIC(12,2),
    line_total    NUMERIC(14,2) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_book_sale_lines_sale ON book_sale_lines (sale_id);

CREATE TABLE IF NOT EXISTS book_gifts (
    id                   BIGSERIAL     PRIMARY KEY,
    student_id           BIGINT        NOT NULL,
    student_group_id     BIGINT        NOT NULL,
    payment_id           BIGINT,
    level_id             BIGINT        NOT NULL,
    book_item_id         BIGINT        NOT NULL,
    first_visit_date     DATE          NOT NULL,
    first_visit_assumed  BOOLEAN       NOT NULL DEFAULT FALSE,
    unit_cost            NUMERIC(12,2),
    gift_date            DATE          NOT NULL,
    created_by           BIGINT,
    created_at           TIMESTAMP
);
-- Bir o'quvchiga bitta bepul kitob
CREATE UNIQUE INDEX IF NOT EXISTS ux_book_gifts_student ON book_gifts (student_id);

CREATE TABLE IF NOT EXISTS book_stock_movements (
    id            BIGSERIAL     PRIMARY KEY,
    book_item_id  BIGINT        NOT NULL,
    type          VARCHAR(20)   NOT NULL,          -- RECEIPT | SALE | GIFT | ADJUSTMENT
    qty_delta     INTEGER       NOT NULL,
    qty_after     INTEGER       NOT NULL,
    unit_cost     NUMERIC(12,2),
    ref_type      VARCHAR(30),
    ref_id        BIGINT,
    reason        TEXT,
    created_by    BIGINT,
    created_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_book_stock_movements_item ON book_stock_movements (book_item_id, created_at);

-- ── 5. cash_transactions bog'lanishlari ─────────────────────────────────────────────
ALTER TABLE cash_transactions ADD COLUMN IF NOT EXISTS book_sale_id BIGINT;
ALTER TABLE cash_transactions ADD COLUMN IF NOT EXISTS book_supplier_payment_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_cash_transactions_book_sale ON cash_transactions (book_sale_id);
CREATE INDEX IF NOT EXISTS idx_cash_transactions_book_supplier_payment ON cash_transactions (book_supplier_payment_id);

-- ── 6. Cheklovlar va FK lar ─────────────────────────────────────────────────────────
DO $$
BEGIN
    -- Katalog
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_book_items_stock_nonneg') THEN
        ALTER TABLE book_items ADD CONSTRAINT ck_book_items_stock_nonneg CHECK (stock_qty >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_book_items_amounts_nonneg') THEN
        ALTER TABLE book_items ADD CONSTRAINT ck_book_items_amounts_nonneg
            CHECK (sale_price >= 0 AND min_stock >= 0 AND (avg_cost IS NULL OR avg_cost >= 0));
    END IF;
    -- O'quv kitobi — darajasi bilan, badiiy — darajasiz
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_book_items_level_by_kind') THEN
        ALTER TABLE book_items ADD CONSTRAINT ck_book_items_level_by_kind
            CHECK ((kind = 'STUDY') = (level_id IS NOT NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_items_level') THEN
        ALTER TABLE book_items ADD CONSTRAINT fk_book_items_level
            FOREIGN KEY (level_id) REFERENCES study_levels(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_study_levels_gift_book') THEN
        ALTER TABLE study_levels ADD CONSTRAINT fk_study_levels_gift_book
            FOREIGN KEY (gift_book_item_id) REFERENCES book_items(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_groups_level') THEN
        ALTER TABLE groups ADD CONSTRAINT fk_groups_level FOREIGN KEY (level_id) REFERENCES study_levels(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_courses_level') THEN
        ALTER TABLE courses ADD CONSTRAINT fk_courses_level FOREIGN KEY (level_id) REFERENCES study_levels(id);
    END IF;

    -- Kirim va to'lov
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_book_receipts_amounts') THEN
        ALTER TABLE book_receipts ADD CONSTRAINT ck_book_receipts_amounts
            CHECK (paid_amount >= 0 AND (total_cost IS NULL OR (total_cost >= 0 AND paid_amount <= total_cost)));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_receipts_supplier') THEN
        ALTER TABLE book_receipts ADD CONSTRAINT fk_book_receipts_supplier
            FOREIGN KEY (supplier_id) REFERENCES book_suppliers(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_book_receipt_lines_qty') THEN
        ALTER TABLE book_receipt_lines ADD CONSTRAINT ck_book_receipt_lines_qty
            CHECK (qty > 0 AND (unit_cost IS NULL OR unit_cost >= 0));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_receipt_lines_receipt') THEN
        ALTER TABLE book_receipt_lines ADD CONSTRAINT fk_book_receipt_lines_receipt
            FOREIGN KEY (receipt_id) REFERENCES book_receipts(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_receipt_lines_item') THEN
        ALTER TABLE book_receipt_lines ADD CONSTRAINT fk_book_receipt_lines_item
            FOREIGN KEY (book_item_id) REFERENCES book_items(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_book_supplier_payments_amount') THEN
        ALTER TABLE book_supplier_payments ADD CONSTRAINT ck_book_supplier_payments_amount CHECK (amount > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_supplier_payments_receipt') THEN
        ALTER TABLE book_supplier_payments ADD CONSTRAINT fk_book_supplier_payments_receipt
            FOREIGN KEY (receipt_id) REFERENCES book_receipts(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_supplier_payments_supplier') THEN
        ALTER TABLE book_supplier_payments ADD CONSTRAINT fk_book_supplier_payments_supplier
            FOREIGN KEY (supplier_id) REFERENCES book_suppliers(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_supplier_payments_register') THEN
        ALTER TABLE book_supplier_payments ADD CONSTRAINT fk_book_supplier_payments_register
            FOREIGN KEY (cash_register_id) REFERENCES cash_registers(id);
    END IF;

    -- Sotuv
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_book_sales_buyer') THEN
        ALTER TABLE book_sales ADD CONSTRAINT ck_book_sales_buyer
            CHECK (student_id IS NOT NULL OR buyer_name IS NOT NULL);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_book_sales_total') THEN
        ALTER TABLE book_sales ADD CONSTRAINT ck_book_sales_total CHECK (total_amount > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_sales_student') THEN
        ALTER TABLE book_sales ADD CONSTRAINT fk_book_sales_student FOREIGN KEY (student_id) REFERENCES students(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_sales_register') THEN
        ALTER TABLE book_sales ADD CONSTRAINT fk_book_sales_register
            FOREIGN KEY (cash_register_id) REFERENCES cash_registers(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_book_sale_lines_amounts') THEN
        ALTER TABLE book_sale_lines ADD CONSTRAINT ck_book_sale_lines_amounts
            CHECK (qty > 0 AND unit_price >= 0 AND line_total >= 0 AND (unit_cost IS NULL OR unit_cost >= 0));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_sale_lines_sale') THEN
        ALTER TABLE book_sale_lines ADD CONSTRAINT fk_book_sale_lines_sale
            FOREIGN KEY (sale_id) REFERENCES book_sales(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_sale_lines_item') THEN
        ALTER TABLE book_sale_lines ADD CONSTRAINT fk_book_sale_lines_item
            FOREIGN KEY (book_item_id) REFERENCES book_items(id);
    END IF;

    -- Sovg'a
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_gifts_student') THEN
        ALTER TABLE book_gifts ADD CONSTRAINT fk_book_gifts_student FOREIGN KEY (student_id) REFERENCES students(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_gifts_item') THEN
        ALTER TABLE book_gifts ADD CONSTRAINT fk_book_gifts_item FOREIGN KEY (book_item_id) REFERENCES book_items(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_gifts_level') THEN
        ALTER TABLE book_gifts ADD CONSTRAINT fk_book_gifts_level FOREIGN KEY (level_id) REFERENCES study_levels(id);
    END IF;

    -- Harakatlar
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_book_stock_movements_qty') THEN
        ALTER TABLE book_stock_movements ADD CONSTRAINT ck_book_stock_movements_qty
            CHECK (qty_delta <> 0 AND qty_after >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_book_stock_movements_item') THEN
        ALTER TABLE book_stock_movements ADD CONSTRAINT fk_book_stock_movements_item
            FOREIGN KEY (book_item_id) REFERENCES book_items(id);
    END IF;

    -- Kassa bog'lanishlari
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cash_transactions_book_sale') THEN
        ALTER TABLE cash_transactions ADD CONSTRAINT fk_cash_transactions_book_sale
            FOREIGN KEY (book_sale_id) REFERENCES book_sales(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cash_transactions_book_supplier_payment') THEN
        ALTER TABLE cash_transactions ADD CONSTRAINT fk_cash_transactions_book_supplier_payment
            FOREIGN KEY (book_supplier_payment_id) REFERENCES book_supplier_payments(id);
    END IF;
END $$;

COMMIT;

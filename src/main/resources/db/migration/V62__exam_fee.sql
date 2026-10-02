-- Imtihon to'lovi va kassa (docs/design/leaves-exams-contracts.md §4, §7).
--
-- V61 dan KEYIN, QO'LDA bajariladi (Flyway yo'q). Idempotent: qayta bajarilsa hech narsa
-- o'zgarmaydi. Ilovani yangilashdan OLDIN bajarish tavsiya etiladi (ilova eski status/payment_status
-- matnlarini ham o'qiydi — ExamRegistrationStatus/ExamPaymentStatus.fromDb).
--
--   * exams.fee NUMERIC(12,2) NOT NULL DEFAULT 0 (0 — bepul), CHECK fee >= 0;
--   * exam_registrations: kassa yozuvlari, chek, Idempotency-Key, bekor qilish izi;
--   * status/payment_status normallashtiriladi: eski PENDING (preview davri, kassasiz) va kassasiz
--     "PAID" (amount_paid = 0 — eski kodda summa 0 bo'lganda PAID yozilardi) → FREE;
--   * UNIQUE(exam_id, student_id) → qisman (status <> 'CANCELLED'): bekor qilingandan keyin qayta yozilish;
--   * cash_transactions.exam_registration_id — kassa tarixidan yozilishga havola.
--
-- Tekshirish: docs/ops/prod-schema-check.sql — V62 bo'laklari "TO'LIQ" chiqishi kerak.

BEGIN;

-- ── 1. exams.fee ────────────────────────────────────────────────────────────────────────
ALTER TABLE exams ADD COLUMN IF NOT EXISTS fee NUMERIC(12,2);
UPDATE exams SET fee = 0 WHERE fee IS NULL;
ALTER TABLE exams ALTER COLUMN fee SET DEFAULT 0;
ALTER TABLE exams ALTER COLUMN fee SET NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'exams'::regclass AND conname = 'ck_exams_fee_nonnegative') THEN
        ALTER TABLE exams ADD CONSTRAINT ck_exams_fee_nonnegative CHECK (fee >= 0);
    END IF;
END $$;

-- ── 2. exam_registrations: yangi ustunlar ───────────────────────────────────────────────
ALTER TABLE exam_registrations ADD COLUMN IF NOT EXISTS cash_transaction_id        BIGINT;
ALTER TABLE exam_registrations ADD COLUMN IF NOT EXISTS refund_cash_transaction_id BIGINT;
ALTER TABLE exam_registrations ADD COLUMN IF NOT EXISTS receipt_number             VARCHAR(32);
ALTER TABLE exam_registrations ADD COLUMN IF NOT EXISTS idempotency_key            VARCHAR(64);
ALTER TABLE exam_registrations ADD COLUMN IF NOT EXISTS cancelled_at               TIMESTAMP;
ALTER TABLE exam_registrations ADD COLUMN IF NOT EXISTS cancelled_by               BIGINT;
ALTER TABLE exam_registrations ADD COLUMN IF NOT EXISTS cancel_reason              VARCHAR(500);
ALTER TABLE exam_registrations ADD COLUMN IF NOT EXISTS created_by                 BIGINT;

-- ── 3. Holatlarni normallashtirish ──────────────────────────────────────────────────────
DO $$
DECLARE
    odd TEXT;
    n   INTEGER;
BEGIN
    SELECT string_agg(DISTINCT COALESCE(status, 'NULL'), ', ') INTO odd
      FROM exam_registrations
     WHERE status IS NULL OR UPPER(TRIM(status)) NOT IN ('REGISTERED', 'CANCELLED', 'ATTENDED', 'ABSENT');
    IF odd IS NOT NULL THEN
        RAISE NOTICE 'exam_registrations.status: noma''lum qiymatlar REGISTERED ga o''tkazildi: %', odd;
    END IF;

    SELECT COUNT(*) INTO n
      FROM exam_registrations
     WHERE UPPER(TRIM(COALESCE(payment_status, ''))) = 'PENDING' AND COALESCE(amount_due, 0) > 0;
    IF n > 0 THEN
        RAISE NOTICE 'exam_registrations: % ta eski PENDING (amount_due > 0, kassaga tushmagan) FREE ga o''tkazildi — kerak bo''lsa qo''lda ko''ring', n;
    END IF;
END $$;

UPDATE exam_registrations
   SET status = CASE WHEN UPPER(TRIM(status)) IN ('REGISTERED', 'CANCELLED', 'ATTENDED', 'ABSENT')
                     THEN UPPER(TRIM(status)) ELSE 'REGISTERED' END
 WHERE status IS NULL OR status <> UPPER(TRIM(status))
    OR UPPER(TRIM(status)) NOT IN ('REGISTERED', 'CANCELLED', 'ATTENDED', 'ABSENT');

UPDATE exam_registrations
   SET payment_status = CASE
           WHEN UPPER(TRIM(payment_status)) = 'REFUNDED' THEN 'REFUNDED'
           WHEN UPPER(TRIM(payment_status)) = 'PAID'
                AND (cash_transaction_id IS NOT NULL OR COALESCE(amount_paid, 0) > 0) THEN 'PAID'
           ELSE 'FREE' END
 WHERE payment_status IS NULL
    OR payment_status NOT IN ('FREE', 'PAID', 'REFUNDED')
    OR (payment_status = 'PAID' AND cash_transaction_id IS NULL AND COALESCE(amount_paid, 0) = 0);

-- ── 4. UNIQUE(exam_id, student_id) → qisman (CANCELLED dan tashqari) ─────────────────────
DO $$
DECLARE
    r   RECORD;
    dup TEXT;
BEGIN
    -- V27 dagi UNIQUE cheklov yoki V58 dagi to'liq UNIQUE indeks — olib tashlanadi
    FOR r IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'exam_registrations'::regclass AND c.contype = 'u'
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                  FROM pg_attribute a
                 WHERE a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)) = ARRAY['exam_id', 'student_id']
    LOOP
        EXECUTE format('ALTER TABLE exam_registrations DROP CONSTRAINT %I', r.conname);
    END LOOP;
    FOR r IN
        SELECT i.indexrelid::regclass::text AS name
          FROM pg_index i
         WHERE i.indrelid = 'exam_registrations'::regclass AND i.indisunique AND NOT i.indisprimary
           AND i.indpred IS NULL
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                  FROM pg_attribute a
                 WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)) = ARRAY['exam_id', 'student_id']
    LOOP
        EXECUTE format('DROP INDEX %s', r.name);
    END LOOP;

    IF to_regclass('public.ux_exam_registrations_active') IS NULL THEN
        SELECT string_agg(exam_id || '/' || student_id, ', ')
          INTO dup
          FROM (SELECT exam_id, student_id FROM exam_registrations
                 WHERE status <> 'CANCELLED'
                 GROUP BY exam_id, student_id HAVING COUNT(*) > 1) d;
        IF dup IS NOT NULL THEN
            RAISE NOTICE 'exam_registrations: faol dublikatlar bor (exam/student: %) — qisman UNIQUE yaratilmadi. Ortiqchasini bekor qilib, skriptni qayta ishga tushiring.', dup;
        ELSE
            CREATE UNIQUE INDEX ux_exam_registrations_active
                ON exam_registrations (exam_id, student_id) WHERE status <> 'CANCELLED';
        END IF;
    END IF;
END $$;

-- Idempotency-Key UNIQUE (NULL lar ko'p bo'lishi mumkin); Hibernate yaratgani bo'lsa — yangisi yo'q
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_index i
         WHERE i.indrelid = 'exam_registrations'::regclass AND i.indisunique AND i.indpred IS NULL
           AND (SELECT array_agg(a.attname::text)
                  FROM pg_attribute a
                 WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)) = ARRAY['idempotency_key']
    ) THEN
        CREATE UNIQUE INDEX ux_exam_registrations_idempotency ON exam_registrations (idempotency_key);
    END IF;
END $$;

-- FK lar (ustunda hech qanday FK bo'lmasa — Hibernate yaratgani bilan dublikat bo'lmasin)
DO $$
DECLARE
    fk RECORD;
BEGIN
    FOR fk IN SELECT * FROM (VALUES
            ('cancelled_by', 'users', 'fk_exam_registrations_cancelled_by'),
            ('created_by',   'users', 'fk_exam_registrations_created_by')
        ) AS t(col, ref, name)
    LOOP
        IF NOT EXISTS (
            SELECT 1
              FROM pg_constraint c
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
             WHERE c.conrelid = 'exam_registrations'::regclass AND c.contype = 'f' AND a.attname = fk.col
        ) THEN
            EXECUTE format('ALTER TABLE exam_registrations ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(id) ON DELETE SET NULL',
                           fk.name, fk.col, fk.ref);
        END IF;
    END LOOP;
END $$;

-- ── 5. cash_transactions.exam_registration_id ──────────────────────────────────────────
ALTER TABLE cash_transactions ADD COLUMN IF NOT EXISTS exam_registration_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_cash_transactions_exam_registration
    ON cash_transactions (exam_registration_id) WHERE exam_registration_id IS NOT NULL;

COMMIT;

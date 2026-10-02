-- Payroll v2 (docs/design/payroll-v2.md §10).
--
-- V52/V53 kabi QO'LDA bajariladi (Flyway yo'q): V53 dan KEYIN va yangi jar deploy qilinishidan
-- OLDIN. Hammasi IF NOT EXISTS / "hali to'ldirilmagan" sharti bilan — qayta ishga tushirishga
-- bardoshli. Ledger va kassaning pul maydonlariga tegilmaydi (faqat atribut metama'lumot).
--
-- Nega kerak: `ddl-auto: update` yangi ustunlarni o'zi qo'shadi, lekin
--   * mavjud davr/darslarga o'qituvchini yozmaydi (teacher_id backfill, "taxminiy");
--   * payroll.status PENDING → DRAFT, eski PAID ga paid_at;
--   * qisman UNIQUE (CANCELLED dan keyin qayta generate) ni yaratmaydi;
--   * v1 da DRAFT/o'chirilgan oylikka "yopishib" qolgan bonuslarni qaytarmaydi (audit #19).

BEGIN;

-- ── 1. billing_periods: davr yozilgan paytdagi o'qituvchi (§8) ───────
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS teacher_id     BIGINT;
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS teacher_source VARCHAR(20);
-- Mavjud davrlar: o'sha paytdagi o'qituvchi noma'lum — hozirgisi, ESTIMATED ("taxminiy")
UPDATE billing_periods bp
   SET teacher_id = g.teacher_id, teacher_source = 'ESTIMATED'
  FROM student_groups sg
  JOIN groups g ON g.id = sg.group_id
 WHERE sg.id = bp.student_group_id
   AND bp.teacher_source IS NULL;
CREATE INDEX IF NOT EXISTS idx_billing_periods_teacher_paid ON billing_periods (teacher_id, paid_on);

-- ── 2. balance_transactions: PER_LESSON darsi o'qituvchisi (§8) ───────
ALTER TABLE balance_transactions ADD COLUMN IF NOT EXISTS teacher_id     BIGINT;
ALTER TABLE balance_transactions ADD COLUMN IF NOT EXISTS teacher_source VARCHAR(20);
UPDATE balance_transactions bt
   SET teacher_id = g.teacher_id, teacher_source = 'ESTIMATED'
  FROM student_groups sg
  JOIN groups g ON g.id = sg.group_id
 WHERE sg.id = bt.student_group_id
   AND bt.type = 'LESSON_CHARGE'
   AND bt.teacher_source IS NULL;
CREATE INDEX IF NOT EXISTS idx_balance_tx_lesson_teacher ON balance_transactions (teacher_id, effective_date)
    WHERE type = 'LESSON_CHARGE';

-- ── 3. payroll: holatlar va amal izlari (§1, §5) ──────────────────────
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS calc_version        INTEGER;
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS approved_at         TIMESTAMP;
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS approved_by         BIGINT REFERENCES users(id);
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS paid_at             TIMESTAMP;
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS paid_by             BIGINT REFERENCES users(id);
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS cancelled_at        TIMESTAMP;
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS cancelled_by        BIGINT REFERENCES users(id);
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS cancel_reason       VARCHAR(500);
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS cash_transaction_id BIGINT;
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS pay_idempotency_key VARCHAR(64);

-- v1: "PENDING" (yoki erkin matn) → DRAFT; PAID saqlanadi. calc_version NULL — v1 qoralamasi
-- tasdiqlashdan oldin qayta hisoblanishi shart (payroll.recalculateRequired).
UPDATE payroll SET status = UPPER(TRIM(status)) WHERE status IS NOT NULL AND status <> UPPER(TRIM(status));
UPDATE payroll SET status = 'DRAFT'
 WHERE status IS NULL OR status NOT IN ('DRAFT', 'APPROVED', 'PAID', 'CANCELLED');
ALTER TABLE payroll ALTER COLUMN status SET DEFAULT 'DRAFT';
UPDATE payroll SET paid_at = payment_date::timestamp
 WHERE status = 'PAID' AND paid_at IS NULL AND payment_date IS NOT NULL;

-- ── 4. Faol payroll bitta: CANCELLED dan keyin yangi DRAFT mumkin ─────
-- Eski indeks (V40) xuddi shu ustunlar bo'yicha shartsiz edi — dublikat bo'lishi mumkin emas.
DROP INDEX IF EXISTS uk_payroll_user_month_year;
CREATE UNIQUE INDEX IF NOT EXISTS uk_payroll_user_month_year_active
    ON payroll (user_id, month, year)
    WHERE user_id IS NOT NULL AND status <> 'CANCELLED';

-- ── 5. Kassa ↔ payroll (§5.2–5.3) ─────────────────────────────────────
ALTER TABLE cash_transactions ADD COLUMN IF NOT EXISTS payroll_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_cash_transactions_payroll ON cash_transactions (payroll_id);
-- v1 da to'langan oylik: izoh bo'yicha AYNAN BITTA mos chiqim bo'lsa bog'lanadi (bekor qilishda
-- teskari yoziladi). Bir nechta / yo'q — bog'lanmaydi, bekor qilish 400 payroll.cancel.legacyCash.
UPDATE payroll p
   SET cash_transaction_id = m.ct_id
  FROM (SELECT p2.id AS payroll_id, MIN(ct.id) AS ct_id, COUNT(*) AS n
          FROM payroll p2
          JOIN cash_transactions ct
            ON ct.teacher_id = p2.teacher_id
           AND ct.cash_register_id = p2.cash_register_id
           AND ct.type = 'EXPENSE'
           AND ct.note LIKE '%Oylik to''lovi (' || p2.month || '/' || p2.year || ')%'
         WHERE p2.status = 'PAID' AND p2.cash_transaction_id IS NULL
         GROUP BY p2.id) m
 WHERE p.id = m.payroll_id AND m.n = 1;
UPDATE cash_transactions ct
   SET payroll_id = p.id
  FROM payroll p
 WHERE p.cash_transaction_id = ct.id AND ct.payroll_id IS NULL;

-- ── 6. Bonuslar (§4, audit #19) ───────────────────────────────────────
-- v2 da bonus faqat APPROVE da APPLIED bo'ladi. v1 da DRAFT (PENDING) oylikka yoki keyin
-- o'chirilgan oylikka qo'llangan TEACHER bonuslari — PENDING ga qaytadi (qayta hisobda yana ko'rinadi).
UPDATE bonus_penalties bp
   SET status = 'PENDING', applied_to_payroll_id = NULL
 WHERE bp.target_type = 'TEACHER'
   AND bp.status = 'APPLIED'
   AND bp.applied_to_payroll_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM payroll p
                    WHERE p.id = bp.applied_to_payroll_id AND p.status IN ('APPROVED', 'PAID'));

COMMIT;

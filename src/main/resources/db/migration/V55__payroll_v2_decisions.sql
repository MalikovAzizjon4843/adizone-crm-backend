-- Payroll v2 — §11 qarorlari (docs/design/payroll-v2.md §11).
--
-- V54 dan KEYIN, yangi jar deploy qilinishidan OLDIN, QO'LDA bajariladi. Hammasi IF NOT EXISTS /
-- "hali to'ldirilmagan" sharti bilan — qayta ishga tushirishga bardoshli, faqat qo'shimcha.

BEGIN;

-- ── #3: qoida muddati va ishlatilgan qoida ────────────────────────────
ALTER TABLE salary_rules ADD COLUMN IF NOT EXISTS effective_to DATE;
ALTER TABLE payroll      ADD COLUMN IF NOT EXISTS salary_rule_id BIGINT;
-- v2 hisobidagi qoida id si calculation_details.rule.id da (v1 yozuvlarida rule yo'q — NULL qoladi)
UPDATE payroll
   SET salary_rule_id = (calculation_details::jsonb -> 'rule' ->> 'id')::bigint
 WHERE calc_version = 2
   AND salary_rule_id IS NULL
   AND calculation_details IS NOT NULL
   AND calculation_details::jsonb -> 'rule' ->> 'id' IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_payroll_salary_rule ON payroll (salary_rule_id);

-- ── #2: PER_LESSON ulushi — birliklar kasr bo'lishi mumkin ────────────
ALTER TABLE payroll ADD COLUMN IF NOT EXISTS paid_student_units NUMERIC(10,4);
UPDATE payroll SET paid_student_units = paid_student_count
 WHERE calc_version = 2 AND paid_student_units IS NULL AND paid_student_count IS NOT NULL;

-- ── #5: STAFF (ADMIN/SALES) bonus/jarimasi ────────────────────────────
ALTER TABLE bonus_penalties ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES users(id);
CREATE INDEX IF NOT EXISTS idx_bonus_penalties_user_status ON bonus_penalties (user_id, status);
-- target_type varchar(20): STAFF qiymati uchun DDL kerak emas
-- (EnumCheckConstraintCleaner eski CHECK larni startup'da olib tashlaydi).

-- ── #8: moliya hisoboti — PAID oyliklar paid_at bo'yicha ──────────────
CREATE INDEX IF NOT EXISTS idx_payroll_paid_at ON payroll (paid_at) WHERE status = 'PAID';

COMMIT;

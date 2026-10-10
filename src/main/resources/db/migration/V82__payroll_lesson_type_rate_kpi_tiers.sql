-- Oylik v3 (buyurtmachi qarorlari 2026-10-10; payroll-v2.md §14). Oktyabr 2026 oyligidan kuchga kiradi
-- (app.payroll.v3-from = 2026-10): undan oldingi oylar eski (v2) formula bilan hisoblanadi.
--   1. payroll.teacher_id NULL bo'la oladi (admin/sotuv oyligi) — prod'da qo'lda qilingan, endi repoda ham;
--   2. dars turi: groups.lesson_type (GROUP | INDIVIDUAL), courses.lesson_type (yangi guruh uchun standart),
--      billing_periods.lesson_type (accrual paytidagi snapshot; NULL — guruhnikidan);
--   3. salary_rules: per_student_individual (individual o'quvchi ulushi; per_student_fee — guruh o'quvchisi),
--      employment_rate (stavka: fiksa × stavka);
--   4. salary_rule_kpi_tiers — KPI pog'onalari, shart qat'iy "faol > threshold". Mavjud kpi_threshold / kpi_bonus
--      (eski shart "≥") bitta pog'onaga threshold − 1 bilan ko'chiriladi — natija o'zgarmaydi. Eski ustunlar
--      tarix uchun qoladi (yangi qoidalarda yozilmaydi).
-- Mavjud guruhlar GROUP bo'ladi; individual guruhlarni belgilash — docs/ops/group-lesson-type-backfill.sql (alohida).
--
-- V81 dan KEYIN, QO'LDA, crm_user bilan, psql -X -v ON_ERROR_STOP=1. Ilova yangi versiyasidan OLDIN bajaring.
-- Idempotent (IF NOT EXISTS / NOT EXISTS / DO-bloklar) — qayta bajarilsa hech narsa o'zgarmaydi.
-- CHECK lar ataylab "= ... OR ..." ko'rinishida: "IN (...)" Postgres'da "= ANY (ARRAY[...])" bo'lib qoladi va
-- EnumCheckConstraintCleaner uni startup'da o'chirib yuboradi.
-- Tekshirish: docs/ops/prod-schema-check.sql — V82 bo'lagi "TO'LIQ".

BEGIN;

-- ── 1. payroll: egasi — o'qituvchi yoki foydalanuvchi ──────────────────
ALTER TABLE payroll ALTER COLUMN teacher_id DROP NOT NULL;

DO $$
BEGIN
    -- Prod'da qo'lda payroll_owner_chk nomi bilan qo'shilgan; nomi boshqacha bo'lsa ham ta'rifi bo'yicha topiladi
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
         WHERE c.conrelid = 'payroll'::regclass AND c.contype = 'c'
           AND (c.conname = 'payroll_owner_chk'
                OR (pg_get_constraintdef(c.oid) LIKE '%teacher_id IS NOT NULL%'
                    AND pg_get_constraintdef(c.oid) LIKE '%user_id IS NOT NULL%'))
    ) THEN
        ALTER TABLE payroll ADD CONSTRAINT payroll_owner_chk CHECK (teacher_id IS NOT NULL OR user_id IS NOT NULL);
    END IF;
END $$;

-- ── 2. Dars turi ─────────────────────────────────────────────────────
ALTER TABLE groups ADD COLUMN IF NOT EXISTS lesson_type VARCHAR(20) NOT NULL DEFAULT 'GROUP';
ALTER TABLE courses ADD COLUMN IF NOT EXISTS lesson_type VARCHAR(20);
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS lesson_type VARCHAR(20);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'groups_lesson_type_chk') THEN
        ALTER TABLE groups ADD CONSTRAINT groups_lesson_type_chk
            CHECK (lesson_type = 'GROUP' OR lesson_type = 'INDIVIDUAL');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'courses_lesson_type_chk') THEN
        ALTER TABLE courses ADD CONSTRAINT courses_lesson_type_chk
            CHECK (lesson_type IS NULL OR lesson_type = 'GROUP' OR lesson_type = 'INDIVIDUAL');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'billing_periods_lesson_type_chk') THEN
        ALTER TABLE billing_periods ADD CONSTRAINT billing_periods_lesson_type_chk
            CHECK (lesson_type IS NULL OR lesson_type = 'GROUP' OR lesson_type = 'INDIVIDUAL');
    END IF;
END $$;

-- ── 3. salary_rules: individual ulush va stavka ──────────────────────
ALTER TABLE salary_rules ADD COLUMN IF NOT EXISTS per_student_individual NUMERIC(12, 2);
ALTER TABLE salary_rules ADD COLUMN IF NOT EXISTS employment_rate NUMERIC(3, 2) NOT NULL DEFAULT 1.00;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'salary_rules_employment_rate_chk') THEN
        ALTER TABLE salary_rules ADD CONSTRAINT salary_rules_employment_rate_chk
            CHECK (employment_rate > 0 AND employment_rate <= 1);
    END IF;
END $$;

-- ── 4. KPI pog'onalari ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS salary_rule_kpi_tiers (
    id             BIGSERIAL PRIMARY KEY,
    salary_rule_id BIGINT         NOT NULL REFERENCES salary_rules (id),
    threshold      INTEGER        NOT NULL CHECK (threshold >= 0),
    bonus          NUMERIC(12, 2) NOT NULL CHECK (bonus >= 0)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_salary_rule_kpi_tiers_rule_threshold
    ON salary_rule_kpi_tiers (salary_rule_id, threshold);

-- Eski bitta pog'ona: "faol ≥ N" ⇔ "faol > N − 1" (N = 0 → 0: kamida bitta faol). Pog'onasi allaqachon bor
-- qoidaga tegilmaydi.
INSERT INTO salary_rule_kpi_tiers (salary_rule_id, threshold, bonus)
SELECT r.id, GREATEST(r.kpi_threshold - 1, 0), COALESCE(r.kpi_bonus, 0)
  FROM salary_rules r
 WHERE r.kpi_threshold IS NOT NULL AND COALESCE(r.kpi_bonus, 0) > 0
   AND NOT EXISTS (SELECT 1 FROM salary_rule_kpi_tiers t WHERE t.salary_rule_id = r.id);

COMMIT;

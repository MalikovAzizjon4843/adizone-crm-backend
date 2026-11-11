-- Oylik v3 KPI kengaytmasi (buyurtmachi 2026-11; payroll-v2.md §14 "KPI metrikasi"):
--   1. salary_rules.kpi_metric — KPI nima bo'yicha sanaladi:
--        ACTIVE_STUDENTS — oy oxiridagi faol o'quvchilar (V82 ma'nosi, standart);
--        NEW_STUDENTS    — oy davomida MARKAZGA kelgan yangi o'quvchilar (attributed_user bo'yicha emas).
--      Mavjud qoidalar ACTIVE_STUDENTS bo'ladi — admin KPI natijasi o'zgarmaydi.
--   2. salary_rule_kpi_tiers.inclusive — pog'ona sharti: false — "qiymat > threshold" (V82 ma'nosi, standart),
--      true — "qiymat ≥ threshold". Mavjud pog'onalar false bo'ladi — natija o'zgarmaydi.
-- KPI pog'onalari endi ADMIN va SALES_HEAD uchun (SALES_MANAGER, TEACHER — yo'q, API 400).
--
-- V87 dan KEYIN, QO'LDA, crm_user bilan, psql -X -v ON_ERROR_STOP=1. Ilova yangi versiyasidan OLDIN bajaring.
-- Idempotent (IF NOT EXISTS / DO-bloklar) — qayta bajarilsa hech narsa o'zgarmaydi.
-- CHECK ataylab "= ... OR ..." ko'rinishida: "IN (...)" Postgres'da "= ANY (ARRAY[...])" bo'lib qoladi va
-- EnumCheckConstraintCleaner uni startup'da o'chirib yuboradi.
-- Tekshirish: docs/ops/prod-schema-check.sql — V88 bo'lagi "TO'LIQ".

BEGIN;

-- ── 1. salary_rules.kpi_metric ───────────────────────────────────────
ALTER TABLE salary_rules ADD COLUMN IF NOT EXISTS kpi_metric VARCHAR(30) NOT NULL DEFAULT 'ACTIVE_STUDENTS';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'salary_rules_kpi_metric_chk') THEN
        ALTER TABLE salary_rules ADD CONSTRAINT salary_rules_kpi_metric_chk
            CHECK (kpi_metric = 'ACTIVE_STUDENTS' OR kpi_metric = 'NEW_STUDENTS');
    END IF;
END $$;

-- ── 2. salary_rule_kpi_tiers.inclusive ───────────────────────────────
ALTER TABLE salary_rule_kpi_tiers ADD COLUMN IF NOT EXISTS inclusive BOOLEAN NOT NULL DEFAULT false;

-- ── 3. Ogohlantirish: ADMIN bo'lmagan qoidalardagi pog'onalar ──────────
-- V82 eski kpi_threshold/kpi_bonus ni rolidan qat'i nazar ko'chirgan. Hozirgacha ADMIN dan boshqa rolda pog'ona
-- hisobga olinmas edi; endi SALES_HEAD da ishlaydi. Shunday qatorlar bo'lsa — ko'rib chiqing (ma'lumot o'zgartirilmaydi).
DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT count(DISTINCT r.id) INTO n
      FROM salary_rules r JOIN salary_rule_kpi_tiers t ON t.salary_rule_id = r.id
     WHERE r.role <> 'ADMIN';
    -- RAISE formati — bitta literal (qo'shni literallar faqat yangi qator bilan birlashadi; skript yuklovchilari
    -- qatorlarni bitta qatorga yig'ishi mumkin).
    IF n > 0 THEN
        RAISE NOTICE 'V88: % ta ADMIN bo''lmagan qoidada KPI pog''onasi bor (SALES_HEAD da endi qo''llanadi)', n;
        RAISE NOTICE 'Ko''rish: SELECT r.id, r.role, t.threshold, t.bonus, t.inclusive FROM salary_rules r JOIN salary_rule_kpi_tiers t ON t.salary_rule_id = r.id WHERE r.role <> ''ADMIN'';';
    END IF;
END $$;

COMMIT;

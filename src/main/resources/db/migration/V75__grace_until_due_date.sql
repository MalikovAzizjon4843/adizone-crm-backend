-- billing_periods.grace_until = due_date — buyurtmachi qarori 2026-10-04 (billing-v2 §14.1, QAROR 2).
--
-- grace = 0 (app.billing.grace-days standarti): "o'z vaqtida to'lov" = muddat kunigacha to'langan. Yangi davrlarda
-- PeriodCoverageService grace_until = due_date + grace ni o'zi yozadi; bu skript mavjud qatorlardagi eski +3 kunni
-- tuzatadi. due_date hali to'ldirilmagan qatorga PeriodCoverageService qoidasi qo'llanadi: due_date = period_start.
-- Ledger, balans, qarz va holatlarga TEGMAYDI — faqat "o'z vaqtida / kechikkan" toifasi (direktor dashboardi
-- "Muddati kelgan to'lovlar", o'qituvchi KPI "o'z vaqtida to'lov") o'zgaradi.
--
-- Keyin: yopilgan oylarning KPI snapshot'lari eski grace bilan hisoblangan — qayta hisoblang:
--   POST /api/teachers/kpi/snapshots?month=2026-09  (SA; har yopilgan oy uchun)
--
-- V74 dan KEYIN, QO'LDA, crm_user bilan, psql -v ON_ERROR_STOP=1. Idempotent: qayta bajarilsa 0 qator.
-- FAQAT app.billing.grace-days = 0 bo'lgan bazada (aks holda yangi davrlar boshqa grace bilan yoziladi).
--   Ko'rib chiqish (hech narsa yozmaydi; 2-bo'lim "read-only transaction" xatosi bilan to'xtaydi — kutilgan):
--     PGOPTIONS='-c default_transaction_read_only=on' psql ... -X -v ON_ERROR_STOP=1 -f V75__grace_until_due_date.sql
--   Qo'llash: psql ... -X -v ON_ERROR_STOP=1 -f V75__grace_until_due_date.sql
-- Tekshirish: docs/ops/prod-schema-check.sql — "V75 billing_periods.grace_until = due_date" = true.

-- ── 1. KO'RIB CHIQISH (faqat o'qiydi) ───────────────────────────────────────────────
-- 1a. `ozgaradi` — 2-bo'limdagi UPDATE soni (= `grace_farqli` + `due_yoq`); `due_yoq` — due_date to'ldirilmagan
--     qatorlar (period_start bilan to'ldiriladi).
SELECT COUNT(*) FILTER (WHERE due_date IS NULL OR grace_until IS DISTINCT FROM due_date) AS ozgaradi,
       COUNT(*) FILTER (WHERE due_date IS NOT NULL AND grace_until IS DISTINCT FROM due_date) AS grace_farqli,
       COUNT(*) FILTER (WHERE due_date IS NULL)                                               AS due_yoq,
       COUNT(*) FILTER (WHERE due_date IS NOT NULL AND grace_until = due_date)                AS allaqachon_mos,
       COUNT(*)                                                                               AS jami
  FROM billing_periods;

-- 1b. Farq taqsimoti oylar bo'yicha (kutilgan: asosan 3 kun). `ontime_dan_kechikkanga` — to'lovi due_date va eski
--     grace_until orasiga tushgan davrlar: ular "o'z vaqtida" dan "kechikkan" ga o'tadi.
SELECT to_char(COALESCE(due_date, period_start), 'YYYY-MM')                         AS oy,
       grace_until - COALESCE(due_date, period_start)                                AS farq_kun,
       COUNT(*)                                                                      AS davrlar,
       COUNT(*) FILTER (WHERE paid_on > COALESCE(due_date, period_start)
                          AND paid_on <= grace_until)                                AS ontime_dan_kechikkanga
  FROM billing_periods
 WHERE due_date IS NULL OR grace_until IS DISTINCT FROM due_date
 GROUP BY 1, 2
 ORDER BY 1, 2;

-- ── 2. QO'LLASH ─────────────────────────────────────────────────────────────────────
-- "UPDATE N" — 1a dagi `ozgaradi` ga teng bo'lishi kerak.
BEGIN;

UPDATE billing_periods
   SET due_date    = COALESCE(due_date, period_start),
       grace_until = COALESCE(due_date, period_start)
 WHERE due_date IS NULL
    OR grace_until IS DISTINCT FROM due_date;

COMMIT;

-- ── 3. TEKSHIRUV ────────────────────────────────────────────────────────────────────
SELECT COUNT(*) AS mos_emas_qoldi
  FROM billing_periods
 WHERE due_date IS NULL OR grace_until IS DISTINCT FROM due_date;

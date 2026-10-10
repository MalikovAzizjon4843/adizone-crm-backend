-- Oxirgi davr darslar bo'yicha (buyurtmachi qoidasi 2026-10-10). FAQAT sxema va bitta sozlama qatori, mavjud davrlarga tegmaydi.
--   Guruh davr o'rtasida tugasa (period_start ≤ groups.end_date < period_end), oxirgi davr summasi
--   min(c, dars narxi × darslar): dars narxi = c / billing.lessons_per_month (butun so'mga yaxlitlanadi), darslar —
--   guruh jadvali bo'yicha [period_start, end_date], bayramlar va bekor qilingan darslar chiqariladi.
--   billing_periods.prorated_lessons / lesson_price — shunday davrda to'ldiriladi, to'liq davrda NULL.
-- Mavjud (bu qoidadan oldin to'liq narx bilan yozilgan) oxirgi davrlar — POST /api/admin/repair/prorate-last-periods
-- (avval dryRun=true) orqali, alohida qaror bilan.
--
-- V80 dan KEYIN, QO'LDA, crm_user bilan, psql -X -v ON_ERROR_STOP=1. Ilova yangi versiyasidan OLDIN bajaring
-- (ddl-auto: update ustunlarni o'zi ham qo'shadi, lekin sozlama qatorini qo'shmaydi — kod u holda 12 ni oladi).
-- Idempotent (IF NOT EXISTS / NOT EXISTS) — qayta bajarilsa hech narsa o'zgarmaydi.
-- Tekshirish: docs/ops/prod-schema-check.sql — V81 bo'lagi "TO'LIQ".

BEGIN;

ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS prorated_lessons INTEGER;
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS lesson_price NUMERIC(12, 2);

-- Oyiga darslar soni (dars narxi = c / shu son). Qator bo'lmasa yoki qiymat musbat butun son bo'lmasa — kod 12 ni oladi.
INSERT INTO settings (setting_key, setting_value, description, updated_at)
SELECT 'billing.lessons_per_month', '12', 'Oxirgi davr dars narxi: oylik / shu son (buyurtmachi qoidasi 2026-10-10)', now()
WHERE NOT EXISTS (SELECT 1 FROM settings WHERE setting_key = 'billing.lessons_per_month');

COMMIT;

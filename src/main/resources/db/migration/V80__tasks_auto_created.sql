-- tasks.auto_created (2026-10-10) — vazifani tizim o'zi yaratgani belgisi. FAQAT sxema, ma'lumot UPDATE yo'q.
--   true: "Yangi lid: bog'lanish" (yangi lid bosqichi requires_task bo'lsa), Meta forma qo'ng'iroq vazifasi,
--         POST /api/admin/repair/leads-missing-tasks yaratgan "Bog'lanish".
--   Lid mas'uli o'zgarganda (PATCH /api/leads/{id}/assign) shu lidning OCHIQ auto_created vazifalari yangi
--   mas'ulga o'tadi; qo'lda yaratilgan vazifalar o'z egasida qoladi.
-- V79 dan keyin, ushbu deploydan OLDIN yaratilgan avtomatik vazifalar false bo'lib qoladi (qo'lda vazifa kabi
-- ko'chmaydi) — ularni belgilash kerak bo'lsa alohida, kelishilgan holda.
--
-- V79 dan KEYIN, QO'LDA, crm_user bilan, psql -X -v ON_ERROR_STOP=1. Ilova yangi versiyasidan OLDIN bajaring
-- (entity'da NOT NULL; ddl-auto: update ustunni o'zi qo'shsa ham DEFAULT @ColumnDefault dan keladi).
-- Idempotent (IF NOT EXISTS) — qayta bajarilsa hech narsa o'zgarmaydi.
-- Tekshirish: docs/ops/prod-schema-check.sql — V80 bo'lagi "TO'LIQ".

BEGIN;

ALTER TABLE tasks ADD COLUMN IF NOT EXISTS auto_created BOOLEAN NOT NULL DEFAULT false;

-- Mas'ul almashganda ko'chirish so'rovi (lead_id, status = OPEN, auto_created) idx_tasks_lead (lead_id, status, due_at)
-- bilan bajariladi — yangi indeks kerak emas.

COMMIT;

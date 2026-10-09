-- Ikki kichik sxema o'zgarishi (2026-10-09). FAQAT sxema — ma'lumot UPDATE yo'q:
--   1. lead_stages.requires_task — bosqichda lidning kamida 1 ta OCHIQ vazifasi bo'lishi shartmi (amoCRM qoidasi).
--      Faqat kind = OPEN bosqichlarda ishlaydi; CONVERTED/REJECTED da kod e'tiborsiz qoldiradi. Standart false —
--      hech bir bosqich o'zicha majburiy bo'lib qolmaydi, sozlash sahifasidan (PUT /api/lead-stages/{id}) yoqiladi.
--   2. students.source / students.source_note — o'quvchi qayerdan kelgan (lid bilan bir xil qiymatlar + REFERRAL,
--      WALK_IN, OTHER). Lid konvertatsiyasida kod lid manbasini ko'chiradi. Eski o'quvchilar uchun to'ldirish —
--      ALOHIDA, qo'lda: docs/ops/student-source-backfill.sql (avval preview, keyin UPDATE).
--
-- V78 dan KEYIN, QO'LDA, crm_user bilan, psql -X -v ON_ERROR_STOP=1. Ilova yangi versiyasidan OLDIN bajaring:
-- entity'da requires_task NOT NULL — ddl-auto: update ustunni o'zi qo'shsa ham DEFAULT shu skriptdan keladi.
-- Idempotent (IF NOT EXISTS) — qayta bajarilsa hech narsa o'zgarmaydi.
-- Tekshirish: docs/ops/prod-schema-check.sql — V79 bo'laklari "TO'LIQ".

BEGIN;

-- ── 1. Lid bosqichi: vazifa majburiyligi ────────────────────────────────────────────
ALTER TABLE lead_stages ADD COLUMN IF NOT EXISTS requires_task BOOLEAN NOT NULL DEFAULT false;

-- "Vazifasiz lidlar" hisobi (GET /api/leads/stats/without-task, kanban taskMissing) NOT EXISTS (ochiq vazifa)
-- so'rovini idx_tasks_lead (lead_id, status, due_at) bilan bajaradi — yangi indeks kerak emas.

-- ── 2. O'quvchi manbasi ─────────────────────────────────────────────────────────────
ALTER TABLE students ADD COLUMN IF NOT EXISTS source      VARCHAR(30);
ALTER TABLE students ADD COLUMN IF NOT EXISTS source_note VARCHAR(255);

-- GET /api/students?source=…, GET /api/analytics/students-by-source
CREATE INDEX IF NOT EXISTS idx_students_source ON students (source);

COMMIT;

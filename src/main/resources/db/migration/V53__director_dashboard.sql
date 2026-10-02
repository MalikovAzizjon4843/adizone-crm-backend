-- Direktor dashboardi (docs/design/director-dashboard.md §3).
--
-- V52 kabi QO'LDA bajariladi (Flyway yo'q), V52 dan KEYIN va yangi jar deploy qilinishidan
-- OLDIN: `ddl-auto: update` to'la jadvalga NOT NULL ustun (lead_stages.funnel_step)
-- qo'sha olmaydi. Hammasi IF NOT EXISTS — qayta ishga tushirishga bardoshli, faqat
-- QO'SHIMCHA (eski jar shu sxemada ishlaydi). V52 ga tegilmaydi.
--
-- Tarixiy ma'lumotni to'ldirish (G2–G6, G10) bu faylda EMAS: SA endpointi
-- POST /api/admin/dashboard/backfill (avval dry-run) — §2.2 "tarixni to'ldirish".

BEGIN;

-- ── G1: lid bosqichi voronka qadami (§7 #1 qarori) ──────────────────
ALTER TABLE lead_stages ADD COLUMN IF NOT EXISTS funnel_step VARCHAR(20) NOT NULL DEFAULT 'NONE';
UPDATE lead_stages SET funnel_step = 'CONTACTED' WHERE code = 'CONTACTED' AND funnel_step = 'NONE';
UPDATE lead_stages SET funnel_step = 'VISITED'
 WHERE code IN ('ONLINE_PAID', 'OFFLINE_PAID') AND funnel_step = 'NONE';
-- *_ENROLLED → NONE (o'zgarmaydi).
-- Yangi bosqich "Sinovga keldi" — CONTACTED dan keyin; keyingilar bir pog'ona suriladi.
DO $$
DECLARE pos INTEGER;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM lead_stages WHERE code = 'VISITED_TRIAL') THEN
    SELECT COALESCE((SELECT sort_order FROM lead_stages WHERE code = 'CONTACTED'), 1) INTO pos;
    UPDATE lead_stages SET sort_order = sort_order + 1 WHERE sort_order > pos;
    INSERT INTO lead_stages (uuid, code, name_uz, name_ru, name_en, color, sort_order, kind,
                             requires_amount, is_active, funnel_step, created_at, updated_at)
    VALUES (gen_random_uuid(), 'VISITED_TRIAL', 'Sinovga keldi', 'Пришёл на пробный', 'Came to trial',
            'info', pos + 1, 'OPEN', FALSE, TRUE, 'VISITED', now(), now());
  END IF;
END $$;

-- ── G2: lid qadam sanalari (birinchi kirish) ────────────────────────
ALTER TABLE leads ADD COLUMN IF NOT EXISTS contacted_at TIMESTAMP;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS visited_at   TIMESTAMP;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS converted_at TIMESTAMP;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS rejected_at  TIMESTAMP;

-- ── G3/G4: tayinlash tarixi va birinchi javob ───────────────────────
CREATE TABLE IF NOT EXISTS lead_assignments (
    id                  BIGSERIAL PRIMARY KEY,
    lead_id             BIGINT      NOT NULL REFERENCES leads(id),
    user_id             BIGINT      NOT NULL REFERENCES users(id),
    assigned_at         TIMESTAMP   NOT NULL,
    assigned_by         BIGINT      REFERENCES users(id),
    unassigned_at       TIMESTAMP,
    first_response_at   TIMESTAMP,
    first_response_kind VARCHAR(20),
    source              VARCHAR(20) NOT NULL DEFAULT 'LIVE'
);

-- ── G5: davr muddati va yopilishi ───────────────────────────────────
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS due_date        DATE;
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS grace_until     DATE;
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS paid_on         DATE;
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS paid_at         TIMESTAMP;
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS paid_tx_id      BIGINT;
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS coverage_source VARCHAR(20);
-- Deterministik: muddat = billing kuni, grace = app.billing.grace-days (3).
-- paid_on — FIFO replay (backfill endpointi yoki birinchi snapshot yangilanishi).
UPDATE billing_periods SET due_date = period_start WHERE due_date IS NULL;
UPDATE billing_periods SET grace_until = period_start + 3 WHERE grace_until IS NULL;

-- ── G6, G10: sinov va chiqish sababi ────────────────────────────────
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS trial_started_at   DATE;
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS trial_converted_at DATE;
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS trial_outcome      VARCHAR(20);
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS trial_source       VARCHAR(20);
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS exit_reason_code   VARCHAR(30);

-- ── G8: kalendar ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS holidays (
    holiday_date DATE PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL
);
CREATE TABLE IF NOT EXISTS lesson_exceptions (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT      NOT NULL REFERENCES groups(id),
    lesson_date DATE        NOT NULL,
    kind        VARCHAR(20) NOT NULL,
    moved_to    DATE,
    reason      VARCHAR(500),
    created_by  BIGINT,
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT uk_lesson_exceptions UNIQUE (group_id, lesson_date, kind)
);

-- ── G11: kunlik snapshot va Telegram jurnali ────────────────────────
CREATE TABLE IF NOT EXISTS director_daily_stats (
    stat_date   DATE        NOT NULL,
    section     VARCHAR(30) NOT NULL,
    payload     TEXT        NOT NULL,
    computed_at TIMESTAMP   NOT NULL,
    is_final    BOOLEAN     NOT NULL DEFAULT FALSE,
    version     INTEGER     NOT NULL DEFAULT 1,
    PRIMARY KEY (stat_date, section)
);
CREATE TABLE IF NOT EXISTS director_digest_log (
    id        BIGSERIAL PRIMARY KEY,
    stat_date DATE        NOT NULL,
    chat_id   VARCHAR(64) NOT NULL,
    sent_at   TIMESTAMP   NOT NULL,
    ok        BOOLEAN     NOT NULL,
    error     TEXT,
    CONSTRAINT uk_director_digest_log UNIQUE (stat_date, chat_id)
);

-- ── §6.2 indekslar ──────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_leads_created_at            ON leads (created_at);
CREATE INDEX IF NOT EXISTS idx_leads_contacted_at          ON leads (contacted_at);
CREATE INDEX IF NOT EXISTS idx_leads_visited_at            ON leads (visited_at);
CREATE INDEX IF NOT EXISTS idx_leads_converted_at          ON leads (converted_at);
CREATE INDEX IF NOT EXISTS idx_lead_status_history_changed ON lead_status_history (changed_at, to_status);
CREATE INDEX IF NOT EXISTS idx_lead_assignments_user       ON lead_assignments (user_id, assigned_at);
CREATE INDEX IF NOT EXISTS idx_lead_assignments_lead       ON lead_assignments (lead_id, assigned_at);
CREATE INDEX IF NOT EXISTS idx_tasks_completed             ON tasks (completed_by, completed_at);
CREATE INDEX IF NOT EXISTS idx_lead_comments_author        ON lead_comments (author_id, created_at);
CREATE INDEX IF NOT EXISTS idx_payments_student_paid       ON payments (student_id, status, payment_date);
CREATE INDEX IF NOT EXISTS idx_payments_received           ON payments (received_by, payment_date);
CREATE INDEX IF NOT EXISTS idx_billing_periods_due         ON billing_periods (due_date)
    WHERE status IN ('CHARGED', 'PARTIALLY_REFUNDED');
CREATE INDEX IF NOT EXISTS idx_billing_periods_paid_on     ON billing_periods (paid_on);
CREATE INDEX IF NOT EXISTS idx_attendance_group_date       ON attendance (group_id, attendance_date);
CREATE INDEX IF NOT EXISTS idx_student_groups_trial        ON student_groups (trial_started_at)
    WHERE trial_started_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_lesson_exceptions_date      ON lesson_exceptions (lesson_date);

COMMIT;

-- Telegram Mini App, bosqich 3 (docs/design/telegram-platform.md §11.1, §11.4):
--   * o'qituvchi rejimi — app_identities.staff_user_id;
--   * qo'lda raqam bilan ulash so'rovlari — app_link_requests.
--
-- V67 dan KEYIN, QO'LDA, crm_user bilan bajariladi (deploy-v2.md §3.3). Idempotent: qayta bajarilsa hech narsa
-- o'zgarmaydi. ddl-auto: update ustun/jadvallarni o'zi ham yaratadi — bu skript FK va indeks nomlarini aniq qiladi.

BEGIN;

-- ── 1. O'qituvchi rejimi: identity → TEACHER rolidagi xodim useri ───────────────────────────────
ALTER TABLE app_identities ADD COLUMN IF NOT EXISTS staff_user_id BIGINT;
-- Bitta xodim — bitta identity (NULL lar takror hisoblanmaydi)
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_identities_staff_user ON app_identities (staff_user_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'app_identities'::regclass AND c.contype = 'f'
           AND c.confrelid = 'users'::regclass AND a.attname = 'staff_user_id'
    ) THEN
        ALTER TABLE app_identities ADD CONSTRAINT fk_app_identities_staff_user
            FOREIGN KEY (staff_user_id) REFERENCES users(id) ON DELETE SET NULL;
    END IF;
END $$;

-- ── 2. Qo'lda ulash so'rovlari ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS app_link_requests (
    id                 BIGSERIAL     PRIMARY KEY,
    telegram_user_id   BIGINT        NOT NULL,
    chat_id            BIGINT,
    telegram_username  VARCHAR(64),
    first_name         VARCHAR(128),
    phone_canonical    VARCHAR(20)   NOT NULL,
    status             VARCHAR(20)   NOT NULL,       -- PENDING | APPROVED | REJECTED | CANCELLED
    match_summary      VARCHAR(1000),
    created_at         TIMESTAMP     NOT NULL,
    decided_by         BIGINT,                       -- users.id (FK emas: xodim soft-delete qilinadi)
    decided_at         TIMESTAMP,
    reject_reason      VARCHAR(500),
    identity_id        BIGINT                        -- tasdiqlanganda ulangan app_identities.id
);
CREATE INDEX IF NOT EXISTS idx_app_link_requests_status ON app_link_requests (status, created_at);
CREATE INDEX IF NOT EXISTS idx_app_link_requests_user ON app_link_requests (telegram_user_id, created_at);

COMMIT;

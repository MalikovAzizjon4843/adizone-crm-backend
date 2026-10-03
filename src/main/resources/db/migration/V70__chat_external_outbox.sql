-- Telegram Mini App, bosqich 3 (docs/design/telegram-platform.md §11.3, §11.5):
--   * chat ko'prigi — EXTERNAL suhbatlar (app foydalanuvchisi ↔ xodimlar);
--   * messages.sender_id NULLABLE + sender_app_identity_id (app foydalanuvchisi yozgan xabar);
--   * telegram_outbox — bot xabarlari navbati (sokin soatlar, dedupe).
--
-- V69 dan KEYIN, QO'LDA, crm_user bilan bajariladi (deploy-v2.md §3.3). Idempotent.
-- MUHIM: ilova (yangi kod) bilan birga — eski kod sender_id = NULL xabarni ko'tara olmaydi, lekin bunday xabarni
-- faqat yangi kod yozadi.

BEGIN;

-- ── 1. conversations: EXTERNAL ustunlari ────────────────────────────────────────────────────────
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS external_identity_id          BIGINT;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS external_target               VARCHAR(20);  -- TEACHER | SUPPORT | DIRECTOR
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS external_staff_user_id        BIGINT;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS external_key                  VARCHAR(80);
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS external_last_read_message_id BIGINT;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS status                        VARCHAR(10);  -- OPEN | CLOSED (NULL = OPEN)
CREATE UNIQUE INDEX IF NOT EXISTS ux_conversations_external_key ON conversations (external_key);
CREATE INDEX IF NOT EXISTS idx_conversations_external_identity ON conversations (external_identity_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'conversations'::regclass AND c.contype = 'f'
           AND c.confrelid = 'app_identities'::regclass AND a.attname = 'external_identity_id'
    ) THEN
        ALTER TABLE conversations ADD CONSTRAINT fk_conversations_external_identity
            FOREIGN KEY (external_identity_id) REFERENCES app_identities(id);
    END IF;
END $$;

-- ── 2. messages: app yuboruvchisi ───────────────────────────────────────────────────────────────
ALTER TABLE messages ALTER COLUMN sender_id DROP NOT NULL;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS sender_app_identity_id BIGINT;

DO $$
BEGIN
    -- Har xabarda yuboruvchi bor: xodim yoki app foydalanuvchisi
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'messages'::regclass AND conname = 'ck_messages_sender') THEN
        ALTER TABLE messages ADD CONSTRAINT ck_messages_sender
            CHECK (sender_id IS NOT NULL OR sender_app_identity_id IS NOT NULL);
    END IF;
END $$;

-- ── 3. telegram_outbox ───────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS telegram_outbox (
    id            BIGSERIAL    PRIMARY KEY,
    chat_id       BIGINT       NOT NULL,
    text          TEXT         NOT NULL,
    reply_markup  TEXT,
    silent        BOOLEAN      NOT NULL,
    priority      VARCHAR(10)  NOT NULL,          -- HIGH | NORMAL
    status        VARCHAR(10)  NOT NULL,          -- PENDING | SENT | FAILED
    attempts      INTEGER      NOT NULL,
    not_before    TIMESTAMP    NOT NULL,
    dedupe_key    VARCHAR(120),
    event_code    VARCHAR(40),
    last_error    VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL,
    sent_at       TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_telegram_outbox_dedupe ON telegram_outbox (dedupe_key);
CREATE INDEX IF NOT EXISTS idx_telegram_outbox_due ON telegram_outbox (status, not_before);

COMMIT;

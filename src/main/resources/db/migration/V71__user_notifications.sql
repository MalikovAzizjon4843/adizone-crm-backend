-- Xodim bildirishnomalari — CRM qo'ng'iroqchasi (phase5-audit S-04; GET/POST /api/notifications/**,
-- STOMP /user/queue/notifications).
--
-- Legacy `notifications` jadvali (id, user_id, title, message, is_read, notification_type, reference_id,
-- reference_type, created_at) — kodsiz, 0 qator, read_at / link yo'q. ATAYLAB ishlatilmaydi va o'chirilmaydi
-- (phase5-audit X-06: alohida qaror). Yangi jadval — user_notifications.
--
-- V70 dan KEYIN, QO'LDA, crm_user bilan bajariladi (deploy-v2.md §3.3). Idempotent.

BEGIN;

CREATE TABLE IF NOT EXISTS user_notifications (
    id           BIGSERIAL     PRIMARY KEY,
    user_id      BIGINT        NOT NULL,
    type         VARCHAR(40)   NOT NULL,        -- APP_LINK_REQUEST | CHAT_EXTERNAL | ABSENCE_NOTICE | ...
    title        VARCHAR(255)  NOT NULL,
    body         VARCHAR(1000),
    link         VARCHAR(500),
    entity_type  VARCHAR(40),
    entity_id    BIGINT,
    read_at      TIMESTAMP,
    created_at   TIMESTAMP     NOT NULL
);
-- Ro'yxat: WHERE user_id = ? ORDER BY created_at DESC; tozalash: created_at < now - 90 kun
CREATE INDEX IF NOT EXISTS idx_user_notifications_user_created ON user_notifications (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_user_notifications_created ON user_notifications (created_at);
-- O'qilmaganlar soni (qo'ng'iroqcha belgisi)
CREATE INDEX IF NOT EXISTS idx_user_notifications_unread ON user_notifications (user_id) WHERE read_at IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'user_notifications'::regclass AND c.contype = 'f'
           AND c.confrelid = 'users'::regclass AND a.attname = 'user_id'
    ) THEN
        ALTER TABLE user_notifications ADD CONSTRAINT fk_user_notifications_user
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
END $$;

COMMIT;

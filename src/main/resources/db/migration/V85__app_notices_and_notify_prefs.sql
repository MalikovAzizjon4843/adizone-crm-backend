-- Mini App: e'lonlar va bildirishnoma sozlamalari (docs/design/notifications.md §6–§7, miniapp-api.md).
-- FAQAT sxema, ma'lumotga tegmaydi.
--   * app_notice_reads — o'quvchi / ota-ona (AppIdentity) e'lonni o'qigani; xodimlarniki — notice_reads (users).
--   * app_notification_prefs — Mini App foydalanuvchisi o'chirgan mavzular (absent | payment_overdue |
--     payment_due_soon | lesson | notice). Qator yo'q — yoqiq.
--   * E'lon auditoriyasi: notice_target_roles.role allaqachon STUDENT / PARENT ni qabul qiladi (V60, VARCHAR(30),
--     cheklov yo'q) — o'zgartirish kerak emas.
--
-- V84 dan KEYIN, QO'LDA, crm_user bilan, psql -X -v ON_ERROR_STOP=1. Idempotent (IF NOT EXISTS).
-- Tekshirish: docs/ops/prod-schema-check.sql — V85 bo'lagi "TO'LIQ".

BEGIN;

CREATE TABLE IF NOT EXISTS app_notice_reads (
    id           BIGSERIAL  PRIMARY KEY,
    notice_id    BIGINT     NOT NULL,
    identity_id  BIGINT     NOT NULL,
    read_at      TIMESTAMP  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_notice_reads_notice_identity ON app_notice_reads (notice_id, identity_id);
CREATE INDEX IF NOT EXISTS idx_app_notice_reads_identity ON app_notice_reads (identity_id);

CREATE TABLE IF NOT EXISTS app_notification_prefs (
    id           BIGSERIAL    PRIMARY KEY,
    identity_id  BIGINT       NOT NULL,
    topic        VARCHAR(30)  NOT NULL,
    enabled      BOOLEAN      NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_notification_prefs_identity_topic
    ON app_notification_prefs (identity_id, topic);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_app_notice_reads_notice') THEN
        ALTER TABLE app_notice_reads ADD CONSTRAINT fk_app_notice_reads_notice
            FOREIGN KEY (notice_id) REFERENCES notices(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_app_notice_reads_identity') THEN
        ALTER TABLE app_notice_reads ADD CONSTRAINT fk_app_notice_reads_identity
            FOREIGN KEY (identity_id) REFERENCES app_identities(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_app_notification_prefs_identity') THEN
        ALTER TABLE app_notification_prefs ADD CONSTRAINT fk_app_notification_prefs_identity
            FOREIGN KEY (identity_id) REFERENCES app_identities(id) ON DELETE CASCADE;
    END IF;
END $$;

COMMIT;

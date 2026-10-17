-- Yagona bildirishnoma xizmati (docs/design/notifications.md): NotificationService.dispatch → qo'ng'iroqcha
-- (user_notifications + STOMP) va bot (telegram_outbox, AppIdentity orqali). FAQAT sxema, ma'lumotga tegmaydi.
--   * user_notifications.dedupe_key — takroriy yuborishdan himoya: bitta xodimga bitta kalit bilan bitta qator
--     (masalan task:12:overdue:202610171500, notice:7). NULL — cheklovsiz (eski turlar).
--   * app_identities.language — bot xabarlari tili (uz | ru; NULL — uz). Mini App autentifikatsiyada yangilaydi.
--   * telegram_outbox: yangi holat CANCELLED (VARCHAR(10), cheklov yo'q — ustun o'zgarmaydi); 403 da shu chatning
--     PENDING qatorlari tez topilishi uchun qisman indeks.
--   * parents.telegram_chat_id — kod endi ISHLATMAYDI (hech kim to'ldirmagan). Ustun o'chirilmaydi (alohida qaror).
--
-- V83 dan KEYIN, QO'LDA, crm_user bilan, psql -X -v ON_ERROR_STOP=1. Idempotent (IF NOT EXISTS).
-- Tekshirish: docs/ops/prod-schema-check.sql — V84 bo'lagi "TO'LIQ".

BEGIN;

ALTER TABLE user_notifications ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(120);
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_notifications_dedupe
    ON user_notifications (user_id, dedupe_key) WHERE dedupe_key IS NOT NULL;

ALTER TABLE app_identities ADD COLUMN IF NOT EXISTS language VARCHAR(5);

CREATE INDEX IF NOT EXISTS idx_telegram_outbox_chat_pending
    ON telegram_outbox (chat_id) WHERE status = 'PENDING';

COMMIT;

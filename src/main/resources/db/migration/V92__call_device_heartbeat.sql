-- "Adizone Qo'ng'iroqlar": oxirgi heartbeat vaqti (docs/design/calls.md §7.4).
--   * last_heartbeat_at — POST /api/calls/app/heartbeat vaqti. perm_* ustunlari (READ_CALL_LOG, yozuvlar papkasi,
--     batareya optimizatsiyasi) shu paytdagi holat; last_seen_at esa har so'rovda yangilanadi. SA qurilmalar
--     ro'yxatida (GET /api/calls/devices) "ruxsatlar — <vaqt> holatiga" ko'rsatish uchun. Mavjud qatorlar — NULL.
--
-- V91 dan KEYIN, ILOVANI YANGI JAR BILAN ISHGA TUSHIRISHDAN OLDIN, QO'LDA, crm_user bilan:
--   psql -X -v ON_ERROR_STOP=1 -f V92__call_device_heartbeat.sql
-- Idempotent. (Bajarilmasa ham Hibernate ddl-auto: update ustunni o'zi qo'shadi — NULL ruxsat etilgan.)

BEGIN;

ALTER TABLE call_devices ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMP;

COMMIT;

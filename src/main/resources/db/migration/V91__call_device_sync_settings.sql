-- "Adizone Qo'ng'iroqlar": qurilma sinxron sozlamasi (docs/design/calls.md §3.3).
--   * sync_mode — ALL | KNOWN_ONLY. KNOWN_ONLY: CRM'da lid / o'quvchi / ota-onaga mos kelmagan qo'ng'iroq SAQLANMAYDI
--                 va avto-lid ochilmaydi (xodimning shaxsiy telefoni). Mavjud qurilmalar — ALL (avvalgi xatti-harakat).
--   * sync_from — bundan oldingi qo'ng'iroq sinxron qilinmaydi (Toshkent vaqti, zonasiz). Birinchi ro'yxatda — ilova
--                 tanlagan vaqt (standart — ro'yxat vaqti, ya'ni eski qo'ng'iroqlar olinmaydi). Mavjud qurilmalar —
--                 NULL (chegara yo'q, faqat app.calls.sync-max-age-days).
--
-- V90 dan KEYIN, ILOVANI YANGI JAR BILAN ISHGA TUSHIRISHDAN OLDIN, QO'LDA, crm_user bilan:
--   psql -X -v ON_ERROR_STOP=1 -f V91__call_device_sync_settings.sql
-- Idempotent — qayta bajarish xavfsiz. Hibernate (ddl-auto: update) ustunni oldin yaratgan bo'lsa ham ishlaydi.

BEGIN;

ALTER TABLE call_devices ADD COLUMN IF NOT EXISTS sync_mode VARCHAR(12);
ALTER TABLE call_devices ADD COLUMN IF NOT EXISTS sync_from TIMESTAMP;

UPDATE call_devices SET sync_mode = 'ALL' WHERE sync_mode IS NULL;
ALTER TABLE call_devices ALTER COLUMN sync_mode SET DEFAULT 'ALL';
ALTER TABLE call_devices ALTER COLUMN sync_mode SET NOT NULL;

DO $$
BEGIN
    -- "= ... OR ..." — "IN (...)" ni EnumCheckConstraintCleaner startup'da o'chirib yuboradi (V90 izohi)
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_call_devices_sync_mode') THEN
        ALTER TABLE call_devices ADD CONSTRAINT ck_call_devices_sync_mode
            CHECK (sync_mode = 'ALL' OR sync_mode = 'KNOWN_ONLY');
    END IF;
END $$;

COMMIT;

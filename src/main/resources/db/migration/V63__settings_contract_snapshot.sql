-- Markaz rekvizitlari (settings.center.*) va shartnoma narx snapshot'i / holatlari / PDF
-- (docs/design/leaves-exams-contracts.md §5, §6, §7).
--
-- V62 dan KEYIN, QO'LDA bajariladi (Flyway yo'q). Idempotent: qayta bajarilsa hech narsa
-- o'zgarmaydi — jadval/ustun/indeks IF NOT EXISTS, FK va UNIQUE oldindan tekshiruv bilan,
-- boshlang'ich qiymatlar ON CONFLICT DO NOTHING (SA qo'lda o'zgartirgan qiymat ustidan yozilmaydi).
-- Ilova yangilanishidan OLDIN ham, KEYIN ham to'g'ri (ddl-auto ustunlarni nullable qo'shadi).
--
-- contracts.status ga CANCELLED qo'shildi: Hibernate yaratgan enum CHECK bo'lsa uni
-- EnumCheckConstraintCleaner (app.schema.drop-enum-checks=true) startda o'chiradi.
--
-- Tekshirish: docs/ops/prod-schema-check.sql — V63 bo'laklari "TO'LIQ" chiqishi kerak.

BEGIN;

-- ── 1. settings (legacy jadval qayta ishlatiladi; yo'q bo'lsa yaratiladi) ───────────────
CREATE TABLE IF NOT EXISTS settings (
    id            BIGSERIAL PRIMARY KEY,
    setting_key   VARCHAR(100) NOT NULL,
    setting_value TEXT,
    description   VARCHAR(255),
    updated_by    BIGINT,
    updated_at    TIMESTAMP
);
ALTER TABLE settings ADD COLUMN IF NOT EXISTS description VARCHAR(255);
ALTER TABLE settings ADD COLUMN IF NOT EXISTS updated_by BIGINT;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

-- setting_key bo'yicha UNIQUE (legacy jadvalda va Hibernate yaratganida bor) — ON CONFLICT uchun shart
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_index i
         WHERE i.indrelid = 'settings'::regclass AND i.indisunique AND i.indpred IS NULL
           AND (SELECT array_agg(a.attname::text)
                  FROM pg_attribute a
                 WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)) = ARRAY['setting_key']
    ) THEN
        CREATE UNIQUE INDEX ux_settings_key ON settings (setting_key);
    END IF;
END $$;

-- Boshlang'ich rekvizitlar (buyurtmachi bergan qiymatlar). Faqat yo'q kalitlar qo'shiladi.
INSERT INTO settings (setting_key, setting_value, description, updated_at) VALUES
    ('center.legalName',     '"ADIZONE LC" MChJ',                                         'Yuridik nomi',                       CURRENT_TIMESTAMP),
    ('center.legalNameRu',   'ООО «ADIZONE LC»',                                          'Yuridik nomi (rus)',                 CURRENT_TIMESTAMP),
    ('center.shortName',     'Adizone',                                                   'Qisqa nomi',                         CURRENT_TIMESTAMP),
    ('center.inn',           '311626069',                                                 'STIR',                               CURRENT_TIMESTAMP),
    ('center.address',       'Toshkent sh., Chilonzor t., Novza MFY, Ye mavzesi, 10-uy',  'Yuridik manzil',                     CURRENT_TIMESTAMP),
    ('center.phone',         '+998 90 045 55 17',                                         'Telefon',                            CURRENT_TIMESTAMP),
    ('center.bankName',      'ОПЕРУ АКБ «Капитал Банк»',                                  'Bank',                               CURRENT_TIMESTAMP),
    ('center.bankMfo',       '00974',                                                     'MFO',                                CURRENT_TIMESTAMP),
    ('center.bankAccount',   '20208000007147330001',                                      'Hisob raqami (h/r)',                 CURRENT_TIMESTAMP),
    ('center.directorName',  'Adizov Oqilbek Oybek o''g''li',                             'Direktor',                           CURRENT_TIMESTAMP),
    ('center.contractCity',  'Toshkent shahri',                                           'Shartnoma tuzilgan joy',             CURRENT_TIMESTAMP),
    ('center.licenseInfo',   'Xabarnoma tasdiqnomasi №1180460 (reestr X-1743276)',        'Litsenziya / xabarnoma',             CURRENT_TIMESTAMP),
    ('center.supportPhone',  '+998 77 337 32 33',                                         'Yordam telefoni (Telegram Mini App)', CURRENT_TIMESTAMP)
ON CONFLICT (setting_key) DO NOTHING;

-- ── 2. contracts: narx snapshot'i, holatlar izi, muzlatilgan PDF ─────────────────────────
-- Eski shartnomalar snapshot'siz qoladi (NULL) — UI "—" ko'rsatadi; rendered_content da eski matn.
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS student_group_id BIGINT;
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS payment_type     VARCHAR(20);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS list_price       NUMERIC(12,2);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS discount_percent NUMERIC(5,2);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS discount_amount  NUMERIC(12,2);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS final_amount     NUMERIC(12,2);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS start_date       DATE;
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS signed_at        TIMESTAMP;
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS signed_by        BIGINT;
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS cancelled_at     TIMESTAMP;
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS cancelled_by     BIGINT;
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS cancel_reason    VARCHAR(500);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS pdf_file         VARCHAR(255);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS pdf_sha256       VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_contracts_student_group ON contracts (student_group_id);

-- FK lar: ustunda hech qanday FK bo'lmasa (Hibernate yaratgani ham hisobga olinadi — dublikat FK yo'q)
DO $$
DECLARE
    fk RECORD;
BEGIN
    FOR fk IN SELECT * FROM (VALUES
            ('student_group_id', 'student_groups', 'fk_contracts_student_group', 'SET NULL'),
            ('signed_by',        'users',          'fk_contracts_signed_by',     'SET NULL'),
            ('cancelled_by',     'users',          'fk_contracts_cancelled_by',  'SET NULL')
        ) AS t(col, ref, name, on_delete)
    LOOP
        IF NOT EXISTS (
            SELECT 1
              FROM pg_constraint c
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
             WHERE c.conrelid = 'contracts'::regclass AND c.contype = 'f' AND a.attname = fk.col
        ) THEN
            EXECUTE format('ALTER TABLE contracts ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(id) ON DELETE %s',
                           fk.name, fk.col, fk.ref, fk.on_delete);
        END IF;
    END LOOP;
END $$;

COMMIT;

-- Meta (Facebook/Instagram) Lead Ads integratsiyasi.
--
-- Loyihada Flyway YO'Q: sxemani `ddl-auto: update` quradi va bu fayl
-- QO'LDA bajariladi. Shuning uchun hamma narsa IF NOT EXISTS bilan va
-- bir necha marta ishga tushirishga bardoshli yozilgan.
--
-- Nega umuman kerak: `ddl-auto: update` jadval va ustunlarni yaratadi,
-- lekin UNIQUE indekslarni KAFOLATLAMAYDI - ayniqsa mavjud jadvalga
-- qo'shilgan ustun ustidagi indeksni. Meta esa bir xil leadgen_id ni
-- bir necha marta yuboradi va butun idempotentlik aynan shu indekslarga
-- tayanadi. Ularni bu yerda aniq yozamiz.
--
-- Raqam: V36 emas, V49 - V36..V48 bu loyihada allaqachon band.

BEGIN;

-- ── Formalar ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS meta_lead_forms (
    id                     BIGSERIAL PRIMARY KEY,
    form_id                VARCHAR(64)  NOT NULL,
    page_id                VARCHAR(64),
    name                   VARCHAR(255),
    status                 VARCHAR(32),
    locale                 VARCHAR(16),
    leads_count            INTEGER,
    lead_type              VARCHAR(20)  NOT NULL DEFAULT 'UNMAPPED',
    default_stage_code     VARCHAR(50),
    default_study_format   VARCHAR(20),
    default_source         VARCHAR(30),
    auto_create_task       BOOLEAN      NOT NULL DEFAULT FALSE,
    task_time_question_key VARCHAR(255),
    active                 BOOLEAN      NOT NULL DEFAULT FALSE,
    synced_at              TIMESTAMP,
    created_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP
);

-- Yagona ishonchli kalit. Sinxronizatsiya UPSERT ni aynan shunga tayanib
-- qiladi; indekssiz takroriy sync formalarni ikkilantirib yuborardi.
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_lead_forms_form_id
    ON meta_lead_forms (form_id);

CREATE INDEX IF NOT EXISTS idx_meta_lead_forms_page
    ON meta_lead_forms (page_id);
CREATE INDEX IF NOT EXISTS idx_meta_lead_forms_type
    ON meta_lead_forms (lead_type);

-- ── Savollar ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS meta_lead_form_questions (
    id           BIGSERIAL PRIMARY KEY,
    form_id      BIGINT       NOT NULL
                 REFERENCES meta_lead_forms (id) ON DELETE CASCADE,
    -- XOM kalit: apostrof, '?', '/' va oxirgi '_' bilan birga saqlanadi.
    -- field_data ham AYNAN shu kalitni qaytaradi va ikkisi bevosita
    -- solishtiriladi - normalizatsiya javobni savolidan ajratib qo'yardi.
    question_key VARCHAR(255) NOT NULL,
    label        VARCHAR(512),
    type         VARCHAR(64),
    -- {optionKey: label} lug'ati. field_data variantlarda KALIT ni
    -- qaytaradi, foydalanuvchi ko'rgan matnni emas.
    options_json TEXT,
    crm_field    VARCHAR(32),
    sort_order   INTEGER,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_form_question
    ON meta_lead_form_questions (form_id, question_key);

CREATE INDEX IF NOT EXISTS idx_meta_form_questions_form
    ON meta_lead_form_questions (form_id);

-- ── Webhook eventlari ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS meta_webhook_events (
    id              BIGSERIAL PRIMARY KEY,
    leadgen_id      VARCHAR(64) NOT NULL,
    form_id         VARCHAR(64),
    page_id         VARCHAR(64),
    ad_id           VARCHAR(64),
    created_time_ms BIGINT,
    raw_payload     TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER     NOT NULL DEFAULT 0,
    error_message   TEXT,
    -- FK YO'Q ataylab: lid o'chirilsa ham event tarixi qolishi kerak,
    -- FK esa lidni o'chirishni bloklardi.
    lead_id         BIGINT,
    received_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMP
);

-- Idempotentlikning BIRINCHI qatlami. Meta bir xil leadgen_id ni qayta
-- urinish, bir nechta obuna va tarmoq uzilishida takroran yuboradi.
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_webhook_events_leadgen
    ON meta_webhook_events (leadgen_id);

-- Scheduler har 15 soniyada shu ikki ustun bo'yicha qidiradi.
CREATE INDEX IF NOT EXISTS idx_meta_webhook_events_status
    ON meta_webhook_events (status, attempts);
CREATE INDEX IF NOT EXISTS idx_meta_webhook_events_form
    ON meta_webhook_events (form_id);
CREATE INDEX IF NOT EXISTS idx_meta_webhook_events_received
    ON meta_webhook_events (received_at);

-- ── Lidlardagi Meta belgilari ────────────────────────────────────────
ALTER TABLE leads ADD COLUMN IF NOT EXISTS meta_leadgen_id VARCHAR(64);
ALTER TABLE leads ADD COLUMN IF NOT EXISTS meta_form_id    VARCHAR(64);
ALTER TABLE leads ADD COLUMN IF NOT EXISTS meta_raw_json   TEXT;

-- Idempotentlikning IKKINCHI qatlami va eng muhimi: bir xil lid webhook
-- orqali ham, backfill orqali ham kelishi mumkin. Indeks qisman
-- (partial): qo'lda yaratilgan lidlarda ustun NULL va PostgreSQL da
-- NULL lar UNIQUE ni buzmaydi, lekin WHERE bilan indeks kichikroq ham
-- bo'ladi.
CREATE UNIQUE INDEX IF NOT EXISTS uk_leads_meta_leadgen_id
    ON leads (meta_leadgen_id)
    WHERE meta_leadgen_id IS NOT NULL;

-- Telefon bo'yicha takroriy murojaat qidiruvi (oxirgi 30 kun, ochiq lidlar).
CREATE INDEX IF NOT EXISTS idx_leads_phone_created
    ON leads (phone, created_at);

CREATE INDEX IF NOT EXISTS idx_leads_meta_form
    ON leads (meta_form_id);

COMMIT;

-- ── Tekshirish ───────────────────────────────────────────────────────
-- Indekslar haqiqatan yaratilganini ko'rish uchun:
--
--   SELECT indexname FROM pg_indexes
--    WHERE tablename IN ('leads', 'meta_lead_forms',
--                        'meta_lead_form_questions', 'meta_webhook_events')
--      AND indexname LIKE 'uk_%';
--
-- uk_leads_meta_leadgen_id yo'q bo'lsa, bazada allaqachon takroriy
-- meta_leadgen_id bor degani. Avval ularni toping:
--
--   SELECT meta_leadgen_id, COUNT(*) FROM leads
--    WHERE meta_leadgen_id IS NOT NULL
--    GROUP BY meta_leadgen_id HAVING COUNT(*) > 1;

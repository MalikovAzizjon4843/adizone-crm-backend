-- Taklif va shikoyatlar — "murojaatlar" (docs/design/telegram-platform.md §12, miniapp-api.md §7b).
-- O'quvchi boti (@adizonestudents_bot) va Mini App orqali qabul qilinadi, CRM'da (/api/appeals) ko'rib chiqiladi,
-- xodim javobi yuboruvchiga bot orqali qaytadi (telegram_outbox, MANUAL).
--
-- Nima qo'shiladi:
--   * appeal_number_seq — murojaat raqami (MRJ-00001), tranzaksiyadan tashqari: rollback raqamni "yeydi" (bo'shliq me'yor);
--   * appeals — murojaat. Anonim bo'lsa app_identity_id / student_id / parent_id / contact_* SAQLANMAYDI (CHECK) —
--     faqat yetkazish kaliti telegram_chat_id qoladi (u API'da hech qachon chiqmaydi);
--   * appeal_messages — yozishma: SENDER (yuboruvchi) va STAFF (xodim; internal=true — faqat xodimlar uchun izoh,
--     yuborilmaydi);
--   * bot_conversations — bot suhbat holati (bir chat — bitta qator; 30 daqiqada eskiradi, tozalash — TelegramCleanupJob).
--
-- CHECK lar ataylab "= ... OR ..." ko'rinishida: "IN (...)" Postgres'da "= ANY (ARRAY[...])" bo'lib qoladi va
-- EnumCheckConstraintCleaner uni startup'da o'chirib yuboradi.
--
-- V88 dan KEYIN, ILOVANI YANGI JAR BILAN ISHGA TUSHIRISHDAN OLDIN, QO'LDA, crm_user bilan:
--   psql -X -v ON_ERROR_STOP=1 -f V89__appeals.sql
-- Idempotent (IF NOT EXISTS / pg_constraint tekshiruvi) — qayta bajarish xavfsiz. Ma'lumotga tegmaydi.
-- Webhook'ni qayta o'rnating (POST /api/admin/telegram/set-webhook): allowed_updates endi "callback_query" ni ham oladi.
-- Tekshirish: docs/ops/prod-schema-check.sql — V89 bo'lagi "TO'LIQ".

BEGIN;

-- ── 1. Raqam ─────────────────────────────────────────────────────────────────────────
CREATE SEQUENCE IF NOT EXISTS appeal_number_seq START WITH 1;

-- ── 2. Murojaatlar ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS appeals (
    id                  BIGSERIAL     PRIMARY KEY,
    number              VARCHAR(20)   NOT NULL,
    type                VARCHAR(20)   NOT NULL,        -- SUGGESTION | COMPLAINT | TEACHER | SERVICE | PAYMENT | OTHER
    text                VARCHAR(3000) NOT NULL,
    anonymous           BOOLEAN       NOT NULL DEFAULT FALSE,
    source              VARCHAR(10)   NOT NULL,        -- BOT | MINIAPP
    telegram_chat_id    BIGINT        NOT NULL,        -- yetkazish kaliti; API'da chiqmaydi
    language            VARCHAR(5),                    -- bot javoblari tili: uz | ru
    app_identity_id     BIGINT,
    student_id          BIGINT,
    parent_id           BIGINT,
    contact_name        VARCHAR(150),
    contact_phone       VARCHAR(20),
    teacher_id          BIGINT,
    status              VARCHAR(20)   NOT NULL DEFAULT 'NEW',  -- NEW | IN_PROGRESS | RESOLVED | REJECTED
    assignee_user_id    BIGINT,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP,
    first_response_at   TIMESTAMP,
    resolved_at         TIMESTAMP
);

-- Hibernate (ddl-auto) yaratgan jadvalda ustun bo'lmasligi mumkin — ustunlar alohida ham
ALTER TABLE appeals ADD COLUMN IF NOT EXISTS language VARCHAR(5);
ALTER TABLE appeals ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS ux_appeals_number ON appeals (number);
CREATE INDEX IF NOT EXISTS idx_appeals_status_created ON appeals (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_appeals_assignee ON appeals (assignee_user_id);
CREATE INDEX IF NOT EXISTS idx_appeals_chat_created ON appeals (telegram_chat_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_appeals_teacher ON appeals (teacher_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeals_type') THEN
        ALTER TABLE appeals ADD CONSTRAINT ck_appeals_type
            CHECK (type = 'SUGGESTION' OR type = 'COMPLAINT' OR type = 'TEACHER' OR type = 'SERVICE'
                   OR type = 'PAYMENT' OR type = 'OTHER');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeals_status') THEN
        ALTER TABLE appeals ADD CONSTRAINT ck_appeals_status
            CHECK (status = 'NEW' OR status = 'IN_PROGRESS' OR status = 'RESOLVED' OR status = 'REJECTED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeals_source') THEN
        ALTER TABLE appeals ADD CONSTRAINT ck_appeals_source
            CHECK (source = 'BOT' OR source = 'MINIAPP');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeals_text_len') THEN
        ALTER TABLE appeals ADD CONSTRAINT ck_appeals_text_len
            CHECK (char_length(btrim(text)) >= 1 AND char_length(text) <= 3000);
    END IF;
    -- Anonimlik bazada kafolatlangan: yuboruvchini aniqlaydigan hech narsa saqlanmaydi
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeals_anonymous_no_sender') THEN
        ALTER TABLE appeals ADD CONSTRAINT ck_appeals_anonymous_no_sender
            CHECK (anonymous = FALSE
                   OR (app_identity_id IS NULL AND student_id IS NULL AND parent_id IS NULL
                       AND contact_name IS NULL AND contact_phone IS NULL));
    END IF;
    -- O'qituvchi faqat TEACHER turida
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeals_teacher_type') THEN
        ALTER TABLE appeals ADD CONSTRAINT ck_appeals_teacher_type
            CHECK (teacher_id IS NULL OR type = 'TEACHER');
    END IF;
    -- Yopilgan murojaatda yopilish vaqti bor, ochiqda — yo'q
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeals_resolved_at') THEN
        ALTER TABLE appeals ADD CONSTRAINT ck_appeals_resolved_at
            CHECK ((resolved_at IS NOT NULL) = (status = 'RESOLVED' OR status = 'REJECTED'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_appeals_identity') THEN
        ALTER TABLE appeals ADD CONSTRAINT fk_appeals_identity
            FOREIGN KEY (app_identity_id) REFERENCES app_identities (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_appeals_student') THEN
        ALTER TABLE appeals ADD CONSTRAINT fk_appeals_student
            FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_appeals_parent') THEN
        ALTER TABLE appeals ADD CONSTRAINT fk_appeals_parent
            FOREIGN KEY (parent_id) REFERENCES parents (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_appeals_teacher') THEN
        ALTER TABLE appeals ADD CONSTRAINT fk_appeals_teacher
            FOREIGN KEY (teacher_id) REFERENCES teachers (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_appeals_assignee') THEN
        ALTER TABLE appeals ADD CONSTRAINT fk_appeals_assignee
            FOREIGN KEY (assignee_user_id) REFERENCES users (id) ON DELETE SET NULL;
    END IF;
END $$;

-- ── 3. Yozishma ──────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS appeal_messages (
    id              BIGSERIAL     PRIMARY KEY,
    appeal_id       BIGINT        NOT NULL,
    author_type     VARCHAR(10)   NOT NULL,            -- SENDER | STAFF
    author_user_id  BIGINT,
    text            VARCHAR(3000) NOT NULL,
    created_at      TIMESTAMP     NOT NULL,
    delivered       BOOLEAN       NOT NULL DEFAULT FALSE,
    internal        BOOLEAN       NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_appeal_messages_appeal ON appeal_messages (appeal_id, id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeal_messages_author_type') THEN
        ALTER TABLE appeal_messages ADD CONSTRAINT ck_appeal_messages_author_type
            CHECK (author_type = 'SENDER' OR author_type = 'STAFF');
    END IF;
    -- Yuboruvchi xabari ichki bo'lmaydi va xodimga bog'lanmaydi
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeal_messages_sender') THEN
        ALTER TABLE appeal_messages ADD CONSTRAINT ck_appeal_messages_sender
            CHECK (author_type = 'STAFF' OR (internal = FALSE AND author_user_id IS NULL));
    END IF;
    -- Ichki izoh hech qachon yuborilmaydi
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeal_messages_internal') THEN
        ALTER TABLE appeal_messages ADD CONSTRAINT ck_appeal_messages_internal
            CHECK (internal = FALSE OR delivered = FALSE);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_appeal_messages_text_len') THEN
        ALTER TABLE appeal_messages ADD CONSTRAINT ck_appeal_messages_text_len
            CHECK (char_length(btrim(text)) >= 1 AND char_length(text) <= 3000);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_appeal_messages_appeal') THEN
        ALTER TABLE appeal_messages ADD CONSTRAINT fk_appeal_messages_appeal
            FOREIGN KEY (appeal_id) REFERENCES appeals (id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_appeal_messages_author') THEN
        ALTER TABLE appeal_messages ADD CONSTRAINT fk_appeal_messages_author
            FOREIGN KEY (author_user_id) REFERENCES users (id) ON DELETE SET NULL;
    END IF;
END $$;

-- ── 4. Bot suhbat holati ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS bot_conversations (
    chat_id     BIGINT       PRIMARY KEY,
    flow        VARCHAR(30)  NOT NULL,               -- APPEAL_NEW | APPEAL_REPLY
    step        VARCHAR(30)  NOT NULL,
    data        TEXT,                                -- JSON (tur, anonim, ism, telefon, matn, appealId …)
    updated_at  TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bot_conversations_updated ON bot_conversations (updated_at);

COMMIT;

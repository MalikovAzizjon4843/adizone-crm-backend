-- "Adizone Qo'ng'iroqlar" — xodimlarning Android telefonidagi qo'ng'iroqlar CRM'da (docs/design/calls.md).
-- Ilova (Kotlin) o'z SIM raqamidagi qo'ng'iroqlar jurnalini /api/calls/app/** orqali sinxron qiladi; CRM'da lid /
-- o'quvchi kartasida ko'rinadi, natija lid timeline'iga yoziladi, CRM'dan telefonga "qo'ng'iroq qil" so'rovi yuboriladi.
--
-- Nima qo'shiladi:
--   * call_devices    — ro'yxatdan o'tgan telefon. Qurilma tokeni FAQAT SHA-256 hash ko'rinishida (token_hash);
--                       bitta xodimda va bitta device_uid da bittadan ortiq FAOL (revoked_at IS NULL) qurilma yo'q;
--   * calls           — qo'ng'iroq. UNIQUE (device_id, device_call_key): ilova bir partiyani qayta yuborsa dublikat yo'q.
--                       contact_type — moslashish natijasi (LEAD | STUDENT | PARENT | UNKNOWN) va unga mos id lar (CHECK);
--   * call_recordings — yozuv fayli (bitta qo'ng'iroqqa bitta). Retention faylni o'chiradi (path = NULL, deleted_at),
--                       metama'lumot qoladi;
--   * call_requests   — CRM'dan "telefonimdan qo'ng'iroq qil" so'rovi (PENDING → DELIVERED → DIALED | CANCELLED | EXPIRED);
--   * call_exclusions — xodimning shaxsiy raqamlari: ular umuman sinxron qilinmaydi;
--   * telefon bo'yicha qidiruv indekslari (students, parents, users) — kiruvchi qo'ng'iroq kartasi < 200 ms.
--
-- CHECK lar ataylab "= ... OR ..." ko'rinishida: "IN (...)" Postgres'da "= ANY (ARRAY[...])" bo'lib qoladi va
-- EnumCheckConstraintCleaner uni startup'da o'chirib yuboradi.
--
-- V89 dan KEYIN, ILOVANI YANGI JAR BILAN ISHGA TUSHIRISHDAN OLDIN, QO'LDA, crm_user bilan:
--   psql -X -v ON_ERROR_STOP=1 -f V90__calls.sql
-- Idempotent (IF NOT EXISTS / pg_constraint tekshiruvi) — qayta bajarish xavfsiz. Mavjud ma'lumotga tegmaydi.
-- Hibernate (ddl-auto: update) jadvallarni o'zi yaratgan bo'lsa ham skript ishlaydi: ustunlar ADD COLUMN IF NOT EXISTS,
-- cheklovlar nomi bo'yicha tekshiriladi.
-- Tekshirish: docs/ops/prod-schema-check.sql — V90 bo'lagi "TO'LIQ".

BEGIN;

-- ── 1. Qurilmalar ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS call_devices (
    id                  BIGSERIAL     PRIMARY KEY,
    user_id             BIGINT        NOT NULL,
    device_uid          VARCHAR(100)  NOT NULL,        -- ilova yaratgan barqaror UUID (qayta o'rnatishda o'zgaradi)
    model               VARCHAR(100),
    android_version     VARCHAR(30),
    app_version         VARCHAR(30),
    token_hash          VARCHAR(64)   NOT NULL,        -- SHA-256(hex) qurilma tokenidan; token o'zi saqlanmaydi
    fcm_token           VARCHAR(512),
    perm_call_log       BOOLEAN,                       -- heartbeat: READ_CALL_LOG berilgan
    perm_recordings     BOOLEAN,                       -- heartbeat: yozuvlar papkasiga ruxsat bor
    perm_battery_opt    BOOLEAN,                       -- heartbeat: batareya optimizatsiyasidan chiqarilgan
    created_at          TIMESTAMP     NOT NULL,
    last_seen_at        TIMESTAMP,
    last_sync_at        TIMESTAMP,
    revoked_at          TIMESTAMP,
    revoked_by          BIGINT
);

ALTER TABLE call_devices ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(512);
ALTER TABLE call_devices ADD COLUMN IF NOT EXISTS perm_call_log BOOLEAN;
ALTER TABLE call_devices ADD COLUMN IF NOT EXISTS perm_recordings BOOLEAN;
ALTER TABLE call_devices ADD COLUMN IF NOT EXISTS perm_battery_opt BOOLEAN;
ALTER TABLE call_devices ADD COLUMN IF NOT EXISTS last_sync_at TIMESTAMP;
ALTER TABLE call_devices ADD COLUMN IF NOT EXISTS revoked_by BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS ux_call_devices_token_hash ON call_devices (token_hash);
-- Bir xodim — bitta faol qurilma; bitta telefon (device_uid) — bitta faol hisob
CREATE UNIQUE INDEX IF NOT EXISTS ux_call_devices_user_active ON call_devices (user_id) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_call_devices_uid_active ON call_devices (device_uid) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_call_devices_user ON call_devices (user_id, created_at DESC);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_call_devices_token_hash') THEN
        ALTER TABLE call_devices ADD CONSTRAINT ck_call_devices_token_hash
            CHECK (char_length(token_hash) = 64);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_call_devices_uid') THEN
        ALTER TABLE call_devices ADD CONSTRAINT ck_call_devices_uid
            CHECK (char_length(btrim(device_uid)) >= 1);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_call_devices_user') THEN
        ALTER TABLE call_devices ADD CONSTRAINT fk_call_devices_user
            FOREIGN KEY (user_id) REFERENCES users (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_call_devices_revoked_by') THEN
        ALTER TABLE call_devices ADD CONSTRAINT fk_call_devices_revoked_by
            FOREIGN KEY (revoked_by) REFERENCES users (id) ON DELETE SET NULL;
    END IF;
END $$;

-- ── 2. Qo'ng'iroqlar ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS calls (
    id                  BIGSERIAL     PRIMARY KEY,
    device_id           BIGINT        NOT NULL,
    user_id             BIGINT        NOT NULL,        -- qurilma egasi (sinxron paytidagi)
    direction           VARCHAR(10)   NOT NULL,        -- IN | OUT | MISSED | REJECTED
    phone_raw           VARCHAR(40)   NOT NULL,        -- telefondagi ko'rinishi
    phone_normalized    VARCHAR(20),                   -- +998XXXXXXXXX; xorijiy — "+" va raqamlar; tanilmasa NULL
    started_at          TIMESTAMP     NOT NULL,        -- Toshkent vaqti
    duration_sec        INTEGER       NOT NULL DEFAULT 0,
    device_call_key     VARCHAR(100)  NOT NULL,        -- ilova kaliti (CallLog._ID + DATE) — idempotentlik
    contact_type        VARCHAR(10)   NOT NULL DEFAULT 'UNKNOWN',  -- LEAD | STUDENT | PARENT | UNKNOWN
    lead_id             BIGINT,
    student_id          BIGINT,                        -- STUDENT: o'zi; PARENT: farzandi (birinchisi)
    parent_id           BIGINT,                        -- PARENT: parents jadvalida bo'lsa
    auto_lead_created   BOOLEAN       NOT NULL DEFAULT FALSE,
    result_code         VARCHAR(20),                   -- TALKED | NO_ANSWER | CALLBACK | THINKING | TRIAL_BOOKED | REJECTED | OTHER
    note                VARCHAR(2000),
    result_by           BIGINT,
    result_at           TIMESTAMP,
    created_at          TIMESTAMP     NOT NULL
);

ALTER TABLE calls ADD COLUMN IF NOT EXISTS contact_type VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE calls ADD COLUMN IF NOT EXISTS auto_lead_created BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE calls ADD COLUMN IF NOT EXISTS parent_id BIGINT;
ALTER TABLE calls ADD COLUMN IF NOT EXISTS result_by BIGINT;
ALTER TABLE calls ADD COLUMN IF NOT EXISTS result_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS ux_calls_device_key ON calls (device_id, device_call_key);
CREATE INDEX IF NOT EXISTS idx_calls_user_started ON calls (user_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_calls_started ON calls (started_at DESC);
CREATE INDEX IF NOT EXISTS idx_calls_phone_started ON calls (phone_normalized, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_calls_lead ON calls (lead_id, started_at DESC) WHERE lead_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_calls_student ON calls (student_id, started_at DESC) WHERE student_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_calls_direction') THEN
        ALTER TABLE calls ADD CONSTRAINT ck_calls_direction
            CHECK (direction = 'IN' OR direction = 'OUT' OR direction = 'MISSED' OR direction = 'REJECTED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_calls_contact_type') THEN
        ALTER TABLE calls ADD CONSTRAINT ck_calls_contact_type
            CHECK (contact_type = 'LEAD' OR contact_type = 'STUDENT' OR contact_type = 'PARENT'
                   OR contact_type = 'UNKNOWN');
    END IF;
    -- Moslashish turi va id lar mos (o'chirilgan lid/o'quvchi — FK SET NULL — UNKNOWN ga tushiriladi: pastdagi
    -- CHECK ga ziddiyat bo'lmasin deb LEAD/STUDENT da id NULL bo'lishiga ham ruxsat bor; teskarisi taqiqlangan)
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_calls_contact_ids') THEN
        ALTER TABLE calls ADD CONSTRAINT ck_calls_contact_ids
            CHECK ((contact_type = 'LEAD' AND student_id IS NULL AND parent_id IS NULL)
                   OR (contact_type = 'STUDENT' AND lead_id IS NULL AND parent_id IS NULL)
                   OR (contact_type = 'PARENT' AND lead_id IS NULL)
                   OR (contact_type = 'UNKNOWN' AND lead_id IS NULL AND student_id IS NULL AND parent_id IS NULL));
    END IF;
    -- Avtomatik lid faqat notanish raqamdan kiruvchi / javobsiz qo'ng'iroqda
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_calls_auto_lead') THEN
        ALTER TABLE calls ADD CONSTRAINT ck_calls_auto_lead
            CHECK (auto_lead_created = FALSE
                   OR (contact_type = 'LEAD' AND (direction = 'IN' OR direction = 'MISSED')));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_calls_duration') THEN
        ALTER TABLE calls ADD CONSTRAINT ck_calls_duration
            CHECK (duration_sec >= 0 AND duration_sec <= 86400);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_calls_result_code') THEN
        ALTER TABLE calls ADD CONSTRAINT ck_calls_result_code
            CHECK (result_code IS NULL OR result_code = 'TALKED' OR result_code = 'NO_ANSWER'
                   OR result_code = 'CALLBACK' OR result_code = 'THINKING' OR result_code = 'TRIAL_BOOKED'
                   OR result_code = 'REJECTED' OR result_code = 'OTHER');
    END IF;
    -- Natija bor bo'lsa — vaqti ham bor
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_calls_result_at') THEN
        ALTER TABLE calls ADD CONSTRAINT ck_calls_result_at
            CHECK (result_code IS NULL OR result_at IS NOT NULL);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_calls_key') THEN
        ALTER TABLE calls ADD CONSTRAINT ck_calls_key
            CHECK (char_length(btrim(device_call_key)) >= 1);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_calls_device') THEN
        ALTER TABLE calls ADD CONSTRAINT fk_calls_device
            FOREIGN KEY (device_id) REFERENCES call_devices (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_calls_user') THEN
        ALTER TABLE calls ADD CONSTRAINT fk_calls_user
            FOREIGN KEY (user_id) REFERENCES users (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_calls_lead') THEN
        ALTER TABLE calls ADD CONSTRAINT fk_calls_lead
            FOREIGN KEY (lead_id) REFERENCES leads (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_calls_student') THEN
        ALTER TABLE calls ADD CONSTRAINT fk_calls_student
            FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_calls_parent') THEN
        ALTER TABLE calls ADD CONSTRAINT fk_calls_parent
            FOREIGN KEY (parent_id) REFERENCES parents (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_calls_result_by') THEN
        ALTER TABLE calls ADD CONSTRAINT fk_calls_result_by
            FOREIGN KEY (result_by) REFERENCES users (id) ON DELETE SET NULL;
    END IF;
END $$;

-- ── 3. Yozuvlar ──────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS call_recordings (
    id                  BIGSERIAL     PRIMARY KEY,
    call_id             BIGINT        NOT NULL,
    path                VARCHAR(500),                  -- app.calls.recording-dir ga nisbatan; retention'dan keyin NULL
    mime                VARCHAR(100)  NOT NULL,
    size_bytes          BIGINT        NOT NULL,
    duration_sec        INTEGER,
    sha256              VARCHAR(64)   NOT NULL,        -- hex, kichik harf
    uploaded_at         TIMESTAMP     NOT NULL,
    deleted_at          TIMESTAMP                      -- retention fayli o'chirgan vaqt
);

ALTER TABLE call_recordings ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS ux_call_recordings_call ON call_recordings (call_id);
CREATE INDEX IF NOT EXISTS idx_call_recordings_live ON call_recordings (uploaded_at) WHERE deleted_at IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_call_recordings_size') THEN
        ALTER TABLE call_recordings ADD CONSTRAINT ck_call_recordings_size
            CHECK (size_bytes > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_call_recordings_sha256') THEN
        ALTER TABLE call_recordings ADD CONSTRAINT ck_call_recordings_sha256
            CHECK (char_length(sha256) = 64);
    END IF;
    -- Fayl yo bor (path), yo retention o'chirgan (deleted_at) — ikkalasi bir vaqtda emas
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_call_recordings_path') THEN
        ALTER TABLE call_recordings ADD CONSTRAINT ck_call_recordings_path
            CHECK ((path IS NULL) = (deleted_at IS NOT NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_call_recordings_call') THEN
        ALTER TABLE call_recordings ADD CONSTRAINT fk_call_recordings_call
            FOREIGN KEY (call_id) REFERENCES calls (id) ON DELETE CASCADE;
    END IF;
END $$;

-- ── 4. CRM'dan qo'ng'iroq so'rovlari ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS call_requests (
    id                  BIGSERIAL     PRIMARY KEY,
    user_id             BIGINT        NOT NULL,        -- kimning telefonida teriladi
    device_id           BIGINT,                        -- yaratilgan paytdagi faol qurilma
    phone               VARCHAR(20)   NOT NULL,        -- +998XXXXXXXXX
    lead_id             BIGINT,
    student_id          BIGINT,
    status              VARCHAR(12)   NOT NULL DEFAULT 'PENDING',  -- PENDING | DELIVERED | DIALED | CANCELLED | EXPIRED
    created_by          BIGINT,
    created_at          TIMESTAMP     NOT NULL,
    expires_at          TIMESTAMP     NOT NULL,
    delivered_at        TIMESTAMP,
    dialed_at           TIMESTAMP,
    closed_at           TIMESTAMP                      -- CANCELLED / EXPIRED vaqti
);

ALTER TABLE call_requests ADD COLUMN IF NOT EXISTS device_id BIGINT;
ALTER TABLE call_requests ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMP;
ALTER TABLE call_requests ADD COLUMN IF NOT EXISTS dialed_at TIMESTAMP;
ALTER TABLE call_requests ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_call_requests_user_status ON call_requests (user_id, status, expires_at);
CREATE INDEX IF NOT EXISTS idx_call_requests_open ON call_requests (expires_at)
    WHERE status = 'PENDING' OR status = 'DELIVERED';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_call_requests_status') THEN
        ALTER TABLE call_requests ADD CONSTRAINT ck_call_requests_status
            CHECK (status = 'PENDING' OR status = 'DELIVERED' OR status = 'DIALED' OR status = 'CANCELLED'
                   OR status = 'EXPIRED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_call_requests_target') THEN
        ALTER TABLE call_requests ADD CONSTRAINT ck_call_requests_target
            CHECK (lead_id IS NULL OR student_id IS NULL);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_call_requests_expires') THEN
        ALTER TABLE call_requests ADD CONSTRAINT ck_call_requests_expires
            CHECK (expires_at > created_at);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_call_requests_closed_at') THEN
        ALTER TABLE call_requests ADD CONSTRAINT ck_call_requests_closed_at
            CHECK ((closed_at IS NOT NULL) = (status = 'CANCELLED' OR status = 'EXPIRED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_call_requests_user') THEN
        ALTER TABLE call_requests ADD CONSTRAINT fk_call_requests_user
            FOREIGN KEY (user_id) REFERENCES users (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_call_requests_device') THEN
        ALTER TABLE call_requests ADD CONSTRAINT fk_call_requests_device
            FOREIGN KEY (device_id) REFERENCES call_devices (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_call_requests_lead') THEN
        ALTER TABLE call_requests ADD CONSTRAINT fk_call_requests_lead
            FOREIGN KEY (lead_id) REFERENCES leads (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_call_requests_student') THEN
        ALTER TABLE call_requests ADD CONSTRAINT fk_call_requests_student
            FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_call_requests_created_by') THEN
        ALTER TABLE call_requests ADD CONSTRAINT fk_call_requests_created_by
            FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL;
    END IF;
END $$;

-- ── 5. Shaxsiy raqamlar (sinxron qilinmaydi) ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS call_exclusions (
    id                  BIGSERIAL     PRIMARY KEY,
    user_id             BIGINT        NOT NULL,
    phone_normalized    VARCHAR(20)   NOT NULL,
    label               VARCHAR(100),
    created_at          TIMESTAMP     NOT NULL
);

ALTER TABLE call_exclusions ADD COLUMN IF NOT EXISTS label VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS ux_call_exclusions_user_phone ON call_exclusions (user_id, phone_normalized);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_call_exclusions_user') THEN
        ALTER TABLE call_exclusions ADD CONSTRAINT fk_call_exclusions_user
            FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
    END IF;
END $$;

-- ── 6. Telefon bo'yicha qidiruv (kiruvchi qo'ng'iroq kartasi, sinxron moslashuvi) ───
-- leads — V49 idx_leads_phone_created (phone, created_at) yetarli.
CREATE INDEX IF NOT EXISTS idx_students_phone ON students (phone);
CREATE INDEX IF NOT EXISTS idx_students_parent_phone ON students (parent_phone);
CREATE INDEX IF NOT EXISTS idx_parents_phone ON parents (phone);
CREATE INDEX IF NOT EXISTS idx_users_phone ON users (phone);

COMMIT;

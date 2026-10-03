-- Telegram bot (minimal) va o'quvchi/ota-ona Mini App — docs/design/telegram-platform.md §3, §5 (bosqich 2, MVP),
-- frontend shartnomasi: docs/design/miniapp-api.md.
--
-- V65 dan KEYIN, QO'LDA, crm_user bilan bajariladi (deploy-v2.md §3.3). Idempotent: qayta bajarilsa hech
-- narsa o'zgarmaydi. ddl-auto: update jadvallarni o'zi ham yaratadi — bu skript nomlarni, FK larni va
-- indekslarni aniq qiladi (Hibernate qisman indeks/ON DELETE bermaydi).
--
-- Dizayn hujjatidagi V64–V67 rejasi o'rniga: V64/V65 boshqa ishlarga ketgan, shu sababli bitta V66.
-- Xodim xabarnomalari (user_telegram_links, telegram_outbox ...) va chat ko'prigi — keyingi bosqichlar.
-- Sozlama center.supportPhone (+998 77 337 32 33) V63 da qo'shilgan — bu yerda takrorlanmaydi.

BEGIN;

-- ── 1. Webhook update'lari (update_id bo'yicha takrorni tashlash, 7 kun saqlanadi) ──────────────
CREATE TABLE IF NOT EXISTS telegram_updates (
    update_id    BIGINT       PRIMARY KEY,
    kind         VARCHAR(30),
    received_at  TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_telegram_updates_received ON telegram_updates (received_at);

-- ── 2. Mini App identity'lari (users jadvalida EMAS — phase5-audit Q1) ─────────────────────────
CREATE TABLE IF NOT EXISTS app_identities (
    id                BIGSERIAL    PRIMARY KEY,
    telegram_user_id  BIGINT       NOT NULL,
    chat_id           BIGINT,
    telegram_username VARCHAR(64),
    first_name        VARCHAR(128),
    kind              VARCHAR(10)  NOT NULL,          -- STUDENT | PARENT
    phone_canonical   VARCHAR(20)  NOT NULL,          -- +998XXXXXXXXX
    status            VARCHAR(20)  NOT NULL,          -- ACTIVE | UNLINKED
    identity_version  INTEGER      NOT NULL DEFAULT 0, -- JWT "iv"; uzishda oshadi
    linked_at         TIMESTAMP    NOT NULL,
    unlinked_at       TIMESTAMP,
    unlink_reason     VARCHAR(30),
    last_seen_at      TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP
);
ALTER TABLE app_identities ALTER COLUMN identity_version SET DEFAULT 0;
-- Bitta Telegram hisobi — bitta identity (Hibernate @UniqueConstraint bilan bir xil nom: ikkinchi indeks yaratilmaydi)
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_identities_telegram_user ON app_identities (telegram_user_id);
CREATE INDEX IF NOT EXISTS idx_app_identities_phone ON app_identities (phone_canonical);

-- ── 3. Identity → ko'ra oladigan o'quvchilar (IDOR tekshiruvi shu jadval bo'yicha) ──────────────
CREATE TABLE IF NOT EXISTS app_identity_students (
    id           BIGSERIAL    PRIMARY KEY,
    identity_id  BIGINT       NOT NULL,
    student_id   BIGINT       NOT NULL,
    relation     VARCHAR(10)  NOT NULL,               -- SELF | PARENT
    linked_at    TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_identity_students ON app_identity_students (identity_id, student_id);
CREATE INDEX IF NOT EXISTS idx_app_identity_students_student ON app_identity_students (student_id);

-- FK lar NOMI bo'yicha emas, USTUNI bo'yicha tekshiriladi (Hibernate yaratgan bo'lsa takrorlanmaydi)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'app_identity_students'::regclass AND c.contype = 'f'
           AND c.confrelid = 'app_identities'::regclass AND a.attname = 'identity_id'
    ) THEN
        ALTER TABLE app_identity_students ADD CONSTRAINT fk_app_identity_students_identity
            FOREIGN KEY (identity_id) REFERENCES app_identities(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'app_identity_students'::regclass AND c.contype = 'f'
           AND c.confrelid = 'students'::regclass AND a.attname = 'student_id'
    ) THEN
        ALTER TABLE app_identity_students ADD CONSTRAINT fk_app_identity_students_student
            FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE;
    END IF;
END $$;

-- ── 4. Bog'lash urinishlari (telefon emas — SHA-256 xeshi; 90 kun saqlanadi) ───────────────────
CREATE TABLE IF NOT EXISTS app_link_attempts (
    id                BIGSERIAL    PRIMARY KEY,
    telegram_user_id  BIGINT       NOT NULL,
    phone_hash        VARCHAR(64),
    result            VARCHAR(20)  NOT NULL,          -- LINKED | NOT_FOUND | REJECTED | LIMITED
    created_at        TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_app_link_attempts_user ON app_link_attempts (telegram_user_id, created_at);

COMMIT;

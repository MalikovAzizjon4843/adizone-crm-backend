-- Ichki chat, 1-bosqich: matnli xabar va o'qilgan belgisi.
--
-- ddl-auto: update bu jadvallarni o'zi ham yaratadi, lekin indekslarni
-- kafolatlamaydi (mavjud jadvalga @Index qo'shilsa Hibernate uni doim ham
-- yaratmaydi). Shu skript sxemani aniq holatga keltiradi va ikki marta
-- bajarilsa ham xato bermaydi.

CREATE TABLE IF NOT EXISTS conversations (
    id              BIGSERIAL PRIMARY KEY,
    uuid            UUID NOT NULL UNIQUE,
    -- DIRECT | GROUP. Matn, enum emas: yangi tur qo'shish migratsiyasiz.
    type            VARCHAR(20) NOT NULL,
    -- GROUP uchun; DIRECT da NULL — nom suhbatdoshdan olinadi.
    title           VARCHAR(255),
    -- DIRECT uchun "kichikId:kattaId". UNIQUE — bir juftlikka ikkinchi
    -- suhbat ochilmaydi, hatto ikki so'rov bir vaqtda kelsa ham.
    -- GROUP da NULL, PostgreSQL bir nechta NULL ni takror deb bilmaydi.
    direct_key      VARCHAR(64) UNIQUE,
    created_by      BIGINT REFERENCES users(id),
    last_message_at TIMESTAMP,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conversations_last_message
    ON conversations(last_message_at DESC);

CREATE TABLE IF NOT EXISTS conversation_participants (
    id                   BIGSERIAL PRIMARY KEY,
    conversation_id      BIGINT NOT NULL REFERENCES conversations(id),
    user_id              BIGINT NOT NULL REFERENCES users(id),
    -- FK emas: xabar o'chsa ham kursor buzilmasligi kerak.
    last_read_message_id BIGINT,
    is_pinned            BOOLEAN NOT NULL DEFAULT FALSE,
    is_muted             BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at            TIMESTAMP NOT NULL,
    -- Guruhdan chiqqan sana; qator o'chirilmaydi, aks holda eski
    -- xabarlarning muallifligi va o'qilganlik tarixi yo'qoladi.
    left_at              TIMESTAMP,
    CONSTRAINT uk_conv_participant UNIQUE (conversation_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_conv_participants_user
    ON conversation_participants(user_id);

CREATE TABLE IF NOT EXISTS messages (
    id              BIGSERIAL PRIMARY KEY,
    uuid            UUID NOT NULL UNIQUE,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id),
    sender_id       BIGINT NOT NULL REFERENCES users(id),
    text            TEXT,
    -- TEXT | SYSTEM; keyingi bosqichda IMAGE, FILE, VOICE shu ustunga
    -- qo'shiladi — matn bo'lgani uchun migratsiya kerak bo'lmaydi.
    type            VARCHAR(20) NOT NULL,
    reply_to_id     BIGINT,
    edited_at       TIMESTAMP,
    -- Yumshoq o'chirish. 1-bosqichda o'chirish endpointi yo'q, ustun
    -- boshidan turadi: keyin qo'shilganda eski xabarlar ko'chirilmaydi.
    deleted_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL
);

-- Vaqt bo'yicha o'qish
CREATE INDEX IF NOT EXISTS idx_messages_conversation_created
    ON messages(conversation_id, created_at DESC);

-- Kursor bo'yicha sahifalash va oxirgi xabar:
-- WHERE conversation_id = ? AND id < ? ORDER BY id DESC
CREATE INDEX IF NOT EXISTS idx_messages_conversation_id_desc
    ON messages(conversation_id, id DESC);

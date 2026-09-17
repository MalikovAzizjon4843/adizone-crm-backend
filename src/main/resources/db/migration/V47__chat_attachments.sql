-- Chat 3-bosqich: biriktirmalar, javob berish, tahrirlash, o'chirish.
--
-- messages jadvalidagi reply_to_id, edited_at va deleted_at ustunlari
-- 1-bosqichda (V45) yaratilgan va shu paytgacha bo'sh turgan edi —
-- endi ular to'ldiriladi, ya'ni bu yerda faqat yangi jadval bor.

CREATE TABLE IF NOT EXISTS message_attachments (
    id           BIGSERIAL PRIMARY KEY,
    uuid         UUID NOT NULL UNIQUE,
    message_id   BIGINT NOT NULL REFERENCES messages(id),
    -- Backend bergan yo'l: /api/files/<uuid>.<kengaytma>.
    -- Tashqi manzil saqlanmaydi — ChatAttachmentService.requireOwnUrl
    -- yozishdan oldin tekshiradi.
    file_url     VARCHAR(500) NOT NULL,
    -- Foydalanuvchi ko'radigan nom; diskdagi nom bundan boshqa.
    file_name    VARCHAR(255) NOT NULL,
    file_size    BIGINT,
    content_type VARCHAR(100),
    -- Faqat rasmlarda: frontend yuklashdan oldin joy ajratadi va
    -- lenta sakrab ketmaydi.
    width        INTEGER,
    height       INTEGER,
    -- Bitta xabardagi rasmlar galereyasining tartibi.
    sort_order   INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL
);

-- Lenta sahifasi biriktirmalarni bitta IN so'rovi bilan oladi va
-- darhol tartiblangan holda: WHERE message_id IN (...) ORDER BY sort_order
CREATE INDEX IF NOT EXISTS idx_message_attachments_message
    ON message_attachments(message_id, sort_order);

-- messages.type endi TEXT | IMAGE | FILE | SYSTEM qiymatlarini oladi.
-- Ustun VARCHAR va qiymat @Convert orqali yoziladi, shuning uchun
-- na o'zgartirish, na ma'lumot ko'chirish kerak. Hibernate yaratgan
-- CHECK constraint bo'lsa, uni EnumCheckConstraintCleaner startup'da
-- olib tashlaydi (app.schema.drop-enum-checks).

-- Fizik fayllar O'CHIRILMAYDI: xabar yumshoq o'chirilganda
-- message_attachments qatorlari ham, diskdagi fayllar ham joyida
-- qoladi. Adashib o'chirilgan xabarni tiklash mumkin bo'lsin.

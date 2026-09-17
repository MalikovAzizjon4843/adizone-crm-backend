-- Chat 2-bosqich: onlayn holat va qidiruv.
--
-- Onlaynlikning o'zi bazada saqlanmaydi — u xotirada, ChatPresenceService
-- dagi sessiya sanog'ida. Bazada faqat "oxirgi marta qachon ko'rindi"
-- qoladi: foydalanuvchi oflayn bo'lganda uni ko'rsatish kerak.

ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;

-- users.last_login ALOHIDA ustun bo'lib qoladi: u tizimga kirishni yozadi,
-- last_seen_at esa oxirgi WebSocket sessiyasi uzilgan paytni. Ochiq turgan
-- tab kun bo'yi last_seen_at ni yangilab turadi, last_login esa yo'q.

-- ── Qidiruv haqida ────────────────────────────────────────────────────
-- messages.text bo'yicha qidiruv LOWER(text) LIKE '%...%' shaklida ketadi
-- va btree indeks bunga yaramaydi — old tomoni ochiq naqsh uni ishlatmaydi.
-- Hozircha bu muammo emas: qidiruv har bir suhbat uchun emas, faqat
-- foydalanuvchi a'zo bo'lgan suhbatlar bo'yicha va LIMIT 20 bilan ketadi.
--
-- Xabarlar soni yuz minglarga yetganda pg_trgm kerak bo'ladi. U alohida
-- kengaytma va uni o'rnatish uchun baza superuser huquqi talab qilinadi,
-- shuning uchun bu yerda bajarilmaydi — kerak bo'lganda qo'lda:
--
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
--   CREATE INDEX CONCURRENTLY idx_messages_text_trgm
--       ON messages USING GIN (LOWER(text) gin_trgm_ops);

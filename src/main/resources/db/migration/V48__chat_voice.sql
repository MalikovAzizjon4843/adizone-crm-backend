-- Chat 4-bosqich: ovozli xabar.
--
-- Yangi jadval yo'q: ovozli xabar ham oddiy biriktirma, faqat ikkita
-- qo'shimcha ustuni bor. messages.type endi VOICE qiymatini ham oladi;
-- ustun VARCHAR va qiymat @Convert orqali yoziladi, shuning uchun u
-- yerda DDL kerak emas.

-- Ovoz uzunligi millisekundda. Brauzer hisoblaydi — server audio
-- oqimini ochmaydi, buning uchun kodek kutubxonasi kerak bo'lardi.
ALTER TABLE message_attachments ADD COLUMN IF NOT EXISTS duration_ms INTEGER;

-- To'lqin shakli: vergul bilan ajratilgan 0..100 oralig'idagi butun
-- sonlar, ko'pi bilan 50 ta ("12,45,78,34,...").
--
-- Alohida jadval emas va massiv ham emas: bu — chizish uchun qaraladigan
-- surat, so'rov solinadigan ma'lumot emas. Eng uzun holatda 50 ta uch
-- xonali son + verguli 200 belgidan oshmaydi, 500 esa zaxira bilan.
ALTER TABLE message_attachments ADD COLUMN IF NOT EXISTS waveform VARCHAR(500);

-- Indeks kerak emas: ikkala ustun ham faqat o'qib ko'rsatiladi,
-- hech qaysi so'rovda shart yoki saralash sifatida ishlatilmaydi.

-- DAY_1_WORKED..DAY_4_WORKED bosqichlari CONTACTED ga birlashtirildi.
-- Sabab: "necha marta urinildi" endi vazifalar lentasida saqlanadi,
-- kanbanda to'rtta deyarli bo'sh ustun turishi kerak emas.
--
-- LeadStatus enumidan bu qiymatlar olib tashlangan, shuning uchun skript
-- ilova YANGILANISHIDAN OLDIN bajarilishi kerak.
--
-- Eslatma: LeadStatus @Convert(LeadStatusConverter) bilan o'qiladi, ya'ni
-- ko'chirilmay qolgan qator istisno tashlamaydi — LeadStatus.fromLegacy()
-- uni CONTACTED ga aylantiradi. Skript baribir kerak: aks holda qiymat
-- bazada eski nomi bilan qolib, filtrlash va statistikada chalkashlik
-- keltiradi.

-- 1) Lidlarning joriy bosqichi
UPDATE leads
SET status = 'CONTACTED'
WHERE status IN ('DAY_1_WORKED', 'DAY_2_WORKED', 'DAY_3_WORKED', 'DAY_4_WORKED');

-- 2) Bosqichlar tarixi — ikkala ustun ham
UPDATE lead_status_history
SET from_status = 'CONTACTED'
WHERE from_status IN ('DAY_1_WORKED', 'DAY_2_WORKED', 'DAY_3_WORKED', 'DAY_4_WORKED');

UPDATE lead_status_history
SET to_status = 'CONTACTED'
WHERE to_status IN ('DAY_1_WORKED', 'DAY_2_WORKED', 'DAY_3_WORKED', 'DAY_4_WORKED');

-- 3) Izoh yozilgan paytdagi bosqich.
--    BU USTUN ODATDA E'TIBORDAN CHETDA QOLADI — lead_comments jadvali
--    ham LeadStatus saqlaydi.
UPDATE lead_comments
SET status_at_comment = 'CONTACTED'
WHERE status_at_comment IN ('DAY_1_WORKED', 'DAY_2_WORKED', 'DAY_3_WORKED', 'DAY_4_WORKED');

-- Tekshiruv: to'rttasi ham 0 bo'lishi kerak
-- SELECT 'leads' AS t, COUNT(*) FROM leads WHERE status LIKE 'DAY\_%' ESCAPE '\'
-- UNION ALL SELECT 'history.from', COUNT(*) FROM lead_status_history WHERE from_status LIKE 'DAY\_%' ESCAPE '\'
-- UNION ALL SELECT 'history.to',   COUNT(*) FROM lead_status_history WHERE to_status   LIKE 'DAY\_%' ESCAPE '\'
-- UNION ALL SELECT 'comments',     COUNT(*) FROM lead_comments WHERE status_at_comment LIKE 'DAY\_%' ESCAPE '\';

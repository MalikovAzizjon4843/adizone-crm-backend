-- group-lesson-type-backfill.sql — individual guruhlarni belgilash (payroll v3, V82 dan keyin). BAZADA O'Z-O'ZIDAN
-- BAJARILMAYDI: avval 1-bo'lim (faqat SELECT) natijasini egasi ko'rib tasdiqlaydi, keyin 2-bo'lim (UPDATE).
--
-- Qoida: guruh nomi "IND/..." bilan boshlanadi YOKI kurs nomida "individual" so'zi bor → INDIVIDUAL.
-- Faqat hozir GROUP bo'lganlar o'zgaradi (qo'lda INDIVIDUAL qilinganiga tegilmaydi). Yozilgan davrlarning
-- billing_periods.lesson_type si NULL bo'lsa, oylik hisobi guruhning joriy turini oladi — shuning uchun backfill
-- oktyabr oyligi hisoblanishidan OLDIN bajarilishi kerak (aks holda individual o'quvchilar guruh ulushi bilan).
--
--   1-bo'lim:  PGOPTIONS='-c default_transaction_read_only=on' psql "$PROD_RO_URL" -X -v ON_ERROR_STOP=1 \
--                  -f docs/ops/group-lesson-type-backfill.sql            (2-bo'lim read-only'da xato beradi — normal)
--   2-bo'lim:  tasdiqdan keyin, crm_user bilan, faqat shu bo'limni nusxalab bajaring.

-- ═══ 1. PREVIEW (faqat o'qiydi) ═══════════════════════════════════════════════════════════════════════════
SELECT g.id, g.group_name, g.status, g.lesson_type AS hozir, c.course_name,
       CASE WHEN g.group_name ILIKE 'IND/%' THEN 'nom IND/...' ELSE 'kurs nomi: individual' END AS sabab,
       (SELECT count(*) FROM student_groups sg WHERE sg.group_id = g.id AND sg.is_active) AS faol_yozilmalar,
       g.teacher_id
  FROM groups g
  LEFT JOIN courses c ON c.id = g.course_id
 WHERE g.lesson_type = 'GROUP'
   AND (g.group_name ILIKE 'IND/%' OR c.course_name ILIKE '%individual%')
 ORDER BY g.status, g.group_name;

-- Shubhali (nomida "ind" bor, lekin qoidaga tushmaydi) — qo'lda ko'rish uchun
SELECT g.id, g.group_name, g.status, c.course_name
  FROM groups g
  LEFT JOIN courses c ON c.id = g.course_id
 WHERE g.lesson_type = 'GROUP'
   AND (g.group_name ILIKE '%ind%' OR c.course_name ILIKE '%ind%')
   AND NOT (g.group_name ILIKE 'IND/%' OR c.course_name ILIKE '%individual%')
 ORDER BY g.group_name;

-- ═══ 2. QO'LLASH (tasdiqdan keyin, alohida) ═══════════════════════════════════════════════════════════════
BEGIN;

UPDATE groups g
   SET lesson_type = 'INDIVIDUAL'
  FROM (SELECT g2.id
          FROM groups g2
          LEFT JOIN courses c ON c.id = g2.course_id
         WHERE g2.lesson_type = 'GROUP'
           AND (g2.group_name ILIKE 'IND/%' OR c.course_name ILIKE '%individual%')) x
 WHERE g.id = x.id;

-- Kurs nomida "individual" bo'lgan kurslar — yangi guruhlar uchun standart
UPDATE courses SET lesson_type = 'INDIVIDUAL'
 WHERE lesson_type IS NULL AND course_name ILIKE '%individual%';

-- Yozilgan davrlar snapshot'i (NULL bo'lganlari) — guruh turidan
UPDATE billing_periods bp
   SET lesson_type = g.lesson_type
  FROM student_groups sg
  JOIN groups g ON g.id = sg.group_id
 WHERE sg.id = bp.student_group_id AND bp.lesson_type IS NULL;

COMMIT;

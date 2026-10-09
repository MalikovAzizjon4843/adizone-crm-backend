-- students.source backfill — lid bilan bog'langan o'quvchilarga lid manbasi (V79 dan KEYIN, QO'LDA).
--
-- Bog'lanish: students.converted_from_lead_id (konvertatsiya yozadi, FK yo'q) — asosiy; leads.student_id (FK) —
-- zaxira (eski yozuvlarda converted_from_lead_id bo'sh bo'lishi mumkin). Bir o'quvchiga bir nechta lid ishora qilsa
-- converted_from_lead_id dagisi, u bo'lmasa eng oxirgi yaratilgan lid olinadi.
-- Qiymat kod bilan bir xil: UPPER(TRIM(leads.source)), 30 belgigacha (SourceCatalog.fromLead) — ro'yxatda
-- bo'lmagan xom qiymat ham AYNAN ko'chiriladi. Faqat students.source IS NULL qatorlar — qo'lda kiritilgan yoki
-- yangi konvertatsiya yozgan qiymat ustiga yozilmaydi. Idempotent: qayta bajarilsa 0 qator.
-- marketing_source (eski enum) TEGILMAYDI.
--
-- Ishlatish (psql, crm_user, -X -v ON_ERROR_STOP=1):
--   1) Ko'rib chiqish — hech narsa yozmaydi (2-bo'lim "read-only transaction" xatosi bilan to'xtaydi — kutilgan):
--        PGOPTIONS='-c default_transaction_read_only=on' psql ... -X -v ON_ERROR_STOP=1 -f student-source-backfill.sql
--   2) Qo'llash: psql ... -X -v ON_ERROR_STOP=1 -f student-source-backfill.sql
--      2-bo'lim oxiridagi tekshiruv yangilangan son preview (1b) dagi bilan teng bo'lmasa ROLLBACK qiladi.

-- ── 1. KO'RIB CHIQISH (faqat o'qiydi) ───────────────────────────────────────────────
-- 1a. Hozirgi holat: manbasi bor / yo'q o'quvchilar, lid bilan bog'langanlar
SELECT COUNT(*)                                                    AS jami_oquvchi,
       COUNT(*) FILTER (WHERE source IS NOT NULL)                  AS manbasi_bor,
       COUNT(*) FILTER (WHERE source IS NULL)                      AS manbasiz,
       COUNT(*) FILTER (WHERE converted_from_lead_id IS NOT NULL)  AS lid_bilan_converted_from
  FROM students;

-- 1b. Yangilanadigan qatorlar: manba bo'yicha soni (2-bo'limdagi UPDATE soni = shu jami)
WITH pick AS (
    SELECT DISTINCT ON (s.id)
           s.id AS student_id, l.id AS lead_id, LEFT(UPPER(TRIM(l.source)), 30) AS new_source
      FROM students s
      JOIN leads l ON l.id = s.converted_from_lead_id OR l.student_id = s.id
     WHERE s.source IS NULL
       AND l.source IS NOT NULL AND TRIM(l.source) <> ''
     ORDER BY s.id, (l.id = s.converted_from_lead_id) DESC, l.created_at DESC NULLS LAST, l.id DESC
)
SELECT new_source, COUNT(*) AS soni FROM pick GROUP BY new_source
UNION ALL
SELECT '== JAMI ==', COUNT(*) FROM pick
 ORDER BY 2 DESC, 1;

-- 1c. Namuna (20 ta): o'quvchi, lid, yangi manba
WITH pick AS (
    SELECT DISTINCT ON (s.id)
           s.id AS student_id, s.first_name, s.last_name, l.id AS lead_id, l.source AS lead_source,
           LEFT(UPPER(TRIM(l.source)), 30) AS new_source
      FROM students s
      JOIN leads l ON l.id = s.converted_from_lead_id OR l.student_id = s.id
     WHERE s.source IS NULL
       AND l.source IS NOT NULL AND TRIM(l.source) <> ''
     ORDER BY s.id, (l.id = s.converted_from_lead_id) DESC, l.created_at DESC NULLS LAST, l.id DESC
)
SELECT * FROM pick ORDER BY student_id LIMIT 20;

-- 1d. Lid bilan bog'lanmagan manbasiz o'quvchilar — backfill ularga TEGMAYDI (qo'lda yoki marketing_source dan
--     alohida qaror bilan). Ma'lumot uchun: eski marketing_source taqsimoti.
SELECT COALESCE(s.marketing_source, '(null)') AS marketing_source, COUNT(*) AS soni
  FROM students s
 WHERE s.source IS NULL
   AND s.converted_from_lead_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM leads l WHERE l.student_id = s.id)
 GROUP BY 1
 ORDER BY 2 DESC;

-- ── 2. QO'LLASH ─────────────────────────────────────────────────────────────────────
BEGIN;

CREATE TEMP TABLE student_source_pick ON COMMIT DROP AS
SELECT DISTINCT ON (s.id)
       s.id AS student_id, LEFT(UPPER(TRIM(l.source)), 30) AS new_source
  FROM students s
  JOIN leads l ON l.id = s.converted_from_lead_id OR l.student_id = s.id
 WHERE s.source IS NULL
   AND l.source IS NOT NULL AND TRIM(l.source) <> ''
 ORDER BY s.id, (l.id = s.converted_from_lead_id) DESC, l.created_at DESC NULLS LAST, l.id DESC;

UPDATE students s
   SET source = p.new_source
  FROM student_source_pick p
 WHERE s.id = p.student_id
   AND s.source IS NULL;

-- Tekshiruv: tanlangan barcha qatorlar yangilandi, bog'langan manbasiz o'quvchi qolmadi
DO $$
DECLARE
    picked  bigint;
    left_   bigint;
BEGIN
    SELECT COUNT(*) INTO picked FROM student_source_pick;
    SELECT COUNT(*) INTO left_
      FROM students s
      JOIN student_source_pick p ON p.student_id = s.id
     WHERE s.source IS NULL;
    IF left_ > 0 THEN
        RAISE EXCEPTION 'student-source-backfill: % ta qator yangilanmadi (tanlangan %)', left_, picked;
    END IF;
    RAISE NOTICE 'student-source-backfill: % ta o''quvchiga manba yozildi', picked;
END $$;

COMMIT;

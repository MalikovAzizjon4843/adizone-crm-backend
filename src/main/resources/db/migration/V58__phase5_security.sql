-- Phase 5 xavfsizlik bloki (docs/audit/phase5-audit.md §12.1): token versiyasi, shartnoma
-- raqami sequence'i, imtihon ro'yxatining UNIQUE indeksi.
--
-- V57 dan KEYIN, QO'LDA bajariladi (Flyway yo'q). Idempotent: qayta ishga tushirish xavfsiz,
-- har bo'lak IF NOT EXISTS yoki oldindan tekshiruv bilan. Ilovani yangilashdan OLDIN bajarish
-- tavsiya etiladi; keyin bajarilsa ham to'g'ri (ddl-auto ustunni nullable qo'shadi, bu skript
-- uni to'ldirib NOT NULL qiladi).
--
-- Tekshirish: docs/ops/prod-schema-check.sql — V58 bo'laklari "BOR" chiqishi kerak.

BEGIN;

-- ── 1. users.token_version (U-05) ───────────────────────────────────
-- Access token ichidagi "tv" claim'i shu qiymat bilan solishtiriladi. Parol almashtirilsa yoki
-- tiklansa ilova uni oshiradi — eski JWT lar keyingi so'rovdayoq rad etiladi. Claim'siz eski
-- tokenlar 0-versiya hisoblanadi, ya'ni deploydan keyin hech kim tizimdan chiqib ketmaydi.
ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version INTEGER;
UPDATE users SET token_version = 0 WHERE token_version IS NULL;
ALTER TABLE users ALTER COLUMN token_version SET DEFAULT 0;
ALTER TABLE users ALTER COLUMN token_version SET NOT NULL;

-- ── 2. Shartnoma raqami: contract_number_seq (C-01, Q8) ─────────────
-- Format CTR-YYYY-NNNNN; NNNNN shu sequence'dan, yil almashganda nolga qaytmaydi.
-- Mavjud CTR-0001 ko'rinishidagi raqamlar o'zgarmaydi va yangi formatga to'g'ri kelmaydi.
-- Sequence yangi formatdagi eng katta raqamdan davom etadi — qayta ishga tushirilsa ham to'g'ri.
CREATE SEQUENCE IF NOT EXISTS contract_number_seq;
SELECT setval('contract_number_seq',
    COALESCE((SELECT MAX(SUBSTRING(contract_number FROM 10)::BIGINT)
              FROM contracts WHERE contract_number ~ '^CTR-[0-9]{4}-[0-9]+$'), 0) + 1,
    false);

-- ── 3. exam_registrations: (exam_id, student_id) takrorlanmasin (E-01, E-02) ──
-- V27 dagi UNIQUE ko'p bazada yo'q (jadvalni Hibernate yaratgan). Ro'yxatga olish E-01 bilan
-- yana ishlaydi, parallel ikki so'rov dublikat yaratmasin: UNIQUE buzilishi endi 409
-- (error.conflict.duplicate). Dublikatlar bo'lsa indeks YARATILMAYDI — NOTICE, qo'lda tozalanadi.
CREATE INDEX IF NOT EXISTS idx_exam_registrations_exam ON exam_registrations (exam_id);
CREATE INDEX IF NOT EXISTS idx_exam_registrations_student ON exam_registrations (student_id);

DO $$
DECLARE
    dup TEXT;
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pg_index i
         WHERE i.indrelid = 'exam_registrations'::regclass AND i.indisunique AND i.indpred IS NULL
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                  FROM pg_attribute a
                 WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey))
               = ARRAY['exam_id', 'student_id']
    ) THEN
        RETURN;  -- V27 yoki oldingi V58 allaqachon yaratgan
    END IF;

    SELECT string_agg(exam_id || '/' || student_id, ', ')
      INTO dup
      FROM (SELECT exam_id, student_id FROM exam_registrations
             GROUP BY exam_id, student_id HAVING COUNT(*) > 1) d;
    IF dup IS NOT NULL THEN
        RAISE NOTICE 'exam_registrations: dublikatlar bor (exam/student: %) — UNIQUE indeks yaratilmadi. Ortiqchasini o''chirib, skriptni qayta ishga tushiring.', dup;
    ELSE
        CREATE UNIQUE INDEX ux_exam_registrations_exam_student
            ON exam_registrations (exam_id, student_id);
    END IF;
END $$;

COMMIT;

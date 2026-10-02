-- Eski (qo'lda migratsiyalar va ddl-auto qoldig'i) cheklovlarni billing/payroll v2 mantiqiga moslash.
--
-- V55 dan KEYIN, QO'LDA bajariladi. Idempotent: cheklovlar NOMI bo'yicha emas, USTUNLARI bo'yicha
-- topiladi (prod'da nomlar farq qilishi mumkin — lokal bazada bir xil cheklov ikki nom bilan bor:
-- payroll_teacher_id_month_year_key va Hibernate yaratgan uk5duwev7ya2dq0c0q71wfbloyu).
-- Audit va asoslash: docs/design/payroll-v2.md §12.

BEGIN;

-- ── 1. payroll: UNIQUE (teacher_id, month, year) — shartsiz ──────────
-- v2 da CANCELLED oylikdan keyin shu oy uchun yangi DRAFT yaratiladi (payroll-v2 §1). Shartsiz
-- cheklov buni 500 (duplicate key) bilan to'xtatardi. O'rniga — faqat faol (CANCELLED emas) oyliklar
-- bo'yicha qisman indeks (user_id bo'yicha V54 dagi uk_payroll_user_month_year_active bilan juft).
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'payroll'::regclass AND c.contype = 'u'
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                  FROM pg_attribute a
                 WHERE a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey))
               IN (ARRAY['month', 'teacher_id', 'year'], ARRAY['month', 'user_id', 'year'])
    LOOP
        EXECUTE format('ALTER TABLE payroll DROP CONSTRAINT %I', r.conname);
        RAISE NOTICE 'payroll: % olib tashlandi', r.conname;
    END LOOP;
    -- Constraint'siz shartsiz UNIQUE indekslar (qo'lda yaratilgan bo'lishi mumkin)
    FOR r IN
        SELECT i.indexrelid::regclass::text AS idx
          FROM pg_index i
         WHERE i.indrelid = 'payroll'::regclass AND i.indisunique AND NOT i.indisprimary
           AND i.indpred IS NULL
           AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid)
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                  FROM pg_attribute a
                 WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey))
               IN (ARRAY['month', 'teacher_id', 'year'], ARRAY['month', 'user_id', 'year'])
    LOOP
        EXECUTE format('DROP INDEX %s', r.idx);
        RAISE NOTICE 'payroll: indeks % olib tashlandi', r.idx;
    END LOOP;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_payroll_teacher_month_year_active
    ON payroll (teacher_id, month, year)
    WHERE teacher_id IS NOT NULL AND status <> 'CANCELLED';

-- ── 2. payroll.teacher_id → teachers: ON DELETE CASCADE ───────────────
-- O'qituvchi o'chirilsa uning PAID oyliklari ham o'chib ketardi (moliyaviy tarix yo'qoladi, kassadagi
-- payroll_id yetim qoladi). CASCADE FK olib tashlanadi; NO ACTION FK (Hibernate) qoladi, u ham yo'q
-- bo'lsa — yaratiladi.
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'payroll'::regclass AND c.contype = 'f'
           AND c.confrelid = 'teachers'::regclass AND a.attname = 'teacher_id'
           AND c.confdeltype = 'c'
    LOOP
        EXECUTE format('ALTER TABLE payroll DROP CONSTRAINT %I', r.conname);
        RAISE NOTICE 'payroll: CASCADE FK % olib tashlandi', r.conname;
    END LOOP;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'payroll'::regclass AND c.contype = 'f'
           AND c.confrelid = 'teachers'::regclass AND a.attname = 'teacher_id'
    ) THEN
        ALTER TABLE payroll ADD CONSTRAINT fk_payroll_teacher FOREIGN KEY (teacher_id) REFERENCES teachers(id);
    END IF;
END $$;

-- ── 3. student_groups: UNIQUE (student_id, group_id, join_date) ───────
-- v2 da yopilgan yozilma (SG) o'chirilmaydi — ledger unga bog'langan (I3). Shu guruhga shu sana bilan
-- qayta qo'shish (chiqarib, xatoni tuzatib qayta qo'shish; bir kunda qaytgan transfer) yangi SG yaratadi
-- va eski cheklov 500 berardi. O'rniga bazada YANGI cheklov qo'yilmaydi: "bir guruhda bitta faol
-- yozilma" qoidasini kod ta'minlaydi (GroupService.addStudentToGroup), eski ma'lumotdagi faol
-- dublikatlar esa billing migratsiyasida A4 anomaliyasi sifatida hold qilinadi (billing-v2 §9) —
-- qisman UNIQUE indeks ularni ifodalab bo'lmas qilib qo'yardi.
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'student_groups'::regclass AND c.contype = 'u'
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                  FROM pg_attribute a
                 WHERE a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey))
               = ARRAY['group_id', 'join_date', 'student_id']
    LOOP
        EXECUTE format('ALTER TABLE student_groups DROP CONSTRAINT %I', r.conname);
        RAISE NOTICE 'student_groups: % olib tashlandi', r.conname;
    END LOOP;
END $$;

COMMIT;

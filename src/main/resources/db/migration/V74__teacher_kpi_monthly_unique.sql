-- teacher_kpi_monthly: UNIQUE (teacher_id, month_start) yetishmasa — qo'shish (V72 tuzatmasi).
--
-- Prod holati (2026-10-04): jadvalni ddl-auto: update (Hibernate) V72 dan oldin yaratgan — CREATE TABLE IF NOT
-- EXISTS o'tkazib yuborilgan; jadvalda faqat pkey va FK bor, UNIQUE yo'q (entity UNIQUE bermaydi — u V72 ga
-- topshirilgan). V72 dagi CREATE UNIQUE INDEX dublikat qatorlarda yiqiladi; skript auto-commit rejimida (yoki
-- ON_ERROR_STOP siz) bajarilgan bo'lsa, xato o'tkazib yuborilib FK qo'shilgan. UNIQUE bo'lmasa bir o'qituvchi +
-- oy uchun ikkita snapshot qatori paydo bo'lishi mumkin (parallel job/qayta hisoblash).
--
-- Bu skript:
--   1) jadval va kerakli ustunlar (teacher_id, month_start, computed_at) borligini tekshiradi — yo'q bo'lsa XATO;
--   2) (teacher_id, month_start) bo'yicha UNIQUE indeks yoki cheklov (NOMIDAN qat'i nazar) bormi — bor bo'lsa hech
--      narsa qilmaydi;
--   3) dublikatlarni NOTICE bilan ko'rsatadi (qaysi id qoladi, qaysilari o'chadi) va eng yangisini qoldiradi
--      (computed_at, keyin id — TeacherKpiService.FRESHEST bilan bir xil). Snapshot — hosila ma'lumot: kerak bo'lsa
--      POST /api/teachers/kpi/snapshots?month=YYYY-MM bilan qayta hisoblanadi;
--   4) shu nomli, lekin UNIQUE bo'lmagan indeks bo'lsa — olib tashlab, ux_teacher_kpi_monthly_teacher_month ni
--      UNIQUE qilib yaratadi.
--
-- V72 dan KEYIN, QO'LDA, crm_user bilan, psql -v ON_ERROR_STOP=1. Idempotent: qayta bajarilsa "UNIQUE allaqachon
-- bor" NOTICE va hech narsa o'zgarmaydi. Tekshirish: docs/ops/prod-schema-check.sql — "V72/V74 teacher_kpi_monthly
-- UNIQUE(teacher_id, month_start)" = true va dublikatlar soni 0.

BEGIN;

DO $$
DECLARE
    missing_cols text;
    r            record;
    groups_cnt   int := 0;
    removed      int := 0;
BEGIN
    IF to_regclass('public.teacher_kpi_monthly') IS NULL THEN
        RAISE EXCEPTION 'teacher_kpi_monthly jadvali yo''q — avval V72 ni bajaring';
    END IF;

    SELECT string_agg(c, ', ') INTO missing_cols
      FROM unnest(ARRAY['teacher_id', 'month_start', 'computed_at']) AS c
     WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'teacher_kpi_monthly'
                          AND column_name = c);
    IF missing_cols IS NOT NULL THEN
        RAISE EXCEPTION 'teacher_kpi_monthly: ustun(lar) yo''q: % — jadval TeacherKpiMonthly entity bilan mos emas',
            missing_cols;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_index i
         WHERE i.indrelid = 'teacher_kpi_monthly'::regclass
           AND i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                  FROM pg_attribute a
                 WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)) = ARRAY['month_start', 'teacher_id']
    ) THEN
        RAISE NOTICE 'teacher_kpi_monthly: UNIQUE (teacher_id, month_start) allaqachon bor — o''zgarish yo''q';
        RETURN;
    END IF;

    FOR r IN
        SELECT teacher_id, month_start, COUNT(*) AS n,
               (array_agg(id ORDER BY computed_at DESC NULLS LAST, id DESC))[1] AS keep_id,
               string_agg(id::text, ', ' ORDER BY id) AS ids
          FROM teacher_kpi_monthly
         GROUP BY teacher_id, month_start
        HAVING COUNT(*) > 1
         ORDER BY month_start, teacher_id
    LOOP
        groups_cnt := groups_cnt + 1;
        RAISE NOTICE 'teacher_kpi_monthly dublikat: teacher_id=%, month_start=%, qatorlar=% (id: %) — qoladi id=%',
            r.teacher_id, r.month_start, r.n, r.ids, r.keep_id;
    END LOOP;

    IF groups_cnt > 0 THEN
        WITH ranked AS (
            SELECT id, row_number() OVER (PARTITION BY teacher_id, month_start
                                          ORDER BY computed_at DESC NULLS LAST, id DESC) AS rn
              FROM teacher_kpi_monthly
        )
        DELETE FROM teacher_kpi_monthly t USING ranked x WHERE t.id = x.id AND x.rn > 1;
        GET DIAGNOSTICS removed = ROW_COUNT;
        RAISE NOTICE 'teacher_kpi_monthly: % guruhda % ta eski dublikat qator o''chirildi', groups_cnt, removed;
    ELSE
        RAISE NOTICE 'teacher_kpi_monthly: dublikat yo''q';
    END IF;

    IF to_regclass('public.ux_teacher_kpi_monthly_teacher_month') IS NOT NULL THEN
        -- Shu nomli, lekin UNIQUE emas (yoki boshqa ustunlarda) — yuqoridagi tekshiruvdan o'tmagan
        RAISE NOTICE 'teacher_kpi_monthly: ux_teacher_kpi_monthly_teacher_month UNIQUE emas — qayta yaratiladi';
        DROP INDEX ux_teacher_kpi_monthly_teacher_month;
    END IF;
    CREATE UNIQUE INDEX ux_teacher_kpi_monthly_teacher_month ON teacher_kpi_monthly (teacher_id, month_start);
    RAISE NOTICE 'teacher_kpi_monthly: UNIQUE (teacher_id, month_start) qo''shildi';
END $$;

COMMIT;

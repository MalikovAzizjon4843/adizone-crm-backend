-- O'qituvchi KPI oy yakuni snapshot'i: GET /api/teachers/{id}/kpi?month=YYYY-MM va /kpi/ranking?month=
-- yopilgan oy uchun shu jadvaldan o'qiydi. Har oyning 1-kuni 01:00 (Asia/Tashkent) da o'tgan oy yoziladi
-- (TeacherKpiSnapshotService); SUPER_ADMIN qayta hisoblaydi: POST /api/teachers/kpi/snapshots?month=YYYY-MM.
--
-- V71 dan KEYIN, QO'LDA, crm_user bilan bajariladi (deploy-v2.md §3.3). Idempotent: qayta bajarilsa hech
-- narsa o'zgarmaydi. ddl-auto: update jadvalni o'zi ham yaratadi — bu skript UNIQUE (teacher_id, month_start)
-- va FK (ON DELETE CASCADE) ni qo'shadi (entity ularni bermaydi).
--
-- Tekshirish: docs/ops/prod-schema-check.sql — V72 bo'laklari "TO'LIQ" chiqishi kerak.

BEGIN;

CREATE TABLE IF NOT EXISTS teacher_kpi_monthly (
    id                    BIGSERIAL         PRIMARY KEY,
    teacher_id            BIGINT            NOT NULL,
    month_start           DATE              NOT NULL,     -- oyning 1-kuni
    attendance_present    INTEGER           NOT NULL,     -- PRESENT + LATE
    attendance_total      INTEGER           NOT NULL,     -- shu oyda belgilangan davomat yozuvlari
    periods_decided       INTEGER           NOT NULL,     -- muddati shu oyda: to'langan yoki grace tugagan
    periods_paid          INTEGER           NOT NULL,
    periods_on_time       INTEGER           NOT NULL,     -- paid_on <= grace_until
    periods_pending       INTEGER           NOT NULL,     -- hali grace ichida, to'lanmagan (maxrajda yo'q)
    open_at_end           INTEGER           NOT NULL,     -- oy oxirida ochiq yozilmalar (sinovsiz)
    graduated             INTEGER           NOT NULL,
    churned               INTEGER           NOT NULL,     -- TRANSFERRED / FROZEN / GRADUATED emas
    attendance_rate       DOUBLE PRECISION,               -- NULL = ma'lumot yetarli emas (maxraj 0)
    payment_rate          DOUBLE PRECISION,
    on_time_payment_rate  DOUBLE PRECISION,
    retention_rate        DOUBLE PRECISION,
    overall_score         DOUBLE PRECISION,
    insufficient_data     BOOLEAN           NOT NULL,
    group_count           INTEGER           NOT NULL,
    student_count         INTEGER           NOT NULL,
    source                VARCHAR(20)       NOT NULL,     -- JOB | MANUAL
    computed_at           TIMESTAMP         NOT NULL
);
-- Reyting: WHERE month_start = ?
CREATE INDEX IF NOT EXISTS idx_teacher_kpi_monthly_month ON teacher_kpi_monthly (month_start);

-- Bitta o'qituvchi + oy uchun bitta qator (qayta hisoblash ustidan yozadi). Jadvalni Hibernate oldinroq
-- yaratgan bo'lishi mumkin (UNIQUE siz) va unda dublikat bo'lishi mumkin — "IF NOT EXISTS" nom bo'yicha yetmaydi:
-- ustunlar bo'yicha tekshiriladi, dublikatlar NOTICE bilan ko'rsatilib eng yangisi qoladi (V74 bilan bir xil).
DO $$
DECLARE
    r       record;
    removed int := 0;
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_index i
         WHERE i.indrelid = 'teacher_kpi_monthly'::regclass
           AND i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                  FROM pg_attribute a
                 WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)) = ARRAY['month_start', 'teacher_id']
    ) THEN
        RETURN;
    END IF;
    FOR r IN
        SELECT teacher_id, month_start, COUNT(*) AS n,
               (array_agg(id ORDER BY computed_at DESC NULLS LAST, id DESC))[1] AS keep_id,
               string_agg(id::text, ', ' ORDER BY id) AS ids
          FROM teacher_kpi_monthly
         GROUP BY teacher_id, month_start
        HAVING COUNT(*) > 1
    LOOP
        RAISE NOTICE 'teacher_kpi_monthly dublikat: teacher_id=%, month_start=%, qatorlar=% (id: %) — qoladi id=%',
            r.teacher_id, r.month_start, r.n, r.ids, r.keep_id;
    END LOOP;
    WITH ranked AS (
        SELECT id, row_number() OVER (PARTITION BY teacher_id, month_start
                                      ORDER BY computed_at DESC NULLS LAST, id DESC) AS rn
          FROM teacher_kpi_monthly
    )
    DELETE FROM teacher_kpi_monthly t USING ranked x WHERE t.id = x.id AND x.rn > 1;
    GET DIAGNOSTICS removed = ROW_COUNT;
    IF removed > 0 THEN
        RAISE NOTICE 'teacher_kpi_monthly: % ta eski dublikat qator o''chirildi', removed;
    END IF;
    IF to_regclass('public.ux_teacher_kpi_monthly_teacher_month') IS NOT NULL THEN
        DROP INDEX ux_teacher_kpi_monthly_teacher_month;
    END IF;
    CREATE UNIQUE INDEX ux_teacher_kpi_monthly_teacher_month ON teacher_kpi_monthly (teacher_id, month_start);
END $$;

DO $$
BEGIN
    -- FK ustuni bo'yicha tekshiriladi (Hibernate nomi bilan yaratilgan bo'lsa takrorlanmaydi)
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'teacher_kpi_monthly'::regclass AND c.contype = 'f'
           AND c.confrelid = 'teachers'::regclass AND a.attname = 'teacher_id'
    ) THEN
        ALTER TABLE teacher_kpi_monthly ADD CONSTRAINT fk_teacher_kpi_monthly_teacher
            FOREIGN KEY (teacher_id) REFERENCES teachers(id) ON DELETE CASCADE;
    END IF;
END $$;

COMMIT;

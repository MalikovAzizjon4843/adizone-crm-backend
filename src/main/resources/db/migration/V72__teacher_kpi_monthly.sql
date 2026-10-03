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
-- Bitta o'qituvchi + oy uchun bitta qator (qayta hisoblash ustidan yozadi)
CREATE UNIQUE INDEX IF NOT EXISTS ux_teacher_kpi_monthly_teacher_month
    ON teacher_kpi_monthly (teacher_id, month_start);
-- Reyting: WHERE month_start = ?
CREATE INDEX IF NOT EXISTS idx_teacher_kpi_monthly_month ON teacher_kpi_monthly (month_start);

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

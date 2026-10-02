-- Ta'tillar, "darsni X o'tdi" (o'rinbosar) va o'rinbosar dars stavkasi
-- (docs/design/leaves-exams-contracts.md §1, §2, §3.3, §7).
--
-- V60 dan KEYIN, QO'LDA bajariladi (Flyway yo'q). Idempotent: qayta bajarilsa hech narsa
-- o'zgarmaydi. Ilovani yangilashdan OLDIN bajarish tavsiya etiladi; KEYIN bajarilsa ham to'g'ri
-- (ilova eski status/leave_type matnlarini ham o'qiydi — LeaveStatus/LeaveType.fromDb).
--
--   * leave_requests: user_id (kim ta'tilda — barcha xodimlar) + backfill, paid, decided_*, cancelled_*;
--     eski approved_* → decided_*; eski "[Rad etish] ..." izohi reason dan decision_note ga;
--     status/leave_type normallashtiriladi; mavjud APPROVED → paid = TRUE (eski ta'tillar oylikka
--     ta'sir qilmagan — retroaktiv ayirma bo'lmasin); CHECK lar, indekslar, (imkon bo'lsa) EXCLUDE;
--   * lesson_substitutions + qisman UNIQUE (group_id, lesson_date) WHERE status <> 'CANCELLED';
--   * salary_rules.substitute_lesson_rate (TEACHER, bir o'tilgan dars uchun qat'iy summa).
--
-- Tekshirish: docs/ops/prod-schema-check.sql — V61 bo'laklari "TO'LIQ" chiqishi kerak.

BEGIN;

-- ── 1. leave_requests: yangi ustunlar ───────────────────────────────────────────────────
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS user_id       BIGINT;
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS paid          BOOLEAN;
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS decided_by    BIGINT;
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS decided_at    TIMESTAMP;
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS decision_note TEXT;
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS cancelled_by  BIGINT;
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS cancelled_at  TIMESTAMP;

-- Kim ta'tilda: o'qituvchi bo'lsa — uning useri, aks holda ariza beruvchi
UPDATE leave_requests l
   SET user_id = t.user_id
  FROM teachers t
 WHERE l.user_id IS NULL AND l.teacher_id = t.id AND t.user_id IS NOT NULL;
UPDATE leave_requests SET user_id = requester_id WHERE user_id IS NULL AND requester_id IS NOT NULL;

-- Eski approved_by/approved_at (ustun bo'lsa) → decided_*
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'leave_requests' AND column_name = 'approved_by') THEN
        EXECUTE 'UPDATE leave_requests SET decided_by = approved_by WHERE decided_by IS NULL AND approved_by IS NOT NULL';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'leave_requests' AND column_name = 'approved_at') THEN
        EXECUTE 'UPDATE leave_requests SET decided_at = approved_at WHERE decided_at IS NULL AND approved_at IS NOT NULL';
    END IF;
END $$;

-- Holat va tur: katta harf; noma'lum holat — PENDING (NOTICE bilan)
DO $$
DECLARE
    odd TEXT;
BEGIN
    SELECT string_agg(DISTINCT COALESCE(status, 'NULL'), ', ') INTO odd
      FROM leave_requests
     WHERE status IS NULL OR UPPER(TRIM(status)) NOT IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED');
    IF odd IS NOT NULL THEN
        RAISE NOTICE 'leave_requests.status: noma''lum qiymatlar PENDING ga o''tkazildi: %', odd;
    END IF;
END $$;
-- paid shu UPDATE ning o'zida: qayta bajarilganda ck_leave_requests_paid allaqachon bor — 'approved' → 'APPROVED'
-- paid NULL bilan qolsa CHECK buziladi (eski jar kichik harfli status yozgan bo'lishi mumkin)
UPDATE leave_requests
   SET status = CASE WHEN UPPER(TRIM(status)) IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')
                     THEN UPPER(TRIM(status)) ELSE 'PENDING' END,
       paid   = CASE WHEN UPPER(TRIM(status)) = 'APPROVED' THEN COALESCE(paid, TRUE) ELSE paid END
 WHERE status IS NULL OR status <> UPPER(TRIM(status))
    OR UPPER(TRIM(status)) NOT IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED');

UPDATE leave_requests
   SET leave_type = CASE UPPER(TRIM(leave_type))
                        WHEN 'VACATION' THEN 'ANNUAL'
                        WHEN 'SICKNESS' THEN 'SICK'
                        WHEN 'ILLNESS'  THEN 'SICK'
                        WHEN 'MEDICAL'  THEN 'SICK'
                        WHEN 'PERSONAL' THEN 'FAMILY'
                        WHEN 'EDUCATION' THEN 'STUDY'
                        ELSE UPPER(TRIM(leave_type)) END
 WHERE leave_type IS NOT NULL AND leave_type <> CASE UPPER(TRIM(leave_type))
                        WHEN 'VACATION' THEN 'ANNUAL'
                        WHEN 'SICKNESS' THEN 'SICK'
                        WHEN 'ILLNESS'  THEN 'SICK'
                        WHEN 'MEDICAL'  THEN 'SICK'
                        WHEN 'PERSONAL' THEN 'FAMILY'
                        WHEN 'EDUCATION' THEN 'STUDY'
                        ELSE UPPER(TRIM(leave_type)) END;
DO $$
DECLARE
    odd TEXT;
BEGIN
    SELECT string_agg(DISTINCT leave_type, ', ') INTO odd
      FROM leave_requests
     WHERE leave_type NOT IN ('ANNUAL', 'SICK', 'FAMILY', 'STUDY', 'OTHER');
    IF odd IS NOT NULL THEN
        RAISE NOTICE 'leave_requests.leave_type: noma''lum turlar (ilova OTHER deb ko''rsatadi): %', odd;
    END IF;
END $$;

-- L-08: eski kod rad etish sababini reason ga "[Rad etish] ..." qilib qo'shardi → decision_note
UPDATE leave_requests
   SET decision_note = substring(reason FROM '\[Rad etish\] (.*)$'),
       reason = NULLIF(btrim(regexp_replace(reason, '\n?\[Rad etish\] .*$', '')), '')
 WHERE decision_note IS NULL AND reason LIKE '%[Rad etish] %';

-- Mavjud APPROVED — haqli (retroaktiv oylik ayirmasi bo'lmasin); boshqa holatlarda paid NULL
UPDATE leave_requests SET paid = TRUE WHERE status = 'APPROVED' AND paid IS NULL;
UPDATE leave_requests SET paid = NULL WHERE status <> 'APPROVED' AND paid IS NOT NULL;

-- ── 2. leave_requests: cheklovlar, FK, indekslar ────────────────────────────────────────
DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT COUNT(*) INTO n FROM leave_requests WHERE user_id IS NULL;
    IF n = 0 THEN
        ALTER TABLE leave_requests ALTER COLUMN user_id SET NOT NULL;
    ELSE
        RAISE NOTICE 'leave_requests: % ta qatorda user_id aniqlanmadi (o''qituvchi loginsiz va requester yo''q) — NOT NULL qo''yilmadi, qo''lda to''ldiring', n;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'leave_requests'::regclass AND conname = 'ck_leave_requests_dates') THEN
        SELECT COUNT(*) INTO n FROM leave_requests WHERE from_date > to_date;
        IF n = 0 THEN
            ALTER TABLE leave_requests ADD CONSTRAINT ck_leave_requests_dates CHECK (from_date <= to_date);
        ELSE
            RAISE NOTICE 'leave_requests: % ta qatorda from_date > to_date — CHECK qo''yilmadi', n;
        END IF;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'leave_requests'::regclass AND conname = 'ck_leave_requests_paid') THEN
        ALTER TABLE leave_requests ADD CONSTRAINT ck_leave_requests_paid
            CHECK (status <> 'APPROVED' OR paid IS NOT NULL);
    END IF;
END $$;

DO $$
DECLARE
    fk RECORD;
BEGIN
    FOR fk IN SELECT * FROM (VALUES
            ('user_id',      'fk_leave_requests_user'),
            ('decided_by',   'fk_leave_requests_decided_by'),
            ('cancelled_by', 'fk_leave_requests_cancelled_by')
        ) AS t(col, name)
    LOOP
        IF NOT EXISTS (
            SELECT 1
              FROM pg_constraint c
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
             WHERE c.conrelid = 'leave_requests'::regclass AND c.contype = 'f' AND a.attname = fk.col
        ) THEN
            EXECUTE format('ALTER TABLE leave_requests ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES users(id)',
                           fk.name, fk.col);
        END IF;
    END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS idx_leave_requests_user    ON leave_requests (user_id, from_date);
CREATE INDEX IF NOT EXISTS idx_leave_requests_teacher ON leave_requests (teacher_id);

-- Kesishuv taqiqi bazada ham (asosiy himoya — servisda, xodim qatori qulfi ostida).
-- btree_gist kerak: o'rnatib bo'lmasa (huquq yo'q) yoki eski ma'lumotda kesishuv bo'lsa — NOTICE.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint
                WHERE conrelid = 'leave_requests'::regclass AND conname = 'ex_leave_requests_overlap') THEN
        RETURN;
    END IF;
    BEGIN
        CREATE EXTENSION IF NOT EXISTS btree_gist;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'btree_gist o''rnatilmadi (%): kesishuv faqat ilovada tekshiriladi', SQLERRM;
        RETURN;
    END;
    BEGIN
        ALTER TABLE leave_requests ADD CONSTRAINT ex_leave_requests_overlap
            EXCLUDE USING gist (user_id WITH =, daterange(from_date, to_date, '[]') WITH &&)
            WHERE (status IN ('PENDING', 'APPROVED'));
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'ex_leave_requests_overlap qo''yilmadi (%): avval kesishgan ta''tillarni tuzating', SQLERRM;
    END;
END $$;

-- ── 3. lesson_substitutions ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS lesson_substitutions (
    id                    BIGSERIAL PRIMARY KEY,
    group_id              BIGINT       NOT NULL REFERENCES groups(id),
    lesson_date           DATE         NOT NULL,
    original_teacher_id   BIGINT       NOT NULL REFERENCES teachers(id),
    substitute_teacher_id BIGINT       NOT NULL REFERENCES teachers(id),
    leave_request_id      BIGINT,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PLANNED',
    conducted_at          TIMESTAMP,
    conducted_by          BIGINT       REFERENCES users(id),
    note                  VARCHAR(500),
    created_by            BIGINT       REFERENCES users(id),
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_by          BIGINT       REFERENCES users(id),
    cancelled_at          TIMESTAMP,
    cancel_reason         VARCHAR(500)
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'lesson_substitutions'::regclass AND conname = 'ck_lesson_substitutions_teachers') THEN
        ALTER TABLE lesson_substitutions ADD CONSTRAINT ck_lesson_substitutions_teachers
            CHECK (original_teacher_id <> substitute_teacher_id);
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'lesson_substitutions'::regclass AND c.contype = 'f' AND a.attname = 'leave_request_id'
    ) THEN
        ALTER TABLE lesson_substitutions ADD CONSTRAINT fk_lesson_substitutions_leave
            FOREIGN KEY (leave_request_id) REFERENCES leave_requests(id) ON DELETE SET NULL;
    END IF;
END $$;

-- Bir darsga bitta faol belgi
CREATE UNIQUE INDEX IF NOT EXISTS ux_lesson_substitutions_active
    ON lesson_substitutions (group_id, lesson_date) WHERE status <> 'CANCELLED';
CREATE INDEX IF NOT EXISTS idx_lesson_substitutions_substitute
    ON lesson_substitutions (substitute_teacher_id, lesson_date);
CREATE INDEX IF NOT EXISTS idx_lesson_substitutions_original
    ON lesson_substitutions (original_teacher_id, lesson_date);
CREATE INDEX IF NOT EXISTS idx_lesson_substitutions_leave
    ON lesson_substitutions (leave_request_id);

-- ── 4. salary_rules.substitute_lesson_rate (§3.3) ───────────────────────────────────────
ALTER TABLE salary_rules ADD COLUMN IF NOT EXISTS substitute_lesson_rate NUMERIC(12,2);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'salary_rules'::regclass AND conname = 'ck_salary_rules_substitute_rate') THEN
        ALTER TABLE salary_rules ADD CONSTRAINT ck_salary_rules_substitute_rate
            CHECK (substitute_lesson_rate IS NULL OR substitute_lesson_rate >= 0);
    END IF;
END $$;

COMMIT;

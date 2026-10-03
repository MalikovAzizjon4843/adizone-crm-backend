-- Telegram Mini App, bosqich 3 (docs/design/telegram-platform.md §11.2): o'quvchi / ota-onaning dars kuniga sabab
-- bildirishi. Davomatni o'zgartirmaydi — o'qituvchi davomat ekranida ko'radi.
--
-- V68 dan KEYIN, QO'LDA, crm_user bilan bajariladi (deploy-v2.md §3.3). Idempotent.

BEGIN;

CREATE TABLE IF NOT EXISTS absence_notices (
    id            BIGSERIAL    PRIMARY KEY,
    identity_id   BIGINT       NOT NULL,          -- app_identities.id — kim yubordi
    student_id    BIGINT       NOT NULL,
    group_id      BIGINT       NOT NULL,
    lesson_date   DATE         NOT NULL,
    type          VARCHAR(10)  NOT NULL,          -- ABSENT | LATE | OTHER
    comment       VARCHAR(500),
    status        VARCHAR(10)  NOT NULL,          -- ACTIVE | CANCELLED
    submitted_as  VARCHAR(10),                    -- SELF | PARENT
    created_at    TIMESTAMP    NOT NULL,
    cancelled_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_absence_notices_group_date ON absence_notices (group_id, lesson_date);
CREATE INDEX IF NOT EXISTS idx_absence_notices_identity ON absence_notices (identity_id, created_at);
-- Bitta o'quvchi + guruh + sana uchun bitta faol bildirish
CREATE UNIQUE INDEX IF NOT EXISTS ux_absence_notices_active
    ON absence_notices (student_id, group_id, lesson_date) WHERE status = 'ACTIVE';

-- FK lar ustuni bo'yicha tekshiriladi (Hibernate yaratgan bo'lsa takrorlanmaydi)
DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN SELECT * FROM (VALUES
            ('identity_id', 'app_identities', 'fk_absence_notices_identity'),
            ('student_id',  'students',       'fk_absence_notices_student'),
            ('group_id',    'groups',         'fk_absence_notices_group')) AS t(col, ref, name)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
             WHERE c.conrelid = 'absence_notices'::regclass AND c.contype = 'f'
               AND c.confrelid = fk.ref::regclass AND a.attname = fk.col
        ) THEN
            EXECUTE format('ALTER TABLE absence_notices ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(id) ON DELETE CASCADE',
                fk.name, fk.col, fk.ref);
        END IF;
    END LOOP;
END $$;

COMMIT;

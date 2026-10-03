-- phase6-api §4 (uy vazifalari) va §5 (guruhga ko'chirish).
--
-- V64 dan KEYIN, QO'LDA, crm_user bilan bajariladi (deploy-v2.md §3.3). Idempotent: qayta bajarilsa
-- hech narsa o'zgarmaydi. ddl-auto: update ustun/jadvalni o'zi ham qo'shadi — bu skript nomlarni,
-- UNIQUE ni va eski holat qiymatlarini aniq qiladi.

BEGIN;

-- ── 1. Uy vazifasiga fayl biriktirish ────────────────────────────────
ALTER TABLE homeworks ADD COLUMN IF NOT EXISTS attachment_url  VARCHAR(500);
ALTER TABLE homeworks ADD COLUMN IF NOT EXISTS attachment_name VARCHAR(255);

-- ── 2. O'quvchi holati: SUBMITTED | LATE | NOT_SUBMITTED ─────────────
-- Eski default PENDING (va boshqa noma'lum qiymatlar) — "topshirmadi". Katta-kichik harf normallashadi.
UPDATE homework_submissions SET status = UPPER(TRIM(status))
 WHERE status IS NOT NULL AND status <> UPPER(TRIM(status));
UPDATE homework_submissions SET status = 'NOT_SUBMITTED'
 WHERE status IS NULL OR status NOT IN ('SUBMITTED', 'LATE', 'NOT_SUBMITTED');
ALTER TABLE homework_submissions ALTER COLUMN status SET DEFAULT 'NOT_SUBMITTED';

-- Bir o'quvchiga bitta yozuv (entity'dagi @UniqueConstraint; eski bazada bo'lmasligi mumkin)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
         WHERE c.conrelid = 'homework_submissions'::regclass AND c.contype = 'u'
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM pg_attribute a
                 WHERE a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)) = ARRAY['homework_id', 'student_id']
    ) AND NOT EXISTS (
        SELECT 1 FROM pg_index i
         WHERE i.indrelid = 'homework_submissions'::regclass AND i.indisunique
           AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM pg_attribute a
                 WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)) = ARRAY['homework_id', 'student_id']
    ) THEN
        IF EXISTS (SELECT 1 FROM homework_submissions GROUP BY homework_id, student_id HAVING COUNT(*) > 1) THEN
            RAISE NOTICE 'homework_submissions: dublikat (homework_id, student_id) bor — UNIQUE qo''yilmadi';
        ELSE
            ALTER TABLE homework_submissions
                ADD CONSTRAINT uk_homework_submissions_hw_student UNIQUE (homework_id, student_id);
        END IF;
    END IF;
END $$;

-- ── 3. Guruhga ommaviy ko'chirish jurnali va idempotentlik ───────────
CREATE TABLE IF NOT EXISTS group_transfer_batches (
    id               BIGSERIAL PRIMARY KEY,
    from_group_id    BIGINT       NOT NULL REFERENCES groups(id),
    target_group_id  BIGINT       NOT NULL REFERENCES groups(id),
    transfer_date    DATE         NOT NULL,
    student_ids      TEXT         NOT NULL,
    note             VARCHAR(500),
    idempotency_key  VARCHAR(64),
    request_hash     VARCHAR(64)  NOT NULL,
    result_json      TEXT,
    created_by       BIGINT       REFERENCES users(id),
    created_at       TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_group_transfer_batches_key
    ON group_transfer_batches (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_group_transfer_batches_from ON group_transfer_batches (from_group_id, created_at);

COMMIT;

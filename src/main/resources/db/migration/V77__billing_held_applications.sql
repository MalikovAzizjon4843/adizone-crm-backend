-- Hold'dagi yozilmani CRM'dan qo'llash (POST /api/admin/billing/held/{sgId}/apply, SA) — har qo'llash yozuvi:
-- Idempotency-Key (bir xil kalit bilan takror so'rov — o'sha natija), sabab (majburiy), langar o'zgarishi, natija.
--
-- V76 dan KEYIN, QO'LDA, crm_user bilan. Idempotent. ddl-auto: update jadvalni o'zi ham yaratadi — bu skript
-- UNIQUE (idempotency_key) va FK larni qo'shadi.
-- Tekshirish: docs/ops/prod-schema-check.sql — V77 bo'laklari "TO'LIQ".

BEGIN;

CREATE TABLE IF NOT EXISTS billing_held_applications (
    id                BIGSERIAL      PRIMARY KEY,
    student_group_id  BIGINT         NOT NULL,
    migration_run_id  BIGINT         NOT NULL,
    idempotency_key   VARCHAR(100)   NOT NULL,
    reason            VARCHAR(1000)  NOT NULL,
    anchor_before     DATE,
    anchor_after      DATE,
    plan_hash         VARCHAR(64),
    result_json       TEXT,
    applied_by        VARCHAR(100),
    applied_at        TIMESTAMP      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_billing_held_applications_key ON billing_held_applications (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_billing_held_applications_sg ON billing_held_applications (student_group_id);

DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN SELECT * FROM (VALUES
            ('student_group_id', 'student_groups',         'fk_billing_held_applications_sg'),
            ('migration_run_id', 'billing_migration_runs', 'fk_billing_held_applications_run')) AS t(col, ref, name)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
             WHERE c.conrelid = 'billing_held_applications'::regclass AND c.contype = 'f'
               AND c.confrelid = fk.ref::regclass AND a.attname = fk.col
        ) THEN
            EXECUTE format('ALTER TABLE billing_held_applications ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(id)',
                fk.name, fk.col, fk.ref);
        END IF;
    END LOOP;
END $$;

COMMIT;

-- Salary system: student attribution, salary rules, payroll extensions

ALTER TABLE students
    ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES users(id);

ALTER TABLE students
    ADD COLUMN IF NOT EXISTS attributed_user_id BIGINT REFERENCES users(id);

CREATE INDEX IF NOT EXISTS idx_students_created_by ON students(created_by);
CREATE INDEX IF NOT EXISTS idx_students_attributed_user ON students(attributed_user_id);

CREATE TABLE IF NOT EXISTS salary_rules (
    id                BIGSERIAL PRIMARY KEY,
    role              VARCHAR(30) NOT NULL,
    user_id           BIGINT REFERENCES users(id),
    base_salary       NUMERIC(12, 2) DEFAULT 0,
    per_student_fee   NUMERIC(12, 2) DEFAULT 0,
    new_student_bonus NUMERIC(12, 2) DEFAULT 0,
    kpi_threshold     INTEGER,
    kpi_bonus         NUMERIC(12, 2) DEFAULT 0,
    is_active         BOOLEAN DEFAULT TRUE,
    effective_from    DATE,
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_salary_rules_role ON salary_rules(role);
CREATE INDEX IF NOT EXISTS idx_salary_rules_user ON salary_rules(user_id);

ALTER TABLE payroll
    ALTER COLUMN teacher_id DROP NOT NULL;

ALTER TABLE payroll
    ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES users(id);

ALTER TABLE payroll
    ADD COLUMN IF NOT EXISTS paid_student_count INTEGER;

ALTER TABLE payroll
    ADD COLUMN IF NOT EXISTS new_student_count INTEGER;

ALTER TABLE payroll
    ADD COLUMN IF NOT EXISTS kpi_applied BOOLEAN;

ALTER TABLE payroll
    ADD COLUMN IF NOT EXISTS kpi_amount NUMERIC(12, 2);

ALTER TABLE payroll
    ADD COLUMN IF NOT EXISTS calculation_details TEXT;

UPDATE payroll p
SET user_id = t.user_id
FROM teachers t
WHERE p.teacher_id = t.id
  AND p.user_id IS NULL
  AND t.user_id IS NOT NULL;

ALTER TABLE payroll DROP CONSTRAINT IF EXISTS payroll_teacher_id_month_year_key;
ALTER TABLE payroll DROP CONSTRAINT IF EXISTS uk_payroll_teacher_month_year;

CREATE UNIQUE INDEX IF NOT EXISTS uk_payroll_user_month_year
    ON payroll(user_id, month, year)
    WHERE user_id IS NOT NULL;

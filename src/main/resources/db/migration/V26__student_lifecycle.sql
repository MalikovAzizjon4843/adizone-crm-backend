-- Student-group payment lifecycle & payment plans

ALTER TABLE student_groups
  ADD COLUMN IF NOT EXISTS lessons_attended INT DEFAULT 0,
  ADD COLUMN IF NOT EXISTS first_lesson_date DATE,
  ADD COLUMN IF NOT EXISTS last_payment_date DATE,
  ADD COLUMN IF NOT EXISTS next_payment_due DATE,
  ADD COLUMN IF NOT EXISTS payment_status VARCHAR(20) DEFAULT 'TRIAL',
  ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS suspension_reason TEXT;

CREATE TABLE IF NOT EXISTS student_payment_plans (
    id              BIGSERIAL PRIMARY KEY,
    student_id      BIGINT NOT NULL REFERENCES students(id),
    group_id        BIGINT NOT NULL REFERENCES groups(id),
    plan_start_date DATE NOT NULL,
    plan_end_date   DATE,
    monthly_amount  DECIMAL(12,2) NOT NULL,
    due_day         INT DEFAULT 1,
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_plans_student
    ON student_payment_plans(student_id);
CREATE INDEX IF NOT EXISTS idx_payment_plans_group
    ON student_payment_plans(group_id);

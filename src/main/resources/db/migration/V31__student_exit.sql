ALTER TABLE student_groups
  ADD COLUMN IF NOT EXISTS exit_reason VARCHAR(50),
  ADD COLUMN IF NOT EXISTS exit_date DATE,
  ADD COLUMN IF NOT EXISTS exit_notes TEXT;

CREATE TABLE IF NOT EXISTS student_status_history (
    id          BIGSERIAL PRIMARY KEY,
    student_id  BIGINT NOT NULL REFERENCES students(id),
    from_status VARCHAR(50),
    to_status   VARCHAR(50),
    reason      VARCHAR(100),
    notes       TEXT,
    changed_by  BIGINT REFERENCES users(id),
    changed_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

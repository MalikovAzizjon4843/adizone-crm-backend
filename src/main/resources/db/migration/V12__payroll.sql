-- V12: Teacher Payroll

CREATE TABLE IF NOT EXISTS payroll (
    id             BIGSERIAL PRIMARY KEY,
    uuid           UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    teacher_id     BIGINT NOT NULL REFERENCES teachers(id) ON DELETE CASCADE,
    month          INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12),
    year           INTEGER NOT NULL,
    basic_salary   DECIMAL(12,2),
    allowances     DECIMAL(12,2) DEFAULT 0,
    deductions     DECIMAL(12,2) DEFAULT 0,
    net_salary     DECIMAL(12,2),
    payment_date   DATE,
    payment_method VARCHAR(20) DEFAULT 'BANK_TRANSFER',
    status         VARCHAR(20) DEFAULT 'PENDING',
    notes          TEXT,
    created_by     BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (teacher_id, month, year)
);

CREATE INDEX IF NOT EXISTS idx_payroll_teacher ON payroll(teacher_id);
CREATE INDEX IF NOT EXISTS idx_payroll_period ON payroll(year, month);
CREATE INDEX IF NOT EXISTS idx_payroll_status ON payroll(status);

CREATE TRIGGER update_payroll_updated_at BEFORE UPDATE ON payroll
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

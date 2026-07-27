-- Balance audit trail + StudentGroup.balance

ALTER TABLE student_groups
    ADD COLUMN IF NOT EXISTS balance NUMERIC(12, 2) DEFAULT 0;

UPDATE student_groups SET balance = 0 WHERE balance IS NULL;

CREATE TABLE IF NOT EXISTS balance_transactions (
    id              BIGSERIAL PRIMARY KEY,
    student_group_id BIGINT REFERENCES student_groups(id),
    student_id      BIGINT NOT NULL REFERENCES students(id),
    type            VARCHAR(30) NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL,
    balance_after   NUMERIC(12, 2) NOT NULL,
    reference_id    BIGINT,
    note            TEXT,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_balance_tx_student ON balance_transactions(student_id);
CREATE INDEX IF NOT EXISTS idx_balance_tx_sg ON balance_transactions(student_group_id);
CREATE INDEX IF NOT EXISTS idx_balance_tx_created ON balance_transactions(created_at);

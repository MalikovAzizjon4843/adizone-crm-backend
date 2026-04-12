CREATE TABLE IF NOT EXISTS exam_registrations (
    id                BIGSERIAL PRIMARY KEY,
    exam_id           BIGINT NOT NULL REFERENCES exams(id),
    student_id        BIGINT NOT NULL REFERENCES students(id),
    payment_status    VARCHAR(20) DEFAULT 'PENDING',
    amount_due        DECIMAL(12,2) DEFAULT 0,
    amount_paid       DECIMAL(12,2) DEFAULT 0,
    registration_date DATE DEFAULT CURRENT_DATE,
    status            VARCHAR(20) DEFAULT 'REGISTERED',
    notes             TEXT,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(exam_id, student_id)
);

CREATE INDEX IF NOT EXISTS idx_exam_registrations_exam ON exam_registrations(exam_id);
CREATE INDEX IF NOT EXISTS idx_exam_registrations_student ON exam_registrations(student_id);

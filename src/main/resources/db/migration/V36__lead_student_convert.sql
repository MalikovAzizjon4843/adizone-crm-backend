ALTER TABLE students
    ADD COLUMN IF NOT EXISTS converted_from_lead_id BIGINT REFERENCES leads(id);

ALTER TABLE leads
    ADD COLUMN IF NOT EXISTS parent_phone VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_students_converted_from_lead_id
    ON students(converted_from_lead_id);

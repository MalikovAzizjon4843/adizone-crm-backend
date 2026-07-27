-- Exam result edit audit fields

ALTER TABLE exam_results
    ADD COLUMN IF NOT EXISTS edit_note TEXT;

ALTER TABLE exam_results
    ADD COLUMN IF NOT EXISTS edited_at TIMESTAMP;

ALTER TABLE exam_results
    ADD COLUMN IF NOT EXISTS edited_by BIGINT REFERENCES users(id);

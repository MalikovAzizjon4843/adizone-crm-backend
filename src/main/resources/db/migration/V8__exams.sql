-- V8: Exams and Exam Results

CREATE TABLE IF NOT EXISTS exams (
    id            BIGSERIAL PRIMARY KEY,
    uuid          UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    exam_name     VARCHAR(255) NOT NULL,
    exam_type     VARCHAR(50),
    class_id      BIGINT REFERENCES classes(id) ON DELETE SET NULL,
    subject_id    BIGINT REFERENCES subjects(id) ON DELETE SET NULL,
    exam_date     DATE,
    start_time    TIME,
    end_time      TIME,
    total_marks   DECIMAL(6,2),
    pass_marks    DECIMAL(6,2),
    academic_year VARCHAR(20),
    is_active     BOOLEAN DEFAULT true,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS exam_results (
    id             BIGSERIAL PRIMARY KEY,
    exam_id        BIGINT NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    student_id     BIGINT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    marks_obtained DECIMAL(6,2),
    grade          VARCHAR(5),
    remarks        TEXT,
    is_passed      BOOLEAN,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (exam_id, student_id)
);

CREATE INDEX IF NOT EXISTS idx_exams_class_id ON exams(class_id);
CREATE INDEX IF NOT EXISTS idx_exams_subject_id ON exams(subject_id);
CREATE INDEX IF NOT EXISTS idx_exams_date ON exams(exam_date);
CREATE INDEX IF NOT EXISTS idx_exam_results_student ON exam_results(student_id);
CREATE INDEX IF NOT EXISTS idx_exam_results_exam ON exam_results(exam_id);

CREATE TRIGGER update_exams_updated_at BEFORE UPDATE ON exams
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_exam_results_updated_at BEFORE UPDATE ON exam_results
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

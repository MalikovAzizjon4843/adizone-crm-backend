-- V9: Homework and Submissions

CREATE TABLE IF NOT EXISTS homeworks (
    id            BIGSERIAL PRIMARY KEY,
    uuid          UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    title         VARCHAR(255) NOT NULL,
    description   TEXT,
    subject_id    BIGINT REFERENCES subjects(id) ON DELETE SET NULL,
    class_id      BIGINT REFERENCES classes(id) ON DELETE SET NULL,
    group_id      BIGINT REFERENCES groups(id) ON DELETE SET NULL,
    teacher_id    BIGINT REFERENCES teachers(id) ON DELETE SET NULL,
    assigned_date DATE NOT NULL DEFAULT CURRENT_DATE,
    due_date      DATE NOT NULL,
    marks         DECIMAL(6,2),
    is_active     BOOLEAN DEFAULT true,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS homework_submissions (
    id             BIGSERIAL PRIMARY KEY,
    homework_id    BIGINT NOT NULL REFERENCES homeworks(id) ON DELETE CASCADE,
    student_id     BIGINT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    submitted_at   TIMESTAMP,
    file_url       VARCHAR(500),
    remarks        TEXT,
    marks_obtained DECIMAL(6,2),
    status         VARCHAR(20) DEFAULT 'PENDING',
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (homework_id, student_id)
);

CREATE INDEX IF NOT EXISTS idx_homeworks_class_id ON homeworks(class_id);
CREATE INDEX IF NOT EXISTS idx_homeworks_group_id ON homeworks(group_id);
CREATE INDEX IF NOT EXISTS idx_homeworks_teacher_id ON homeworks(teacher_id);
CREATE INDEX IF NOT EXISTS idx_homeworks_due_date ON homeworks(due_date);
CREATE INDEX IF NOT EXISTS idx_hw_submissions_homework ON homework_submissions(homework_id);
CREATE INDEX IF NOT EXISTS idx_hw_submissions_student ON homework_submissions(student_id);

CREATE TRIGGER update_homeworks_updated_at BEFORE UPDATE ON homeworks
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_hw_submissions_updated_at BEFORE UPDATE ON homework_submissions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

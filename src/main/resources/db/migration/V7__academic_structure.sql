-- V7: Academic structure — Classes, Sections, Subjects, Classrooms, Timetable

CREATE TABLE IF NOT EXISTS classes (
    id         BIGSERIAL PRIMARY KEY,
    class_name VARCHAR(100) NOT NULL,
    class_code VARCHAR(20),
    is_active  BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sections (
    id           BIGSERIAL PRIMARY KEY,
    section_name VARCHAR(50) NOT NULL,
    class_id     BIGINT REFERENCES classes(id) ON DELETE CASCADE,
    teacher_id   BIGINT REFERENCES teachers(id) ON DELETE SET NULL,
    room         VARCHAR(50),
    max_students INTEGER DEFAULT 30,
    is_active    BOOLEAN DEFAULT true,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS subjects (
    id           BIGSERIAL PRIMARY KEY,
    subject_name VARCHAR(100) NOT NULL,
    subject_code VARCHAR(20),
    class_id     BIGINT REFERENCES classes(id) ON DELETE CASCADE,
    teacher_id   BIGINT REFERENCES teachers(id) ON DELETE SET NULL,
    is_active    BOOLEAN DEFAULT true,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS classrooms (
    id          BIGSERIAL PRIMARY KEY,
    room_name   VARCHAR(100) NOT NULL,
    room_number VARCHAR(20),
    capacity    INTEGER,
    floor       VARCHAR(20),
    is_active   BOOLEAN DEFAULT true,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS timetable (
    id            BIGSERIAL PRIMARY KEY,
    class_id      BIGINT REFERENCES classes(id) ON DELETE CASCADE,
    section_id    BIGINT REFERENCES sections(id) ON DELETE CASCADE,
    subject_id    BIGINT REFERENCES subjects(id) ON DELETE CASCADE,
    teacher_id    BIGINT REFERENCES teachers(id) ON DELETE SET NULL,
    classroom_id  BIGINT REFERENCES classrooms(id) ON DELETE SET NULL,
    day_of_week   VARCHAR(10) NOT NULL,
    start_time    TIME NOT NULL,
    end_time      TIME NOT NULL,
    academic_year VARCHAR(20),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_classes_is_active ON classes(is_active);
CREATE INDEX IF NOT EXISTS idx_sections_class_id ON sections(class_id);
CREATE INDEX IF NOT EXISTS idx_sections_teacher_id ON sections(teacher_id);
CREATE INDEX IF NOT EXISTS idx_subjects_class_id ON subjects(class_id);
CREATE INDEX IF NOT EXISTS idx_subjects_teacher_id ON subjects(teacher_id);
CREATE INDEX IF NOT EXISTS idx_timetable_class_id ON timetable(class_id);
CREATE INDEX IF NOT EXISTS idx_timetable_teacher_id ON timetable(teacher_id);
CREATE INDEX IF NOT EXISTS idx_timetable_day ON timetable(day_of_week);

-- updated_at triggers
CREATE TRIGGER update_classes_updated_at BEFORE UPDATE ON classes
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_sections_updated_at BEFORE UPDATE ON sections
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_subjects_updated_at BEFORE UPDATE ON subjects
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_classrooms_updated_at BEFORE UPDATE ON classrooms
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_timetable_updated_at BEFORE UPDATE ON timetable
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

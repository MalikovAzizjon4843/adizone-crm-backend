-- V10: Student Promotions

CREATE TABLE IF NOT EXISTS promotions (
    id                  BIGSERIAL PRIMARY KEY,
    student_id          BIGINT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    from_class_id       BIGINT REFERENCES classes(id) ON DELETE SET NULL,
    to_class_id         BIGINT REFERENCES classes(id) ON DELETE SET NULL,
    from_section_id     BIGINT REFERENCES sections(id) ON DELETE SET NULL,
    to_section_id       BIGINT REFERENCES sections(id) ON DELETE SET NULL,
    from_academic_year  VARCHAR(20),
    to_academic_year    VARCHAR(20),
    promotion_date      DATE DEFAULT CURRENT_DATE,
    promoted_by         BIGINT REFERENCES users(id) ON DELETE SET NULL,
    remarks             TEXT,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_promotions_student_id ON promotions(student_id);
CREATE INDEX IF NOT EXISTS idx_promotions_from_class ON promotions(from_class_id);
CREATE INDEX IF NOT EXISTS idx_promotions_to_class ON promotions(to_class_id);
CREATE INDEX IF NOT EXISTS idx_promotions_date ON promotions(promotion_date);

CREATE TRIGGER update_promotions_updated_at BEFORE UPDATE ON promotions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

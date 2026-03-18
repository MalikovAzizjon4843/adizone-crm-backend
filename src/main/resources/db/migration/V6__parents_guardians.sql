-- V6: Parents and Student-Parent link table

CREATE TABLE IF NOT EXISTS parents (
    id         BIGSERIAL PRIMARY KEY,
    uuid       UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name  VARCHAR(100),
    phone      VARCHAR(20),
    email      VARCHAR(255),
    occupation VARCHAR(100),
    address    TEXT,
    photo_url  VARCHAR(500),
    relation   VARCHAR(20) DEFAULT 'PARENT',
    is_active  BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS student_parents (
    id         BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    parent_id  BIGINT NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
    relation   VARCHAR(20),
    is_primary BOOLEAN DEFAULT false,
    UNIQUE (student_id, parent_id)
);

CREATE INDEX IF NOT EXISTS idx_parents_phone ON parents(phone);
CREATE INDEX IF NOT EXISTS idx_parents_is_active ON parents(is_active);
CREATE INDEX IF NOT EXISTS idx_student_parents_student ON student_parents(student_id);
CREATE INDEX IF NOT EXISTS idx_student_parents_parent ON student_parents(parent_id);

CREATE TRIGGER update_parents_updated_at BEFORE UPDATE ON parents
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

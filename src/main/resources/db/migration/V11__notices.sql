-- V11: Notices and Announcements

CREATE TABLE IF NOT EXISTS notices (
    id           BIGSERIAL PRIMARY KEY,
    uuid         UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    title        VARCHAR(255) NOT NULL,
    content      TEXT NOT NULL,
    notice_type  VARCHAR(30) DEFAULT 'GENERAL',
    target_role  VARCHAR(30),
    is_published BOOLEAN DEFAULT true,
    published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at   TIMESTAMP,
    created_by   BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notices_is_published ON notices(is_published);
CREATE INDEX IF NOT EXISTS idx_notices_published_at ON notices(published_at);
CREATE INDEX IF NOT EXISTS idx_notices_target_role ON notices(target_role);
CREATE INDEX IF NOT EXISTS idx_notices_expires_at ON notices(expires_at);

CREATE TRIGGER update_notices_updated_at BEFORE UPDATE ON notices
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- V20: Leads table (matches com.crm.entity.Lead — required for ddl-auto: validate)

CREATE TABLE IF NOT EXISTS leads (
    id          BIGSERIAL PRIMARY KEY,
    uuid        UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    full_name   VARCHAR(255) NOT NULL,
    phone       VARCHAR(20) NOT NULL,
    address     VARCHAR(255),
    course      VARCHAR(100),
    format      VARCHAR(20),
    status      VARCHAR(20) DEFAULT 'NEW',
    source      VARCHAR(30) DEFAULT 'WEBSITE',
    notes       TEXT,
    converted   BOOLEAN DEFAULT false,
    student_id  BIGINT REFERENCES students(id) ON DELETE SET NULL,
    created_by  BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_leads_phone ON leads(phone);
CREATE INDEX IF NOT EXISTS idx_leads_status ON leads(status);
CREATE INDEX IF NOT EXISTS idx_leads_student_id ON leads(student_id);
CREATE INDEX IF NOT EXISTS idx_leads_created_by ON leads(created_by);

CREATE TRIGGER update_leads_updated_at BEFORE UPDATE ON leads
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

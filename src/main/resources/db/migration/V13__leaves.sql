-- V13: Leave Requests

CREATE TABLE IF NOT EXISTS leave_requests (
    id           BIGSERIAL PRIMARY KEY,
    uuid         UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    requester_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    leave_type   VARCHAR(30) NOT NULL,
    from_date    DATE NOT NULL,
    to_date      DATE NOT NULL,
    reason       TEXT,
    status       VARCHAR(20) DEFAULT 'PENDING',
    approved_by  BIGINT REFERENCES users(id) ON DELETE SET NULL,
    approved_at  TIMESTAMP,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_leave_requests_requester ON leave_requests(requester_id);
CREATE INDEX IF NOT EXISTS idx_leave_requests_status ON leave_requests(status);
CREATE INDEX IF NOT EXISTS idx_leave_requests_dates ON leave_requests(from_date, to_date);

CREATE TRIGGER update_leave_requests_updated_at BEFORE UPDATE ON leave_requests
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

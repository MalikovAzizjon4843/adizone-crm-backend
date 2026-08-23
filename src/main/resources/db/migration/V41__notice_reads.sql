-- Per-user notice read status

CREATE TABLE IF NOT EXISTS notice_reads (
    id         BIGSERIAL PRIMARY KEY,
    notice_id  BIGINT NOT NULL REFERENCES notices(id),
    user_id    BIGINT NOT NULL REFERENCES users(id),
    read_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_notice_reads_notice_user UNIQUE (notice_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_notice_reads_user ON notice_reads(user_id);
CREATE INDEX IF NOT EXISTS idx_notice_reads_notice ON notice_reads(notice_id);

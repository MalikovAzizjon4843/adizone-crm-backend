UPDATE leads SET status = 'DAY_1_WORKED' WHERE status = 'CONTACTED';
UPDATE leads SET status = 'DAY_2_WORKED' WHERE status = 'IN_PROGRESS';
UPDATE leads SET status = 'DAY_3_WORKED' WHERE status = 'INTERESTED';
UPDATE leads SET status = 'CONVERTED' WHERE status = 'ENROLLED' AND converted = true;
UPDATE leads SET status = 'ONLINE_ENROLLED'
  WHERE status = 'ENROLLED'
    AND (converted IS NULL OR converted = false)
    AND UPPER(COALESCE(format, 'OFFLINE')) = 'ONLINE';
UPDATE leads SET status = 'OFFLINE_ENROLLED'
  WHERE status = 'ENROLLED'
    AND (converted IS NULL OR converted = false)
    AND UPPER(COALESCE(format, 'OFFLINE')) != 'ONLINE';

ALTER TABLE leads
  ADD COLUMN IF NOT EXISTS assigned_user_id BIGINT REFERENCES users(id),
  ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS lead_comments (
  id BIGSERIAL PRIMARY KEY,
  lead_id BIGINT NOT NULL REFERENCES leads(id),
  author_id BIGINT NOT NULL REFERENCES users(id),
  text TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  status_at_comment VARCHAR(30)
);

CREATE INDEX IF NOT EXISTS idx_lead_comments_lead_id ON lead_comments(lead_id);
CREATE INDEX IF NOT EXISTS idx_leads_assigned_user_id ON leads(assigned_user_id);
CREATE INDEX IF NOT EXISTS idx_leads_status ON leads(status);

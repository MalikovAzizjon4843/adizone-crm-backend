-- Universal audit log

CREATE TABLE IF NOT EXISTS audit_logs (
    id            BIGSERIAL PRIMARY KEY,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    -- FK EMAS: foydalanuvchi o'chirilsa ham log qolishi kerak
    user_id       BIGINT,
    username      VARCHAR(100),
    user_role     VARCHAR(30),
    action        VARCHAR(30) NOT NULL,
    entity_type   VARCHAR(50),
    entity_id     BIGINT,
    entity_label  VARCHAR(255),
    summary       VARCHAR(500),
    details_json  TEXT,
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_user    ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_entity  ON audit_logs(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_action  ON audit_logs(action);

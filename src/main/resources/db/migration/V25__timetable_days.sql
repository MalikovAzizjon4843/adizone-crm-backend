-- Group schedule days (per-group weekly slots with optional room)

CREATE TABLE IF NOT EXISTS group_schedule_days (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT NOT NULL REFERENCES groups(id)
                ON DELETE CASCADE,
    day_of_week VARCHAR(20) NOT NULL,
    start_time  VARCHAR(10),
    end_time    VARCHAR(10),
    room_id     BIGINT REFERENCES classrooms(id),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(group_id, day_of_week)
);

CREATE INDEX IF NOT EXISTS idx_schedule_group ON group_schedule_days(group_id);

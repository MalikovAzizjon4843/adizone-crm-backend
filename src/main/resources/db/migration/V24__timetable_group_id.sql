-- CRM guruhlari bilan dars jadvalini bog'lash (ixtiyoriy)
ALTER TABLE timetable
    ADD COLUMN IF NOT EXISTS group_id BIGINT REFERENCES groups(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_timetable_group_id ON timetable(group_id);

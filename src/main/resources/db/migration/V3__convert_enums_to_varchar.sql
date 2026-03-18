-- Convert all PostgreSQL native enum columns to VARCHAR so Hibernate's
-- @Enumerated(EnumType.STRING) works correctly with both reads and writes.
-- PostgreSQL rejects implicit varchar→custom_enum casts in JDBC parameterized
-- statements, causing UPDATE failures. VARCHAR columns have no such restriction.
--
-- Pattern: DROP DEFAULT → ALTER TYPE → SET DEFAULT (as plain string) → DROP TYPE

-- users.role (default: ADMIN)
ALTER TABLE users ALTER COLUMN role DROP DEFAULT;
ALTER TABLE users ALTER COLUMN role TYPE VARCHAR(50) USING role::text;
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'ADMIN';

-- groups.status (default: ACTIVE)
ALTER TABLE groups ALTER COLUMN status DROP DEFAULT;
ALTER TABLE groups ALTER COLUMN status TYPE VARCHAR(50) USING status::text;
ALTER TABLE groups ALTER COLUMN status SET DEFAULT 'ACTIVE';

-- group_schedules.day_of_week (no default)
ALTER TABLE group_schedules ALTER COLUMN day_of_week TYPE VARCHAR(20) USING day_of_week::text;

-- students.marketing_source (default: OTHER)
ALTER TABLE students ALTER COLUMN marketing_source DROP DEFAULT;
ALTER TABLE students ALTER COLUMN marketing_source TYPE VARCHAR(50) USING marketing_source::text;
ALTER TABLE students ALTER COLUMN marketing_source SET DEFAULT 'OTHER';

-- students.status (default: ACTIVE)
ALTER TABLE students ALTER COLUMN status DROP DEFAULT;
ALTER TABLE students ALTER COLUMN status TYPE VARCHAR(50) USING status::text;
ALTER TABLE students ALTER COLUMN status SET DEFAULT 'ACTIVE';

-- attendance.status (default: PRESENT)
ALTER TABLE attendance ALTER COLUMN status DROP DEFAULT;
ALTER TABLE attendance ALTER COLUMN status TYPE VARCHAR(50) USING status::text;
ALTER TABLE attendance ALTER COLUMN status SET DEFAULT 'PRESENT';

-- payments.payment_method (default: CASH)
ALTER TABLE payments ALTER COLUMN payment_method DROP DEFAULT;
ALTER TABLE payments ALTER COLUMN payment_method TYPE VARCHAR(50) USING payment_method::text;
ALTER TABLE payments ALTER COLUMN payment_method SET DEFAULT 'CASH';

-- payments.status (default: PAID)
ALTER TABLE payments ALTER COLUMN status DROP DEFAULT;
ALTER TABLE payments ALTER COLUMN status TYPE VARCHAR(50) USING status::text;
ALTER TABLE payments ALTER COLUMN status SET DEFAULT 'PAID';

-- expenses.category (no default)
ALTER TABLE expenses ALTER COLUMN category TYPE VARCHAR(50) USING category::text;

-- income.category (no default)
ALTER TABLE income ALTER COLUMN category TYPE VARCHAR(50) USING category::text;

-- Drop the now-unused custom enum types (no dependents remain after DROP DEFAULT above)
DROP TYPE IF EXISTS user_role;
DROP TYPE IF EXISTS group_status;
DROP TYPE IF EXISTS day_of_week;
DROP TYPE IF EXISTS marketing_source;
DROP TYPE IF EXISTS student_status;
DROP TYPE IF EXISTS attendance_status;
DROP TYPE IF EXISTS payment_method;
DROP TYPE IF EXISTS payment_status;
DROP TYPE IF EXISTS expense_category;
DROP TYPE IF EXISTS income_category;

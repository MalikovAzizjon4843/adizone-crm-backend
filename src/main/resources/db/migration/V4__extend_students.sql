-- V4: Extend students table with all fields required by the frontend "Add Student" form

-- Academic info
ALTER TABLE students ADD COLUMN IF NOT EXISTS admission_number VARCHAR(50);
ALTER TABLE students ADD COLUMN IF NOT EXISTS admission_date DATE;
ALTER TABLE students ADD COLUMN IF NOT EXISTS roll_number VARCHAR(50);
ALTER TABLE students ADD COLUMN IF NOT EXISTS academic_year VARCHAR(20);

-- Personal info
ALTER TABLE students ADD COLUMN IF NOT EXISTS gender VARCHAR(10);
ALTER TABLE students ADD COLUMN IF NOT EXISTS blood_group VARCHAR(5);
ALTER TABLE students ADD COLUMN IF NOT EXISTS religion VARCHAR(50);
ALTER TABLE students ADD COLUMN IF NOT EXISTS category VARCHAR(50);
ALTER TABLE students ADD COLUMN IF NOT EXISTS mother_tongue VARCHAR(50);
ALTER TABLE students ADD COLUMN IF NOT EXISTS email VARCHAR(255);
-- photo_url already exists from V1; address already exists from V1

-- Address (current/permanent as distinct fields; existing address column preserved)
ALTER TABLE students ADD COLUMN IF NOT EXISTS current_address TEXT;
ALTER TABLE students ADD COLUMN IF NOT EXISTS permanent_address TEXT;

-- Parent info
ALTER TABLE students ADD COLUMN IF NOT EXISTS father_name VARCHAR(100);
ALTER TABLE students ADD COLUMN IF NOT EXISTS father_phone VARCHAR(20);
ALTER TABLE students ADD COLUMN IF NOT EXISTS father_email VARCHAR(100);
ALTER TABLE students ADD COLUMN IF NOT EXISTS father_occupation VARCHAR(100);
ALTER TABLE students ADD COLUMN IF NOT EXISTS mother_name VARCHAR(100);
ALTER TABLE students ADD COLUMN IF NOT EXISTS mother_phone VARCHAR(20);
ALTER TABLE students ADD COLUMN IF NOT EXISTS mother_email VARCHAR(100);
ALTER TABLE students ADD COLUMN IF NOT EXISTS mother_occupation VARCHAR(100);

-- Guardian info
ALTER TABLE students ADD COLUMN IF NOT EXISTS guardian_name VARCHAR(100);
ALTER TABLE students ADD COLUMN IF NOT EXISTS guardian_relation VARCHAR(50);
ALTER TABLE students ADD COLUMN IF NOT EXISTS guardian_phone VARCHAR(20);
ALTER TABLE students ADD COLUMN IF NOT EXISTS guardian_email VARCHAR(100);
ALTER TABLE students ADD COLUMN IF NOT EXISTS guardian_occupation VARCHAR(100);
ALTER TABLE students ADD COLUMN IF NOT EXISTS guardian_address TEXT;

-- Medical info
ALTER TABLE students ADD COLUMN IF NOT EXISTS medical_condition VARCHAR(20);
ALTER TABLE students ADD COLUMN IF NOT EXISTS allergies TEXT;
ALTER TABLE students ADD COLUMN IF NOT EXISTS medications TEXT;

-- Previous school
ALTER TABLE students ADD COLUMN IF NOT EXISTS previous_school_name VARCHAR(255);
ALTER TABLE students ADD COLUMN IF NOT EXISTS previous_school_address TEXT;

-- Bank info
ALTER TABLE students ADD COLUMN IF NOT EXISTS bank_name VARCHAR(100);
ALTER TABLE students ADD COLUMN IF NOT EXISTS bank_account_number VARCHAR(50);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_students_email ON students(email);
CREATE INDEX IF NOT EXISTS idx_students_admission_number ON students(admission_number);
CREATE INDEX IF NOT EXISTS idx_students_gender ON students(gender);

-- V15: Course center — remove school-style fields from students; trim parents table (step 1)

ALTER TABLE students
  DROP COLUMN IF EXISTS academic_year,
  DROP COLUMN IF EXISTS roll_number,
  DROP COLUMN IF EXISTS blood_group,
  DROP COLUMN IF EXISTS religion,
  DROP COLUMN IF EXISTS category,
  DROP COLUMN IF EXISTS email,
  DROP COLUMN IF EXISTS mother_tongue,
  DROP COLUMN IF EXISTS current_address,
  DROP COLUMN IF EXISTS permanent_address;

-- Embedded parent/guardian, medical, previous school, bank — use Parent entity / not needed for course center
ALTER TABLE students
  DROP COLUMN IF EXISTS father_name,
  DROP COLUMN IF EXISTS father_phone,
  DROP COLUMN IF EXISTS father_email,
  DROP COLUMN IF EXISTS father_occupation,
  DROP COLUMN IF EXISTS mother_name,
  DROP COLUMN IF EXISTS mother_phone,
  DROP COLUMN IF EXISTS mother_email,
  DROP COLUMN IF EXISTS mother_occupation,
  DROP COLUMN IF EXISTS guardian_name,
  DROP COLUMN IF EXISTS guardian_relation,
  DROP COLUMN IF EXISTS guardian_phone,
  DROP COLUMN IF EXISTS guardian_email,
  DROP COLUMN IF EXISTS guardian_occupation,
  DROP COLUMN IF EXISTS guardian_address,
  DROP COLUMN IF EXISTS medical_condition,
  DROP COLUMN IF EXISTS allergies,
  DROP COLUMN IF EXISTS medications,
  DROP COLUMN IF EXISTS previous_school_name,
  DROP COLUMN IF EXISTS previous_school_address,
  DROP COLUMN IF EXISTS bank_name,
  DROP COLUMN IF EXISTS bank_account_number;

DROP INDEX IF EXISTS idx_students_email;

ALTER TABLE parents
  DROP COLUMN IF EXISTS occupation,
  DROP COLUMN IF EXISTS photo_url;

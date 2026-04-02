-- V17: Course center — simplify teachers; PAN → passport_info

ALTER TABLE teachers
  DROP COLUMN IF EXISTS blood_group,
  DROP COLUMN IF EXISTS previous_school,
  DROP COLUMN IF EXISTS previous_school_address,
  DROP COLUMN IF EXISTS previous_school_phone,
  DROP COLUMN IF EXISTS bank_account_name,
  DROP COLUMN IF EXISTS bank_account_number,
  DROP COLUMN IF EXISTS bank_name,
  DROP COLUMN IF EXISTS ifsc_code,
  DROP COLUMN IF EXISTS branch_name,
  DROP COLUMN IF EXISTS work_shift,
  DROP COLUMN IF EXISTS work_location,
  DROP COLUMN IF EXISTS leaving_date,
  DROP COLUMN IF EXISTS epf_number,
  DROP COLUMN IF EXISTS contract_type,
  DROP COLUMN IF EXISTS religion;

ALTER TABLE teachers
  RENAME COLUMN pan_number TO passport_info;

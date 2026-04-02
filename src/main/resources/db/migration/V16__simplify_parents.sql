-- V16: Parents — single full_name; drop split names and email

ALTER TABLE parents
  ADD COLUMN IF NOT EXISTS full_name VARCHAR(200);

UPDATE parents
SET full_name = TRIM(CONCAT(COALESCE(first_name, ''), ' ', COALESCE(last_name, '')))
WHERE full_name IS NULL OR full_name = '';

UPDATE parents
SET full_name = COALESCE(NULLIF(TRIM(full_name), ''), 'Unknown')
WHERE full_name IS NULL OR TRIM(full_name) = '';

ALTER TABLE parents
  DROP COLUMN IF EXISTS last_name,
  DROP COLUMN IF EXISTS occupation,
  DROP COLUMN IF EXISTS photo_url,
  DROP COLUMN IF EXISTS email,
  DROP COLUMN IF EXISTS first_name;

ALTER TABLE parents
  ALTER COLUMN full_name SET NOT NULL;

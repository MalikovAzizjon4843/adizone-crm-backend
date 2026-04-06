-- V23: Excel import — longer gender labels and formatted phone numbers

ALTER TABLE students
    ALTER COLUMN gender TYPE VARCHAR(50);

ALTER TABLE students
    ALTER COLUMN phone TYPE VARCHAR(32);

ALTER TABLE students
    ALTER COLUMN parent_phone TYPE VARCHAR(32);

-- Teacher import: formatted numbers
ALTER TABLE teachers
    ALTER COLUMN phone TYPE VARCHAR(32);

-- V21: teachers.photo_url (matches com.crm.entity.Teacher#photoUrl)

ALTER TABLE teachers ADD COLUMN IF NOT EXISTS photo_url VARCHAR(500);

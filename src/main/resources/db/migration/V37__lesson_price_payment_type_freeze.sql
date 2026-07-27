-- Course lesson price + StudentGroup payment type / lesson billing + Student balance + Payment.balance_used

ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS lesson_price NUMERIC(12, 2);

ALTER TABLE students
    ADD COLUMN IF NOT EXISTS balance NUMERIC(12, 2) DEFAULT 0;

ALTER TABLE student_groups
    ADD COLUMN IF NOT EXISTS payment_type VARCHAR(20) DEFAULT 'MONTHLY',
    ADD COLUMN IF NOT EXISTS lesson_price NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS lessons_purchased INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS lessons_used INTEGER DEFAULT 0;

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS balance_used NUMERIC(12, 2) DEFAULT 0;

UPDATE student_groups SET payment_type = 'MONTHLY' WHERE payment_type IS NULL;
UPDATE student_groups SET lessons_purchased = 0 WHERE lessons_purchased IS NULL;
UPDATE student_groups SET lessons_used = 0 WHERE lessons_used IS NULL;
UPDATE students SET balance = 0 WHERE balance IS NULL;
UPDATE payments SET balance_used = 0 WHERE balance_used IS NULL;

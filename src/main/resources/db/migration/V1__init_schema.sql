-- ============================================================
-- Education CRM System - Full Database Schema
-- V1__init_schema.sql
-- ============================================================

-- EXTENSIONS
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- ============================================================
-- ENUM TYPES
-- ============================================================

CREATE TYPE user_role AS ENUM ('SUPER_ADMIN', 'ADMIN', 'TEACHER', 'ACCOUNTANT');
CREATE TYPE student_status AS ENUM ('ACTIVE', 'FROZEN', 'FINISHED', 'LEFT');
CREATE TYPE attendance_status AS ENUM ('PRESENT', 'ABSENT', 'LATE');
CREATE TYPE marketing_source AS ENUM ('INSTAGRAM', 'TELEGRAM', 'YOUTUBE', 'REFERRAL', 'OFFLINE', 'OTHER');
CREATE TYPE payment_status AS ENUM ('PAID', 'PENDING', 'OVERDUE', 'PARTIAL');
CREATE TYPE payment_method AS ENUM ('CASH', 'CARD', 'BANK_TRANSFER', 'ONLINE');
CREATE TYPE expense_category AS ENUM ('SALARY', 'RENT', 'MARKETING', 'UTILITIES', 'EQUIPMENT', 'OTHER');
CREATE TYPE income_category AS ENUM ('STUDENT_PAYMENT', 'OTHER_INCOME');
CREATE TYPE day_of_week AS ENUM ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY');
CREATE TYPE group_status AS ENUM ('ACTIVE', 'COMPLETED', 'CANCELLED');

-- ============================================================
-- USERS TABLE
-- ============================================================

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    username VARCHAR(100) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE,
    password VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    role user_role NOT NULL DEFAULT 'ADMIN',
    is_active BOOLEAN DEFAULT true,
    last_login TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_is_active ON users(is_active);

-- ============================================================
-- COURSES TABLE
-- ============================================================

CREATE TABLE courses (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    course_name VARCHAR(255) NOT NULL,
    description TEXT,
    duration_months INTEGER NOT NULL,
    lessons_count INTEGER NOT NULL,
    monthly_price DECIMAL(12, 2) NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_courses_is_active ON courses(is_active);
CREATE INDEX idx_courses_created_at ON courses(created_at);

-- ============================================================
-- TEACHERS TABLE
-- ============================================================

CREATE TABLE teachers (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    email VARCHAR(255),
    subject_specialization VARCHAR(255),
    monthly_salary DECIMAL(12, 2),
    hire_date DATE,
    is_active BOOLEAN DEFAULT true,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_teachers_is_active ON teachers(is_active);
CREATE INDEX idx_teachers_user_id ON teachers(user_id);

-- ============================================================
-- GROUPS TABLE
-- ============================================================

CREATE TABLE groups (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE RESTRICT,
    teacher_id BIGINT REFERENCES teachers(id) ON DELETE SET NULL,
    room VARCHAR(100),
    max_students INTEGER DEFAULT 20,
    current_students INTEGER DEFAULT 0,
    start_date DATE NOT NULL,
    end_date DATE,
    status group_status DEFAULT 'ACTIVE',
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_groups_course_id ON groups(course_id);
CREATE INDEX idx_groups_teacher_id ON groups(teacher_id);
CREATE INDEX idx_groups_status ON groups(status);
CREATE INDEX idx_groups_start_date ON groups(start_date);

-- ============================================================
-- GROUP SCHEDULES
-- ============================================================

CREATE TABLE group_schedules (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    day_of_week day_of_week NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_group_schedules_group_id ON group_schedules(group_id);

-- ============================================================
-- STUDENTS TABLE
-- ============================================================

CREATE TABLE students (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    parent_phone VARCHAR(20),
    birth_date DATE,
    marketing_source marketing_source DEFAULT 'OTHER',
    referral_student_id BIGINT REFERENCES students(id) ON DELETE SET NULL,
    status student_status DEFAULT 'ACTIVE',
    notes TEXT,
    photo_url VARCHAR(500),
    address TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_students_status ON students(status);
CREATE INDEX idx_students_created_at ON students(created_at);
CREATE INDEX idx_students_phone ON students(phone);
CREATE INDEX idx_students_marketing_source ON students(marketing_source);
CREATE INDEX idx_students_name ON students USING gin (first_name gin_trgm_ops, last_name gin_trgm_ops);

-- ============================================================
-- STUDENT_GROUPS (Join Table with payment info)
-- ============================================================

CREATE TABLE student_groups (
    id BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    join_date DATE NOT NULL DEFAULT CURRENT_DATE,
    leave_date DATE,
    next_payment_date DATE,
    payment_start_date DATE,
    is_active BOOLEAN DEFAULT true,
    discount_percentage DECIMAL(5,2) DEFAULT 0,
    monthly_price_override DECIMAL(12,2),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (student_id, group_id, join_date)
);

CREATE INDEX idx_student_groups_student_id ON student_groups(student_id);
CREATE INDEX idx_student_groups_group_id ON student_groups(group_id);
CREATE INDEX idx_student_groups_next_payment_date ON student_groups(next_payment_date);
CREATE INDEX idx_student_groups_is_active ON student_groups(is_active);

-- ============================================================
-- ATTENDANCE TABLE
-- ============================================================

CREATE TABLE attendance (
    id BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    attendance_date DATE NOT NULL,
    status attendance_status NOT NULL DEFAULT 'PRESENT',
    marked_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (student_id, group_id, attendance_date)
);

CREATE INDEX idx_attendance_student_id ON attendance(student_id);
CREATE INDEX idx_attendance_group_id ON attendance(group_id);
CREATE INDEX idx_attendance_date ON attendance(attendance_date);
CREATE INDEX idx_attendance_status ON attendance(status);

-- ============================================================
-- PAYMENTS TABLE
-- ============================================================

CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    student_id BIGINT NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    group_id BIGINT REFERENCES groups(id) ON DELETE SET NULL,
    student_group_id BIGINT REFERENCES student_groups(id) ON DELETE SET NULL,
    amount DECIMAL(12, 2) NOT NULL,
    payment_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payment_method payment_method DEFAULT 'CASH',
    status payment_status DEFAULT 'PAID',
    period_from DATE,
    period_to DATE,
    description VARCHAR(500),
    received_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payments_student_id ON payments(student_id);
CREATE INDEX idx_payments_group_id ON payments(group_id);
CREATE INDEX idx_payments_payment_date ON payments(payment_date);
CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_period ON payments(period_from, period_to);

-- ============================================================
-- INCOME TABLE (Finance Module)
-- ============================================================

CREATE TABLE income (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    category income_category NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    payment_id BIGINT REFERENCES payments(id) ON DELETE SET NULL,
    description VARCHAR(500),
    income_date DATE NOT NULL DEFAULT CURRENT_DATE,
    received_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_income_income_date ON income(income_date);
CREATE INDEX idx_income_category ON income(category);

-- ============================================================
-- EXPENSES TABLE (Finance Module)
-- ============================================================

CREATE TABLE expenses (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    category expense_category NOT NULL,
    title VARCHAR(255) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    expense_date DATE NOT NULL DEFAULT CURRENT_DATE,
    teacher_id BIGINT REFERENCES teachers(id) ON DELETE SET NULL,
    approved_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    description TEXT,
    receipt_url VARCHAR(500),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_expenses_expense_date ON expenses(expense_date);
CREATE INDEX idx_expenses_category ON expenses(category);
CREATE INDEX idx_expenses_teacher_id ON expenses(teacher_id);

-- ============================================================
-- TEACHER SALARIES
-- ============================================================

CREATE TABLE teacher_salaries (
    id BIGSERIAL PRIMARY KEY,
    teacher_id BIGINT NOT NULL REFERENCES teachers(id) ON DELETE RESTRICT,
    amount DECIMAL(12, 2) NOT NULL,
    payment_date DATE NOT NULL,
    period_month INTEGER NOT NULL,
    period_year INTEGER NOT NULL,
    expense_id BIGINT REFERENCES expenses(id) ON DELETE SET NULL,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_teacher_salaries_teacher_id ON teacher_salaries(teacher_id);
CREATE INDEX idx_teacher_salaries_period ON teacher_salaries(period_year, period_month);

-- ============================================================
-- NOTIFICATIONS TABLE
-- ============================================================

CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    is_read BOOLEAN DEFAULT false,
    notification_type VARCHAR(50),
    reference_id BIGINT,
    reference_type VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifications_user_id ON notifications(user_id);
CREATE INDEX idx_notifications_is_read ON notifications(is_read);
CREATE INDEX idx_notifications_created_at ON notifications(created_at);

-- ============================================================
-- SETTINGS TABLE
-- ============================================================

CREATE TABLE settings (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(100) UNIQUE NOT NULL,
    setting_value TEXT,
    description VARCHAR(500),
    updated_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- AUDIT LOG TABLE
-- ============================================================

CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id BIGINT,
    old_values JSONB,
    new_values JSONB,
    ip_address VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);

-- ============================================================
-- DEFAULT DATA
-- ============================================================

-- Default settings
INSERT INTO settings (setting_key, setting_value, description) VALUES
('payment_cycle_days', '30', 'Number of days between payments'),
('currency', 'UZS', 'Default currency'),
('school_name', 'Education Center', 'School/Center name'),
('max_debt_days', '7', 'Days after due date before marked as debtor');

-- Default SUPER_ADMIN user (password: Admin@123)
INSERT INTO users (username, email, password, first_name, last_name, phone, role, is_active)
VALUES ('superadmin', 'superadmin@crm.com',
        '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.',
        'Super', 'Admin', '+998901234567', 'SUPER_ADMIN', true);

-- ============================================================
-- TRIGGERS for updated_at
-- ============================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_students_updated_at BEFORE UPDATE ON students
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_groups_updated_at BEFORE UPDATE ON groups
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_courses_updated_at BEFORE UPDATE ON courses
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_teachers_updated_at BEFORE UPDATE ON teachers
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_student_groups_updated_at BEFORE UPDATE ON student_groups
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_payments_updated_at BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- TRIGGER: Auto-update group current_students count
-- ============================================================

CREATE OR REPLACE FUNCTION update_group_student_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.is_active = true THEN
        UPDATE groups SET current_students = current_students + 1 WHERE id = NEW.group_id;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.is_active = true AND NEW.is_active = false THEN
            UPDATE groups SET current_students = current_students - 1 WHERE id = NEW.group_id;
        ELSIF OLD.is_active = false AND NEW.is_active = true THEN
            UPDATE groups SET current_students = current_students + 1 WHERE id = NEW.group_id;
        END IF;
    ELSIF TG_OP = 'DELETE' AND OLD.is_active = true THEN
        UPDATE groups SET current_students = current_students - 1 WHERE id = OLD.group_id;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_update_group_student_count
AFTER INSERT OR UPDATE OR DELETE ON student_groups
FOR EACH ROW EXECUTE FUNCTION update_group_student_count();

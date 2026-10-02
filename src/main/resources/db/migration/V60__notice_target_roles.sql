-- E'lon auditoriyasi — rollar to'plami (phase5-audit N-01, Q12).
--
-- V59 dan KEYIN, QO'LDA bajariladi (Flyway yo'q). Idempotent: qayta bajarilsa hech narsa o'zgarmaydi.
-- Ilova bu skriptsiz ham to'g'ri ishlaydi: notice_target_roles bo'sh bo'lsa eski published_to /
-- target_role o'qiladi (NoticeRepository.VISIBLE). Skript jadvalni yaratadi va eski qiymatlarni
-- yangi modelga ko'chiradi, shunda ikki model parallel yashamaydi.

BEGIN;

CREATE TABLE IF NOT EXISTS notice_target_roles (
    notice_id BIGINT      NOT NULL REFERENCES notices(id) ON DELETE CASCADE,
    role      VARCHAR(30) NOT NULL,
    PRIMARY KEY (notice_id, role)
);
CREATE INDEX IF NOT EXISTS idx_notice_target_roles_role ON notice_target_roles (role);

-- Eski target_role (bitta rol) — rollar jadvaliga, faqat ma'lum rol nomlari.
INSERT INTO notice_target_roles (notice_id, role)
SELECT n.id, UPPER(TRIM(n.target_role))
  FROM notices n
 WHERE n.target_role IS NOT NULL
   AND UPPER(TRIM(n.target_role)) IN ('SUPER_ADMIN','ADMIN','SALES_MANAGER','TEACHER','ACCOUNTANT','STUDENT','PARENT')
   AND NOT EXISTS (SELECT 1 FROM notice_target_roles r WHERE r.notice_id = n.id)
ON CONFLICT DO NOTHING;

-- Eski published_to (TEACHERS / STUDENTS / PARENTS) — target_role bo'lmagan yozuvlar uchun.
INSERT INTO notice_target_roles (notice_id, role)
SELECT n.id, CASE UPPER(TRIM(n.published_to))
                 WHEN 'TEACHERS' THEN 'TEACHER'
                 WHEN 'STUDENTS' THEN 'STUDENT'
                 WHEN 'PARENTS'  THEN 'PARENT' END
  FROM notices n
 WHERE n.target_role IS NULL
   AND UPPER(TRIM(n.published_to)) IN ('TEACHERS', 'STUDENTS', 'PARENTS')
   AND NOT EXISTS (SELECT 1 FROM notice_target_roles r WHERE r.notice_id = n.id)
ON CONFLICT DO NOTHING;

-- Ko'chirilgan yozuvlar — ilova yozadigan holatga (published_to = ROLES, target_role = NULL).
UPDATE notices n
   SET published_to = 'ROLES', target_role = NULL
 WHERE EXISTS (SELECT 1 FROM notice_target_roles r WHERE r.notice_id = n.id)
   AND (n.published_to IS DISTINCT FROM 'ROLES' OR n.target_role IS NOT NULL);

-- Noma'lum eski qiymatlar (masalan 'ADMINS') — ko'chirilmadi; ilova ularni "hamma" deb ko'rsatadi,
-- lekin filtrda cheklaydi. Qo'lda ko'rib chiqish uchun:
DO $$
DECLARE bad TEXT;
BEGIN
    SELECT string_agg(DISTINCT COALESCE(target_role, published_to), ', ') INTO bad
      FROM notices n
     WHERE NOT EXISTS (SELECT 1 FROM notice_target_roles r WHERE r.notice_id = n.id)
       AND (n.target_role IS NOT NULL
            OR UPPER(TRIM(COALESCE(n.published_to, 'ALL'))) NOT IN ('ALL', 'ROLES'));
    IF bad IS NOT NULL THEN
        RAISE NOTICE 'notices: ko''chirilmagan auditoriya qiymatlari: %', bad;
    END IF;
END $$;

COMMIT;

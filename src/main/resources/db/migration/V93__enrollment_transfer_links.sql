-- Qo'lda ko'chirishni transfer'ga tenglash izi (POST /api/admin/repair/merge-into-transfer).
--   Adminlar o'quvchini "Guruhga ko'chirish" o'rniga qo'lda o'tkazgan: yangi guruhga qo'shib, eskisidan
--   TRANSFERRED bilan chiqargan — eski guruhdagi to'langan davr qolib, yangi guruhda yangi davr hisoblangan.
--   Ta'mir (REPAIR_MANUAL_TRANSFER) ledger'ga PERIOD_REFUND va TRANSFER_OUT/IN yozadi; bu jadval eski → yangi
--   yozilma bog'lanishini va xulosani saqlaydi. UNIQUE (old, new) — ta'mir bir marta (idempotent).
--
-- V92 dan KEYIN, ILOVANI YANGI JAR BILAN ISHGA TUSHIRISHDAN OLDIN, QO'LDA, crm_user bilan:
--   psql -X -v ON_ERROR_STOP=1 -f V93__enrollment_transfer_links.sql
-- Idempotent. (Bajarilmasa ham Hibernate ddl-auto: update jadvalni o'zi yaratadi.)

BEGIN;

CREATE TABLE IF NOT EXISTS enrollment_transfer_links (
    id                   BIGSERIAL PRIMARY KEY,
    student_id           BIGINT        NOT NULL,
    old_student_group_id BIGINT        NOT NULL REFERENCES student_groups (id),
    new_student_group_id BIGINT        NOT NULL REFERENCES student_groups (id),
    source               VARCHAR(40)   NOT NULL,
    mode                 VARCHAR(20)   NOT NULL,
    continued_anchor     DATE,
    refunded_amount      NUMERIC(12, 2),
    moved_amount         NUMERIC(12, 2),
    created_at           TIMESTAMP     NOT NULL,
    CONSTRAINT uk_enrollment_transfer_links_pair UNIQUE (old_student_group_id, new_student_group_id)
);

CREATE INDEX IF NOT EXISTS idx_enrollment_transfer_links_new ON enrollment_transfer_links (new_student_group_id);

COMMIT;

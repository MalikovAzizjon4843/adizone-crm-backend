-- StudentStatusHistory: muzlatish balansi endi matn ichida emas, alohida ustunda

ALTER TABLE student_status_history
    ADD COLUMN IF NOT EXISTS balance_snapshot NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS meta_json        TEXT;

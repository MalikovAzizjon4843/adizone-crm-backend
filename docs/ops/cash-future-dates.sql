-- Kelajak sanali kassa yozuvlari (transaction_date > bugun) — ko'rib chiqish va to'lov sanasiga tuzatish (QO'LDA).
--
-- Sabab: to'lov kiritilgandan keyin to'lov sanasi tuzatilgan (masalan RCP-00108), kassa yozuvi esa eski (kelajak)
-- sanada qolgan. Kelajak sanali yozuv kunlik va davr hisobotlarida noto'g'ri kunga tushadi.
--
-- Taklif qoidasi (faqat INCOME):
--   to'lov topildi — payment_id bo'yicha, bo'lmasa shu kassada izoh "To'lov #<receipt_number>" bo'yicha —
--   va to'lov sanasi ≤ bugun  → transaction_date := payments.payment_date;
--   to'lov topilmadi yoki to'lov sanasi ham kelajakda, yoki yozuv INCOME emas → taklif YO'Q (SA qo'lda hal qiladi).
-- Faqat transaction_date o'zgaradi (summa, balans, topshirish — yo'q). Idempotent: qayta bajarilsa 0 qator.
--
-- Ishlatish (psql, crm_user, -X -v ON_ERROR_STOP=1):
--   1) Ko'rib chiqish — hech narsa yozmaydi (2-bo'lim "read-only transaction" xatosi bilan to'xtaydi — kutilgan):
--        PGOPTIONS='-c default_transaction_read_only=on' psql ... -X -v ON_ERROR_STOP=1 -f cash-future-dates.sql
--   2) Qo'llash: psql ... -X -v ON_ERROR_STOP=1 -f cash-future-dates.sql

-- ── 1. KO'RIB CHIQISH (faqat o'qiydi) ───────────────────────────────────────────────
-- 1a. Kelajak sanali kassa yozuvlari + bog'langan to'lov + taklif
WITH fut AS (
    SELECT t.*
      FROM cash_transactions t
     WHERE t.transaction_date > current_date
), linked AS (
    SELECT f.id AS tx_id,
           COALESCE(p1.id, p2.id)                         AS payment_id,
           COALESCE(p1.receipt_number, p2.receipt_number) AS receipt_number,
           COALESCE(p1.payment_date, p2.payment_date)     AS payment_date,
           COALESCE(p1.status, p2.status)                 AS payment_status,
           CASE WHEN p1.id IS NOT NULL THEN 'payment_id'
                WHEN p2.id IS NOT NULL THEN 'chek izohi'
                ELSE NULL END                             AS topildi
      FROM fut f
      LEFT JOIN payments p1 ON p1.id = f.payment_id
      LEFT JOIN payments p2 ON f.payment_id IS NULL
                           AND p2.cash_register_id = f.cash_register_id
                           AND f.note = 'To''lov #' || p2.receipt_number
)
SELECT f.id, r.name AS kassa, f.type, f.payment_method, f.amount, f.transaction_date,
       f.created_at::date AS kiritilgan, f.transaction_name, f.note,
       l.topildi, l.payment_id, l.receipt_number, l.payment_date, l.payment_status,
       CASE WHEN f.type = 'INCOME' AND l.payment_date IS NOT NULL AND l.payment_date <= current_date
            THEN l.payment_date END AS taklif_sana,
       CASE WHEN f.type <> 'INCOME' THEN 'INCOME emas — qo''lda'
            WHEN l.payment_id IS NULL THEN 'to''lov topilmadi — qo''lda'
            WHEN l.payment_date > current_date THEN 'to''lov sanasi ham kelajakda — qo''lda'
            ELSE 'to''lov sanasiga' END AS izoh
  FROM fut f
  JOIN cash_registers r ON r.id = f.cash_register_id
  LEFT JOIN linked l ON l.tx_id = f.id
 ORDER BY f.transaction_date, f.id;

-- 1b. Ma'lumot uchun: kelajak sanali to'lovlar (payments) — kassa yozuvi bilan birga ko'rib chiqing
SELECT p.id, p.receipt_number, p.payment_date, p.created_at::date AS kiritilgan, p.status,
       COALESCE(p.cash_amount, p.amount) AS summa, p.cash_register_id
  FROM payments p
 WHERE p.payment_date > current_date
 ORDER BY p.payment_date, p.id;

-- ── 2. QO'LLASH ─────────────────────────────────────────────────────────────────────
BEGIN;

CREATE TEMP TABLE cash_future_fix ON COMMIT DROP AS
SELECT t.id AS tx_id, t.transaction_date AS old_date, COALESCE(p1.payment_date, p2.payment_date) AS new_date
  FROM cash_transactions t
  LEFT JOIN payments p1 ON p1.id = t.payment_id
  LEFT JOIN payments p2 ON t.payment_id IS NULL
                       AND p2.cash_register_id = t.cash_register_id
                       AND t.note = 'To''lov #' || p2.receipt_number
 WHERE t.transaction_date > current_date
   AND t.type = 'INCOME'
   AND COALESCE(p1.payment_date, p2.payment_date) IS NOT NULL
   AND COALESCE(p1.payment_date, p2.payment_date) <= current_date;

UPDATE cash_transactions t
   SET transaction_date = f.new_date,
       updated_at       = now()
  FROM cash_future_fix f
 WHERE t.id = f.tx_id
   AND t.transaction_date > current_date;

DO $$
DECLARE
    fixed  bigint;
    left_  bigint;
BEGIN
    SELECT COUNT(*) INTO fixed FROM cash_future_fix;
    SELECT COUNT(*) INTO left_
      FROM cash_transactions t JOIN cash_future_fix f ON f.tx_id = t.id
     WHERE t.transaction_date <> f.new_date;
    IF left_ > 0 THEN
        RAISE EXCEPTION 'cash-future-dates: % ta yozuv tuzatilmadi (tanlangan %)', left_, fixed;
    END IF;
    RAISE NOTICE 'cash-future-dates: % ta yozuv to''lov sanasiga tuzatildi; qolgan kelajak sanalilar: %', fixed,
        (SELECT COUNT(*) FROM cash_transactions WHERE transaction_date > current_date);
END $$;

COMMIT;

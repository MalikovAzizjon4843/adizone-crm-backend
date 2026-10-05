-- future-dated.sql — FAQAT O'QIYDI. Kelajak sanali pul yozuvlari (sana > joriy Toshkent kuni).
-- 2026-10-05 qoidasi (PaymentDatePolicy): yangi to'lov / kassa yozuvi / xarajat sanasi bugundan keyin bo'lmaydi.
-- Bu skript qoida kiritilishidan OLDIN yozilgan yozuvlarni topadi; tuzatish — alohida qaror (SA: to'lovni
-- bekor qilib qayta kiritish yoki ma'lumotni egasi bilan kelishib yangilash).
--
--   PGOPTIONS='-c default_transaction_read_only=on' psql ... -X -v ON_ERROR_STOP=1 -f docs/ops/future-dated.sql

-- 1. Jamlanma
WITH today AS (SELECT (now() AT TIME ZONE 'Asia/Tashkent')::date AS d)
SELECT 'payments' AS jadval, COUNT(*) AS soni, COALESCE(SUM(p.amount), 0) AS summa,
       MIN(p.payment_date::date) AS eng_erta, MAX(p.payment_date::date) AS eng_kech
  FROM payments p, today
 WHERE p.payment_date::date > today.d
UNION ALL
SELECT 'cash_transactions', COUNT(*), COALESCE(SUM(c.amount), 0), MIN(c.transaction_date), MAX(c.transaction_date)
  FROM cash_transactions c, today
 WHERE c.transaction_date > today.d;

-- 2. To'lovlar (har biri)
WITH today AS (SELECT (now() AT TIME ZONE 'Asia/Tashkent')::date AS d)
SELECT p.id, p.receipt_number, p.status, p.payment_date, p.amount, p.cash_amount, p.payment_method,
       p.student_id, s.first_name || ' ' || s.last_name AS student, p.student_group_id, p.cash_register_id,
       p.created_at, p.payment_date::date - today.d AS kun_oldinda
  FROM payments p
  JOIN today ON true
  LEFT JOIN students s ON s.id = p.student_id
 WHERE p.payment_date::date > today.d
 ORDER BY p.payment_date, p.id;

-- 3. Kassa yozuvlari (har biri)
WITH today AS (SELECT (now() AT TIME ZONE 'Asia/Tashkent')::date AS d)
SELECT c.id, c.cash_register_id, c.type, c.transaction_date, c.amount, c.payment_method, c.transaction_name,
       c.payment_id, c.created_at, c.transaction_date - today.d AS kun_oldinda
  FROM cash_transactions c
  JOIN today ON true
 WHERE c.transaction_date > today.d
 ORDER BY c.transaction_date, c.id;

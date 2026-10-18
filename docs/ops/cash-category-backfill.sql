-- cash_transactions.category backfill — eski kassa yozuvlariga kategoriya (V86 dan KEYIN, QO'LDA).
-- docs/design/cash-and-books.md §1.2.2.
--
-- Qoidalar (birinchi mos kelgani; faqat category IS NULL qatorlar — yangi kod yozgani ustiga yozilmaydi):
--   INCOME / EXPENSE:
--     payment_id NOT NULL                                   → STUDENT_PAYMENT
--     exam_registration_id NOT NULL                         → EXAM
--     payroll_id NOT NULL                                   → SALARY
--     INCOME, nomi 'O''quvchi to''lovi' (payment_id siz — billing v2 dan oldingi to'lov; to'lovga izoh
--       "To'lov #RCP-…" orqali bog'langan, to'lov payments da bor)  → STUDENT_PAYMENT
--     EXPENSE, nomi 'O''quvchiga pul qaytarildi'            → REFUND
--     EXPENSE, nomi 'Xarajat: X' va X cash_categories da   → X   (eski FinanceService.createExpense)
--     INCOME, qolgani (nomsiz ham)                          → OTHER_INCOME
--     EXPENSE, qolgani (kassadan to'g'ridan chiqim)        → OTHER (strategik — tarix Direktorga)
--   REVERSAL: asl yozuvniki (related_tx_id); asl topilmasa — payment/exam/payroll bog'lanishidan.
--   TRANSFER: NULL qoladi.
-- Hisobot (CashCategoryTotals) category NULL bo'lsa xuddi shu qoidani o'zi qo'llaydi — backfill hisobot
-- raqamlarini O'ZGARTIRMAYDI, faqat filtr va ro'yxatlar uchun kerak. Idempotent: qayta bajarilsa 0 qator.
--
-- Ishlatish (psql, crm_user, -X -v ON_ERROR_STOP=1):
--   1) Ko'rib chiqish — hech narsa yozmaydi (2-bo'lim "read-only transaction" xatosi bilan to'xtaydi — kutilgan):
--        PGOPTIONS='-c default_transaction_read_only=on' psql ... -X -v ON_ERROR_STOP=1 -f cash-category-backfill.sql
--   2) Qo'llash: psql ... -X -v ON_ERROR_STOP=1 -f cash-category-backfill.sql
--      2-bo'lim oxiridagi tekshiruv kategoriyasiz INCOME/EXPENSE qolsa ROLLBACK qiladi.

-- ── 1. KO'RIB CHIQISH (faqat o'qiydi) ───────────────────────────────────────────────
-- 1a. Hozirgi holat: tur bo'yicha kategoriyali / kategoriyasiz
SELECT type,
       COUNT(*)                                  AS jami,
       COUNT(*) FILTER (WHERE category IS NULL)  AS kategoriyasiz,
       SUM(amount) FILTER (WHERE category IS NULL) AS kategoriyasiz_summa
  FROM cash_transactions
 GROUP BY type
 ORDER BY type;

-- 1b. INCOME / EXPENSE: yangi kategoriya bo'yicha soni va summasi (2-bo'limdagi UPDATE shu to'plam)
WITH pick AS (
    SELECT t.id, t.type, t.amount,
           CASE
               WHEN t.payment_id IS NOT NULL THEN 'STUDENT_PAYMENT'
               WHEN t.exam_registration_id IS NOT NULL THEN 'EXAM'
               WHEN t.payroll_id IS NOT NULL THEN 'SALARY'
               WHEN t.type = 'INCOME' AND t.transaction_name = 'O''quvchi to''lovi' THEN 'STUDENT_PAYMENT'
               WHEN t.type = 'EXPENSE' AND t.transaction_name = 'O''quvchiga pul qaytarildi' THEN 'REFUND'
               WHEN t.type = 'EXPENSE' AND t.transaction_name LIKE 'Xarajat: %'
                    AND EXISTS (SELECT 1 FROM cash_categories c WHERE c.code = SUBSTRING(t.transaction_name FROM 10))
                   THEN SUBSTRING(t.transaction_name FROM 10)
               WHEN t.type = 'INCOME' THEN 'OTHER_INCOME'
               ELSE 'OTHER'
           END AS new_category
      FROM cash_transactions t
     WHERE t.category IS NULL AND t.type IN ('INCOME', 'EXPENSE')
)
SELECT type, new_category, COUNT(*) AS soni, SUM(amount) AS summa
  FROM pick GROUP BY type, new_category
UNION ALL
SELECT '== JAMI ==', NULL, COUNT(*), SUM(amount) FROM pick
 ORDER BY 1, 2;

-- 1c. Kassadan to'g'ridan chiqimlar (OTHER ga tushadiganlar) — namuna, SA ko'rib chiqsin (50 ta, eng yiriklari)
SELECT t.id, t.transaction_date, r.name AS kassa, t.amount, t.transaction_name, t.note
  FROM cash_transactions t
  JOIN cash_registers r ON r.id = t.cash_register_id
 WHERE t.category IS NULL AND t.type = 'EXPENSE'
   AND t.payment_id IS NULL AND t.exam_registration_id IS NULL AND t.payroll_id IS NULL
   AND COALESCE(t.transaction_name, '') <> 'O''quvchiga pul qaytarildi'
   AND COALESCE(t.transaction_name, '') NOT LIKE 'Xarajat: %'
 ORDER BY t.amount DESC
 LIMIT 50;

-- 1d. REVERSAL lar: asl yozuv kategoriyasi bo'yicha (asl hali kategoriyasiz bo'lsa — 1b dan keyin oladi)
SELECT COALESCE(o.category, '(asl hali kategoriyasiz)') AS asl_kategoriya, COUNT(*) AS soni, SUM(t.amount) AS summa
  FROM cash_transactions t
  LEFT JOIN cash_transactions o ON o.id = t.related_tx_id
 WHERE t.type = 'REVERSAL' AND t.category IS NULL
 GROUP BY 1
 ORDER BY 2 DESC;

-- 1e. payment_id siz "O'quvchi to'lovi" kirimlari: to'lovga izoh ("To'lov #RCP-…", shu kassa) orqali topiladimi.
--     Moliya hisoboti o'quvchi to'lovini payments dan oladi (kassa yozuvi ikki marta sanalmaydi) — topilmaganlari
--     ("to'lovsiz") hisobotda ko'rinmaydi: SA ko'rib chiqsin (payments da bormi, boshqa izoh bilanmi).
SELECT CASE WHEN p.id IS NULL THEN 'TO''LOVSIZ' WHEN p.status = 'PAID' THEN 'PAID' ELSE p.status END AS tolov,
       t.payment_method, COUNT(*) AS soni, SUM(t.amount) AS summa
  FROM cash_transactions t
  LEFT JOIN payments p ON p.cash_register_id = t.cash_register_id
                      AND t.note = 'To''lov #' || p.receipt_number
 WHERE t.category IS NULL AND t.type = 'INCOME' AND t.payment_id IS NULL
   AND t.transaction_name = 'O''quvchi to''lovi'
 GROUP BY 1, 2
 ORDER BY 1, 2;

-- 1f. Nomsiz kirimlar (OTHER_INCOME ga tushadi) — ro'yxat
SELECT t.id, t.transaction_date, r.name AS kassa, t.payment_method, t.amount, t.note
  FROM cash_transactions t
  JOIN cash_registers r ON r.id = t.cash_register_id
 WHERE t.category IS NULL AND t.type = 'INCOME' AND t.payment_id IS NULL
   AND t.exam_registration_id IS NULL AND COALESCE(t.transaction_name, '') = ''
 ORDER BY t.id;

-- ── 2. QO'LLASH ─────────────────────────────────────────────────────────────────────
BEGIN;

UPDATE cash_transactions t
   SET category = CASE
           WHEN t.payment_id IS NOT NULL THEN 'STUDENT_PAYMENT'
           WHEN t.exam_registration_id IS NOT NULL THEN 'EXAM'
           WHEN t.payroll_id IS NOT NULL THEN 'SALARY'
           WHEN t.type = 'INCOME' AND t.transaction_name = 'O''quvchi to''lovi' THEN 'STUDENT_PAYMENT'
           WHEN t.type = 'EXPENSE' AND t.transaction_name = 'O''quvchiga pul qaytarildi' THEN 'REFUND'
           WHEN t.type = 'EXPENSE' AND t.transaction_name LIKE 'Xarajat: %'
                AND EXISTS (SELECT 1 FROM cash_categories c WHERE c.code = SUBSTRING(t.transaction_name FROM 10))
               THEN SUBSTRING(t.transaction_name FROM 10)
           WHEN t.type = 'INCOME' THEN 'OTHER_INCOME'
           ELSE 'OTHER'
       END
 WHERE t.category IS NULL AND t.type IN ('INCOME', 'EXPENSE');

-- REVERSAL: asl yozuvniki
UPDATE cash_transactions t
   SET category = o.category
  FROM cash_transactions o
 WHERE t.type = 'REVERSAL' AND t.category IS NULL
   AND o.id = t.related_tx_id AND o.category IS NOT NULL;

-- Asli topilmagan REVERSAL: bog'lanishdan
UPDATE cash_transactions t
   SET category = CASE
           WHEN t.payment_id IS NOT NULL THEN 'STUDENT_PAYMENT'
           WHEN t.exam_registration_id IS NOT NULL THEN 'EXAM'
           WHEN t.payroll_id IS NOT NULL THEN 'SALARY'
       END
 WHERE t.type = 'REVERSAL' AND t.category IS NULL
   AND (t.payment_id IS NOT NULL OR t.exam_registration_id IS NOT NULL OR t.payroll_id IS NOT NULL);

-- Tekshiruv: kategoriyasiz INCOME / EXPENSE qolmadi
DO $$
DECLARE
    left_  bigint;
    rev    bigint;
BEGIN
    SELECT COUNT(*) INTO left_ FROM cash_transactions WHERE category IS NULL AND type IN ('INCOME', 'EXPENSE');
    IF left_ > 0 THEN
        RAISE EXCEPTION 'cash-category-backfill: % ta INCOME/EXPENSE kategoriyasiz qoldi', left_;
    END IF;
    SELECT COUNT(*) INTO rev FROM cash_transactions WHERE category IS NULL AND type = 'REVERSAL';
    RAISE NOTICE 'cash-category-backfill: tayyor; kategoriyasiz REVERSAL (asl yozuvi va bog''lanishi yo''q): %', rev;
END $$;

COMMIT;

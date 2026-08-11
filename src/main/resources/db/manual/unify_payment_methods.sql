-- ============================================================================
-- To'lov usullarini yagona PaymentMethod enumiga keltirish.
--
-- Flyway O'CHIQ — bu fayl QO'LDA ishga tushiriladi, migration EMAS.
-- Ilovani TO'XTATIB, so'ng bajaring (yoki ish vaqti tashqarisida).
--
-- Yagona ro'yxat:
--   CASH, CARD, CLICK, PAYME, UZUM, TERMINAL, BANK, CASH_AND_CARD, OTHER
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. cash_transactions — eski ro'yxat: CASH, PLASTIC, ONLINE, CASH_AND_CARD
-- ---------------------------------------------------------------------------

-- PLASTIC -> CARD
UPDATE cash_transactions SET payment_method = 'CARD'
 WHERE payment_method = 'PLASTIC';

-- ONLINE -> OTHER (aniq usul noma'lum, qo'lda aniqlashtirish mumkin)
UPDATE cash_transactions SET payment_method = 'OTHER'
 WHERE payment_method = 'ONLINE';

-- ---------------------------------------------------------------------------
-- 2. payroll.payment_method — matn ustuni, enum EMAS.
--    Eski qiymatlar: BANK_TRANSFER, CASH, CASH_AND_CARD, PLASTIC, ONLINE
-- ---------------------------------------------------------------------------

UPDATE payroll SET payment_method = 'BANK'
 WHERE payment_method = 'BANK_TRANSFER';

UPDATE payroll SET payment_method = 'CARD'
 WHERE payment_method = 'PLASTIC';

UPDATE payroll SET payment_method = 'OTHER'
 WHERE payment_method = 'ONLINE';

-- ---------------------------------------------------------------------------
-- 3. payments — V32__payment_methods.sql allaqachon ONLINE/CARD/BANK_TRANSFER
--    qiymatlarini ko'chirgan. Agar o'sha migration ishlamagan bo'lsa, quyidagi
--    ikkitasi qoladi (CARD endi yaroqli qiymat, shuning uchun u ko'chirilmaydi).
-- ---------------------------------------------------------------------------

UPDATE payments SET payment_method = 'OTHER'
 WHERE payment_method = 'ONLINE';

UPDATE payments SET payment_method = 'BANK'
 WHERE payment_method = 'BANK_TRANSFER';

UPDATE payments SET payment_method = 'CARD'
 WHERE payment_method = 'PLASTIC';

COMMIT;

-- ---------------------------------------------------------------------------
-- 4. Tekshirish: yaroqsiz qiymat qolmaganini ko'rish.
--    Har uchala so'rov faqat yuqoridagi 9 ta nomni qaytarishi kerak.
-- ---------------------------------------------------------------------------

SELECT payment_method, COUNT(*) FROM cash_transactions GROUP BY 1 ORDER BY 2 DESC;
SELECT payment_method, COUNT(*) FROM payments           GROUP BY 1 ORDER BY 2 DESC;
SELECT payment_method, COUNT(*) FROM payroll            GROUP BY 1 ORDER BY 2 DESC;

-- Yaroqsiz qiymatlarni bitta so'rovda topish:
SELECT 'cash_transactions' AS tbl, payment_method, COUNT(*)
  FROM cash_transactions
 WHERE payment_method IS NOT NULL
   AND payment_method NOT IN ('CASH','CARD','CLICK','PAYME','UZUM','TERMINAL',
                              'BANK','CASH_AND_CARD','OTHER')
 GROUP BY 1, 2
UNION ALL
SELECT 'payments', payment_method, COUNT(*)
  FROM payments
 WHERE payment_method IS NOT NULL
   AND payment_method NOT IN ('CASH','CARD','CLICK','PAYME','UZUM','TERMINAL',
                              'BANK','CASH_AND_CARD','OTHER')
 GROUP BY 1, 2
UNION ALL
SELECT 'payroll', payment_method, COUNT(*)
  FROM payroll
 WHERE payment_method IS NOT NULL
   AND payment_method NOT IN ('CASH','CARD','CLICK','PAYME','UZUM','TERMINAL',
                              'BANK','CASH_AND_CARD','OTHER')
 GROUP BY 1, 2;

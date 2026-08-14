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
-- 2. payroll.payment_method — endi @Enumerated(STRING). Ustun tipi varchar
--    bo'lib qoladi, ya'ni tip migratsiyasi shart emas — faqat qiymatlarni
--    tozalash kerak. Eski qiymatlar: BANK_TRANSFER, PLASTIC, ONLINE va h.k.
-- ---------------------------------------------------------------------------

UPDATE payroll SET payment_method = 'BANK'
 WHERE payment_method = 'BANK_TRANSFER';

UPDATE payroll SET payment_method = 'CARD'
 WHERE payment_method = 'PLASTIC';

UPDATE payroll SET payment_method = 'OTHER'
 WHERE payment_method = 'ONLINE';

-- Tanib bo'lmaydigan qolgan qiymatlar -> NULL (to'lanmagan oylik uchun ruxsat etilgan).
-- Bularsiz Hibernate satrni o'qiyotganda IllegalArgumentException beradi.
UPDATE payroll SET payment_method = NULL
 WHERE payment_method IS NOT NULL
   AND payment_method NOT IN ('CASH','CARD','CLICK','PAYME','UZUM',
                              'TERMINAL','BANK','CASH_AND_CARD','OTHER');

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
-- 4. Yakuniy tekshiruv: yaroqsiz qiymat qolmaganini ko'rish.
--    Natijada faqat quyidagi 9 ta nom (va payroll uchun NULL) bo'lishi kerak:
--    CASH, CARD, CLICK, PAYME, UZUM, TERMINAL, BANK, CASH_AND_CARD, OTHER
-- ---------------------------------------------------------------------------

SELECT 'payments' AS jadval, payment_method, COUNT(*) FROM payments GROUP BY 1,2
UNION ALL
SELECT 'cash_transactions', payment_method, COUNT(*) FROM cash_transactions GROUP BY 1,2
UNION ALL
SELECT 'payroll', payment_method, COUNT(*) FROM payroll GROUP BY 1,2
ORDER BY 1, 2;

-- ---------------------------------------------------------------------------
-- 5. cash_transactions.cash_part / card_part ustunlarini Hibernate
--    (ddl-auto: update) o'zi qo'shadi — qo'lda yaratish shart emas.
--    Eski CASH_AND_CARD yozuvlarida ular NULL bo'lib qoladi; kod bunday
--    yozuvlarni butun summa naqd deb hisoblaydi va log.warn yozadi.
--    Ularni ko'rish uchun:
-- ---------------------------------------------------------------------------

SELECT id, transaction_date, amount, cash_part, card_part
  FROM cash_transactions
 WHERE payment_method = 'CASH_AND_CARD'
   AND (cash_part IS NULL OR card_part IS NULL)
 ORDER BY transaction_date DESC;

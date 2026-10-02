-- Oylik qoidalari: ustma-ust tushgan / teskari muddatli qoidalarni tuzatish (payroll-v2 §11 #3, §13).
--
-- V56 dan KEYIN, QO'LDA bajariladi. Idempotent: tuzatilgan qoida nofaol bo'ladi va shartlarga qayta
-- tushmaydi. Faqat APPROVED/PAID oylikda ISHLATILMAGAN qoidalarga tegiladi (payroll.salary_rule_id);
-- ishlatilganlari o'zgarmaydi — NOTICE da ro'yxat, qo'lda ko'riladi (muzlatilgan snapshot ularga
-- bog'liq emas, lekin tarix aniqligi uchun avtomatik o'zgartirilmaydi).
--
-- Holat (lokal bazada): bir xil doirada (TEACHER, umumiy) ikkita faol qoida, ikkalasi 02.10 dan —
-- ikkinchisi yaratilganda birinchisi yopilmagan (yopish faqat effectiveFrom < yangi.effectiveFrom ni
-- olardi). Endi ilova bunday holatda oldingisini nofaol qiladi yoki 409 salaryRule.overlapsUsed beradi.

BEGIN;

-- ── 1. Teskari muddat: effective_to < effective_from ──────────────────
UPDATE salary_rules r
   SET is_active = FALSE, effective_to = NULL, updated_at = now()
 WHERE r.is_active
   AND r.effective_from IS NOT NULL AND r.effective_to IS NOT NULL
   AND r.effective_to < r.effective_from
   AND NOT EXISTS (SELECT 1 FROM payroll p
                    WHERE p.salary_rule_id = r.id AND p.status IN ('APPROVED', 'PAID'));

-- ── 2. Ustma-ust: shu doirada keyin yaratilgan faol qoida xuddi shu yoki oldinroq sanadan ──
-- Doira: shaxsiy — shu user_id; umumiy — shu role va user_id IS NULL. Eng yangisi (id katta) qoladi.
UPDATE salary_rules r
   SET is_active = FALSE, effective_to = NULL, updated_at = now()
 WHERE r.is_active
   AND r.effective_from IS NOT NULL
   AND EXISTS (SELECT 1 FROM salary_rules n
                WHERE n.is_active AND n.id > r.id
                  AND n.role = r.role
                  AND n.user_id IS NOT DISTINCT FROM r.user_id
                  AND n.effective_from IS NOT NULL
                  AND n.effective_from <= r.effective_from)
   AND NOT EXISTS (SELECT 1 FROM payroll p
                    WHERE p.salary_rule_id = r.id AND p.status IN ('APPROVED', 'PAID'));

-- ── 3. Qolgan (ishlatilgani uchun tegilmagan) muammoli qoidalar — qo'lda ko'rish uchun ──
DO $$
DECLARE ids text;
BEGIN
    SELECT string_agg(r.id::text, ', ') INTO ids
      FROM salary_rules r
     WHERE r.is_active
       AND ((r.effective_from IS NOT NULL AND r.effective_to IS NOT NULL AND r.effective_to < r.effective_from)
         OR (r.effective_from IS NOT NULL AND EXISTS (
                SELECT 1 FROM salary_rules n
                 WHERE n.is_active AND n.id > r.id AND n.role = r.role
                   AND n.user_id IS NOT DISTINCT FROM r.user_id
                   AND n.effective_from IS NOT NULL AND n.effective_from <= r.effective_from)));
    IF ids IS NOT NULL THEN
        RAISE NOTICE 'salary_rules: tasdiqlangan/to''langan oylikda ishlatilgani uchun tegilmadi: %', ids;
    END IF;
END $$;

COMMIT;

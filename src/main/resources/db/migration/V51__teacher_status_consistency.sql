-- teachers.status va teachers.is_active izchilligi.
--
-- Qoida (endi Teacher entity da majburiy):
--   status = INACTIVE          -> is_active = false
--   status = ACTIVE / ON_LEAVE -> is_active = true
--
-- Eski kod ikki maydonni alohida yozardi va ular zid bo'lib qolgan:
--   * DELETE /api/teachers/{id}  faqat is_active = false yozardi (status ACTIVE qolardi);
--   * o'qituvchini tahrirlash formasi faqat status yozardi (is_active true qolardi) —
--     shuning uchun "INACTIVE" qilingan o'qituvchi ro'yxatda faol ko'rinardi.
--
-- Zid qatorda NOFAOL ustun: ikkala eski yo'lda ham admin o'qituvchini
-- nofaol qilmoqchi bo'lgan. Hech kim bu migratsiya tufayli faollashmaydi.
-- Idempotent — qayta ishga tushirish xavfsiz.

-- 1) Registr/bo'shliqlarni tozalash ('active ' -> 'ACTIVE')
UPDATE teachers
   SET status = UPPER(TRIM(status))
 WHERE status IS NOT NULL
   AND status <> UPPER(TRIM(status));

-- 2) Statusi yo'q qatorlar is_active dan kelib chiqadi
UPDATE teachers
   SET status = CASE WHEN is_active = FALSE THEN 'INACTIVE' ELSE 'ACTIVE' END
 WHERE status IS NULL OR status = '';

-- 3) Zidlik: bitta maydon "nofaol" desa — ikkalasi ham nofaol
UPDATE teachers
   SET status = 'INACTIVE',
       is_active = FALSE
 WHERE (is_active = FALSE OR status = 'INACTIVE')
   AND (is_active IS DISTINCT FROM FALSE OR status IS DISTINCT FROM 'INACTIVE');

-- 4) Qolganlari (ACTIVE / ON_LEAVE) faol
UPDATE teachers
   SET is_active = TRUE
 WHERE status <> 'INACTIVE'
   AND (is_active IS NULL OR is_active = FALSE);

-- Tekshiruv: noma'lum statuslar (masalan, qo'lda kiritilgan) shu yerda ko'rinadi.
-- Ular o'zgartirilmaydi — lekin keyingi tahrirda API ularni 400 bilan rad etadi.
DO $$
DECLARE
    unknown TEXT;
BEGIN
    SELECT string_agg(DISTINCT status, ', ')
      INTO unknown
      FROM teachers
     WHERE status NOT IN ('ACTIVE', 'INACTIVE', 'ON_LEAVE');
    IF unknown IS NOT NULL THEN
        RAISE NOTICE 'teachers.status da noma''lum qiymatlar bor: %', unknown;
    END IF;
END $$;

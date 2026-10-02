-- Shartnoma raqami har yil noldan: CTR-2027-00001 (phase5-audit C-01, Q8; buyurtmachi qarori).
--
-- V58 dan KEYIN, QO'LDA bajariladi (Flyway yo'q). Idempotent: qayta ishga tushirish xavfsiz.
-- Ilovani yangilashdan OLDIN bajaring — yangi kod shu jadvalga yozadi.
--
-- Hisoblagich: har yilga bitta qator. Ilova UPDATE ... SET last_value = last_value + 1 bilan
-- qatorni qulflab raqam oladi (ContractNumberService). Mavjud raqamlar O'ZGARMAYDI:
--   * eski CTR-0001 ko'rinishidagilar boshqa formatda — hisobga olinmaydi;
--   * V58 davridagi CTR-YYYY-NNNNN lar — shu yil hisoblagichi ularning eng kattasidan davom etadi.
-- contract_number_seq (V58) endi ishlatilmaydi; ataylab o'chirilmaydi (orqaga qaytish uchun).

BEGIN;

CREATE TABLE IF NOT EXISTS contract_number_counters (
    contract_year INTEGER PRIMARY KEY,
    last_value    BIGINT  NOT NULL CHECK (last_value >= 0)
);

-- Mavjud yangi formatdagi raqamlardan boshlang'ich qiymat. Qayta bajarilsa hisoblagich
-- kamaymaydi (GREATEST) — ilova bergan raqamlar takrorlanmaydi.
INSERT INTO contract_number_counters (contract_year, last_value)
SELECT SUBSTRING(contract_number FROM 5 FOR 4)::INTEGER,
       MAX(SUBSTRING(contract_number FROM 10)::BIGINT)
  FROM contracts
 WHERE contract_number ~ '^CTR-[0-9]{4}-[0-9]+$'
 GROUP BY 1
ON CONFLICT (contract_year)
DO UPDATE SET last_value = GREATEST(contract_number_counters.last_value, EXCLUDED.last_value);

COMMIT;

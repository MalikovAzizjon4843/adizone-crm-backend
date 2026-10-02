-- pgtest: V52/V53 dan KEYIN bajariladi (application-pgtest.yml).
--
-- V53 bo'sh lead_stages ga "VISITED_TRIAL" ni qo'shadi; LeadStageSeeder esa faqat BO'SH
-- jadvalni to'ldiradi. H2 dagi bilan bir xil boshlang'ich holat (seed'ning 10 bosqichi)
-- uchun V53 qo'shgan qator olib tashlanadi.
DELETE FROM lead_stages;

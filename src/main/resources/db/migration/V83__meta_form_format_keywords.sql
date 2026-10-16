-- Meta lid formati (buyurtmachi 16.10.2026). FAQAT sxema, ma'lumotga tegmaydi.
--   meta_lead_form_questions.crm_field yangi qiymat FORMAT oladi (VARCHAR(32), cheklov yo'q — migratsiya kerak emas):
--   javob matnida onlayn/online/онлайн → ONLINE, oflayn/offline/офлайн/markaz/o'quv markaz → OFFLINE; tanilmasa,
--   savol bo'lmasa yoki forma UNMAPPED bo'lsa — leads.format NULL ("aniqlanmagan"). Ilgari Meta va sayt lidiga OFFLINE
--   taxmin qilinardi (Lead.@PrePersist, MetaLeadIngestService.resolveFormat, LeadService.createLead) — olib tashlangan.
--   Kalit so'zlar forma sozlamasida (vergul bilan); NULL — standart ro'yxat.
-- Mavjud lidlarning formati o'zgartirilmaydi.
--
-- V82 dan KEYIN, QO'LDA, crm_user bilan, psql -X -v ON_ERROR_STOP=1. Idempotent (IF NOT EXISTS).
-- Tekshirish: docs/ops/prod-schema-check.sql — V83 bo'lagi "TO'LIQ".

BEGIN;

ALTER TABLE meta_lead_forms ADD COLUMN IF NOT EXISTS format_online_keywords VARCHAR(500);
ALTER TABLE meta_lead_forms ADD COLUMN IF NOT EXISTS format_offline_keywords VARCHAR(500);

COMMIT;

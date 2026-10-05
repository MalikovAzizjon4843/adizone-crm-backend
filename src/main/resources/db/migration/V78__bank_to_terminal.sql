-- payment_method BANK → TERMINAL — buyurtmachi qarori 2026-10-05: BANK alohida to'lov turi emas, TERMINAL bank
-- to'lovi ma'nosida. Kod: PaymentChannel da BANK kanali yo'q (eski BANK → TERMINAL), yangi to'lov / xarajat / kassa
-- yozuvida BANK → 400 (payment.method.bankNotAccepted). Enum qiymati eski yozuvlarni o'qish uchun qoladi.
--
-- Qamrov: public sxemadagi payment_method ustuni bor HAMMA jadval (varchar/text) — hozir payments,
-- cash_transactions, payroll (expenses da ustun yo'q). Summa, kassa chelaklari (cash/plastic balance) va
-- ledger o'zgarmaydi: BANK va TERMINAL ikkalasi ham naqdsiz (plastic) chelak.
-- Audit: o'zgargan har jadval soni va id lari bitta audit_logs yozuviga (username 'V78', entity_type 'PaymentMethod',
-- matni ASCII — psql client_encoding ga bog'liq bo'lmasin;
-- details_json = {"jadval": {"count": N, "ids": "1,2,..."}}) — kerak bo'lsa qaytarish uchun.
--
-- V77 dan KEYIN, QO'LDA, crm_user bilan, psql -X -v ON_ERROR_STOP=1. Idempotent: qayta bajarilsa 0 qator, audit
-- yozuvi qo'shilmaydi.
--   Ko'rib chiqish (hech narsa yozmaydi; 2-bo'lim "read-only transaction" xatosi bilan to'xtaydi — kutilgan):
--     PGOPTIONS='-c default_transaction_read_only=on' psql ... -X -v ON_ERROR_STOP=1 -f V78__bank_to_terminal.sql
--   Qo'llash: psql ... -X -v ON_ERROR_STOP=1 -f V78__bank_to_terminal.sql
-- Tekshirish: docs/ops/prod-schema-check.sql — "V78 payment_method = BANK yo'q" = true.

-- ── 1. KO'RIB CHIQISH (faqat o'qiydi) ───────────────────────────────────────────────
-- 1a. payment_method ustuni bor jadvallar (2-bo'lim aynan shularni yangilaydi)
SELECT table_name, data_type
  FROM information_schema.columns
 WHERE table_schema = 'public' AND column_name = 'payment_method'
 ORDER BY table_name;

-- 1b. BANK yozuvlari soni va summasi (2-bo'limdagi UPDATE soni = shu)
SELECT 'payments' AS jadval, COUNT(*) AS bank_soni, COALESCE(SUM(amount), 0) AS summa
  FROM payments WHERE payment_method = 'BANK'
UNION ALL
SELECT 'cash_transactions', COUNT(*), COALESCE(SUM(amount), 0)
  FROM cash_transactions WHERE payment_method = 'BANK'
UNION ALL
SELECT 'payroll', COUNT(*), COALESCE(SUM(net_salary), 0)
  FROM payroll WHERE payment_method = 'BANK';

-- ── 2. QO'LLASH ─────────────────────────────────────────────────────────────────────
BEGIN;

DO $$
DECLARE
    t       record;
    n       bigint;
    ids     text;
    details jsonb := '{}'::jsonb;
    total   bigint := 0;
BEGIN
    FOR t IN
        SELECT c.table_name,
               EXISTS (SELECT 1 FROM information_schema.columns i
                        WHERE i.table_schema = 'public' AND i.table_name = c.table_name
                          AND i.column_name = 'id') AS has_id
          FROM information_schema.columns c
          JOIN information_schema.tables tb
            ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name AND tb.table_type = 'BASE TABLE'
         WHERE c.table_schema = 'public' AND c.column_name = 'payment_method'
           AND c.data_type IN ('character varying', 'text')
         ORDER BY c.table_name
    LOOP
        EXECUTE format(
            'WITH u AS (UPDATE public.%I SET payment_method = ''TERMINAL'' WHERE payment_method = ''BANK'' RETURNING %s)'
            || ' SELECT COUNT(*), COALESCE(string_agg(k::text, '','' ORDER BY k), '''') FROM u',
            t.table_name, CASE WHEN t.has_id THEN 'id AS k' ELSE 'NULL::bigint AS k' END)
          INTO n, ids;
        IF n > 0 THEN
            details := details || jsonb_build_object(t.table_name, jsonb_build_object('count', n, 'ids', ids));
            total := total + n;
            RAISE NOTICE 'V78: % - % ta BANK -> TERMINAL', t.table_name, n;
        END IF;
    END LOOP;

    IF total > 0 THEN
        INSERT INTO audit_logs (created_at, username, action, entity_type, entity_label, summary, details_json)
        VALUES (NOW(), 'V78', 'UPDATE', 'PaymentMethod', 'BANK -> TERMINAL',
                'V78: to''lov usuli BANK -> TERMINAL (' || total || ' ta yozuv), buyurtmachi qarori 2026-10-05',
                details::text);
    END IF;
END $$;

COMMIT;

-- ── 3. TEKSHIRUV ────────────────────────────────────────────────────────────────────
-- Hammasi 0 bo'lishi kerak
SELECT (SELECT COUNT(*) FROM payments WHERE payment_method = 'BANK')          AS payments_bank,
       (SELECT COUNT(*) FROM cash_transactions WHERE payment_method = 'BANK') AS cash_transactions_bank,
       (SELECT COUNT(*) FROM payroll WHERE payment_method = 'BANK')           AS payroll_bank;

-- ═══════════════════════════════════════════════════════════════════════════════════
-- Lidlarni BUTUNLAY o'chirish — test yoki shaxsiy ma'lumot (iltimos bo'yicha) uchun.
-- CRM'da lid o'chirish yo'q (faqat REJECTED); bu skript lidni va unga bog'liq HAMMA narsani bazadan olib tashlaydi.
--
-- Ishga tushirish (parolni buyruq qatoriga yozmang — ~/.pgpass yoki PGPASSWORD):
--   1) Faqat ko'rish (PREVIEW, hech narsa o'zgarmaydi):
--        psql -h <host> -U crm_user -d adizone -X -v ids='1475,1476' -f docs/ops/lead-hard-delete.sql
--   2) O'chirish (PREVIEW + alohida BEGIN/DELETE/COMMIT bo'limi):
--        psql -h <host> -U crm_user -d adizone -X -v ids='1475,1476' -v confirm=yes -f docs/ops/lead-hard-delete.sql
--   Avval pg_dump zaxira oling (kamida: leads, tasks, lead_*, user_notifications, audit_logs, calls).
--
-- Nima o'chadi (har bir id uchun; ro'yxat — §2 hd_steps):
--   * user_notifications — entity_type Lead/Task + entity_id, link "/leads?lead=ID" / "/tasks?task=ID",
--     dedupe_key "task:ID:..." (FK yo'q — link/payload bo'yicha);
--   * telegram_outbox    — dedupe_key "task:ID:..." (vazifa eslatmalari) va LEAD_ASSIGNED xabarlari, matnida lid
--     ismi bo'lganlari (nom bo'yicha — TAXMINIY, PREVIEW da alohida qator);
--   * audit_logs         — entity_type Lead (lid id) va Task (shu lidlarning vazifalari);
--   * call_requests, call_recordings, calls (V90) — lid_id bo'yicha. Yozuv FAYLLARI diskda qoladi — PREVIEW ro'yxati
--     bo'yicha qo'lda o'chiring (/opt/crm/uploads/calls/<path>);
--   * meta_webhook_events.lead_id (FK yo'q; Meta raw JSON da shaxsiy ma'lumot);
--   * lead_assignments, lead_comments (timeline), lead_notes, lead_status_history (bosqich o'zgarishlari);
--   * tasks (shu lidlarning vazifalari; vazifalarga boshqa jadval FK bilan bog'lanmagan — §1 tekshiradi);
--   * leads.
--   appeals — lidga bog'lanmaydi (ustun yo'q). director_daily_stats — agregat, tegilmaydi.
--
-- Xavfsizlik (biri bo'lsa ham EXCEPTION — hech narsa o'chmaydi):
--   * id topilmadi; lid o'quvchiga aylantirilgan (converted / converted_at / student_id / students.converted_from_lead_id /
--     CONVERTED turidagi bosqich); to'lovi bor (leads.amount, summa talab qiladigan bosqichda bo'lgan yoki
--     "To'lov qabul qilindi" izohi). Shartnoma va to'lovlar faqat o'quvchiga bog'lanadi — konvertatsiya tekshiruvi qamraydi;
--   * pg_constraint da leads / tasks / calls ga FK bilan bog'langan, skript bilmaydigan jadval bor;
--   * bazada skript bilmaydigan *lead_id ustuni bor (FK siz havola).
--   Yangi jadval qo'shilsa — shu skriptdagi hd_steps va ruxsat ro'yxatini yangilang.
--
-- Talab: psql ≥ 10 (\if), baza V84+ (telegram_outbox.event_code, user_notifications.dedupe_key). calls / call_requests /
-- meta_webhook_events bo'lmasa — o'tkazib yuboriladi.
-- ═══════════════════════════════════════════════════════════════════════════════════

\set ON_ERROR_STOP on
\pset null '∅'

\if :{?ids}
\else
  \echo 'Foydalanish: psql ... -v ids=''1475,1476'' [-v confirm=yes] -f docs/ops/lead-hard-delete.sql'
  \quit
\endif

-- psql o'zgaruvchisi DO / funksiya ichida ochilmaydi — sessiya sozlamasiga (tranzaksiyadan tashqarida)
SELECT set_config('hd.ids', :'ids', false) AS ids;

-- ── 1. Yordamchi funksiyalar (pg_temp — faqat shu sessiyada, bazaga yozilmaydi) ─────

CREATE OR REPLACE FUNCTION pg_temp.hd_ids() RETURNS bigint[]
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(array_agg(DISTINCT btrim(x)::bigint ORDER BY btrim(x)::bigint), '{}')
    FROM unnest(string_to_array(current_setting('hd.ids'), ',')) AS x
    WHERE btrim(x) <> ''
$$;

CREATE OR REPLACE FUNCTION pg_temp.hd_task_ids(ids bigint[]) RETURNS bigint[]
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(array_agg(t.id ORDER BY t.id), '{}') FROM tasks t WHERE t.lead_id = ANY (ids)
$$;

-- Qadamlar: tartib (o'chirish ketma-ketligi), jadval, WHERE. PREVIEW va DELETE aynan shu ro'yxatdan foydalanadi.
CREATE OR REPLACE FUNCTION pg_temp.hd_steps(ids bigint[], tids bigint[])
RETURNS TABLE (tartib int, jadval text, shart text, izoh text)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    a_ids  text := quote_literal(ids::text) || '::bigint[]';
    a_tids text := quote_literal(tids::text) || '::bigint[]';
    -- link: "/leads?lead=ID" (boshqa parametrlar bilan ham); id dan keyin raqam emas — 147 1475 ni ushlamasin
    lead_re text := quote_literal('^/leads\?(.*&)?lead=(' || array_to_string(ids, '|') || ')([^0-9]|$)');
    task_re text := CASE WHEN cardinality(tids) = 0 THEN 'NULL'
                         ELSE quote_literal('^/tasks\?(.*&)?task=(' || array_to_string(tids, '|') || ')([^0-9]|$)') END;
    task_key text := CASE WHEN cardinality(tids) = 0 THEN 'NULL'
                          ELSE quote_literal('^task:(' || array_to_string(tids, '|') || '):') END;
BEGIN
    RETURN QUERY VALUES
      (10, 'user_notifications',
           '(lower(entity_type) = ''lead'' AND entity_id = ANY (' || a_ids || '))'
           || ' OR (lower(entity_type) = ''task'' AND entity_id = ANY (' || a_tids || '))'
           || ' OR link ~ ' || lead_re || ' OR link ~ ' || task_re || ' OR dedupe_key ~ ' || task_key,
           'qo''ng''iroqcha: entity / link / dedupe'),
      (20, 'telegram_outbox', 'dedupe_key ~ ' || task_key,
           'vazifa eslatmalari (dedupe task:ID:)'),
      (21, 'telegram_outbox',
           'event_code = ''LEAD_ASSIGNED'' AND EXISTS (SELECT 1 FROM leads l WHERE l.id = ANY (' || a_ids || ')'
           || ' AND char_length(l.full_name) >= 5 AND position(l.full_name IN telegram_outbox.text) > 0'
           || ' AND telegram_outbox.created_at >= l.created_at)',
           'LEAD_ASSIGNED, matnida lid ismi — TAXMINIY, ko''rib chiqing'),
      (30, 'audit_logs',
           '(entity_type = ''Lead'' AND entity_id = ANY (' || a_ids || '))'
           || ' OR (entity_type = ''Task'' AND entity_id = ANY (' || a_tids || '))',
           'audit: Lead va uning vazifalari'),
      (40, 'call_requests', 'lead_id = ANY (' || a_ids || ')', 'CRM''dan qo''ng''iroq so''rovlari (V90)'),
      (41, 'call_recordings', 'call_id IN (SELECT c.id FROM calls c WHERE c.lead_id = ANY (' || a_ids || '))',
           'yozuv metama''lumoti (fayllar — qo''lda!)'),
      (42, 'calls', 'lead_id = ANY (' || a_ids || ')', 'telefon qo''ng''iroqlari (V90)'),
      (50, 'meta_webhook_events', 'lead_id = ANY (' || a_ids || ')', 'Meta leadgen (FK yo''q)'),
      (60, 'lead_assignments', 'lead_id = ANY (' || a_ids || ')', 'tayinlash tarixi'),
      (61, 'lead_comments', 'lead_id = ANY (' || a_ids || ')', 'timeline izohlari'),
      (62, 'lead_notes', 'lead_id = ANY (' || a_ids || ')', 'eslatmalar'),
      (63, 'lead_status_history', 'lead_id = ANY (' || a_ids || ')', 'bosqich o''zgarishlari'),
      (70, 'tasks', 'lead_id = ANY (' || a_ids || ')', 'vazifalar'),
      (90, 'leads', 'id = ANY (' || a_ids || ')', 'lidlar');
END $$;

-- Xavfsizlik tekshiruvi — biror shart buzilsa EXCEPTION
CREATE OR REPLACE FUNCTION pg_temp.hd_check() RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    ids bigint[] := pg_temp.hd_ids();
    bad text;
BEGIN
    IF cardinality(ids) = 0 THEN
        RAISE EXCEPTION 'ids bo''sh';
    END IF;

    SELECT string_agg(x::text, ', ') INTO bad
    FROM unnest(ids) x WHERE NOT EXISTS (SELECT 1 FROM leads l WHERE l.id = x);
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'Lid(lar) topilmadi: %', bad;
    END IF;

    -- O'quvchiga aylantirilgan
    SELECT string_agg(l.id || ' (' || l.full_name || ')', ', ') INTO bad
    FROM leads l
    WHERE l.id = ANY (ids)
      AND (COALESCE(l.converted, false)
           OR l.converted_at IS NOT NULL
           OR l.student_id IS NOT NULL
           OR EXISTS (SELECT 1 FROM students s WHERE s.converted_from_lead_id = l.id)
           OR EXISTS (SELECT 1 FROM lead_stages st WHERE st.code = l.status AND upper(st.kind) = 'CONVERTED')
           OR EXISTS (SELECT 1 FROM lead_status_history h JOIN lead_stages st ON st.code = h.to_status
                      WHERE h.lead_id = l.id AND upper(st.kind) = 'CONVERTED'));
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'Lid o''quvchiga aylantirilgan — o''chirilmaydi: %', bad;
    END IF;

    -- To'lov
    SELECT string_agg(l.id || ' (' || l.full_name || ')', ', ') INTO bad
    FROM leads l
    WHERE l.id = ANY (ids)
      AND (l.amount IS NOT NULL
           OR EXISTS (SELECT 1 FROM lead_stages st WHERE st.code = l.status AND st.requires_amount)
           OR EXISTS (SELECT 1 FROM lead_status_history h JOIN lead_stages st ON st.code = h.to_status
                      WHERE h.lead_id = l.id AND st.requires_amount)
           OR EXISTS (SELECT 1 FROM lead_comments c WHERE c.lead_id = l.id AND c.text LIKE 'To''lov qabul qilindi%'));
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'Lidda to''lov bor — o''chirilmaydi: %', bad;
    END IF;

    -- Skript bilmaydigan FK (pg_constraint): leads / tasks / calls ga havola qiluvchi har bir ustun
    SELECT string_agg(DISTINCT format('%s.%s -> %s (%s)', c.conrelid::regclass, a.attname, c.confrelid::regclass,
                                      c.conname), '; ') INTO bad
    FROM pg_constraint c
    CROSS JOIN LATERAL unnest(c.conkey) AS k(attnum)
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
    WHERE c.contype = 'f'
      AND c.confrelid IN (SELECT to_regclass(t) FROM unnest(ARRAY['public.leads', 'public.tasks', 'public.calls']) t
                          WHERE to_regclass(t) IS NOT NULL)
      AND format('%s.%s', c.conrelid::regclass, a.attname) NOT IN (
            'lead_comments.lead_id', 'lead_notes.lead_id', 'lead_status_history.lead_id', 'lead_assignments.lead_id',
            'tasks.lead_id', 'students.converted_from_lead_id', 'calls.lead_id', 'call_requests.lead_id',
            'meta_webhook_events.lead_id', 'call_recordings.call_id');
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'Skript bilmaydigan FK bor — hd_steps ga qo''shing: %', bad;
    END IF;

    -- FK siz *lead_id ustunlari
    SELECT string_agg(format('%s.%s', table_name, column_name), ', ') INTO bad
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND (column_name LIKE '%lead\_id' OR column_name = 'task_id')
      AND format('%s.%s', table_name, column_name) NOT IN (
            'lead_comments.lead_id', 'lead_notes.lead_id', 'lead_status_history.lead_id', 'lead_assignments.lead_id',
            'tasks.lead_id', 'students.converted_from_lead_id', 'calls.lead_id', 'call_requests.lead_id',
            'meta_webhook_events.lead_id');
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'Skript bilmaydigan lead_id / task_id ustuni bor — hd_steps ga qo''shing: %', bad;
    END IF;

    RETURN format('OK: %s ta lid, %s ta vazifa', cardinality(ids), cardinality(pg_temp.hd_task_ids(ids)));
END $$;

-- Har qadam bo'yicha qatorlar soni (jadval bo'lmasa — o'tkaziladi)
CREATE OR REPLACE FUNCTION pg_temp.hd_preview()
RETURNS TABLE (tartib int, jadval text, qatorlar bigint, izoh text)
LANGUAGE plpgsql AS $$
DECLARE
    ids bigint[] := pg_temp.hd_ids();
    s record;
BEGIN
    FOR s IN SELECT * FROM pg_temp.hd_steps(ids, pg_temp.hd_task_ids(ids)) ORDER BY 1 LOOP
        tartib := s.tartib; jadval := s.jadval; izoh := s.izoh;
        IF to_regclass('public.' || s.jadval) IS NULL
           OR (s.jadval = 'call_recordings' AND to_regclass('public.calls') IS NULL) THEN
            qatorlar := NULL; izoh := s.izoh || ' — jadval yo''q, o''tkaziladi';
        ELSE
            EXECUTE format('SELECT count(*) FROM %I WHERE %s', s.jadval, s.shart) INTO qatorlar;
        END IF;
        RETURN NEXT;
    END LOOP;
END $$;

-- Yozuv fayllari yo'llari (calls jadvali bo'lmasa — bo'sh)
CREATE OR REPLACE FUNCTION pg_temp.hd_files() RETURNS TABLE (call_id bigint, path text)
LANGUAGE plpgsql AS $$
BEGIN
    IF to_regclass('public.calls') IS NULL OR to_regclass('public.call_recordings') IS NULL THEN
        RETURN;
    END IF;
    RETURN QUERY EXECUTE
        'SELECT r.call_id, r.path::text FROM call_recordings r JOIN calls c ON c.id = r.call_id
         WHERE c.lead_id = ANY ($1) AND r.path IS NOT NULL ORDER BY r.call_id'
        USING pg_temp.hd_ids();
END $$;

-- O'chirish — hd_steps tartibida; oxirida qoldiq tekshiruvi
CREATE OR REPLACE FUNCTION pg_temp.hd_delete()
RETURNS TABLE (tartib int, jadval text, ochirildi bigint)
LANGUAGE plpgsql AS $$
DECLARE
    ids bigint[] := pg_temp.hd_ids();
    tids bigint[] := pg_temp.hd_task_ids(ids);   -- vazifalar o'chishidan OLDIN olinadi
    s record;
    left_over bigint;
BEGIN
    FOR s IN SELECT * FROM pg_temp.hd_steps(ids, tids) ORDER BY 1 LOOP
        CONTINUE WHEN to_regclass('public.' || s.jadval) IS NULL
                   OR (s.jadval = 'call_recordings' AND to_regclass('public.calls') IS NULL);
        EXECUTE format('DELETE FROM %I WHERE %s', s.jadval, s.shart);
        GET DIAGNOSTICS ochirildi = ROW_COUNT;
        tartib := s.tartib; jadval := s.jadval;
        RETURN NEXT;
    END LOOP;

    -- Qoldiq: o'chirish shartlari bo'yicha hech narsa qolmagan bo'lishi kerak
    FOR s IN SELECT * FROM pg_temp.hd_steps(ids, tids) ORDER BY 1 LOOP
        CONTINUE WHEN to_regclass('public.' || s.jadval) IS NULL
                   OR (s.jadval = 'call_recordings' AND to_regclass('public.calls') IS NULL);
        EXECUTE format('SELECT count(*) FROM %I WHERE %s', s.jadval, s.shart) INTO left_over;
        IF left_over <> 0 THEN
            RAISE EXCEPTION 'O''chirishdan keyin %.% da % qator qoldi — ROLLBACK', s.jadval, s.tartib, left_over;
        END IF;
    END LOOP;
END $$;

-- ── 2. PREVIEW (READ ONLY, oxirida ROLLBACK) ─────────────────────────────────────────
\echo
\echo '════ PREVIEW — hech narsa o''zgarmaydi ════'
BEGIN TRANSACTION READ ONLY;

SELECT pg_temp.hd_check() AS xavfsizlik;

\echo '— O''chiriladigan lidlar:'
SELECT l.id, l.full_name, l.phone, l.status, l.source, l.created_at,
       (SELECT count(*) FROM tasks t WHERE t.lead_id = l.id) AS vazifalar
FROM leads l
WHERE l.id = ANY (pg_temp.hd_ids())
ORDER BY l.id;

\echo '— Har jadval bo''yicha o''chadigan qatorlar (∅ — jadval yo''q):'
SELECT * FROM pg_temp.hd_preview() ORDER BY tartib;

\echo '— Diskdan QO''LDA o''chiriladigan yozuv fayllari (app.calls.recording-dir ga nisbatan):'
SELECT * FROM pg_temp.hd_files();

ROLLBACK;

-- ── 3. O'CHIRISH (faqat -v confirm=yes) ──────────────────────────────────────────────
\if :{?confirm}
\if :confirm
\echo
\echo '════ O''CHIRISH ════'
BEGIN;

-- Tekshiruv qayta (PREVIEW dan beri ma'lumot o'zgargan bo'lishi mumkin); lidlarni qulflash
SELECT pg_temp.hd_check() AS xavfsizlik;
SELECT id AS qulflandi FROM leads WHERE id = ANY (pg_temp.hd_ids()) ORDER BY id FOR UPDATE;

SELECT * FROM pg_temp.hd_delete() ORDER BY tartib;

-- Yakuniy tekshiruv: lidlar soni 0 (aks holda EXCEPTION → ON_ERROR_STOP, tranzaksiya qo'llanmaydi)
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM leads WHERE id = ANY (pg_temp.hd_ids());
    IF n <> 0 THEN
        RAISE EXCEPTION 'Lidlar o''chmadi: % ta qoldi', n;
    END IF;
END $$;
SELECT count(*) AS qolgan_lidlar FROM leads WHERE id = ANY (pg_temp.hd_ids());

COMMIT;
\echo 'Tayyor. Yozuv fayllarini (PREVIEW ro''yxati) diskdan o''chirishni unutmang.'
\else
\echo
\echo 'confirm=yes emas — o''chirilmadi.'
\endif
\else
\echo
\echo 'PREVIEW tugadi. O''chirish uchun: ... -v ids=''<id lar>'' -v confirm=yes -f docs/ops/lead-hard-delete.sql'
\endif

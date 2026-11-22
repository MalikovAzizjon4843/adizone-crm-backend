-- ═══════════════════════════════════════════════════════════════════════════════════
-- Sinov boshlanishi (student_groups.trial_started_at) backfill — dashboard "Sinov → to'lov".
-- Ixtiyoriy: TrialMetricsService endi bo'sh trial_started_at ni o'zi davomat / join_date dan aniqlaydi. Skript shu
-- qoidani bazaga yozadi (drill-down, eksport va keyingi hisoblar bir xil bo'lsin) va noto'g'ri NO_SHOW larni tuzatadi.
--
--   1) PREVIEW (hech narsa o'zgarmaydi):
--        psql -h <host> -U crm_user -d adizone -X -f docs/ops/trial-start-backfill.sql
--   2) APPLY:
--        psql -h <host> -U crm_user -d adizone -X -v confirm=yes -f docs/ops/trial-start-backfill.sql
--
-- Qoida (TrialMetricsService.resolve bilan AYNAN bir xil), faqat trial_started_at IS NULL bo'lgan sinov
-- yozilmalari (trial_outcome IS NOT NULL OR is_trial):
--   conv  = LEAST(trial_converted_at, join_date dan keyingi birinchi PAID to'lov (COALESCE(cash_amount, amount) > 0))
--   start = birinchi PRESENT/LATE dars ∈ [join_date, conv]  → trial_source o'zgarmaydi (aniq);
--           yo'q bo'lsa va conv bor → LEAST(join_date, conv), trial_source = 'BACKFILL' (taxminiy belgisi);
--           ikkalasi yo'q → tegilmaydi (yozilib kelmagan).
--   trial_outcome = 'NO_SHOW' va start topildi → 'LEFT' (TrialTracking.markClosed: kelgan bo'lsa LEFT).
-- Mavjud trial_started_at ga TEGILMAYDI. Idempotent.
-- ═══════════════════════════════════════════════════════════════════════════════════

\set ON_ERROR_STOP on

CREATE TEMP TABLE tsb AS
WITH t AS (
    SELECT sg.id, sg.student_id, sg.group_id, sg.join_date, sg.trial_outcome, sg.trial_source,
           LEAST(sg.trial_converted_at,
                 (SELECT min(p.payment_date) FROM payments p
                   WHERE p.student_group_id = sg.id AND p.status = 'PAID'
                     AND COALESCE(p.cash_amount, p.amount) > 0
                     AND (sg.join_date IS NULL OR p.payment_date >= sg.join_date))) AS conv
    FROM student_groups sg
    WHERE (sg.trial_outcome IS NOT NULL OR sg.is_trial = TRUE) AND sg.trial_started_at IS NULL
), a AS (
    SELECT t.*,
           (SELECT min(x.attendance_date) FROM attendance x
             WHERE x.student_id = t.student_id AND x.group_id = t.group_id
               AND x.status IN ('PRESENT', 'LATE')
               AND (t.join_date IS NULL OR x.attendance_date >= t.join_date)
               AND (t.conv IS NULL OR x.attendance_date <= t.conv)) AS first_att
    FROM t
)
SELECT id, join_date, conv, first_att, trial_outcome, trial_source,
       COALESCE(first_att, CASE WHEN conv IS NOT NULL THEN LEAST(COALESCE(join_date, conv), conv) END) AS new_start,
       first_att IS NULL AND conv IS NOT NULL AS estimated
FROM a;

\echo '════ PREVIEW ════'
SELECT count(*)                                              AS bosh_trial_started_at,
       count(*) FILTER (WHERE first_att IS NOT NULL)         AS darsdan_aniq,
       count(*) FILTER (WHERE estimated)                     AS join_date_taxminiy,
       count(*) FILTER (WHERE new_start IS NULL)             AS tegilmaydi_kelmagan,
       count(*) FILTER (WHERE new_start IS NOT NULL AND trial_outcome = 'NO_SHOW') AS no_show_dan_left
FROM tsb;

\echo '— Namuna (50 ta):'
SELECT id, join_date, conv, first_att, new_start, estimated, trial_outcome
FROM tsb WHERE new_start IS NOT NULL ORDER BY id DESC LIMIT 50;

\if :{?confirm}
\if :confirm
\echo '════ APPLY ════'
BEGIN;
UPDATE student_groups sg
SET trial_started_at = b.new_start,
    trial_source     = CASE WHEN b.estimated THEN 'BACKFILL' ELSE COALESCE(sg.trial_source, 'LIVE') END,
    trial_outcome    = CASE WHEN sg.trial_outcome = 'NO_SHOW' THEN 'LEFT' ELSE sg.trial_outcome END
FROM tsb b
WHERE sg.id = b.id AND b.new_start IS NOT NULL AND sg.trial_started_at IS NULL;

SELECT count(*) AS qolgan_tiklanadigan
FROM tsb b JOIN student_groups sg ON sg.id = b.id
WHERE b.new_start IS NOT NULL AND sg.trial_started_at IS NULL;
COMMIT;
\echo 'Tayyor.'
\else
\echo 'confirm=yes emas — o''zgartirilmadi.'
\endif
\else
\echo 'PREVIEW tugadi. Qo''llash: ... -v confirm=yes -f docs/ops/trial-start-backfill.sql'
\endif

-- ═══════════════════════════════════════════════════════════════════════════════════
-- Dashboard "Sinov → to'lov" bloki 0 ko'rsatadi — prod'da diagnoz (FAQAT O'QIYDI).
--   psql -h <host> -U crm_user -d adizone -X -f docs/ops/trial-dashboard-diag.sql
--
-- Eski hisob (TrialMetricsService, tuzatishdan oldin): kogorta = student_groups.trial_started_at ∈ davr.
-- trial_started_at faqat AttendanceSignals → TrialTracking.markAttended da, davomat PRESENT/LATE belgilanganda VA
-- o'sha paytda is_trial = true bo'lsa yoziladi. To'lov darsdan oldin bo'lsa (PaymentBookingService is_trial=false
-- qiladi) yoki sinov darsi belgilanmasa — NULL qoladi → kogorta 0, barcha bucket va konversiya 0.
-- ═══════════════════════════════════════════════════════════════════════════════════

BEGIN TRANSACTION READ ONLY;

-- 1. Sinov yozilmalari (oxirgi 90 kun, join_date bo'yicha): qaysi maydon to'ldirilgan
SELECT count(*)                                                        AS sinov_yozilmalari,
       count(*) FILTER (WHERE is_trial)                                AS hozir_sinovda,
       count(*) FILTER (WHERE trial_outcome IS NOT NULL)               AS trial_outcome_bor,
       count(*) FILTER (WHERE trial_started_at IS NOT NULL)            AS trial_started_at_bor,
       count(*) FILTER (WHERE trial_converted_at IS NOT NULL)          AS trial_converted_at_bor,
       count(*) FILTER (WHERE first_lesson_date IS NOT NULL)           AS first_lesson_date_bor,
       count(*) FILTER (WHERE trial_source = 'BACKFILL')               AS backfill
FROM student_groups
WHERE (trial_outcome IS NOT NULL OR is_trial)
  AND join_date >= current_date - 90;

-- 2. trial_outcome taqsimoti
SELECT trial_outcome, trial_source, is_trial, (trial_started_at IS NOT NULL) AS started, count(*)
FROM student_groups
WHERE (trial_outcome IS NOT NULL OR is_trial) AND join_date >= current_date - 90
GROUP BY 1, 2, 3, 4 ORDER BY 5 DESC;

-- 3. trial_started_at bo'sh bo'lgan sabablar: davomat to'lovdan keyin / umuman yo'q
WITH t AS (
    SELECT sg.id, sg.student_id, sg.group_id, sg.join_date, sg.trial_converted_at,
           (SELECT min(p.payment_date) FROM payments p
             WHERE p.student_group_id = sg.id AND p.status = 'PAID'
               AND COALESCE(p.cash_amount, p.amount) > 0 AND p.payment_date >= sg.join_date) AS first_paid,
           (SELECT min(a.attendance_date) FROM attendance a
             WHERE a.student_id = sg.student_id AND a.group_id = sg.group_id
               AND a.status IN ('PRESENT', 'LATE') AND a.attendance_date >= sg.join_date) AS first_att
    FROM student_groups sg
    WHERE (sg.trial_outcome IS NOT NULL OR sg.is_trial) AND sg.trial_started_at IS NULL
      AND sg.join_date >= current_date - 90
)
SELECT count(*)                                                                         AS started_null,
       count(*) FILTER (WHERE first_att IS NOT NULL
                          AND first_att <= COALESCE(LEAST(trial_converted_at, first_paid), first_att)) AS darsi_bor_tiklanadi,
       count(*) FILTER (WHERE first_att IS NULL AND LEAST(trial_converted_at, first_paid) IS NOT NULL)  AS tolagan_darssiz_join_date,
       count(*) FILTER (WHERE first_att IS NULL AND LEAST(trial_converted_at, first_paid) IS NULL)      AS kelmagan
FROM t;

-- 4. Sinov yozilmalaridagi PAID to'lovlarda cash_amount NULL (eski hisob ularni ham ko'rmasdi)
SELECT count(*) AS cash_amount_null
FROM payments p JOIN student_groups sg ON sg.id = p.student_group_id
WHERE (sg.trial_outcome IS NOT NULL OR sg.is_trial) AND p.status = 'PAID' AND p.cash_amount IS NULL;

ROLLBACK;

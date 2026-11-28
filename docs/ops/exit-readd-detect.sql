-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- Qo'lda ko'chirishlarni aniqlash va tasniflash (FAQAT O'QISH).
--
-- Muammo: admin o'quvchini "Guruhga ko'chirish" (POST /api/students/{id}/transfer-group) o'rniga QO'LDA
-- o'tkazgan — avval yangi guruhga qo'shgan (POST /api/groups/students), keyin eskisidan TRANSFERRED sabab
-- bilan chiqargan (POST /api/groups/{g}/remove-student). Eski guruhda to'langan davr qoladi, yangi guruhda
-- esa o'sha kunlar uchun yangi oylik davr hisoblanadi → o'quvchi "qarzdor".
--
-- Java'dagi ko'rinishi: GET /api/admin/repair/manual-transfers?from=&to= (ManualTransferDetectionService) —
-- qoidalar AYNAN shu. Ta'mir: POST /api/admin/repair/merge-into-transfer (dryRun standart true).
--
-- Juftlik (o'quvchi bo'yicha vaqt tartibida):
--   O — TRANSFERRED sabab bilan yopilgan, muzlatilmagan yozilma; chiqish sanasi = COALESCE(exit_date, leave_date)
--       [from, to] oralig'ida.
--   N — o'sha o'quvchi, N.id > O.id, |N.join_date − O.exit| ≤ 7 kun.
--   Zanjir A→B→C: O nomzodlaridan keyinroq yaratilgan (id kattaroq) boshqa TRANSFERRED chiqishning nomzodlari
--   chiqariladi (A → {B}, B → {C}). Bir nechta nomzod qolsa — MULTIPLE_CANDIDATES; hech biri qolmasa —
--   NEW_SHARED (barcha nomzodlar).
--
-- Transfer qoidasi (EnrollmentLifecycleService.transfer / continuedAnchor): eski yozilma davrlari o'z joyida
-- qoladi (proratsiya yo'q), yangi yozilma eski oxirgi davr oxiri + 1 kundan (cont_anchor) hisoblanadi, eski
-- balans TRANSFER_OUT/IN bilan to'liq ko'chadi. Shunga ko'ra:
--   refund_total — N ning cont_anchor dan OLDIN boshlangan CHARGED/PARTIALLY_REFUNDED davrlari (ikki marta hisob);
--   old_balance  — O da qolgan ledger balansi (ko'chishi kerak edi);
--   unused       — max(0, refund_total + old_balance): N ga o'tadigan kredit.
--
-- Tasnif (tartib muhim):
--   TRANSFER_OK — ta'mir bog'lanishi (enrollment_transfer_links) YOKI ustma-ust davr yo'q va (TRANSFER_OUT/IN
--                 juftligi bor yoki O balansi 0 va N langari = cont_anchor);
--   UNCLEAR     — nomzod noaniq (MULTIPLE_CANDIDATES / NEW_SHARED), zanjir (CHAIN), sinov yozilmasi (TRIAL);
--   NO_LOSS     — unused = 0 (eski davr to'lanmagan: NO_LOSS_UNPAID; yoki ustma-ust hisob yo'q);
--   UNCLEAR     — PER_LESSON, N muzlatilgan (NEW_FROZEN), billing hold, yoki N da cont_anchor
--                 dan keyin boshlangan davr ham bor (REFUND_ONLY — langarni surib bo'lmaydi);
--   AFFECTED    — qolgani: ta'mir kerak, summa = unused.
--
-- Ishlatish:  psql -X -v from=2026-10-01 -v to=2026-11-28 -f exit-readd-detect.sql
--   (from/to berilmasa — quyidagi params dagi standart: oxirgi 60 kun.)
-- DIQQAT: V93 (enrollment_transfer_links) hali bajarilmagan bo'lsa, "repair_link" qatoridagi EXISTS(...) ni
--   FALSE bilan almashtiring.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════

\if :{?from}
\else
  \set from ''
\endif
\if :{?to}
\else
  \set to ''
\endif

WITH RECURSIVE
params AS (
    SELECT COALESCE(NULLIF(:'to', '')::date, CURRENT_DATE) AS d_to,
           COALESCE(NULLIF(:'from', '')::date, COALESCE(NULLIF(:'to', '')::date, CURRENT_DATE) - 60) AS d_from,
           7 AS win
),
cand_students AS (
    SELECT DISTINCT x.student_id
    FROM student_groups x, params p
    WHERE COALESCE(x.is_active, FALSE) = FALSE AND x.frozen_from IS NULL
      AND (x.exit_reason_code = 'TRANSFERRED' OR upper(trim(x.exit_reason)) = 'TRANSFERRED')
      AND COALESCE(x.exit_date, x.leave_date) BETWEEN p.d_from AND p.d_to
),
sg AS (
    SELECT s.*, COALESCE(s.exit_date, s.leave_date) AS exit_d
    FROM student_groups s
    WHERE s.student_id IN (SELECT student_id FROM cand_students)
),
exits AS (
    SELECT * FROM sg
    WHERE COALESCE(is_active, FALSE) = FALSE AND frozen_from IS NULL AND exit_d IS NOT NULL
      AND (exit_reason_code = 'TRANSFERRED' OR upper(trim(exit_reason)) = 'TRANSFERRED')
),
raw AS (
    SELECT o.student_id, o.id AS old_id, n.id AS new_id
    FROM exits o
    JOIN sg n ON n.student_id = o.student_id AND n.id > o.id AND n.join_date IS NOT NULL
             AND abs(n.join_date - o.exit_d) <= (SELECT win FROM params)
),
own AS (   -- keyingi (id kattaroq) chiqishning nomzodi bo'lgan yozilma O ga tegishli emas
    SELECT r.* FROM raw r
    WHERE NOT EXISTS (SELECT 1 FROM raw r2
                      WHERE r2.student_id = r.student_id AND r2.old_id > r.old_id AND r2.new_id = r.new_id)
),
found AS (
    SELECT o.student_id, o.old_id, o.new_id,
           CASE WHEN count(*) OVER (PARTITION BY o.old_id) > 1 THEN 'MULTIPLE_CANDIDATES' END AS structural
    FROM own o
    UNION ALL
    SELECT r.student_id, r.old_id, r.new_id, 'NEW_SHARED'
    FROM raw r
    WHERE NOT EXISTS (SELECT 1 FROM own o WHERE o.old_id = r.old_id)
),
plain AS (SELECT * FROM found WHERE structural IS NULL),
up AS (    -- zanjir boshi (root): oldingi juftligi yo'q juftlikdan yangi → eski bo'ylab
    SELECT p.old_id, p.new_id, p.old_id AS root
    FROM plain p
    WHERE NOT EXISTS (SELECT 1 FROM plain q WHERE q.new_id = p.old_id)
    UNION ALL
    SELECT p.old_id, p.new_id, up.root
    FROM plain p JOIN up ON p.old_id = up.new_id
),
pairs AS (
    SELECT f.*,
           CASE WHEN f.structural IS NULL
                 AND (EXISTS (SELECT 1 FROM plain q WHERE q.new_id = f.old_id)
                      OR EXISTS (SELECT 1 FROM plain q WHERE q.old_id = f.new_id))
                THEN f.student_id || ':' || (SELECT u.root FROM up u WHERE u.old_id = f.old_id AND u.new_id = f.new_id LIMIT 1)
           END AS chain_key
    FROM found f
),
calc AS (
    SELECT pr.*, o.exit_d AS old_exit, o.join_date AS old_join, o.group_id AS old_group_id,
           n.group_id AS new_group_id, n.join_date AS new_join, n.payment_start_date AS new_anchor,
           COALESCE(n.is_active, FALSE) AS new_active,
           o.is_trial AS old_trial, n.is_trial AS new_trial,
           COALESCE(o.payment_type, 'MONTHLY') AS old_type, COALESCE(n.payment_type, 'MONTHLY') AS new_type,
           n.frozen_from AS new_frozen, n.billing_hold AS new_hold,
           COALESCE(
               CASE WHEN COALESCE(o.payment_type, 'MONTHLY') <> 'PER_LESSON' AND NOT COALESCE(o.is_trial, FALSE) THEN
                   (SELECT bp.period_end + 1 FROM billing_periods bp
                    WHERE bp.student_group_id = o.id
                      AND (o.payment_start_date IS NULL OR bp.period_start >= o.payment_start_date)
                    ORDER BY bp.period_start DESC LIMIT 1)
               END,
               GREATEST(o.payment_start_date, o.exit_d)
           ) AS cont_anchor,
           (SELECT COALESCE(sum(t.amount), 0) FROM balance_transactions t WHERE t.student_group_id = o.id) AS old_balance,
           COALESCE(n.balance, 0) AS new_balance,
           (EXISTS (SELECT 1 FROM balance_transactions t
                    WHERE t.student_group_id = o.id AND t.type = 'TRANSFER_OUT' AND t.reference_id = n.id)
            OR EXISTS (SELECT 1 FROM balance_transactions t
                       WHERE t.student_group_id = n.id AND t.type = 'TRANSFER_IN' AND t.reference_id = o.id)) AS ledger_link,
           EXISTS (SELECT 1 FROM enrollment_transfer_links l
                   WHERE l.old_student_group_id = o.id AND l.new_student_group_id = n.id) AS repair_link
    FROM pairs pr
    JOIN sg o ON o.id = pr.old_id
    JOIN sg n ON n.id = pr.new_id
    WHERE o.exit_d BETWEEN (SELECT d_from FROM params) AND (SELECT d_to FROM params)
),
plan AS (
    SELECT c.*,
           (SELECT COALESCE(sum(CASE WHEN bp.charge_tx_id IS NOT NULL
                                     THEN GREATEST(bp.amount - COALESCE(bp.refunded_amount, 0), 0) ELSE 0 END), 0)
            FROM billing_periods bp
            WHERE bp.student_group_id = c.new_id AND bp.period_start < c.cont_anchor
              AND bp.status IN ('CHARGED', 'PARTIALLY_REFUNDED')) AS refund_total,
           (SELECT min(bp.period_start) FROM billing_periods bp
            WHERE bp.student_group_id = c.new_id AND bp.period_start >= c.cont_anchor) AS later_start
    FROM calc c
),
classified AS (
    SELECT p.*,
           p.refund_total + p.old_balance AS credit,
           CASE
               WHEN p.repair_link THEN 'REPAIRED'
               WHEN p.refund_total = 0 AND (p.ledger_link OR (p.old_balance = 0 AND p.new_anchor = p.cont_anchor))
                   THEN 'TRANSFER_FLOW'
               WHEN p.structural IS NOT NULL THEN p.structural
               WHEN p.chain_key IS NOT NULL THEN 'CHAIN'
               WHEN COALESCE(p.old_trial, FALSE) OR COALESCE(p.new_trial, FALSE) THEN 'TRIAL'
               WHEN p.refund_total + p.old_balance <= 0
                   THEN CASE WHEN p.refund_total > 0 THEN 'NO_LOSS_UNPAID' ELSE 'NO_LOSS' END
               WHEN p.old_type = 'PER_LESSON' OR p.new_type = 'PER_LESSON' THEN 'PER_LESSON'
               WHEN p.new_frozen IS NOT NULL THEN 'NEW_FROZEN'
               WHEN COALESCE(p.new_hold, FALSE) THEN 'BILLING_HOLD'
               WHEN p.later_start IS NOT NULL THEN 'REFUND_ONLY'
               ELSE 'AFFECTED'
           END AS reason_code
    FROM plan p
),
final AS (
    SELECT c.*,
           CASE
               WHEN c.reason_code IN ('REPAIRED', 'TRANSFER_FLOW') THEN 'TRANSFER_OK'
               WHEN c.reason_code IN ('NO_LOSS', 'NO_LOSS_UNPAID') THEN 'NO_LOSS'
               WHEN c.reason_code = 'AFFECTED' THEN 'AFFECTED'
               ELSE 'UNCLEAR'
           END AS classification,
           GREATEST(c.credit, 0) AS unused
    FROM classified c
)
SELECT f.student_id,
       st.admission_number,
       trim(COALESCE(st.first_name, '') || ' ' || COALESCE(st.last_name, '')) AS student_name,
       f.old_id                       AS old_enrollment_id,
       og.group_name                  AS old_group,
       f.old_join,
       f.old_exit,
       f.new_id                       AS new_enrollment_id,
       ng.group_name                  AS new_group,
       f.new_join,
       f.new_anchor                   AS new_payment_start,
       f.new_active,
       f.cont_anchor                  AS continued_anchor,
       CASE WHEN f.later_start IS NULL THEN 'REANCHOR' ELSE 'REFUND_ONLY' END AS mode,
       f.refund_total,
       f.old_balance,
       f.unused                       AS unused_amount,
       f.new_balance,
       (SELECT COALESCE(sum(GREATEST(-x.balance, 0)), 0) FROM student_groups x
        WHERE x.student_id = f.student_id AND x.is_active = TRUE) AS current_debt,
       f.classification,
       f.reason_code,
       f.chain_key,
       count(*) FILTER (WHERE f.classification = 'AFFECTED') OVER ()             AS affected_count,
       COALESCE(sum(f.unused) FILTER (WHERE f.classification = 'AFFECTED') OVER (), 0) AS affected_total
FROM final f
JOIN students st ON st.id = f.student_id
LEFT JOIN groups og ON og.id = f.old_group_id
LEFT JOIN groups ng ON ng.id = f.new_group_id
ORDER BY f.student_id, f.old_id, f.new_id;

-- held-review.sql — FAQAT O'QIYDI (SELECT). docs/ops/held-review.md §2.
-- Oxirgi qo'llangan migratsiya run'i bulk yozgan har SG: apply paytidagi balans (target_old) va sof hisob
-- (target_net = sof to'lovlar + boshqa yozuvlar − davrlar × fee, billing-v2 §9.7.1). delta < 0 — ortiqcha qarz.
-- Boshqa run uchun: run CTE dagi WHERE ni "WHERE id = <run_id>" ga almashtiring.
WITH run AS (
    SELECT id, applied_at, max_tx_id, max_payment_id
    FROM billing_migration_runs
    WHERE applied_at IS NOT NULL
    ORDER BY id DESC
    LIMIT 1
),
sgs AS (
    -- run yozgan SG lar; revert-sg qilinganlari (run yozuviga REVERSAL bor) chiqariladi
    SELECT x.sg_id
    FROM (
        SELECT bp.student_group_id AS sg_id FROM billing_periods bp JOIN run ON bp.migration_run_id = run.id
        UNION
        SELECT bt.student_group_id FROM balance_transactions bt JOIN run ON bt.migration_run_id = run.id
    ) x
    WHERE NOT EXISTS (
        SELECT 1
        FROM balance_transactions o
        JOIN balance_transactions r ON r.related_tx_id = o.id AND r.type = 'REVERSAL'
        JOIN run ON o.migration_run_id = run.id
        WHERE o.student_group_id = x.sg_id
    )
),
periods AS (
    SELECT bp.student_group_id AS sg_id,
           MAX(bp.fee) AS fee,
           COUNT(*) FILTER (WHERE bp.status <> 'MIGRATED') AS n_charged,
           COALESCE(SUM(bp.amount) FILTER (WHERE bp.status <> 'MIGRATED'), 0) AS charges_old,
           COALESCE(SUM(bp.fee) FILTER (WHERE bp.status <> 'MIGRATED'), 0) AS charges_net,
           COUNT(*) FILTER (WHERE bp.status <> 'MIGRATED' AND bp.amount <> bp.fee) AS a14_periods,
           COALESCE(MAX(bp.amount - bp.fee) FILTER (WHERE bp.status <> 'MIGRATED'), 0) AS max_over_fee
    FROM billing_periods bp
    JOIN run ON bp.migration_run_id = run.id
    GROUP BY bp.student_group_id
),
pays AS (
    -- dry-run paytidagi to'lovlar (id <= max_payment_id), hozir ham PAID
    SELECT p.student_group_id AS sg_id,
           COUNT(*) AS n_payments,
           SUM(p.amount) AS gross,
           SUM(COALESCE(p.discount_amount, 0)) AS discount,
           SUM(p.amount - COALESCE(p.balance_used, 0)) AS paid_net
    FROM payments p
    JOIN run ON p.id <= run.max_payment_id
    WHERE p.status = 'PAID'
    GROUP BY p.student_group_id
),
ledger AS (
    -- apply paytidagi ledger: dry-run'gacha bo'lgan yozuvlar + run'ning o'z yozuvlari
    SELECT bt.student_group_id AS sg_id,
           SUM(bt.amount) AS target_old,
           COALESCE(SUM(bt.amount) FILTER (WHERE bt.migration_run_id IS NULL), 0) AS l_before,
           COALESCE(SUM(-bt.amount) FILTER (WHERE bt.type = 'PERIOD_CHARGE' AND bt.billing_period_id IS NULL
               AND bt.migration_run_id IS NULL), 0) AS legacy_pc,
           COALESCE(SUM(bt.amount) FILTER (WHERE bt.type IN ('PAYMENT', 'DISCOUNT')
               AND bt.migration_run_id IS NULL), 0) AS ledger_credits,
           COALESCE(SUM(bt.amount) FILTER (WHERE bt.type = 'MIGRATION' AND bt.migration_run_id IS NOT NULL), 0)
               AS migration_amount,
           COALESCE(SUM(bt.amount) FILTER (WHERE bt.migration_run_id IS NULL
               AND (bt.type IN ('BONUS', 'PENALTY', 'REFUND_PAYOUT', 'TRANSFER_IN', 'TRANSFER_OUT')
                    OR (bt.type = 'MANUAL_ADJUST' AND COALESCE(bt.note, '') NOT LIKE '[ledger-repair]%'))), 0) AS kept,
           MIN(bt.created_at) FILTER (WHERE bt.migration_run_id IS NOT NULL) AS run_rows_at
    FROM balance_transactions bt
    JOIN run ON bt.id <= run.max_tx_id OR bt.migration_run_id = run.id
    WHERE bt.student_group_id IN (SELECT sg_id FROM sgs)
    GROUP BY bt.student_group_id
),
calc AS (
    SELECT s.sg_id,
           sg.student_id,
           st.first_name || ' ' || st.last_name AS student_name,
           g.group_name,
           sg.payment_start_date AS anchor,
           pr.fee,
           COALESCE(pr.n_charged, 0) AS n_charged,
           COALESCE(pr.charges_old, 0) AS charges_old,
           COALESCE(pr.charges_net, 0) AS charges_net,
           COALESCE(pr.a14_periods, 0) AS a14_periods,
           COALESCE(pr.max_over_fee, 0) AS max_over_fee,
           COALESCE(py.n_payments, 0) AS n_payments,
           COALESCE(py.gross, 0) AS payments_gross,
           COALESCE(py.discount, 0) AS payments_discount,
           COALESCE(py.paid_net, 0) AS paid_net,
           COALESCE(l.l_before, 0) AS l_before,
           COALESCE(l.legacy_pc, 0) AS legacy_pc,
           COALESCE(l.ledger_credits, 0) AS ledger_credits,
           COALESCE(l.migration_amount, 0) AS migration_amount,
           COALESCE(l.kept, 0) AS kept,
           COALESCE(l.target_old, 0) AS target_old,
           COALESCE(py.paid_net, 0) + COALESCE(l.kept, 0) - COALESCE(pr.charges_net, 0) AS target_net,
           l.run_rows_at > run.applied_at AS late_apply_sg,
           sg.balance AS balance_now
    FROM sgs s
    CROSS JOIN run
    JOIN student_groups sg ON sg.id = s.sg_id
    JOIN students st ON st.id = sg.student_id
    LEFT JOIN groups g ON g.id = sg.group_id
    LEFT JOIN periods pr ON pr.sg_id = s.sg_id
    LEFT JOIN pays py ON py.sg_id = s.sg_id
    LEFT JOIN ledger l ON l.sg_id = s.sg_id
)
SELECT c.*,
       c.target_old - c.target_net AS delta,
       CASE
           WHEN c.target_old = c.target_net THEN 'OK'
           WHEN c.max_over_fee > 0 THEN 'A14_MULTI'
           WHEN c.payments_discount > 0 AND c.charges_old > c.charges_net - c.payments_discount
               AND c.target_old < c.target_net THEN 'DISCOUNT_UNCREDITED'
           WHEN c.ledger_credits <> c.paid_net THEN 'LEDGER_CREDITS_MISMATCH'
           ELSE 'OTHER'
       END AS reason
FROM calc c
ORDER BY c.target_old - c.target_net, c.sg_id

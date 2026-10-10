-- diag-billing.sql — FAQAT O'QIYDI (faqat SELECT). Qarzdorlar / kutilayotgan to'lovlar ro'yxatidan o'quvchi nega
-- tushib qolganini va "Keyingi to'lov: —" sababini ko'rsatadi. Hech narsa yozmaydi, vaqtinchalik obyekt yaratmaydi.
--
-- Ishga tushirish (prod, read-only):
--   PGOPTIONS='-c default_transaction_read_only=on' psql "$PROD_RO_URL" -X -v ON_ERROR_STOP=1 \
--       -v q='0-000043' -f docs/ops/diag-billing.sql
--
-- O'zgaruvchilar (hammasi ixtiyoriy):
--   q     — o'quvchi raqami (admission_number, masalan 0-000043), students.id yoki telefon (oxirgi 9 raqam bo'yicha).
--           Bo'sh — 1-bo'lim o'tkazib yuboriladi, faqat umumiy taqsimot (2-bo'lim) va "tushib qolganlar" ro'yxati.
--   today — hisob kuni (standart: hozirgi Toshkent kuni). Shikoyat kunini takrorlash uchun: -v today=2026-10-07.
--           Eslatma: saqlangan snapshot (balance, debt_since, next_payment_date) bugungi holatda — today faqat
--           ro'yxat shartlarini (debt_since ≤ today, oraliq) o'zgartiradi.
--   from, to — kutilayotganlar oralig'i. Standart: ertadan OY OXIRIGACHA (buyurtmachi ko'rgan 08.10–31.10 kabi).
--           API standarti boshqacha: ertadan bugun + 30 gacha (DebtorService.java:204-205).
--
-- Kod bilan moslik (billing-v2):
--   qarzdor     = sg.balance < 0 AND sg.debt_since IS NOT NULL AND debt_since ≤ today (grace 0)
--                 AND SG ochiq (is_active OR frozen_from) AND students.status IN (ACTIVE, FROZEN)
--                 — DebtorService.java:117-154, StudentGroupRepository.java:275, BillingStatusService.java:67-73, 106-108
--   kutilayotgan = is_active, muzlatilmagan, sinov emas, guruh ACTIVE/FORMING (NULL ham), students.status = ACTIVE
--                 (StudentGroupRepository.java:26-36) va BillingStatusService.upcoming (256-285) sanasi > today, oraliqda.
--                 MONTHLY sana: davr zanjiri (BillingCalendar.firstUnbilledStart) + k = floor(balance / c) oldindan
--                 qoplangan davr; qarzdorda — bugundan keyingi birinchi yozilmagan davr. R3: sana ≥ guruh end_date — yo'q.
--   PER_LESSON kutilayotgan sanasi (jadval bo'yicha dars) bu skriptda hisoblanmaydi — alohida "PER_LESSON" toifasi.

\if :{?q}
\else
  \set q ''
\endif
\if :{?today}
\else
  \set today ''
\endif
\if :{?from}
\else
  \set from ''
\endif
\if :{?to}
\else
  \set to ''
\endif

SELECT (:'q' <> '') AS has_q,
       COALESCE((SELECT string_agg(s.id::text, ',' ORDER BY s.id)
                   FROM students s
                  WHERE :'q' <> ''
                    AND (s.admission_number = :'q'
                         OR s.id::text = :'q'
                         OR (length(regexp_replace(:'q', '\D', '', 'g')) >= 9
                             AND regexp_replace(COALESCE(s.phone, ''), '\D', '', 'g')
                                 LIKE '%' || right(regexp_replace(:'q', '\D', '', 'g'), 9)))), '-1') AS qids
\gset

-- ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
-- 1. BITTA O'QUVCHI (-v q=...)
-- ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
\if :has_q

\echo '== 1.1 O''quvchi (karta snapshot''i: students jadvali)'
SELECT s.id, s.admission_number AS raqam, trim(s.first_name || ' ' || s.last_name) AS oquvchi, s.phone,
       s.status, s.payment_status, s.balance, s.debt, s.monthly_fee,
       s.admission_date, s.payment_start_date AS st_payment_start, s.next_payment_date, s.next_payment_amount,
       s.created_at,
       (SELECT COALESCE(sum(sg.balance), 0) FROM student_groups sg WHERE sg.student_id = s.id) AS sum_sg_balance
  FROM students s
 WHERE s.id IN (:qids)
 ORDER BY s.id;

\echo '== 1.2 Migratsiya: yozilma qaysi run''da qanday holatda (excluded / apply xatosi / run yozuvlari)'
SELECT sg.id AS sg_id, sg.billing_hold, r.id AS run_id, r.status AS run_status, r.cutover_date, r.go_live_date,
       r.applied_at,
       sg.id::text = ANY (string_to_array(COALESCE(r.excluded_sg_ids, ''), ',')) AS excluded,
       position('sg=' || sg.id || ':' IN COALESCE(r.errors, '')) > 0 AS apply_xatosi,
       EXISTS (SELECT 1 FROM balance_transactions bt
                WHERE bt.student_group_id = sg.id AND bt.migration_run_id = r.id) AS run_ledger_yozuvi,
       EXISTS (SELECT 1 FROM billing_periods p
                WHERE p.student_group_id = sg.id AND p.migration_run_id = r.id) AS run_davri
  FROM student_groups sg
 CROSS JOIN billing_migration_runs r
 WHERE sg.student_id IN (:qids) AND r.applied_at IS NOT NULL
 ORDER BY sg.id, r.id;

\echo '== 1.3 billing_periods (davr, summa, holat, due)'
SELECT p.id, p.student_group_id AS sg_id, p.period_start, p.period_end, p.fee, p.discount_percentage AS chegirma,
       p.amount, p.status, p.due_date, p.grace_until, p.paid_on, p.coverage_source, p.refunded_amount,
       p.charge_tx_id, p.migration_run_id, p.created_at
  FROM billing_periods p
  JOIN student_groups sg ON sg.id = p.student_group_id
 WHERE sg.student_id IN (:qids)
 ORDER BY p.student_group_id, p.period_start;

\echo '== 1.4 To''lovlar (hammasi, bekor qilinganlar ham). v2 = oxirgi qo''llangan run''dan keyin kiritilgan'
SELECT py.id, py.receipt_number, py.status, py.payment_date, py.amount, py.payable_amount, py.discount_amount,
       py.bonus_discount, py.balance_used, py.cash_amount, py.payment_method, py.period_from, py.period_to,
       py.student_group_id AS sg_id, py.created_at, py.cancelled_at, py.cancel_reason,
       py.id > COALESCE((SELECT max(r.max_payment_id) FROM billing_migration_runs r WHERE r.applied_at IS NOT NULL), 0)
           AS v2_tolov
  FROM payments py
 WHERE py.student_id IN (:qids)
 ORDER BY py.payment_date, py.id;

\echo '== 1.5 Ledger (balance_transactions), SG bo''yicha yig''indi bilan'
SELECT bt.id, bt.student_group_id AS sg_id, bt.type, bt.amount,
       sum(bt.amount) OVER (PARTITION BY bt.student_group_id ORDER BY bt.id) AS yigindi,
       bt.balance_after, bt.effective_date, bt.billing_period_id, bt.migration_run_id, bt.related_tx_id,
       bt.note, bt.created_at
  FROM balance_transactions bt
 WHERE bt.student_id IN (:qids)
 ORDER BY bt.student_group_id NULLS FIRST, bt.id;

\echo '== 1.6 Yozilmalar: hisoblangan holat, toifa va barcha belgilar — pastdagi 2.1 so''rovi shu o''quvchi uchun'

\endif

-- ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
-- 2.1 YOZILMALAR RO'YXATI
--   q berilgan  — shu o'quvchining BARCHA yozilmalari (1.6);
--   q bo'sh     — ochiq yozilmalar ichida qarzdorlarda ham, kutilayotganlarda ham bo'lmaganlari ("tushib qolgan"),
--                 normal toifalar (QARZDOR, KUTILAYOTGAN, SHU_OY_TOLAGAN, KEYINROQ) chiqarilmaydi.
--   toifa — birinchi mos sabab (ustunlik tartibida); belgilar — mos kelgan BARCHA shartlar.
--   snapshot_mos — saqlangan sg.next_payment_date kod hozir yozadigan qiymatga tengmi (false — snapshot eskirgan).
-- ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
\echo '== 2.1 Yozilmalar (q bo''sh bo''lsa — tushib qolganlar)'
WITH params AS (
    SELECT t.today,
           COALESCE(NULLIF(:'from', '')::date, t.today + 1) AS d_from,
           COALESCE(NULLIF(:'to', '')::date, (date_trunc('month', t.today::timestamp) + interval '1 month - 1 day')::date) AS d_to,
           date_trunc('month', t.today::timestamp)::date AS month_start
      FROM (SELECT COALESCE(NULLIF(:'today', '')::date, (now() AT TIME ZONE 'Asia/Tashkent')::date) AS today) t
),
x AS (
    SELECT sg.id AS sg_id, s.id AS student_id, s.admission_number AS raqam,
           trim(s.first_name || ' ' || s.last_name) AS oquvchi, s.status AS st_status,
           g.group_name AS guruh, COALESCE(g.status, 'ACTIVE') AS g_status, g.end_date AS g_end,
           COALESCE(sg.payment_type, 'MONTHLY') AS tur,
           COALESCE(sg.is_active, false) AS is_active, COALESCE(sg.is_trial, false) AS is_trial,
           sg.frozen_from, sg.join_date, sg.payment_start_date AS anchor, COALESCE(sg.billing_hold, false) AS hold,
           COALESCE(sg.balance, 0) AS balance, sg.debt_since, sg.payment_status AS sg_status,
           sg.next_payment_date AS sg_next, sg.next_payment_amount AS sg_next_amount,
           CASE WHEN sg.monthly_price_override > 0 THEN sg.monthly_price_override
                WHEN c.monthly_price > 0 THEN c.monthly_price ELSE 0 END AS fee,
           COALESCE(sg.discount_percentage, 0) AS disc,
           (SELECT max(p.period_start) FROM billing_periods p
             WHERE p.student_group_id = sg.id AND p.period_start >= sg.payment_start_date) AS last_start,
           (SELECT count(*) FROM billing_periods p WHERE p.student_group_id = sg.id) AS davrlar,
           (SELECT COALESCE(sum(bt.amount), 0) FROM balance_transactions bt WHERE bt.student_group_id = sg.id) AS ledger_sum,
           EXISTS (SELECT 1 FROM group_schedules gs WHERE gs.group_id = g.id) AS jadval_bor,
           EXISTS (SELECT 1 FROM payments py, params
                    WHERE py.student_group_id = sg.id AND py.status = 'PAID'
                      AND py.payment_date BETWEEN params.month_start AND params.today) AS shu_oy_tolov
      FROM student_groups sg
      JOIN students s ON s.id = sg.student_id
      JOIN groups g ON g.id = sg.group_id
      LEFT JOIN courses c ON c.id = g.course_id
),
y AS (
    SELECT x.*, params.*,
           round(x.fee * (100 - x.disc) / 100, 0) AS fee_eff,                                  -- EnrollmentPricing c(sg)
           (x.is_active OR x.frozen_from IS NOT NULL) AS sg_open,                               -- isOpen
           (x.is_active AND x.frozen_from IS NULL AND NOT x.is_trial
              AND x.g_status IN ('ACTIVE', 'FORMING')) AS billing_open,                         -- isBillingOpen
           gi.i_after_last, gi.i_after_today
      FROM x
     CROSS JOIN params
      LEFT JOIN LATERAL (
          -- Langar panjarasi: start(n) = min(langar kuni, oy uzunligi), BillingCalendar.start
          SELECT min(grid.n) FILTER (WHERE grid.st > x.last_start) AS i_after_last,
                 min(grid.n) FILTER (WHERE grid.st > params.today) AS i_after_today
            FROM (SELECT n, (date_trunc('month', x.anchor::timestamp) + make_interval(months => n))::date
                            + LEAST(EXTRACT(DAY FROM x.anchor)::int,
                                    EXTRACT(DAY FROM date_trunc('month', x.anchor::timestamp)
                                                     + make_interval(months => n + 1) - interval '1 day')::int) - 1 AS st
                    FROM generate_series(0, 600) n) grid
      ) gi ON x.anchor IS NOT NULL
),
z AS (
    SELECT y.*, nn.n_up,
           CASE WHEN nn.n_up IS NOT NULL THEN
               (date_trunc('month', y.anchor::timestamp) + make_interval(months => nn.n_up))::date
               + LEAST(EXTRACT(DAY FROM y.anchor)::int,
                       EXTRACT(DAY FROM date_trunc('month', y.anchor::timestamp)
                                        + make_interval(months => nn.n_up + 1) - interval '1 day')::int) - 1
           END AS up_raw,                                       -- kod hisoblaydigan keyingi davr boshi (R3 va today'siz)
           CASE WHEN y.fee_eff > 0 AND y.balance >= 0
                THEN y.fee_eff - (y.balance - y.fee_eff * floor(y.balance / y.fee_eff))
                WHEN y.fee_eff > 0 THEN y.fee_eff END AS up_amount
      FROM y
     CROSS JOIN LATERAL (
          SELECT CASE
                   WHEN y.tur = 'PER_LESSON' OR y.anchor IS NULL OR y.fee_eff <= 0 THEN NULL
                   WHEN y.balance >= 0
                       THEN COALESCE(CASE WHEN y.last_start IS NULL THEN 0 ELSE y.i_after_last END, 0)
                            + floor(y.balance / y.fee_eff)::int
                   ELSE GREATEST(COALESCE(CASE WHEN y.last_start IS NULL THEN 0 ELSE y.i_after_last END, 0),
                                 y.i_after_today)
                 END AS n_up
     ) nn
),
v AS (
    SELECT z.*,
           (z.g_end IS NOT NULL AND z.up_raw >= z.g_end) AS r3_kesdi,
           (z.balance < 0 AND z.debt_since IS NOT NULL AND z.debt_since <= z.today
              AND z.sg_open AND z.st_status IN ('ACTIVE', 'FROZEN')) AS qarzdor,
           CASE WHEN z.billing_open AND z.st_status = 'ACTIVE' AND z.up_raw > z.today
                     AND NOT (z.g_end IS NOT NULL AND z.up_raw >= z.g_end)
                THEN z.up_raw END AS kutilgan_sana,
           -- BillingStatusService.nextPayment (197-226) hozir yozadigan qiymat (MONTHLY)
           CASE WHEN NOT z.billing_open OR z.tur = 'PER_LESSON' THEN NULL
                WHEN z.balance < 0 THEN z.debt_since
                WHEN z.g_end IS NOT NULL AND z.up_raw >= z.g_end THEN NULL
                ELSE z.up_raw END AS snap_kutilgan
      FROM z
),
w AS (
    SELECT v.*,
           CASE
               WHEN v.qarzdor THEN 'QARZDOR'
               WHEN NOT v.sg_open THEN 'YOPILGAN'
               WHEN v.balance < 0 THEN 'MANFIY_BALANS_QARZDOR_EMAS'
               WHEN v.frozen_from IS NOT NULL THEN 'MUZLATILGAN'
               WHEN v.is_trial THEN 'SINOV'
               WHEN v.tur = 'PER_LESSON' THEN 'PER_LESSON'
               WHEN v.hold THEN 'HOLD'
               WHEN v.g_status NOT IN ('ACTIVE', 'FORMING') THEN 'GURUH_' || v.g_status
               WHEN v.st_status <> 'ACTIVE' THEN 'OQUVCHI_' || v.st_status
               WHEN v.anchor IS NULL THEN 'LANGAR_YOQ'
               WHEN v.fee_eff <= 0 THEN CASE WHEN v.fee > 0 THEN 'CHEGIRMA_100' ELSE 'NARX_0' END
               WHEN v.r3_kesdi THEN 'GURUH_TUGAGAN_R3'
               WHEN v.up_raw <= v.today THEN 'DAVR_YOZILMAGAN'
               WHEN v.up_raw < v.d_from THEN 'ORALIQDAN_OLDIN'
               WHEN v.up_raw <= v.d_to THEN 'KUTILAYOTGAN'
               WHEN v.shu_oy_tolov THEN 'SHU_OY_TOLAGAN'
               ELSE 'KEYINROQ'
           END AS toifa,
           concat_ws(', ',
               CASE WHEN v.hold THEN 'hold' END,
               CASE WHEN v.anchor IS NULL THEN 'langar yo''q' END,
               CASE WHEN v.fee <= 0 THEN 'narx 0' END,
               CASE WHEN v.fee > 0 AND v.fee_eff <= 0 THEN 'chegirma 100%' END,
               CASE WHEN v.disc > 0 AND v.disc < 100 THEN 'chegirma ' || v.disc || '%' END,
               CASE WHEN v.g_status NOT IN ('ACTIVE', 'FORMING') THEN 'guruh ' || v.g_status END,
               CASE WHEN v.g_end IS NOT NULL AND v.g_end <= v.today THEN 'guruh end_date o''tgan' END,
               CASE WHEN v.r3_kesdi THEN 'R3: keyingi davr ≥ end_date' END,
               CASE WHEN v.is_trial THEN 'sinov' END,
               CASE WHEN v.frozen_from IS NOT NULL THEN 'muzlatilgan' END,
               CASE WHEN NOT v.is_active AND v.frozen_from IS NULL THEN 'nofaol' END,
               CASE WHEN v.st_status NOT IN ('ACTIVE', 'FROZEN') THEN 'o''quvchi ' || v.st_status END,
               CASE WHEN v.tur = 'PER_LESSON' AND NOT v.jadval_bor THEN 'PER_LESSON jadvalsiz' END,
               CASE WHEN v.tur = 'MONTHLY' AND v.anchor IS NOT NULL AND v.anchor <= v.today AND v.davrlar = 0
                    THEN 'davr yo''q (langar o''tgan)' END,
               CASE WHEN v.up_raw <= v.today AND v.balance >= 0 THEN 'boshlangan davr yozilmagan' END,
               CASE WHEN v.balance > 0 THEN 'musbat balans' END,
               CASE WHEN v.balance < 0 AND v.debt_since IS NULL THEN 'manfiy balans, debt_since yo''q' END,
               CASE WHEN v.balance < 0 AND v.debt_since > v.today THEN 'debt_since kelajakda' END,
               CASE WHEN v.ledger_sum <> v.balance THEN 'balans ≠ ledger' END,
               CASE WHEN v.tur = 'MONTHLY' AND v.sg_next IS DISTINCT FROM v.snap_kutilgan THEN 'snapshot eskirgan' END
           ) AS belgilar
      FROM v
)
SELECT w.raqam, w.oquvchi, w.sg_id, w.guruh, w.toifa, w.belgilar,
       w.tur, w.st_status, w.g_status, w.g_end, w.is_active, w.is_trial, w.frozen_from, w.hold,
       w.join_date, w.anchor AS langar, w.fee, w.disc AS chegirma, w.fee_eff AS c,
       w.balance, w.ledger_sum, w.debt_since, w.sg_status,
       w.sg_next AS saqlangan_keyingi, w.sg_next_amount AS saqlangan_summa, w.snap_kutilgan AS kod_yozadigan_keyingi,
       (w.sg_next IS NOT DISTINCT FROM w.snap_kutilgan) AS snapshot_mos,
       w.davrlar, w.last_start AS oxirgi_davr, w.up_raw AS hisoblangan_keyingi, w.up_amount AS keyingi_summa,
       w.kutilgan_sana, (w.kutilgan_sana BETWEEN w.d_from AND w.d_to) AS kutilayotganda,
       w.shu_oy_tolov, w.today, w.d_from, w.d_to
  FROM w
 WHERE (:'q' <> '' AND w.student_id IN (:qids))
    OR (:'q' = '' AND w.sg_open AND w.st_status IN ('ACTIVE', 'FROZEN')
        AND w.toifa NOT IN ('QARZDOR', 'KUTILAYOTGAN', 'SHU_OY_TOLAGAN', 'KEYINROQ'))
 ORDER BY w.toifa, w.raqam, w.sg_id;

-- ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
-- 2.2 UMUMIY TAQSIMOT (q dan qat'i nazar)
--   A — ochiq MONTHLY yozilmalar toifalar bo'yicha (har yozilma bitta toifada; JAMI = hammasi);
--   B — faol (students.status = ACTIVE) o'quvchilar, har biri BITTA toifada (eng ustun yozilmasi bo'yicha):
--       QARZDOR > KUTILAYOTGAN > ORALIQDAN_OLDIN > SHU_OY_TOLAGAN > KEYINROQ > PER_LESSON > (boshqa sabablar) > SINOV;
--       JAMI = "faol o'quvchilar" soni — buyurtmachi hisobidagi "qolganlari" shu yerda;
--   C — ekrandagi raqamlar: qarzdorlar, kutilayotganlar (MONTHLY) va ularning kesishmasi ("ikki marta sanash"),
--       kutilayotgandagi "To'langan" (balans ≥ 0) qatorlar.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
\echo '== 2.2 Umumiy taqsimot'
WITH params AS (
    SELECT t.today,
           COALESCE(NULLIF(:'from', '')::date, t.today + 1) AS d_from,
           COALESCE(NULLIF(:'to', '')::date, (date_trunc('month', t.today::timestamp) + interval '1 month - 1 day')::date) AS d_to,
           date_trunc('month', t.today::timestamp)::date AS month_start
      FROM (SELECT COALESCE(NULLIF(:'today', '')::date, (now() AT TIME ZONE 'Asia/Tashkent')::date) AS today) t
),
x AS (
    SELECT sg.id AS sg_id, s.id AS student_id, s.status AS st_status,
           COALESCE(g.status, 'ACTIVE') AS g_status, g.end_date AS g_end,
           COALESCE(sg.payment_type, 'MONTHLY') AS tur,
           COALESCE(sg.is_active, false) AS is_active, COALESCE(sg.is_trial, false) AS is_trial,
           sg.frozen_from, sg.payment_start_date AS anchor, COALESCE(sg.billing_hold, false) AS hold,
           COALESCE(sg.balance, 0) AS balance, sg.debt_since,
           CASE WHEN sg.monthly_price_override > 0 THEN sg.monthly_price_override
                WHEN c.monthly_price > 0 THEN c.monthly_price ELSE 0 END AS fee,
           COALESCE(sg.discount_percentage, 0) AS disc,
           CASE WHEN sg.lesson_price > 0 THEN sg.lesson_price
                WHEN c.lesson_price > 0 THEN c.lesson_price ELSE 0 END AS lesson_fee,
           (SELECT max(p.period_start) FROM billing_periods p
             WHERE p.student_group_id = sg.id AND p.period_start >= sg.payment_start_date) AS last_start,
           EXISTS (SELECT 1 FROM group_schedules gs WHERE gs.group_id = g.id) AS jadval_bor,
           EXISTS (SELECT 1 FROM payments py, params
                    WHERE py.student_group_id = sg.id AND py.status = 'PAID'
                      AND py.payment_date BETWEEN params.month_start AND params.today) AS shu_oy_tolov
      FROM student_groups sg
      JOIN students s ON s.id = sg.student_id
      JOIN groups g ON g.id = sg.group_id
      LEFT JOIN courses c ON c.id = g.course_id
),
y AS (
    SELECT x.*, params.*,
           round(x.fee * (100 - x.disc) / 100, 0) AS fee_eff,
           (x.is_active OR x.frozen_from IS NOT NULL) AS sg_open,
           (x.is_active AND x.frozen_from IS NULL AND NOT x.is_trial
              AND x.g_status IN ('ACTIVE', 'FORMING')) AS billing_open,
           gi.i_after_last, gi.i_after_today
      FROM x
     CROSS JOIN params
      LEFT JOIN LATERAL (
          SELECT min(grid.n) FILTER (WHERE grid.st > x.last_start) AS i_after_last,
                 min(grid.n) FILTER (WHERE grid.st > params.today) AS i_after_today
            FROM (SELECT n, (date_trunc('month', x.anchor::timestamp) + make_interval(months => n))::date
                            + LEAST(EXTRACT(DAY FROM x.anchor)::int,
                                    EXTRACT(DAY FROM date_trunc('month', x.anchor::timestamp)
                                                     + make_interval(months => n + 1) - interval '1 day')::int) - 1 AS st
                    FROM generate_series(0, 600) n) grid
      ) gi ON x.anchor IS NOT NULL
),
z AS (
    SELECT y.*,
           CASE WHEN nn.n_up IS NOT NULL THEN
               (date_trunc('month', y.anchor::timestamp) + make_interval(months => nn.n_up))::date
               + LEAST(EXTRACT(DAY FROM y.anchor)::int,
                       EXTRACT(DAY FROM date_trunc('month', y.anchor::timestamp)
                                        + make_interval(months => nn.n_up + 1) - interval '1 day')::int) - 1
           END AS up_raw
      FROM y
     CROSS JOIN LATERAL (
          SELECT CASE
                   WHEN y.tur = 'PER_LESSON' OR y.anchor IS NULL OR y.fee_eff <= 0 THEN NULL
                   WHEN y.balance >= 0
                       THEN COALESCE(CASE WHEN y.last_start IS NULL THEN 0 ELSE y.i_after_last END, 0)
                            + floor(y.balance / y.fee_eff)::int
                   ELSE GREATEST(COALESCE(CASE WHEN y.last_start IS NULL THEN 0 ELSE y.i_after_last END, 0),
                                 y.i_after_today)
                 END AS n_up
     ) nn
),
v AS (
    SELECT z.*,
           (z.g_end IS NOT NULL AND z.up_raw >= z.g_end) AS r3_kesdi,
           (z.balance < 0 AND z.debt_since IS NOT NULL AND z.debt_since <= z.today
              AND z.sg_open AND z.st_status IN ('ACTIVE', 'FROZEN')) AS qarzdor,
           CASE WHEN z.billing_open AND z.st_status = 'ACTIVE' AND z.up_raw > z.today
                     AND NOT (z.g_end IS NOT NULL AND z.up_raw >= z.g_end)
                THEN z.up_raw END AS kutilgan_sana
      FROM z
),
w AS (
    SELECT v.*,
           CASE
               WHEN v.qarzdor THEN 'QARZDOR'
               WHEN NOT v.sg_open THEN 'YOPILGAN'
               WHEN v.balance < 0 THEN 'MANFIY_BALANS_QARZDOR_EMAS'
               WHEN v.frozen_from IS NOT NULL THEN 'MUZLATILGAN'
               WHEN v.is_trial THEN 'SINOV'
               WHEN v.tur = 'PER_LESSON' THEN 'PER_LESSON'
               WHEN v.hold THEN 'HOLD'
               WHEN v.g_status NOT IN ('ACTIVE', 'FORMING') THEN 'GURUH_' || v.g_status
               WHEN v.st_status <> 'ACTIVE' THEN 'OQUVCHI_' || v.st_status
               WHEN v.anchor IS NULL THEN 'LANGAR_YOQ'
               WHEN v.fee_eff <= 0 THEN CASE WHEN v.fee > 0 THEN 'CHEGIRMA_100' ELSE 'NARX_0' END
               WHEN v.r3_kesdi THEN 'GURUH_TUGAGAN_R3'
               WHEN v.up_raw <= v.today THEN 'DAVR_YOZILMAGAN'
               WHEN v.up_raw < v.d_from THEN 'ORALIQDAN_OLDIN'
               WHEN v.up_raw <= v.d_to THEN 'KUTILAYOTGAN'
               WHEN v.shu_oy_tolov THEN 'SHU_OY_TOLAGAN'
               ELSE 'KEYINROQ'
           END AS toifa
      FROM v
),
rank_of AS (
    SELECT w.*,
           CASE w.toifa
               WHEN 'QARZDOR' THEN 1 WHEN 'KUTILAYOTGAN' THEN 2 WHEN 'ORALIQDAN_OLDIN' THEN 3
               WHEN 'SHU_OY_TOLAGAN' THEN 4 WHEN 'KEYINROQ' THEN 5 WHEN 'PER_LESSON' THEN 6
               WHEN 'SINOV' THEN 90 WHEN 'MUZLATILGAN' THEN 91 WHEN 'YOPILGAN' THEN 99 ELSE 50
           END AS ustunlik
      FROM w
),
per_student AS (
    SELECT DISTINCT ON (s.id) s.id AS student_id, COALESCE(r.toifa, 'OCHIQ_YOZILMA_YOQ') AS toifa,
           COALESCE(r.ustunlik, 100) AS ustunlik
      FROM students s
      LEFT JOIN rank_of r ON r.student_id = s.id AND r.sg_open
     WHERE s.status = 'ACTIVE'
     ORDER BY s.id, COALESCE(r.ustunlik, 100)
),
exp_rows AS (
    SELECT w.* FROM w WHERE w.tur = 'MONTHLY' AND w.kutilgan_sana BETWEEN w.d_from AND w.d_to
)
SELECT '0. Parametrlar' AS kesim,
       format('bugun %s, kutilayotgan oralig''i %s – %s', p.today, p.d_from, p.d_to) AS toifa,
       NULL::bigint AS yozilma, NULL::bigint AS oquvchi, 0 AS tartib
  FROM params p
UNION ALL
SELECT 'A. Ochiq MONTHLY yozilmalar', COALESCE(toifa, '= JAMI'), count(*), count(DISTINCT student_id),
       CASE WHEN toifa IS NULL THEN 999 ELSE min(ustunlik) END
  FROM rank_of
 WHERE tur = 'MONTHLY' AND sg_open AND st_status IN ('ACTIVE', 'FROZEN')
 GROUP BY ROLLUP (toifa)
UNION ALL
SELECT 'B. Faol o''quvchilar (har biri bitta toifada)', COALESCE(toifa, '= JAMI'), NULL, count(*),
       CASE WHEN toifa IS NULL THEN 999 ELSE min(ustunlik) END
  FROM per_student
 GROUP BY ROLLUP (toifa)
UNION ALL
SELECT 'C. Ekran', 'Qarzdorlar (o''quvchi, barcha turlar)', count(*), count(DISTINCT student_id), 1
  FROM w WHERE qarzdor
UNION ALL
SELECT 'C. Ekran', 'Kutilayotgan, MONTHLY (qator / o''quvchi)', count(*), count(DISTINCT student_id), 2
  FROM exp_rows
UNION ALL
SELECT 'C. Ekran', '  shundan qarzdor ham (ikki ro''yxatda)', count(*), count(DISTINCT student_id), 3
  FROM exp_rows e WHERE EXISTS (SELECT 1 FROM w q WHERE q.student_id = e.student_id AND q.qarzdor)
UNION ALL
SELECT 'C. Ekran', '  shundan "To''langan" (balans ≥ 0)', count(*), count(DISTINCT student_id), 4
  FROM exp_rows WHERE balance >= 0
UNION ALL
SELECT 'C. Ekran', 'Kutilayotgan, PER_LESSON (taxminiy: jadval bor, dars narxi > 0)', count(*),
       count(DISTINCT student_id), 5
  FROM w
 WHERE tur = 'PER_LESSON' AND billing_open AND st_status = 'ACTIVE' AND jadval_bor
   AND round(lesson_fee * (100 - disc) / 100, 0) > 0
ORDER BY kesim, tartib, toifa;

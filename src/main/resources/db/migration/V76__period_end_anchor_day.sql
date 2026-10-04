-- Kalendar QAROR 1 (billing-v2 §14.7) — eski qoida (29–31 → oy oxiri) bilan saqlangan oxirgi davr OXIRINI tuzatish.
--
-- Nima uchun: langar kuni 29/30 bo'lgan yozilmada oxirgi davr eski qoida bilan yozilgan (masalan 29.09–30.10). Kod endi
-- keyingi davrni langar kuni bo'yicha topadi (29.10) va uni yozganda eski oxirni 28.10 ga qisqartiradi (AccrualService).
-- Ungacha saqlangan 30.10 muzlatish qaytarimi (davr uzunligi) va re-anchor cheklovida (oxir + 1) eski kalendarni
-- ishlatadi. Bu skript har yozilmaning langar bo'yicha OXIRGI davri oxirini yangi panjaraga keltiradi:
--   yangi_oxir = (davr boshidan keyingi birinchi langar-kuni sanasi) − 1,  langar kuni = min(kun, oy uzunligi)
-- Faqat QISQARTIRADI (yangi_oxir < saqlangan oxir). Boshi, summasi, ledger, holat — o'zgarmaydi. Qaytarilgan
-- (REFUNDED / PARTIALLY_REFUNDED) davrlarga tegilmaydi — ularning qaytarimi eski chegaralar bilan yozilgan.
--
-- V75 dan KEYIN, QO'LDA, crm_user bilan, psql -v ON_ERROR_STOP=1. Idempotent: qayta bajarilsa 0 qator.
--   Ko'rib chiqish (hech narsa yozmaydi; 2-bo'lim "read-only transaction" xatosi bilan to'xtaydi — kutilgan):
--     PGOPTIONS='-c default_transaction_read_only=on' psql ... -X -v ON_ERROR_STOP=1 -f V76__period_end_anchor_day.sql
-- Tekshirish: docs/ops/prod-schema-check.sql — "V76 oxirgi davr oxiri langar kuni panjarasida" = true.

-- ── 1. KO'RIB CHIQISH (faqat o'qiydi) ───────────────────────────────────────────────
-- Har qator — o'zgaradigan davr: saqlangan oxir → yangi oxir (keyingi davr = yangi_oxir + 1).
WITH last AS (
    SELECT DISTINCT ON (p.student_group_id)
           p.id, p.student_group_id, p.period_start, p.period_end, p.status,
           sg.payment_start_date AS langar, EXTRACT(DAY FROM sg.payment_start_date)::int AS kun
      FROM billing_periods p
      JOIN student_groups sg ON sg.id = p.student_group_id
     WHERE sg.payment_start_date IS NOT NULL
       AND p.period_start >= sg.payment_start_date
       AND EXTRACT(DAY FROM sg.payment_start_date) >= 29
     ORDER BY p.student_group_id, p.period_start DESC
), g AS (
    SELECT last.*,
           date_trunc('month', period_start)::date                       AS m0,
           (date_trunc('month', period_start) + INTERVAL '1 month')::date AS m1
      FROM last
), c AS (
    SELECT g.*,
           make_date(EXTRACT(YEAR FROM m0)::int, EXTRACT(MONTH FROM m0)::int,
                     LEAST(kun, EXTRACT(DAY FROM (m0 + INTERVAL '1 month' - INTERVAL '1 day'))::int)) AS y0,
           make_date(EXTRACT(YEAR FROM m1)::int, EXTRACT(MONTH FROM m1)::int,
                     LEAST(kun, EXTRACT(DAY FROM (m1 + INTERVAL '1 month' - INTERVAL '1 day'))::int)) AS y1
      FROM g
), t AS (
    SELECT c.*, (CASE WHEN y0 > period_start THEN y0 ELSE y1 END) - 1 AS yangi_oxir FROM c
)
SELECT student_group_id AS sg_id, id AS period_id, langar, kun, status, period_start, period_end AS saqlangan_oxir,
       yangi_oxir, yangi_oxir + 1 AS keyingi_davr
  FROM t
 WHERE period_end > yangi_oxir
   AND status NOT IN ('REFUNDED', 'PARTIALLY_REFUNDED')
 ORDER BY student_group_id;

-- ── 2. QO'LLASH ─────────────────────────────────────────────────────────────────────
-- "UPDATE N" — 1-bo'limdagi qatorlar soniga teng bo'lishi kerak.
BEGIN;

WITH last AS (
    SELECT DISTINCT ON (p.student_group_id)
           p.id, p.period_start, p.period_end, p.status,
           EXTRACT(DAY FROM sg.payment_start_date)::int AS kun
      FROM billing_periods p
      JOIN student_groups sg ON sg.id = p.student_group_id
     WHERE sg.payment_start_date IS NOT NULL
       AND p.period_start >= sg.payment_start_date
       AND EXTRACT(DAY FROM sg.payment_start_date) >= 29
     ORDER BY p.student_group_id, p.period_start DESC
), g AS (
    SELECT last.*,
           date_trunc('month', period_start)::date                       AS m0,
           (date_trunc('month', period_start) + INTERVAL '1 month')::date AS m1
      FROM last
), c AS (
    SELECT g.*,
           make_date(EXTRACT(YEAR FROM m0)::int, EXTRACT(MONTH FROM m0)::int,
                     LEAST(kun, EXTRACT(DAY FROM (m0 + INTERVAL '1 month' - INTERVAL '1 day'))::int)) AS y0,
           make_date(EXTRACT(YEAR FROM m1)::int, EXTRACT(MONTH FROM m1)::int,
                     LEAST(kun, EXTRACT(DAY FROM (m1 + INTERVAL '1 month' - INTERVAL '1 day'))::int)) AS y1
      FROM g
), t AS (
    SELECT c.id, c.period_end, c.status, (CASE WHEN y0 > period_start THEN y0 ELSE y1 END) - 1 AS yangi_oxir FROM c
)
UPDATE billing_periods p
   SET period_end = t.yangi_oxir
  FROM t
 WHERE p.id = t.id
   AND t.period_end > t.yangi_oxir
   AND t.status NOT IN ('REFUNDED', 'PARTIALLY_REFUNDED');

COMMIT;

-- ── 3. TEKSHIRUV ────────────────────────────────────────────────────────────────────
-- Bir SG ichida ustma-ust davrlar yo'q (keyingi boshi ≤ oldingi oxiri) — 0 bo'lishi kerak. Qaytarilgan (muzlatish)
-- davr ustiga unfreeze'dagi yangi langar tushishi mumkin (§6.7) — ular sanalmaydi.
SELECT COUNT(*) AS ustma_ust
  FROM (SELECT period_end, status,
               LEAD(period_start) OVER (PARTITION BY student_group_id ORDER BY period_start) AS nxt
          FROM billing_periods) x
 WHERE nxt IS NOT NULL AND nxt <= period_end
   AND status NOT IN ('REFUNDED', 'PARTIALLY_REFUNDED');

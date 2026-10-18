-- Kassa cut-over (V86 dan KEYIN, yangi jar ishga tushgandan keyin, deploy kuni kechqurun, QO'LDA).
-- docs/design/cash-and-books.md §1.2.3, §1.7, docs/ops/cash-and-books-deploy.md.
--
-- Nima qiladi (har ADMIN kassa uchun BIR MARTA — kalit cash_handovers.idempotency_key = 'cutover-v86-<id>'):
--   a) a'zolar: kassaning moderator_id si (roli ADMIN yoki ACCOUNTANT) → cash_register_members;
--   b) Direktor kassasi: DIRECTOR turidagi kassa bo'lmasa — "Direktor kassasi" yaratiladi (balans 0);
--   c) legacy topshirish (ACCEPTED, legacy) — kassa uchun cut-over belgisi;
--   d) ESKI QOLDIQ Direktor kassasiga ko'chiriladi: tizim boshidan yig'ilgan balans (pul real hayotda allaqachon
--      Direktorga tizimdan tashqarida topshirilgan). Oddiy o'tkazma bilan AYNAN bir xil juftlik — TRANSFER
--      "Ko'chirish (chiqim)" (manba) + "Ko'chirish (kirim)" (maqsad, related_tx_id = chiqim qatori), kategoriyasiz,
--      ikkala qator legacy topshirishga bog'langan. Usul bo'yicha: har usul (CASH, CARD, TERMINAL, CLICK, PAYME, UZUM,
--      OTHER) tranzaksiyalardagi sof qoldig'i (CashChannelService bilan bir xil yo'nalish qoidasi; CASH_AND_CARD qismlari
--      CASH/CARD ga, eski BANK → TERMINAL); tranzaksiyasiz farq (boshlang'ich qoldiq) — naqd chelak uchun CASH,
--      plastik chelak uchun CARD qatoriga qo'shiladi. Manfiy sof qoldiq — teskari yo'nalishda (Direktor → admin).
--      Natija: admin kassasi naqd = razmen (float_amount; cut-over paytida odatda 0), plastik = 0;
--      usul bo'yicha qoldiqlar ham 0 (razmen — CASH da).
--   e) kassaning qolgan barcha topshirilmagan yozuvlari legacy topshirishga bog'lanadi — birinchi kunlik topshirish
--      faqat cut-over'dan keyingi yozuvlarni ko'rsatadi.
-- Moliya hisobotiga TA'SIR QILMAYDI: o'tkazma kirim/chiqim emas (CashCategoryTotals, CashChannelService netIncome/
-- netExpense ularni hisobga olmaydi; barcha kassalar bo'yicha o'tkazmalar 0 ga teng).
-- Idempotent: qayta bajarilsa cut-over qilingan kassaga TEGMAYDI (ikkinchi marta ko'chirmaydi); faqat yangi qo'shilgan
-- ADMIN kassalar uchun bajariladi.
--
-- Razmen: cut-over'dan OLDIN SA kassaga razmen belgilasa (PUT /api/cash-registers/{id} floatAmount), shuncha naqd
-- admin kassasida qoladi. Keyin belgilansa — razmen pulini Direktor kassasidan admin kassasiga o'tkazma (SA) bilan bering,
-- aks holda topshirishda kutilgan naqd = cash_balance − razmen manfiy chiqadi.
--
-- Ishlatish (psql, crm_user, -X -v ON_ERROR_STOP=1):
--   1) Ko'rib chiqish — hech narsa yozmaydi (2-bo'lim "read-only transaction" xatosi bilan to'xtaydi — kutilgan):
--        PGOPTIONS='-c default_transaction_read_only=on' psql ... -X -v ON_ERROR_STOP=1 -f cash-cutover.sql
--   2) Qo'llash: psql ... -X -v ON_ERROR_STOP=1 -f cash-cutover.sql

-- ── 1. KO'RIB CHIQISH (faqat o'qiydi) ───────────────────────────────────────────────
-- 1a. Kassalar: turi, mas'ul, uning roli, balanslar, topshirilmagan yozuvlar, cut-over qilinganmi
SELECT r.id, r.name, r.kind, r.status, r.float_amount,
       u.id AS moderator_id, u.username, u.role AS moderator_role,
       r.cash_balance, r.plastic_balance,
       (SELECT COUNT(*) FROM cash_transactions t WHERE t.cash_register_id = r.id AND t.handover_id IS NULL)
           AS topshirilmagan_yozuvlar,
       (SELECT COUNT(*) FROM cash_register_members m WHERE m.cash_register_id = r.id) AS azolar,
       EXISTS (SELECT 1 FROM cash_handovers h WHERE h.idempotency_key = 'cutover-v86-' || r.id) AS cutover_qilingan
  FROM cash_registers r
  LEFT JOIN users u ON u.id = r.moderator_id
 ORDER BY r.kind, r.id;

-- 1b. A'zo QILINMAYDIGAN mas'ullar (roli ADMIN / ACCOUNTANT emas) — SA qo'lda hal qiladi
SELECT r.id AS kassa_id, r.name, u.username, u.role
  FROM cash_registers r
  JOIN users u ON u.id = r.moderator_id
 WHERE r.kind = 'ADMIN' AND u.role NOT IN ('ADMIN', 'ACCOUNTANT');

-- 1c. Bir nechta faol kassaning mas'uli bo'lgan admin ("bir admin — bitta kassa" qoidasiga zid) — SA tanlaydi
SELECT u.id, u.username, COUNT(*) AS kassalar, string_agg(r.name, ', ' ORDER BY r.id) AS nomlar
  FROM cash_registers r
  JOIN users u ON u.id = r.moderator_id
 WHERE r.kind = 'ADMIN' AND r.status = 'ACTIVE' AND u.role IN ('ADMIN', 'ACCOUNTANT')
 GROUP BY u.id, u.username
HAVING COUNT(*) > 1;

-- 1d. Kassasiz qoladigan faol ADMIN / ACCOUNTANT — cut-over'dan keyin to'lov qabul qila olmaydi (400)
SELECT u.id, u.username, u.role
  FROM users u
 WHERE u.role IN ('ADMIN', 'ACCOUNTANT')
   AND COALESCE(u.is_active, TRUE)
   AND NOT EXISTS (SELECT 1 FROM cash_registers r WHERE r.moderator_id = u.id AND r.kind = 'ADMIN')
   AND NOT EXISTS (SELECT 1 FROM cash_register_members m WHERE m.user_id = u.id);

-- 1e. Direktor kassasi bormi
SELECT COUNT(*) AS direktor_kassalari FROM cash_registers WHERE kind = 'DIRECTOR';

-- 1f. KO'CHIRILADIGAN QOLDIQ — usul bo'yicha (faqat hali cut-over qilinmagan ADMIN kassalar).
--     sof_qoldiq — tranzaksiyalardan; taqsimlanmagan — chelak balansi bilan farq (CASH / CARD qatoriga qo'shiladi);
--     ko'chiriladi > 0 — admin → Direktor, < 0 — Direktor → admin.
WITH regs AS (
    SELECT r.* FROM cash_registers r
     WHERE r.kind = 'ADMIN'
       AND NOT EXISTS (SELECT 1 FROM cash_handovers h WHERE h.idempotency_key = 'cutover-v86-' || r.id)
), tx AS (
    SELECT t.cash_register_id AS reg, t.payment_method AS method, t.amount, t.cash_part, t.card_part,
           (COALESCE(t.payment_method, '') = 'CASH_AND_CARD' AND t.cash_part IS NOT NULL AND t.card_part IS NOT NULL)
               AS split,
           CASE WHEN t.type = 'INCOME' THEN 1
                WHEN t.type = 'EXPENSE' THEN -1
                WHEN t.type = 'TRANSFER' THEN
                    CASE WHEN t.related_tx_id IS NOT NULL OR t.transaction_name LIKE '%(kirim)%' THEN 1 ELSE -1 END
                WHEN o.type = 'INCOME' THEN -1
                WHEN o.type = 'EXPENSE' THEN 1
                WHEN t.payment_id IS NOT NULL OR t.exam_registration_id IS NOT NULL THEN -1
                ELSE 1 END AS dir
      FROM cash_transactions t
      JOIN regs r ON r.id = t.cash_register_id
      LEFT JOIN cash_transactions o ON o.id = t.related_tx_id AND t.type = 'REVERSAL'
), parts AS (
    SELECT reg, 'CASH' AS method, dir * cash_part AS amt FROM tx WHERE split
    UNION ALL
    SELECT reg, 'CARD', dir * card_part FROM tx WHERE split
    UNION ALL
    SELECT reg, CASE WHEN method IS NULL OR method = 'CASH_AND_CARD' THEN 'CASH'
                     WHEN method = 'BANK' THEN 'TERMINAL' ELSE method END,
           dir * amount
      FROM tx WHERE NOT split
), nets AS (
    SELECT reg, method, SUM(amt) AS net FROM parts GROUP BY reg, method
), rest AS (
    SELECT r.id AS reg, 'CASH' AS method,
           r.cash_balance - r.float_amount - COALESCE((SELECT SUM(n.net) FROM nets n
                                                       WHERE n.reg = r.id AND n.method = 'CASH'), 0) AS remainder
      FROM regs r
    UNION ALL
    SELECT r.id, 'CARD',
           r.plastic_balance - COALESCE((SELECT SUM(n.net) FROM nets n
                                         WHERE n.reg = r.id AND n.method <> 'CASH'), 0)
      FROM regs r
), moves AS (
    SELECT COALESCE(n.reg, x.reg) AS reg, COALESCE(n.method, x.method) AS method,
           COALESCE(n.net, 0) AS sof_qoldiq, COALESCE(x.remainder, 0) AS taqsimlanmagan,
           COALESCE(n.net, 0) + COALESCE(x.remainder, 0) AS kochiriladi
      FROM nets n
      FULL JOIN rest x ON x.reg = n.reg AND x.method = n.method
)
SELECT r.id AS kassa_id, r.name AS kassa, m.method, m.sof_qoldiq, m.taqsimlanmagan, m.kochiriladi
  FROM moves m JOIN regs r ON r.id = m.reg
 WHERE m.sof_qoldiq <> 0 OR m.taqsimlanmagan <> 0
 ORDER BY r.id, m.method;

-- 1g. Kassa bo'yicha jami: ko'chiriladigan naqd va plastik, keyingi holat (naqd = razmen, plastik = 0)
SELECT r.id AS kassa_id, r.name AS kassa, r.cash_balance, r.plastic_balance, r.float_amount,
       r.cash_balance - r.float_amount AS kochiriladigan_naqd,
       r.plastic_balance AS kochiriladigan_plastik,
       r.float_amount AS keyin_naqd, 0 AS keyin_plastik
  FROM cash_registers r
 WHERE r.kind = 'ADMIN'
   AND NOT EXISTS (SELECT 1 FROM cash_handovers h WHERE h.idempotency_key = 'cutover-v86-' || r.id)
 ORDER BY r.id;

-- ── 2. QO'LLASH ─────────────────────────────────────────────────────────────────────
BEGIN;

-- a) moderator → a'zo
INSERT INTO cash_register_members (cash_register_id, user_id)
SELECT r.id, r.moderator_id
  FROM cash_registers r
  JOIN users u ON u.id = r.moderator_id
 WHERE r.kind = 'ADMIN' AND u.role IN ('ADMIN', 'ACCOUNTANT')
ON CONFLICT (cash_register_id, user_id) DO NOTHING;

-- b) Direktor kassasi
INSERT INTO cash_registers (uuid, name, kind, status, archived, accept_online_payment,
                            cash_balance, plastic_balance, balance, float_amount, created_at, updated_at)
SELECT md5(random()::text || clock_timestamp()::text)::uuid::text, 'Direktor kassasi', 'DIRECTOR', 'ACTIVE',
       FALSE, TRUE, 0, 0, 0, 0, now(), now()
 WHERE NOT EXISTS (SELECT 1 FROM cash_registers WHERE kind = 'DIRECTOR');

CREATE TEMP TABLE cash_cutover_director ON COMMIT DROP AS
SELECT MIN(id) AS id FROM cash_registers WHERE kind = 'DIRECTOR' AND status = 'ACTIVE';

-- c) legacy topshirish — faqat hali cut-over qilinmagan ADMIN kassalar (kalit bo'yicha idempotent).
--    Kassalar jadvali tranzaksiya oxirigacha yoziladigan o'zgarishlardan qulflanadi (parallel to'lov balansni o'zgartirmasin).
LOCK TABLE cash_registers IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE cash_cutover_regs ON COMMIT DROP AS
SELECT r.id AS reg, r.cash_balance, r.plastic_balance, r.float_amount
  FROM cash_registers r
 WHERE r.kind = 'ADMIN'
   AND NOT EXISTS (SELECT 1 FROM cash_handovers h WHERE h.idempotency_key = 'cutover-v86-' || r.id);

INSERT INTO cash_handovers (uuid, cash_register_id, target_cash_register_id, business_date, status, float_amount,
                            expected_cash, declared_cash, counted_cash, cash_diff, note, submitted_at, decided_at,
                            legacy, idempotency_key)
SELECT md5(random()::text || clock_timestamp()::text || c.reg::text)::uuid::text,
       c.reg, d.id, current_date, 'ACCEPTED', c.float_amount,
       c.cash_balance - c.float_amount, c.cash_balance - c.float_amount, c.cash_balance - c.float_amount, 0,
       'Cut-over (V86): eski qoldiq Direktor kassasiga, eski yozuvlar topshirilgan', now(), now(),
       TRUE, 'cutover-v86-' || c.reg
  FROM cash_cutover_regs c CROSS JOIN cash_cutover_director d
ON CONFLICT (idempotency_key) DO NOTHING;

-- d) ko'chiriladigan summalar (1f bilan AYNAN bir xil hisob)
CREATE TEMP TABLE cash_cutover_moves ON COMMIT DROP AS
WITH tx AS (
    SELECT t.cash_register_id AS reg, t.payment_method AS method, t.amount, t.cash_part, t.card_part,
           (COALESCE(t.payment_method, '') = 'CASH_AND_CARD' AND t.cash_part IS NOT NULL AND t.card_part IS NOT NULL)
               AS split,
           CASE WHEN t.type = 'INCOME' THEN 1
                WHEN t.type = 'EXPENSE' THEN -1
                WHEN t.type = 'TRANSFER' THEN
                    CASE WHEN t.related_tx_id IS NOT NULL OR t.transaction_name LIKE '%(kirim)%' THEN 1 ELSE -1 END
                WHEN o.type = 'INCOME' THEN -1
                WHEN o.type = 'EXPENSE' THEN 1
                WHEN t.payment_id IS NOT NULL OR t.exam_registration_id IS NOT NULL THEN -1
                ELSE 1 END AS dir
      FROM cash_transactions t
      JOIN cash_cutover_regs c ON c.reg = t.cash_register_id
      LEFT JOIN cash_transactions o ON o.id = t.related_tx_id AND t.type = 'REVERSAL'
), parts AS (
    SELECT reg, 'CASH' AS method, dir * cash_part AS amt FROM tx WHERE split
    UNION ALL
    SELECT reg, 'CARD', dir * card_part FROM tx WHERE split
    UNION ALL
    SELECT reg, CASE WHEN method IS NULL OR method = 'CASH_AND_CARD' THEN 'CASH'
                     WHEN method = 'BANK' THEN 'TERMINAL' ELSE method END,
           dir * amount
      FROM tx WHERE NOT split
), nets AS (
    SELECT reg, method, SUM(amt) AS net FROM parts GROUP BY reg, method
), rest AS (
    SELECT c.reg, 'CASH' AS method,
           c.cash_balance - c.float_amount - COALESCE((SELECT SUM(n.net) FROM nets n
                                                       WHERE n.reg = c.reg AND n.method = 'CASH'), 0) AS remainder
      FROM cash_cutover_regs c
    UNION ALL
    SELECT c.reg, 'CARD',
           c.plastic_balance - COALESCE((SELECT SUM(n.net) FROM nets n
                                         WHERE n.reg = c.reg AND n.method <> 'CASH'), 0)
      FROM cash_cutover_regs c
)
SELECT m.reg, m.method, m.amount, h.id AS handover_id, d.id AS director_id
  FROM (SELECT COALESCE(n.reg, x.reg) AS reg, COALESCE(n.method, x.method) AS method,
               COALESCE(n.net, 0) + COALESCE(x.remainder, 0) AS amount
          FROM nets n FULL JOIN rest x ON x.reg = n.reg AND x.method = n.method) m
  JOIN cash_handovers h ON h.idempotency_key = 'cutover-v86-' || m.reg
  CROSS JOIN cash_cutover_director d
 WHERE m.amount <> 0;

-- O'tkazma juftliklari (CashRegisterService.transfer bilan bir xil): chiqim qatori manbada, kirim qatori maqsadda,
-- kirim.related_tx_id = chiqim.id. amount > 0: admin → Direktor; amount < 0: Direktor → admin.
WITH outs AS (
    INSERT INTO cash_transactions (uuid, cash_register_id, target_cash_register_id, type, payment_method, amount,
                                   transaction_name, note, status, transaction_date, handover_id,
                                   created_at, updated_at)
    SELECT md5(random()::text || clock_timestamp()::text || m.reg::text || m.method)::uuid::text,
           CASE WHEN m.amount > 0 THEN m.reg ELSE m.director_id END,
           CASE WHEN m.amount > 0 THEN m.director_id ELSE m.reg END,
           'TRANSFER', m.method, ABS(m.amount),
           'Ko''chirish (chiqim)', 'Cut-over (V86): eski qoldiq', 'COMPLETED', current_date, m.handover_id,
           now(), now()
      FROM cash_cutover_moves m
    RETURNING id, cash_register_id, target_cash_register_id, payment_method, amount, handover_id
)
INSERT INTO cash_transactions (uuid, cash_register_id, target_cash_register_id, type, payment_method, amount,
                               transaction_name, note, status, transaction_date, handover_id, related_tx_id,
                               created_at, updated_at)
SELECT md5(random()::text || clock_timestamp()::text || o.id::text)::uuid::text,
       o.target_cash_register_id, o.cash_register_id, 'TRANSFER', o.payment_method, o.amount,
       'Ko''chirish (kirim)', 'Cut-over (V86): eski qoldiq', 'COMPLETED', current_date, o.handover_id, o.id,
       now(), now()
  FROM outs o;

-- Chelaklar: CASH — naqd, qolgan usullar — plastik (CashRegisterService.bucketAmounts bilan bir xil)
UPDATE cash_registers r
   SET cash_balance    = r.cash_balance    - COALESCE(s.cash, 0),
       plastic_balance = r.plastic_balance - COALESCE(s.plastic, 0),
       updated_at      = now()
  FROM (SELECT reg,
               SUM(amount) FILTER (WHERE method = 'CASH')  AS cash,
               SUM(amount) FILTER (WHERE method <> 'CASH') AS plastic
          FROM cash_cutover_moves GROUP BY reg) s
 WHERE r.id = s.reg;

UPDATE cash_registers r
   SET cash_balance    = r.cash_balance    + COALESCE(s.cash, 0),
       plastic_balance = r.plastic_balance + COALESCE(s.plastic, 0),
       updated_at      = now()
  FROM (SELECT director_id,
               SUM(amount) FILTER (WHERE method = 'CASH')  AS cash,
               SUM(amount) FILTER (WHERE method <> 'CASH') AS plastic
          FROM cash_cutover_moves GROUP BY director_id) s
 WHERE r.id = s.director_id;

UPDATE cash_registers SET balance = cash_balance + plastic_balance
 WHERE id IN (SELECT reg FROM cash_cutover_regs UNION SELECT id FROM cash_cutover_director);

-- e) eski yozuvlar → legacy topshirish (cut-over qilingan kassalar)
UPDATE cash_transactions t
   SET handover_id = h.id
  FROM cash_handovers h
  JOIN cash_cutover_regs c ON h.idempotency_key = 'cutover-v86-' || c.reg
 WHERE t.cash_register_id = c.reg
   AND t.handover_id IS NULL;

-- Tekshiruv
DO $$
DECLARE
    directors bigint;
    bad_bal   bigint;
    left_     bigint;
    moved     bigint;
BEGIN
    SELECT COUNT(*) INTO directors FROM cash_registers WHERE kind = 'DIRECTOR';
    IF directors = 0 THEN
        RAISE EXCEPTION 'cash-cutover: Direktor kassasi yaratilmadi';
    END IF;
    SELECT COUNT(*) INTO bad_bal
      FROM cash_registers r JOIN cash_cutover_regs c ON c.reg = r.id
     WHERE r.cash_balance <> r.float_amount OR r.plastic_balance <> 0;
    IF bad_bal > 0 THEN
        RAISE EXCEPTION 'cash-cutover: % ta kassada qoldiq nolga (razmenga) tushmadi', bad_bal;
    END IF;
    SELECT COUNT(*) INTO left_
      FROM cash_transactions t JOIN cash_cutover_regs c ON c.reg = t.cash_register_id
     WHERE t.handover_id IS NULL;
    IF left_ > 0 THEN
        RAISE EXCEPTION 'cash-cutover: % ta eski yozuv legacy topshirishga bog''lanmadi', left_;
    END IF;
    SELECT COUNT(*) INTO moved FROM cash_cutover_moves;
    RAISE NOTICE 'cash-cutover: % ta kassa, % ta o''tkazma juftligi, Direktor kassalari %',
        (SELECT COUNT(*) FROM cash_cutover_regs), moved, directors;
END $$;

COMMIT;

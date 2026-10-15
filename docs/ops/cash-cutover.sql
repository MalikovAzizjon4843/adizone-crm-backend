-- Kassa cut-over (V86 dan KEYIN, yangi jar ishga tushgandan keyin, deploy kuni kechqurun, QO'LDA).
-- docs/design/cash-and-books.md §1.2.3, §1.7, docs/ops/cash-and-books-deploy.md.
--
-- Nima qiladi:
--   a) a'zolar: har ADMIN kassaning moderator_id si (roli ADMIN yoki ACCOUNTANT) → cash_register_members;
--   b) Direktor kassasi: DIRECTOR turidagi kassa bo'lmasa — "Direktor kassasi" yaratiladi (balans 0);
--   c) eski yozuvlar: har ADMIN kassaning hozirgacha topshirilmagan yozuvlari bitta legacy (ACCEPTED)
--      topshirishga bog'lanadi — aks holda birinchi kunlik topshirish butun tarixni ko'rsatardi.
--      Kassadagi haqiqiy naqd (eski qoldiq ham) birinchi haqiqiy topshirishda "kutilgan naqd" ga kiradi
--      (cash_balance − razmen) — Direktor shu kuni sanab qabul qiladi.
-- Pulga (balanslarga) TEGMAYDI. Idempotent: qayta bajarilsa a) va b) hech narsa qo'shmaydi, c) faqat
-- yangi topshirilmagan yozuvlar bo'lsa yangi legacy topshirish yaratadi — shuning uchun BIR MARTA bajaring.
--
-- Ishlatish (psql, crm_user, -X -v ON_ERROR_STOP=1):
--   1) Ko'rib chiqish — hech narsa yozmaydi (2-bo'lim "read-only transaction" xatosi bilan to'xtaydi — kutilgan):
--        PGOPTIONS='-c default_transaction_read_only=on' psql ... -X -v ON_ERROR_STOP=1 -f cash-cutover.sql
--   2) Qo'llash: psql ... -X -v ON_ERROR_STOP=1 -f cash-cutover.sql

-- ── 1. KO'RIB CHIQISH (faqat o'qiydi) ───────────────────────────────────────────────
-- 1a. Kassalar: turi, mas'ul, uning roli, balanslar, topshirilmagan yozuvlar
SELECT r.id, r.name, r.kind, r.status, r.float_amount,
       u.id AS moderator_id, u.username, u.role AS moderator_role,
       r.cash_balance, r.plastic_balance,
       (SELECT COUNT(*) FROM cash_transactions t WHERE t.cash_register_id = r.id AND t.handover_id IS NULL)
           AS topshirilmagan_yozuvlar,
       (SELECT COUNT(*) FROM cash_register_members m WHERE m.cash_register_id = r.id) AS azolar
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

-- c) eski yozuvlar → legacy topshirish (har ADMIN kassa, topshirilmagan yozuvi bo'lsa)
CREATE TEMP TABLE cash_cutover_legacy ON COMMIT DROP AS
SELECT r.id AS register_id
  FROM cash_registers r
 WHERE r.kind = 'ADMIN'
   AND EXISTS (SELECT 1 FROM cash_transactions t WHERE t.cash_register_id = r.id AND t.handover_id IS NULL);

INSERT INTO cash_handovers (uuid, cash_register_id, business_date, status, float_amount, expected_cash,
                            declared_cash, note, submitted_at, decided_at, legacy)
SELECT md5(random()::text || clock_timestamp()::text || l.register_id::text)::uuid::text,
       l.register_id, current_date, 'ACCEPTED', 0, 0, 0,
       'Cut-over (V86): eski yozuvlar', now(), now(), TRUE
  FROM cash_cutover_legacy l;

UPDATE cash_transactions t
   SET handover_id = h.id
  FROM cash_handovers h
  JOIN cash_cutover_legacy l ON l.register_id = h.cash_register_id
 WHERE h.legacy AND h.business_date = current_date
   AND t.cash_register_id = h.cash_register_id
   AND t.handover_id IS NULL;

-- Tekshiruv
DO $$
DECLARE
    members_  bigint;
    directors bigint;
    left_     bigint;
BEGIN
    SELECT COUNT(*) INTO members_ FROM cash_register_members;
    SELECT COUNT(*) INTO directors FROM cash_registers WHERE kind = 'DIRECTOR';
    SELECT COUNT(*) INTO left_
      FROM cash_transactions t JOIN cash_registers r ON r.id = t.cash_register_id
     WHERE r.kind = 'ADMIN' AND t.handover_id IS NULL;
    IF directors = 0 THEN
        RAISE EXCEPTION 'cash-cutover: Direktor kassasi yaratilmadi';
    END IF;
    IF left_ > 0 THEN
        RAISE EXCEPTION 'cash-cutover: % ta eski yozuv legacy topshirishga bog''lanmadi', left_;
    END IF;
    RAISE NOTICE 'cash-cutover: a''zolar %, Direktor kassalari %', members_, directors;
END $$;

COMMIT;

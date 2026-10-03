-- O'quvchi holati (students.status) yozilmalardan — StudentStatusService.derive bilan AYNAN bir xil qoida.
--
-- Nima uchun: ilgari holatni faqat POST /api/groups/{g}/remove-student o'zgartirardi va faqat matnli
-- reason "LEFT"/"GRADUATED" bo'lsa; DELETE /groups/{g}/students/{s}, boshqa sabab, tahrirda guruh
-- almashtirish faol yozilmasiz ACTIVE o'quvchi qoldirardi. Teskarisi ham bor: bitta guruhdan "LEFT" bilan
-- chiqqan, boshqa guruhda o'qiyotgan o'quvchi LEFT bo'lib qolardi. Endi kod har yozilma amalidan keyin
-- holatni qayta hisoblaydi; bu skript mavjud qatorlarni bir marta tekislaydi.
--
-- Qoida (faqat kamida bitta yozilmasi bor o'quvchilar; hech qachon yozilmaganlarga tegilmaydi):
--   1. faol yozilma bor (is_active, frozen_from yo'q; sinov ham)         → ACTIVE
--   2. faol yo'q, muzlatilgani bor (frozen_from, yoki eski: nofaol + exit_reason = 'FROZEN') → FROZEN
--   3. ochiq yozilma yo'q: ARCHIVED / FINISHED / SUSPENDED — o'zgarmaydi (admin qo'ygan);
--      aks holda oxirgi yopilgan yozilma (COALESCE(leave_date, exit_date), updated_at, id) sababi:
--      exit_reason_code = GRADUATED yoki exit_reason = 'GRADUATED' → GRADUATED,
--      exit_reason = 'SUSPENDED' → SUSPENDED, qolgani → LEFT.
-- O'zgargan har o'quvchiga student_status_history qatori (reason = 'V73_SYNC'). students.updated_at ga
-- TEGILMAYDI — dashboard "yangi ketganlar" (updated_at bo'yicha) tarixi buzilmasin.
--
-- V72 dan KEYIN, QO'LDA, crm_user bilan. Idempotent: ikkinchi marta 0 qator o'zgaradi.
--
--   Ko'rib chiqish (hech narsa yozmaydi; 2-bo'lim "read-only transaction" xatosi bilan to'xtaydi — kutilgan):
--     PGOPTIONS='-c default_transaction_read_only=on' psql ... -X -v ON_ERROR_STOP=1 -f V73__student_status_from_enrollments.sql
--   Qo'llash:
--     psql ... -X -v ON_ERROR_STOP=1 -f V73__student_status_from_enrollments.sql
-- Natijani o'qish — 1a, 1b va 3-bo'lim izohlari.

-- ── 1. KO'RIB CHIQISH (faqat o'qiydi) ───────────────────────────────────────────────
-- 1a. Necha o'quvchi va qaysi holatdan qaysiga: har qator — (eski → yangi, soni).
--     Bo'sh natija = hammasi allaqachon mos, 2-bo'lim hech narsa o'zgartirmaydi.
WITH e AS (
    SELECT sg.*,
           (sg.is_active IS TRUE AND sg.frozen_from IS NULL) AS act,
           (sg.frozen_from IS NOT NULL OR (sg.is_active IS NOT TRUE AND sg.exit_reason = 'FROZEN')) AS frz
      FROM student_groups sg
), agg AS (
    SELECT student_id, bool_or(act) AS has_active, bool_or(frz) AS has_frozen,
           COUNT(*) FILTER (WHERE act) AS active_cnt, COUNT(*) FILTER (WHERE frz) AS frozen_cnt
      FROM e GROUP BY student_id
), last_closed AS (
    SELECT DISTINCT ON (student_id) student_id, group_id, exit_reason, exit_reason_code,
           COALESCE(leave_date, exit_date) AS closed_on
      FROM e WHERE NOT act AND NOT frz
     ORDER BY student_id, COALESCE(leave_date, exit_date) DESC NULLS LAST, updated_at DESC NULLS LAST, id DESC
), target AS (
    SELECT s.id, s.status AS from_status,
           CASE WHEN a.has_active THEN 'ACTIVE'
                WHEN a.has_frozen THEN 'FROZEN'
                WHEN s.status IN ('ARCHIVED', 'FINISHED', 'SUSPENDED') THEN s.status
                WHEN l.exit_reason_code = 'GRADUATED' OR UPPER(TRIM(l.exit_reason)) = 'GRADUATED' THEN 'GRADUATED'
                WHEN UPPER(TRIM(l.exit_reason)) = 'SUSPENDED' THEN 'SUSPENDED'
                ELSE 'LEFT' END AS to_status
      FROM students s
      JOIN agg a ON a.student_id = s.id
      LEFT JOIN last_closed l ON l.student_id = s.id
)
SELECT COALESCE(from_status, '(null)') AS eski_holat, to_status AS yangi_holat, COUNT(*) AS oquvchilar
  FROM target
 WHERE from_status IS DISTINCT FROM to_status
 GROUP BY from_status, to_status
 ORDER BY COUNT(*) DESC, 1, 2;

-- 1b. O'zgaradiganlar ro'yxati (tekshirish uchun): faol / muzlatilgan yozilmalar soni va oxirgi yopilgan
--     yozilma (guruh, sana, sabab). Masalan "LEFT → ACTIVE, faol 1" — LEFT deb belgilangan, lekin hali
--     guruhda; "ACTIVE → LEFT, faol 0, oxirgi sabab OTHER" — guruhdan chiqarilgan, holat yangilanmagan.
WITH e AS (
    SELECT sg.*,
           (sg.is_active IS TRUE AND sg.frozen_from IS NULL) AS act,
           (sg.frozen_from IS NOT NULL OR (sg.is_active IS NOT TRUE AND sg.exit_reason = 'FROZEN')) AS frz
      FROM student_groups sg
), agg AS (
    SELECT student_id, bool_or(act) AS has_active, bool_or(frz) AS has_frozen,
           COUNT(*) FILTER (WHERE act) AS active_cnt, COUNT(*) FILTER (WHERE frz) AS frozen_cnt
      FROM e GROUP BY student_id
), last_closed AS (
    SELECT DISTINCT ON (student_id) student_id, group_id, exit_reason, exit_reason_code,
           COALESCE(leave_date, exit_date) AS closed_on
      FROM e WHERE NOT act AND NOT frz
     ORDER BY student_id, COALESCE(leave_date, exit_date) DESC NULLS LAST, updated_at DESC NULLS LAST, id DESC
), target AS (
    SELECT s.id, s.status AS from_status,
           CASE WHEN a.has_active THEN 'ACTIVE'
                WHEN a.has_frozen THEN 'FROZEN'
                WHEN s.status IN ('ARCHIVED', 'FINISHED', 'SUSPENDED') THEN s.status
                WHEN l.exit_reason_code = 'GRADUATED' OR UPPER(TRIM(l.exit_reason)) = 'GRADUATED' THEN 'GRADUATED'
                WHEN UPPER(TRIM(l.exit_reason)) = 'SUSPENDED' THEN 'SUSPENDED'
                ELSE 'LEFT' END AS to_status
      FROM students s
      JOIN agg a ON a.student_id = s.id
      LEFT JOIN last_closed l ON l.student_id = s.id
)
SELECT t.id AS student_id, TRIM(COALESCE(s.first_name, '') || ' ' || COALESCE(s.last_name, '')) AS oquvchi,
       COALESCE(t.from_status, '(null)') AS eski_holat, t.to_status AS yangi_holat,
       a.active_cnt AS faol, a.frozen_cnt AS muzlatilgan,
       g.group_name AS oxirgi_yopilgan_guruh, l.closed_on AS yopilgan_sana,
       COALESCE(l.exit_reason_code, '') || CASE WHEN l.exit_reason IS NOT NULL THEN ' / ' || l.exit_reason ELSE '' END
           AS oxirgi_sabab
  FROM target t
  JOIN students s ON s.id = t.id
  JOIN agg a ON a.student_id = t.id
  LEFT JOIN last_closed l ON l.student_id = t.id
  LEFT JOIN groups g ON g.id = l.group_id
 WHERE t.from_status IS DISTINCT FROM t.to_status
 ORDER BY t.from_status, t.to_status, t.id;

-- ── 2. QO'LLASH ─────────────────────────────────────────────────────────────────────
-- "INSERT 0 N" — N = holati o'zgargan o'quvchilar (1a dagi sonlar yig'indisiga teng bo'lishi kerak).
BEGIN;

WITH e AS (
    SELECT sg.*,
           (sg.is_active IS TRUE AND sg.frozen_from IS NULL) AS act,
           (sg.frozen_from IS NOT NULL OR (sg.is_active IS NOT TRUE AND sg.exit_reason = 'FROZEN')) AS frz
      FROM student_groups sg
), agg AS (
    SELECT student_id, bool_or(act) AS has_active, bool_or(frz) AS has_frozen
      FROM e GROUP BY student_id
), last_closed AS (
    SELECT DISTINCT ON (student_id) student_id, exit_reason, exit_reason_code
      FROM e WHERE NOT act AND NOT frz
     ORDER BY student_id, COALESCE(leave_date, exit_date) DESC NULLS LAST, updated_at DESC NULLS LAST, id DESC
), target AS (
    SELECT s.id, s.status AS from_status,
           CASE WHEN a.has_active THEN 'ACTIVE'
                WHEN a.has_frozen THEN 'FROZEN'
                WHEN s.status IN ('ARCHIVED', 'FINISHED', 'SUSPENDED') THEN s.status
                WHEN l.exit_reason_code = 'GRADUATED' OR UPPER(TRIM(l.exit_reason)) = 'GRADUATED' THEN 'GRADUATED'
                WHEN UPPER(TRIM(l.exit_reason)) = 'SUSPENDED' THEN 'SUSPENDED'
                ELSE 'LEFT' END AS to_status
      FROM students s
      JOIN agg a ON a.student_id = s.id
      LEFT JOIN last_closed l ON l.student_id = s.id
), changed AS (
    UPDATE students s
       SET status = t.to_status
      FROM target t
     WHERE s.id = t.id
       AND s.status IS DISTINCT FROM t.to_status
    RETURNING s.id, t.from_status, t.to_status, s.balance
)
INSERT INTO student_status_history (student_id, from_status, to_status, reason, notes, balance_snapshot, changed_at)
SELECT id, from_status, to_status, 'V73_SYNC', 'V73: holat yozilmalardan qayta hisoblandi', balance,
       (now() AT TIME ZONE 'Asia/Tashkent')::timestamp
  FROM changed;

COMMIT;

-- ── 3. TEKSHIRUV ────────────────────────────────────────────────────────────────────
-- Qo'llangandan keyin 0 bo'lishi kerak (skriptni qayta ishga tushirsangiz ham 1a bo'sh chiqadi).
SELECT COUNT(*) AS mos_emas_qoldi
  FROM students s
 WHERE EXISTS (SELECT 1 FROM student_groups sg WHERE sg.student_id = s.id)
   AND ((EXISTS (SELECT 1 FROM student_groups sg
                  WHERE sg.student_id = s.id AND sg.is_active IS TRUE AND sg.frozen_from IS NULL)
         AND s.status IS DISTINCT FROM 'ACTIVE')
     OR (NOT EXISTS (SELECT 1 FROM student_groups sg
                      WHERE sg.student_id = s.id
                        AND (sg.is_active IS TRUE OR sg.frozen_from IS NOT NULL
                             OR sg.exit_reason = 'FROZEN'))
         AND s.status IN ('ACTIVE', 'FROZEN')));

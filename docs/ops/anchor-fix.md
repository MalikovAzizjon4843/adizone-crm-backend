# Langar (anchor) ≠ guruhga qo'shilgan sana — tahlil va tuzatish yo'li

> Buyurtmachi qoidasi R5 ([billing-v2.md §14.5](../design/billing-v2.md#145-r5--langar--guruhga-qoshilgan-sana-tolov-sanasi-langarga-tasir-qilmaydi)):
> hisob boshlanishi (`student_groups.payment_start_date`) = guruhga qo'shilgan sana. Sinovdan keyin to'lovli bo'lgan yozilmada —
> to'lovliga o'tkazilgan kun (`trial_converted_at`), chunki `join_date` sinov boshlangan kun.
>
> Kod **faqat yangi** yozilmalar uchun standartni o'zgartirdi. Mavjud qatorlar **avtomatik o'zgartirilmaydi** — bu hujjat
> nechta va qaysi yozilmalar farq qilishini ko'rsatadi va har toifa uchun tavsiya beradi. Hamma so'rov faqat o'qiydi.

```bash
PGOPTIONS='-c default_transaction_read_only=on' psql -h localhost -U crm_user -d adizone_crm -X -v ON_ERROR_STOP=1
```

## Toifalar

| Toifa | Qanday aniqlanadi | Langar qayerdan kelgan |
|---|---|---|
| `HOLD` | `billing_hold = true` | migratsiya qo'llanmagan (bloklovchi anomaliya yoki xato) — ledger/davrlar yo'q |
| `MIGRATED` | davrlaridan biri `migration_run_id IS NOT NULL` | eski `payment_start_date` (buyurtmachi dry-run'ni tasdiqlagan, §9) |
| `POST_CUTOVER` | qolganlari | v2 da yaratilgan: forma (`paymentStartDate` standarti = bugun edi), sinov to'lovi (langar = to'lov sanasi edi) |

"Kutilgan langar" = `COALESCE(trial_converted_at, join_date)`.
**Kech** — langar kutilgandan keyin (o'quvchi qo'shilgan kundan langargacha hisoblanmagan — kam olingan);
**erta** — oldin (qo'shilishdan oldingi davr ham hisoblangan — ortiqcha olingan bo'lishi mumkin).

## 1. Xulosa: toifa × holat × tur

```sql
WITH e AS (
    SELECT sg.*,
           COALESCE(sg.trial_converted_at, sg.join_date) AS expected_anchor,
           EXISTS (SELECT 1 FROM billing_periods p
                    WHERE p.student_group_id = sg.id AND p.migration_run_id IS NOT NULL) AS migrated
      FROM student_groups sg
     WHERE sg.is_trial IS NOT TRUE AND sg.payment_start_date IS NOT NULL
)
SELECT CASE WHEN billing_hold IS TRUE THEN 'HOLD' WHEN migrated THEN 'MIGRATED' ELSE 'POST_CUTOVER' END AS toifa,
       CASE WHEN frozen_from IS NOT NULL THEN 'MUZLATILGAN'
            WHEN is_active IS TRUE THEN 'FAOL' ELSE 'YOPILGAN' END AS holat,
       COALESCE(payment_type, 'MONTHLY') AS tur,
       COUNT(*) FILTER (WHERE payment_start_date > expected_anchor) AS langar_kech,
       COUNT(*) FILTER (WHERE payment_start_date < expected_anchor) AS langar_erta,
       COUNT(*) FILTER (WHERE payment_start_date = expected_anchor) AS mos,
       COUNT(*) AS jami
  FROM e
 GROUP BY 1, 2, 3
 ORDER BY 1, 2, 3;
```

**O'qish:** asosiy e'tibor — `FAOL` qatorlardagi `langar_kech` va `langar_erta`. `YOPILGAN` — faqat ma'lumot (tarix,
hisob-kitob yopilgan). PER_LESSON da langar faqat "shu sanadan oldingi darslar hisoblanmaydi" ma'nosida (§6.6).

## 2. Faol, farq qiladigan yozilmalar — ro'yxat

```sql
WITH e AS (
    SELECT sg.*,
           COALESCE(sg.trial_converted_at, sg.join_date) AS expected_anchor,
           EXISTS (SELECT 1 FROM billing_periods p
                    WHERE p.student_group_id = sg.id AND p.migration_run_id IS NOT NULL) AS migrated
      FROM student_groups sg
     WHERE sg.is_trial IS NOT TRUE AND sg.payment_start_date IS NOT NULL
       AND (sg.is_active IS TRUE OR sg.frozen_from IS NOT NULL)
)
SELECT e.id AS sg_id, s.id AS student_id,
       TRIM(COALESCE(s.first_name, '') || ' ' || COALESCE(s.last_name, '')) AS oquvchi,
       g.group_name AS guruh,
       CASE WHEN e.billing_hold IS TRUE THEN 'HOLD' WHEN e.migrated THEN 'MIGRATED' ELSE 'POST_CUTOVER' END AS toifa,
       COALESCE(e.payment_type, 'MONTHLY') AS tur,
       e.join_date, e.trial_converted_at, e.payment_start_date AS langar,
       e.payment_start_date - e.expected_anchor AS farq_kun,          -- > 0 kech, < 0 erta
       (SELECT COUNT(*) FROM billing_periods p WHERE p.student_group_id = e.id) AS davrlar,
       (SELECT MIN(p.period_start) FROM billing_periods p WHERE p.student_group_id = e.id) AS birinchi_davr,
       e.balance
  FROM e
  JOIN students s ON s.id = e.student_id
  JOIN groups g ON g.id = e.group_id
 WHERE e.payment_start_date <> e.expected_anchor
 ORDER BY toifa, ABS(e.payment_start_date - e.expected_anchor) DESC, e.id;
```

## 3. Xavfsiz tuzatiladiganlar — POST_CUTOVER, davri hali yo'q

Langarni orqaga surish oldingi davrlar bilan ustma-ust tushmasligi kerak (409 `billing.anchor.overlap`, §3.6) — davri yo'q
yozilmada bu cheklov yo'q.

```sql
SELECT sg.id AS sg_id, sg.student_id, sg.group_id, sg.join_date, sg.trial_converted_at,
       sg.payment_start_date AS langar, COALESCE(sg.trial_converted_at, sg.join_date) AS kutilgan
  FROM student_groups sg
 WHERE sg.is_trial IS NOT TRUE AND sg.is_active IS TRUE AND sg.frozen_from IS NULL
   AND sg.billing_hold IS NOT TRUE
   AND sg.payment_start_date IS NOT NULL
   AND sg.payment_start_date <> COALESCE(sg.trial_converted_at, sg.join_date)
   AND NOT EXISTS (SELECT 1 FROM billing_periods p WHERE p.student_group_id = sg.id)
 ORDER BY sg.id;
```

## 4. Tavsiya (har biri buyurtmachi tasdig'i bilan, qo'lda — kod hech narsani o'zgartirmaydi)

| Holat | Tavsiya | Qanday |
|---|---|---|
| POST_CUTOVER, davri yo'q (3-bo'lim) | Langarni kutilgan sanaga qo'yish | `PATCH /api/students/{id}/payment-start-date` `{groupId, paymentStartDate: kutilgan}` (SA/A). Kutilgan sana o'tgan bo'lsa, o'tgan davrlar darhol hisoblanadi va (to'lanmagan bo'lsa) o'quvchi R1 bo'yicha qarzdor bo'ladi — oldindan xabar bering |
| POST_CUTOVER, davrlar bor, langar **kech** | Odatda o'zgartirmaslik (hisoblanmagan kunlar — markaz zarari, mijozdan qo'shimcha talab qilinmaydi); kerak bo'lsa yakka tartibda | Orqaga surish 409 beradi. Faqat SA: ortiqcha qism uchun `MANUAL_ADJUST` (sabab bilan) yoki davrni qaytarib (`PERIOD_REFUND`) keyin PATCH — har holat alohida ko'riladi |
| POST_CUTOVER, langar **erta** | Ro'yxatni egasi ko'rib chiqadi: qo'shilishdan oldingi davr olingan bo'lsa — qaytarim | SA `MANUAL_ADJUST` (kredit, sabab bilan); langar o'zgartirilmaydi |
| MIGRATED | O'zgartirmaslik (langar eski tizimdan, dry-run'ni buyurtmachi tasdiqlagan, §13 #20). Faqat egasi alohida ko'rsatgan yozilmalar | Yuqoridagi POST_CUTOVER qatorlari kabi, yakka tartibda |
| HOLD | **Eng arzon nuqta** — migratsiya qo'llanishidan oldin langar to'g'rilanadi: planer langarni `payment_start_date` dan oladi (`MigrationPlanner`, §9) | Egasi bilan kelishib `payment_start_date` ni kutilgan sanaga qo'yish (hold'dagi SG da ledger yo'q), so'ng `dry-run` → `apply-sg` (billing-v2-migration.md) |
| YOPILGAN | Tegmaslik | — |

## 5. Xulosa

- Yangi yozilmalarda farq endi paydo bo'lmasligi kerak: standart `paymentStartDate = joinDate`, sinov to'lovida langar = o'tkazilgan kun.
  Farq faqat formada qo'lda boshqa sana kiritilsa qoladi (ruxsat etilgan — masalan chegirma boshlanishi bilan kelishilgan).
- Mavjud farqlar uchun avtomatik migratsiya **yozilmadi**: davri bor yozilmada langarni orqaga surish ledger tuzatishini
  talab qiladi va har birida pul summasi o'zgaradi — buni faqat egasi yakka tartibda hal qiladi.
- Ish tartibi: 1-bo'lim (soni) → 2-bo'lim (ro'yxat egasiga) → 3-bo'lim (xavfsizlari PATCH bilan) → qolganlari 4-bo'lim jadvali bo'yicha.

## 6. Kalendar QAROR 1 (langar kuni 29–31) — keyingi davr qanday o'zgaradi

[billing-v2.md §14.7](../design/billing-v2.md#147-billing-kuni-2931--qaror-1-04102026): keyingi davr = langar kuni (oyda
yo'q bo'lsa oy oxiri; ilgari 29–31 → doim oy oxiri). Keyingi davr **boshi** o'zgarmaydi — u oxirgi yozilgan davrdan keyin
(`oxirgi period_end + 1`); o'zgaradi — shu davrning **oxiri** va undan keyingi sanalar. Langar kuni 31 bo'lganlarda ikkala qoida
bir xil (farq 0). Faqat o'qiydi; hech narsa o'zgartirilmaydi (kod ham avtomatik ravishda faqat keyingi davrlarga qo'llaydi).

```sql
WITH a AS (
    SELECT sg.id, sg.student_id, sg.group_id, sg.payment_start_date AS langar,
           EXTRACT(DAY FROM sg.payment_start_date)::int AS kun,
           (SELECT MAX(p.period_end) FROM billing_periods p
             WHERE p.student_group_id = sg.id AND p.period_start >= sg.payment_start_date) AS oxirgi_oxir
      FROM student_groups sg
     WHERE sg.is_active IS TRUE AND sg.frozen_from IS NULL AND sg.is_trial IS NOT TRUE
       AND COALESCE(sg.payment_type, 'MONTHLY') = 'MONTHLY'
       AND sg.payment_start_date IS NOT NULL
       AND EXTRACT(DAY FROM sg.payment_start_date) >= 29
), n AS (
    SELECT a.*, COALESCE(a.oxirgi_oxir + 1, a.langar) AS keyingi_bosh FROM a
), m AS (
    SELECT n.*,
           date_trunc('month', n.keyingi_bosh)::date                       AS m0,
           (date_trunc('month', n.keyingi_bosh) + INTERVAL '1 month')::date AS m1
      FROM n
), c AS (
    SELECT m.*,
           -- yangi: shu yoki keyingi oyda min(kun, oy uzunligi), keyingi_bosh dan keyin
           make_date(EXTRACT(YEAR FROM m0)::int, EXTRACT(MONTH FROM m0)::int,
                     LEAST(kun, EXTRACT(DAY FROM (m0 + INTERVAL '1 month' - INTERVAL '1 day'))::int)) AS yangi0,
           make_date(EXTRACT(YEAR FROM m1)::int, EXTRACT(MONTH FROM m1)::int,
                     LEAST(kun, EXTRACT(DAY FROM (m1 + INTERVAL '1 month' - INTERVAL '1 day'))::int)) AS yangi1,
           -- eski: oy oxiri
           (m0 + INTERVAL '1 month' - INTERVAL '1 day')::date                                     AS eski0,
           (m1 + INTERVAL '1 month' - INTERVAL '1 day')::date                                     AS eski1
      FROM m
)
SELECT c.id AS sg_id, c.student_id,
       TRIM(COALESCE(s.first_name, '') || ' ' || COALESCE(s.last_name, '')) AS oquvchi,
       g.group_name AS guruh, c.langar, c.kun, c.oxirgi_oxir, c.keyingi_bosh,
       (CASE WHEN c.eski0 > c.keyingi_bosh THEN c.eski0 ELSE c.eski1 END) - 1   AS eski_qoida_davr_oxiri,
       (CASE WHEN c.yangi0 > c.keyingi_bosh THEN c.yangi0 ELSE c.yangi1 END) - 1 AS yangi_qoida_davr_oxiri,
       (CASE WHEN c.yangi0 > c.keyingi_bosh THEN c.yangi0 ELSE c.yangi1 END)
         - (CASE WHEN c.eski0 > c.keyingi_bosh THEN c.eski0 ELSE c.eski1 END) AS farq_kun   -- < 0: keyingi to'lov ertaroq
  FROM c
  JOIN students s ON s.id = c.student_id
  JOIN groups g ON g.id = c.group_id
 ORDER BY farq_kun, c.id;
```

**O'qish:**
- `keyingi_bosh` — navbatdagi yoziladigan davr boshi (ikkala qoidada bir xil, ustma-ust yo'q).
- `yangi_qoida_davr_oxiri` — shu davr endi qachon tugaydi; keyingi to'lov (navbatdagi davr) = shu sana + 1.
- `farq_kun` — keyingi to'lov sanasi eski qoidaga nisbatan necha kun siljiydi (manfiy — ertaroq; masalan langar 29.08,
  `keyingi_bosh = 31.10`: eski 30.11, yangi 29.11 → −1). Langar 31 — 0.
- `oxirgi_oxir` bo'sh — yozilmada hali davr yo'q, birinchi davr langardan boshlanadi; keyingilari darhol yangi qoida bo'yicha.

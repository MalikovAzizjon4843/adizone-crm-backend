# Billing v2 — dry-run hisoboti namunasi (test ma'lumoti)

> ⚠ **Bu haqiqiy hisobot emas.** `MigrationTest.writeSampleDryRunReport` testi H2 xotira bazasida sun'iy ma'lumot yaratib `POST /api/admin/billing/migration/dry-run` dagi `MigrationPlanner.dryRun` ni chaqirgan. Migratsiya hech qaysi haqiqiy bazada bajarilmagan. Xlsx shakli — `target/billing-v2-dry-run-sample.xlsx` (`mvn test` dan keyin).

**Parametrlar:** T = 2026-10-05, G = 2026-09-18, A14 payable = false, max(balance_transactions.id) = 184, max(payments.id) = 21  
**reportHash:** `b6496012c8f3541d4a42badb4e05ea240002ca459504ebaf035f7ff272195032`

Ma'lumot: SG 1–7 — §9.3 jadvalining 7 qatori; SG 8–24 — har anomaliya kodi uchun bittadan holat; SG 25 — COMPLETED guruhdagi faol SG. Barchasida fee 700 000 (A1 bundan mustasno).

## SG qatorlari

| SG | Toifa | start | R | Davr C/M | L | MIGRATION | Charges | Target | Eski → Yangi | debtSince | Next / summa | Anomaliya |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 700 000 | 700 000 | 0 | PENDING → PAID | — | 2026-10-20 / 700 000 |  |
| 2 | M | 2026-09-22 | 2026-09-22 | 1/0 | 0 | 0 | 700 000 | -700 000 | PENDING → OVERDUE | 2026-09-22 | 2026-09-22 / 700 000 |  |
| 3 | M | 2026-09-18 | 2026-09-18 | 1/0 | 0 | 1 400 000 | 700 000 | 700 000 | PENDING → PAID | — | 2026-11-18 / 700 000 |  |
| 4 | M | 2026-09-25 | 2026-09-25 | 1/0 | 300 000 | 0 | 700 000 | -400 000 | PENDING → OVERDUE | 2026-09-25 | 2026-09-25 / 400 000 |  |
| 5 | M | 2026-07-01 | 2026-09-01 | 2/2 | 0 | 0 | 1 400 000 | -1 400 000 | PENDING → OVERDUE | 2026-09-01 | 2026-09-01 / 1 400 000 | A12 |
| 6 | M | 2026-08-01 | 2026-09-01 | 2/1 | 0 | 700 000 | 1 400 000 | -700 000 | PENDING → OVERDUE | 2026-10-01 | 2026-10-01 / 700 000 | A5 |
| 7 | M | 2026-09-15 | 2026-09-15 | 1/0 | 0 | 630 000 | 700 000 | -70 000 | PENDING → OVERDUE | 2026-09-15 | 2026-09-15 / 70 000 | A14, A7 |
| 8 | M | 2026-09-20 | 2026-09-20 | 0/0 | 0 | 0 | 0 | 0 | PENDING → PAID | — | — | A1 |
| 9 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 0 | 700 000 | -700 000 | PENDING → OVERDUE | 2026-09-20 | 2026-09-20 / 700 000 | A2 |
| 10 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 700 000 | 700 000 | 0 | PENDING → PAID | — | 2026-10-20 / 700 000 | A3 |
| 11 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 0 | 700 000 | -700 000 | PENDING → OVERDUE | 2026-09-20 | 2026-09-20 / 700 000 | A4 ⛔ |
| 12 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 0 | 700 000 | -700 000 | PENDING → OVERDUE | 2026-09-20 | 2026-09-20 / 700 000 | A4 ⛔ |
| 13 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 0 | 700 000 | -700 000 | PENDING → OVERDUE | 2026-09-20 | 2026-09-20 / 700 000 | A5 |
| 14 | T | 2026-09-20 | — | 0/0 | 0 | 0 | 0 | 0 | PENDING → TRIAL | — | — | A6 |
| 15 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 0 | 700 000 | -700 000 | PENDING → OVERDUE | 2026-09-20 | 2026-09-20 / 700 000 | A7 |
| 16 | M | 2026-09-20 | 2026-09-20 | 1/0 | 700 000 | -700 000 | 700 000 | -700 000 | PENDING → PENDING | 2026-10-05 | 2026-10-05 / 700 000 | A12, A8 |
| 17 | M | 2026-09-20 | 2026-09-20 | 1/0 | 100 000 | 0 | 700 000 | -600 000 | PENDING → OVERDUE | 2026-09-20 | 2026-09-20 / 600 000 | A9 |
| 18 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 0 | 700 000 | -700 000 | PENDING → OVERDUE | 2026-09-20 | 2026-09-20 / 700 000 | A10 |
| 19 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 0 | 700 000 | -700 000 | PENDING → OVERDUE | 2026-09-20 | 2026-09-20 / 700 000 | A11 |
| 20 | M | 2026-07-01 | 2026-08-01 | 3/1 | 0 | 0 | 2 100 000 | -2 100 000 | PENDING → OVERDUE | 2026-08-01 | 2026-08-01 / 2 100 000 | A12 |
| 21 | L | 2026-10-01 | — | 0/0 | -80 000 | 0 | 0 | -80 000 | PENDING → OVERDUE | 2026-09-28 | 2026-09-28 / 80 000 | A13 |
| 22 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 650 000 | 700 000 | -50 000 | PENDING → OVERDUE | 2026-09-20 | 2026-09-20 / 50 000 | A14 |
| 23 | M | 2026-09-20 | 2026-09-20 | 1/0 | 0 | 0 | 700 000 | -700 000 | PENDING → OVERDUE | 2026-09-20 | 2026-09-20 / 700 000 | A15 |
| 24 | M | 2024-01-01 | 2024-01-01 | 24/0 | 0 | 0 | 16 800 000 | -16 800 000 | PENDING → OVERDUE | 2024-01-01 | 2024-01-01 / 16 800 000 | A12, A16 ⛔ |
| 25 | M | 2026-09-20 | 2026-09-20 | 0/0 | 0 | 0 | 0 | 0 | PENDING → PAID | — | — |  |

⛔ — bloklovchi anomaliya (A4, A16): apply bu SG larni yozmaydi, `billing_hold` qiladi.

## Jami

| Ko'rsatkich | Qiymat |
|---|---|
| SG jami | 25 |
| Toifalar | L: 1, M: 23, T: 1 |
| Bloklovchi | 3 |
| Qarzdorlar (eski / yangi) | 0 / 17 |
| Σ qarz (eski / yangi) | 80 000 / 29 200 000 |
| Σ balans (eski / yangi) | 1 020 000 / -28 500 000 |
| Davrlar CHARGED / MIGRATED | 48 / 4 |
| Yoziladigan PERIOD_CHARGE / MIGRATION yozuvlari | 48 / 7 |
| Anomaliyalar | A1×1, A10×1, A11×1, A12×4, A13×1, A14×2, A15×1, A16×1, A2×1, A3×1, A4×2, A5×2, A6×1, A7×2, A8×1, A9×1 |
| Holat o'tishlari | PENDING→OVERDUE: 18, PENDING→PAID: 5, PENDING→PENDING: 1, PENDING→TRIAL: 1 |

**Izohlar:**
- "Eski holat" test fixture'ining saqlangan qiymati (PENDING). Prodda bu ustun haqiqiy eski holatni ko'rsatadi.
- SG 7 (§9.3 7-qator): yangi holat OVERDUE. Dizayndagi PENDING noto'g'ri edi, chunki debtSince 15.09 va 20 kun o'tgan.
- SG 7 `a14UsePayable=true` bilan: target 0, PAID.
- A8 qatori (SG 16): `[ledger-repair]` krediti neytrallanganda `MIGRATION` manfiy bo'ladi va T sanasi bilan debet bo'lib tushadi (debtSince = T). Bu qator qo'lda ko'riladi.

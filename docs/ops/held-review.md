# Hold rejasi: ikki marta hisoblash — sabab, tuzatish, migratsiya qilinganlar tekshiruvi (2026-10-04)

Bog'liq: [billing-v2 §9.2, §9.7.1](../design/billing-v2.md), [held-review.sql](held-review.sql).

## 1. Sabab

`GET /api/admin/billing/held` rejasi bulk migratsiya formulasini ishlatardi (`MigrationPlanner.plan`):

```
l         = Σ balance_transactions.amount (SG bo'yicha, barcha yozuvlar)
legacyPC  = Σ |PERIOD_CHARGE| (billing_period_id IS NULL, migration_run_id IS NULL)   -- v1 debetlari
MIGRATION = legacyPC − ledger-repair                                                    -- ularni neytrallaydi
c_n       = fee; A14 tasdiqlangan run'da: chegirmali to'lov qoplagan davr uchun c_n = o'sha to'lovning payable'i
target    = l + MIGRATION − Σ c_n
```

v1 `PaymentService.writeLedgerForPayment` bitta to'lov uchun yozgan: `PAYMENT = naqd (gross − chegirma − balansdan)` va
**bitta** `PERIOD_CHARGE = −(oylar × fee − chegirma)` (oylar ≥ 1 bo'lsa; payable 0 bo'lsa debet yo'q). Ikki xato:

1. **A14 ko'p oylik to'lovda** — `payableByPeriod` to'lovning BUTUN payable'ini (masalan 2 oy uchun 1 000 001) bitta davrga
   (`gridFloor(periodStart)`) qo'yadi → 1-davr `amount > fee`. To'lov qoplagan qolgan oylar alohida yana `fee` bilan
   hisoblanadi. v1 debeti (1 000 001) MIGRATION bilan neytrallanadi, lekin uning o'rniga yozilgan v2 debetlar
   `1 000 001 + (oylar − 1) × fee` — qarz **(oylar − 1) × fee** ga oshadi.
2. **100% chegirma** (naqd 0, v1 debet yo'q) — ledger'da hech narsa yo'q, payable 0 bo'lgani uchun A14 ham ishlamaydi;
   davr to'liq `fee` bilan hisoblanadi, chegirma kredit bo'lmaydi → qarz `fee` ga oshadi.

Umumiy ildiz: reja to'lov pulini **ledger'dan** (`l`) o'qiydi va faqat v1 PERIOD_CHARGE ni neytrallaydi; v1 debeti
ko'p oyni bitta yozuvda qamragani va chegirma kredit sifatida yozilmagani hisobga olinmaydi.

### Misollar (fee 700 000, T = cutover, 03.10 ≤ T < 29.10). Ledger qanday o'qilgan

| SG | Ledger (v1) | Bulk reja | Natija | Sof |
|---|---|---|---|---|
| 4 (langar 03.08, to'lovlar 2 100 000) | PAYMENT +1 000 001, PERIOD_CHARGE −1 000 001 (1 400 000 gross, chegirma 399 999, 2 oy); PAYMENT +700 000 (debetsiz) → `l = 700 000` = balanceBefore | legacyPC = MIGRATION = 1 000 001; davrlar 03.08 = **1 000 001** (A14), 03.09 = 03.10 = 700 000 → Σc = 2 400 001 | target = 700 000 + 1 000 001 − 2 400 001 = **−700 000** | 2 100 000 − 3 × 700 000 = **0** |
| 6 (29.08, 1 400 000) | PAYMENT +P, PERIOD_CHARGE −P (2 oylik chegirmali to'lov, P = payable) → `l = 0` | MIGRATION = P; 29.08 = P (A14), 29.09 = 700 000 | target = P − P − 700 000 = **−700 000** | 1 400 000 − 2 × 700 000 = **0** |
| 46 (01.09, 700 000) | 100% chegirma: naqd 0 — debet ham, kredit ham yo'q → `l = 0`, legacyPC = 0 | MIGRATION 0; 01.09, 01.10 = 700 000 | target = **−1 400 000** | 700 000 − 2 × 700 000 = **−700 000** |
| 47 (01.06, 2 800 000) | PAYMENT +P, PERIOD_CHARGE −P (4 oylik to'lov) → `l = 0` | MIGRATION = P; 01.06 = P (A14), 01.07–01.10 = 4 × 700 000 | target = P − P − 2 800 000 = **−2 800 000** | 2 800 000 − 5 × 700 000 = **−700 000** |

Prod raqamlari (sg 4: 1-davr 1 000 001, MIGRATION 1 000 001, balanceBefore 700 000; sg 46: MIGRATION 0, balanceBefore 0)
aynan shu o'qishga mos. sg 6/47 uchun aniq P ni §2 dagi SQL ko'rsatadi (`legacy_pc`, `max_over_fee`).
A12 (|target − l| > fee) shu sababli hammasida chiqqan. Testlar: `HeldEnrollmentTest#heldPlan_isNet_prodExamples`
(bulk reja prod raqamlarini takrorlaydi, hold rejasi — sof).

## 2. Tuzatish (kod)

Hold'dagi SG (`/api/admin/billing/held*` va `POST /api/admin/billing/migration/apply-sg`) endi **sof hisob** bilan
(`MigrationPlanner.Options.heldNet`, billing-v2 §9.7.1):

```
davrlar  = R..T, har biri c_n = fee (A14 almashtirish yo'q)
paidNet  = Σ PAID to'lovlar (amount [gross] − balance_used)
kept     = BONUS, PENALTY, REFUND_PAYOUT, TRANSFER_IN/OUT, MANUAL_ADJUST (ta'mir emas), mavjud v2 davr yozuvlari
target   = paidNet + kept − Σ c_n
yoziladi: davrlar + PERIOD_CHARGE; MIGRATION = −Σ neytral oila (v1 PERIOD_CHARGE, ta'mir, eski MIGRATION);
          MANUAL_ADJUST "[held-net] ..." = paidNet + kept − (l − neytral)  (migration_run_id bilan, revert-sg qaytaradi)
```

Held qatorida yangi maydonlar: `paidNet`, `otherLedger`, `netAdjustment`. Bulk dry-run/apply va uning hash'i o'zgarmagan.

## 3. Migratsiya qilingan 57 ta SG — tekshiruv (FAQAT O'QIYDI)

Bulk apply AYNAN shu formulani ishlatgan — A14 tasdiqlangan run'da ko'p oylik chegirmali to'lovi yoki 100% chegirmasi bor
har qanday migratsiya qilingan SG da xuddi shu ortiqcha qarz bo'lishi mumkin. Qolganlarida (chegirmasiz yoki bir oylik
chegirmali to'lov, v1 ledger to'liq) bulk va sof natija bir xil.

**So'rov:** [held-review.sql](held-review.sql) (bitta SELECT, hech narsa yozmaydi; oxirgi qo'llangan run, boshqasi uchun
`run` CTE dagi WHERE ni o'zgartiring). PG testi `HeldEnrollmentTest#heldReviewSql_findsOverchargedMigrated` faylni
aynan o'qib bajaradi.

```bash
psql "$PROD_RO_URL" -v ON_ERROR_STOP=1 -f docs/ops/held-review.sql
```

Ustunlar: `target_old` — apply paytidagi balans (dry-run'gacha ledger + run yozuvlari), `target_net` — sof hisob,
`delta = target_old − target_net` (**manfiy — o'quvchiga ortiqcha qarz yozilgan**), `charges_old`/`charges_net`,
`a14_periods`, `max_over_fee` (> 0 — davr narxi fee'dan katta), `paid_net`, `ledger_credits`, `legacy_pc`, `kept`,
`late_apply_sg` (run'dan keyin apply-sg bilan yozilgan — taqqoslash taxminiy), `balance_now`, `reason`:

| reason | Ma'nosi |
|---|---|
| `OK` | farq yo'q |
| `A14_MULTI` | ko'p oylik to'lov payable'i bitta davrda (§1, xato 1) |
| `DISCOUNT_UNCREDITED` | chegirma kredit bo'lmagan (100% yoki A14 siz run) (§1, xato 2) |
| `LEDGER_CREDITS_MISMATCH` | v1 ledger'dagi PAYMENT/DISCOUNT ≠ to'lovlar jadvali (yo'qolgan/bog'lanmagan kredit) |
| `OTHER` | qo'lda ko'rish |

Jamlanma:

```sql
SELECT reason, COUNT(*) AS sg, SUM(delta) AS delta_sum
FROM (/* held-review.sql matni */) r
GROUP BY reason ORDER BY reason;
```

Cheklovlar: `kept` REVERSAL'larni hisobga olmaydi (v1 da kam); `balance_used` li to'lovlar (A3) — sof hisob ularni chiqaradi,
balans boshqa yozilmadan kelgan bo'lsa qo'lda ko'ring.

### Tuzatish yo'li (egasi tasdig'idan keyin, alohida qaror)

- `delta < 0` SG lar uchun eng xavfsizi — `POST /api/students/{id}/balance-adjust` (SA, `groupId` bilan, `MANUAL_ADJUST` +|delta|, izohda
  "held-review: run #N, sg #M, sabab A14_MULTI"). Davrlar va to'lov tarixi o'zgarmaydi, FIFO qarzni qayta hisoblaydi.
- `revert-sg` + held apply faqat SG da run'dan keyingi v2 davr bo'lmasa ishlaydi (aks holda `migration.sgAlreadyMigrated`) —
  T dan keyin accrual bo'lgan SG lar uchun yaramaydi.
- Bu hujjat hech narsa yozmaydi; tuzatish skripti/endpointi yo'q.

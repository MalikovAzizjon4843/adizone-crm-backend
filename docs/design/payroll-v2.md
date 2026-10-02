# Payroll v2 — oylik hisobi (dizayn hujjati)

> **Holat:** amalga oshirildi (02.10.2026, `billing-v2` branch). Frontend uchun API —
> [`payroll-v2-api.md`](payroll-v2-api.md).
> Asos: `docs/audit/backend-audit.md` §6.C, §9.12 #19–#21; `docs/design/billing-v2.md`
> (§3.3 `billing_periods`, §4.1 FIFO, §13 #27); director-dashboard §3.2 (`paid_on`).

## 0. Muammo → yechim

| Audit / muammo | v2 |
|---|---|
| #19 `generate overwrite=true` APPLIED bonuslarni yo'qotadi | Bonus faqat **APPROVE** da APPLIED bo'ladi; DRAFT uni faqat ko'rsatadi (§4) |
| #19 `deletePayroll` bonus/kassani qaytarmaydi | DELETE faqat DRAFT — qaytaradigan narsa yo'q; PAID → faqat **cancel** (REVERSAL) (§5.3) |
| #19 `PUT` upsert, ixtiyoriy `status` string ("PAID" yozilsa kassa yozilmaydi) | `PUT`, `POST /` olib tashlandi; holat faqat amallar orqali (§1) |
| #19 `generate` oyda bitta payroll bo'lsa yangi xodimni qo'sha olmaydi | `generate` yetishmayotganlarga DRAFT yaratadi; mavjud DRAFT faqat `recalculate=true` bilan (§1.2) |
| #20 o'qituvchi — **hozirgi** `group.teacher` | `billing_periods.teacher_id` — accrual paytidagi o'qituvchi (§2.1, §8) |
| #20 ADMIN KPI — **hozirgi** ACTIVE soni | oy oxirida hisob davri bor o'quvchilar soni (§2.3) |
| #20 `cashAmount=0` to'lov ham "to'lagan" | "to'lagan" = FIFO bo'yicha yopilgan davr (`paid_on`), to'lov yozuvi emas (§2.1) |
| #20 yangi o'quvchi — `MIN(id)` to'lov | birinchi davri yopilgan oy (§2.2) |
| `calculationDetails` — JSON **string** | tuzilgan obyekt: `lines[]` + `items` (§6) |
| Frontend `fixedSalary/perPayingStudent/perNewStudent`, backend `baseSalary/perStudentFee/newStudentBonus` — qoidalar 0 bo'lib saqlanardi | yagona nomlar = frontend nomlari; eski nom → 400 (§7) |

## 1. Holatlar

```
            generate / recalculate (faqat DRAFT ga tegadi)
                 │
   (yo'q) ──► DRAFT ──approve──► APPROVED ──pay──► PAID
                 │                  │                │
              DELETE             cancel (SA)      cancel (SA, kassaga REVERSAL)
                 ▼                  ▼                ▼
              (o'chadi)         CANCELLED         CANCELLED
```

| O'tish | Kim | Shart | Ta'siri |
|---|---|---|---|
| → DRAFT (`generate`) | SA, A, ACC | shu oy uchun faol (CANCELLED bo'lmagan) payroll yo'q | hisob saqlanadi; bonuslar PENDING qoladi |
| DRAFT → DRAFT (`generate?recalculate=true`, `POST /{id}/recalculate`) | SA, A, ACC | DRAFT | hisob qayta yoziladi |
| DRAFT → APPROVED | SA, A | DRAFT, `net ≥ 0` | PENDING bonuslar (qulf ostida) → APPLIED; summa muzlaydi |
| APPROVED → PAID | SA, A, ACC | APPROVED | kassaga EXPENSE (kassa berilsa), `paidAt` |
| APPROVED/PAID → CANCELLED | **faqat SA** | sabab 3–500 belgi | bonuslar → PENDING; PAID bo'lsa kassaga REVERSAL |
| DELETE | SA, A | **faqat DRAFT** | yozuv o'chadi (bonusga tegilmaydi — u PENDING) |

- **APPROVED va PAID hech qachon qayta yozilmaydi** — hech qaysi amal ularning summasini o'zgartirmaydi.
- `status` — enum `PayrollStatus {DRAFT, APPROVED, PAID, CANCELLED}`. Bazadagi eski `PENDING` o'qishda
  DRAFT deb olinadi (converter), V54 uni DRAFT ga yozib qo'yadi.
- Bitta xodim + oy uchun **faol** payroll bitta: `UNIQUE (user_id, month, year) WHERE status <> 'CANCELLED'`.
  CANCELLED dan keyin `generate` yangi DRAFT yaratadi.

### 1.2 `generate(month, year, recalculate=false)`
Har hisoblanadigan xodim uchun (`calculable=true`):

| Mavjud faol payroll | `recalculate=false` | `recalculate=true` |
|---|---|---|
| yo'q | DRAFT yaratiladi (`created`) | DRAFT yaratiladi |
| DRAFT | o'zgarmaydi (`skipped: DRAFT_EXISTS`) | qayta hisoblanadi (`recalculated`) |
| APPROVED / PAID | o'zgarmaydi (`skipped: APPROVED`/`PAID`) | o'zgarmaydi |

`calculable=false` (qoida yo'q, profil bog'lanmagan) → `skipped` ro'yxatida sababi bilan.
Eski `overwrite` parametri va "Bu oy uchun oylik yaratilgan" bloki olib tashlandi.

## 2. Formulalar

Davr: `[from, to]` = kalendar oy. Qoida: `resolveRule(user, to)` (o'zgarmagan: shaxsiy → rol, `effectiveFrom ≤ to`).

```
TEACHER:        gross = fixedSalary + perPayingStudent × (paidPeriods + lessonEnrollments)
ADMIN:          gross = fixedSalary + perNewStudent × newStudents + (activeAtMonthEnd ≥ kpiThreshold ? kpiBonus : 0)
SALES_MANAGER:  gross = fixedSalary + perNewStudent × newStudents
net = gross + Σ BONUS − Σ PENALTY        (TEACHER — BonusTargetType TEACHER; ADMIN/SALES — STAFF, §11 #5)
```

### 2.1 "To'lagan o'quvchi" (TEACHER)
**MONTHLY — `paidPeriods`:** `billing_periods` qatorlari, shartlari:
- `teacher_id = o'qituvchi` (§8 — accrual paytidagi o'qituvchi);
- yig'iladigan: `status ∈ {CHARGED, PARTIALLY_REFUNDED}`, `charge_tx_id IS NOT NULL`, `amount − refunded_amount > 0`
  (to'liq qaytarilgan / migratsiya / 0 so'mlik davr sanalmaydi);
- `paid_on IS NOT NULL` va **`max(paid_on, period_start) ∈ [from, to]`**.

Birlik — **davr** (bitta davr bir marta, yopilgan oyida haq beradi). Qarzni kech to'lagan o'quvchining
ikki davri bir oyda yopilsa — 2 birlik (har oy uchun haq bir marta, kechikib).

Nega `max(paid_on, period_start)`: FIFO oldindan to'lovni kelajak davrga ham yozadi (`paid_on` = to'lov
sanasi, davrdan oldin). `paid_on` ning o'zi olinsa, sentyabrda 3 oy oldindan to'lagan o'quvchining
oktyabr/noyabr davrlari ular hisoblangandan keyin **sentyabr** hisobiga qo'shilib ketardi — o'tgan oy
qayta hisoblanganda natija o'zgarardi (qaror 7). `max` bilan davr o'z oyida sanaladi.

Bekor qilingan to'lov: snapshot `paid_on` ni qayta hisoblaydi (davr ochiladi) — sanalmaydi.
**Real to'lov sharti (§11 #7):** davrni yopishda FIFO bo'yicha pul bergan kreditlar orasida kamida bitta `PAYMENT`
bo'lishi shart (`PeriodCoverageService.paymentCoveredCharges`); faqat DISCOUNT/BONUS bilan yopilgani sanalmaydi
(`app.payroll.count-discount-covered=true` — sanaladi).

**PER_LESSON — `lessonEnrollments`:** `balance_transactions` dagi `LESSON_CHARGE` lar:
`teacher_id = o'qituvchi`, `effective_date ∈ [from, to]`, teskari yozuvlar (`LESSON_REFUND`, `REVERSAL`,
`related_tx_id` orqali) bilan **net < 0**. Birlik — **yozilma (SG) oyiga bir**, o'qituvchilar orasida darslar
ulushida bo'linadi (§11 #2): `share = o'qituvchining net darslari / SG ning oydagi barcha net darslari`
(masalan 2 va 1 dars → 0.6667 va 0.3333). `amount = Money.uzs(perPayingStudent × Σ birliklar)`.

### 2.2 "Yangi o'quvchi" (ADMIN, SALES_MANAGER)
`student.attributed_user_id = xodim` bo'lgan o'quvchilar, shundan:
1. o'quvchining **birinchi real to'langan** (§11 #7) yig'iladigan davri (barcha SG lari bo'yicha `(period_start, id)` eng kichigi)
   uchun `max(paid_on, period_start) ∈ [from, to]`;
2. yig'iladigan MONTHLY davri umuman bo'lmasa (faqat PER_LESSON) — birinchi `PAID`, `cash_amount > 0`
   to'lov `(payment_date, id)` bo'yicha shu oyda.

`MIN(id)` emas: orqaga sanalab kiritilgan to'lov o'z sanasi bo'yicha sanaladi.

### 2.3 ADMIN KPI — `activeAtMonthEnd`
Oy oxirida (`to`) hisob davri bor o'quvchilar (DISTINCT): `period_start ≤ to ≤ period_end`,
`status ∈ {CHARGED, PARTIALLY_REFUNDED, MIGRATED, PREPAID_LEGACY}` + oyda net `LESSON_CHARGE` i bor
PER_LESSON o'quvchilar. Hozirgi `student.status` ishlatilmaydi.

## 3. Determinizm (qaror 7)

Hisob faqat **o'zgarmaydigan tarixiy** ma'lumotdan: `billing_periods` (teacher_id, period_start, paid_on),
ledger (`LESSON_CHARGE.teacher_id`, effective_date), to'lov sanasi, `attributed_user_id`, bonus `effective_date`.
Ishlatilmaydi: `group.teacher` (hozirgi), `student.status`, `LocalDate.now()`.

Natijani o'zgartira oladigan narsalar — faqat **ma'lumotni tuzatish**:
- to'lov bekor qilinishi (`paid_on` qayta hisoblanadi; billing-v2 §13 #23 — faqat 31 kun ichida);
- davomat o'zgarishi (`LESSON_REFUND`);
- qoida tahriri — faqat APPROVED/PAID da ishlatilmagan qoidada (§11 #3; aks holda yangi qoida + `effectiveTo`),
  xodim nofaol qilinishi (`generate` uni o'tkazib yuboradi).

Shuning uchun APPROVED/PAID payroll **muzlatilgan snapshot**: qoida nusxasi va barcha qatorlar
`calculationDetails` da saqlanadi, qayta hisoblanmaydi.

## 4. Bonus / jarima (qaror 3)

| Payroll amali | Bonus (TEACHER yoki STAFF, `effective_date ≤ oy oxiri`) |
|---|---|
| DRAFT yaratish / qayta hisoblash | PENDING qoladi; `calculationDetails.lines` da `BONUS`/`PENALTY` qatori, `items.bonuses[].status = PENDING` ("kutilmoqda"); `net` ularni o'z ichiga oladi |
| DRAFT o'chirish | hech narsa — bonuslar PENDING |
| APPROVE | qulf ostida qayta o'qiladi: shu paytdagi PENDING lar → APPLIED, `applied_to_payroll_id`; `net` shu ro'yxat bo'yicha |
| CANCEL (APPROVED/PAID) | `applied_to_payroll_id = id` bo'lganlar → PENDING, `applied_to_payroll_id = NULL` (keyingi payroll oladi) |

- Bitta PENDING bonus bir necha DRAFT da ko'rinishi mumkin (masalan sentyabr va oktyabr) — qaysi biri
  birinchi tasdiqlansa, o'sha oladi. Ikkinchisi tasdiqlanganda qulf ostida qayta o'qiladi va olinmaydi.
- Oylikka qo'llangan bonusni `/api/bonus-penalties/{id}/cancel` bekor qilmaydi
  (`bonus.cancel.teacherApplied`) — avval payroll bekor qilinadi.

## 5. Kassa, qulf, idempotentlik

### 5.1 Qulf tartibi
`BillingLocks` (billing-v2 §7.2) ga **Payroll** qo'shildi:
`Student → StudentGroup → Payment → Payroll → BonusPenalty → CashRegister`.
approve: payroll + bonuslar; pay: payroll + kassa; cancel: payroll + bonuslar + kassa.

### 5.2 `pay` (APPROVED → PAID)
```
lock(payroll)
if PAID:   idempotencyKey == payroll.payIdempotencyKey → o'sha javob (yangi yozuv yo'q)
           aks holda 409 payroll.alreadyPaid
if ≠ APPROVED → 409 payroll.notApproved
if net > 0 && cashRegisterId:  CashTransaction(EXPENSE, amount = net, payroll_id, teacher) — qulf ostida
payroll: PAID, paidAt, paidBy, paymentDate = bugun (billing soati), cashTransactionId, payIdempotencyKey
```
Kalit: `Idempotency-Key` sarlavhasi yoki body `idempotencyKey` (≤ 64). `cashRegisterId` siz to'lov
(bank o'tkazmasi) — kassa yozuvisiz PAID. Kassa manfiyga tushishi mumkin (avvalgidek).

### 5.3 `cancel` (SA)
- PAID va `cash_transaction_id` bor → `CashTransaction(REVERSAL, related_tx_id = asl, payroll_id)`;
  EXPENSE teskarisi kassa chelaklariga **qo'shadi** (`recordReversal` endi INCOME/EXPENSE ni ajratadi).
- Eski (v1) PAID: `cash_transaction_id` yo'q, kassasi bor → izoh `Oylik to'lovi (m/y)` bo'yicha **bitta**
  chiqim topilsa, o'sha teskari yoziladi; topilmasa / bir nechta → 400 `payroll.cancel.legacyCash`.
- Audit: `PAYMENT_CANCEL` (sabab bilan). Boshqa amallar: generate `CREATE`, recalculate `UPDATE`,
  approve `STATUS_CHANGE`, pay `PAYMENT`, delete `DELETE`.

## 6. `calculationDetails` (qaror 8)

```json
{
  "version": 2,
  "month": 9, "year": 2026, "role": "TEACHER",
  "rule": { "id": 4, "scope": "PERSONAL", "role": "TEACHER", "effectiveFrom": "2026-01-01",
            "fixedSalary": 3000000, "perPayingStudent": 50000, "perNewStudent": 0,
            "kpiThreshold": null, "kpiBonus": 0 },
  "lines": [
    { "code": "FIXED",              "label": "Belgilangan oylik",  "base": 3000000, "count": 1,  "amount": 3000000 },
    { "code": "PER_PAYING_STUDENT", "label": "To'lagan o'quvchi",  "base": 50000,   "count": 12, "amount": 600000 },
    { "code": "BONUS",              "label": "Bonuslar",           "base": null,    "count": 2,  "amount": 200000, "status": "PENDING" },
    { "code": "PENALTY",            "label": "Jarimalar",          "base": null,    "count": 1,  "amount": -50000, "status": "PENDING" }
  ],
  "gross": 3600000, "bonusPenalty": 150000, "net": 3750000,
  "items": {
    "paidPeriods":       [ { "periodId": 1, "studentGroupId": 7, "studentId": 3, "studentName": "...", "groupId": 2,
                             "groupName": "...", "periodStart": "2026-09-15", "paidOn": "2026-09-16", "countedOn": "2026-09-16" } ],
    "lessonEnrollments": [ { "studentGroupId": 9, "studentId": 5, "studentName": "...", "groupId": 4, "groupName": "...", "lessons": 6 } ],
    "newStudents":       [ { "studentId": 3, "studentName": "...", "countedOn": "2026-09-16", "source": "PERIOD" } ],
    "kpi":               { "threshold": 100, "actual": 112, "applied": true },
    "bonuses":           [ { "id": 11, "kind": "BONUS", "amount": 100000, "effectiveDate": "2026-09-10",
                             "reason": "...", "status": "PENDING" } ]
  }
}
```

- `lines[]` — har qator `{code, label, base, count, amount}` (+ ixtiyoriy `status`). `Σ lines.amount = net`.
- `count` — son (BigDecimal): `PER_PAYING_STUDENT` da kasr bo'lishi mumkin (§11 #2, masalan `1.6667`).
  `estimated: true` — cutover oyi (§11 #1). `rule.effectiveTo` — qoida muddati (§11 #3).
- Kodlar: `FIXED`, `PER_PAYING_STUDENT`, `PER_NEW_STUDENT`, `KPI`, `BONUS`, `PENALTY`. Nol qatorlar ham
  yoziladi (FIXED, rol formulasi qatorlari); bonus/jarima qatori faqat yozuv bo'lsa.
- Bazada `payroll.calculation_details` (TEXT) da shu JSON; javobda **obyekt**. v1 yozuvlarining eski
  JSON i o'zgarmay obyekt bo'lib qaytadi (`version` yo'q).
- Payroll ustunlari (ro'yxat uchun): `basicSalary = FIXED`, `allowances = gross − FIXED`,
  `deductions = 0`, `bonusPenaltyAdjustment = bonusPenalty`, `netSalary = net`, `paidStudentCount`,
  `newStudentCount`, `kpiApplied`, `kpiAmount`.

## 7. SalaryRule (qaror 9)

Yakuniy nomlar (so'rov = javob): `role`, `userId`, `fixedSalary`, `perPayingStudent`, `perNewStudent`,
`kpiThreshold`, `kpiBonus`, `effectiveFrom`, `isActive`. Bazadagi ustunlar o'zgarmaydi
(`base_salary`, `per_student_fee`, `new_student_bonus`).

- Noma'lum maydon (shu jumladan eski `baseSalary`, `perStudentFee`, `newStudentBonus`) → 400
  `salaryRule.field.unknown` — jimgina 0 saqlanmasin.
- `role ∈ {TEACHER, ADMIN, SALES_MANAGER}`; `userId` berilsa uning roli = `role`.
- Rolga tegishli bo'lmagan maydon nol emas → 400 `salaryRule.field.notApplicable`
  (TEACHER: `perNewStudent`, KPI; SALES: `perPayingStudent`, KPI; ADMIN: `perPayingStudent`).
- Summalar ≥ 0, `kpiThreshold ≥ 0`. Hammasi faqat SA (`GET /{id}` qo'shildi).
- `effectiveTo` (§11 #3): yangi qoida oldingisini `effectiveFrom − 1` da yopadi; APPROVED/PAID oylikda ishlatilgan
  qoidani `PUT` → 409 `salaryRule.inUse`.

## 8. O'qituvchi atributsiyasi (qaror 5)

| Jadval | Ustun | Kim yozadi |
|---|---|---|
| `billing_periods` | `teacher_id`, `teacher_source` | `AccrualService` — davr yozilgan paytdagi `group.teacher` (`LIVE`); migratsiya davrlari — `ESTIMATED` |
| `balance_transactions` | `teacher_id`, `teacher_source` | `LessonChargeService` — `LESSON_CHARGE` yozilgan paytdagi `group.teacher` (`LIVE`) |

O'qituvchi keyin almashsa, yozilgan davr/dars eski o'qituvchida qoladi. V54 mavjud qatorlarni hozirgi
`g.teacher_id` bilan to'ldiradi, `teacher_source = 'ESTIMATED'` ("taxminiy"). Ledgerning pul
maydonlariga tegilmaydi (I3 — faqat atribut metama'lumot).

## 9. API o'zgarishlari (qisqa)

| Endpoint | v2 |
|---|---|
| `GET /api/payroll` | + `month`, `year`, `userId` filtrlari; `status` = enum |
| `POST /api/payroll/generate` | `recalculate` (query yoki body); `overwrite` olib tashlandi; javob `{created, recalculated, skipped[], totalAmount}` |
| `POST /api/payroll/{id}/recalculate` | yangi (DRAFT) |
| `POST /api/payroll/{id}/approve` | yangi (SA, A) |
| `POST /api/payroll/{id}/pay` | APPROVED shart; `Idempotency-Key` |
| `POST /api/payroll/{id}/cancel` | yangi (SA), `{reason}` |
| `DELETE /api/payroll/{id}` | faqat DRAFT |
| `POST /api/payroll`, `PUT /api/payroll/{id}` | **olib tashlandi** (405) |
| `GET /api/payroll/calculate[/{userId}]` | `details`/`students` o'rniga `calculationDetails` (§6) |
| `/api/salary-rules` | yakuniy nomlar (§7), `GET /{id}`, `effectiveTo`, 409 `salaryRule.inUse` (§11 #3) |
| `POST /api/payroll/{id}/approve` body | `{expectedNetSalary}` → 409 `payroll.netChanged` + `data.netSalary` (§11 #4) |
| `/api/bonus-penalties` | `targetType=STAFF` + `userId`; `GET /preview/staff/{userId}`; filtr `userId` (§11 #5) |
| `GET /api/finance/report` | + `payrollPaid`, `payrollByRole`; `netProfit` oylikni ayiradi (§11 #8) |

Hamma payroll endpointlarida `@PreAuthorize` (avval GET larda yo'q edi).

## 10. Sxema — `V54__payroll_v2.sql` (qo'lda, idempotent)

1. `billing_periods.teacher_id/teacher_source` + backfill (`ESTIMATED`) + indeks `(teacher_id, paid_on)`.
2. `balance_transactions.teacher_id/teacher_source` + `LESSON_CHARGE` backfill + qisman indeks.
3. `payroll`: `approved_at/by`, `paid_at/by`, `cancelled_at/by`, `cancel_reason`, `cash_transaction_id`,
   `pay_idempotency_key`, `calc_version`; `PENDING → DRAFT`; eski PAID uchun `paid_at = payment_date`.
4. `uk_payroll_user_month_year` → `uk_payroll_user_month_year_active` (`WHERE status <> 'CANCELLED'`).
5. `cash_transactions.payroll_id`; v1 PAID payroll ↔ yagona mos chiqim bog'lanadi (izoh bo'yicha).
6. Bonuslar: DRAFT payroll ga yoki o'chirilgan payroll ga APPLIED bo'lib qolgan TEACHER bonuslari →
   PENDING (#19: "yo'qolgan" bonuslar qaytadi).

**`V55__payroll_v2_decisions.sql`** (§11 qarorlari): `salary_rules.effective_to`; `payroll.salary_rule_id`
(v2 yozuvlari uchun `calculation_details.rule.id` dan) va `payroll.paid_student_units`; `bonus_penalties.user_id`
(STAFF); indekslar.

Tartib: V52 → V53 → **V54 → V55 → V56 → V57** (§12, §13) → yangi jar. `ddl-auto: update` ustunlarni o'zi qo'shadi, lekin backfill,
`PENDING → DRAFT`, qisman UNIQUE va bonus tuzatishi faqat V54/V55 da. pgtest ikkalasini har ishga tushishda bajaradi.

## 11. Ochiq savollar va qarorlar

Har savol uchun taklif berilgan edi. **02.10.2026 da egasi qarorlari olindi** — har savol ostida "Qaror:" qatori;
amalga oshirilgan (`V55__payroll_v2_decisions.sql`, testlar `PayrollOwnerDecisionsTest`).

1. **v2 dan oldingi oylar.** Billing v2 cutover'dan oldingi oylarda `billing_periods` yo'q (yoki MIGRATED) —
   ularni v2 bilan qayta hisoblash 0 "to'lagan" beradi. *Taklif:* cutover oyidan oldingi oylar uchun
   `generate` ga ruxsat bermaslik (400 `payroll.beforeCutover`) yoki faqat mavjud PAID larni ko'rsatish.
   **Qaror:** cutover oyidan OLDINGI oylar uchun `generate`/`calculate`/`recalculate` → 400 `payroll.beforeCutover`;
   cutover oyi — ruxsat, `calculationDetails.estimated = true`. Cutover sanasi: `app.payroll.cutover-date`, berilmasa —
   qo'llangan (`APPLIED`/`APPLIED_WITH_ERRORS`) billing migratsiyasining eng erta `cutover_date` i; ikkalasi ham
   bo'lmasa — cheklov yo'q.
2. **O'qituvchi oy o'rtasida almashgan PER_LESSON SG** — ikkalasi ham 1 birlik oladi. *Taklif:* shunday
   qoldirish (oddiy); muqobil — darslar ulushi bo'yicha (6/10 va 4/10).
   **Qaror:** `LESSON_CHARGE` soni ulushida bo'linadi: `share = o'qituvchining net darslari / SG ning oydagi barcha
   net darslari`. `PER_PAYING_STUDENT.count` kasr bo'lishi mumkin (ko'rsatish 4 xona), `amount = Money.uzs(base × units)`
   (aniq kasr bilan, bir marta yaxlitlash). `paidStudentUnits` — birliklar, `paidStudentCount` — elementlar soni.
3. **Qoida tahriri o'tgan oylarga ta'sir qiladi** (DRAFT qayta hisoblanganda). *Taklif:* `PUT` o'rniga
   "yangi qoida + effectiveFrom" ni majburlash va `effectiveTo` qo'shish (o'chirilgan qoida o'tgan oylarda
   ishlayversin). APPROVED/PAID ga ta'siri yo'q (snapshot).
   **Qaror:** `effectiveTo` qo'shildi (`resolveRule`: `effectiveFrom ≤ oy oxiri ≤ effectiveTo`). `PUT` — qoida
   APPROVED/PAID oylikda ishlatilgan bo'lsa (`payroll.salary_rule_id`) 409 `salaryRule.inUse` ("yangi qoida yarating").
   Yangi qoida `effectiveFrom` bilan yaratilsa, shu doiradagi (shu xodim yoki shu rolning umumiy) oldingi faol qoidaga
   `effectiveTo = yangi.effectiveFrom − 1 kun`. `effectiveTo < effectiveFrom` → 400 `salaryRule.effectiveRange.invalid`.
   **To'ldirish (02.10.2026):** yangi qoidaning `effectiveFrom` i shu doiradagi faol qoidaning `effectiveFrom` idan
   kichik yoki teng bo'lsa (masalan ikkalasi 02.10), oldingisi yopilmaydi (aks holda `effectiveTo < effectiveFrom`):
   APPROVED/PAID oylikda ishlatilmagan bo'lsa nofaol qilinadi (`isActive=false`, `effectiveTo=null`), ishlatilgan bo'lsa —
   409 `salaryRule.overlapsUsed`. Lokal bazadagi shunday qoidalarni `V57__salary_rule_overlaps.sql` tuzatadi (§13).
4. **APPROVE paytida bonus ro'yxati DRAFT dagidan farq qilishi mumkin** (orada yangi bonus kiritilsa).
   *Taklif:* frontend approve dan oldin `recalculate` chaqiradi; xohlansa `expectedNetSalary` tekshiruvi
   (farq bo'lsa 409) qo'shiladi.
   **Qaror:** approve body `{expectedNetSalary}` — berilsa va qulf ostidagi yakuniy `net` dan farq qilsa → 409
   `payroll.netChanged`, javobda `data.netSalary` (yangi), `data.expectedNetSalary`, `data.bonusPenaltyAdjustment`;
   hech narsa yozilmaydi. Berilmasa — avvalgidek.
5. **ADMIN/SALES uchun qo'lda tuzatish** (bonus/jarima faqat TEACHER ga). *Taklif:* `BonusTargetType.STAFF`
   (user bo'yicha) — keyingi bosqich.
   **Qaror:** `BonusTargetType.STAFF` (`userId`, roli ADMIN yoki SALES_MANAGER) — TEACHER bilan aynan bir xil oqim:
   DRAFT da PENDING ("kutilmoqda"), approve da APPLIED (`applied_to_payroll_id`), cancel da PENDING ga qaytadi.
6. **APPROVED → DRAFT ("qayta ochish")** yo'q — CANCELLED + `generate`. *Taklif:* shunday qoldirish
   (audit izi aniq).
   **Qaror:** o'zgarishsiz.
7. **Bonus/chegirma bilan yopilgan davr "to'langan"** sanaladi (§2.1). *Taklif:* qabul; muqobil — faqat
   `PAYMENT` krediti yopgan davr.
   **Qaror:** davr faqat yopilishida real `PAYMENT` krediti ishtirok etgan bo'lsa "to'lagan" (FIFO taqsimotida shu davrga
   pul bergan kreditlar orasida kamida bitta PAYMENT; PAYMENT + DISCOUNT — sanaladi, faqat DISCOUNT/BONUS — yo'q).
   Sozlama `app.payroll.count-discount-covered` (default `false`; `true` — avvalgi xulq). Yangi o'quvchi ham shu
   qoida bilan: birinchi *real to'langan* davri oyida.
8. **Payroll to'lovi `Expense(SALARY)` va moliya hisobotiga tushmaydi** (audit #22). *Taklif:* alohida vazifa.
   **Qaror:** `GET /api/finance/report` ga `payrollPaid` (Σ PAID `netSalary`, `paidAt` davr ichida, CANCELLED kirmaydi)
   va `payrollByRole`; `netProfit = totalIncome − totalExpenses − payrollPaid`. Oylik kassadan EXPENSE
   (`CashTransaction`) sifatida ham yoziladi, lekin `totalExpenses` faqat `Expense` jadvalidan — ikki marta ayirilmaydi.

## 12. Eski sxema auditi va `V56__legacy_constraints.sql` (02.10.2026)

**Sabab (BUG 1).** Lokal `adizone` bazasida (prod'ga yaqin) bekor qilingan oktyabr oyligidan keyin `generate` →
500: `duplicate key payroll_teacher_id_month_year_key (teacher_id, month, year)=(1,10,2026)`. V54 faqat
`uk_payroll_user_month_year` indeksini o'chirgan; eski qo'lda migratsiyadagi `UNIQUE (teacher_id, month, year)` qolgan.
pgtest buni ushlamagan — uning sxemasi entity'dan quriladi, eski cheklovlar yo'q.

**Audit usuli.** `pg_constraint` (u/c/f), `pg_indexes` (UNIQUE), NOT NULL ustunlar va enum-ustun uzunliklari —
11 jadval: payroll, payments, balance_transactions, student_groups, bonus_penalties, cash_transactions, salary_rules,
billing_periods, leads, tasks, lead_stages. Faqat o'qish (`default_transaction_read_only=on`).

| Jadval | Cheklov (lokal nom) | Ta'rif | v2 bilan | Qaror |
|---|---|---|---|---|
| payroll | `payroll_teacher_id_month_year_key` | UNIQUE (teacher_id, month, year) | **zid** — CANCELLED dan keyin yangi DRAFT (§1) | V56: DROP → `uk_payroll_teacher_month_year_active` (… WHERE teacher_id IS NOT NULL AND status <> 'CANCELLED') |
| payroll | `uk5duwev7ya2dq0c0q71wfbloyu` | UNIQUE (teacher_id, month, year) — Hibernate qoldig'i (entity'da endi yo'q) | **zid** (xuddi shu) | V56: DROP (ustunlar bo'yicha topiladi, nomga bog'liq emas) |
| payroll | `uk_payroll_user_month_year_active` | qisman UNIQUE (user_id, month, year) WHERE status <> 'CANCELLED' | mos (V54) | — |
| payroll | `payroll_teacher_id_fkey` | FK teacher_id → teachers **ON DELETE CASCADE** | **xavfli** — o'qituvchi o'chirilsa PAID oyliklar o'chadi, kassa `payroll_id` yetim qoladi | V56: CASCADE FK DROP; NO ACTION FK (`fki2aa…`) qoladi |
| payroll | `payroll_uuid_key`, `uk_mnw2…` | UNIQUE (uuid) ×2 | dublikat, zararsiz | xavfsiz |
| payroll | `fk…`, `payroll_*_by_fkey` | FK user/created_by/approved_by/paid_by/cancelled_by/cash_register | mos | xavfsiz |
| student_groups | `student_groups_student_id_group_id_join_date_key` | UNIQUE (student_id, group_id, join_date) | **zid** — v2 da yopilgan SG o'chirilmaydi (ledger, I3); shu guruhga shu sana bilan qayta qo'shish / bir kunda qaytgan transfer yangi SG yaratadi → 500 | V56: DROP. Yangi cheklov **qo'yilmaydi**: "bitta faol SG" qoidasini kod ta'minlaydi (`addStudentToGroup`), eski faol dublikatlar billing migratsiyasida A4 anomaliyasi sifatida hold qilinadi (billing-v2 §9) — qisman UNIQUE indeks ularni ifodalab bo'lmas qilardi (`MigrationTest` A4 ssenariysi) |
| student_groups | `student_groups_*_fkey` | FK student/group **ON DELETE CASCADE** | ledger FK (NO ACTION) o'chirishni baribir to'xtatadi; ilovada qattiq o'chirish yo'q | xavfsiz |
| payments | `uk_j4pd…` (idempotency_key), `uk_u6rn…` (receipt_number) | UNIQUE | mos (V52 §7.3, §5.3) | — |
| payments | `payments_uuid_key`, `uk_hm20…` | UNIQUE (uuid) ×2 | dublikat | xavfsiz |
| payments | `payments_student_id_fkey` RESTRICT, `payments_group_id_fkey` / `student_group_id_fkey` / `received_by_fkey` SET NULL + Hibernate NO ACTION juftlari | FK | ledger va SG o'chirilmaydi; ilovada qattiq o'chirish yo'q | xavfsiz |
| balance_transactions | 3 × FK (NO ACTION) | student, student_group, created_by | mos — append-only ledger | — |
| billing_periods | `uk_billing_periods_sg_start` | UNIQUE (student_group_id, period_start) | mos (I4) | — |
| bonus_penalties | `uk_2k60…` (uuid), FK teacher/user/student/created_by | | mos (V55 `user_id` FK bor) | — |
| cash_transactions | `uk_fgn0…` (uuid), FK register/teacher/student/target/created_by | | mos (`payment_id`, `payroll_id` — FK siz, ataylab) | — |
| salary_rules | FK user_id | | mos | — |
| leads | `leads_uuid_key` + `uk_lpea…` (uuid ×2), `uk_58qg…` (meta_leadgen_id), FK ×5 (SET NULL + NO ACTION juftlari) | | NULL lar ko'p bo'lishi mumkin (PG) — mos | xavfsiz |
| tasks | `uk_8xvl…` (uuid), FK ×5 | | mos | — |
| lead_stages | `uk_4miu…` UNIQUE (code), `uk_721k…` (uuid) | | mos (V53 kod bo'yicha) | — |
| hammasi | CHECK | — | `EnumCheckConstraintCleaner` allaqachon olib tashlagan (lokal bazada 0 ta) | — |
| hammasi | NOT NULL | payroll: faqat uuid/month/year/created_at; bonus_penalties: teacher/student/user NOT NULL emas | STAFF bonusi va ADMIN/SALES oyligi (teacher_id NULL) yoziladi | xavfsiz |
| hammasi | enum ustun uzunligi | `payroll.status` 20, `cash_transactions.type` 20, `bonus_penalties.target_type` 20, `balance_transactions.type` 30 | CANCELLED, REVERSAL, STAFF, REFUND_PAYOUT sig'adi | xavfsiz |

V56 ning lokal bazadagi quruq tekshiruvi (aniqlash so'rovlari, faqat o'qish): olib tashlanadi — 4 ta
(`payroll_teacher_id_month_year_key`, `uk5duwev7ya2dq0c0q71wfbloyu`, `payroll_teacher_id_fkey`,
`student_groups_student_id_group_id_join_date_key`); faol (teacher, oy) dublikatlari — 0, ya'ni `uk_payroll_teacher_month_year_active` muammosiz yaratiladi.

**Generate izolyatsiyasi.** Har xodim alohida tranzaksiyada (`REQUIRES_NEW`): baza xatosi bo'lsa o'sha xodim
`skipped` ga `reason = ERROR`, `code = DB_CONSTRAINT` (yoki `UNEXPECTED`) va sabab matni bilan tushadi; qolganlari
saqlanadi, javob 200.

**BUG 3 — "O'qituvchi profili bog'lanmagan".** Lokal bazada TEACHER rolidagi 4 user (19–22) bor, `teachers` da
esa bitta qator (`#1 → user 19`). 20, 21, 22 userlarda **Teacher qatori umuman yo'q** (telefon/email/ism bo'yicha
mos egasiz profil ham yo'q). Eski `POST /api/admin/repair/link-teacher-users` faqat *egasiz profillarni* userga
bog'lardi va `remainingUnlinked` = egasiz *profillar* sonini qaytarardi — profili yo'q *userlarni* ko'rmasdi, shuning
uchun `linkedCount 0, remainingUnlinked 0`. Oylik esa ta'rifni user tomondan oladi (`teachers.user_id = user.id`).
Tuzatish: repair endi 2-qadam sifatida profili yo'q TEACHER userlarga `ensureTeacherProfile` (sync-from-users bilan
bir xil qoida) bilan profil yaratadi va har qator uchun `action` + `reason` qaytaradi; oylikdagi xabar ajratildi —
`TEACHER_PROFILE_MISSING` ("profil yo'q") va `TEACHER_PROFILE_LINKED_TO_OTHER_USER` ("profil boshqa userga
bog'langan").

Tartib: V52 → V53 → V54 → V55 → **V56** → V57 (§13) → yangi jar.

## 13. Qoida ustma-ustligi (`V57__salary_rule_overlaps.sql`) va kassa yo'nalishi (02.10.2026)

**Qoidalar.** Lokal bazada bir doirada (TEACHER, umumiy) ikkita faol qoida — #1 va #2, ikkalasi `02.10.2026 – 31.12.2026`:
#2 yaratilganda #1 yopilmagan (yopish so'rovi faqat `effectiveFrom < yangi.effectiveFrom` ni olardi). Endi ilova
shu sanadan yoki keyin boshlanadigan faol qoidani ishlatilmagan bo'lsa nofaol qiladi, ishlatilgan bo'lsa 409
`salaryRule.overlapsUsed` beradi (§11 #3 to'ldirish).

V57 (qo'lda, idempotent; faqat APPROVED/PAID da ishlatilmagan qoidalar):
1. teskari muddat (`effective_to < effective_from`) → `is_active = false, effective_to = NULL`;
2. ustma-ust: shu doirada keyin yaratilgan (id katta) faol qoida xuddi shu yoki oldinroq sanadan → eskisi
   `is_active = false, effective_to = NULL` (eng yangisi qoladi);
3. ishlatilgani uchun tegilmaganlar — `RAISE NOTICE` ro'yxati (qo'lda ko'riladi).

Lokal bazada quruq tekshiruv (faqat o'qish): o'zgaradi — #1 (ustma-ust, faqat CANCELLED oylikda ishlatilgan).

**Kassa yo'nalishi.** `CashTransactionDto` ga `direction` (IN/OUT), `signedAmount`, `paymentId`, `payrollId`,
`relatedTxId`; Excel eksportida "Yo'nalish" ustuni va ishorali "Summa (±)". REVERSAL yo'nalishi asl yozuvdan
(`relatedTxId`) olinadi — bekor qilingan oylik (EXPENSE teskarisi) IN, bekor qilingan to'lov OUT. Transfer'ning
yangi kirim qatori `relatedTxId` bilan chiqim qatoriga bog'lanadi (eskilari nom bo'yicha aniqlanadi).

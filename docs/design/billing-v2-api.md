# Billing v2 — API shartnomasi (o'zgarishlar)

> Dizayn: `docs/design/billing-v2.md` (§10.2). Bu hujjat — **amalda qurilgan** holat (branch `billing-v2`).
> Dizayndan farq qilgan joylar **⚠ Farq** bilan belgilangan.
> Barcha javoblar avvalgidek `ApiResponse {success, message, data, meta?}` ichida.
> Xatolarda yangi maydon bor: `ErrorResponse.code` (masalan `payment.cancel.tooOld`). Matn `Accept-Language` (uz/ru/en) bo'yicha keladi.

## 0. Umumiy

| Mavzu | Qoida |
|---|---|
| Summalar | UZS, butun so'm. Foydalanuvchi kiritgan kasrli summa → 400 `money.wholeSumRequired`. |
| Belgi | Ledgerda `amount > 0` — o'quvchi foydasiga (kredit), `< 0` — majburiyat. |
| Billing o'chirilgan | `app.billing.enabled=false` (cutover oynasi) → yozish amallari 503 `billing.maintenance`; o'qish ishlaydi. |
| Qulf band | Parallel amal 5 soniyadan ko'p kutsa → 409 `concurrency.busy`. |
| Holat | `paymentStatus` ∈ `PAID, PENDING, OVERDUE, TRIAL, FROZEN` — ledgerdan hosila (§4.2), grace 3 kun. |

## 1. To'lovlar — `/api/payments`

### `POST /api/payments` — to'lov (SA, A, ACC)
**Sarlavha:** `Idempotency-Key: <uuid>` (ixtiyoriy, ≤ 64 belgi; tavsiya etiladi).

**Request** (`PaymentRequest`, mavjud maydonlar saqlangan):

| Maydon | v1 | v2 |
|---|---|---|
| `studentId` | majburiy | majburiy |
| `groupId` | ixtiyoriy | O'quvchida > 1 ochiq SG bo'lsa **majburiy** → 400 `payment.group.required`. Guruhda emas → 404 `payment.enrollment.notFound`. |
| `amount` | ≥ 0 | > 0, butun |
| `cashRegisterId` | ixtiyoriy | **majburiy** (kassaga tushadigan qism > 0 bo'lsa) → 400 `payment.cashRegister.required` / `notFound` / `archived` / `onlineNotAccepted` |
| `discountAmount` | har kim | Faqat **SA, A** (ACC → 403 `payment.discount.forbidden`). Sabab majburiy (`discountReason`, bo'lmasa `notes`) → 400 `payment.discount.reasonRequired`. Ledgerda `DISCOUNT` krediti. |
| `discountReason` | — | **yangi** |
| `applyBonuses` | default true | default true. PENDING STUDENT bonus/jarimalar `BONUS`/`PENALTY` sifatida yoziladi. |
| `paymentDate` | — | ≤ bugun (400 `payment.date.future`) |
| `paymentMethod`, `cashPart`, `cardPart` | — | `CASH_AND_CARD`: qismlar yig'indisi summaga teng bo'lishi kerak (400 `payment.split.mismatch`) |
| `periodFrom`, `periodTo`, `useBalance`, `balanceAmount` | ishlatilgan | **e'tiborsiz** (deprecated) |
| `expectedPlanHash` | — | **yangi**: preview'dagi `planHash`. Mos kelmasa → 409 `payment.plan.changed`. |

**Javob:**
- Yangi to'lov — **201**.
- Shu kalit bilan takroriy so'rov — **200** va `X-Idempotent-Replay: true` (o'sha to'lov qaytadi).
- Shu kalit, boshqa body — 409 `payment.idempotency.mismatch`.

`PaymentResponse` — eski maydonlar saqlangan, quyidagilar qo'shilgan:
```
studentGroupId, lines[] (BillingLineDto), balanceAfter, debtAfter, statusAfter,
nextPaymentDate, nextPaymentAmount, planHash, warnings[],
cancelledAt, cancelledByName, cancelReason, reversalLines[]   (bekor qilinganda)
```
`BillingLineDto = {type, amount, effectiveDate, note, bonusPenaltyId?, pending?, ledgerTxId?}`.

Qiymatlardagi o'zgarishlar:
- `periodFrom/periodTo = null`, `balanceUsed = 0`;
- `bonusDiscount` = Σ BONUS − Σ PENALTY.

Sinovdagi o'quvchi to'lasa, avtomatik to'lovliga o'tadi: `paymentStartDate = to'lov sanasi`, darhol `PERIOD_CHARGE` yoziladi (§13 #8).

### `POST /api/payments/preview` (SA, A, ACC)
- **Request:** create bilan **bir xil** `PaymentRequest` (⚠ v1 da `PaymentPreviewRequest` edi; eski maydon nomlari shu DTO da bor).
- **Javob** (`PaymentPreviewResponse`):
  - eski maydonlar saqlangan: `gross, discount, payable, balanceUsed (=0), cashAmount, studentBalance, balanceAfter`;
  - yangi maydonlar: `studentGroupId, groupName, lines[]` — hali yozilmagan accrual `pending: true` bilan; `balanceBefore, debtBefore, debtAfter, statusAfter, nextPaymentDateAfter, nextPaymentAmountAfter, convertsTrial, planHash, warnings[]`.
- Kafolat: `preview.lines == create.lines` va `planHash` teng (test `preview_equalsCreate_withBonuses`).

### `POST /api/payments/{id}/cancel` — **yangi** (faqat SA)
- **Body:** `{"reason": "3..500 belgi"}`.
- **Xatolar:**

  | Holat | Javob |
  |---|---|
  | `reason` yo'q yoki qisqa | 400 `payment.cancel.reasonRequired` |
  | allaqachon bekor qilingan | 409 `payment.alreadyCancelled` |
  | `paymentDate < bugun − 31` | 400 `payment.cancel.tooOld` (§13 #23 — SA `balance-adjust` bilan tuzatadi) |
  | to'lov topilmadi | 404 `payment.notFound` |

- **Amallar:**
  1. har bog'langan ledger yozuviga (PAYMENT, DISCOUNT, BONUS, PENALTY) `REVERSAL`;
  2. bonuslar PENDING holatiga qaytadi;
  3. kassada `REVERSAL` chiqimi yoziladi;
  4. `status = CANCELLED`;
  5. audit `PAYMENT_CANCEL` yoziladi.
- **Javob:** `PaymentResponse` + `reversalLines[]` + `warnings[]` (`cash.negativeBalance`, `cash.incomeNotFound`).

### Boshqa to'lov endpointlari

| Endpoint | O'zgarish |
|---|---|
| `GET /api/payments` | `meta` aggregati default'da faqat PAID; `status=CANCELLED` filtri ishlaydi; qatorlarda `status`, `cancel*`. |
| `GET /api/payments/student/{id}` | **@PreAuthorize qo'shildi:** SA, A, ACC, SALES_MANAGER. |
| `GET /api/payments/debtors` | Yangi parametrlar: `scope` (`ACTIVE` default / `ALL`), `minDays`, `groupId`, `page`, `size`. Ro'yxatda faqat OVERDUE. Qator: `studentId, fullName, phone, debt, debtSince, daysOverdue, status, groups[]`. Eski nomlar saqlangan: `totalDebt (=debt)`, `monthlyAmount`, `monthsUnpaid`, `nextPaymentDate (=debtSince)`, `groupName`. |
| `GET /api/payments/debtors/summary` | `scope`; `{totalDebtors, overdue7Plus, totalDebt}` + `closedDebt` (scope=ALL). |
| `GET /api/payments/expected` | Default `from = bugun`; `amount = nextPaymentAmount`. Faqat hisoblanadigan SG lar: trial, muzlatilgan va COMPLETED/CANCELLED guruhdagilar kirmaydi. |
| `GET /api/payments/calculate-debt` | **Deprecated**: `{debt, balance, debtSince, status, nextPaymentDate, nextPaymentAmount}` snapshot'dan. |
| `GET /api/payments/stats` | Faqat PAID yig'iladi. |

## 2. O'quvchilar — `/api/students`

| Endpoint | Rollar | Request | Response |
|---|---|---|---|
| `GET /{id}`, ro'yxat | — | — | + `debt`, `nextPaymentAmount`; `groups[]` + `billingDay`, `effectiveFee`, `debtSince`, `frozenFrom`, `debt`, `nextPaymentAmount` |
| `GET /{id}/balance-history` | **+ ACC** (SA, A, ACC) | — | + `id`, `effectiveDate`, `relatedTxId`, `billingPeriod {start, end}`, `paymentId`, `receiptNumber` (PAYMENT/DISCOUNT uchun) |
| `POST /{id}/balance-adjust` | SA | `amount` butun, ≠ 0; **yangi** `effectiveDate` (ixtiyoriy, ≤ bugun) | — (endi qulf ostida, snapshot yangilanadi) |
| **`POST /{id}/balance-transfer`** (yangi, §13 #6) | SA | `{fromGroupId*, toGroupId*, amount* > 0 butun, note* ≥ 3}` | `[TRANSFER_OUT, TRANSFER_IN]` tarix qatorlari. 400 `balanceTransfer.sameGroup`. |
| **`POST /{id}/refund-payout`** (yangi, §13 #24) | **SA, A** | `{groupId?, amount*, cashRegisterId*, paymentMethod?, cashPart?, cardPart?, reason* 3..500}` | `RefundPayoutResponse {studentId, studentName, groupId, studentGroupId, amount, reason, cashTransactionId, line, balanceAfter, debtAfter, statusAfter}`. Musbat balansdan oshsa 400 `refund.amount.exceedsBalance`. Kassa chiqimi yoziladi. Audit `REFUND`. |
| `POST /{id}/freeze/preview`, `/freeze` | SA, A | `groupId` (> 1 faol SG bo'lsa majburiy → 400 `payment.group.required`). **Yangi** `freezeDate` (default bugun; 400 `freeze.date.future` / `freeze.date.tooOld` (30 kun) / `freeze.date.beforeAttendance`) | Eski `studentId, totalBalance, groups[]` saqlangan. **Yangi:** `freezeDate, studentGroupId, refundLines[], refundTotal, balanceBefore, balanceAfter, debtAfter, statusAfter, studentStatusAfter`. Preview va freeze bitta rejadan, qatorlari teng. |
| `POST /{id}/unfreeze` | SA, A | `groupId*`, `paymentStartDate` (default bugun). Davrlar bilan ustma-ust tushsa → 409 `billing.anchor.overlap`. | O'sha SG qayta faollashadi (yangi SG yaratilmaydi). ⚠ Farq: javob avvalgidek `StudentDetailResponse`; `accrualLines[]` qo'shilmagan — yozuvlar `balance-history` da. |
| `POST /{id}/transfer-group` | SA, A | — | Narx, rejim, chegirma, trial va format ko'chadi. Balans `TRANSFER_OUT/IN` bilan ko'chadi (qarz `debtSince` bilan). Yangi SG `paymentStartDate` = eski SG ning keyingi hisoblanmagan davri. ⚠ `transferLines[]` qo'shilmagan (`balance-history` da). |
| `PATCH /{id}/payment-start-date` | SA, A, ACC | **Yangi** `groupId` (> 1 faol SG bo'lsa majburiy). 409 `billing.anchor.overlap`; ACC bir oydan ko'p orqaga qo'ysa → 403 `billing.anchor.backdateForbidden`. | Bugungacha davrlar darhol yoziladi. ⚠ `accrualLines[]` qo'shilmagan. |

## 3. Guruhlar — `/api/groups`

| Endpoint | O'zgarish |
|---|---|
| `POST /students`, `/students/create-and-add`, lid konvertatsiyasi, import | `discountPercentage`: null → 0; 0..100 dan tashqari → 400 `student.discount.invalid`. `monthlyPriceOverride` faqat kurs narxidan farq qilsa saqlanadi (§9.5). `paymentStartDate ≤ bugun` bo'lsa davr **darhol** yoziladi. ⚠ `accrualLines[]` javobga qo'shilmagan. |
| `POST /{id}/remove-student`, `DELETE /{g}/students/{s}` | Avval bugungacha davrlar yoziladi, keyin SG yopiladi (`leaveDate = bugun`). Qaytarim yo'q (§13 #3), balans SG da qoladi. ⚠ Javob o'zgarmagan (`balance/debt` qo'shilmagan). |
| `POST /api/promotions/bulk-promote` | Har o'quvchi `transfer-group` bilan bir xil yo'ldan o'tadi (balans va narx ko'chadi). |

## 4. Bonus/jarima — `/api/bonus-penalties`

| Endpoint | O'zgarish |
|---|---|
| `POST`, `PUT /{id}` | STUDENT uchun **yangi** `groupId` (bonus faqat shu yozilmaga). Summa butun. |
| **`POST /{id}/apply`** (yangi) | SA, A. Body `{groupId?}`. To'lovsiz qo'llaydi: `BONUS +x` / `PENALTY −y`. Takror → 409 `bonus.notPending`. |
| `PATCH /{id}/cancel` | PENDING bo'lsa — avvalgidek. APPLIED bo'lsa — **faqat SA** (403 `bonus.cancel.forbidden`), body `{reason*}` (400 `bonus.cancel.reasonRequired`), ledgerga `REVERSAL` yoziladi. v1 da qo'llanganlar → 400 `bonus.cancel.legacy`. |
| Javob (`BonusPenaltyDto`) | + `studentGroupId`, `groupId`, `ledgerTxId`, `appliedToPaymentId`, `cancelReason`. |

## 5. Kassa

| Endpoint | O'zgarish |
|---|---|
| `POST /api/cash-registers/{id}/income` | `studentId` bilan → 400 `cash.income.studentPaymentViaPayments` (o'quvchi to'lovi faqat `/api/payments` orqali). |
| `GET /api/cash-registers/{id}/transactions` (va har qanday `CashTransactionDto`) | **02.10.2026:** yangi maydonlar `direction` (`IN`\|`OUT`), `signedAmount` (IN → +amount, OUT → −amount), `paymentId`, `payrollId`, `relatedTxId`. Qoida: INCOME → IN; EXPENSE → OUT; TRANSFER — chiqim qatori OUT, kirim qatori IN (yangi kirim qatorida `relatedTxId` = chiqim qatori; eski qatorlar nomi "(kirim)" bo'yicha); REVERSAL — asl yozuvga teskari (bekor qilingan to'lov → OUT, bekor qilingan oylik/chiqim → IN). `amount` avvalgidek musbat — ro'yxatda ishorani `direction`/`signedAmount` dan oling. |
| `GET /api/cash-registers/{id}/transactions/export` | Excel: yangi ustun **"Yo'nalish"** (IN/OUT, "Turi" dan keyin); **"Summa (±)"** — ishorali (chiqim manfiy). |

## 6. Davomat (PER_LESSON)

`POST /api/attendance/...` — javob o'zgarmagan, ledger qoidasi o'zgargan:
- dars billable bo'lsa (PRESENT/ABSENT/LATE) → `LESSON_CHARGE −l(sg)`. Bunda chegirma Money bilan butunlanadi. Sinov, muzlatilgan va langardan oldingi darslar hisoblanmaydi.
- EXCUSED ga o'tsa → `LESSON_REFUND`. Qaytariladigan summa — **asl charge** (narx keyin o'zgargan bo'lsa ham).
- Qayta yuborish yangi yozuv yaratmaydi (idempotent).

## 7. Admin — `/api/admin/billing/*` (faqat SA)

| Endpoint | Tavsif |
|---|---|
| `POST /accrue?date=` | Accrual'ni qo'lda ishga tushirish (job bilan bir xil kod). |
| `GET /job-runs?limit=` | Job tarixi. |
| `POST /refresh-snapshots` | Barcha o'quvchilar snapshot'i qayta yoziladi (pulga tegmaydi). |
| `GET /verify` | I1 (SG va o'quvchi balansi = ledger), I4 (davr ↔ charge), I5 (v2 to'lov ↔ kassa) — faqat o'qiydi. |
| `POST /migration/dry-run?cutover=&a14UsePayable=` | **Hech narsa yozmaydi**. `{cutover, goLive, maxTxId, maxPaymentId, reportHash, summary, rows[]}`. |
| `GET /migration/dry-run.xlsx?cutover=` | Xuddi shu, xlsx (sarlavha `X-Report-Hash`). ⚠ Farq: dizayndagi `runs/{id}/report.xlsx` o'rniga — dry-run saqlanmaydi. |
| `POST /migration/approve` | `{cutover, a14UsePayable, reportHash, approvedByOwner*, note}` → `billing_migration_runs` (APPROVED). Hash mos kelmasa → 409 `migration.reportChanged`. **Yangi** (§13 #20). |
| `GET /migration/runs`, `/migration/runs/{id}` | Run'lar: `status, approvedByOwner, appliedAt, rollbackDeadline (+72 soat), migrated, held, failed, errors`. |
| `POST /migration/apply?runId=&confirm=APPLY-<runId>&exclude=&clearOverrides=` | Faqat APPROVED run va `app.billing.enabled=false` holatida. Hash qayta tekshiriladi. Javob `{run, migrated[], held[], errors[]}`. |
| `POST /migration/revert-sg?runId=&sgId=&confirm=REVERT-<runId>-<sgId>` | Qisman rollback: REVERSAL yoziladi, run davrlari o'chiriladi, SG hold'ga o'tadi. |
| `POST /migration/apply-sg?runId=&sgId=&confirm=APPLY-<runId>-<sgId>` | **Yangi**: hold'dagi SG ni qayta qo'llash. |
| `GET /payments-since?from=<ISO datetime>` | Rollback uchun to'lovlar ro'yxati (xlsx). |

**Olib tashlangan (410 `billing.endpoint.gone`):**
- `/api/admin/repair/recalculate-payment-dates`
- `/api/admin/repair/fix-payment-periods`
- `/api/admin/repair/rebuild-monthly-ledger`
- `/api/admin/repair/verify-balances`

## 8. Yangi xato kodlari (tanlab)
`money.wholeSumRequired`, `billing.maintenance`, `concurrency.busy`,
`payment.group.required`, `payment.enrollment.notFound|required`, `payment.cashRegister.*`, `payment.discount.forbidden|reasonRequired`,
`payment.plan.changed`, `payment.idempotency.mismatch|keyTooLong`, `payment.cancel.reasonRequired|tooOld`, `payment.alreadyCancelled`,
`refund.amount.required|exceedsBalance`, `refund.reason.required`, `bonus.notPending|notApplied|cancel.*|apply.*`,
`freeze.date.future|tooOld|beforeAttendance`, `billing.anchor.overlap|backdateForbidden|required`,
`balanceTransfer.sameGroup|note.required`, `migration.*`.

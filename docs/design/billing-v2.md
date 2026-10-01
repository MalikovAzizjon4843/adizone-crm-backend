# Billing v2 — ACCRUAL modeli (dizayn hujjati)

| | |
|---|---|
| Holat | Tasdiqlangan (§13 qarorlari bilan). Amalga oshirilmoqda — `billing-v2` branchi. |
| Branch | `billing-v2` (`main` dan, `5237df6`) |
| Sana | 01.10.2026 |
| Asos | `docs/audit/backend-audit.md` §6.A (To'lov), §9.12 (Moliya biznes qoidalari) |
| Qarorlar | Buyurtmachi qarorlari 1–8 (pastda Q1…Q8 deb havola qilinadi) |

Yo'llar `src/main/java/com/crm/` ga nisbatan. Raqamlar UZS, butun so'm. Sana `dd.MM.yyyy`, vaqt zonasi Asia/Tashkent.

---

## 0. Kirish

### 0.1 Muammo bir jumlada
MONTHLY yozilmada davr qarzi (`PERIOD_CHARGE`) faqat **to'lov paytida** yoziladi (`service/PaymentService.java:226-234`). To'lamagan o'quvchining balansi manfiy bo'lmaydi, shuning uchun qarz sanalardan taxmin qilinadi. Natijada 4+ ta bir-biriga zid "qarzdor" ta'rifi paydo bo'lgan (audit §9.12 #9).

### 0.2 v2 yechimi bir jumlada
Har davr boshida (billing kuni) `PERIOD_CHARGE` **avtomatik** yoziladi, `PAYMENT` faqat kredit bo'ladi. Balans, qarz, holat va keyingi to'lov sanasi **faqat ledgerdan** hisoblanadi — bitta servis, bitta formula.

### 0.3 Audit muammolari → v2 bo'limi

| Audit §9.12 | Muammo | v2 da hal qilinadi |
|---|---|---|
| #1 | Ko'p guruhda `student.monthlyFee` bilan noto'g'ri PERIOD_CHARGE | §3.4 (narx faqat yozilmadan), §8 |
| #2 | Kam to'lov / `amount=0` ham `nextPaymentDate` ni 1 oyga suradi | §4.3 (sana ledgerdan), §5.2 (`amount>0`) |
| #3 | PER_LESSON da chegirma neytral emas | §3.5, §6.9 |
| #4; §9.0 #18 | Bonus/jarima pulga ta'sir qilmaydi; preview ≠ create | §5, §6.5 |
| #5, #6 | Enrollmentsiz to'lov ledgerga tushmaydi; noto'g'ri `groupId` jim fallback | §5.2 qadam 2 |
| #7, #8 | Holat "biror to'lov bor"ga asoslangan; bugun to'lashi kerak bo'lganlar hech qayerda yo'q | §4 |
| #9, #10 | 4+ qarzdor ta'rifi; qarz balansni hisobga olmaydi | §4.5 |
| #11 | Bekor qilish yo'q, `PERIOD_REFUND` o'lik | §6.4, §6.7 |
| #12 | Kassaga qo'lda kirim `student.balance` ni ledgersiz o'zgartiradi | §1.2 I3, §10 |
| #13 | Bitta guruh muzlatilsa ham o'quvchi FROZEN; to'langan davr qaytarilmaydi | §6.7 |
| #14 | Transferda balans/narx/rejim yo'qoladi | §6.8 |
| #15 | Avto-arxiv tarixsiz FROZEN | §13 (ochiq savol) |
| #17 | LESSON_REFUND yangi narx bilan | §6.9 |
| #24 | `discountPercentage` ishlatilmaydi | §3.5 |
| Race | `sg.balance`, kassa, chek raqami, bonus | §7 |
| Yaxlitlash | DOWN/HALF_UP aralash, `double` | §1.3 |

### 0.4 Bog'liqlik
`phase3-fixes` (vaqt zonasi: `TimeZone.setDefault(Asia/Tashkent)`, cron'larda `zone`) hali `main` da **yo'q**. Accrual "billing kuni"ga tayanadi, shuning uchun **billing-v2 dan oldin `phase3-fixes` merge qilinishi shart**. Shundan keyin billing-v2 rebase qilinadi.

Konfliktlar kutiladi:
- `PaymentScheduleService`
- `StudentPaymentLifecycleService`
- `PaymentReminderService`
- `AttendanceService`

### 0.5 Qamrovdan tashqarida
- Imtihon to'lovi (`ExamPaymentCalculatorService`).
- Payroll formulasi (faqat ta'sir §13 da qayd etilgan).
- Kassa smenasi.
- Onlayn to'lov integratsiyasi.

---

## 1. Atamalar va invariantlar

### 1.1 Atamalar

| Atama | Ma'nosi |
|---|---|
| **Yozilma (SG)** | `StudentGroup` — o'quvchining bitta guruhdagi a'zoligi. Pul va holat **SG darajasida** yuritiladi. |
| **Ledger** | `balance_transactions` — faqat INSERT qilinadigan pul daftari. Har yozuvda belgili `amount` bor. |
| **Kredit / debet** | `amount > 0` — o'quvchi foydasiga (to'lov, qaytarish, bonus); `amount < 0` — majburiyat (davr, dars, jarima). |
| **Balans (B)** | `B(sg) = Σ amount` (shu SG ning barcha ledger yozuvlari). `B ≥ 0` — oldindan to'langan; `B < 0` — qarz. |
| **Qarz** | `debt(sg) = max(0, −B(sg))`. |
| **Davr** | MONTHLY yozilmaning bitta hisob oyi `[periodStart, periodEnd]`. Jadvali — `billing_periods`. |
| **Billing kuni** | Davr boshlanadigan oy kuni (§3.1). |
| **Accrual** | Davr boshlanganda `PERIOD_CHARGE` yozish (job yoki "quvib yetish"). |
| **Samarali narx (c)** | `c = Money.uzs(fee × (100 − d) / 100)`, bu yerda `fee` — yozilma oylik narxi, `d` — `discountPercentage`. |
| **debtSince** | Eng eski to'lanmagan majburiyatning sanasi (FIFO, §4.1). |
| **Grace** | `app.billing.grace-days`, default **3**. |
| **Kassa yozuvi** | `CashTransaction` (kassa balansi o'zgaradigan yagona joy). |

### 1.2 Invariantlar
Har biri test bilan tekshiriladi (§12) va admin `verify` endpointi bilan kuzatiladi (§10).

**I1. Balans = ledger yig'indisi.**
- `sg.balance == Σ bt.amount WHERE bt.student_group_id = sg.id` — saqlangan qiymat faqat kesh.
- `student.balance == Σ sg.balance` (barcha SG, yopilganlari ham).
- `student.debt == Σ max(0, −sg.balance)`.

**I2. Har bir pul o'zgarishi faqat ledger orqali.**
- Yagona kirish nuqtasi — `LedgerService.post(sg, type, amount, effectiveDate, refs)`.
- `setBalance(...)` faqat shu metod ichida chaqiriladi; arxitektura testi boshqa joyda borligini tekshiradi.
- `CashRegisterService.addIncome` dagi `student.setBalance` (`service/CashRegisterService.java:395-400`) **olib tashlanadi**.

**I3. Ledger append-only.**
- UPDATE/DELETE yo'q.
- Xato tuzatiladi teskari yozuv (`REVERSAL`) yoki tuzatish (`MANUAL_ADJUST`) bilan.
- Teskari yozuv `related_tx_id` orqali aslini ko'rsatadi.

**I4. Davr bir marta hisoblanadi.**
- `UNIQUE (student_group_id, period_start)` `billing_periods` da.
- `PERIOD_CHARGE` faqat `billing_periods` qatori bilan birga yoziladi.

**I5. To'lov ↔ ledger ↔ kassa.** `status = PAID` bo'lgan har bir `Payment` uchun:
- (a) bitta `PAYMENT` ledger yozuvi (`amount = cashAmount`), agar `cashAmount > 0`;
- (b) bitta `DISCOUNT` yozuvi, agar `discountAmount > 0`;
- (c) bitta `CashTransaction(INCOME, payment_id = p.id, amount = cashAmount)`, agar `cashAmount > 0`.

`CANCELLED` to'lov uchun bularning har biriga bittadan teskari yozuv bor: ledgerda `REVERSAL`, kassada `CashTransactionType.REVERSAL`.

**I6. Kassa balansi = kassa yozuvlari yig'indisi.** `cashBalance` va `plasticBalance` faqat `CashTransaction` yozilganda o'zgaradi (hozir ham shunday). Endi o'zgarish qulf ostida bo'ladi (§7).

**I7. Holat ledgerdan hosila.** `paymentStatus`, `nextPaymentDate`, `debtSince` faqat `BillingSnapshotService.refresh(sg)` da yoziladi. Boshqa joyda `setPaymentStatus(...)` yo'q (hozir 15+ joy bor — agent ro'yxati §9 da).

**I8. Pul summalari butun so'm.** Ledgerga va kassaga yoziladigan har bir summa `scale = 0`.

### 1.3 Yaxlitlash — BIR joyda
```java
// billing/Money.java — loyihadagi YAGONA RoundingMode ishlatiladigan joy (billing paketi uchun)
public static BigDecimal uzs(BigDecimal raw) { return raw.setScale(0, RoundingMode.HALF_UP); }
```

**Qoida.** Formula avval to'liq aniqlikda hisoblanadi (ko'paytirish, keyin `divide(…, 10, HALF_UP)` oraliq aniqlik bilan), so'ng **bir marta** `Money.uzs` qo'llanadi:
- Davr summasi: `c = uzs(fee × (100 − d) / 100)`.
- Dars summasi: `uzs(lessonPrice × (100 − d) / 100)`.
- Muzlatish qaytarimi: `uzs(chargeAmount × unusedDays / periodDays)`. U **yozilgan charge summasidan** hisoblanadi (fee dan emas), shuning uchun qaytarim hech qachon charge dan oshmaydi.

**Foydalanuvchi kiritgan summalar** (to'lov, chegirma, bonus, tuzatish) yaxlitlanmaydi. Kasrli bo'lsa 400 `money.wholeSumRequired` ("Summa butun so'mda bo'lishi kerak"). Sabab: kassadagi real pul bilan farq paydo bo'lmasin.

`double` billing kodida taqiqlanadi. `calculateStudentDebt` va Telegram summasi `doubleValue()` olib tashlanadi.

---

## 2. Ledger turlari

`entity/enums/BalanceTransactionType.java` ga qo'shiladi. Mavjud qiymatlar o'chirilmaydi — eski yozuvlar o'qilishi kerak. `EnumCheckConstraintCleaner` yangi qiymatlar uchun CHECK muammosini hal qiladi.

| Tur | Belgi | Qachon | Kim yozadi (v2) | `effective_date` | Bog'lanish | Holat |
|---|---|---|---|---|---|---|
| `PERIOD_CHARGE` | − | MONTHLY davr boshlanganda | `AccrualService` (job, quvib yetish, to'lov/preview oldidan) | `period_start` | `billing_period_id` | **o'zgaradi**: endi to'lovda emas, accrualda |
| `PERIOD_REFUND` | + | MONTHLY muzlatishda ishlatilmagan qism | `FreezeService` | muzlatish sanasi | `related_tx_id` = charge, `billing_period_id` | **jonlanadi** (hozir o'lik) |
| `LESSON_CHARGE` | − | PER_LESSON: davomat billable bo'ldi | `AttendanceBillingService` | dars sanasi | `reference_id` = attendance.id | o'zgaradi: chegirma, sinov, idempotentlik |
| `LESSON_REFUND` | + | PER_LESSON: billable → EXCUSED | `AttendanceBillingService` | dars sanasi | `related_tx_id` = aynan o'sha charge | o'zgaradi: **asl summa** qaytariladi |
| `PAYMENT` | + | To'lov (`cashAmount > 0`) | `PaymentService.create` | to'lov sanasi | `reference_id` = payment.id | o'zgaradi: endi faqat kredit (PERIOD_CHARGE juftisiz) |
| `DISCOUNT` | + | To'lovdagi bir martalik chegirma (`discountAmount`) | `PaymentService.create` | to'lov sanasi | `reference_id` = payment.id | **yangi** |
| `BONUS` | + | O'quvchi bonusi qo'llanganda | `PaymentService.create` / `BonusPenaltyService.apply` | qo'llangan sana | `reference_id` = bonus_penalty.id | **yangi** |
| `PENALTY` | − | O'quvchi jarimasi qo'llanganda | xuddi shunday | `effectiveDate` | `reference_id` = bonus_penalty.id | **yangi** |
| `REVERSAL` | ∓ | Istalgan kredit/debetni bekor qilish (to'lov, chegirma, bonus, jarima) | `PaymentCancelService`, `BonusPenaltyService.cancelApplied` | **asl yozuvniki** | `related_tx_id` = asl yozuv | **yangi** |
| `TRANSFER_OUT` | ∓ | transfer-group: eski SG balansini nolga tushirish | `TransferService` | transfer sanasi | `related_tx_id` = juft yozuv | **yangi** |
| `TRANSFER_IN` | ± | transfer-group: yangi SG ga balans | `TransferService` | qarz bo'lsa — eski `debtSince`, aks holda transfer sanasi | `related_tx_id` = juft yozuv | **yangi** |
| `MANUAL_ADJUST` | ± | SUPER_ADMIN qo'lda tuzatish (`balance-adjust`) | `LedgerService` (SA) | kiritilgan sana | — | o'zgarmaydi; `[ledger-repair]` yozuvlari endi yaratilmaydi |
| `MIGRATION` | ± | Migratsiya: eski PERIOD_CHARGE / ta'mir yozuvlarini neytrallash | `BillingMigrationService` | cutover sanasi | `migration_run_id` | **yangi** (§9) |
| `REFUND_PAYOUT` | − | O'quvchiga musbat balansdan naqd qaytarish (§13 #24 qarori) | `RefundPayoutService` (SA, A) | qaytarish sanasi | `reference_id` = kassa chiqimi (`cash_transactions.id`) | **yangi** |
| `FREEZE` | ± | — | yozilmaydi (legacy, faqat o'qiladi) | — | — | **legacy** |
| `UNFREEZE` | ± | — | yozilmaydi (legacy) | — | — | **legacy** |

### 2.1 Belgi qoidasi
`amount` belgisi turdan kelib chiqadi va `LedgerService` tekshiradi (masalan `PERIOD_CHARGE` > 0 → `IllegalStateException`). `REVERSAL` belgisi — asl yozuvga teskari.

### 2.2 `typeLabel()`
Yorliqlar to'ldiriladi: "Davr to'lovi", "Davr qaytarimi", "Chegirma", "Bonus", "Jarima", "Bekor qilindi: <asl yorliq>", "Ko'chirish (chiqim/kirim)", "Migratsiya".

### 2.3 Yangi ustunlar (`balance_transactions`)

| Ustun | Tur | Ma'nosi |
|---|---|---|
| `effective_date` | `date NOT NULL` | Majburiyat/kredit sanasi (FIFO uchun). Eski yozuvlar uchun `created_at::date` bilan to'ldiriladi. |
| `related_tx_id` | `bigint` | REVERSAL/REFUND/TRANSFER juftligi. |
| `billing_period_id` | `bigint` | PERIOD_CHARGE / PERIOD_REFUND → `billing_periods.id`. |
| `migration_run_id` | `bigint` | Migratsiya yozuvlari (qaytarish uchun belgi). |

`reference_id` saqlanadi (payment / attendance / bonus id). Qaysi jadvalga ishora qilishi turdan aniqlanadi.

---

## 3. Accrual (avtomatik hisoblash)

### 3.1 Billing kuni
Langar (anchor) — `sg.paymentStartDate`. U trial'dan chiqishda, unfreeze'da, `payment-start-date` o'zgarganda qayta o'rnatiladi (§3.6).

```
billingDay      = day(paymentStartDate)                      // 1..31
start(0)        = paymentStartDate
start(n), n ≥ 1 = dayOf(YearMonth.from(paymentStartDate).plusMonths(n), billingDay)
dayOf(ym, d)    = d ≥ 29 ? ym.atEndOfMonth() : ym.atDay(d)   // 29–31 → oy oxiri
end(n)          = start(n+1) − 1 kun
```

| paymentStartDate | Davr boshlari | Davrlar (kun) |
|---|---|---|
| 15.09.2026 | 15.09, 15.10, 15.11, 15.12 | 15.09–14.10 (30), 15.10–14.11 (31), 15.11–14.12 (30) |
| 01.10.2026 | 01.10, 01.11, 01.12 | 01.10–31.10 (31), 01.11–30.11 (30) |
| 31.01.2027 | 31.01, 28.02, 31.03, 30.04 | 31.01–27.02 (28), 28.02–30.03 (31), 31.03–29.04 (30) |
| 29.01.2027 | 29.01, 28.02, 31.03, 30.04 | birinchi davr haqiqiy sanadan; keyingilari oy oxiri |
| 30.01.2028 | 30.01, 29.02 (kabisa), 31.03 | 30.01–28.02 (30), 29.02–30.03 (31) |

Proratsiya YO'Q — birinchi davr `paymentStartDate` dan boshlanadi va to'liq narx olinadi (hozirgi "yubiley" mantiqi saqlanadi, audit §6 A.9).

### 3.2 Hisoblanadigan yozilma (billable)
```
isAccruable(sg, date) =
     sg.paymentType == MONTHLY
  && !sg.isTrial
  && sg.paymentStartDate != null && sg.paymentStartDate ≤ date
  && sg.frozenFrom == null                       // muzlatilmagan (§6.7)
  && (sg.leaveDate == null || date ≤ sg.leaveDate)
  && group.status ∈ {ACTIVE, FORMING}            // ochiq savol §13 #14
  && fee(sg) > 0                                 // 0 bo'lsa — davr yozilmaydi, hisobotga "narx yo'q"
```

### 3.3 `billing_periods` jadvali (yangi)

| Ustun | Tur | Izoh |
|---|---|---|
| `id` | bigserial | |
| `student_group_id` | bigint NOT NULL | FK |
| `period_start`, `period_end` | date NOT NULL | |
| `fee` | numeric(12,2) | o'sha paytdagi oylik narx (snapshot) |
| `discount_percentage` | numeric(5,2) | snapshot |
| `amount` | numeric(12,2) | `c` (yaxlitlangan) |
| `status` | varchar(20) | `CHARGED` / `PARTIALLY_REFUNDED` / `REFUNDED` / `MIGRATED` / `PREPAID_LEGACY` |
| `charge_tx_id` | bigint | PERIOD_CHARGE yozuvi (MIGRATED/PREPAID_LEGACY da null) |
| `refunded_amount` | numeric(12,2) default 0 | |
| `created_at` | timestamp | |

Constraint: **`UNIQUE (student_group_id, period_start)`** — oddiy (partial emas) constraint, `ddl-auto` yangi jadval yaratganda ham hosil bo'ladi.

### 3.4 Narx (fee) — faqat yozilmadan
```
fee(sg) = sg.monthlyPriceOverride (> 0)  →  group.course.monthlyPrice (> 0)  →  0
```

- `student.monthlyFee` fallback'i **olib tashlanadi** (`PaymentScheduleService.java:602-615`). U endi faqat hosila yig'indi (§8).
- `ensureGroupFeeAndStartDate` (`PaymentScheduleService.java:156-169`) har recalc'da override'ni kurs narxi bilan to'ldiradi. Natijada kurs narxi o'zgarsa ham eski narx "muzlab" qoladi va "individual narx" tushunchasi yo'qoladi. v2 da bu **to'xtatiladi**: override faqat foydalanuvchi kiritganda yoziladi. Migratsiya bo'yicha qaror — §9.5, §13 #15.
- Narx yoki chegirma o'zgarsa, faqat **keyingi** davrdan ta'sir qiladi. Yozilgan davr qayta hisoblanmaydi (`billing_periods.fee/amount` snapshot).

### 3.5 Chegirma (Q3)
```
MONTHLY:     c(sg) = Money.uzs(fee × (100 − d) / 100)
PER_LESSON:  l(sg) = Money.uzs(lessonPrice × (100 − d) / 100)
d = sg.discountPercentage ?? 0,  0 ≤ d ≤ 100   (aks holda 400 student.discount.invalid)
```

- `addStudentToGroup` hozir `discountPercentage` ni so'rovdan to'g'ridan-to'g'ri qo'yadi va berilmasa `null` bo'ladi (`GroupService.java:568-587`). v2 da `null → 0`.
- `d = 100` bo'lsa: `billing_periods` qatori `amount = 0` bilan yoziladi (idempotentlik uchun), ledger yozuvi yozilmaydi.

### 3.6 Langarni o'zgartirish (re-anchor)
`paymentStartDate` yangi qiymatga o'tganda accrual shu sanadan, `n = 0` dan qayta boshlanadi. Bu quyidagi hollarda bo'ladi:
- trial → to'lovli;
- unfreeze;
- `PATCH /payment-start-date`.

Oldingi langardan yozilgan davrlar o'zgarmaydi.

Cheklov: yangi sana **oxirgi hisoblangan davr oxiridan oldin** bo'lsa, ikki davr ustma-ust tushadi. Bunday so'rov 409 `billing.anchor.overlap` bilan rad etiladi ("Yangi sana {end} dan keyin bo'lishi kerak yoki avval davr qaytarilsin").

### 3.7 Scheduled job
```java
@Scheduled(cron = "${app.billing.accrual-cron:0 10 0 * * *}", zone = "Asia/Tashkent")
public void dailyBilling() {
    LocalDate today = LocalDate.now(clock);                          // clock = Asia/Tashkent (§12)
    for (Long sgId : sgRepo.findAccrualCandidateIds(today)) {        // MONTHLY, aktiv, trial/frozen emas
        try { accrualService.accrueUpTo(sgId, today); }              // HAR BIRI alohida tranzaksiya (REQUIRES_NEW)
        catch (Exception e) { run.fail(sgId, e); }                   // biri yiqilsa qolganlari davom etadi
    }
    snapshotService.refreshAllDue(today);                            // PENDING → OVERDUE vaqt o'tishi bilan
    run.finish();                                                    // billing_job_runs: soni, xatolar
}
```

- **00:10** da ishlaydi — 00:05 dagi eski job o'rnini bosadi.
- Hozirgi `updateOverdueStatusesDaily` barcha o'quvchilarni **bitta** tranzaksiyada yuradi (`PaymentScheduleService.java:1031-1045`). v2 da har SG alohida.

### 3.8 `accrueUpTo(sg, asOf)` — idempotent va "quvib yetuvchi"
```
lock(student), lock(sg)                                  // §7 tartibi
next = firstUnbilledStart(sg)                            // langardan boshlab billing_periods da yo'q birinchi start(n)
if next == null || !isAccruable(sg, next) → snapshot.refresh(sg); return
k = 0
while next ≤ asOf && isAccruable(sg, next) && k < app.billing.max-catch-up (24):
    period = insert billing_periods(sg, next, end(next), fee, d, c)  ON CONFLICT (sg, period_start) DO NOTHING
    if inserted && c > 0:
        tx = ledger.post(sg, PERIOD_CHARGE, −c, effectiveDate = next, billingPeriod = period)
        period.chargeTx = tx
    next = following(next); k++
if k == max-catch-up → hisobotga "catch-up chegarasi" (qo'lda tekshirish)
snapshot.refresh(sg)
```

**Chaqiriladigan joylar** (hammasi bitta metod):
1. kunlik job;
2. ilova ishga tushganda (`ApplicationReadyEvent`, server o'chib qolgan kunlar uchun);
3. SG yaratilganda / trial'dan chiqqanda / unfreeze — agar `paymentStartDate ≤ bugun`;
4. to'lov `create` va `preview` oldidan (preview'da faqat hisoblanadi, yozilmaydi — §5);
5. admin `POST /api/admin/billing/accrue?date=`.

**Idempotentlik — uch qavat:**
1. SG qatoriga qulf — parallel ikki `accrueUpTo` ketma-ket bajariladi;
2. `UNIQUE (student_group_id, period_start)`;
3. `firstUnbilledStart` mavjud qatorlarni o'tkazib yuboradi.

Bir kunda job ikki marta ishlasa yoki server qayta ishga tushsa, yangi yozuv paydo bo'lmaydi.

**Quvib yetish misollari:**

| Holat | Natija |
|---|---|
| Server 14.10 23:00 dan 17.10 09:00 gacha o'chiq, langar 15 | 17.10 dagi startup'da bitta `PERIOD_CHARGE −630 000`, `effective_date = 15.10` (`created_at = 17.10`). Holat 15.10 dan hisoblanadi: 18.10 da 3 kun → PENDING, 19.10 da OVERDUE. |
| 01.10 da o'quvchi `paymentStartDate = 15.06` bilan qo'shildi (orqaga sana) | Darhol 4 ta charge: 15.06, 15.07, 15.08, 15.09 (`4 × 700 000 = −2 800 000`). Qo'shish dialogining preview'i "4 ta davr hisoblanadi: 2 800 000" deb ko'rsatadi (ochiq savol §13 #13). |

---

## 4. Holat, nextPaymentDate, qarz

### 4.1 FIFO — eng eski to'lanmagan majburiyat
Kreditlar eng eski majburiyatlarni birinchi yopadi (sanasidan qat'i nazar).

```
// Guruhlash: teskari va qaytarim yozuvlari asl yozuvga qo'shiladi
groupKey(tx)  = tx.relatedTxId ?? tx.id         (TRANSFER juftlari bundan mustasno — ular alohida SG da)
net(g)        = Σ amount (guruh ichida)
obligations   = { g : net(g) < 0 }, tartib: (asl.effective_date, asl.id) bo'yicha o'sish
funds         = Σ net(g) > 0
for g in obligations:
    if funds ≥ |net(g)|: funds −= |net(g)|
    else: debtSince = asl(g).effective_date; break     // eng eski to'liq yopilmagan majburiyat
B < 0  ⇔  debtSince != null
```

Nega guruhlash kerak: to'lov bekor qilinsa (`REVERSAL`) qarz **asl charge sanasidan** qaytadi, bekor qilingan kundan emas. Muzlatish qaytarimi (`PERIOD_REFUND`) esa aynan o'sha davr majburiyatini kamaytiradi.

### 4.2 Holat (Q2)
```
days = today − debtSince   (kalendar kunlari, Toshkent)
B ≥ 0                  → PAID
B < 0 && days ≤ grace  → PENDING
B < 0 && days > grace  → OVERDUE        // grace = app.billing.grace-days = 3
```

**SG ko'rsatish holati** (`sg.paymentStatus`, yuqoridan pastga birinchi mos):

| # | Shart | Holat |
|---|---|---|
| 1 | `B < 0`, `days > grace` | `OVERDUE` (muzlatilgan/yopilgan bo'lsa ham — qarz ustun) |
| 2 | `B < 0`, `days ≤ grace` | `PENDING` |
| 3 | `frozenFrom != null` | `FROZEN` |
| 4 | `isTrial` | `TRIAL` |
| 5 | qolgan barchasi | `PAID` |

Misol: charge 15.10 da yozilgan, to'lov yo'q:

| Bugun | days | Holat |
|---|---|---|
| 15.10 | 0 | PENDING |
| 18.10 | 3 | PENDING |
| 19.10 | 4 | **OVERDUE** — qarzdor |

`sg.paymentStatus` `String` dan `PaymentStatus` enumiga o'tkaziladi (`@Enumerated(STRING)`, ustun turi o'zgarmaydi — varchar). `"FROZEN"`, `"ARCHIVED"`, `"TRIAL"` literallari yo'qoladi.

### 4.3 nextPaymentDate va nextPaymentAmount
**MONTHLY**, faol, trial/frozen emas:
```
if B < 0:
    next = debtSince;  amount = −B                          // "to'lash kerak edi"
else:
    c  = c(sg) (joriy narx);  if c == 0 → next = null
    k  = floor(B / c)                                         // oldindan to'liq qoplangan kelgusi davrlar
    next   = upcomingStart(sg, k)                             // upcomingStart(0) = hali hisoblanmagan birinchi davr boshi
    amount = c − (B − k × c)
```

**PER_LESSON**:
```
if B < 0: next = debtSince; amount = −B
else:     l = l(sg); k = floor(B / l)
          next = jadval bo'yicha (k+1)-chi kelgusi dars sanasi (GroupScheduleService.lessonWeekdays),
                 jadval yo'q → null
          amount = l − (B − k × l)
```

Trial, frozen yoki yopilgan SG: `next = null`, `amount = null`.

| Ali, c = 630 000, langar 15 | B | next | amount |
|---|---|---|---|
| 16.09, 630 000 to'ladi | 0 | 15.10 | 630 000 |
| 16.09, 1 890 000 to'ladi | +1 260 000 | 15.12 (k = 2) | 630 000 |
| 16.09, 930 000 to'ladi | +300 000 | 15.10 (k = 0) | 330 000 |
| 20.10, qisman to'lovdan keyin | −330 000 | 15.10 | 330 000 |

Bu hozirgi xatolarni tuzatadi:
- #2: kam to'lov sanani surmaydi;
- #7: boshqa guruh to'lovi sanaga ta'sir qilmaydi;
- #8: bugun yozilgan charge bugunoq PENDING bo'lib "kutilayotgan to'lovlar"da ko'rinadi.

### 4.4 Saqlanadigan snapshot va yagona hisob metodi
Dashboard/analitika har so'rovda FIFO hisoblamasligi uchun natija SG da saqlanadi. U har ledger yozuvidan keyin **o'sha tranzaksiyada** va kunlik job'da yangilanadi:

| Maydon | Qayerda |
|---|---|
| `balance` | `student_groups` (bor) |
| `debt_since` (yangi), `next_payment_date` (bor), `next_payment_amount` (yangi), `payment_status` | `student_groups` |
| `balance`, `debt` (yangi), `next_payment_date`, `next_payment_amount` (yangi), `monthly_fee`, `payment_status` | `students` (agregat, §8) |

```java
// billing/BillingStatusService.java — YAGONA ta'rif
public BillingStatus statusOf(BigDecimal balance, LocalDate debtSince, LocalDate today);  // Java tomoni
public Specification<StudentGroup> overdue(LocalDate today);   // SQL: balance < 0 AND debt_since < today − grace
public Specification<StudentGroup> pending(LocalDate today);   // balance < 0 AND debt_since ≥ today − grace
public DebtorPage debtors(DebtorFilter f, LocalDate today);    // ro'yxat + jami
public DebtorSummary debtorSummary(DebtorFilter f, LocalDate today);
public BillingSnapshot snapshot(StudentGroup sg, LocalDate today);   // bitta SG uchun to'liq (FIFO bilan)
```

`statusOf` va `overdue/pending` bir xil uchta qiymatdan foydalanadi: `balance`, `debt_since`, `grace`. Ekvivalentlik testi bor (§12 T2.4).

### 4.5 Qarzdor — yagona ta'rif

> **Qarzdor = kamida bitta SG si `OVERDUE` bo'lgan o'quvchi** (`balance < 0` va `today − debt_since > grace`).
> **Qarz summasi = Σ max(0, −sg.balance)** (shu o'quvchining OVERDUE va PENDING SG lari).

| Iste'molchi | Hozir (audit) | v2 |
|---|---|---|
| `GET /api/payments/debtors`, `/debtors/summary` | sana + status, `monthlyFee × monthsUnpaid` (`PaymentScheduleService.java:732-811`) | `debtors()` / `debtorSummary()` |
| `GET /api/dashboard/stats` → `debtors` | `student.balance < 0` (`DashboardService.java:50`) | `debtorSummary().totalDebtors` |
| `GET /api/analytics/dashboard`, `/analytics/students` | `sg.nextPaymentDate < today`, SG soni, TRIAL ham (`AnalyticsService.java:56,303`) | `debtorSummary()` (o'quvchilar soni) |
| KPI (`StudentGroupRepository.java:138-154`) | `OVERDUE OR nextPaymentDate < today` | `overdue(today)` predikati, SG darajasida |
| `GET /api/payments/calculate-debt` | `kun/30 × narx − Σgross`, `double` (`PaymentService.java:730-787`) | `snapshot(sg)` — deprecated alias (§10) |
| Telegram eslatma (10:00) | `daysOverdue ≥ 3`, summa = `monthlyFee` (`PaymentReminderService.java:22-60`) | `debtors()`; summa = **qarz** (`debt`) |
| `GET /api/payments/stats.totalPending` | `Payment.status = PENDING` gross (amalda 0) | Σ debt (PENDING SG lar) |
| Ro'yxat doirasi | turlicha | Default — faqat `student.status = ACTIVE`. `scope=ALL` yopilgan SG qarzlarini ham ko'rsatadi (ochiq savol §13 #4). |

`GET /api/payments/expected`:
- SG `next_payment_date ∈ [from, to]` va holat `PAID` yoki `PENDING`;
- summa — `next_payment_amount`;
- default `from` — **bugun** (hozir ertaga, `PaymentScheduleConfig.java:18-20`).

---

## 5. To'lov oqimi

### 5.1 Yagona kod yo'li: preview == create
```
PaymentPlan PaymentPlanner.plan(PaymentCommand cmd, BillingState state, LocalDate today)   // SOF funksiya, DB yozmaydi
```

- **Preview:** `state` ni o'qiydi (qulfsiz) → `plan()` → javob.
- **Create:**
  1. idempotentlik tekshiruvi;
  2. qulf (§7);
  3. `state` ni qulf ostida qayta o'qiydi;
  4. **o'sha** `plan()`;
  5. rejadagi qatorlarni aynan yozadi (`PlanApplier.apply(plan)`).

Natija: preview'da ko'rsatilgan qatorlar (accrual, to'lov, chegirma, bonus, jarima) create'da bir xil bo'ladi. Faqat preview bilan create orasida holat o'zgargan bo'lsa (boshqa operator to'lov qilgan) farq qilishi mumkin. Javobda `planHash` qaytariladi; create so'roviga ixtiyoriy `expectedPlanHash` yuborilsa va mos kelmasa — 409 `payment.plan.changed` ("Hisob o'zgardi, qayta ko'ring").

### 5.2 `plan()` qadamlari
1. **Validatsiya.**
   - `amount > 0` (bundan mustasno: `amount = discountAmount` — sof chegirma);
   - butun so'm;
   - `0 ≤ discountAmount ≤ amount`;
   - `paymentDate ≤ today`;
   - `cashRegisterId` majburiy (I5, ochiq savol §13 #9);
   - CASH_AND_CARD: `cashPart + cardPart == cashAmount`;
   - onlayn usul → kassada `acceptOnlinePayment`;
   - ARCHIVED kassa → 400 (audit §9.12 #23).
2. **Yozilma.**
   - `groupId` berilgan → shu o'quvchining shu guruhdagi SG si. Topilmasa — **404** `payment.enrollment.notFound`. Jim fallback yo'q (#6).
   - `groupId` berilmagan:
     - faol yoki qarzli SG soni = 1 → o'sha;
     - 0 → 400 `payment.enrollment.required` (enrollmentsiz to'lov yo'q, #5);
     - > 1 → 400 `payment.group.required`.
3. **Kutilayotgan accrual.** `AccrualCalculator.dueCharges(sg, today)` — `accrueUpTo` bilan bir xil sof hisob. Qatorlar rejaga `ACCRUAL` sifatida kiradi.
4. **Sinov yozilma.** `sg.isTrial` bo'lsa: rejaga `convertTrial(paymentStartDate = paymentDate)` va uning accrual'i qo'shiladi (ochiq savol §13 #8).
5. **Ledger qatorlari.**
   - `PAYMENT +cashAmount`, bu yerda `cashAmount = amount − discountAmount`;
   - `DISCOUNT +discountAmount` (> 0 bo'lsa);
   - `applyBonuses != false` bo'lsa: shu o'quvchining `PENDING` STUDENT bonus/jarimalari (`effectiveDate ≤ paymentDate`, `groupId` null yoki shu SG) → `BONUS +x` / `PENALTY −y`.
6. **Natija.** Mavjud ledger + reja qatorlari ustida `BillingStatusService.snapshot` → `balanceAfter`, `debtAfter`, `statusAfter`, `nextPaymentDateAfter`, `nextPaymentAmountAfter`.

`useBalance` / `balanceAmount` **e'tiborsiz** (javobda `balanceUsed = 0`). Accrual modelida musbat balans keyingi davrni o'zi yopadi, shuning uchun "balansdan to'lash" amali kerak emas.

`periodFrom` / `periodTo` e'tiborsiz, `payment.periodStart/End = null`. Davr endi to'lovga bog'liq emas (§10).

### 5.3 `create` — yozish tartibi (bitta tranzaksiya)
```
1  Idempotency-Key bo'yicha payments da bor? → o'sha to'lovni qaytar (200, X-Idempotent-Replay: true);
   kalit bor, lekin body boshqa → 409
2  lock: student → sg → cash_register (§7)
3  plan = PaymentPlanner.plan(...)
4  accrualService.accrueUpTo(sg, today)         // rejadagi ACCRUAL qatorlari aynan shu yerda yoziladi
5  receipt = 'RCP-' || lpad(nextval('payment_receipt_seq')::text, 5, '0')
6  payments INSERT (status = PAID, amount, discountAmount, cashAmount, payableAmount = cashAmount,
   balanceUsed = 0, bonusDiscount = Σ bonus, studentGroup, idempotencyKey)
7  ledger: PAYMENT, DISCOUNT, BONUS…, PENALTY…
8  bonus_penalties: status = APPLIED, appliedToPaymentId, ledger_tx_id, student_group_id   (qulf ostida, status = PENDING sharti bilan)
9  cash: CashRegisterService.recordIncome(..., paymentId) → CashTransaction(INCOME, payment_id);
   payments.cash_transaction_id; Income(STUDENT_PAYMENT)
10 snapshot.refresh(sg), refresh(student); audit(PAYMENT)
```

**Chek raqami** — `count() + 1` o'rniga (`PaymentService.java:88-89`) DB sequence:
```sql
CREATE SEQUENCE IF NOT EXISTS payment_receipt_seq;
SELECT setval('payment_receipt_seq',
  COALESCE((SELECT max(substring(receipt_number from 5)::bigint) FROM payments WHERE receipt_number ~ '^RCP-[0-9]+$'), 0) + 1, false);
```
- Format o'zgarmaydi (`RCP-00043`, 99 999 dan keyin 6 xona).
- Sequence tranzaksiyadan tashqari ishlaydi, shuning uchun rollback bo'lgan to'lov raqamni "yeydi". Bo'shliq bo'lishi mumkin — bu me'yor.
- Shartnoma raqami (`ContractService.java:168`) uchun ham xuddi shunday `contract_number_seq` tavsiya qilinadi (billing-v2 qamrovida emas).

### 5.4 Preview javobi (yangi shakl, eski maydonlar saqlanadi)
```json
{
  "studentGroupId": 412, "groupName": "English B1",
  "lines": [
    {"type": "PERIOD_CHARGE", "amount": -630000, "effectiveDate": "2026-10-15", "note": "15.10–14.11", "pending": true},
    {"type": "PAYMENT",       "amount":  600000, "effectiveDate": "2026-10-16"},
    {"type": "BONUS",         "amount":   50000, "bonusPenaltyId": 77, "note": "Do'stini olib kelgani uchun"},
    {"type": "PENALTY",       "amount":  -20000, "bonusPenaltyId": 81, "note": "Kitob yo'qotildi"}
  ],
  "balanceBefore": 0, "balanceAfter": 0, "debtBefore": 630000, "debtAfter": 0,
  "statusAfter": "PAID", "nextPaymentDateAfter": "2026-11-15", "nextPaymentAmountAfter": 630000,
  "planHash": "9f2c…",
  "gross": 600000, "discount": 0, "payable": 600000, "balanceUsed": 0, "cashAmount": 600000,
  "studentBalance": 0
}
```

Oxirgi qatordagi maydonlar (`gross` … `studentBalance`) — eski front uchun (§11).

---

## 6. Ssenariylar (raqamli)

Umumiy ma'lumot:
- **Ali** — "English B1" guruhi, kurs narxi 700 000, `discountPercentage = 10` → `c = 630 000`, `paymentStartDate = 15.09.2026`.
- "Asosiy kassa" — `acceptOnlinePayment = true`.

### 6.1 Oddiy oy, qisman to'lov
| Sana | Hodisa | Ledger | Summa | B | Holat | next / amount |
|---|---|---|---|---|---|---|
| 15.09 00:10 | accrual 15.09–14.10 | PERIOD_CHARGE | −630 000 | −630 000 | PENDING (0 kun) | 15.09 / 630 000 |
| 16.09 | RCP-00042, naqd | PAYMENT | +630 000 | 0 | PAID | 15.10 / 630 000 |
| 15.10 00:10 | accrual 15.10–14.11 | PERIOD_CHARGE | −630 000 | −630 000 | PENDING | 15.10 / 630 000 |
| 19.10 | (vaqt o'tdi) | — | — | −630 000 | **OVERDUE** (4 kun) | 15.10 / 630 000 |
| 20.10 | 300 000 to'ladi | PAYMENT | +300 000 | −330 000 | OVERDUE (debtSince 15.10) | 15.10 / 330 000 |
| 22.10 | 330 000 to'ladi | PAYMENT | +330 000 | 0 | PAID | 15.11 / 630 000 |

Hozirgi tizimda 20.10 dagi 300 000 to'lov `nextPaymentDate` ni 15.11 ga surardi va holatni PAID qilardi (#2).

### 6.2 Oldindan to'lov
| Sana | Hodisa | Ledger | Summa | B | next |
|---|---|---|---|---|---|
| 15.09 | accrual | PERIOD_CHARGE | −630 000 | −630 000 | 15.09 |
| 16.09 | 3 oyga to'lov | PAYMENT | +1 890 000 | +1 260 000 | 15.12 (k = 2) |
| 15.10 | accrual | PERIOD_CHARGE | −630 000 | +630 000 | 15.12 |
| 15.11 | accrual | PERIOD_CHARGE | −630 000 | 0 | 15.12 |
| 15.12 | accrual | PERIOD_CHARGE | −630 000 | −630 000 | 15.12 (PENDING) |

### 6.3 Chegirma va yaxlitlash
| fee | d | Hisob | Xom qiymat | `c` (HALF_UP) |
|---|---|---|---|---|
| 700 000 | 10 | 700 000 × 90 / 100 | 630 000 | 630 000 |
| 650 000 | 15 | 650 000 × 85 / 100 | 552 500 | 552 500 |
| 333 333 | 7 | 333 333 × 93 / 100 | 309 999,69 | **310 000** |
| 555 555 | 12,5 | 555 555 × 87,5 / 100 | 486 110,625 | **486 111** |
| 500 000 | 100 | — | 0 | 0 (davr qatori bor, ledger yo'q) |
| PER_LESSON 80 000 | 10 | 80 000 × 90 / 100 | 72 000 | 72 000 |
| PER_LESSON 75 000 | 15 | 75 000 × 85 / 100 | 63 750 | 63 750 |

**Chegirma o'zgarishi.** 20.09 da `d` 10 dan 20 ga o'zgartirildi:
- 15.09 davri 630 000 bo'lib qoladi;
- 15.10 davri `700 000 × 80 / 100 = 560 000`.

### 6.4 To'lovni bekor qilish (Q5)
**Qoidalar:**
- Faqat SUPER_ADMIN.
- `reason` majburiy (3..500 belgi).
- Faqat `paymentDate ≥ bugun − 31 kun` (§13 #23 qarori). Eskisi — 400 `payment.cancel.tooOld`; tuzatish SA `MANUAL_ADJUST` orqali.
- Qulf (§7) ostida `payment.status == PAID` tekshiriladi. Allaqachon CANCELLED bo'lsa — 409 `payment.alreadyCancelled`.

**Yozuvlar:**
1. Har bir bog'langan ledger yozuvi (PAYMENT, DISCOUNT, BONUS, PENALTY) uchun `REVERSAL(−amount, related_tx_id, effective_date = asl)`.
2. Bonus/jarimalar → `PENDING`; `appliedToPaymentId`, `ledger_tx_id` null qilinadi.
3. Kassa: `CashTransaction(type = REVERSAL, payment_id, related_tx_id = asl INCOME, amount, cashPart/cardPart = asl)`. Kassa chelaklari asl taqsimot bo'yicha kamayadi; manfiyga tushishi mumkin (ochiq savol §13 #22).
4. `payment.status = CANCELLED`, `cancelled_at`, `cancelled_by_id`, `cancel_reason`.
5. Audit (`@Audited(PAYMENT_CANCEL)`).
6. Snapshot yangilanadi.

`Income` qatori o'chirilmaydi. Moliya hisobotlari `payment.status = PAID` bilan filtrlaydi (`FinanceService` `incomeByCategory` — join qo'shiladi).

**Misol — bonus bilan to'lov bekor qilinadi:**

| Sana | Hodisa | Ledger | Summa | B | Kassa (naqd) |
|---|---|---|---|---|---|
| 15.09 | accrual | PERIOD_CHARGE | −630 000 | −630 000 | 2 000 000 |
| 16.09 | RCP-00042 naqd 580 000; PENDING bonus #77 (50 000) qo'llandi | PAYMENT / BONUS | +580 000 / +50 000 | 0 | 2 580 000 |
| 17.09 | SA bekor qildi, sabab: "Summa xato kiritildi" | REVERSAL(PAYMENT) / REVERSAL(BONUS) | −580 000 / −50 000 | −630 000 | 2 000 000 (REVERSAL −580 000) |

17.09 holati: `debtSince = 15.09` (asl charge sanasi), 2 kun → **PENDING**. Bonus #77 yana PENDING — keyingi to'lovda qo'llanadi.

**Misol — accrualdan keyin bekor qilish:**
- 16.09 dagi 1 890 000 lik to'lov 20.10 da bekor qilinadi.
- O'sha paytda 15.09 va 15.10 charge'lari yozilgan: B = +630 000 → −1 260 000.
- FIFO bo'yicha `debtSince = 15.09` → 35 kun → OVERDUE.

### 6.5 Bonus va jarima (Q4)
`BonusPenalty` (STUDENT) ga `student_group_id` (ixtiyoriy) va `ledger_tx_id` qo'shiladi.

**Qo'llash:**
- (a) to'lovda (`applyBonuses`, default true) — to'lov SG siga;
- (b) to'lovsiz — `POST /api/bonus-penalties/{id}/apply` (SA, A). O'quvchida bir nechta SG bo'lsa `groupId` majburiy.

**Bekor qilish:**
- PENDING → CANCELLED, hozirgidek.
- APPLIED → CANCELLED — yangi: faqat SA, sabab majburiy, `REVERSAL` yoziladi.

| # | Holat | Ledger | B |
|---|---|---|---|
| a | B = −630 000; PENDING bonus 100 000 (tavsiya), PENDING jarima 30 000; to'lov 560 000, `applyBonuses = true` | PAYMENT +560 000, BONUS +100 000, PENALTY −30 000 | 0 → PAID |
| b | Xuddi shu, `applyBonuses = false` | PAYMENT +560 000 | −70 000 → PENDING; bonus/jarima PENDING qoladi |
| c | 20.09: B = +630 000 (15.10 davri oldindan to'langan); bonus 100 000 → `/apply` | BONUS +100 000 | +730 000; next 15.11 (k = 1) o'zgarmaydi, amount 630 000 → **530 000** |
| d | (a) dagi bonus SA tomonidan bekor qilindi | REVERSAL −100 000 | −100 000 |

Ikki parallel to'lov bitta bonusni ikki marta qo'llay olmaydi: bonus qatori `FOR UPDATE` + `status = PENDING` sharti (§7).

### 6.6 Sinov (Q6)
Qoida: `isTrial = true` bo'lganda:
- MONTHLY — accrual yo'q;
- PER_LESSON — `LESSON_CHARGE` yo'q;
- `paymentStartDate` dan oldingi sanalar hech qachon hisoblanmaydi (davomat keyin tahrirlansa ham).

**MONTHLY.** Said, kurs 700 000, `d = 0`, 01.10 da sinov bilan qo'shildi:

| Sana | Hodisa | Ledger | B | Holat |
|---|---|---|---|---|
| 01.10, 03.10 | sinov darslari | — | 0 | TRIAL |
| 06.10 | "To'lovli qilish", `paymentStartDate = 06.10` | PERIOD_CHARGE −700 000 (06.10–05.11) | −700 000 | PENDING |
| 07.10 | to'lov 700 000 | PAYMENT +700 000 | 0 | PAID, next 06.11 |

**Muqobil — sinovdagi o'quvchi to'g'ridan-to'g'ri to'lasa** (eski front "Qabul qilish" oqimi). 06.10 dagi to'lov preview'i:

```
convertTrial(paymentStartDate = 06.10)
PERIOD_CHARGE −700 000
PAYMENT       +700 000
→ B = 0, PAID
```

**PER_LESSON.** Dars narxi 80 000, `d = 0`:

| Sana | Davomat | `paymentStartDate` | Ledger | B |
|---|---|---|---|---|
| 01.10 | PRESENT (sinov) | — | — | 0 |
| 03.10 | PRESENT (sinov) | — | — | 0 |
| 06.10 | to'lovli qilindi | 06.10 | — | 0 |
| 06.10 | PRESENT | 06.10 | LESSON_CHARGE −80 000 | −80 000 |
| 08.10 | ABSENT | 06.10 | LESSON_CHARGE −80 000 | −160 000 |
| 10.10 | 03.10 dars PRESENT → LATE qilib tahrirlandi | 06.10 | — (03.10 < `paymentStartDate`) | −160 000 |

Hozirgi kodda sinovdagi PER_LESSON o'quvchidan ham yechiladi (#18).

### 6.7 Muzlatish va qaytarish (Q7)
**Model.**
- Muzlatish faqat **tanlangan SG** ga qo'llanadi. Bir nechta faol SG bo'lsa `groupId` majburiy.
- SG `isActive = false`, `exitReason = FROZEN` (hozirgidek, guruh ro'yxatlari va sig'imga ta'sir qilmasligi uchun). Yangi `frozen_from = freezeDate`.
- `Student.status = FROZEN` faqat **barcha** faol SG lar muzlatilganda.
- Unfreeze **o'sha SG ni** qayta faollashtiradi: `isActive = true`, `frozen_from = null`, `paymentStartDate` yangilanadi. Yangi SG yaratilmaydi, balans ko'chirish yo'q (`UNFREEZE` yozuvi kerak emas). Guruh sig'imi tekshiriladi.
- `freezeDate` default bugun, `≤ today` (ochiq savol §13 #2).

**MONTHLY qaytarim:**
```
P            = freezeDate ni o'z ichiga olgan hisoblangan davr
periodDays   = P.end − P.start + 1
unusedDays   = P.end − freezeDate + 1
refund       = Money.uzs(P.amount × unusedDays / periodDays)        // P.amount — yozilgan charge
PERIOD_REFUND +refund (related_tx_id = P.charge_tx, effective_date = freezeDate), P.status = PARTIALLY_REFUNDED
freezeDate dan keyin boshlangan (allaqachon hisoblangan) davrlar → to'liq PERIOD_REFUND, status = REFUNDED
```

| Davr | freezeDate | used / unused | Hisob | refund |
|---|---|---|---|---|
| 15.09–14.10 (30), 630 000 | 28.09 | 13 / 17 | 630 000 × 17 / 30 | **357 000** |
| 15.10–14.11 (31), 630 000 | 01.11 | 17 / 14 | 630 000 × 14 / 31 = 284 516,13 | **284 516** |
| 15.10–14.11 (31), 630 000 | 15.10 (billing kuni) | 0 / 31 | to'liq | **630 000** |

**To'liq misol** (Ali, 15.09 davri to'langan):

| Sana | Hodisa | Ledger | B | SG holati |
|---|---|---|---|---|
| 15.09 / 16.09 | charge / to'lov | −630 000 / +630 000 | 0 | PAID |
| 28.09 | muzlatish | PERIOD_REFUND +357 000 | +357 000 | FROZEN |
| 15.10 | (job) | — (`frozen_from` bor) | +357 000 | FROZEN |
| 20.10 | unfreeze, `paymentStartDate = 20.10` | PERIOD_CHARGE −630 000 (20.10–19.11) | −273 000 | PENDING, next 20.10 / 273 000 |

Agar 15.09 davri **to'lanmagan** bo'lsa, 28.09 da B = −630 000 + 357 000 = −273 000. Muzlatilgan bo'lsa ham `debtSince = 15.09` bo'yicha OVERDUE — qarz holatdan ustun (§4.2, ochiq savol §13 #5).

**Ko'p guruh.** Vali A va B guruhlarida. Faqat A muzlatildi → `Student.status = ACTIVE`, holat B dan olinadi. Keyin B ham muzlatilsa → `Student.status = FROZEN`.

**PER_LESSON muzlatish.** Ledger yozuvi yo'q. Balans (masalan +240 000 = 3 dars) SG da qoladi va unfreeze'dan keyin ishlatiladi. Hozirgi `FREEZE` delta yozuvi (`StudentService.java:982-993`) to'xtatiladi.

Preview (`/freeze/preview`) xuddi shu `FreezePlanner.plan()` dan foydalanadi (§5.1 tamoyili). Javobga `refundLines[]` qo'shiladi.

### 6.8 Guruhga ko'chirish — transfer-group (Q8)
**Qoidalar:**
1. Yangi SG ga ko'chadi: `paymentType`, `monthlyPriceOverride`, `discountPercentage`, `lessonPrice`, `isTrial`.
2. **Langar uzluksiz.** Eski SG MONTHLY bo'lsa, yangi SG `paymentStartDate` = eski SG ning keyingi hisoblanmagan davr boshi. Joriy davr eski SG da to'langan/hisoblangan — ikki marta olinmaydi.
3. **Balans** `TRANSFER_OUT` / `TRANSFER_IN` juftligi bilan to'liq ko'chadi (musbat ham, manfiy ham). Manfiy bo'lsa `TRANSFER_IN.effective_date = eski debtSince` — qarz muddati davom etadi.
4. Eski SG `isActive = false`, `exitReason = TRANSFERRED`, B = 0.
5. Override bo'lmasa, yangi SG yangi guruh kursining narxini keyingi davrdan oladi (ochiq savol §13 #17).

**Misol 1 — musbat balans.** A: override 600 000, `d = 10` → `c = 540 000`, langar 15. B guruhiga 25.10 da ko'chiriladi.

| Sana | SG | Ledger | Summa | B(A) | B(B) |
|---|---|---|---|---|---|
| 15.10 | A | PERIOD_CHARGE | −540 000 | −540 000 | — |
| 16.10 | A | PAYMENT | +1 080 000 | +540 000 | — |
| 25.10 | A | TRANSFER_OUT | −540 000 | 0 | — |
| 25.10 | B | TRANSFER_IN (override 600 000, d 10, MONTHLY, `paymentStartDate = 15.11`) | +540 000 | 0 | +540 000 |
| 15.11 | B | PERIOD_CHARGE | −540 000 | | 0 |

**Misol 2 — qarz.** A: B = −540 000, `debtSince = 15.10`. 25.10 da transfer:

```
A: TRANSFER_OUT +540 000          → 0
B: TRANSFER_IN  −540 000 (effective 15.10)
   → 25.10 da 10 kun → OVERDUE (qarzdor ro'yxatidan "tushib qolmaydi")
```

Hozirgi kodda balans eski SG da qoladi va yangi SG kurs narxi + MONTHLY bilan boshlanadi (`StudentService.java:398-418`).

`bulkPromote` (`PromotionService.java:92-165`) ham shu `TransferService` dan foydalanadi (bitta kod yo'li).

### 6.9 PER_LESSON — narx o'zgarishi va qayta saqlash
Dars narxi 80 000, `d = 10` → `l = 72 000`.

| Sana | Hodisa | Ledger | B |
|---|---|---|---|
| 06.10 | PRESENT | LESSON_CHARGE −72 000 | −72 000 |
| 08.10 | PRESENT | LESSON_CHARGE −72 000 | −144 000 |
| 08.10 | xuddi shu davomat qayta yuborildi (double-click) | — (net(attendance) = −72 000, kerakli holat bilan bir xil) | −144 000 |
| 09.10 | narx 90 000 ga o'zgardi (`l = 81 000`) | — | −144 000 |
| 10.10 | 08.10 → EXCUSED | LESSON_REFUND **+72 000** (asl charge, `related_tx_id`) | −72 000 |
| 13.10 | PRESENT | LESSON_CHARGE −81 000 | −153 000 |

Hozirgi kodda 10.10 dagi qaytarim yangi narx bilan bo'lardi (#17).

### 6.10 Guruhdan chiqish (remove-student)
- SG yopiladi (`leaveDate`). `leaveDate` dan keyin boshlanadigan davrlar hisoblanmaydi.
- Joriy davr qaytarilmaydi — hozirgi xatti-harakat (ochiq savol §13 #3).
- Balans SG da qoladi. Qarz bo'lsa SG OVERDUE bo'lib turadi, `scope = ALL` qarzdorlar ro'yxatida ko'rinadi (ochiq savol §13 #4).

---

## 7. Konkurentlik va idempotentlik

### 7.1 Qaror: pessimistic lock (`SELECT … FOR UPDATE`), `@Version` emas
Hozir loyihada lock faqat `PayrollRepository.findByIdForUpdate` da bor. Balans va kassa read-modify-write qulfsiz:
- `BalanceTransactionService.java:68-71`
- `CashRegisterService.java:661-665`

**Lost update misoli:** kassada naqd 1 000 000. Ikki operator bir vaqtda 500 000 dan to'lov kiritadi. Ikkala tranzaksiya 1 000 000 ni o'qiydi va 1 500 000 yozadi — 500 000 "yo'qoladi".

**Asoslash:**
1. **Qaynoq hisoblagichlar.** Bitta SG ga bir vaqtda to'lov, davomat (20 o'quvchili guruh bir so'rovda) va 00:10 job yozadi. `@Version` bilan har to'qnashuv foydalanuvchiga xato yoki qayta urinish bo'ladi. Davomat ommaviy saqlashda bitta to'qnashuv butun partiyani yiqitadi.
2. **Yozuvlar qisqa.** Bitta tranzaksiyada 2–6 ta INSERT. PostgreSQL qator qulfi arzon, kutish millisekundlar.
3. **Invariantlar bir nechta qatorni qamraydi** (SG + student agregati + kassa). Optimistik versiyalash har birida alohida xato beradi. Qulf esa bitta izchil oyna beradi.
4. **Deterministik.** Qayta urinish mantiqi (retry) yozilmaydi.

**Sozlash:**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
Optional<StudentGroup> findByIdForUpdate(Long id);
// xuddi shunday: StudentRepository, CashRegisterRepository, PaymentRepository, BonusPenaltyRepository
```

Timeout → 409 `concurrency.busy` ("Bir vaqtda boshqa amal bajarilmoqda, qayta urinib ko'ring").

### 7.2 Qulf tartibi (deadlock yo'q)
```
Student  →  StudentGroup(lar) id o'sish tartibida  →  Payment  →  BonusPenalty(lar) id o'sishida  →  CashRegister(lar) id o'sishida
```

| Amal | Qulflar |
|---|---|
| To'lov | student, sg, bonus_penalties (PENDING), cash_register |
| Bekor qilish | student, sg, payment, bonus_penalties, cash_register |
| Accrual | student, sg |
| Davomat (partiya) | o'quvchilar id o'sishida: student → sg (faqat PER_LESSON SG lar) |
| Transfer | student, min(sgA, sgB), max(sgA, sgB) |
| Muzlatish / unfreeze | student, sg |
| Kassalararo transfer | min(reg), max(reg) |

`student` qatori qulflanadi, chunki agregat (`student.balance = Σ`) bir nechta SG dan hisoblanadi. Ikki SG parallel yangilansa, agregat oxirgi yozuvchida eskirgan bo'lib qolardi.

### 7.3 Idempotentlik

| Amal | Mexanizm |
|---|---|
| To'lov | `Idempotency-Key` sarlavhasi (frontend dialog ochilganda UUID yaratadi) → `payments.idempotency_key UNIQUE`. Takror: o'sha javob 200 + `X-Idempotent-Replay: true`. Boshqa body bilan takror — 409. Kalit bo'lmasa ham ishlaydi (eski front), lekin himoyasiz. |
| Accrual | qulf + `UNIQUE (student_group_id, period_start)` |
| Davomat (PER_LESSON) | `attendance UNIQUE (student_id, group_id, attendance_date)` (entity'da bor). SG qulfi ostida `desired = isBillable(status) && date ≥ paymentStartDate && !isTrial`, `current = net(LESSON_* by attendance) < 0`. Faqat farq bo'lsa yoziladi. Find-then-insert poygasi (`AttendanceService.java:97-110`) `DataIntegrityViolation` da update'ga qaytish bilan yopiladi. |
| Bekor qilish | payment qulfi + `status = PAID` sharti |
| Bonus qo'llash | bonus qulfi + `status = PENDING` sharti |
| Chek raqami | DB sequence |
| Muzlatish | SG qulfi + `frozen_from IS NULL` sharti; takroriy → 409 `student.freeze.already` |

`lessonsAttended++` (`StudentPaymentLifecycleService.java:46-47`) idempotent emas. v2 da u hisobdan chiqariladi (`count(attendance)` — hosila), ustun yozilmaydi.

---

## 8. Ko'p guruhli o'quvchi

Student darajasidagi maydonlar endi faqat SG lardan **hosila**. Ular `BillingSnapshotService.refreshStudent` da, student qulfi ostida yoziladi:

```
student.balance           = Σ sg.balance                    (barcha SG, yopilganlari ham — I1)
student.debt              = Σ max(0, −sg.balance)
student.monthlyFee        = Σ c(sg)   (faol, MONTHLY, trial emas, muzlatilmagan; chegirmadan keyin)
student.nextPaymentDate   = min(sg.nextPaymentDate)          (faol, trial/frozen emas)
student.nextPaymentAmount = Σ sg.nextPaymentAmount, faqat sg.nextPaymentDate == student.nextPaymentDate bo'lganlar
student.paymentStatus     = OVERDUE, agar ∃ SG OVERDUE
                          → PENDING, agar ∃ SG PENDING
                          → FROZEN,  agar barcha faol SG muzlatilgan (≥ 1)
                          → TRIAL,   agar barcha faol, muzlatilmagan SG trial
                          → PAID
student.status            = FROZEN ⇔ barcha faol SG muzlatilgan
```

**Kesishuv yo'q.** Bir SG ning musbat balansi boshqa SG qarzini avtomatik yopmaydi (ochiq savol §13 #6). Shu sababli `student.balance ≥ 0` bo'lsa ham `debt > 0` bo'lishi mumkin. UI qarzni SG kesimida ko'rsatadi.

**Misol — Vali.** A (Matematika, 500 000, langar 05) va B (Ingliz tili, 500 000, langar 20), chegirmasiz.

| Sana | Hodisa | B(A) | B(B) | student.balance | student.debt | Holat | next |
|---|---|---|---|---|---|---|---|
| 05.10 | A charge; A ga 500 000 to'lov | 0 | 0 | 0 | 0 | PAID | 20.10 (B langari; A — 05.11) |
| 20.10 | B charge | 0 | −500 000 | −500 000 | 500 000 | PENDING | 20.10 / 500 000 |
| 24.10 | — | 0 | −500 000 | −500 000 | 500 000 | **OVERDUE** | 20.10 |
| 25.10 | 1 000 000 to'lov, `groupId = A` (xato) | +1 000 000 | −500 000 | +500 000 | 500 000 | OVERDUE | 20.10 |

`student.monthlyFee = 1 000 000`.

Hozirgi tizim bilan solishtirish: 05.10 dagi 500 000 to'lovda `fee = student.monthlyFee = 1 000 000` olinadi, `months = floor(500 000 / 1 000 000) = 0`, PERIOD_CHARGE yozilmaydi (#1). v2 da narx faqat SG dan olinadi.

25.10 dagi xato to'lovni tuzatish uchun ikki yo'l bor:
- bekor qilib qayta kiritish (§6.4);
- yangi `POST /api/students/{id}/balance-transfer` (SA): A → B, `TRANSFER_OUT` / `TRANSFER_IN` (ochiq savol §13 #6).

O'quvchi kartasida har SG uchun alohida qator bo'ladi: `balance`, `debt`, `debtSince`, `status`, `nextPaymentDate`, `nextPaymentAmount`, `effectiveFee`, `billingDay`.

---

## 9. Migratsiya

### 9.1 Boshlang'ich holat
- Tizim **18.09.2026** dan beri ishlatilmoqda (G — go-live). Shundan keyin kiritilgan to'lovlar to'liq CRM da.
- Import qilingan o'quvchilarning `paymentStartDate` si G dan oldin bo'lishi mumkin. Ularning G gacha bo'lgan tarixi tashqarida.
- **T** — cutover (deploy) sanasi, masalan 05.10.2026.

### 9.2 Tanlangan yondashuv: qayta qurish + bitta ochilish yozuvi
Buyurtmachi ikki variantni sanagan — "o'tgan davrlarni qayta qurish" va "ochilish balansi". Bu yerda ikkalasi birlashtiriladi:
- har SG uchun davrlar `R` nuqtadan T gacha **qayta quriladi** (haqiqiy `PERIOD_CHARGE` + `billing_periods`, FIFO to'g'ri ishlashi uchun);
- eski modelning davr debetlari **bitta `MIGRATION` yozuvi** bilan neytrallanadi.

Ledger append-only qoladi (I3), har SG bo'yicha tushunarli iz qoladi.

**SG toifalari:**

| Toifa | Shart | Amal |
|---|---|---|
| M | MONTHLY, trial emas | qayta qurish (pastda) |
| L | PER_LESSON | ledger allaqachon dars bo'yicha; faqat tekshiruv (anomaliya A13), yozuv yo'q |
| T | `isTrial` | yozuv yo'q; trial'da to'lov yoki PERIOD_CHARGE bo'lsa — A6 |
| F | muzlatilgan (`exitReason = FROZEN`) yoki yopilgan | balans saqlanadi, davr yo'q; muzlatishda qaytarilmagan qoldiq — A9 |

**M toifasi uchun boshlang'ich nuqta R:**
```
agar paymentStartDate ≥ G:            R = paymentStartDate                     // butun tarix CRM da
aks holda (import):
   SG ning CRM to'lovi yo'q:          R = eski sg.nextPaymentDate (davr to'riga moslab)  // "shu sanagacha tashqarida to'langan"
   CRM to'lovi bor:                   R = min( G dan keyingi birinchi davr boshi,
                                               eng erta CRM to'lovining periodStart'i (to'rga moslab) )
start(n) < R               → billing_periods(status = MIGRATED), ledger yo'q
R ≤ start(n) ≤ T           → billing_periods(status = CHARGED) + PERIOD_CHARGE(−c_n, effective = start(n))
```

**Yozuvlar va maqsad balans:**
```
L          = joriy sg.balance (= Σ ledger; farq bo'lsa A11)
legacyPC   = Σ |PERIOD_CHARGE| (eski, to'lov paytida yozilganlar)
repairAdj  = Σ MANUAL_ADJUST '[ledger-repair]%'
MIGRATION  = +legacyPC − repairAdj                     (0 bo'lsa yozilmaydi; effective = T)
charges    = Σ c_n (R ≤ start(n) ≤ T)
target     = L + legacyPC − repairAdj − charges
```

**Narx c_n — o'tgan davrlar uchun.** `discountPercentage` qo'llanmaydi, `c_n = fee` (eski tizim uni hech qachon qo'llamagan). Eski to'lovdagi `discountAmount` allaqachon eski PERIOD_CHARGE ni kamaytirgan, shuning uchun neytrallashda u o'z-o'zidan hisobga kiradi. Chegirma T dan keyingi birinchi accrual'dan qo'llanadi (ochiq savol §13 #18).

### 9.3 Raqamli misollar (T = 05.10.2026, G = 18.09.2026, fee 700 000)
| SG | Tarix | L | legacyPC | R | Charges | MIGRATION | target | Yangi holat (05.10) | Eski holat |
|---|---|---|---|---|---|---|---|---|---|
| 1 | start 20.09; 20.09 da 700 000 | 0 | 700 000 | 20.09 | 20.09: 700 000 | +700 000 | **0** | PAID, next 20.10 | PAID |
| 2 | start 22.09; to'lov yo'q | 0 | 0 | 22.09 | 22.09: 700 000 | — | **−700 000** | OVERDUE (13 kun) | OVERDUE |
| 3 | start 18.09; 18.09 da 1 400 000 (2 oy) | 0 | 1 400 000 | 18.09 | 18.09: 700 000 | +1 400 000 | **+700 000** | PAID, next 18.11 | PAID |
| 4 | start 25.09; 25.09 da 300 000 | +300 000 | 0 (months = 0) | 25.09 | 25.09: 700 000 | — | **−400 000** | OVERDUE (10 kun) | **PAID** (#2 xatosi) |
| 5 | import, start 01.07, eski next 01.09, CRM to'lovi yo'q | 0 | 0 | 01.09 | 01.09, 01.10: 1 400 000 | — | **−1 400 000** | OVERDUE (34 kun) | OVERDUE, totalDebt 700 000 |
| 6 | import, start 01.08; 20.09 da 700 000 (periodStart 01.09) | 0 | 700 000 | 01.09 | 01.09, 01.10: 1 400 000 | +700 000 | **−700 000** | OVERDUE (4 kun, FIFO: 01.09 yopilgan) | OVERDUE |
| 7 | start 15.09, `discountPercentage = 10`; 15.09 da gross 700 000, `discountAmount = 70 000`, naqd 630 000 | 0 | 630 000 | 15.09 | 15.09: 700 000 | +630 000 | **−70 000** ⚠ | PENDING → **A14** (qo'lda) | PAID |

> **Tuzatish (6-bosqich, `MigrationTest`):**
> - 7-qatorning yangi holati **OVERDUE**, PENDING emas. `debtSince = 15.09`, 05.10 da 20 kun o'tgan, grace esa 3 kun (§4.2).
> - `a14UsePayable=true` bo'lsa target 0 va holat PAID bo'ladi.
> - Jadvaldagi qolgan qatorlar kod natijasi bilan mos keladi.

7-qator: eski bir martalik chegirma (70 000) davr narxini kamaytirgan, yangi modelda esa davr to'liq narxda. Bu holat dry-run'da **A14** bilan belgilanadi. Taklif: bunday SG lar uchun `c_n` o'rniga eski to'lovning `payable` qiymati olinadi — buyurtmachi tasdiqlaydi (§13 #18).

### 9.4 Dry-run
`POST /api/admin/billing/migration/dry-run?cutover=2026-10-05` (SA). Natija `billing_migration_runs` ga saqlanadi va `GET …/runs/{id}/report.xlsx` orqali olinadi. Hech narsa yozilmaydi.

**Har SG qatori:** o'quvchi, guruh, toifa, `paymentStartDate`, R, davrlar ro'yxati, L, legacyPC, repairAdj, MIGRATION, charges, target, `target − L`, eski holat / eski next / eski "qarz" (`getDebtorsByDate` formulasi bilan), yangi holat / debtSince / next / amount, anomaliyalar.

**Anomaliyalar:**

| Kod | Tavsif |
|---|---|
| A1 | `fee = 0` (narx yo'q) — davr yozilmaydi |
| A2 | O'quvchida `studentGroup = null` to'lovlar (enrollmentsiz, #5) |
| A3 | `balanceUsed > 0` boshqa SG balansidan olingan (student darajasidagi `balanceUsed`, `PaymentService.java:309`) |
| A4 | Bir o'quvchida bir guruhda > 1 faol SG yoki bir kunda yaratilgan ikki faol SG (eski front `edit-student` xatosi, §11) — **accrual ikki marta oladi** |
| A5 | `periodFrom/periodTo` qo'lda berilgan to'lovlar; eski next R dan oldin |
| A6 | Trial SG da to'lov yoki PERIOD_CHARGE |
| A7 | `discountPercentage > 0` (hech qachon qo'llanmagan) |
| A8 | `[ledger-repair]` MANUAL_ADJUST bor |
| A9 | MONTHLY da UNFREEZE / FREEZE yozuvlari; muzlatilgan SG da balans ≠ 0 |
| A10 | `student.balance ≠ Σ sg.balance` (kassa `addIncome` izi, #12) |
| A11 | `sg.balance ≠ Σ ledger` |
| A12 | `abs(target − L) > fee` — katta siljish, qo'lda ko'rish |
| A13 | PER_LESSON: `paymentStartDate` dan oldingi yoki trial darslari uchun LESSON_CHARGE |
| A14 | Eski to'lovda `discountAmount > 0` (9.3, 7-qator) |
| A15 | `monthlyPriceOverride == course.monthlyPrice` (avtomatik to'ldirilgan, individual emas — §9.5) |
| A16 | catch-up chegarasi (24 davr) oshdi |

**Jami bo'lim:**
- qarzdorlar soni — eski 4 ta ta'rif bo'yicha (dashboard, analytics, debtors, KPI) va yangi;
- Σ qarz eski / yangi;
- Σ balans eski / yangi;
- holat o'tishlari matritsasi (PAID → OVERDUE va h.k.).

### 9.5 Override tozalash
`monthlyPriceOverride` hozir har recalc'da kurs narxi bilan to'ldiriladi (`PaymentScheduleService.java:156-169`, `StudentService.java:734-746`). Natijada "individual narx"ni ajratib bo'lmaydi.

Migratsiyada `override == course.monthlyPrice` bo'lgan SG lar uchun `override = NULL` (A15 ro'yxati bilan, buyurtmachi tasdig'idan keyin). Shunda kurs narxi o'zgarsa, ular keyingi davrdan yangi narxni oladi.

### 9.6 Cutover rejasi
1. **T − 3 kun.** Prod nusxasida (`pg_dump` → staging) v2 bilan dry-run. Hisobot buyurtmachiga beriladi. A-ro'yxatlar bo'yicha qarorlar olinadi: istisnolar, qo'lda tuzatishlar, A14 qoidasi.
2. **T, texnik oyna (~30 daq).**
   - `phase3-fixes` allaqachon deploy qilingan bo'lishi kerak.
   - `pg_dump` (zaxira, fayl nomi `pre-billing-v2-<sana>.dump`).
   - `V52__billing_v2.sql` qo'lda bajariladi — faqat qo'shimcha (additive): yangi jadval/ustun/sequence/indeks, eski kod bilan mos.
   - v2 jar `app.billing.enabled=false` bilan deploy qilinadi. To'lov, accrual, muzlatish yozuvlari 503 `billing.maintenance` beradi; o'qish ishlaydi.
3. Dry-run qayta (prod, oxirgi holat) → `runId`.
   - `POST /api/admin/billing/migration/apply?runId=…&exclude=…` — faqat dry-run'dan keyin `max(balance_transactions.id)` va `max(payments.id)` o'zgarmagan bo'lsa.
   - Har SG alohida tranzaksiyada; yozuvlar `migration_run_id` bilan.
4. `GET /api/admin/billing/verify` — I1, I4, I5 tekshiruvi, 0 farq bo'lishi kerak.
5. `app.billing.enabled=true` → job yoqiladi, to'lovlar ochiladi.

### 9.7 Qaytarish (rollback)
| Bosqich | Usul |
|---|---|
| 5-qadamdan oldin (to'lov qabul qilinmagan) | `pg_dump` dan tiklash + oldingi jar (`main`) — ma'lumot yo'qolmaydi |
| 5-qadamdan keyin, ≤ 72 soat | Tiklash + oldingi jar. Cutover'dan keyin kiritilgan to'lovlar `GET /api/admin/billing/payments-since?from=T` (xlsx) bilan oldindan eksport qilinadi va eski UI orqali qayta kiritiladi. Bekor qilishlar va muzlatishlar ham shu ro'yxatda. |
| > 72 soat | Rollback yo'q — faqat oldinga tuzatish: `MANUAL_ADJUST`, `REVERSAL`, migratsiyani SG bo'yicha qayta qo'llash |

**Qisman rollback (bitta SG noto'g'ri migratsiya qilingan bo'lsa):** `POST /api/admin/billing/migration/revert-sg?runId&sgId`:
- shu run'ning SG yozuvlariga `REVERSAL` yoziladi;
- `billing_periods` (CHARGED, MIGRATED) shu run'niki o'chiriladi — yagona DELETE istisnosi, faqat `migration_run_id` bo'yicha;
- SG `MIGRATION_PENDING` holatiga o'tadi (accrual o'tkazib yuboradi) va qo'lda qayta qo'llanadi.

---

## 10. O'zgaradigan endpointlar va o'lik kod

### 10.1 Sxema (`db/migration/V52__billing_v2.sql`)
Flyway yo'q — skript qo'lda, idempotent (`IF NOT EXISTS`) bajariladi. Startup'da `BillingSchemaGuard` (ApplicationRunner) quyidagilarni tekshiradi:
- sequence;
- `billing_periods` UNIQUE;
- `payments.idempotency_key` UNIQUE.

Birortasi yo'q bo'lsa — `app.billing.enabled` majburan `false` va ERROR log.

| Jadval | O'zgarish |
|---|---|
| `billing_periods` | yangi (§3.3), `UNIQUE (student_group_id, period_start)` |
| `balance_transactions` | + `effective_date` (backfill `created_at::date`), `related_tx_id`, `billing_period_id`, `migration_run_id`; indeks `(student_group_id, effective_date, id)` |
| `student_groups` | + `frozen_from date`, `debt_since date`, `next_payment_amount numeric(12,2)`; `payment_status` → enum qiymatlari; `discount_percentage` NULL → 0 |
| `students` | + `debt numeric(12,2)`, `next_payment_amount numeric(12,2)` |
| `payments` | + `cancelled_at`, `cancelled_by_id`, `cancel_reason`, `idempotency_key varchar(64)` (UNIQUE, NULL ruxsat), `cash_transaction_id` |
| `cash_transactions` | + `payment_id`, `related_tx_id`; `type` += `REVERSAL` |
| `bonus_penalties` | + `student_group_id`, `ledger_tx_id`, `cancel_reason` |
| yangi | `payment_receipt_seq`, `billing_migration_runs`, `billing_job_runs` |

Konfiguratsiya (`application.yml`, `app.billing.*`):

| Kalit | Default |
|---|---|
| `enabled` | `true` |
| `grace-days` | `3` |
| `accrual-cron` | `"0 10 0 * * *"` |
| `max-catch-up` | `24` |
| `reminder-cron` | `"0 0 10 * * *"` |

### 10.2 Endpointlar
| Endpoint | Rollar | Request farqi | Response farqi |
|---|---|---|---|
| `POST /api/payments` | SA, A, ACC | `amount > 0`, butun; `cashRegisterId` **majburiy**; `groupId` > 1 SG bo'lsa majburiy; `periodFrom/To`, `useBalance`, `balanceAmount` e'tiborsiz (deprecated); `Idempotency-Key` sarlavhasi; ixtiyoriy `expectedPlanHash` | + `studentGroupId`, `lines[]`, `balanceAfter`, `debtAfter`, `statusAfter`, `nextPaymentDate`, `nextPaymentAmount`; `periodFrom/To = null`, `balanceUsed = 0` |
| `POST /api/payments/preview` | SA, A, ACC | Body = `PaymentRequest` (create bilan bir xil; eski `PaymentPreviewRequest` maydonlari ham qabul qilinadi) | §5.4 (eski maydonlar saqlanadi) |
| **`POST /api/payments/{id}/cancel`** (yangi) | **SA** | `{reason*}` | `PaymentResponse` + `status = CANCELLED`, `cancelledAt`, `cancelledByName`, `cancelReason`, `reversalLines[]` |
| `GET /api/payments` | SA, A, ACC | `status` filtri (`PAID`, `CANCELLED`) | + `status`, `cancel*`; `meta.totalCashAmount` — faqat PAID |
| `GET /api/payments/student/{id}` | **@PreAuthorize qo'shiladi** (SA, A, ACC, SM) | — | + `status` |
| `GET /api/payments/debtors` | SA, A, ACC | + `scope` (`ACTIVE` default / `ALL`), `minDays`, `groupId`, `page`/`size` | Qator: `studentId`, `fullName`, `phone`, `debt`, `debtSince`, `daysOverdue`, `status`, `groups[] {groupId, groupName, debt, debtSince, daysOverdue}`. **Eski moslik:** `totalDebt (= debt)`, `monthlyAmount (= Σ c)`, `monthsUnpaid (= ceil(debt / monthlyAmount))`, `nextPaymentDate (= debtSince)`, `groupName` (birinchi). Ro'yxatda faqat OVERDUE. |
| `GET /api/payments/debtors/summary` | SA, A, ACC | + `scope` | `{totalDebtors, overdue7Plus, totalDebt}` — yagona metoddan |
| `GET /api/payments/expected` | SA, A, ACC | default `from = bugun` | `amount = nextPaymentAmount`; PENDING (bugungi/grace ichidagi) qatorlar ham |
| `GET /api/payments/calculate-debt` | SA, A, ACC | — | **Deprecated:** `snapshot(sg)` dan `{debt, balance, debtSince, status}`; v2.1 da o'chiriladi |
| `GET /api/payments/stats` | SA, A, ACC | — | `totalPending` = Σ debt (PENDING); `totalCollected` — faqat PAID |
| `GET /api/dashboard/stats`, `/api/analytics/dashboard`, `/api/analytics/students`, KPI (`/api/teachers/**/kpi*`, `/api/analytics/staff*`) | o'zgarmaydi | — | Raqamlar yagona ta'rifdan (§4.5), shakl o'zgarmaydi |
| `GET /api/students/{id}`, ro'yxat | — | — | + `debt`, `nextPaymentAmount`; `monthlyFee` = Σ `c` (chegirmadan keyin); `groups[]` + `billingDay`, `effectiveFee`, `debtSince`, `frozenFrom` |
| `GET /api/students/{id}/balance-history` | **+ ACC** | — | + `effectiveDate`, `relatedTxId`, `billingPeriod {start, end}`, `paymentId`, `receiptNumber` |
| `POST /api/students/{id}/balance-adjust` | SA | `amount` butun; `effectiveDate` ixtiyoriy | — |
| **`POST /api/students/{id}/balance-transfer`** (yangi, §13 #6) | SA | `{fromGroupId*, toGroupId*, amount*, note*}` | ledger juftligi |
| `POST /api/students/{id}/freeze/preview`, `/freeze` | SA, A | `groupId` (> 1 SG bo'lsa majburiy), `freezeDate` (default bugun) | + `refundLines[]`, `balanceAfter`; eski `groups[]`, `totalBalance` saqlanadi |
| `POST /api/students/{id}/unfreeze` | SA, A | `groupId*`, `paymentStartDate` | O'sha SG qayta faollashadi (yangi SG yo'q); + `accrualLines[]` |
| `POST /api/students/{id}/transfer-group` | SA, A | — | Narx/rejim/chegirma ko'chadi; + `transferLines[]`, yangi SG `paymentStartDate` |
| `PATCH /api/students/{id}/payment-start-date` | SA, A, ACC | + `groupId` (hozir "oxirgi faol SG") | 409 `billing.anchor.overlap`; + `accrualLines[]` |
| `POST /api/groups/students`, `/students/create-and-add`, lead convert, import | — | `discountPercentage` null → 0, 0..100 | + `accrualLines[]` (`paymentStartDate ≤ bugun` bo'lsa darhol charge) |
| `POST /api/groups/{id}/remove-student`, `DELETE /api/groups/{g}/students/{s}` | — | — | + `balance`, `debt` (yopilgan SG) |
| `/api/bonus-penalties` | — | STUDENT uchun + `groupId`; **yangi** `POST /{id}/apply` (SA, A); `PATCH /{id}/cancel` APPLIED uchun (SA, `reason*`) | + `ledgerTxId`, `studentGroupId` |
| `POST /api/cash-registers/{id}/income` | — | `studentId` bilan → **400** `cash.income.studentPaymentViaPayments` ("O'quvchi to'lovi /api/payments orqali") | — |
| `/api/admin/repair/recalculate-payment-dates`, `/fix-payment-periods`, `/rebuild-monthly-ledger`, `/verify-balances` | SA | — | **O'chiriladi** (410). O'rniga `/api/admin/billing/*`. |
| **`/api/admin/billing/*`** (yangi) | SA | `verify`, `accrue?date`, `refresh-snapshots`, `migration/dry-run`, `migration/apply`, `migration/runs/{id}/report.xlsx`, `migration/revert-sg`, `payments-since`, `job-runs` | — |

### 10.3 O'lik kod (o'chiriladi)
| Joy | Sabab |
|---|---|
| `service/PeriodChargeFormula.java` (butun fayl) | "to'lovdan oylar" modeli yo'q |
| `PaymentService`: `resolvePaymentPeriod` (382-424), `applyPerLessonPaymentPeriod` (263-276), `calculate()` dagi `balanceUsed` qismi, `calculateStudentDebt` (730-787, alias bilan almashtiriladi), `getDebtorsLegacy` (630-643), `getAllPayments(LocalDate, LocalDate)` (563-572) | v2 oqimi / chaqiruvchisi yo'q |
| `PaymentScheduleService`: `calculateNextPaymentDate` (ikkalasi), `calculatePerLessonNextPaymentDate`, `syncPerLessonCounters`, `resolvePaymentStatus`, `findLastPaymentForEnrollment`, `clearOrInferWithoutGroup`, `ensureGroupFeeAndStartDate` (override yozishi), `fillPaymentPeriod`, `fixMissingPaymentPeriodsInternal`, `ensurePaymentPeriodsForGroup`, `fixPaymentPeriods`, `recalculateAllActiveStudents`, `getDebtorsByDate` (eski formula), `addMonthsKeepingDay`, `resolveFreezeLessonPrice`, `updateOverdueStatusesDaily` | `AccrualService`, `BillingStatusService`, `BillingSnapshotService` bilan almashtiriladi. `getExpectedPayments` qayta yoziladi. `estimateDateAfterRemainingLessons` `GroupScheduleService` ga ko'chadi. |
| `service/BalanceExpectationService.java`, `MonthlyLedgerRepairWorker.java`, `MonthlyLedgerRepairService.java` | "kutilgan balans" endi ta'rifan ledgerga teng |
| `BalanceTransactionService.recordStudentOnly`, `record()` | `LedgerService.post` |
| `StudentService`: `computeFreezeBreakdown` (PER_LESSON qismi), FREEZE/UNFREEZE yozuvlari, unfreeze'dagi yangi SG yaratish | §6.7 |
| `StudentPaymentLifecycleService.checkOverduePayments` (83-87), `lessonsAttended++` | o'lik / idempotent emas |
| `entity/StudentPaymentPlan.java` + repository; `PaymentScheduleConfig.SETTING_EXPECTED_PAYMENTS_UNTIL` | ishlatilmaydi |
| `StudentGroupRepository`: `findDebtors`, `findPaymentsDueSoon`, `findSuspended*` (SUSPENDED hech qachon qo'yilmaydi); `countActivePaymentStatsGroupedByTeacher` debtor shartı qayta yoziladi | yagona ta'rif |
| `PaymentRepository`: `sumPaidByStudentAndGroup`, `sumCreditsByStudentGroupId`, `sumCreditsByStudentAndGroup`, `findFirst…PeriodEnd…`, `findWithMissingPeriods`, `findUnlinkedByStudentAndGroup` (migratsiyadan keyin) | — |
| `CashRegisterService.addIncome` dagi `student.setBalance` (395-400) | I2 |
| `StudentGroup`: `nextPaymentDue`, `lessonsPurchased`, `lessonsUsed`, `suspendedAt`, `suspensionReason` — yozish to'xtaydi; ustunlar v2.1 da o'chiriladi | hosila / o'lik |
| `controller/PaymentController.class` (gitdagi bytecode, audit §9.10 #39) | ortiqcha fayl |

**Holat (7-bosqich, 01.10.2026):**

O'chirildi:
- `PeriodChargeFormula`, `BalanceExpectationService`, `MonthlyLedgerRepairWorker`, `MonthlyLedgerRepairService`;
- `StudentPaymentPlan` va uning repository'si;
- `PaymentScheduleConfig` — butun klass, hech qayerda ishlatilmasdi;
- `BalanceTransactionService.record/recordStudentOnly/verifyBalances/syncStudentBalanceFromGroups`;
- `PaymentService.getAllPayments(LocalDate, LocalDate)`;
- `PaymentScheduleService` dagi barcha eski metodlar. Qolgani bitta `recalculateForStudent`: qo'shilgan yozilmaga accrual va snapshot;
- `PaymentRepository` dan quyidagi metodlar:
  - `sumPaidByStudentAndGroup`, `sumCredits*`, `sumCash*`;
  - `findFirstByStudentGroup_Id…`, `findFirstByStudent_IdOrderByPaymentDateDesc`;
  - `findWithMissingPeriods`, `findUnlinkedByStudentAndGroup`;
- `StudentService` dagi eski freeze/unfreeze/transfer kodi;
- `StudentPaymentLifecycleService.checkOverduePayments` va `lessonsAttended++` — endi hisoblagich davomatdan qayta sanaladi;
- gitdagi 4 ta `.class` fayl:
  - `controller/PaymentController.class`;
  - `dto/request/StudentRequest.class`;
  - `service/GroupService.class`;
  - `service/PaymentService.class`.

Avvalroq o'chirilgan (3–4-bosqich):
- `resolvePaymentPeriod`, `applyPerLessonPaymentPeriod`, `getDebtorsLegacy`;
- `findDebtors`, `findPaymentsDueSoon`, `findSuspended*`;
- `addIncome` dagi `setBalance`.

Qoldirildi:
- `calculateStudentDebt` — deprecated endpoint, snapshot'dan javob beradi;
- `findFirstByStudent_IdAndPeriodEndIsNotNull…` — `ExamService` ishlatadi;
- `StudentGroup.nextPaymentDue/lessonsPurchased/lessonsUsed/suspended*` ustunlari — yozish to'xtatildi, faqat `@PrePersist` default'lari qoldi; ustunlar v2.1 da o'chiriladi.

API farqlari: `docs/design/billing-v2-api.md`.

---

## 11. Eski admin (`adizone-crm-front`) ga ta'siri

Yangi admin (`adizone-admin`) da to'lov moduli hali yo'q (`/payments/*` — `ComingSoonPage`). Shuning uchun cutover'dan keyin ham to'lov qabul qilish **eski frontda** qoladi. v2 javoblarida eski maydon nomlari saqlanadi (§5.4, §10.2).

| Ekran / fayl | Hozir nima qiladi | v2 dan keyin | Eski frontda kerakli o'zgarish |
|---|---|---|---|
| `components/modal/collect-fees-modal.vue` | preview `{studentId, groupId, amount, discountAmount, useBalance, applyBonuses}`; `gross/discount/payable/balanceUsed/cashAmount` ni o'qiydi (:219-245); summani guruh narxidan to'ldiradi (:308, :333) | Ishlaydi. `balanceUsed` doim 0; "balansdan" belgisi ta'sirsiz; `cashRegisterId` majburiy (modal yuboradi) | "Balansdan foydalanish"ni yashirish; summani `nextPaymentAmount` / `debt` dan to'ldirish (tavsiya); 2+ guruhda `groupId` tanlovini majburiy qilish |
| `views/pages/students/trial-students.vue` "Qabul qilish" (:276-283) | `POST /api/payments` `cashRegisterId` siz | **400** `cashRegisterId` majburiy. Sinov avtomatik to'lovliga o'tadi (§13 #8) | Kassa tanlovini qo'shish |
| `trial-students.vue` ko'chirish (:306-314), `students-promotion.vue` (:226-235) | remove-student + addStudent (atomar emas) | Eski SG balansi ko'chmaydi. Yangi SG `paymentStartDate = bugun` bilan darhol charge oladi | `transfer-group` endpointiga o'tkazish (tavsiya) |
| `add-student.vue:531`, `edit-student.vue:639` | edit'da boshqa guruh tanlansa eski SG yopilmaydi → **ikkinchi faol SG** | ⚠ **Ikkala SG ham accrual oladi — ikki baravar qarz.** | Edit'da guruh almashtirishni `transfer-group` ga o'tkazish yoki bloklash; migratsiyada A4 |
| `fees-collection/collect-fees.vue` (:154-209) | `/debtors`, `totalDebt ?? debt ?? monthlyAmount`; summary'ni klientda hisoblaydi | `totalDebt` endi haqiqiy qarz. Ro'yxatda faqat OVERDUE (grace ichidagilar yo'q). Summary mos | — (ishlaydi) |
| `students/suspended-students.vue` (:151-173) | `/debtors` + klient filtri; jami = Σ `monthlyAmount` | Jami noto'g'ri bo'lib qoladi (oylik ≠ qarz) | `totalDebt` ga o'tkazish |
| `finance/expected-payments.vue` (:252-350) | `/expected`, `amount` fallback'lari | `amount = nextPaymentAmount`; bugungi qatorlar ham keladi, klient faqat kelajakni ko'rsatadi | ixtiyoriy: bugunni ham ko'rsatish |
| `fees-collection/fees-assign.vue` (:134-303) | `periodFrom → periodTo`, `balanceUsed`, Σ `cashAmount` klientda | Yangi to'lovlarda davr bo'sh; **CANCELLED to'lovlar ro'yxatda va klient yig'indisida** | `status` ustuni/filtri, CANCELLED ni yig'indidan chiqarish |
| `report/fees-report.vue`, `admin-dashboard.vue` | `receiptNumber`, stats | Ishlaydi; qarzdorlar soni yangi ta'rifdan | — |
| `peoples/students/student-details.vue` | SG kesimida `paymentStatus`/`nextPaymentDate`; chek (`periodFrom/To`, `balanceUsed`) | Ishlaydi; chekda davr bo'sh. **Bekor qilish tugmasi yo'q** (faqat API / yangi admin). balance-adjust `reason` yuboradi, backend `note` kutadi — eski xato (doim 400) | ixtiyoriy |
| `modal/freeze-student-modal.vue` (:136-168) | preview'da mavjud bo'lmagan maydonlarni o'qiydi (0 ko'rsatadi) — eski xato | Muzlatish tanlangan guruh bilan ishlaydi; qaytarim summasi modalda ko'rinmaydi | `refundLines` / `groups[]` ni ko'rsatish |
| `modal/unfreeze-student-modal.vue` (:118-121) | `startDate` yuboradi (e'tiborsiz) → bugun | O'sha SG qayta faollashadi, langar = bugun, darhol charge | `paymentStartDate` nomiga tuzatish |
| `academic/classes/group-detail.vue` | SG maydonlari; qo'shish formasida chegirma yo'q; payment-date modal `isTrial` bilan PATCH | `paymentStartDate ≤ bugun` bo'lsa darhol charge (operatorlar bilishi kerak) | — |
| `peoples/students/student-list.vue` | status, `nextPaymentDate`, `monthlyAmount = student.monthlyFee` | `monthlyFee` endi chegirmadan keyingi yig'indi | — |
| `components/leads/lead-convert-modal.vue` | `paymentStartDate`, `isTrial`, `paymentType`, narx | Ishlaydi; `paymentStartDate ≤ bugun` → darhol charge | — |

**Xulosa:**
- Asosiy oqimlar (to'lov, qarzdorlar, kutilayotganlar) mos qoladi.
- Majburiy tuzatishlar:
  1. sinovni "qabul qilish"da kassa;
  2. `edit-student` dagi ikkinchi SG;
  3. `fees-assign` da CANCELLED filtri.
- Tavsiya: yangi admin to'lov moduli tayyor bo'lgach, eski frontda billing yozish amallarini o'chirish.

### 11.1 Yangilanish — amalga oshirishdan keyin (01.10.2026)
§13 qarorlari va qurilgan kod bo'yicha eski frontga **qo'shimcha** ta'sirlar:

| Ekran / oqim | O'zgarish | Eski frontda kerak |
|---|---|---|
| `collect-fees-modal.vue` — chegirma | `discountAmount > 0`: ACCOUNTANT → **403** `payment.discount.forbidden` (§13 #10); SA/A uchun sabab majburiy (`discountReason` yoki `notes`) → aks holda 400 | ACC uchun chegirma maydonini yashirish; sabab maydonini qo'shish (yoki `notes` ni majburiy qilish) |
| `collect-fees-modal.vue` — takroriy bosish | `Idempotency-Key` sarlavhasi qo'llab-quvvatlanadi (dialog ochilganda UUID); kalitsiz ham ishlaydi, lekin himoyasiz | Sarlavha yuborish (tavsiya) |
| `freeze-student-modal.vue` | 2+ faol guruhda `groupId` siz → **400** `payment.group.required` (avval hammasi muzlatilardi). Javobda `refundLines`, `refundTotal`, `freezeDate` | Guruh tanlovi; qaytarimni ko'rsatish; ixtiyoriy `freezeDate` |
| `unfreeze-student-modal.vue` | O'sha SG qayta ochiladi; `paymentStartDate` qayta-qaytarilgan davr bilan ustma-ust tushsa → 409 `billing.anchor.overlap` | `startDate` → `paymentStartDate` (avvalgi xato) |
| `group-detail.vue` payment-date modal (`PATCH /payment-start-date`) | 2+ faol guruhda `groupId` majburiy (400); mavjud davrlar bilan ustma-ust → 409; ACC bir oydan ko'p orqaga → 403 | `groupId` yuborish |
| `student-details.vue` balance-adjust | Summa butun bo'lishi kerak; `effectiveDate` ixtiyoriy | — |
| To'lovni bekor qilish | Faqat `POST /api/payments/{id}/cancel` (SA, sabab, ≤ 31 kun); eski frontda tugma yo'q | Yangi admin'da qilinadi |
| Pul qaytarish | Yangi `POST /api/students/{id}/refund-payout` (SA, A) — kassa chiqimi bilan | Yangi admin'da qilinadi |
| Guruhga qo'shish / lid konvertatsiyasi / import | `paymentStartDate ≤ bugun` bo'lsa davr darhol yoziladi (o'quvchi darhol PENDING); chegirma 0..100 tekshiriladi; `monthlyFee == kurs narxi` bo'lsa override saqlanmaydi | Operatorlarga ogohlantirish |
| `students-promotion.vue` (bulk) | Endi `transfer-group` mantiqi: balans va narx shartlari ko'chadi, yangi SG uzluksiz langar bilan | — |
| Bonus/jarima sahifasi | APPLIED yozuvni bekor qilish faqat SA va sabab bilan; yangi `/apply` tugmasi uchun endpoint bor | Ixtiyoriy |

**Yangi admin (`adizone-admin`) uchun qayd:**
- `AddExistingStudentForm` `discountPercentage` yuboradi — endi pulga ta'sir qiladi (§3.5).
- `TrialStudentsPage` "to'lovli qilish" darhol charge yozadi.
- Tiplarga `debt`, `nextPaymentAmount`, `effectiveFee`, `debtSince`, `frozenFrom` qo'shiladi.
- `FreezeDialog` ga `refundLines`.

---

## 12. Test rejasi

### 12.1 Infratuzilma
- **Unit (Spring'siz, JUnit 5).** Sof funksiyalar: `Money`, `BillingCalendar` (§3.1), `AccrualCalculator`, `FifoDebt`, `BillingStatusService.statusOf`, `PaymentPlanner`, `FreezePlanner`, `TransferPlanner`.
- **Vaqt.** Billing servislariga `Clock` (Asia/Tashkent) inject qilinadi. Testda `Clock.fixed(...)`.
- **Integratsiya.** `@SpringBootTest` + **Testcontainers PostgreSQL** (yangi test dependency — `FOR UPDATE`, UNIQUE, sequence uchun H2 yetarli emas). Mavjud H2 faqat kontekst testi uchun qoladi.
- **Arxitektura testi.**
  - `RoundingMode` billing paketida faqat `Money` da;
  - `setBalance(` faqat `LedgerService` da;
  - `setPaymentStatus(` faqat `BillingSnapshotService` da;
  - `double` billing paketida yo'q.

### 12.2 Testlar (har qaror uchun kamida 2 ta)
| ID | Qaror | Test | Berilgan → kutilgan |
|---|---|---|---|
| T1.1 | Q1 accrual | `accrual_writesChargeOnBillingDay` | start 15.09, fee 700 000, d 0; job 15.09 → bitta PERIOD_CHARGE −700 000, `effective = 15.09`, B = −700 000 |
| T1.2 | Q1 | `accrual_isIdempotent` | job 15.09 da 2 marta + 2 parallel thread → `billing_periods` 1 qator, ledger 1 yozuv |
| T1.3 | Q1 | `accrual_catchUpAfterDowntime` | job 15–17.10 ishlamagan; startup 17.10 → 1 charge `effective 15.10`; 18.10 PENDING, 19.10 OVERDUE |
| T1.4 | Q1 | `accrual_backdatedStart` | 01.10 da start 15.06 → 4 charge (15.06…15.09), Σ −2 800 000; max-catch-up = 2 → 2 charge + hisobot |
| T1.5 | Q1 | `calendar_endOfMonthRule` | 31.01.2027 → 28.02, 31.03, 30.04; 29.01.2027 → 28.02, 31.03; 30.01.2028 → 29.02.2028 |
| T1.6 | Q1 | `paymentIsCreditOnly` | MONTHLY to'lov 630 000 → faqat PAYMENT +630 000 (PERIOD_CHARGE yo'q) |
| T1.7 | Q1 | `balanceEqualsLedgerSum` (property) | 200 tasodifiy amal (to'lov, accrual, bekor, muzlatish, transfer) → har qadamda I1 |
| T2.1 | Q2 holat | `status_graceBoundary` | charge 15.10; 18.10 → PENDING; 19.10 → OVERDUE; grace 5 → 20.10 PENDING, 21.10 OVERDUE |
| T2.2 | Q2 | `status_partialPaymentKeepsDebtSince` | 15.10 −630 000; 20.10 +300 000 → OVERDUE, `debtSince 15.10`, debt 330 000, next 15.10 / 330 000 |
| T2.3 | Q2 | `fifo_oldestUnpaid` | charge 15.09 va 15.10 (630 000 dan), to'lov 700 000 → B −560 000, `debtSince 15.10` |
| T2.4 | Q2 | `debtors_singleSourceOfTruth` | 6 o'quvchi (2 OVERDUE, 1 PENDING, 1 PAID, 1 FROZEN + qarz, 1 TRIAL) → dashboard = analytics = `/debtors/summary` = Σ KPI debtor = `statusOf` bo'yicha hisob = **3** (2 OVERDUE + qarzi muddati o'tgan FROZEN) |
| T2.5 | Q2 | `nextPaymentDate_prepaid` | B +1 260 000, c 630 000 → next 15.12 / 630 000; B +300 000 → 15.10 / 330 000 |
| T2.6 | Q2 | `reversal_restoresOriginalDebtSince` | 15.09 charge, 16.09 to'lov, 17.09 bekor → `debtSince 15.09` (17.09 emas) |
| T3.1 | Q3 chegirma | `discount_monthly` | 700 000 × 10% → 630 000; 333 333 × 7% → 310 000; 555 555 × 12,5% → 486 111 |
| T3.2 | Q3 | `discount_perLesson` | 80 000 × 10% → LESSON_CHARGE −72 000 |
| T3.3 | Q3 | `discount_changeAppliesNextPeriod` | 20.09 da d 10 → 20: 15.09 = 630 000, 15.10 = 560 000 |
| T3.4 | Q3 | `money_wholeSumsOnly` | to'lov 630 000,50 → 400 `money.wholeSumRequired`; d 100 → davr qatori bor, ledger yo'q |
| T4.1 | Q4 bonus | `bonus_creditOnPayment` | B −630 000, bonus 100 000, jarima 30 000, to'lov 560 000 → +560 000 / +100 000 / −30 000, B 0; bonus APPLIED, `ledgerTxId` bor |
| T4.2 | Q4 | `preview_equalsCreate_withBonuses` | xuddi shu holat: `preview.lines == create.lines` (id siz), `planHash` teng |
| T4.3 | Q4 | `bonus_notAppliedWhenFlagFalse` | `applyBonuses = false` → bonus PENDING, B −70 000 |
| T4.4 | Q4 | `bonus_concurrentPaymentsApplyOnce` | 2 parallel to'lov → bonus aynan 1 marta (Testcontainers) |
| T4.5 | Q4 | `bonus_cancelApplied` | APPLIED bonus SA bekor qiladi → REVERSAL −100 000, status CANCELLED |
| T5.1 | Q5 bekor | `cancel_reversesLedgerAndCash` | RCP-00042 naqd 630 000 → REVERSAL −630 000; kassa naqd −630 000; `status CANCELLED`; audit yozuvi bor |
| T5.2 | Q5 | `cancel_onlySuperAdmin_reasonRequired_once` | ADMIN → 403; bo'sh sabab → 400; ikkinchi bekor → 409 |
| T5.3 | Q5 | `cancel_returnsBonusToPending` | §6.4 jadvali: 17.09 da B −630 000, bonus #77 PENDING |
| T5.4 | Q5 | `cancel_splitPayment` | CASH_AND_CARD 400 000 + 230 000 → REVERSAL naqd −400 000, plastik −230 000 |
| T5.5 | Q5 | `finance_excludesCancelled` | 2 to'lov (630 000 PAID, 580 000 CANCELLED) → `totalIncome` 630 000 |
| T6.1 | Q6 sinov | `trial_monthly_noAccrual` | `isTrial` 01–05.10 → 0 charge; 06.10 konvertatsiya → charge 06.10 −700 000 |
| T6.2 | Q6 | `trial_perLesson_noCharge` | 01.10, 03.10 PRESENT (trial) → 0; 06.10 dan → −80 000 har dars; 03.10 ni keyin tahrirlash → charge yo'q |
| T6.3 | Q6 | `trial_paymentAutoConverts` | sinovdagi o'quvchi 06.10 da 700 000 to'laydi → `convertTrial` + charge −700 000 + PAYMENT +700 000, B 0, PAID |
| T7.1 | Q7 muzlatish | `freeze_proportionalRefund30` | davr 15.09–14.10, 630 000, freeze 28.09 → PERIOD_REFUND +357 000 |
| T7.2 | Q7 | `freeze_proportionalRefund31` | 15.10–14.11, freeze 01.11 → +284 516; freeze 15.10 → +630 000 (REFUNDED) |
| T7.3 | Q7 | `freeze_onlySelectedEnrollment` | Vali A ni muzlatadi → `Student.status ACTIVE`; B ham → FROZEN |
| T7.4 | Q7 | `unfreeze_reanchors` | B +357 000; 15.10 da job → charge yo'q; unfreeze 20.10 → charge 20.10 −630 000, B −273 000, next 20.10 / 273 000; SG id o'zgarmagan |
| T7.5 | Q7 | `freeze_unpaidKeepsDebt` | B −630 000, freeze 28.09 → −273 000, holat OVERDUE (`debtSince 15.09`) |
| T7.6 | Q7 | `freezePreview_equalsFreeze` | preview `refundLines` == freeze yozuvlari |
| T8.1 | Q8 transfer | `transfer_movesPositiveBalanceAndPricing` | A: override 600 000, d 10, B +540 000 → TRANSFER −/+540 000; yangi SG override 600 000, d 10, MONTHLY, start 15.11 |
| T8.2 | Q8 | `transfer_movesDebtWithDebtSince` | A −540 000 (`debtSince 15.10`), 25.10 transfer → B −540 000, OVERDUE 10 kun |
| T8.3 | Q8 | `transfer_perLesson` | PER_LESSON, `lessonPrice` 80 000, B +240 000 → yangi SG PER_LESSON 80 000, +240 000 |
| T8.4 | Q8 | `transfer_noDoubleChargeInCurrentPeriod` | A 15.10 davri hisoblangan; 25.10 transfer → yangi SG da 15.11 gacha charge yo'q |
| C1 | §7 | `cash_concurrentIncome` | kassa 1 000 000 + 2 × 500 000 parallel → 2 000 000 |
| C2 | §7 | `receipt_unique_parallel` | 20 parallel to'lov → 20 xil chek, xato yo'q |
| C3 | §7 | `idempotencyKey_replay` | bir xil kalit 2 marta → 1 to'lov, ikkinchi javob `X-Idempotent-Replay`; boshqa body → 409 |
| C4 | §7 | `attendance_doubleSubmit` | PER_LESSON 08.10 PRESENT ikki marta → 1 LESSON_CHARGE |
| C5 | §7 | `payment_vs_accrual_sameSG` | 00:10 job va to'lov bir vaqtda → I1 buzilmaydi, charge 1 ta |
| M1 | §9 | `migration_examples` | §9.3 jadvalidagi 7 SG → target: 0; −700 000; +700 000; −400 000; −1 400 000; −700 000; −70 000 (A14) |
| M2 | §9 | `migration_dryRunWritesNothing` | dry-run'dan keyin `balance_transactions`, `billing_periods` soni o'zgarmagan |
| M3 | §9 | `migration_applyRequiresFreshRun` | dry-run'dan keyin yangi to'lov → apply 409 |
| M4 | §9 | `migration_revertSg` | revert → SG balansi L ga qaytadi, I1 saqlanadi |

---

## 13. Ochiq savollar

Har savol uchun **taklif** (default) berilgan edi. **01.10.2026 da qarorlar olindi** — default takliflar qabul qilingan, 10, 20, 23, 24, 27 bundan mustasno (har savol ostida "Qaror:" qatori).

1. **"29–31 → oy oxiri" talqini.** Taklif: billing kuni 29, 30 yoki 31 bo'lsa, har oy oxirgi kuni olinadi (29.01 → 28.02 → **31.03** → 30.04). Muqobil: kunni oy uzunligi bilan cheklash (29.01 → 28.02 → **29.03**). Qaysi biri?
   **Qaror:** Taklif qabul qilindi — 29–31 → oy oxiri (29.01 → 28.02 → 31.03 → 30.04).
2. **Muzlatish sanasi o'tmishda bo'lishi mumkinmi?** (masalan, o'quvchi 01.10 dan kelmagan, admin 10.10 da muzlatadi). Taklif: `freezeDate ≤ bugun`, lekin oxirgi billable davomatdan oldin emas. 30 kundan eski bo'lmasin.
   **Qaror:** Taklif qabul qilindi — `freezeDate ≤ bugun`, oxirgi billable davomatdan oldin emas, 30 kundan eski emas.
3. **Guruhdan chiqishda** (LEFT / GRADUATED / remove-student) joriy davrning ishlatilmagan qismi qaytariladimi? Taklif: yo'q (hozirgidek). Qaror 7 faqat muzlatishni qamraydi.
   **Qaror:** Taklif qabul qilindi — chiqishda qaytarim yo'q.
4. **Chiqib ketgan o'quvchining qarzi** (SG yopilgan, B < 0) qarzdorlar ro'yxatida va dashboard sonida bo'ladimi? Taklif: default ro'yxat va dashboard — faqat faol o'quvchilar. Yopilgan qarzlar `scope=ALL` filtrida va alohida "Yopilgan qarzlar" summasida.
   **Qaror:** Taklif qabul qilindi — default faqat faol o'quvchilar; yopilgan qarzlar `scope=ALL` da.
5. **Muzlatilgan, lekin qarzi bor SG:** holat OVERDUE va qarzdor sifatida sanaladimi? Taklif: ha, qarz holatdan ustun. Telegram eslatma muzlatilganlarga yuborilmaydi.
   **Qaror:** Taklif qabul qilindi — qarz holatdan ustun (OVERDUE, qarzdor sanaladi); Telegram muzlatilganlarga yuborilmaydi.
6. **Guruhlararo kesishuv.** A da +500 000, B da −500 000 bo'lsa, avtomatik o'zaro yopiladimi? Taklif: yo'q. SA uchun qo'lda `balance-transfer` endpointi qo'shiladi. Muqobil: to'lovni FIFO bo'yicha guruhlarga avtomatik taqsimlash.
   **Qaror:** Taklif qabul qilindi — avtomatik kesishuv yo'q; SA uchun `balance-transfer`.
7. **Bir nechta guruhli o'quvchida to'lov `groupId` siz:** taklif — 400 (guruh majburiy). Muqobil: eng katta / eng eski qarzli SG ga avtomatik.
   **Qaror:** Taklif qabul qilindi — >1 SG da `groupId` majburiy (400).
8. **Sinovdagi o'quvchi to'g'ridan-to'g'ri to'lasa** (eski front "Qabul qilish"): taklif — avtomatik to'lovliga o'tkazish (`paymentStartDate = to'lov sanasi`, darhol charge). Muqobil: 400 "Avval to'lovli qiling".
   **Qaror:** Taklif qabul qilindi — avtomatik to'lovliga o'tkaziladi (`paymentStartDate = to'lov sanasi`).
9. **Kassasiz to'lov.** Hozir `cashRegisterId` siz to'lov faqat `Income` yozadi. Taklif: kassa majburiy (I5). Muqobil: sozlamada "default kassa".
   **Qaror:** Taklif qabul qilindi — kassa majburiy.
10. **To'lovdagi bir martalik `discountAmount`** saqlanadimi? Taklif: ha — `DISCOUNT` kredit yozuvi, rollar SA, A, ACC, cheksiz. Muqobil: olib tashlash (faqat `discountPercentage` va `MANUAL_ADJUST`), yoki faqat SA/A, yoki maksimal foiz.
   **Qaror:** **Taklifdan farqli:** `discountAmount` saqlanadi (`DISCOUNT` kredit), lekin faqat **SA, A**; `note` (sabab) **majburiy**. ACC chegirma bilan to'lov kirita olmaydi (403).
11. **Bonus qachon qo'llanadi?** Taklif: to'lovda (`applyBonuses`) + qo'lda `/apply`. Muqobil: `effectiveDate` kelganda avtomatik, to'lovsiz. Bir nechta guruhda — `groupId` majburiy.
   **Qaror:** Taklif qabul qilindi — to'lovda (`applyBonuses`) + qo'lda `/apply`; >1 SG da `groupId` majburiy.
12. **Jarima** ham to'lovda qo'llanadimi yoki yaratilganda darhol debet bo'ladimi? Taklif: bonus bilan bir xil (to'lovda yoki `/apply`).
   **Qaror:** Taklif qabul qilindi — jarima bonus bilan bir xil (to'lovda yoki `/apply`).
13. **Orqaga sanalangan `paymentStartDate`** bir nechta o'tgan davrni darhol hisoblaydi (15.06 → 4 davr). Ruxsat etiladimi? Taklif: SA/A uchun ha, preview'da "N ta davr, jami X" ogohlantirishi bilan; chegara 24 davr. ACC uchun 1 davrdan ortiq orqaga — yo'q.
   **Qaror:** Taklif qabul qilindi — SA/A uchun ha (preview ogohlantirishi, chegara 24 davr); ACC uchun 1 davrdan ortiq orqaga yo'q.
14. **Guruh holati.** FORMING guruhda `paymentStartDate ≤ bugun` bo'lsa accrual bo'ladimi? COMPLETED / CANCELLED da to'xtaydimi? Taklif: ACTIVE va FORMING — ha; COMPLETED / CANCELLED — yangi davr yo'q.
   **Qaror:** Taklif qabul qilindi — ACTIVE, FORMING: ha; COMPLETED, CANCELLED: yangi davr yo'q.
15. **Override tozalash (§9.5).** `monthlyPriceOverride == kurs narxi` bo'lgan SG larni "individual emas" deb `NULL` qilamizmi? Taklif: ha, A15 ro'yxati tasdiqlangandan keyin. Aks holda kurs narxi o'zgarishi ularga hech qachon ta'sir qilmaydi.
   **Qaror:** Taklif qabul qilindi — A15 ro'yxati tasdiqlangandan keyin `override = NULL`.
16. **Kurs narxi o'zgarishi** keyingi davrdan qo'llanadi (yozilgan davrlar o'zgarmaydi) — tasdiqlang. O'quvchilarga xabar kerakmi?
   **Qaror:** Taklif qabul qilindi — keyingi davrdan; xabar v2 qamrovida emas.
17. **Transferda yangi kurs narxi.** Override bo'lmasa, yangi guruh kursining narxi keyingi davrdan qo'llanadi va joriy davr qayta hisoblanmaydi. Taklif: shunday. Muqobil: joriy davrni proporsional qayta hisoblash (refund + yangi langar).
   **Qaror:** Taklif qabul qilindi — yangi kurs narxi keyingi davrdan, joriy davr qayta hisoblanmaydi.
18. **Migratsiyada chegirma.** Cutover'dan oldingi davrlarga `discountPercentage` qo'llanadimi? Taklif: yo'q, birinchi yangi davrdan. Eski to'lovda `discountAmount` bo'lgan SG lar (A14) uchun o'tgan davr narxi = o'sha to'lovning `payable` qiymati?
   **Qaror:** Taklif qabul qilindi — chegirma birinchi yangi davrdan; A14 SG lar uchun o'tgan davr narxi = eski to'lov `payable` (dry-run hisobotida ko'rsatiladi, apply'da tasdiqlangandan keyin).
19. **Import qilingan o'quvchilar uchun "shu sanagacha to'langan"** (R) — eski `nextPaymentDate` ga ishonamizmi? Taklif: CRM'da to'lovi bo'lmasa ha. Boshqalari §9.2 formulasi bo'yicha, A12 qo'lda ko'riladi.
   **Qaror:** Taklif qabul qilindi — CRM'da to'lovi bo'lmasa eski `nextPaymentDate`; qolganlari §9.2 formulasi, A12 qo'lda.
20. **Cutover sanasi, texnik oyna va dry-run hisobotini kim tasdiqlaydi?** Rollback oynasi 72 soat — yetarlimi?
   **Qaror:** **Dry-run hisobotini buyurtmachi (egasi) tasdiqlaydi**; apply faqat tasdiqlangan run bo'yicha. Rollback oynasi **72 soat** (§9.7 bilan bir xil).
21. **Grace sozlamasi** `application.yml` da (o'zgartirish uchun qayta ishga tushirish kerak) yetarlimi yoki admin UI dan o'zgartiriladigan sozlama kerakmi? Taklif: yml, default 3.
   **Qaror:** Taklif qabul qilindi — `application.yml`, default 3.
22. **Bekor qilishda kassada pul yetmasa** (naqd 100 000, bekor 630 000): manfiyga ruxsat (xarajatdagi kabi) yoki 400? Taklif: ruxsat, javobda ogohlantirish.
   **Qaror:** Taklif qabul qilindi — kassa manfiyga tushishi mumkin, javobda ogohlantirish.
23. **Eski to'lovni bekor qilish** (masalan o'tgan oy, payroll allaqachon PAID): ruxsatmi? O'qituvchi oyligidagi "to'lagan o'quvchi" soni qayta hisoblanmaydi. Taklif: ruxsat, payroll'ga ta'sir hisobotda ogohlantirish. Muqobil: N kundan eski to'lovni bekor qilish taqiqlanadi.
   **Qaror:** **Taklifdan farqli:** bekor qilish faqat `paymentDate ≥ bugun − 31 kun` bo'lsa. Eskisi — **400** `payment.cancel.tooOld`; SA tuzatishni `MANUAL_ADJUST` orqali qiladi.
24. **O'quvchiga pul qaytarish** (chiqib ketganda musbat balansni naqd berish) — qarorlarda yo'q. Taklif: v2.1 da `REFUND_PAYOUT` turi + kassa chiqimi. Hozircha `MANUAL_ADJUST` + kassada qo'lda chiqim.
   **Qaror:** **Taklifdan farqli — v2 da qilinadi:** `REFUND_PAYOUT` ledger turi + `POST /api/students/{id}/refund-payout` (SA, A). Sabab majburiy, kassa chiqimi bilan, faqat musbat balans doirasida (SG balansidan ko'p qaytarib bo'lmaydi).
25. **Avto-arxiv** (30 kun davomatsiz → FROZEN, `StudentPaymentLifecycleService.java:89-127`) accrual bilan qanday ishlaydi? Bu vaqt ichida yana bir davr hisoblanadi. Taklif: avto-arxiv §6.7 dagi muzlatishni chaqiradi (`freezeDate = bugun`, qaytarim bilan, tarixga yoziladi). Muqobil: o'chirish va faqat "nofaol o'quvchilar" ro'yxatini ko'rsatish.
   **Qaror:** Taklif qabul qilindi — avto-arxiv §6.7 dagi muzlatishni chaqiradi (`freezeDate = bugun`, qaytarim va tarix bilan).
26. **Telegram eslatma.** v2 da OVERDUE bo'lganlarga, summa = qarz. Har kuni yuboriladimi (hozirgidek) yoki OVERDUE bo'lgan kun + har 3 kunda? Taklif: OVERDUE kuni va keyin har 3 kunda.
   **Qaror:** Taklif qabul qilindi — OVERDUE bo'lgan kun va keyin har 3 kunda.
27. **Payroll** (o'qituvchi uchun "shu oyda to'lagan o'quvchilar", `PaymentRepository.java:285-303`): accrual modelida "shu oy davri to'langan SG" ga o'tkaziladimi? Billing-v2 qamrovida emas. Taklif: alohida vazifa; hozircha CANCELLED to'lovlar sanalmasligi uchun filtr qo'shiladi.
   **Qaror:** **Payroll v2 qamrovida emas.** Faqat filtr: `CANCELLED` to'lovlar payroll hisobida sanalmaydi.
28. **PER_LESSON holati.** Grace (3 kun) dars sanasidan hisoblanadi, `nextPaymentDate` jadval bo'yicha. Jadvali yo'q guruhda `next = null` — qabulmi?
   **Qaror:** Taklif qabul qilindi — grace dars sanasidan; jadvalsiz guruhda `next = null`.


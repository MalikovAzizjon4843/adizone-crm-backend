# Payroll v2 — API shartnomasi (frontend uchun)

> Dizayn: [`payroll-v2.md`](payroll-v2.md). Bu hujjat — **amalda qurilgan** holat (branch `billing-v2`).
> Javoblar avvalgidek `ApiResponse {success, message, data}` ichida; xatolar `ErrorResponse {status, error, message, code}`.
> `message` `Accept-Language` (uz/ru/en) bo'yicha keladi; frontend mantiqi uchun **`code`** ga tayaning.

## 0. Umumiy

| Mavzu | Qoida |
|---|---|
| Rollar | SA = SUPER_ADMIN, A = ADMIN, ACC = ACCOUNTANT. Jadvaldagi roldan boshqasi → **403**. "SALES" = `SALES_MANAGER` va `SALES_HEAD` (oylik qoidasi, hisob va STAFF bonusi bo'yicha bir xil). |
| Holatlar | `status` ∈ `DRAFT`, `APPROVED`, `PAID`, `CANCELLED` (eski `PENDING` yo'q — bazada DRAFT ga o'tkazilgan). |
| Holat qanday o'zgaradi | Faqat amal endpointlari orqali (`approve`, `pay`, `cancel`, `DELETE`). **`PUT /api/payroll/{id}` va `POST /api/payroll` olib tashlandi → 405.** |
| Summalar | UZS. `netSalary = grossSalary + bonusPenaltyAdjustment`. |
| Qulf band | Parallel amal 5 s dan ko'p kutsa → 409 `concurrency.busy` (qayta urinish mumkin). |
| Cutover | Billing v2 cutover oyidan **oldingi** oylar uchun `calculate`, `generate`, `recalculate` → 400 `payroll.beforeCutover`. Cutover oyida `calculationDetails.estimated = true` — "taxminiy" belgisini ko'rsating. |
| Xato tafsiloti | Ba'zi 409 larda `ErrorResponse.data` obyekti bor (masalan `payroll.netChanged` → `data.netSalary`). |

```
            generate / recalculate
                 │
   (yo'q) ──► DRAFT ──approve──► APPROVED ──pay──► PAID
                 │                  │                │
              DELETE             cancel (SA)      cancel (SA)
                 ▼                  ▼                ▼
             (o'chadi)          CANCELLED         CANCELLED
```

Frontend uchun tugmalar:

| status | Ko'rsatiladigan amallar |
|---|---|
| `DRAFT` | Qayta hisoblash (SA/A/ACC), Tasdiqlash (SA/A), O'chirish (SA/A) |
| `APPROVED` | To'lash (SA/A/ACC), Bekor qilish (SA) |
| `PAID` | Bekor qilish (SA) |
| `CANCELLED` | — (faqat ko'rish; shu oy uchun `generate` yangi DRAFT yaratadi) |

## 1. Endpointlar — `/api/payroll`

| Metod, yo'l | Rollar | Tavsif |
|---|---|---|
| `GET /api/payroll` | SA, A, ACC | Ro'yxat (sahifali) |
| `GET /api/payroll/{id}` | SA, A, ACC | Bitta oylik |
| `GET /api/payroll/teacher/{teacherId}` | SA, A, ACC | O'qituvchining oyliklari (yil/oy kamayishida) |
| `GET /api/payroll/calculate?month&year` | SA, A, ACC | Preview — hamma xodim (hech narsa yozilmaydi) |
| `GET /api/payroll/calculate/{userId}?month&year` | SA, A, ACC | Preview — bitta xodim |
| `POST /api/payroll/generate` | SA, A, ACC | Yetishmayotganlarga DRAFT |
| `POST /api/payroll/{id}/recalculate` | SA, A, ACC | DRAFT ni qayta hisoblash |
| `POST /api/payroll/{id}/approve` | SA, A | DRAFT → APPROVED |
| `POST /api/payroll/{id}/pay` | SA, A, ACC | APPROVED → PAID |
| `POST /api/payroll/{id}/cancel` | **SA** | APPROVED/PAID → CANCELLED |
| `DELETE /api/payroll/{id}` | SA, A | Faqat DRAFT |

### 1.1 `GET /api/payroll`
Query: `page` (0), `size` (20), `status` (`DRAFT|APPROVED|PAID|CANCELLED`, noma'lum → 400 `payroll.status.invalid`),
`month`, `year`, `userId`. Tartib: yil ↓, oy ↓, id ↓.

`data` = `PageResponse<PayrollResponse>` = `{content, pageNumber, pageSize, totalElements, totalPages, last}`.

### 1.2 `PayrollResponse`

| Maydon | Tur | Izoh |
|---|---|---|
| `id`, `uuid` | number, string | |
| `userId`, `userName`, `role` | | xodim (`TEACHER` / `ADMIN` / `SALES_MANAGER` / `SALES_HEAD`) |
| `teacherId`, `teacherName` | | o'qituvchi bo'lsa — Teacher profili; `teacherName` boshqalarda ham xodim ismi |
| `month`, `year` | number | |
| `status` | string | §0 |
| `basicSalary` | number | qoidaning `fixedSalary` qismi |
| `allowances` | number | `grossSalary − basicSalary` (to'lagan/yangi o'quvchi, KPI) |
| `deductions` | number | doim 0 (jarimalar `bonusPenaltyAdjustment` da) |
| `grossSalary` | number | bonuslarsiz |
| `bonusPenaltyAdjustment` | number | Σ bonus − Σ jarima. **DRAFT** da — kutilayotgan (PENDING), **APPROVED** dan keyin — qo'llangan |
| `netSalary` | number | to'lanadigan summa |
| `paidStudentCount` | number | TEACHER: elementlar soni (to'lagan davrlar + PER_LESSON yozilmalar) |
| `paidStudentUnits` | number | TEACHER: haq olinadigan birliklar — **kasr bo'lishi mumkin** (o'qituvchi almashgan PER_LESSON yozilma ulushi, masalan `1.6667`) |
| `newStudentCount` | number | ADMIN/SALES |
| `kpiApplied`, `kpiAmount` | | ADMIN |
| `calculationDetails` | **object** | §3 (avval JSON *string* edi). v1 yozuvlarida eski shakl |
| `calcVersion` | number \| null | 2 — v2 hisobi; `null` — v1 yozuvi (tasdiqlashdan oldin qayta hisoblash kerak) |
| `paymentDate`, `paymentMethod`, `paymentMethodLabel`, `paymentMethodIcon` | | PAID bo'lganda |
| `cashRegisterId`, `cashRegisterName`, `cashTransactionId` | | kassa orqali to'langan bo'lsa |
| `approvedAt`, `approvedByName` | | |
| `paidAt`, `paidByName` | | |
| `cancelledAt`, `cancelledByName`, `cancelReason` | | |
| `notes`, `createdByName`, `createdAt`, `updatedAt` | | |

### 1.3 `POST /api/payroll/generate`
Parametrlar query **yoki** JSON body da (query ustun):
```json
{ "month": 9, "year": 2026, "recalculate": false }
```
- `recalculate=false` — faqat oyligi yo'q xodimlarga DRAFT; mavjud DRAFT tegilmaydi.
- `recalculate=true` — mavjud DRAFT lar ham qayta hisoblanadi. **APPROVED/PAID hech qachon o'zgarmaydi.**
- `month`/`year` yo'q → 400 `payroll.period.required`. Eski `overwrite` parametri endi yo'q.

Javob (200):
```json
{
  "created": 3,
  "recalculated": 1,
  "totalAmount": 12450000,
  "skipped": [
    { "userId": 15, "fullName": "Aziza Karimova",  "reason": "NOT_CALCULABLE", "code": "RULE_NOT_FOUND",          "message": "Oylik qoidasi topilmadi", "payrollId": null },
    { "userId": 20, "fullName": "Zamira Abdumajidova", "reason": "NOT_CALCULABLE", "code": "TEACHER_PROFILE_MISSING", "message": "O'qituvchi profili yo'q — /api/admin/repair/link-teacher-users profil yaratadi", "payrollId": null },
    { "userId": 21, "fullName": "Bobur Aliyev",    "reason": "DRAFT_EXISTS",   "code": null, "message": null, "payrollId": 88 },
    { "userId": 22, "fullName": "Dilnoza Sobirova","reason": "PAID",           "code": null, "message": null, "payrollId": 71 },
    { "userId": 19, "fullName": "Sarvinoz Ochilova","reason": "ERROR",         "code": "DB_CONSTRAINT", "message": "duplicate key value violates unique constraint ...", "payrollId": null }
  ]
}
```
| `reason` | Ma'nosi | `code` |
|---|---|---|
| `NOT_CALCULABLE` | hisoblab bo'lmaydi | `RULE_NOT_FOUND` — qoida yo'q; `TEACHER_PROFILE_MISSING` — TEACHER userda Teacher profili yo'q (`POST /api/admin/repair/link-teacher-users` yaratadi); `TEACHER_PROFILE_LINKED_TO_OTHER_USER` — shu telefon/email li profil boshqa userga bog'langan (qo'lda tekshiring); `ROLE_NOT_CALCULATED` |
| `DRAFT_EXISTS` / `APPROVED` / `PAID` | mavjud faol oylik (`payrollId`) tegilmadi | — |
| `ERROR` | shu xodimni saqlashda baza xatosi — **faqat shu xodim** o'tkazildi, qolganlari saqlangan, javob 200 | `DB_CONSTRAINT` (cheklov, masalan eski sxema — V56) yoki `UNEXPECTED` |

`totalAmount` — yaratilgan + qayta hisoblangan DRAFT lar `netSalary` yig'indisi. Har xodim alohida tranzaksiyada saqlanadi.

### 1.4 `POST /api/payroll/{id}/recalculate`
Body yo'q. Javob — `PayrollResponse`. Xatolar: 409 `payroll.notDraft`; 400 `payroll.notCalculable` (qoida o'chirilgan va h.k.).

### 1.5 `POST /api/payroll/{id}/approve`
Body ixtiyoriy:
```json
{ "expectedNetSalary": 3360000 }
```
DRAFT dagi hisob muzlatiladi; shu paytdagi PENDING bonus/jarimalar (TEACHER yoki STAFF, oy oxirigacha kuchga kirgan)
**APPLIED** bo'ladi — `netSalary` DRAFT dagidan farq qilishi mumkin (orada bonus qo'shilgan bo'lsa).

- **`expectedNetSalary` berilsa** (tavsiya: foydalanuvchi ko'rgan `netSalary`) va yakuniy summa farq qilsa → **409
  `payroll.netChanged`**, hech narsa yozilmaydi:
  ```json
  { "status": 409, "code": "payroll.netChanged", "message": "Oylik summasi o'zgargan: ...",
    "data": { "netSalary": 3610000, "expectedNetSalary": 3360000, "bonusPenaltyAdjustment": 410000 } }
  ```
  Frontend yangi summani ko'rsatadi va foydalanuvchi tasdiqlasa `expectedNetSalary: data.netSalary` bilan qayta yuboradi.
- Berilmasa — tekshiruvsiz (avvalgidek).

Xatolar: 409 `payroll.notDraft`; 409 `payroll.netChanged`; 409 `payroll.recalculateRequired` (v1 qoralamasi,
`calcVersion = null`); 400 `payroll.netNegative` (jarimalar oylikdan oshgan).

### 1.6 `POST /api/payroll/{id}/pay`
Sarlavha: `Idempotency-Key: <uuid>` — **tavsiya etiladi** (to'lov dialogi ochilganda yarating). Body ixtiyoriy:
```json
{
  "paymentMethod": "CASH_AND_CARD",
  "cashRegisterId": 2,
  "paymentMethodForCash": null,
  "cashPart": 2000000,
  "cardPart": 1360000,
  "idempotencyKey": null
}
```
| Maydon | Izoh |
|---|---|
| `paymentMethod` | `PaymentMethod` (default `CASH`) |
| `cashRegisterId` | berilsa — kassaga chiqim (EXPENSE, summa = `netSalary`); berilmasa (bank o'tkazmasi) — kassa yozuvisiz |
| `paymentMethodForCash` | kassaga yoziladigan usulni majburlash (eski nomlar ham: `PLASTIC`, `BANK_TRANSFER`) |
| `cashPart`, `cardPart` | faqat `CASH_AND_CARD`: yig'indisi `netSalary` ga teng |
| `idempotencyKey` | sarlavha o'rniga (≤ 64); sarlavha ustun |

Takror so'rov: kalit **bir xil** bo'lsa — o'sha javob (200, yangi kassa yozuvi yo'q); kalitsiz yoki boshqa kalit bilan →
409 `payroll.alreadyPaid`. Boshqa xatolar: 409 `payroll.notApproved`; 400 `payroll.idempotency.keyTooLong`;
kassa xatolari avvalgidek. Kassa manfiyga tushishi mumkin.

### 1.7 `POST /api/payroll/{id}/cancel` (faqat SA)
```json
{ "reason": "Hisobda xato — qayta hisoblanadi" }
```
- APPROVED yoki PAID → CANCELLED. Qo'llangan bonus/jarimalar **PENDING** ga qaytadi (keyingi oylik oladi).
- PAID bo'lsa kassaga **REVERSAL** yoziladi (chiqim teskarisi — pul kassaga qaytadi), `relatedTxId` = asl chiqim.
- Shu oy uchun `generate` keyin yangi DRAFT yaratadi.

Xatolar: 403 `payroll.cancel.forbidden` (SA emas); 400 `payroll.cancel.reasonRequired` (3–500 belgi);
409 `payroll.cancel.draft` (DRAFT — `DELETE` qiling); 409 `payroll.alreadyCancelled`;
400 `payroll.cancel.legacyCash` (eski tizimda to'langan oylikning kassa yozuvi topilmadi — kassada qo'lda tuzatiladi).

### 1.8 `DELETE /api/payroll/{id}`
Faqat DRAFT (bonuslar baribir PENDING — hech narsa yo'qolmaydi). Boshqa holat → 409 `payroll.notDraft`.

## 2. Preview — `GET /api/payroll/calculate`

`data` = `SalaryCalculationDto[]` (yoki bitta, `/{userId}` da):

| Maydon | Izoh |
|---|---|
| `userId`, `fullName`, `role`, `month`, `year` | |
| `calculable`, `message`, `messageCode` | `false` bo'lganda: `messageCode` ∈ `RULE_NOT_FOUND`, `TEACHER_PROFILE_MISSING` ("O'qituvchi profili yo'q"), `TEACHER_PROFILE_LINKED_TO_OTHER_USER` ("O'qituvchi profili boshqa userga bog'langan (teacher #X → user #Y)"), `ROLE_NOT_CALCULATED` |
| `baseSalary` | `fixedSalary` |
| `paidStudentCount`, `paidStudentUnits`, `perStudentAmount` | TEACHER (`paidStudentUnits` kasr bo'lishi mumkin) |
| `newStudentCount`, `newStudentAmount` | ADMIN, SALES |
| `kpiApplied`, `kpiAmount`, `totalActiveStudents` | ADMIN (`totalActiveStudents` — oy oxirida hisob davri bor o'quvchilar) |
| `grossAmount`, `bonusPenaltyAdjustment`, `totalAmount` | `totalAmount = net` |
| `calculationDetails` | §3. **v1 dagi `details` (Map) va `students` olib tashlandi.** |

## 3. `calculationDetails` (obyekt)

```json
{
  "version": 2,
  "month": 9,
  "year": 2026,
  "role": "TEACHER",
  "estimated": false,
  "rule": {
    "id": 4, "scope": "PERSONAL", "role": "TEACHER", "effectiveFrom": "2026-01-01", "effectiveTo": null,
    "fixedSalary": 3000000, "perPayingStudent": 100000, "perNewStudent": 0,
    "kpiThreshold": null, "kpiBonus": 0
  },
  "lines": [
    { "code": "FIXED",              "label": "Belgilangan oylik", "base": 3000000, "count": 1,      "amount": 3000000 },
    { "code": "PER_PAYING_STUDENT", "label": "To'lagan o'quvchi", "base": 100000,  "count": 1.6667, "amount": 166667 },
    { "code": "BONUS",              "label": "Bonuslar",          "base": null,    "count": 1,      "amount": 200000, "status": "PENDING" },
    { "code": "PENALTY",            "label": "Jarimalar",         "base": null,    "count": 1,      "amount": -40000, "status": "PENDING" }
  ],
  "gross": 3166667,
  "bonusPenalty": 160000,
  "net": 3326667,
  "items": {
    "paidPeriods": [
      { "periodId": 31, "studentGroupId": 12, "studentId": 7, "studentName": "Ali Valiyev",
        "groupId": 3, "groupName": "IELTS-1", "periodStart": "2026-09-15", "paidOn": "2026-09-15", "countedOn": "2026-09-15" }
    ],
    "lessonEnrollments": [
      { "studentGroupId": 14, "studentId": 9, "studentName": "Vali Aliyev", "groupId": 5, "groupName": "Math",
        "lessons": 2, "totalLessons": 3, "share": 0.6667 }
    ],
    "newStudents": [],
    "kpi": null,
    "bonuses": [
      { "id": 11, "kind": "BONUS",   "amount": 200000, "effectiveDate": "2026-09-10", "reason": "Ochiq dars", "status": "PENDING" },
      { "id": 12, "kind": "PENALTY", "amount": 40000,  "effectiveDate": "2026-09-11", "reason": "Kechikish",  "status": "PENDING" }
    ]
  }
}
```

| Joy | Qoida |
|---|---|
| `lines[]` | `{code, label, base, count, amount}` (+ `status` faqat BONUS/PENALTY). **`Σ amount = net`** — jadval shu qatorlardan chiziladi |
| `code` | `FIXED`, `PER_PAYING_STUDENT` (TEACHER), `PER_NEW_STUDENT` (ADMIN, SALES), `KPI` (ADMIN), `BONUS`, `PENALTY` |
| `status` (BONUS/PENALTY) | `PENDING` — DRAFT da "kutilmoqda"; `APPLIED` — tasdiqlangan oylikda |
| `items.paidPeriods[].countedOn` | `max(paidOn, periodStart)` — davr shu sana oyiga sanaladi (oldindan to'lov kelajak oyda sanaladi) |
| `count` | son; `PER_PAYING_STUDENT` da **kasr bo'lishi mumkin** (4 xonagacha). `amount = uzs(base × aniq birliklar)` — `base × count` dan 1 so'mga farq qilishi mumkin |
| `estimated` | `true` — billing v2 cutover oyi (oyning bir qismi eski tizimda) |
| `items.paidPeriods` | faqat yopilishida **real to'lov (PAYMENT)** qatnashgan davrlar — faqat chegirma/bonus bilan yopilgani kirmaydi (sozlama `app.payroll.count-discount-covered`) |
| `items.lessonEnrollments[]` | `lessons` — shu o'qituvchining darslari, `totalLessons` — yozilmaning oydagi barcha darslari, `share = lessons / totalLessons` |
| `items.newStudents[].source` | `PERIOD` (birinchi real to'langan davri yopilgan) yoki `PAYMENT` (faqat PER_LESSON o'quvchi — birinchi to'lov) |
| `items.kpi` | ADMIN: `{threshold, actual, applied}`; boshqalarda `null` |
| `label` | o'zbekcha; tarjima uchun frontend `code` dan foydalansin |

## 4. Oylik qoidalari — `/api/salary-rules` (faqat SA)

| Metod, yo'l | Tavsif |
|---|---|
| `GET /api/salary-rules` | hammasi (nofaollar ham) |
| `GET /api/salary-rules/{id}` | **yangi** |
| `POST /api/salary-rules` | yaratish → 201. `effectiveFrom` berilsa, shu doiradagi (shu xodim yoki shu rolning umumiy) faol qoidalar: **oldinroq boshlangani** — `effectiveTo = effectiveFrom − 1 kun` bilan yopiladi; **shu sanadan yoki keyin boshlangani** — tasdiqlangan/to'langan oylikda ishlatilmagan bo'lsa nofaol qilinadi (`isActive=false`, `effectiveTo=null`), ishlatilgan bo'lsa → **409 `salaryRule.overlapsUsed`** (hech narsa yozilmaydi). `effectiveTo < effectiveFrom` holati hech qachon paydo bo'lmaydi |
| `PUT /api/salary-rules/{id}` | tahrirlash. Qoida **tasdiqlangan yoki to'langan oylikda ishlatilgan** bo'lsa → **409 `salaryRule.inUse`** ("yangi qoida yarating") |
| `DELETE /api/salary-rules/{id}` | `isActive = false` |

**Yakuniy maydon nomlari (so'rov = javob):**

| Maydon | Tur | Kim uchun | Izoh |
|---|---|---|---|
| `role` | string, majburiy | | `TEACHER`, `ADMIN`, `SALES_MANAGER`, `SALES_HEAD` (boshqasi → 400 `salaryRule.role.invalid`). SALES_HEAD — SALES_MANAGER kabi: `fixedSalary`, `perNewStudent` |
| `userId` | number \| null | | null — rol uchun umumiy qoida; berilsa xodim roli = `role` (aks holda 400 `salaryRule.userRoleMismatch`) |
| `fixedSalary` | number ≥ 0 | hammasi | belgilangan oylik |
| `perPayingStudent` | number ≥ 0 | TEACHER | har to'lagan o'quvchi (davr) uchun |
| `perNewStudent` | number ≥ 0 | ADMIN, SALES_MANAGER, SALES_HEAD | har yangi o'quvchi uchun |
| `kpiThreshold` | integer ≥ 0 | ADMIN | oy oxirida faol o'quvchilar chegarasi |
| `kpiBonus` | number ≥ 0 | ADMIN | chegaraga yetsa |
| `effectiveFrom` | date \| null | | shu sanadan kuchga kiradi |
| `effectiveTo` | date \| null | | **yangi:** shu sanagacha (kiritilgan) amal qiladi; null — muddatsiz. `effectiveTo < effectiveFrom` → 400 `salaryRule.effectiveRange.invalid` |
| `isActive` | boolean | | default true |

Oy uchun qoida: `effectiveFrom ≤ oy oxiri ≤ effectiveTo` bo'lgan faol qoidalardan — avval shaxsiy, keyin rol umumiysi;
bir nechta bo'lsa eng so'nggi `effectiveFrom`.

Javobda qo'shimcha: `id`, `userName`, `createdAt`, `updatedAt`.

- **Eski nomlar (`baseSalary`, `perStudentFee`, `newStudentBonus`) va har qanday noma'lum maydon → 400
  `salaryRule.field.unknown`** (avval jimgina tashlab yuborilib, qoida 0 so'm bilan saqlanardi).
- Rolga tegishli bo'lmagan maydon nol emas → 400 `salaryRule.field.notApplicable` (masalan TEACHER uchun `perNewStudent: 5`).
  `null` yoki `0` yuborish mumkin.
- Ishlatilgan qoidani o'zgartirish tartibi: yangi qoida `POST` (yangi `effectiveFrom` bilan) — eskisi o'zi yopiladi,
  o'tgan tasdiqlangan oyliklar o'zgarmaydi.

Misol:
```http
POST /api/salary-rules
{ "role": "TEACHER", "userId": 42, "fixedSalary": 3500000, "perPayingStudent": 50000, "effectiveFrom": "2026-10-01" }
```
→ shu xodimning oldingi qoidasi `effectiveTo: "2026-09-30"` bo'ladi.

## 5. Bonus/jarima — `/api/bonus-penalties`

- **`targetType: "STAFF"` (yangi)** — ADMIN, SALES_MANAGER yoki SALES_HEAD xodim oyligiga bonus/jarima:
  ```json
  { "kind": "BONUS", "targetType": "STAFF", "userId": 15, "amount": 300000, "reason": "Reja bajarildi", "effectiveDate": "2026-09-10" }
  ```
  `userId` majburiy (400 `bonus.staff.userRequired`); xodim roli ADMIN/SALES_MANAGER/SALES_HEAD bo'lmasa → 400 `bonus.staff.roleInvalid`
  (o'qituvchi uchun `TEACHER` + `teacherId`). Oqim TEACHER bilan bir xil: oylik DRAFT da "kutilmoqda", tasdiqlanganda
  APPLIED, oylik bekor qilinsa PENDING ga qaytadi.
- `BonusPenaltyDto` ga **`userId`, `userName`** (STAFF; `targetName` = xodim ismi) va **`appliedToPayrollId`** qo'shildi.
- `GET /api/bonus-penalties?userId=` — yangi filtr; `targetType=STAFF` filtri ham ishlaydi.
- `GET /api/bonus-penalties/preview/staff/{userId}?upToDate=` — **yangi**, `BonusPenaltyPreviewDto` (teacher preview kabi).
- O'qituvchi/xodim bonusi endi **faqat oylik tasdiqlanganda** APPLIED bo'ladi (avval generate paytida).
- Oylikka qo'llangan bonusni `PATCH /{id}/cancel` bekor qilmaydi → 400 `bonus.cancel.teacherApplied`;
  avval oylik bekor qilinadi (bonus PENDING ga qaytadi), keyin bonus tahrirlanadi/bekor qilinadi.

## 6. Moliya hisoboti — `GET /api/finance/report` (SA, A, ACC)

Query: `from`, `to` (ISO sana; default — oy boshi … bugun). Javob `FinanceReportResponse`:

| Maydon | Izoh |
|---|---|
| `totalIncome` | Σ PAID to'lovlar naqd qismi, `paymentDate ∈ [from, to]` (o'zgarmagan) |
| `totalExpenses` | Σ `Expense` (xarajatlar jadvali), `expenseDate ∈ [from, to]` (o'zgarmagan) |
| `payrollPaid` | **yangi:** Σ `netSalary` — `status = PAID`, `paidAt ∈ [from, to]` (oylik oyi emas, to'langan kuni); CANCELLED kirmaydi |
| `payrollByRole` | **yangi:** `{ "TEACHER": …, "ADMIN": …, "SALES_MANAGER": …, "SALES_HEAD": … }` (faqat summasi bor rollar; xodimi aniqlanmagan eski yozuv — `OTHER`) |
| `netProfit` | **o'zgardi:** `totalIncome − totalExpenses − payrollPaid` |
| `incomeByCategory`, `expenseByCategory`, `period` | o'zgarmagan |

Oylik to'lovi kassada `CashTransaction(EXPENSE)` sifatida ham yoziladi, lekin `totalExpenses` faqat `Expense`
jadvalidan olinadi — oylik **ikki marta ayirilmaydi**. (Oylikni qo'lda `Expense(SALARY)` qilib kiritish endi kerak emas —
kiritilsa, ikki marta hisoblanadi.)

```json
{
  "totalIncome": 1400000, "totalExpenses": 0,
  "payrollPaid": 7100000, "payrollByRole": { "TEACHER": 3100000, "ADMIN": 4000000 },
  "netProfit": -5700000,
  "incomeByCategory": { "STUDENT_PAYMENT": 1400000 }, "expenseByCategory": {},
  "period": "2026-09-01 to 2026-09-30"
}
```

## 7. Eski frontend (`adizone-crm-front`) uchun o'zgartirishlar ro'yxati

| Joy | Nima qilish kerak |
|---|---|
| `salary-rules.vue` | Nomlar allaqachon to'g'ri (`fixedSalary`, `perPayingStudent`, `perNewStudent`) — endi saqlanadi. `getById` endi ishlaydi |
| `payroll-list.vue` status filtri | `PENDING` → `DRAFT`, `APPROVED`, `PAID`, `CANCELLED` |
| `payroll-list.vue` tahrirlash (PUT) | olib tashlash — o'rniga Qayta hisoblash / Tasdiqlash / To'lash / Bekor qilish tugmalari (§0) |
| `payrollService.create` (POST) | olib tashlash |
| `payrollService.generate` | body `{month, year, recalculate}` endi qabul qilinadi; javob `{created, recalculated, skipped[], totalAmount}` |
| `payrollService.pay` | `Idempotency-Key` sarlavhasi; faqat APPROVED uchun |
| `calculationDetails` | endi obyekt — `JSON.parse` kerak emas; jadval `lines[]` dan |
| `payroll-calculate.vue` | `details`/`students` o'rniga `calculationDetails.items` |
| Tasdiqlash tugmasi | body `{expectedNetSalary: <ko'rsatilgan netSalary>}`; 409 `payroll.netChanged` da `data.netSalary` ni ko'rsatib qayta so'rash |
| Oylik oyi tanlovi | cutover oyidan oldingi oylar → 400 `payroll.beforeCutover` (xabarni ko'rsatish); `calculationDetails.estimated` → "taxminiy" belgisi |
| `paidStudentUnits` | kasr bo'lishi mumkin — formatlash (`1.6667`) |
| `salary-rules.vue` | `effectiveTo` ustuni/maydoni; tahrirda 409 `salaryRule.inUse` → "yangi qoida yaratish" taklifi; yaratishda 409 `salaryRule.overlapsUsed` → keyinroq `effectiveFrom` so'rash |
| Bonus/jarima formasi | `targetType: STAFF` + xodim tanlash (`userId`, ADMIN/SALES_MANAGER/SALES_HEAD) |
| Moliya hisoboti | `payrollPaid`, `payrollByRole` kartochkalari; `netProfit` endi oylikni ayirgan |
| Generate natijasi | `skipped[].code` bo'yicha xabar/havola: `TEACHER_PROFILE_MISSING` → "Profil yaratish" (repair), `ERROR` → xodim qatorida xato belgisi |
| Kassa tranzaksiyalari | `direction` / `signedAmount` bo'yicha rang va ishora; `paymentId` → to'lov kartasi, `payrollId` → oylik, `relatedTxId` → asl yozuv havolasi (billing-v2-api §5) |
| Generate `TEACHER_PROFILE_MISSING` | qatorda "Profil yaratish" tugmasi → `POST /api/teachers/{userId}/ensure-profile` (§8.1) |

## 8. O'qituvchi profili ta'miri — `POST /api/admin/repair/link-teacher-users` (faqat SA)

Oylik `TEACHER_PROFILE_MISSING` bilan o'tkazgan xodimlar uchun. Body yo'q. Javob:
```json
{
  "linkedCount": 1, "createdCount": 3, "skippedCount": 1,
  "remainingUnlinked": 1, "usersWithoutProfile": 0,
  "items": [
    { "type": "TEACHER", "teacherId": 7,  "userId": 25,   "username": "B.Nodira", "action": "LINKED",  "reason": null },
    { "type": "TEACHER", "teacherId": 9,  "userId": null, "username": null,       "action": "SKIPPED", "reason": "Mos TEACHER user topilmadi (ism, telefon, email bo'yicha)" },
    { "type": "USER",    "teacherId": 12, "userId": 20,   "username": "Z.Abdumajidova", "action": "CREATED", "reason": null }
  ]
}
```
- 1-qadam (`type: TEACHER`): egasiz profil → mos TEACHER user (ism / telefon / email). O'tkazilsa sababi: user topilmadi
  yoki mos user allaqachon boshqa profilga bog'langan.
- 2-qadam (`type: USER`): profili yo'q TEACHER user → profil yaratiladi (`CREATED`) yoki shu telefonli egasiz profilga
  bog'lanadi (`LINKED`); har user alohida tranzaksiyada, xato bo'lsa `SKIPPED` + sabab.
- `remainingUnlinked` — hali egasiz profillar; `usersWithoutProfile` — hali profili yo'q TEACHER userlar (0 bo'lishi kerak).

### 8.1 Bitta user — `POST /api/teachers/{userId}/ensure-profile` (faqat SA)
UI'dagi "Profil yaratish" tugmasi uchun (masalan generate `skipped[].code = TEACHER_PROFILE_MISSING` qatorida).
Path dagi id — **User** id. Body yo'q. Javob repair bilan bir xil shaklda, bitta item bilan:
```json
{ "linkedCount": 0, "createdCount": 1, "skippedCount": 0,
  "items": [ { "type": "USER", "teacherId": 12, "userId": 20, "username": "Z.Abdumajidova", "action": "CREATED", "reason": null } ] }
```
| `action` | Ma'nosi |
|---|---|
| `CREATED` | yangi Teacher profili yaratildi |
| `LINKED` | shu telefonli egasiz profil shu userga bog'landi |
| `EXISTS` | profil allaqachon bor (`teacherId`) — hech narsa o'zgarmadi |
| `SKIPPED` | `reason`: "Roli TEACHER emas: …" yoki "Shu telefon/email li profil #X boshqa userga (#Y) bog'langan — qo'lda tekshiring" (dublikat profil yaratilmaydi; `teacherId` — o'sha profil) |

User topilmasa → 404; SA emas → 403. Xuddi shu "boshqa userga bog'langan" tekshiruvi repair'ning 2-qadamida ham
qo'llanadi (`action: SKIPPED`).

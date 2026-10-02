# Ta'tillar, "darsni X o'tdi", imtihon to'lovi, shartnoma, rekvizitlar — API (frontend uchun)

> Dizayn va yakuniy qarorlar: [`leaves-exams-contracts.md`](leaves-exams-contracts.md) (§0.1 — ustun). Bu hujjat —
> **amalda qurilgan** holat (branch `billing-v2`, V61–V63).
> Javoblar `ApiResponse {success, message, data}` ichida; xatolar `ErrorResponse {status, error, message, code, data?}`.
> `message` `Accept-Language` (uz/ru/en) bo'yicha; frontend mantiqi uchun **`code`** ga tayaning.

## 0. Umumiy

| Mavzu | Qoida |
|---|---|
| Rollar | SA = SUPER_ADMIN, A = ADMIN, SALES = SALES_MANAGER, ACC = ACCOUNTANT, T = TEACHER. "STAFF" = shu beshtasi. Boshqasi → **403**. |
| Sana / vaqt | `LocalDate` — `"2026-09-15"`, `LocalDateTime` — `"2026-09-15T18:40:00"` (Asia/Tashkent). |
| Summalar | UZS, butun so'm (`700000`). |
| Qulf band | Parallel amal 5 s dan ko'p kutsa → 409 `concurrency.busy` (qayta urinish mumkin). |
| Sahifa | `PageResponse<T>` = `{content, pageNumber, pageSize, totalElements, totalPages, last}`. |
| Yopiq oylik | Ta'til/o'rinbosar o'zgarishi oyligi **APPROVED/PAID** bo'lgan oyga tegsa → 409 `leave.payrollLocked` / `substitution.payrollLocked` (`message` da oy). |

---

## 1. Ta'tillar — `/api/leaves`

Barcha xodimlar ta'til so'raydi (o'zi uchun). SA/A — istalgan xodim uchun yozadi, tasdiqlaydi/rad etadi.
Haqli/haqsiz **tasdiqlashda** tanlanadi. Hard delete yo'q (`DELETE` → 405) — bekor qilish.

```
           submit                approve {paid}
 (yo'q) ──────► PENDING ─────────────────────► APPROVED
                  │  reject {note}                │ cancel (SA/A)
                  ▼                               ▼
               REJECTED      cancel (egasi/SA/A) ► CANCELLED
```

| status | Kimga qaysi tugma |
|---|---|
| `PENDING` | SA/A: Tasdiqlash (haqli / haqsiz), Rad etish (izoh bilan), Bekor qilish. Egasi: Bekor qilish |
| `APPROVED` | SA/A: Bekor qilish (oylik yopilgan bo'lsa — 409) |
| `REJECTED`, `CANCELLED` | — |

### 1.1 Endpointlar

| Metod, yo'l | Rollar | Tavsif |
|---|---|---|
| `GET /api/leaves` | SA, A | Ro'yxat. Query: `status` (`PENDING|APPROVED|REJECTED|CANCELLED`; noma'lum → 400 `leave.status.invalid`), `userId`, `teacherId`, `from`, `to` (davr kesishuvi), `paid` (`true|false`), `page` (0), `size` (20). Tartib: yaratilgan ↓ |
| `GET /api/leaves/my` | STAFF | O'zi haqidagi va o'zi bergan arizalar (sahifali) |
| `GET /api/leaves/{id}` | STAFF | SA/A — har qanday; boshqalar — faqat o'zi haqidagi/o'zi bergan (aks holda 403 `leave.forbidden`) |
| `GET /api/leaves/{id}/affected-lessons` | SA, A | O'qituvchining shu davrdagi rejadagi darslari (+ "darsni X o'tdi" belgisi) |
| `GET /api/leaves/summary?userId&year` | STAFF | Yil bo'yicha APPROVED: haqli/haqsiz kunlar. `userId` berilmasa — o'zi; boshqaniki — faqat SA/A |
| `GET /api/leaves/pending/count` | SA, A | `{count}` — menyu belgisi uchun |
| `POST /api/leaves` | STAFF | Ariza → 201 |
| `POST /api/leaves/{id}/approve` | SA, A | `{paid, note?}` |
| `POST /api/leaves/{id}/reject` | SA, A | `{note}` |
| `POST /api/leaves/{id}/cancel` | STAFF | `{note?}` — egasi: faqat PENDING; SA/A: PENDING yoki APPROVED |
| `PATCH /api/leaves/{id}/status` | SA, A | **deprecated** alias: `{status: APPROVED, paid}` / `{status: REJECTED, reason}` / `{status: CANCELLED}`. `APPROVED` + `paid` yo'q → 400 `leave.paidRequired` |
| `GET /api/leaves/pending`, `/teacher/{id}`, `/user/{id}` | — | **deprecated**, yangi filtrlar bilan `GET /api/leaves` / `/my` ishlating |

### 1.2 `POST /api/leaves`
```json
{ "userId": 12, "leaveType": "FAMILY", "fromDate": "2026-09-14", "toDate": "2026-09-16", "reason": "To'y" }
```
- `userId` — faqat SA/A uchun (boshqa xodim nomidan); boshqalarda e'tiborsiz (har doim o'zi). Eski `teacherId` ham tushuniladi (o'qituvchi profiliga bog'langan user).
- `leaveType`: `ANNUAL`, `SICK`, `FAMILY`, `STUDY`, `OTHER` (noma'lum → 400 `leave.type.invalid`). Haqli/haqsizni tur emas, tasdiqlash belgilaydi.
- Xatolar: 400 `leave.dates.invalid` (from > to), 400 `leave.tooLong` (> 60 kalendar kun), 409 `leave.overlap`
  (shu xodimning PENDING/APPROVED ta'tili bilan kesishadi; `data = {leaveId, fromDate, toDate, status}`),
  400 `leave.userNotStaff`, 400 `leave.teacherNoUser`.

### 1.3 `POST /api/leaves/{id}/approve`
```json
{ "paid": false, "note": "Kelishildi" }
```
- `paid` **majburiy** → aks holda 400 `leave.paidRequired`.
- ADMIN o'z ta'tilini tasdiqlay olmaydi → 403 `leave.selfApprove` (SA tasdiqlaydi).
- Holat PENDING emas → 409 `leave.notPending`. Kesishuv qayta tekshiriladi → 409 `leave.overlap`.
- `paid = false` va davr oyligi APPROVED/PAID → 409 `leave.payrollLocked`.
- Javob: `LeaveResponse` + `affectedLessons[]` (o'qituvchi bo'lsa — "darsni kim o'tadi" ni belgilash uchun, §2).
- Ta'til bugunni qamrasa o'qituvchi statusi darhol `ON_LEAVE`; har kuni 00:10 da job `ACTIVE ↔ ON_LEAVE` ni yangilaydi (`INACTIVE` ga tegmaydi).

### 1.4 `reject` / `cancel`
- `reject`: `{note}` majburiy, 3–500 belgi → aks holda 400 `leave.noteRequired`. Izoh `decisionNote` da (arizaning `reason` i o'zgarmaydi).
- `cancel`: egasi faqat PENDING ni (APPROVED → 409 `leave.notPending`); SA/A — PENDING/APPROVED (boshqasi → 409 `leave.notCancellable`).
  APPROVED davri oyligi yopilgan → 409 `leave.payrollLocked`. Ta'tilga bog'langan o'tilmagan "darsni X o'tdi" belgilari ham bekor bo'ladi.

### 1.5 `LeaveResponse`
```json
{
  "id": 7, "uuid": "…", "userId": 12, "userName": "Ali Valiyev", "userRole": "TEACHER",
  "teacherId": 4, "teacherName": "Ali Valiyev", "requesterId": 12, "requesterName": "Ali Valiyev",
  "leaveType": "FAMILY", "fromDate": "2026-09-14", "toDate": "2026-09-16",
  "days": 3, "workdays": 3,
  "reason": "To'y", "status": "APPROVED", "paid": false,
  "decidedById": 1, "decidedByName": "Direktor", "decidedAt": "2026-09-10T11:00:00", "decisionNote": "Kelishildi",
  "cancelledAt": null, "cancelledByName": null, "createdAt": "2026-09-09T09:12:00",
  "affectedLessons": [ … ]          // faqat approve javobida
}
```
`days` — kalendar kunlar; `workdays` — **Du–Sha, bayramsiz** (haqsiz ta'til ayirmasi shu bilan hisoblanadi). `paid` — PENDING/REJECTED/CANCELLED da `null`.

### 1.6 `AffectedLessonDto` (`affected-lessons` va `approve`)
```json
{ "groupId": 4, "groupName": "IELTS 2", "lessonDate": "2026-09-14", "startTime": "14:00", "endTime": "15:30",
  "substitutionId": 31, "substituteTeacherId": 9, "substituteTeacherName": "Vali Karimov", "substitutionStatus": "PLANNED" }
```
Belgi yo'q bo'lsa `substitution*` maydonlari `null`.

### 1.7 `GET /api/leaves/summary`
```json
{ "userId": 12, "year": 2026, "paidDays": 10, "unpaidDays": 3, "paidWorkdays": 9, "unpaidWorkdays": 3,
  "byType": { "ANNUAL": 10, "FAMILY": 3 } }
```
Yil chegarasidagi ta'til faqat shu yilga tushgan qismi bilan. Kvota yo'q (faqat hisobot).

---

## 2. "Darsni X o'tdi" (o'rinbosar) — `/api/substitutions`

O'rinbosar **majburiy emas**: o'qituvchilar o'zaro kelishadi, SA/A esa "shu guruhning shu sanadagi darsini X o'tdi"
deb belgilaydi (dars oldidan ham, keyin ham). Shu darsning davomatini **faqat o'rinbosar** (va SA/A) belgilaydi.
O'rinbosar har **o'tilgan** dars (davomati belgilangan, `CONDUCTED`) uchun oylik qoidasidagi `substituteLessonRate`
ni oladi; asosiy o'qituvchidan ayirilmaydi; o'quvchi to'lovi o'zgarmaydi. Dars umuman o'tilmasa — guruhning dars
istisnosi `CANCELLED` (mavjud `POST /api/groups/{id}/lesson-exceptions` oqimi) — o'tilmagan belgi avtomatik bekor.

```
 belgilash ──► PLANNED ──(shu dars davomati birinchi saqlandi)──► CONDUCTED
                  │                                                  │
                  └──────────────── cancel (SA/A) ────────────────────┴──► CANCELLED
```
Belgilash paytida davomat allaqachon bo'lsa — darhol `CONDUCTED`.

| Metod, yo'l | Rollar | Tavsif |
|---|---|---|
| `POST /api/substitutions` | SA, A | `{groupId, lessonDate, substituteTeacherId, leaveRequestId?, note?}` → 201 |
| `POST /api/substitutions/bulk` | SA, A | `{items: [ … ]}` (1–200) — hammasi yoki hech biri; xatoda `data.index` — qaysi element |
| `GET /api/substitutions` | SA, A, ACC | Query: `teacherId` (asosiy yoki o'rinbosar), `substituteTeacherId`, `groupId`, `from`, `to`, `status`, `page`, `size` (50) |
| `GET /api/substitutions/my` | T | O'rinbosar sifatidagi bugungi va kelgusi darslarim |
| `POST /api/substitutions/{id}/cancel` | SA, A | `{reason?}`. CONDUCTED ham bekor qilinadi (xato belgi), lekin o'rinbosar oyligi yopilgan oyda → 409 |

Xatolar (belgilash): 400 `substitution.sameTeacher` (guruh o'qituvchisining o'zi), 400 `substitution.teacherInactive`
(ACTIVE emas, masalan ON_LEAVE), 400 `substitution.noLesson` (shu kuni darsi yo'q: jadval/bayram/istisno),
400 `substitution.noTeacher`, 409 `substitution.exists` (shu darsda faol belgi bor), 409 `substitution.timeConflict`
(o'rinbosarning shu vaqtda o'z darsi yoki boshqa belgisi bor; `data = {groupId, groupName, startTime, endTime}`),
409 `substitution.payrollLocked`; bekor qilish: 404 `substitution.notFound`, 409 `substitution.alreadyCancelled`.

**`SubstitutionResponse`:**
```json
{ "id": 31, "groupId": 4, "groupName": "IELTS 2", "lessonDate": "2026-09-15", "startTime": "14:00", "endTime": "15:30",
  "originalTeacherId": 2, "originalTeacherName": "…", "substituteTeacherId": 9, "substituteTeacherName": "…",
  "leaveRequestId": 7, "status": "CONDUCTED", "conductedAt": "2026-09-15T15:10:00", "conductedByName": "…",
  "note": null, "createdByName": "…", "createdAt": "…", "cancelledAt": null, "cancelledByName": null, "cancelReason": null }
```

### 2.1 Davomat ruxsati (o'zgargan)

| Kim | `POST /api/attendance/mark`, `POST /api/attendance/unlock-requests` | `GET /api/attendance/group/{id}?date=` |
|---|---|---|
| SA, A | har doim | har doim |
| Guruh o'qituvchisi | o'z guruhi, **shu kuni faol belgi bo'lmasa** (bo'lsa → 403 `substitution.lessonTaken`, `message` da kim o'tishi) | har doim |
| O'rinbosar | faqat belgi bor kun (o'tgan kun — odatdagidek unlock bilan) | faqat o'sha kun |

### 2.2 O'qituvchi kabineti `GET /api/teacher/dashboard`
`todayLessons[]` ga o'rinbosar darslari qo'shiladi: `{…, "substitute": true, "substitutionId": 31, "originalTeacherName": "…"}`.
Bugungi darsini boshqa o'qituvchi o'tadigan asosiy o'qituvchida o'sha dars ko'rsatilmaydi.
Direktor dashboardining davomat intizomida o'sha dars o'rinbosarga yoziladi.

---

## 3. Oylik: `LEAVE_DEDUCTION`, `SUBSTITUTE_LESSONS`, `substituteLessonRate`

### 3.1 Oylik qoidasi (`/api/salary-rules`, SA)
Yangi maydon **`substituteLessonRate`** (faqat TEACHER; boshqa rolda musbat → 400 `salaryRule.field.notApplicable`).
Bitta o'tilgan o'rinbosar darsi uchun qat'iy summa. Shaxsiy qoidada `null` bo'lsa — rol qoidasidan olinadi.
Ikkalasida yo'q va oyda o'tilgan o'rinbosar darsi bor → oylik hisoblanmaydi: `calculable=false`,
`messageCode = SUBSTITUTE_RATE_MISSING` (generate da `skipped[]`).

### 3.2 `calculationDetails` — yangi qatorlar (`version` o'zgarmadi — 2)
```json
{ "code": "LEAVE_DEDUCTION", "label": "Haqsiz ta'til", "base": 115385, "count": 3, "amount": -346154 }
{ "code": "SUBSTITUTE_LESSONS", "label": "O'rinbosar darslari", "base": 60000, "count": 4, "amount": 240000 }
```
- `LEAVE_DEDUCTION` (barcha oylik rollari: TEACHER, ADMIN, SALES): `amount = −uzs(fixedSalary × haqsiz ish kunlari / oydagi ish kunlari)`,
  ish kuni = Du–Sha, bayram emas; `base` = kunlik stavka (faqat ko'rsatish uchun, `base × count` emas); `|amount| ≤ fixedSalary`.
  Faqat belgilangan qismdan — "to'lagan o'quvchi", "yangi o'quvchi", KPI ga tegilmaydi. Oylar orasidagi ta'til har oyga alohida.
  ACCOUNTANT oyligi tizimda hisoblanmaydi — ta'til yoziladi, ayirma yo'q.
- `SUBSTITUTE_LESSONS` (TEACHER): `base` = stavka, `count` = shu oydagi CONDUCTED darslar (PLANNED pullik emas).
- Qatorlar faqat mavjud bo'lsa chiqadi; `Σ lines.amount = net` saqlanadi; `gross` endi ayirma va o'rinbosar haqini ham o'z ichiga oladi.
- `items.leaves[]`: `{leaveId, fromDate, toDate, leaveType, paid, workdaysInMonth}` — haqli ta'til ham (qatorsiz).
- `items.substitutions[]`: `{substitutionId, groupId, groupName, lessonDate, originalTeacherId, originalTeacherName, conductedAt}`.
- `rule.substituteLessonRate` — hisobda ishlatilgan stavka. Eski snapshot'larda bu maydonlar `null`.

### 3.3 `PayrollResponse` ustunlari
`deductions = |LEAVE_DEDUCTION|` (avval doim 0), `allowances` — o'zgaruvchan qism + `SUBSTITUTE_LESSONS`,
`grossSalary = basicSalary + allowances` (ayirmadan oldin), `netSalary` — yakuniy. `GET /api/payroll/calculate*`
javobida qo'shimcha: `leaveDeduction`, `unpaidLeaveDays`, `substituteLessonCount`, `substituteAmount`.

---

## 4. Imtihon to'lovi — `/api/exams`

`exams.fee` — imtihon narxi (`0` — bepul). Pullik imtihonga yozilish = **shu so'rovda kassaga kirim**;
bekor qilish = kassaga **REVERSAL** (pul qaytadi, asl yozuv o'chmaydi). O'quvchi balansi/qarzi o'zgarmaydi.

| Metod, yo'l | Rollar | Tavsif |
|---|---|---|
| `POST /api/exams`, `PUT /api/exams/{id}` | SA, A, T | `ExamRequest` + **`fee`** (≥ 0; `PUT` da berilmasa o'zgarmaydi). REGISTERED yozilish bor imtihonda `fee` o'zgarsa → 409 `exam.feeLocked`. Guruhli **bepul** imtihon yaratilganda guruh o'quvchilari avtomatik FREE yoziladi (OVERDUE lar yo'q); pullikda — yo'q |
| `GET /api/exams/{id}/registrations` | SA, A, ACC, T (o'z imtihoni) | Sahifali (`size` 50), barcha holatlar, yangilari avval |
| `POST /api/exams/{id}/registrations` | SA, A, ACC; T — faqat bepul | Yozilish (pullikda to'lov bilan) |
| `POST /api/exams/{id}/registrations/{regId}/cancel` | SA, A, ACC | `{reason}` 3–500 |
| `DELETE /api/exams/{id}` | SA, A | To'langan REGISTERED yozilish bor → 409 `exam.hasRegistrations` (avval bekor qiling) |
| `POST /api/exams/{id}/register-student?studentId=` | SA, A, T | **deprecated** — faqat bepulda; pullikda 400 `exam.paymentRequired` |
| `POST /api/exams/{id}/calculate-payment?studentId=` | SA, A, T | **deprecated** — endi `{examId, examName, examDate, fee, amountDue, free, message}` |

`ExamResponse` ga `fee` qo'shildi.

### 4.1 `POST /api/exams/{id}/registrations`
```json
{ "studentId": 5, "cashRegisterId": 2, "paymentMethod": "CASH", "cashPart": null, "cardPart": null, "note": null }
```
Sarlavha: **`Idempotency-Key: <uuid>`** (to'lov dialogi ochilganda yarating; ≤ 64 belgi). Takroriy so'rov — o'sha
yozilish, **200** + `X-Idempotent-Replay: true` (ikkinchi kirim yo'q); boshqa o'quvchi/imtihon bilan — 409 `exam.idempotency.mismatch`.

Tekshiruvlar (shu tartibda):
1. Imtihon nofaol yoki sanasi o'tgan → 409 `exam.closed`.
2. TEACHER — o'z imtihoni va o'z o'quvchisi (aks holda 403).
3. Kirish sharti → 400 `exam.notEligible`, `data.reason` ∈ `ATTENDANCE` (8 tadan kam PRESENT), `NO_ENROLLMENT`, `OVERDUE`.
4. Faol yozilish bor → 409 `exam.alreadyRegistered` (CANCELLED dan keyin qayta yozilish mumkin).
5. Pullik + TEACHER → 403 `exam.paymentRole`.
6. Pullik + `cashRegisterId`/`paymentMethod` yo'q → 400 `exam.paymentRequired`. `CASH_AND_CARD` da qismlar yig'indisi = fee.

Javob 201 — `ExamRegistrationResponse`; pullikda kassada `INCOME` (`transactionName = "Imtihon to'lovi: <nomi>"`,
`examRegistrationId`), chek raqami `RCP-…` (to'lov cheklari bilan bitta ketma-ketlik).

### 4.2 `POST …/registrations/{regId}/cancel`
`{ "reason": "Imtihonga kela olmaydi" }` → 400 `exam.registration.reasonRequired` (3–500), 404 `exam.registration.notFound`,
409 `exam.registration.notActive` (REGISTERED emas), 409 `exam.registration.hasResult` (natija qo'yilgan).
PAID → kassaga `REVERSAL` (`relatedTxId` = asl kirim), `paymentStatus = REFUNDED`. Kassa chelagi manfiyga tushsa ham
yoziladi — javobda `negativeCashBalance: true` (ogohlantirish ko'rsating).

### 4.3 `ExamRegistrationResponse`
```json
{ "id": 18, "examId": 3, "examName": "Oraliq", "studentId": 5, "studentName": "Ali Valiyev",
  "status": "REGISTERED", "paymentStatus": "PAID", "amountDue": 150000, "amountPaid": 150000,
  "cashTransactionId": 77, "refundCashTransactionId": null, "receiptNumber": "RCP-00043",
  "registrationDate": "2026-09-15", "cancelledAt": null, "cancelReason": null, "notes": null }
```
`status` ∈ `REGISTERED, CANCELLED, ATTENDED, ABSENT`; `paymentStatus` ∈ `FREE, PAID, REFUNDED` (PENDING yo'q).
Kassa tarixida (`CashTransactionDto`) yangi maydon `examRegistrationId`. Moliya hisobotida (`GET /api/finance/report`)
yangi `examFees` (kirim − REVERSAL), `netProfit = totalIncome + examFees − totalExpenses − payrollPaid`.

---

## 5. Markaz rekvizitlari — `/api/settings/center`

| Metod, yo'l | Rollar | Tavsif |
|---|---|---|
| `GET /api/settings/center` | STAFF | Rekvizitlar + `missing[]` |
| `PUT /api/settings/center` | **SA** (A → 403) | Qisman: `null`/yo'q maydon — o'zgarmaydi, `""` — tozalanadi |

```json
{ "legalName": "\"ADIZONE LC\" MChJ", "legalNameRu": "ООО «ADIZONE LC»", "shortName": "Adizone",
  "inn": "311626069", "address": "Toshkent sh., Chilonzor t., Novza MFY, Ye mavzesi, 10-uy",
  "phone": "+998 90 045 55 17", "bankName": "ОПЕРУ АКБ «Капитал Банк»", "bankAccount": "20208000007147330001",
  "bankMfo": "00974", "directorName": "Adizov Oqilbek Oybek o'g'li", "contractCity": "Toshkent shahri",
  "licenseInfo": "Xabarnoma tasdiqnomasi №1180460 (reestr X-1743276)", "supportPhone": "+998 77 337 32 33",
  "missing": [] }
```
Validatsiya (bo'sh joylar olib tashlanadi): `inn` — 9 raqam (`settings.center.inn.invalid`), `bankAccount` — 20
(`settings.center.bankAccount.invalid`), `bankMfo` — 5 (`settings.center.bankMfo.invalid`), har maydon ≤ 500
(`settings.center.tooLong`), noma'lum maydon → 400 `settings.center.field.unknown`. `missing` — shartnoma uchun majburiy,
lekin bo'sh: `legalName, inn, address, phone, bankName, bankAccount, bankMfo, directorName`.
`supportPhone` — Telegram Mini App dagi "yordam" tugmasi uchun.

---

## 6. Shartnoma — `/api/contracts`, `/api/contract-templates` (SA, A)

| Metod, yo'l | Tavsif |
|---|---|
| `POST /api/contracts/generate` | `{studentId, studentGroupId?, templateId?}` → 201 `ContractDto` (narx snapshot'i bilan) |
| `GET /api/contracts/{id}/pdf` | `application/pdf`, `Content-Disposition: inline; filename="CTR-2026-00001.pdf"` — yangi oynada ochib chop eting |
| `GET /api/contracts/{id}/pdf?download=true` | `attachment` |
| `GET /api/contracts/{id}/print` | `text/html` — PDF bilan bir xil sahifa (skriptsiz; iframe/yangi oynada `print()`) |
| `POST /api/contracts/{id}/sign` | DRAFT → SIGNED (faqat OFFLINE; OFFER → 400 `contract.sign.offer`). PDF **muzlatiladi** |
| `POST /api/contracts/{id}/accept-offer` | DRAFT → ACCEPTED (faqat OFFER). PDF muzlatiladi. (`PATCH` ham ishlaydi) |
| `POST /api/contracts/{id}/cancel` | `{reason}` 3–500 → CANCELLED (`contract.cancel.reasonRequired`, `contract.alreadyCancelled`) |
| `DELETE /api/contracts/{id}` | Faqat imzolanmagan; SIGNED/ACCEPTED (yoki ulardan bekor qilingan) → 409 `contract.signed` |
| `PATCH /api/contracts/{id}/sign` | **deprecated** — `POST …/sign` |
| `GET /api/contract-templates/placeholders` | `[{key, description, example}]` — shablon muharriri uchun |
| `POST/PUT /api/contract-templates` | Saqlashda tozalanadi; noma'lum `{{x}}` → 400 `contract.template.unknownPlaceholder` (`data.unknown[]`) |

Qayta amal: DRAFT bo'lmagan shartnomani imzolash/qabul qilish → 409 `contract.notDraft`.

### 6.1 Generatsiya va narx
- `studentGroupId` berilmasa: o'quvchining yagona faol yozilmasi; **bir nechta** bo'lsa → 400 `contract.studentGroupRequired`
  (`data.studentGroups = [{studentGroupId, groupName}]` — tanlash oynasi); faol yozilma yo'q — narxsiz shartnoma.
  Boshqa o'quvchining yozilmasi → 400 `contract.studentGroup.mismatch`.
- Snapshot (keyin narx o'zgarsa ham shartnoma o'zgarmaydi): MONTHLY — oylik narx (individual narx > 0, aks holda kurs narxi),
  PER_LESSON — bir dars narxi; chegirma — yozilmaniki.

### 6.2 `ContractDto` (yangi maydonlar)
```json
{ "studentGroupId": 41, "groupName": "IELTS 2", "courseName": "IELTS", "paymentType": "MONTHLY",
  "listPrice": 700000, "discountPercent": 10, "discountAmount": 70000, "finalAmount": 630000, "startDate": "2026-09-15",
  "signedAt": null, "signedByName": null, "cancelledAt": null, "cancelReason": null,
  "hasPdf": false, "missingRequisites": [] }
```
`status` ∈ `DRAFT, SIGNED, ACCEPTED, CANCELLED`. `renderedContent` — tozalangan XHTML bo'lagi (`v-html` xavfsiz:
faqat `p, b, strong, i, em, u, br, h1–h3, table, thead, tbody, tr, td, th, ul, ol, li`, atributsiz; belgilar qiymati escape qilingan).
Eski shartnomalarda snapshot maydonlari `null` — "—" ko'rsating.

### 6.3 Shablon belgilari
`{{contractNumber}} {{contractDate}} {{studentName}} {{studentPhone}} {{studentPassport}} {{parentName}} {{parentPhone}}
{{groupName}} {{courseName}} {{paymentType}} {{coursePrice}} {{discountPercent}} {{discountAmount}} {{finalAmount}}
{{monthlyFee}} {{startDate}} {{centerName}}` va rekvizitlar `{{center.legalName}} {{center.legalNameRu}} {{center.shortName}}
{{center.inn}} {{center.address}} {{center.phone}} {{center.bankName}} {{center.bankAccount}} {{center.bankMfo}}
{{center.directorName}} {{center.contractCity}} {{center.licenseInfo}}` (eski `{{currentDate}}` ham ishlaydi).
Rekvizit bo'sh bo'lsa PDF da `________` va `missingRequisites` da nomi (PDF bloklanmaydi).
Shablon oddiy matn bo'lsa (tegsiz) — qatorlar saqlanadi.

### 6.4 PDF
- A4, Noto Serif/Sans (o'zbek lotini `ʻ ʼ`, kirill `ў қ ғ ҳ`, `№ « » —`), sahifa raqami, pastda ikki tomon rekvizitlari va imzo joylari,
  shartnoma matnidan keyin narx jadvali (kurs narxi, chegirma, yakuniy summa, boshlanish).
- DRAFT — har so'rovda yangidan (rekvizit o'zgarsa PDF ham o'zgaradi). SIGNED/ACCEPTED — imzo paytidagi nusxa
  (keyin rekvizit o'zgarsa ham o'zgarmaydi).
- Chop etish: PDF ni blob URL bilan yangi oynada ochib `print()`; eski `window.print()` + HTML usuli kerak emas.
- Fayl `/api/files/**` orqali ochilmaydi — faqat shu endpointlar (SA, A).

---

## 7. Xato kodlari (yangi)

| Kod | HTTP |
|---|---|
| `leave.type.invalid`, `leave.dates.invalid`, `leave.tooLong`, `leave.paidRequired`, `leave.noteRequired`, `leave.status.invalid`, `leave.teacherNoUser`, `leave.userNotStaff` | 400 |
| `leave.selfApprove`, `leave.forbidden` | 403 |
| `leave.overlap`, `leave.notPending`, `leave.notCancellable`, `leave.payrollLocked` | 409 |
| `substitution.sameTeacher`, `substitution.teacherInactive`, `substitution.noLesson`, `substitution.noTeacher`, `substitution.status.invalid` | 400 |
| `substitution.lessonTaken` | 403 |
| `substitution.notFound` | 404 |
| `substitution.exists`, `substitution.timeConflict`, `substitution.payrollLocked`, `substitution.alreadyCancelled` | 409 |
| `exam.notEligible` (`data.reason`), `exam.paymentRequired`, `exam.registration.reasonRequired` | 400 |
| `exam.paymentRole` | 403 |
| `exam.registration.notFound` | 404 |
| `exam.closed`, `exam.alreadyRegistered`, `exam.idempotency.mismatch`, `exam.feeLocked`, `exam.hasRegistrations`, `exam.registration.notActive`, `exam.registration.hasResult` | 409 |
| `settings.center.field.unknown`, `settings.center.tooLong`, `settings.center.inn.invalid`, `settings.center.bankAccount.invalid`, `settings.center.bankMfo.invalid` | 400 |
| `contract.studentGroupRequired` (`data.studentGroups`), `contract.studentGroup.mismatch`, `contract.template.unknownPlaceholder` (`data.unknown`), `contract.sign.offer`, `contract.cancel.reasonRequired`, `contract.pdf.failed` | 400 |
| `contract.notDraft`, `contract.signed`, `contract.alreadyCancelled` | 409 |

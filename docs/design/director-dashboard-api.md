# Direktor dashboardi — API shartnomasi (frontend uchun)

> **Dizayn va ta'riflar:** `docs/design/director-dashboard.md` (§1 formulalar, §0.1 amalga oshirish aniqliklari).
> **Holat:** backend `billing-v2` branchida tayyor, testlar yashil. Prod uchun avval `V53__director_dashboard.sql` qo'lda bajariladi, keyin backfill (§8).

## 0. Umumiy

| Mavzu | Qoida |
|---|---|
| Javob qobig'i | Har doim `ApiResponse { success, message, data }`. Xatoda `{ success: false, message, code }` — `code` mashina uchun (§9). |
| Vaqt zonasi | Hamma sana **Asia/Tashkent**. `LocalDate` → `"2026-10-01"`, `LocalDateTime` → `"2026-10-01T20:00:00"` (zonasiz, Toshkent vaqti). |
| Pul | So'm, butun son (`700000`). Formatlashni frontend qiladi. |
| Foiz | `percent`, `rate` — 0..100, 1 kasr (`66.7`). Maxraj 0 bo'lsa `null` — "—" ko'rsating. |
| Daqiqa | Javob vaqti **ish daqiqalarida**: dushanba–shanba 09:00–20:00. Tun va yakshanba sanalmaydi. |
| Kesh | Xulosa (`GET /director`) 60 soniya keshlanadi. Raqam bir daqiqagacha eski bo'lishi mumkin. Drill-down keshlanmaydi. |

### 0.1 Davr parametrlari (barcha endpointlarda bir xil)

| Parametr | Qiymat | Default | Izoh |
|---|---|---|---|
| `period` | `DAY` \| `WEEK` \| `MONTH` \| `CUSTOM` | `DAY` | Hafta: dushanba–yakshanba. Oy: kalendar oyi. |
| `date` | `YYYY-MM-DD` | bugun | Shu sana tushgan kun, hafta yoki oy olinadi. |
| `from`, `to` | `YYYY-MM-DD` | — | Faqat `CUSTOM` da, majburiy. `from ≤ to`, ko'pi bilan 366 kun, aks holda 400 `dashboard.period.invalid`. |

**Ikki xil raqam turi bor:**
- **Faollik** — davr ichida sodir bo'lgan hodisalar: lid yaratildi, davomat olindi, to'lov tushdi. Raqam davr chegarasi bilan sanaladi.
- **Kogorta** — davrda boshlanganlar **hozirgacha** qayergacha yetdi: muddati kelgan davrlardan qanchasi undirildi, sinovdan qanchasi to'ladi. O'tgan davr kogortasi vaqt o'tishi bilan "pishadi".

O'tgan **kun** (`period=DAY`, `date < bugun`) uchun backend kechki snapshot'ni beradi (§0.2).

### 0.2 `meta.source`

| Qiymat | Ma'nosi |
|---|---|
| `LIVE` | Hammasi hozir hisoblangan. |
| `SNAPSHOT` | O'sha kunning 23:55 dagi yakuniy holati (`director_daily_stats`). |
| `MIXED` | Bo'limlarning bir qismi snapshot, bir qismi jonli. Sinov va operatorlar doim jonli. |

### 0.3 Rollar va bo'limlar (§7 #15 qarori)

| Bo'lim (`section`) | SUPER_ADMIN | ADMIN | ACCOUNTANT |
|---|---|---|---|
| `funnel` | ✓ | ✓ | — |
| `collections` | ✓ | — | ✓ |
| `debtors` | ✓ | — | ✓ |
| `attendance` | ✓ | ✓ | — |
| `trials` | ✓ | ✓ | — |
| `operators` | ✓ | ✓ | — |
| `retention` | ✓ | — | — |

- **Xulosada** ruxsatsiz bo'lim `null` bo'lib keladi va nomi `meta.hiddenSections[]` ga yoziladi. Bunda **403 qaytmaydi** — frontend kartani yashiradi.
- **Drill-down** ruxsatsiz bo'lsa 403 va `code: "dashboard.section.forbidden"` qaytadi.
- Boshqa rollar (TEACHER, SALES_MANAGER) uchun `/api/dashboard/director/**` → 403.

### 0.4 "Taxminiy" belgisi — qachon ko'rsatiladi
| Joy | Shart | UI |
|---|---|---|
| `collections.estimated`, `CollectionRow.estimated` | Davr billing migratsiyasidan kelgan (`coverageSource = MIGRATION_REPLAY`); `paidOn` eski to'lov sanasidan tiklangan | Raqam yonida "taxminiy" belgisi va tooltip: "Cutover'dan oldingi davrlar — eski ma'lumotdan tiklangan" |
| `trials.estimated`, `TrialRow.estimated` | Sinov sanalari tarixdan to'ldirilgan (`trial_source = BACKFILL`) | xuddi shunday |
| `operators.estimated`, `OperatorRow.estimated` | Tayinlash tarixi to'ldirilgan (`source = BACKFILL`) — faqat oxirgi tayinlash ma'lum | xuddi shunday |
| `debtors.source = "NONE"` | O'sha kun uchun snapshot yo'q — dashboard ishga tushmasidan oldingi kun | Kartada "ma'lumot yo'q"; qiymatlar `null` |

---

## 1. Xulosa — `GET /api/dashboard/director`

**Rollar:** SA, A, ACC. Bo'limlar yuqoridagi jadval bo'yicha.

| Qo'shimcha parametr | Default | Izoh |
|---|---|---|
| `operatorId` | — | `funnel` filtri: lidning hozirgi operatori |
| `teacherId` | — | `attendance` filtri |
| `includeImported` | `false` | Import qilingan (amoCRM) lidlarni ham sanash |

### 1.1 Javob — `DirectorSummary`
```jsonc
{
  "success": true,
  "data": {
    "meta": {
      "date": "2026-10-01", "period": "DAY", "from": "2026-10-01", "to": "2026-10-01",
      "asOf": "2026-10-01T20:00:00", "timezone": "Asia/Tashkent",
      "source": "LIVE", "hiddenSections": []
    },
    "funnel": {
      "activity": {
        "leadsCreated": 34, "contacted": 25, "visited": 17, "converted": 11, "rejected": 3,
        "firstPayments": { "total": 10, "fromLeads": 9, "walkIn": 1 }
      },
      "cohort": null            // period=DAY da null; WEEK/MONTH/CUSTOM da §1.2
    },
    "collections": {
      "due":       { "count": 28, "amount": 19600000 },
      "collected": { "count": 21, "amount": 14700000 },
      "onTime":    { "count": 18, "amount": 12600000 },
      "late":      { "count": 3,  "amount": 2100000 },
      "pending":   { "count": 5,  "amount": 3500000 },
      "unpaid":    { "count": 2,  "amount": 1400000 },
      "collectionRate": 75.0, "avgDelayDays": 0.6, "avgLateDelayDays": 4.3,
      "collectedOnDay": { "count": 9, "amount": 6300000 },
      "cashIn": 7350000, "estimated": false
    },
    "debtors": {
      "count": 7, "amount": 4900000, "overdue7Plus": 3, "newToday": 2, "clearedToday": 1,
      "closedDebt": 1400000, "source": "LIVE"
    },
    "attendance": { "planned": 42, "taken": 40, "takenOnTime": 38, "missing": 1, "unplanned": 0, "rate": 95.2, "upcomingToday": 1 },
    "trials": {
      "cohort": 6, "noShow": 1,
      "buckets": {
        "D0": { "count": 2, "percent": 33.3 }, "D1": { "count": 1, "percent": 16.7 },
        "D2_7": { "count": 0, "percent": 0.0 }, "D8_PLUS": { "count": 0, "percent": 0.0 },
        "IN_TRIAL": { "count": 3, "percent": 50.0 }, "NOT_PAID": { "count": 0, "percent": 0.0 }
      },
      "conversionRate": 100.0, "stayed30": null, "estimated": false
    },
    "operators": {
      "activeOperators": 4, "assigned": 31, "medianFirstResponseMin": 12, "noResponse": 3,
      "tasksDone": 47, "tasksOverdueOpen": 5,
      "top": [ /* OperatorRow, ko'pi bilan 5 ta — §2.9 */ ],
      "estimated": false
    },
    "headline": [
      "34 lid → 17 tashrif → 10 birinchi to'lov",
      "28 muddati kelgan → 21 undirildi (o'z vaqtida 18, kechikib 3), 5 kutilmoqda",
      "7 qarzdor, 4 900 000 so'm (bugun +2 / −1)",
      "42 darsdan 40 tasida davomat qilindi (1 ta yo'q)",
      "Sinov: 6 kelgan → 2 shu kuni to'ladi",
      "Operatorlar: javob medianasi 12 daq, javobsiz 3 lid, muddati o'tgan vazifa 5"
    ]
  }
}
```
`headline` — tayyor matn (Telegram xulosasi bilan bir xil raqamlar). Faqat ko'rinadigan bo'limlar uchun. UI uni "kun xulosasi" kartasida ko'rsatishi mumkin.

### 1.2 Maydonlar

**`funnel.activity`** — davr ichidagi mustaqil hodisalar. Bir xil odamlar **emas**. Masalan "34 lid · 17 tashrif" — tashrif qilgan 17 kishi kechagi lidlar bo'lishi mumkin.

| Maydon | Ma'nosi |
|---|---|
| `leadsCreated` | Davrda yaratilgan lidlar (import qilinganlarsiz) |
| `contacted` / `visited` / `converted` / `rejected` | Shu qadamga davrda **birinchi marta** yetgan lidlar |
| `firstPayments.total` | Birinchi PAID to'lovi (`cash > 0`) davrga tushgan o'quvchilar. Eski o'quvchilar (`FORMER_STUDENT`) sanalmaydi |
| `firstPayments.fromLeads` / `walkIn` | Lid orqali kelgan / lidsiz |

**`funnel.cohort`** (`period ≠ DAY`):
- Shakli: `{ size, steps: [{ step, reached, rate, medianDaysToStep }] }`.
- `step` qiymatlari: `CREATED`, `CONTACTED`, `VISITED`, `CONVERTED`, `FIRST_PAYMENT`.
- `size` — davrda yaratilgan lidlar soni.
- `reached` — ulardan hozirgacha shu qadamga yetganlar; `rate = reached / size`.
- `medianDaysToStep` — lid yaratilgandan qadamgacha kunlar medianasi (`CREATED` uchun `null`).

**`collections`** (faqat MONTHLY davrlar; muddat = billing kuni, grace 3 kun):

| Maydon | Ma'nosi |
|---|---|
| `due` | Muddati davrga tushgan davrlar (soni, Σ summa) |
| `collected` | Ulardan hozirgacha to'liq yopilganlari |
| `onTime` | Yopilgan, `paidOn ≤ dueDate + 3` (oldindan to'lov ham) |
| `late` | Yopilgan, grace dan keyin |
| `pending` | Hali yopilmagan, lekin grace ichida |
| `unpaid` | Yopilmagan, grace o'tgan |
| `collectionRate` | `collected / due` |
| `avgDelayDays` | Yopilganlar bo'yicha o'rtacha `max(0, paidOn − dueDate)` |
| `avgLateDelayDays` | Faqat `late` lar bo'yicha |
| `collectedOnDay` | **Boshqa savol:** davr ichida yopilgan davrlar (muddati qachon bo'lishidan qat'i nazar) — "bugun eski qarzdan qancha tushdi" |
| `cashIn` | Davrdagi PAID to'lovlarning kassaga tushgan Σ summasi |

**`debtors`** — billing-v2 ning yagona qarzdor ta'rifi (`/api/payments/debtors` bilan bir xil raqam):

| Maydon | Ma'nosi |
|---|---|
| `count` | Kamida bitta OVERDUE yozilmasi bor o'quvchilar |
| `amount` | Ularning Σ qarzi |
| `overdue7Plus` | 7+ kun kechikkanlar |
| `newToday` | Bugun OVERDUE ga o'tganlar |
| `clearedToday` | Kecha qarzdor edi, bugun emas (kechagi snapshot kerak, bo'lmasa `null`) |
| `closedDebt` | Yopilgan yozilmalar qarzi (ma'lumot uchun) |
| `source` | `LIVE` \| `SNAPSHOT` \| `NONE` |

O'tgan kun uchun faqat snapshot'dan olinadi; snapshot yo'q bo'lsa qiymatlar `null`.

**`attendance`:**
- **Rejadagi dars** — quyidagi shartlarning hammasi bajarilgan kun:
  - guruh ACTIVE;
  - kun jadvalda bor;
  - guruhda shu kuni faol (muzlatilmagan) o'quvchi bor;
  - kun bayram yoki bekor qilingan dars emas.
- Bir kunda bitta dars sanaladi.

| Maydon | Ma'nosi |
|---|---|
| `planned` | Rejadagi darslar |
| `taken` | Ulardan davomat olinganlari |
| `takenOnTime` | Davomat o'sha kun 23:59 gacha olinganlari (§7 #8) |
| `missing` | Soati o'tgan, davomat yo'q |
| `upcomingToday` | Bugungi, hali tugamagan darslar — `missing` ga kirmaydi |
| `unplanned` | Davomat bor, lekin rejada yo'q (jadval eskirgan bo'lishi mumkin) |
| `rate` | `taken / planned` |

**`trials`:**
- **Kogorta** — sinov darsiga haqiqatan **kelganlar** (birinchi PRESENT/LATE sanasi davrda).
- `k` = birinchi to'lov kuni − sinov kuni.

| Bucket | Shart |
|---|---|
| `D0` | Sinov kuni to'lagan |
| `D1` | Ertasi kuni |
| `D2_7` | 2–7 kun |
| `D8_PLUS` | 8+ kun |
| `IN_TRIAL` | Hali sinovda, ≤ 14 kun |
| `NOT_PAID` | 14 kun o'tdi yoki chiqib ketdi |

| Maydon | Ma'nosi |
|---|---|
| `noShow` | Davrda sinovga yozilgan, hali kelmagan |
| `conversionRate` | To'laganlar / (kogorta − IN_TRIAL) |
| `stayed30` | To'laganlardan 30 kundan keyin ham o'qiyotganlar %. 30 kun hali o'tmagan bo'lsa `null` |

**`operators`** — `top` da faol operatorlar (ADMIN, SALES_MANAGER) qatorlari tayinlangan lidlar soni bo'yicha kamayish tartibida; to'liq ro'yxat `/operators` da. `medianFirstResponseMin` — barcha operatorlar bo'yicha.

---

## 2. Drill-down (raqam bosilganda)
Hammasida §0.1 davr parametrlari ishlaydi.

**Sahifalash:** `page` (0 dan) va `size` (default 50, max 200). Javob shakli: `PageDto { items: [], page, size, total }`. `total` xulosadagi raqam bilan bir xil bo'ladi.

### 2.1 `GET /api/dashboard/director/funnel/leads` — SA, A
| Parametr | Qiymatlar |
|---|---|
| `step` (majburiy) | `CREATED`, `CONTACTED`, `VISITED`, `CONVERTED`, `FIRST_PAYMENT`, `REJECTED` |
| `mode` | `ACTIVITY` (default) \| `COHORT` |
| `operatorId`, `includeImported` | §1 dagidek |

Qator — `FunnelLeadRow`:
```json
{ "leadId": 812, "fullName": "Aziza Karimova", "phone": "+998901234567", "source": "INSTAGRAM",
  "createdAt": "2026-10-01T09:14:00", "stepAt": "2026-10-01T16:02:00", "currentStage": "VISITED_TRIAL",
  "assignedUserId": 5, "assignedUser": "Dilnoza Rahimova",
  "studentId": null, "firstPaymentDate": null, "firstPaymentAmount": null }
```
`FIRST_PAYMENT` da lidsiz (walk-in) o'quvchi qatorida `leadId = null` bo'ladi, `fullName` o'quvchiniki.

### 2.2 `GET /api/dashboard/director/collections/periods` — SA, ACC
`bucket` = `DUE` (default) \| `COLLECTED` \| `ON_TIME` \| `LATE` \| `PENDING` \| `UNPAID` \| `COLLECTED_ON_DAY`.

Qator — `CollectionRow`:
```json
{ "periodId": 4410, "studentGroupId": 233, "studentId": 190, "studentName": "Ali Valiyev", "phone": "+998901112233",
  "groupName": "Ingliz B1-3", "periodStart": "2026-09-15", "periodEnd": "2026-10-14",
  "dueDate": "2026-09-15", "graceUntil": "2026-09-18", "amountDue": 630000,
  "paidOn": "2026-09-20", "paidAt": "2026-09-20T15:41:00", "delayDays": 5,
  "bucket": "LATE", "coverageSource": "FIFO", "estimated": false }
```
- `paidOn` — pul kelgan kun. To'lov orqaga sanalab kiritilgan bo'lsa, shu `payment_date`.
- `paidAt` — to'lov tizimga kiritilgan payt.
- `delayDays` — manfiy bo'lishi mumkin (oldindan to'lov).

### 2.3 `GET /api/dashboard/director/debtors` — SA, ACC
`scope` = `ACTIVE` (default) \| `ALL`; `minDays`; `page`; `size`.

Javob — billing-v2 `DebtorsListResponse`, `GET /api/payments/debtors` bilan aynan bir xil shakl: `{ totalDebtors, overdue7Plus, totalDebt, scope, page, size, students: [...] }`.

### 2.4 `GET /api/dashboard/director/attendance/lessons` — SA, A
- `status` = `PLANNED` (default — rejadagi hammasi) \| `TAKEN` \| `LATE_MARKED` \| `MISSING` \| `UPCOMING` \| `UNPLANNED`.
- Filtrlar: `teacherId`, `groupId`.

Qator — `LessonRow`:
```json
{ "groupId": 17, "groupName": "Matematika-3", "teacherId": 4, "teacherName": "Sardor Aliyev",
  "date": "2026-10-01", "startTime": "14:00", "endTime": "15:30", "activeStudents": 12,
  "markedCount": 0, "firstMarkedAt": null, "status": "MISSING" }
```

### 2.5 `GET /api/dashboard/director/trials/enrollments` — SA, A
`bucket` (majburiy) = `D0` \| `D1` \| `D2_7` \| `D8_PLUS` \| `IN_TRIAL` \| `NOT_PAID` \| `NO_SHOW`.

Qator — `TrialRow`:
```json
{ "studentGroupId": 301, "studentId": 255, "studentName": "Madina Yusupova", "phone": "+998935551122",
  "groupName": "Ingliz A1-2", "trialStartedAt": "2026-10-01", "convertedDay": "2026-10-02", "days": 1,
  "bucket": "D1", "outcome": "CONVERTED", "leadId": 812, "estimated": false }
```

### 2.6 `GET /api/dashboard/director/retention` — faqat SA
- Parametrlar: `cohortFrom`, `cohortTo` (`YYYY-MM`, ko'pi bilan 24 oy).
- Default — oxirgi 6 oy.

Javob — `RetentionCohort[]`:
```json
[ { "month": "2026-08", "size": 2, "retained": [100.0, 50.0, 50.0] },
  { "month": "2026-09", "size": 1, "retained": [100.0, 100.0] } ]
```
- `retained[k]` — kogortaning (oy + k) oxirida o'qiyotgan ulushi. Joriy oy uchun bugungi holat olinadi.
- Kelajak oylari yo'q, shuning uchun massiv uzunligi turlicha.

### 2.7 `GET /api/dashboard/director/retention/exits` — faqat SA
- Filtrlar: `reasonCode`, `kind` = `CHURN` \| `PARTIAL` \| `GRADUATED` \| `PAUSE` \| `TRANSFER`.

Qator — `ExitRow`:
```json
{ "studentId": 190, "studentName": "Ali Valiyev", "studentGroupId": 233, "groupName": "Ingliz B1-3",
  "exitDate": "2026-10-10", "reasonCode": "PRICE", "notes": "Qimmat", "monthsStudied": 1, "kind": "CHURN" }
```

| `kind` | Ma'nosi |
|---|---|
| `CHURN` | O'quvchi butunlay ketdi: 30 kun ichida boshqa ochiq yozilmasi yo'q |
| `PARTIAL` | Bir guruhdan chiqdi, boshqasida o'qiyapti |
| `PAUSE` | Muzlatish (avto-arxiv ham) |

### 2.8 `GET /api/dashboard/director/operators` — SA, A
Javob — `OperatorRow[]` (to'liq ro'yxat, tayinlangan lidlar soni bo'yicha kamayish tartibida).

### 2.9 `OperatorRow`
```json
{ "userId": 5, "fullName": "Dilnoza Rahimova", "role": "SALES_MANAGER", "assigned": 12,
  "firstResponse": { "medianMinutes": 9, "p90Minutes": 41, "within15m": 58.3, "within1h": 91.7 },
  "noResponse": 1,
  "tasks": { "done": 14, "doneLate": 2, "overdueOpen": 3 },
  "converted": 4, "conversionRate": 33.3, "firstPayments": 3,
  "paymentsReceived": { "count": 6, "amount": 4200000 },
  "activity": { "statusChanges": 37, "comments": 21, "lastSeenAt": "2026-10-01T08:52:10" },
  "estimated": false }
```

| Maydon | Ma'nosi |
|---|---|
| `assigned` | Davrda shu operatorga tayinlangan lidlar. Qayta tayinlash ham sanaladi; importlar sanalmaydi |
| `firstResponse` | Tayinlangandan operatorning birinchi harakatigacha, ish daqiqalarida. Harakat: bosqich o'zgarishi, bajarilgan vazifa yoki izoh |
| `noResponse` | 24 ish soatida javob yo'q |
| `converted` / `conversionRate` | Tayinlanganlardan hozirgacha konvert bo'lganlar |
| `firstPayments` | `attributed_user_id` shu operator bo'lgan o'quvchilarning birinchi to'lovi |

### 2.10 `GET /api/dashboard/director/operators/{userId}/leads` — SA, A
`metric` = `ASSIGNED` (default) \| `NO_RESPONSE` \| `SLOW_RESPONSE` (> 60 ish daqiqasi) \| `CONVERTED`.

Qator — `OperatorLeadRow`: `{ leadId, fullName, phone, assignedAt, firstResponseAt, responseMinutes, firstResponseKind (STATUS|TASK|COMMENT), currentStage, convertedAt, metric }`.

### 2.11 `GET /api/dashboard/director/operators/{userId}/tasks` — SA, A
`state` = `DONE` (default) \| `DONE_LATE` \| `OVERDUE_OPEN`.

Qator — `OperatorTaskRow`: `{ taskId, title, type, leadId, studentId, dueAt, completedAt, result, state }`.

---

## 3. Trend — `GET /api/dashboard/director/trend`
Kunlik snapshot'lardan sparkline uchun.

| Parametr | Izoh |
|---|---|
| `metric` (majburiy) | `<bo'lim>.<maydon yo'li>`, masalan `collections.due.count`, `funnel.activity.leadsCreated`, `attendance.rate`, `debtors.count` |
| `from`, `to` (majburiy) | Ko'pi bilan 92 kun |

Bo'lim roli tekshiriladi.

```json
[ { "date": "2026-09-30", "value": 26, "finalized": true },
  { "date": "2026-10-01", "value": 28, "finalized": false } ]
```
- Snapshot yo'q kun ro'yxatga kirmaydi — UI bo'shliq qoldiradi.
- `finalized: false` — kun hali yopilmagan (20:00 holati).

---

## 4. Sozlamalar

### 4.1 Lid bosqichi voronka qadami
- `PUT /api/lead-stages/{id}` (SA, A) — mavjud endpoint, yangi maydon `funnelStep`: `NONE` \| `CONTACTED` \| `VISITED`. `null` yuborilsa o'zgarmaydi.
- `GET /api/lead-stages` javobida ham `funnelStep` bor.
- Default seed: yangi bosqich **"Sinovga keldi"** (`VISITED_TRIAL`) va *_PAID → `VISITED`; CONTACTED → `CONTACTED`; qolganlari → `NONE`.
- Qadam o'zgarsa, mavjud lidlarning sanalari qayta hisoblanmaydi.

### 4.2 Dam olish kunlari — SA, A
| So'rov | Body / parametr | Javob |
|---|---|---|
| `GET /api/holidays` | `from`, `to` (default — joriy yil) | `[{ holidayDate, name, createdBy, createdAt }]` |
| `POST /api/holidays` | `{ "date": "2026-10-05", "name": "Ustozlar kuni" }` | 201; mavjud → 409 `holiday.exists` |
| `DELETE /api/holidays/{date}` | — | 200; yo'q → 404 `holiday.notFound` |

### 4.3 Dars istisnolari — SA, A, guruh o'qituvchisi
| So'rov | Body | Javob |
|---|---|---|
| `GET /api/groups/{groupId}/lesson-exceptions` | — | `[{ id, groupId, lessonDate, kind, movedTo, reason, createdBy, createdAt }]` |
| `POST /api/groups/{groupId}/lesson-exceptions` | `{ "lessonDate": "2026-10-07", "kind": "CANCELLED" \| "MOVED" \| "EXTRA", "movedTo": "2026-10-08", "reason": "..." }` | 201. MOVED da `movedTo` majburiy (400 `lessonException.movedToRequired`); takror → 409 `lessonException.exists` |
| `DELETE /api/groups/{groupId}/lesson-exceptions/{id}` | — | 200 |

- O'qituvchi faqat o'z guruhi uchun istisno kirita oladi (boshqasiga 403).
- Istisnolar rejadagi darslarga va PER_LESSON keyingi to'lov sanasiga ta'sir qiladi.

### 4.4 Guruhdan chiqarish sababi
- `POST /api/groups/{groupId}/remove-student` — body'ga yangi maydon `reasonCode`. Qiymatlari: `PRICE`, `SCHEDULE`, `MOVED_AWAY`, `QUALITY`, `HEALTH`, `NO_TIME`, `GRADUATED`, `TRANSFERRED`, `OTHER`.
- Ixtiyoriy: berilmasa eski `reason` matnidan olinadi (noma'lum → `OTHER`).
- Yangi UI'da tanlov majburiy qilib ko'rsatilsin, `notes` — izoh.

---

## 5. Kunlik Telegram xulosasi (faqat backend)
- Soat 20:00 (Asia/Tashkent) da direktor chat'iga dashboard `headline` bilan bir xil raqamlar yuboriladi. Ism va telefon yuborilmaydi.
- Default o'chiq. Yoqish uchun env o'zgaruvchilari:
  - `DIRECTOR_DIGEST_ENABLED=true`;
  - `DIRECTOR_DIGEST_CHAT_IDS=<id1>,<id2>`;
  - `DIRECTOR_DASHBOARD_URL=https://…` — xabardagi havola.
- Bir kunga bir chat'ga bitta xabar ketadi; yuborilmagan bo'lsa keyingi urinishda qayta yuboriladi.
- Frontendga ta'siri yo'q.

## 6. Kunlik snapshot (frontendga ta'siri)
| Vaqt | Nima yoziladi |
|---|---|
| 20:00 | `finalized: false` snapshot (Telegram yoqilgan bo'lsa) |
| 23:55 | Kun yakuni — `finalized: true` |
| Har kecha | Oxirgi 7 kun qayta hisoblanadi: kechikib kiritilgan to'lov yoki davomat raqamni o'zgartirishi mumkin. Qarzdorlar soni bundan mustasno — o'z kunida muzlatiladi |

## 7. Admin — `POST /api/admin/dashboard/backfill` (faqat SA)
Bir martalik tarixni to'ldirish (V53 dan keyin):
- Default **DRY-RUN** — hech narsa yozmaydi.
- Qo'llash: `?dryRun=false&confirm=BACKFILL-APPLY`.
- Faqat bo'sh maydonlar to'ldiriladi, shuning uchun qayta ishga tushirish xavfsiz.

```json
{ "dryRun": true, "generatedAt": "2026-10-02T10:00:00",
  "items": [ { "code": "G2", "title": "Lid voronka sanalari …", "candidates": 1840, "changes": 1532, "sample": ["lead#12 contacted=…"] },
             { "code": "G3/G4", "…": "…" }, { "code": "G5" }, { "code": "G6" }, { "code": "G10" } ],
  "notes": [ "Faqat bo'sh maydonlar to'ldiriladi — qayta ishga tushirish xavfsiz.", "…" ] }
```

## 8. Joriy etish tartibi (backend)
1. `V53__director_dashboard.sql` ni qo'lda bajaring (V52 dan keyin, jar deploy qilinishidan oldin).
2. Jar deploy qilinadi.
3. `POST /api/admin/dashboard/backfill` — avval dry-run, hisobotni ko'rib chiqing, keyin `confirm=BACKFILL-APPLY`.
4. Bayramlarni kiriting (`/api/holidays`).
5. Kerak bo'lsa Telegram env'larini yoqing.

## 9. Xato kodlari
| `code` | HTTP | Qachon |
|---|---|---|
| `dashboard.period.invalid` | 400 | Noto'g'ri `period`, CUSTOM da `from`/`to` yo'q yoki > 366 kun, trend > 92 kun |
| `dashboard.param.invalid` | 400 | Noto'g'ri `step`, `bucket`, `status`, `metric`, `state`, oy formati |
| `dashboard.section.forbidden` | 403 | Drill-down bo'limi rolga ochiq emas |
| `holiday.invalid` / `holiday.exists` / `holiday.notFound` | 400 / 409 / 404 | Bayram CRUD |
| `lessonException.invalid` / `.movedToRequired` / `.exists` / `.notFound` | 400 / 400 / 409 / 404 | Dars istisnosi CRUD |
| `migration.confirmRequired` | 400 | Backfill qo'llashda `confirm` yo'q |

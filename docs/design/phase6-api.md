# Phase 6 — "Tez orada" sahifalari uchun API (frontend uchun)

> 5 ta sahifa: **Analitika: umumiy ko'rinish**, **Analitika: xodimlar**, **Chat**, **Uy vazifalari**, **Guruhga ko'chirish**.
> Bu hujjat — **amalda qurilgan** holat (branch `billing-v2`, migratsiya **V65**). Frontend faqat shu shartnoma bo'yicha ishlaydi.
> Javoblar `ApiResponse {success, message, data}` ichida; xatolar `ErrorResponse {status, error, message, code, data?}`.
> `message` `Accept-Language` (uz/ru/en) bo'yicha; mantiq uchun **`code`** ga tayaning (chat bundan istisno — §3).

## 0. Umumiy

| Mavzu | Qoida |
|---|---|
| Rollar | SA = SUPER_ADMIN, A = ADMIN, SH = SALES_HEAD, SM = SALES_MANAGER, ACC = ACCOUNTANT, T = TEACHER. Jadvaldagidan boshqa rol → **403**. |
| Sana | `LocalDate` — `"2026-09-15"`, Asia/Tashkent. Davr chegaralari **ikkalasi kiritilgan** (`from ≤ d ≤ to`). |
| Summalar | UZS, butun so'm. JSON da son (`1200000` yoki `1200000.00`). |
| Foiz | 1 kasr (`12.5`); maxraj 0 bo'lsa `null` ("—" ko'rsating). |
| Sahifa | `PageResponse<T>` = `{content, pageNumber, pageSize, totalElements, totalPages, last}`. |
| Qulf band | Parallel amal 5 s dan ko'p kutsa → 409 `concurrency.busy`. |
| Billing o'chiq | Ko'chirish (§5) billing yozuvi qiladi: `app.billing.enabled=false` paytida → **503** `billing.maintenance`. |

---

## 1. Analitika: umumiy ko'rinish — `GET /api/analytics/overview` (SA, A)

| Query | Turi | Standart | Qoida |
|---|---|---|---|
| `from` | date | joriy oy 1-kuni | |
| `to` | date | bugun | `from ≤ to`, oraliq ≤ **366** kun → aks holda 400 `analytics.period.invalid` |
| `groupBy` | `DAY` \| `WEEK` \| `MONTH` | ≤ 31 kun — `DAY`, ≤ 120 — `WEEK`, aks holda `MONTH` | noma'lum → 400 `analytics.groupBy.invalid`; `DAY` > 92 kun → 400 `analytics.groupBy.tooFine` |

**Oldingi teng davr** — xuddi shu uzunlik, `from` dan bevosita oldin (`previousFrom … previousTo`).
**Bucket** — `period.buckets[i]` — i-bo'lak boshlanishi (DAY — har kun; WEEK — ISO hafta, birinchi bo'lak `from` dan; MONTH — kalendar oyi).
Har bir `series` massivi `buckets` bilan **bir xil uzunlikda**.

```json
{
  "period": { "from": "2026-09-01", "to": "2026-09-14", "groupBy": "WEEK",
              "previousFrom": "2026-08-18", "previousTo": "2026-08-31",
              "buckets": ["2026-09-01", "2026-09-07", "2026-09-14"] },
  "finance": {
    "income":      { "value": 1200000, "previous": 300000, "changePercent": 300.0, "series": [500000, 700000, 0] },
    "incomeByMethod": { "CARD": 500000, "CASH": 700000 },
    "expenses":    { "value": 200000, "previous": 0, "changePercent": null, "series": [200000, 0, 0] },
    "payrollPaid": { "value": 0, "previous": 0, "changePercent": null, "series": [0, 0, 0] },
    "examFees":    { "value": 0, "previous": 0, "changePercent": null, "series": [0, 0, 0] },
    "net":         { "value": 1000000, "previous": 300000, "changePercent": 233.3, "series": [300000, 700000, 0] }
  },
  "students": {
    "newStudents": { "value": 1, "previous": 1, "changePercent": 0.0, "series": [0, 1, 0] },
    "exits":       { "value": 1, "previous": 0, "changePercent": null, "series": [0, 1, 0] },
    "exitsByReason": { "PRICE": 1 },
    "activeAtEnd": { "value": 2, "previous": 2, "changePercent": 0.0, "series": [2, 2, 2] }
  },
  "leads": {
    "created":   { "value": 2, "previous": 1, "changePercent": 100.0, "series": [2, 0, 0] },
    "converted": { "value": 1, "previous": 0, "changePercent": null, "series": [1, 0, 0] },
    "conversionRate": 50.0, "previousConversionRate": 0.0,
    "bySource": [ { "source": "INSTAGRAM", "leads": 1, "converted": 1, "conversionRate": 100.0, "firstPayments": 0 },
                  { "source": "WEBSITE",   "leads": 1, "converted": 0, "conversionRate": 0.0,   "firstPayments": 0 } ]
  },
  "groups": {
    "activeGroups": 2, "averageFillPercent": 1.0,
    "groups":   [ { "groupId": 4, "groupName": "IELTS 2", "courseName": "IELTS", "teacherName": "Ali Valiyev",
                    "students": 2, "maxStudents": 100, "fillPercent": 2.0 } ],
    "byCourse": [ { "courseId": 1, "courseName": "IELTS", "groups": 1, "students": 2 } ]
  }
}
```

### 1.1 Ko'rsatkichlar ta'rifi (direktor dashboardi va moliya hisoboti bilan bir xil manba)

| Ko'rsatkich | Ta'rif | Manba |
|---|---|---|
| `finance.income` | Kassaga tushgan real pul: `PAID` to'lovlar, `COALESCE(cashAmount, amount)`, `paymentDate` davrda. Bekor qilingan kirmaydi | `GET /api/finance/report` → `totalIncome` (aynan bir xil raqam) |
| `incomeByMethod` | `income` ning to'lov usuli (`PaymentMethod`) bo'yicha bo'linishi; yig'indisi = `income.value` | |
| `expenses` | `expenses` jadvali, `expenseDate` davrda | `totalExpenses` |
| `payrollPaid` | `PAID` oyliklar, `paidAt` davrda | `payrollPaid` |
| `examFees` | Imtihon to'lovlari (kirim − REVERSAL) | `examFees` |
| `net` | `income + examFees − expenses − payrollPaid` | `netProfit` |
| `students.newStudents` | **Birinchi real to'lovi** davrga tushgan o'quvchilar (sinov, sobiq o'quvchi — yo'q) | direktor dashboardi "first payments" |
| `students.exits` | Davrda guruhdan **butunlay** ketgan (CHURN) o'quvchilar — ko'chish, muzlatish, bitirish va boshqa guruhda davom etish kirmaydi. Qator — o'quvchining birinchi chiqish kuni | `GET /api/dashboard/director/retention/exits?kind=CHURN` |
| `exitsByReason` | CHURN yozilmalari `exitReasonCode` bo'yicha (`PRICE`, `SCHEDULE`, `MOVED_AWAY`, `QUALITY`, `HEALTH`, `NO_TIME`, `OTHER`) | |
| `students.activeAtEnd` | Kun oxirida o'qiyotganlar: yozilgan, chiqmagan, muzlatilmagan (sinov ham). `value` — `to` kuni, `previous` — `previousTo`, `series[i]` — bo'lak oxiri | |
| `leads.created` | Davrda **yaratilgan** lidlar (import qilinganlarsiz) | dashboard funnel kogortasi |
| `leads.converted` | Shu lidlardan o'quvchiga aylanganlar (hozirgacha); qator — lid yaratilgan bo'lak | |
| `bySource` | Kogorta `source` bo'yicha; `firstPayments` — birinchi to'lovgacha yetganlar | |
| `groups.*` | **Hozirgi** `ACTIVE` guruhlar, `to` kunidagi o'quvchilar soni / `maxStudents`. `averageFillPercent` — sig'imi bor guruhlar o'rtachasi. Tarixiy emas | |

`changePercent = (value − previous) / |previous| × 100`, 1 kasr; `previous = 0` → `null`.

### 1.2 Deprecated (eski frontend uchun qoldirilgan, yangi panel ISHLATMAYDI)
`GET /api/analytics/dashboard`, `/revenue`, `/students`, `/marketing/sources` (`/marketing-sources`), `/staff/summary`,
`/staff/{userId}/trend`, `/staff` (**`role` siz**). Sabab: sanasiz (butun davr) yoki dashboarddan boshqa ta'rif bilan hisoblaydi —
raqamlar §1/§2 bilan mos kelmaydi. Eski loyiha o'chirilgach olib tashlanadi.

---

## 2. Analitika: xodimlar samaradorligi — `GET /api/analytics/staff?role=` (SA, A; SH — faqat `SALES`)

| Query | Qoida |
|---|---|
| `role` | **Majburiy**: `TEACHER` \| `SALES` (SM + SH) \| `ADMIN`. Noma'lum → 400 `analytics.staff.roleInvalid`. SH boshqa rol so'rasa (yoki `role` siz) → 403 `analytics.staff.roleForbidden` |
| `from`, `to` | §1 dagidek (standart — joriy oy boshidan bugungacha, ≤ 366 kun) |

```json
{ "role": "SALES", "from": "2026-09-01", "to": "2026-09-14",
  "rows": [ { "rank": 1, "userId": 15, "fullName": "Dilnoza Karimova", "role": "SALES_MANAGER",
              "sales": { "assigned": 12, "converted": 4, "conversionRate": 33.3,
                         "firstResponse": { "medianMinutes": 14, "p90Minutes": 95, "within15m": 58.3, "within1h": 83.3 },
                         "noResponse": 1, "tasks": { "done": 20, "doneLate": 3, "overdueOpen": 2 }, "firstPayments": 3 },
              "links": { "leads": "/api/dashboard/director/operators/15/leads?metric=ASSIGNED&period=CUSTOM&from=2026-09-01&to=2026-09-14",
                         "noResponse": "…metric=NO_RESPONSE…", "overdueTasks": "/api/dashboard/director/operators/15/tasks?state=OVERDUE_OPEN&…" } } ] }
```

Qatorda faqat `role` ga mos bitta obyekt bor (`teacher` | `sales` | `admin`), qolganlari JSON da yo'q. `rank` — 1 dan, tartib quyida.

| `role` | Qator | Obyekt | Tartib (reyting) | `links` |
|---|---|---|---|---|
| `TEACHER` | INACTIVE bo'lmagan barcha o'qituvchilar (`teacherId`; login bog'langan bo'lsa `userId`) | `teacher: { kpi: {attendanceRate, paymentRate, onTimePaymentRate, retentionRate, overallScore, insufficientData}, activeStudents, attendance: {planned, taken, takenOnTime, missing, rate}, exits, exitsByReason }` | `kpi.overallScore` ↓ (yetarli ma'lumot yo'q — oxirida), `activeStudents` ↓, ism | `kpi` → `GET /api/teachers/{id}/kpi?from&to`; `missingLessons` → davomat qilinmagan darslar; `exits` → ketganlar |
| `SALES` | Faol SM va SH (va davrda lid olgan nofaollar) | `sales: { assigned, converted, conversionRate, firstResponse, noResponse, tasks, firstPayments }` | `converted` ↓, `firstPayments` ↓, `conversionRate` ↓, ism | `leads`, `noResponse`, `overdueTasks` |
| `ADMIN` | Faol ADMIN lar | `admin: { newStudents, paymentsReceived: {count, amount} }` | `newStudents` ↓, `paymentsReceived.amount` ↓, ism | `leads` |

- **O'qituvchi KPI** — `GET /api/teachers/kpi/ranking` dagi 4 ko'rsatkich (davomat %, to'lov %, o'z vaqtida to'lov %, qolish %) va ularning o'rtachasi.
  `activeStudents` — `to` kuni guruhlarida o'qiyotganlar. `attendance` — direktor dashboardi davomat intizomi (rejadagi / belgilangan /
  o'z vaqtida / belgilanmagan darslar). `exits` — o'qituvchi guruhlaridan CHURN.
- **Sotuv va admin** — direktor dashboardi "operatorlar" jadvali (`GET /api/dashboard/director/operators`) bilan **aynan bir xil raqamlar**:
  `assigned` — davrda tayinlangan lidlar, `firstResponse` — javob tezligi (ish soatlarida), `tasks` — bajarilgan / kech / muddati o'tgan ochiq,
  `firstPayments` / `newStudents` — shu xodimga attributsiya qilingan birinchi real to'lovlar, `paymentsReceived` — xodim qabul qilgan `PAID` to'lovlar.
- `links` — drill-down uchun tayyor nisbiy URL (API bazasiga qo'shing). Ular direktor dashboardi endpointlari: SH faqat `operators/*` ni ocha oladi.

---

## 3. Chat — `/api/chat` (REST) + STOMP `/ws` (barcha xodimlar)

> Bu hujjat — **amalda qurilgan** holat (branch `billing-v2`, migratsiyalar V45–V48). Kodda o'zgarish yo'q, faqat tavsif.
> REST javoblari `ApiResponse {success, message, data}` ichida. Xatolar **ikki xil shaklda** keladi (§3.8.1) va chat xatolarida
> mashina o'qiydigan **`code` yo'q** — faqat tarjima qilingan `message` (`Accept-Language` uz/ru/en). Frontend mantiqini HTTP
> statusga quring, matnni esa foydalanuvchiga ko'rsating.
> Jonli qism — STOMP over WebSocket (§3.6). REST faqat sahifa ochilganda, qayta ulanganda va fayl yuklashda kerak.

### 3.0 Umumiy

| Mavzu | Qoida |
|---|---|
| Rollar | SA = SUPER_ADMIN, A = ADMIN, SH = SALES_HEAD, SALES = SALES_MANAGER, ACC = ACCOUNTANT, T = TEACHER. **STAFF** = shu oltitasi. STUDENT/PARENT → REST **403**, WebSocket handshake **403**. |
| Rol bo'yicha farq | Chatda yo'q — hamma STAFF bir xil. Yagona istisno: **o'zganing xabarini o'chirish** — faqat SA, A (§3.6.5). |
| Kirish huquqi | Suhbat bilan ishlaydigan har bir amal — faqat suhbatning **faol a'zosi** (`left_at IS NULL`). Aks holda **403** `chat.notParticipant` (404 emas — begona `id` mavjudligi oshkor qilinmaydi). |
| Sana / vaqt | `LocalDateTime`, zonasiz, Toshkent vaqti: `"2026-10-03T14:05:12.345678"` (kasr soniya bo'lishi mumkin — parser buni qabul qilsin). |
| ID lar | `id` — `Long`. Xabarlarda tartib va kursor **`id` bo'yicha** (monoton o'sadi), `createdAt` bo'yicha emas. |
| Sahifalash | `PageResponse` **yo'q** — xabarlar lentasi kursorli oddiy massiv (`before` / `around`, §3.2.2). |
| Rate limit | Yo'q (na REST, na STOMP). |



### 3.1 Tushunchalar

#### 3.1.1 Suhbat turlari (`ConversationType`)

| `type` | Tavsif | `title` | `photoUrl` | `participants` |
|---|---|---|---|---|
| `DIRECT` | Ikki kishilik. Juftlik uchun **bitta** suhbat (UNIQUE `direct_key = "minId:maxId"`). | Suhbatdosh ismi (server hisoblaydi) | Suhbatdosh rasmi | Faqat suhbatdosh (1 ta) |
| `GROUP` | Yaratuvchi + kamida 1 a'zo (ya'ni **2 kishilik guruh ham mumkin**). | Guruh nomi | `null` | Barcha faol a'zolar, **o'zingiz ham** |

- Yaratish: `POST /conversations/direct` (bor bo'lsa mavjudi qaytadi), `POST /conversations/group` (§3.2.6–2.7).
- **A'zo qo'shish / chiqarish / guruhdan chiqish / nomini o'zgartirish / suhbatni o'chirish — endpoint YO'Q.** Guruh tarkibi
  yaratilganda qotadi. (`left_at` ustuni bor, lekin uni yozadigan kod yo'q.)
- `isMuted` — faqat o'qiladi, uni o'zgartiradigan endpoint yo'q (doim `false`), o'qilmaganlar soniga ta'sir qilmaydi.
- `isPinned` — shaxsiy (faqat sizning ro'yxatingizga ta'sir qiladi), `PATCH …/pin`.

#### 3.1.2 Xabar turlari (`MessageType`)

| `messageType` | Qachon | Tahrirlash |
|---|---|---|
| `TEXT` | Biriktirmasiz | Ha (15 daqiqa ichida, faqat muallif) |
| `IMAGE` | Barcha biriktirmalar `image/*` | Yo'q |
| `FILE` | Kamida bittasi rasm emas (va ovoz emas) | Yo'q |
| `VOICE` | Biriktirma `audio/*` — doim **bitta**, boshqa fayl bilan aralashmaydi | Yo'q |
| `SYSTEM` | Enumda bor, lekin hozir hech kim yaratmaydi | — |

Tur **mijozdan so'ralmaydi** — server biriktirmalar `contentType` idan aniqlaydi. Biriktirmali xabarda `text` ixtiyoriy (izoh).

#### 3.1.3 O'qilganlik

- Har bir a'zoning **kursori** bor: `lastReadMessageId` (shu `id` gacha, o'zi ham, o'qilgan). `null` — hech narsa o'qilmagan.
- Kursor faqat **oldinga** suriladi. Eski `messageId` yuborilsa hech narsa o'zgarmaydi va hodisa tarqatilmaydi.
- Xabar yuborganda yuboruvchining kursori avtomatik o'sha xabarga suriladi (READ hodisasi **chiqmaydi**).
- O'qilmaganlar = `id > lastReadMessageId`, **o'z xabarlaringiz va o'chirilganlar sanalmaydi**, `isMuted` ta'sir qilmaydi.
- DIRECT da ✓✓: `ConversationResponse.peerLastReadMessageId` (REST) + jonli `READ` hodisasi. GROUP da REST orqali a'zolar
  kursori **berilmaydi** — faqat jonli `READ` hodisalari (`userId` bilan).

#### 3.1.4 Tahrirlash va o'chirish

- **Tahrir**: faqat muallif, faqat `TEXT`, faqat `createdAt + 15 daqiqa` ichida, o'chirilmagan xabar, bo'sh bo'lmagan matn.
- **O'chirish** — yumshoq (`deletedAt`). Muallif yoki SA/A (lekin baribir suhbat a'zosi bo'lishi kerak). Xabar lentada qoladi:
  `text = null`, `attachments = null`, `deletedAt` to'ldirilgan → "Xabar o'chirildi" chizing. Fayl diskdan o'chmaydi.
  Ikkinchi marta o'chirish — xato emas, shunchaki hodisa chiqmaydi.
- O'chirilgan xabarga qilingan javobda `replyTo.text = null` (kim yozgani qoladi).
- Suhbatlar ro'yxatidagi "oxirgi xabar" o'chirilganlarni hisobga olmaydi (oldingi o'chirilmagan xabar ko'rinadi).



### 3.2 REST — `/api/chat`

Barcha endpointlar: **STAFF** (`SecurityConfig`: `/api/chat/** → hasAnyRole(STAFF_ROLES)`), JWT `Authorization: Bearer …`.

| Metod, yo'l | Tavsif | Javob `data` |
|---|---|---|
| `GET /api/chat/conversations` | Mening suhbatlarim | `ConversationResponse[]` |
| `GET /api/chat/conversations/{id}/messages` | Lenta, yangi → eski. Query: `before`, `around`, `size` | `ChatMessageResponse[]` |
| `GET /api/chat/conversations/{id}/messages/search?q=` | Bitta suhbat ichida qidiruv | `ChatMessageResponse[]` |
| `GET /api/chat/search?q=` | Umumiy qidiruv (suhbat nomlari + xabar matnlari) | `ChatSearchResponse` |
| `GET /api/chat/presence` | Suhbatdoshlarim onlayn holati (dastlabki surat) | `PresenceResponse[]` |
| `POST /api/chat/conversations/direct` | DIRECT ochish (bor bo'lsa mavjudi) → **200** | `ConversationResponse` |
| `POST /api/chat/conversations/group` | Guruh yaratish → **201**, `message: "Guruh yaratildi"` | `ConversationResponse` |
| `PATCH /api/chat/conversations/{id}/pin` | Qadash / yechish | `ConversationResponse` |
| `GET /api/chat/users` | Kim bilan yozishish mumkin (faol foydalanuvchilar, o'zimdan tashqari) | `ChatUserResponse[]` |
| `GET /api/chat/unread-count` | Umumiy o'qilmaganlar (sidebar belgisi) | `{ "count": 7 }` |
| `POST /api/chat/upload` | Biriktirma yuklash (multipart), `message: "Fayl yuklandi"` | `ChatUploadResponse` |

`{id}` faqat raqam (`\d+`); boshqa narsa → 404 (`error.endpointNotFound`). Query parametrida raqam o'rniga matn → 400 `error.typeMismatch`.

#### 3.2.1 `GET /conversations`

Tartib: avval qadalganlar (`isPinned DESC`), keyin `conversations.last_message_at DESC` (yangi yuborilgan xabar suhbatni tepaga
chiqaradi; yangi yaratilgan suhbat ham tepada), keyin `id DESC`. Sahifalash yo'q — hammasi bitta massivda.

```json
{
  "success": true, "message": null,
  "data": [
    {
      "id": 42, "type": "DIRECT",
      "title": "Dilnoza Karimova", "photoUrl": "/api/files/7f1c….jpg",
      "participants": [
        { "id": 9, "fullName": "Dilnoza Karimova", "username": "dilnoza", "role": "ACCOUNTANT",
          "photoUrl": "/api/files/7f1c….jpg", "online": true, "lastSeenAt": "2026-10-02T18:40:00" }
      ],
      "lastMessageText": null,
      "lastMessageAt": "2026-10-03T09:12:44.120391",
      "lastMessageSenderId": 9,
      "lastMessageType": "VOICE",
      "unreadCount": 2,
      "lastReadMessageId": 1031,
      "peerLastReadMessageId": 1035,
      "isPinned": false, "isMuted": false
    }
  ]
}
```

- `lastMessageText` biriktirmali xabarda `null` bo'lishi mumkin → `lastMessageType` ga qarab "Rasm" / "Fayl" / "Ovozli xabar" yozing.
- Hali xabar yo'q suhbatda `lastMessage*` = `null`, `lastMessageAt` = suhbat yaratilgan payt.
- `peerLastReadMessageId` — faqat DIRECT, GROUP da `null`.

#### 3.2.2 `GET /conversations/{id}/messages`

| Query | Turi | Ma'nosi |
|---|---|---|
| `before` | Long, ixtiyoriy | Oldingi sahifadagi **eng eski** xabar `id` si; shundan eskilar qaytadi. 1-sahifada berilmaydi. |
| `around` | Long, ixtiyoriy | Qidiruvdan sakrash: shu xabar + ikki tomonidagi kontekst. Xabar bu suhbatda bo'lmasa → 400 `chat.message.notFound`. |
| `size` | Integer, ixtiyoriy | Standart **50**, maksimal **100**; `≤ 0` yoki yo'q → 50; `> 100` → 100 (xato emas). |

- `before` va `around` birga → 400 `chat.cursor.conflict`.
- Javob doim **yangi → eski** (`id DESC`). Bo'sh massiv — eng boshiga yetdingiz.
- `around`: `half = max(size/2, 1)`; `half` ta eskiroq + mo'ljaldagi xabar + `half` ta yangiroq (jami ≤ `2·half + 1`).
  Keyin pastga/yuqoriga yurish uchun `before=<eng eski id>` dan foydalaning; yangiroq tomonga kursor (`after`) **yo'q** —
  oxirigacha qaytish uchun birinchi sahifani (`before` siz) qayta so'rang.
- O'chirilgan xabarlar ham keladi (mazmunsiz, §3.1.4).
- `before` boshqa suhbatning `id` si bo'lsa ham xato yo'q (shunchaki `id < before` filtri).
- A'zo emas → 403 `chat.notParticipant`.

#### 3.2.3 `GET /conversations/{id}/messages/search?q=`

- `q` trim qilingandan keyin **< 2 belgi** → bo'sh massiv (bazaga so'rov yo'q). `q` yo'q ham — bo'sh massiv.
- Registrsiz, qism-satr bo'yicha (`LOWER(text) LIKE %q%`); `%` va `_` oddiy belgi sifatida qidiriladi.
- O'chirilganlar topilmaydi. Ko'pi bilan **20** ta, yangi → eski. Faqat matn bo'yicha (fayl nomi bo'yicha emas).
- Natijaga bosilganda: `GET …/messages?around=<id>`.

#### 3.2.4 `GET /search?q=`

```json
{ "conversations": [ ConversationResponse… ],          // ≤ 20
  "messages":      [ ChatMessageResponse + "conversationTitle" … ] }   // ≤ 20
```
- `conversations`: GROUP — guruh nomi bo'yicha; DIRECT — suhbatdosh `firstName + " " + lastName` bo'yicha. GROUP a'zolari ismi bo'yicha qidirilmaydi.
- `messages`: faqat hozir a'zo bo'lgan suhbatlardan, o'chirilmaganlar, yangi → eski. Har birida qo'shimcha `conversationTitle`
  (DIRECT da suhbatdosh ismi, GROUP da nomi). Bu maydon faqat shu javobda bor (boshqa joyda JSON da umuman chiqmaydi).
- `q` < 2 belgi → `{conversations: [], messages: []}`.

#### 3.2.5 `GET /presence`

```json
[ { "type": "PRESENCE", "userId": 9, "online": true, "lastSeenAt": "2026-10-02T18:40:00" } ]
```
- Faqat **suhbatdoshlar** (siz bilan kamida bitta umumiy faol suhbatdagi odamlar), o'zingizsiz.
- `online` — server xotirasidagi ochiq WebSocket sessiyalari soni > 0. `lastSeenAt` — oxirgi sessiya yopilgan payt (`users.last_seen_at`);
  onlayn bo'lsa ham to'ldirilgan bo'lishi mumkin (oldingi uzilish) — ko'rsatmang. Hech ulanmagan bo'lsa `null`.
- WebSocket ulangan zahoti bir marta so'rang, keyin `/topic/presence` hodisalari bilan yangilang (§3.6.6).

#### 3.2.6 `POST /conversations/direct`

```json
{ "userId": 9 }
```
| Holat | Natija |
|---|---|
| `userId` yo'q | 400, `validationErrors.userId` = `chat.userId.required` |
| `userId` = o'zingiz | 400 `chat.direct.self` |
| Suhbat allaqachon bor | 200, mavjud suhbat (suhbatdosh nofaol bo'lsa ham) |
| Foydalanuvchi topilmadi | 404 `chat.user.notFound` |
| Foydalanuvchi nofaol (`isActive = false`) | 400 `chat.user.inactive` |
| Parallel yaratish to'qnashuvi va qayta o'qib bo'lmadi | 400 `chat.direct.failed` |

Javob har doim **200** (yangi yaratilgan bo'lsa ham). Yangi suhbat haqida suhbatdoshga **hech qanday jonli hodisa yuborilmaydi** (§3.6.8).

#### 3.2.7 `POST /conversations/group`

```json
{ "title": "Sotuv bo'limi", "userIds": [9, 12, 15] }
```
| Maydon | Turi | Qoida |
|---|---|---|
| `title` | string | Majburiy, trim qilingandan keyin bo'sh emas, ≤ 255 (`chat.title.required`, `chat.title.size`) |
| `userIds` | Long[] | Majburiy, bo'sh emas (`chat.userIds.required`). Takrorlar va o'zingiz olib tashlanadi; qolgani bo'sh → 400 `chat.group.members`. Birortasi topilmasa → 404 `chat.user.notFound` |

- Yaratuvchi avtomatik a'zo. Javob **201**, `ConversationResponse` (`participants` da hammasi, siz ham).
- Nofaol foydalanuvchini qo'shish **tekshirilmaydi** (DIRECT dan farqli) — frontend `GET /users` ro'yxatidan tanlatsin.

#### 3.2.8 `PATCH /conversations/{id}/pin`

```json
{ "pinned": true }
```
`pinned` majburiy (`chat.pinned.required`). A'zo emas → 403. Javob — yangilangan `ConversationResponse` (ro'yxatni qayta so'rash shart emas,
lekin tartib o'zgaradi: qadalganlar tepada).

#### 3.2.9 `GET /users`

`ChatUserResponse[]` — barcha `isActive = true` foydalanuvchilar, o'zingizsiz, `firstName`, keyin `lastName` bo'yicha (registrsiz).
Sahifalash va qidiruv yo'q — frontend o'zi filtrlaydi.

#### 3.2.10 `GET /unread-count`

`{ "count": 7 }` — barcha faol suhbatlardagi o'qilmaganlar yig'indisi (o'z xabarlaringiz va o'chirilganlarsiz).
Jonli yangilanmaydi — `MESSAGE` / `READ` hodisalarida o'zingiz hisoblang yoki qayta so'rang.

#### 3.2.11 `POST /upload` — §3.5 ga qarang.



### 3.3 DTO lar

#### 3.3.1 `ChatMessageResponse` (REST lentasi va `MESSAGE` hodisasi — bir xil shakl)

```json
{
  "type": "MESSAGE",
  "id": 1036,
  "uuid": "3f0b6a1e-…",
  "conversationId": 42,
  "senderId": 5, "senderName": "Aziz Malikov", "senderPhotoUrl": null,
  "text": "Hisobotni yubordim",
  "messageType": "FILE",
  "replyToId": 1030,
  "clientId": "tmp-1727950000-1",
  "attachments": [ ChatAttachmentResponse… ],
  "replyTo": { "id": 1030, "senderName": "Dilnoza Karimova", "text": "Hisobot qachon?", "type": "TEXT" },
  "createdAt": "2026-10-03T09:15:02.811204",
  "editedAt": null,
  "deletedAt": null
}
```
| Maydon | Izoh |
|---|---|
| `type` | Hodisa turi, doim `"MESSAGE"`. Xabar turi — `messageType`. |
| `text` | Trim qilingan. Biriktirmali xabarda `null` bo'lishi mumkin. O'chirilgan → `null`. |
| `attachments` | `sortOrder` bo'yicha. Biriktirmasiz xabarda: yuborilgan hodisada `[]`, REST lentasida **`null`** — ikkalasini bir xil ishlang. O'chirilgan → `null`. |
| `replyTo` | Javob bo'lmasa `null`. Asl xabar o'chirilgan → `replyTo.text = null`. |
| `clientId` | Faqat `/app/chat.send` dan keyingi jonli hodisada; REST da doim `null`. **Hodisa suhbatning barcha a'zolariga** shu `clientId` bilan boradi — optimistik xabarni almashtirishda `senderId == me && clientId == …` ni tekshiring. |
| `editedAt` | Tahrirlangan bo'lsa — "tahrirlandi" belgisi. |
| `deletedAt` | To'ldirilgan → "Xabar o'chirildi". |

#### 3.3.2 `ChatAttachmentResponse`

```json
{ "id": 77, "fileUrl": "/api/files/0c6f…-….webm", "fileName": "voice.webm", "fileSize": 48213,
  "contentType": "audio/webm", "width": null, "height": null,
  "durationMs": 7400, "waveform": "12,40,77,63,20", "sortOrder": 0 }
```
`width/height` — faqat rasm (o'qib bo'lmasa `null`, masalan ba'zi WebP). `durationMs/waveform` — faqat `audio/*`.
`fileUrl` — nisbiy yo'l: to'liq URL = API bazasi + `fileUrl`.

#### 3.3.3 `ChatUserResponse`
```json
{ "id": 9, "fullName": "Dilnoza Karimova", "username": "dilnoza", "role": "ACCOUNTANT",
  "photoUrl": null, "online": false, "lastSeenAt": "2026-10-02T18:40:00" }
```

#### 3.3.4 Hodisa DTO lari — §3.6.5 jadvalida.



### 3.4 Xabar yuborish oqimi (umumiy ko'rinish)

```
 [fayl tanlandi] ──POST /api/chat/upload──► ChatUploadResponse (fayl diskda, xabar YO'Q)
        (har fayl uchun alohida, ≤ 10)               │ o'zgarishsiz
                                                     ▼
 STOMP SEND /app/chat.send {conversationId, text?, clientId, replyToId?, attachments:[…]}
        │
        ├─ OK  → /topic/conversation.{id} ga MESSAGE (hammaga, sizga ham, clientId bilan)
        └─ xato → /user/queue/errors {type:"ERROR", message}  (clientId QAYTMAYDI)
```



### 3.5 Fayllar va ovozli xabarlar

#### 3.5.1 `POST /api/chat/upload`

`multipart/form-data`:

| Qism | Turi | Qoida |
|---|---|---|
| `file` | fayl | Majburiy. **Fayl nomida kengaytma bo'lishi shart** (pastdagi jadval) va qismning `Content-Type` i shu kengaytmaga mos bo'lishi shart. |
| `durationMs` | integer | Faqat `audio/*` da **majburiy**: `1 … 300000` (5 daqiqa). Boshqa turlarda e'tiborsiz. |
| `waveform` | string | Faqat `audio/*` da, ixtiyoriy: vergul bilan ajratilgan butun sonlar `0…100`, **≤ 50 ta**. Bo'sh/yo'q → `null`. Bo'sh joylar tozalanadi. |

Javob (`message: "Fayl yuklandi"`):
```json
{ "fileUrl": "/api/files/0c6f2a…e1.webm", "fileName": "voice.webm", "fileSize": 48213,
  "contentType": "audio/webm", "width": null, "height": null, "durationMs": 7400, "waveform": "12,40,77,63,20" }
```
- Server faylni `<UUID>.<kengaytma>` nomi bilan saqlaydi; `fileName` — asl nom (yo'l qismlari olib tashlanadi, ≤ 255 belgiga qisqartiriladi; nom yo'q → `"file"`).
- `contentType` — `;` dan keyingisi tashlanadi, kichik harf (`"audio/webm;codecs=opus"` → `"audio/webm"`).
- Bu bosqichda suhbat a'zoligi tekshirilmaydi — u `chat.send` da tekshiriladi. Yuklangan, lekin yuborilmagan fayl diskda qoladi (tozalovchi yo'q).
- Javobni **o'zgarishsiz** `attachments[]` ga qo'ying (server `fileUrl` ni tekshiradi, qolganini "ko'rinish uchun" yozib qo'yadi).

#### 3.5.2 Ruxsat etilgan turlar (`FileStorageService.ALLOWED`)

| Kengaytma | Qabul qilinadigan `Content-Type` | Natijaviy `messageType` | `GET /api/files` da |
|---|---|---|---|
| `jpg`, `jpeg` | `image/jpeg`, `image/pjpeg` | IMAGE | inline |
| `png` | `image/png` | IMAGE | inline |
| `webp` | `image/webp` | IMAGE | inline |
| `gif` | `image/gif` | IMAGE | inline |
| `pdf` | `application/pdf` | FILE | inline |
| `doc` | `application/msword` | FILE | attachment |
| `docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | FILE | attachment |
| `xls` | `application/vnd.ms-excel` | FILE | attachment |
| `xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | FILE | attachment |
| `webm` | `audio/webm` | VOICE | inline |
| `ogg` | `audio/ogg`, `application/ogg` | VOICE (faqat `audio/ogg` bo'lsa — §3.9) | inline |
| `mp3` | `audio/mpeg`, `audio/mp3` | VOICE | inline |
| `m4a` | `audio/mp4`, `audio/x-m4a`, `audio/m4a` | VOICE | inline |

SVG, HTML, video, zip va h.k. — **yo'q** → 400 `chat.upload.type` (audio bo'lsa `chat.upload.audioType`).

**Diktofon (MediaRecorder) uchun muhim:** `FormData.append("file", blob)` nomsiz qo'shilsa brauzer nomni `"blob"` qiladi → kengaytma yo'q → 400.
Doim nom bering: Chrome → `voice.webm` (`audio/webm`), Firefox → `voice.ogg` (`audio/ogg`), Safari → `voice.m4a` (`audio/mp4`).
`video/webm` (video trekli yozuv) qabul qilinmaydi.

#### 3.5.3 Chegaralar

| Narsa | Chegara | Xato |
|---|---|---|
| Bitta fayl | **4 MB** (`FileStorageService.MAX_BYTES` + `spring.servlet.multipart.max-file-size: 4MB`) | 400 `chat.upload.tooLarge` |
| Butun so'rov | 8 MB (`max-request-size`) | 400 `chat.upload.tooLarge` |
| Bo'sh fayl | — | 400 `chat.upload.empty` |
| Rasm tomoni | ≤ **12 000** px har tomon, ≤ **50 000 000** piksel (faqat sarlavhadan o'qiladi) | 400 `chat.upload.imageDimensions` |
| Ovoz uzunligi | `1 … 300 000` ms, majburiy | 400 `chat.voice.durationRequired` / `chat.voice.tooLong` |
| To'lqin | ≤ 50 nuqta, har biri 0…100 butun son | 400 `chat.voice.waveformTooLong` / `chat.voice.waveformInvalid` |
| Saqlab bo'lmadi (disk) | — | 400 `chat.upload.failed` |
| Biriktirma / xabar | ≤ **10** | `chat.attachment.tooMany` (STOMP xatosi) |
| Ovozli xabar | Faqat **1** biriktirma, boshqa fayl bilan aralashmaydi | `chat.voice.single` (STOMP xatosi) |

Prod nginx'da `client_max_body_size 10m` (2026-10-03 da qo'llandi). Undan katta so'rov nginx'da **413** oladi; javobda CORS sarlavhasi
bo'lmagani uchun brauzer buni "CORS xatosi" deb ko'rsatadi — fayl hajmini frontendda (≤ 4 MB) oldindan tekshiring.

#### 3.5.4 Fayllarni ko'rsatish — `GET /api/files/{filename}`

- **Ochiq** (token kerak emas) — `<img src>`, `<audio src>` sarlavha yubora olmaydi. Himoya — taxmin qilib bo'lmaydigan UUID nom.
- `Content-Type` faqat kengaytmadan; `X-Content-Type-Options: nosniff`. Rasm/PDF/audio — `inline`, qolgani — `attachment`.
- `Content-Disposition` dagi nom — **diskdagi UUID nom**, asl nom emas. Asl nom bilan yuklab olish uchun frontend
  `fetch` → blob → `<a download="{fileName}">` qilsin.
- Topilmasa → 404 (tanasiz).
- O'chirilgan xabar fayli diskda qoladi va URL ni bilgan odam uni ochaveradi.



### 3.6 WebSocket / STOMP

#### 3.6.1 Ulanish

| Narsa | Qiymat |
|---|---|
| Endpoint | `wss://<api-host>/ws` — **sof WebSocket, SockJS yo'q** (`withSockJS()` chaqirilmagan) |
| Autentifikatsiya | Handshake'da: `?token=<JWT>` (brauzer uchun) yoki `Authorization: Bearer <JWT>` sarlavhasi. STOMP `CONNECT` sarlavhalaridagi token **o'qilmaydi**. |
| Handshake rad etiladi (HTTP 403) | Token yo'q / yaroqsiz / muddati o'tgan, foydalanuvchi nofaol, rol STAFF emas, Origin ruxsat etilmagan |
| Origin | `https://admin.adizone.uz`, `https://crm.adizone.uz`, `https://app.adizone.uz`, `https://adizone.uz`, `https://www.adizone.uz`, `https://*.vercel.app`, `http://localhost:3000`, `http://localhost:5173`, `http://localhost:5174`, `http://127.0.0.1:5173`, `http://127.0.0.1:3000` (REST CORS bilan bir ro'yxat) |
| Broker | Xotiradagi `SimpleBroker`, prefikslar `/topic`, `/queue` |
| Ilova prefiksi | `/app` (mijoz → server) |
| Shaxsiy prefiks | `/user` |
| Heartbeat | **Sozlanmagan** (broker'da `TaskScheduler`/`setHeartbeatValue` yo'q) — server heartbeat yubormaydi va kutmaydi. Proxy idle-timeout ulanishni uzishi mumkin → avtomatik qayta ulanish shart (§3.6.8). |
| Token muddati | Faqat handshake'da tekshiriladi. Ulangan sessiya token eskirgandan keyin ham ishlayveradi; qayta ulanishda yangi token bering. |

`@stomp/stompjs` misoli:
```js
const client = new Client({
  brokerURL: `${WS_BASE}/ws?token=${encodeURIComponent(accessToken)}`, // har ulanishda yangi token
  reconnectDelay: 3000,
  heartbeatIncoming: 0, heartbeatOutgoing: 0,
  onConnect: () => { /* §3.6.8 dagi tartib */ },
});
```

#### 3.6.2 Obuna (SUBSCRIBE) — oq ro'yxat

| Manzil | Kim | Izoh |
|---|---|---|
| `/topic/conversation.{id}` | Faqat shu suhbatning faol a'zosi | `{id}` — faqat raqam (1–18 xona) |
| `/topic/presence` | Har qanday ulangan xodim | Onlayn/oflayn hodisalar |
| `/user/queue/errors` | Har kim (o'zining) | Shaxsiy xatolar |

Boshqa har qanday manzil, naqsh belgilari (`* ? { }`) bo'lgan manzil yoki a'zo bo'lmagan suhbat → server **STOMP ERROR kadri
yuboradi va ulanishni yopadi**. Ya'ni noto'g'ri obuna butun ulanishni o'ldiradi — obunadan oldin suhbat ro'yxatdagi
(`GET /conversations`) `id` ekanini tekshiring.

A'zolik faqat obuna paytida tekshiriladi.

#### 3.6.3 Yuborish (SEND) — faqat `/app/*`

`/topic/…` yoki `/queue/…` ga to'g'ridan-to'g'ri SEND — tashlanadi, `/user/queue/errors` ga `"Bu manzilga xabar yuborib bo'lmaydi"`.
Tanada `conversationId` bo'lsa va siz a'zo bo'lmasangiz — tashlanadi, `"Bu suhbatga ruxsat yo'q"`. Yuboruvchi har doim
handshake'dagi foydalanuvchi (tanadagi `senderId` va h.k. e'tiborsiz). Tana — JSON (`content-type: application/json`).

| Manzil | Tana | Natija (`/topic/conversation.{id}` ga) |
|---|---|---|
| `/app/chat.send` | `ChatSendRequest` (§3.6.4) | `MESSAGE` |
| `/app/chat.read` | `{ "conversationId": 42, "messageId": 1036 }` (ikkalasi majburiy) | `READ` — faqat kursor haqiqatan oldinga surilsa |
| `/app/chat.typing` | `{ "conversationId": 42, "typing": true }` (ikkalasi majburiy) | `TYPING` |
| `/app/chat.edit` | `{ "messageId": 1036, "text": "…" }` (`text` bo'sh emas, ≤ 4000) | `EDITED` |
| `/app/chat.delete` | `{ "messageId": 1036 }` | `DELETED` — faqat birinchi marta |

#### 3.6.4 `ChatSendRequest`

```json
{
  "conversationId": 42,
  "text": "Hisobotni yubordim",
  "clientId": "tmp-1727950000-1",
  "replyToId": 1030,
  "attachments": [
    { "fileUrl": "/api/files/0c6f…pdf", "fileName": "hisobot.pdf", "fileSize": 182733,
      "contentType": "application/pdf", "width": null, "height": null, "durationMs": null, "waveform": null }
  ]
}
```
| Maydon | Turi | Qoida |
|---|---|---|
| `conversationId` | Long | Majburiy; a'zo bo'lishingiz shart |
| `text` | string | ≤ 4000 belgi; trim qilinadi. `text` ham, `attachments` ham bo'sh → `chat.text.required` |
| `clientId` | string | Ixtiyoriy, ≤ 64. Saqlanmaydi, hodisada aks etadi |
| `replyToId` | Long | Ixtiyoriy; **shu suhbatdagi** xabar bo'lishi shart → aks holda `chat.reply.notInConversation`. O'chirilgan xabarga ham javob berish mumkin |
| `attachments[]` | | ≤ 10; har biri: |
| `.fileUrl` | string | Majburiy, ≤ 500; `/api/files/<nom>` shaklida, ichida `/`, `\`, `..` yo'q, fayl diskda **mavjud** bo'lishi shart (`chat.attachment.foreignUrl` / `chat.attachment.missing`) |
| `.fileName` | string | Majburiy, ≤ 255 |
| `.contentType` | string | ≤ 100; `messageType` shundan aniqlanadi |
| `.fileSize`, `.width`, `.height` | Long/Integer | Tekshirilmaydi, yozib qo'yiladi |
| `.durationMs`, `.waveform` | | `contentType` `audio/*` bo'lsa yuklashdagi qoidalar qayta tekshiriladi (§3.5.3) |

#### 3.6.5 Server → mijoz hodisalari

Hamma suhbat hodisalari bitta topikda — `/topic/conversation.{id}`; turi `type` maydonida.

| `type` | Qachon | Tana |
|---|---|---|
| `MESSAGE` | `chat.send` muvaffaqiyatli | `ChatMessageResponse` (§3.3.1) — sizga ham keladi (`clientId` bilan) |
| `READ` | Kimdir kursorini oldinga surdi | `{ "type": "READ", "conversationId": 42, "userId": 9, "messageId": 1036 }` |
| `TYPING` | Kimdir yozmoqda / to'xtatdi | `{ "type": "TYPING", "conversationId": 42, "userId": 9, "userName": "Dilnoza Karimova", "typing": true }` |
| `EDITED` | Matn tahrirlandi | `{ "type": "EDITED", "conversationId": 42, "messageId": 1036, "text": "…", "editedAt": "2026-10-03T09:20:00.1" }` |
| `DELETED` | Xabar o'chirildi | `{ "type": "DELETED", "conversationId": 42, "messageId": 1036, "deletedBy": 1 }` |

`/topic/presence`:
```json
{ "type": "PRESENCE", "userId": 9, "online": false, "lastSeenAt": "2026-10-03T09:30:11.52" }
```

`/user/queue/errors`:
```json
{ "type": "ERROR", "message": "Bu suhbatga ruxsat yo'q" }
```

Hodisa semantikasi:
- **Tasdiq (ack) yo'q.** Xabar qabul qilinganining yagona belgisi — o'zingizga qaytgan `MESSAGE` (mos `clientId` bilan). Shuning uchun
  **yuborishdan oldin o'sha suhbat topikiga obuna bo'ling.**
- **Xatoda `clientId` qaytmaydi** — eng oxirgi "yuborilmoqda" holatidagi xabarni xato deb belgilang. Bir vaqtda bir nechta
  kutilayotgan xabar bo'lsa, qaysi biri rad etilganini aniq bilib bo'lmaydi; timeout (masalan 10 s) bilan ham himoyalang.
- `READ`: o'zingizning boshqa tabingizdan ham keladi (`userId == me`) — o'z hisoblagichingizni shu bilan sinxronlang.
- `TYPING`: o'zingizga ham qaytadi — `userId == me` bo'lsa tashlang. Server taymer yuritmaydi: belgini **3 soniyada o'zingiz
  o'chiring**; yozish davom etsa `typing: true` ni har ~2 soniyada qayta yuboring (tavsiya), matn tozalanganda/yuborilganda `typing: false`.
  Bazaga yozilmaydi.
- `EDITED`/`DELETED` faqat o'zgargan qismni olib keladi — lentadagi kartani `messageId` bo'yicha yangilang.
  `DELETED` dan keyin `text` va biriktirmalarni yashiring; agar bu oxirgi xabar bo'lsa, ro'yxatdagi "oxirgi xabar"ni qayta so'rang.
- `PRESENCE` faqat holat o'zgarganda (0↔1 sessiya) chiqadi: ikkinchi tab ochilganda yoki yopilganda hodisa yo'q. Hodisa **barcha
  ulangan xodimlarga** boradi (faqat suhbatdoshlarga emas) — keraksizlarini e'tiborsiz qoldiring.
- Hodisalar tartibi kafolatlanmagan (kiruvchi kanal thread-pool'da) — lentani doim `id` bo'yicha saralang, `id` bo'yicha dublikatni tashlang.

#### 3.6.6 Onlayn holat

- Holat server **xotirasida** (sessiyalar soni), bazada faqat `users.last_seen_at` (V46) — oxirgi sessiya yopilganda yoziladi.
- Server qayta ishga tushsa hamma oflayn bo'ladi (sessiyalar ham uziladi), `last_seen_at` yozilmay qolishi mumkin.
- Manbalar: `GET /presence` (suhbatdoshlar surati), `ChatUserResponse.online/lastSeenAt` (`/users`, `participants`), `/topic/presence` (jonli).

#### 3.6.7 Ruxsat qoidalari (xulosa)

| Amal | Kim |
|---|---|
| Suhbatni o'qish, yozish, `read`, `typing`, obuna, qadash | Suhbatning faol a'zosi |
| Tahrir | Muallif (+ a'zo), 15 daqiqa, faqat TEXT |
| O'chirish | Muallif yoki SA/A (+ a'zo) |
| DIRECT / GROUP yaratish | Har qanday STAFF |
| A'zo qo'shish/chiqarish | — (funksiya yo'q) |

#### 3.6.8 Tavsiya etilgan ulanish tartibi

1. `GET /conversations` → ro'yxat.
2. WebSocket ulanish (`?token=`).
3. `onConnect`: `/user/queue/errors`, `/topic/presence` va **har bir suhbat** uchun `/topic/conversation.{id}` ga obuna.
   Server sizga "yangi xabar" yoki "sizni yangi suhbatga qo'shishdi" degan **shaxsiy bildirishnoma yubormaydi** — obuna bo'lmagan
   suhbatdagi xabar sizga umuman yetmaydi.
4. `GET /presence` → boshlang'ich onlayn holat.
5. Ochiq suhbat uchun `GET …/messages` (yoki qayta ulanishda — oxirgi ma'lum `id` dan yangilarini olish uchun birinchi sahifani qayta so'rang).
6. Yangi suhbatlarni topish uchun `GET /conversations` ni vaqti-vaqti bilan / oyna fokusida qayta so'rang va yangi `id` larga obuna bo'ling.
7. Ulanish uzilsa — qayta ulanish va 3–5-qadamlarni takrorlash (uzilish paytidagi hodisalar qayta yuborilmaydi).



### 3.7 Cheklovlar (jamlanma)

| Narsa | Chegara |
|---|---|
| Xabar matni | ≤ 4000 belgi (trim'dan keyin; trim'dan oldin ham `@Size` 4000) |
| `clientId` | ≤ 64 |
| Biriktirma / xabar | ≤ 10; ovoz — faqat 1 |
| Fayl | ≤ 4 MB; so'rov ≤ 8 MB |
| Rasm | tomon ≤ 12 000 px, ≤ 50 MP |
| Ovoz | 1 ms … 5 daqiqa; to'lqin ≤ 50 nuqta (0–100), satr ≤ 500 |
| Guruh nomi | 1–255 |
| Lenta sahifasi | standart 50, maks 100 |
| Qidiruv | `q` ≥ 2 belgi; har bo'limda ≤ 20 natija |
| Tahrir oynasi | 15 daqiqa |
| Rate limit | yo'q |
| STOMP kadr hajmi | Alohida sozlanmagan (Spring/Tomcat standartlari). Kirill matnli 4000 belgilik xabar ~8 KB bo'ladi — chegaraga yaqin, §3.9 ga qarang |



### 3.8 Xatolar

#### 3.8.1 Shakllar

| Manba | HTTP | Tana |
|---|---|---|
| `BadRequestException` (servis) | 400 | `ErrorResponse {timestamp, status, error, message}` — **`code` yo'q** |
| Bean validation (`@Valid` body) | 400 | `ErrorResponse {…, message: "<error.validationFailed>", validationErrors: {field: matn}}` |
| `ResourceNotFoundException` | 404 | `ErrorResponse` |
| `ForbiddenException` (a'zo emas, auth) | 403 | **`ApiResponse {success: false, message, data: null}`** — boshqa shakl! |
| URL qoidasi (rol STAFF emas) | 403 | `ApiResponse` / Security javobi |
| STOMP (har qanday) | — | `/user/queue/errors` → `{type: "ERROR", message}` |
| STOMP noto'g'ri SUBSCRIBE | — | STOMP `ERROR` kadri + ulanish yopiladi |

`message` ni ikkala shakldan ham `body.message` sifatida o'qing.

#### 3.8.2 Xabar kalitlari → status

| Kalit (`messages.properties`) | Qayerda | HTTP / kanal |
|---|---|---|
| `chat.auth.required` | REST, STOMP | 403 / errors |
| `chat.notParticipant` | REST (messages, search, pin), STOMP | 403 / errors |
| `chat.cursor.conflict` | `GET …/messages` | 400 |
| `chat.message.notFound` | `around`, `chat.read` (400); `chat.edit`/`chat.delete` (404 → errors) | 400 / errors |
| `chat.direct.self`, `chat.user.inactive`, `chat.direct.failed` | `POST …/direct` | 400 |
| `chat.user.notFound` | `POST …/direct`, `POST …/group` | 404 |
| `chat.userId.required` | `POST …/direct` (validation) | 400 |
| `chat.title.required`, `chat.title.size`, `chat.userIds.required` | `POST …/group` (validation) | 400 |
| `chat.group.members` | `POST …/group` | 400 |
| `chat.pinned.required` | `PATCH …/pin` (validation) | 400 |
| `chat.upload.empty`, `chat.upload.type`, `chat.upload.audioType`, `chat.upload.tooLarge`, `chat.upload.imageDimensions`, `chat.upload.failed` | `POST /upload` | 400 |
| `chat.voice.durationRequired`, `chat.voice.tooLong`, `chat.voice.waveformTooLong`, `chat.voice.waveformInvalid` | `POST /upload` (400); `chat.send` (errors) | 400 / errors |
| `chat.conversationId.required`, `chat.messageId.required`, `chat.typing.required`, `chat.text.size`, `chat.clientId.size`, `chat.attachment.url.*`, `chat.attachment.name.*`, `chat.attachment.contentType.size`, `chat.attachment.waveform.size` | STOMP validation | errors (matn — §3.9) |
| `chat.text.required`, `chat.text.size`, `chat.reply.notInConversation`, `chat.attachment.tooMany`, `chat.voice.single`, `chat.attachment.foreignUrl`, `chat.attachment.missing` | `chat.send` | errors |
| `chat.edit.notAuthor` | `chat.edit` | errors (403 ma'nosida) |
| `chat.edit.deleted`, `chat.edit.notText`, `chat.edit.tooOld` | `chat.edit` | errors |
| `chat.delete.notAllowed` | `chat.delete` | errors (403 ma'nosida) |
| `"Bu manzilga xabar yuborib bo'lmaydi"`, `"Bu suhbatga ruxsat yo'q"` (kalitsiz, faqat uz) | Interceptor (SEND rad) | errors |
| `"Xabarni yuborib bo'lmadi"` (kalitsiz) | Xabarsiz istisno | errors |



### 3.9 Ma'lum cheklovlar / xatolar

1. **Guruh boshqaruvi yo'q**: a'zo qo'shish/chiqarish, guruhdan chiqish, nomini o'zgartirish, suhbatni o'chirish, `mute` — endpoint yo'q.
2. **Shaxsiy bildirishnoma yo'q**: yangi suhbat yoki obuna bo'linmagan suhbatdagi xabar haqida server xabar bermaydi (§3.6.8). Har bir suhbatga obuna bo'lish va ro'yxatni vaqti-vaqti bilan yangilash kerak.
3. **Ack yo'q, xatoda `clientId` yo'q** (§3.6.5).
4. **Heartbeat yo'q**: nginx `proxy_read_timeout` (standart 60 s) bo'sh ulanishni uzishi mumkin — `reconnectDelay` majburiy.
5. **Xato shakllari aralash**: 403 — `ApiResponse`, 400/404 — `ErrorResponse`; chat xatolarida `code` yo'q.
6. **STOMP validatsiya xatolari xom matn**: `@Valid` dan yiqilgan kadr (masalan `text` > 4000, `conversationId` yo'q) uchun
   `/user/queue/errors` ga Spring'ning ichki matni boradi (metod imzosi, maydon nomlari bilan) — foydalanuvchiga ko'rsatmang;
   yuborishdan oldin chegaralarni frontendda tekshiring.
7. **STOMP xato tili** `Accept-Language` ga bog'liq emas — server JVM lokaliga bog'liq (aniqlanmagan, odatda en yoki uz).
8. ~~Apostrof xatosi~~ — **tuzatildi (2026-10-03)**: argumentsiz `chat.*` matnlari endi bitta apostrof bilan (`"Fayl bo'sh"`).
9. **Upload'dagi chalg'ituvchi matnlar**: `file` qismi yo'q → `"… (student import uchun 'file' kerak)"`; multipart bo'lmagan so'rov →
   `"… Excel (.xlsx) yuboring …"`. Status to'g'ri (400), matn chatga mos emas.
10. **`messageType` mijoz yuborgan `contentType` dan** aniqlanadi va `fileUrl` dagi haqiqiy kengaytma bilan solishtirilmaydi — `upload`
    javobini o'zgartirmasdan uzating. `application/ogg` bilan yuklangan `.ogg` fayl `VOICE` emas, `FILE` bo'ladi (VOICE faqat `audio/*`).
11. **`GET /users` rol bo'yicha filtrlanmaydi**: bazada faol STUDENT/PARENT foydalanuvchilar bo'lsa, ular ham ro'yxatda chiqadi (ular
    chatga ulana olmaydi). GROUP yaratishda nofaol foydalanuvchi ham qo'shilaveradi.
12. **Onlayn holat ikki tabda noto'g'ri bo'lishi mumkin**: Spring bitta sessiya uchun `SessionDisconnectEvent` ni ikki marta chiqarishi
    mumkin, sanoq esa idempotent emas — ikkinchi tab hali ochiq bo'lsa ham foydalanuvchi oflayn ko'rinishi mumkin.
13. **Katta STOMP kadr**: Tomcat'ning standart matn buferi (8 KB) sozlanmagan; 4000 belgilik kirill matn + biriktirmalar shu chegaraga
    yetishi va ulanish 1009 kodi bilan yopilishi mumkin (tekshirilmagan). Ulanish yopilsa — qayta ulaning va xabarni xato deb belgilang.
14. **Yetim fayllar**: yuklangan, lekin yuborilmagan fayllar diskda qoladi; o'chirilgan xabar fayllari URL orqali ochiq qoladi.
15. **`after` kursor yo'q**: `around` dan keyin yangiroq tomonga sahifalab bo'lmaydi (§3.2.2).
16. Sessiya davomida token muddati yoki foydalanuvchi faolligi qayta tekshirilmaydi (faqat handshake'da).



### 3.10 Manbalar (kod)

| Mavzu | Fayl |
|---|---|
| REST | `controller/ChatController.java` |
| STOMP | `controller/ChatSocketController.java`, `config/WebSocketConfig.java`, `config/ChatChannelInterceptor.java` |
| Handshake | `security/jwt/JwtHandshakeInterceptor.java`, `security/jwt/PrincipalHandshakeHandler.java` |
| Qoidalar | `service/ChatService.java`, `service/ChatAccessService.java`, `service/ChatAttachmentService.java`, `service/ChatPresenceService.java` |
| Fayllar | `service/FileStorageService.java`, `controller/FileController.java` |
| Rollar, CORS | `config/SecurityConfig.java` (`STAFF_ROLES`, `ALLOWED_ORIGIN_PATTERNS`) |
| Sxema | `V45__chat.sql`, `V46__chat_presence.sql`, `V47__chat_attachments.sql`, `V48__chat_voice.sql` |
| Matnlar | `messages*.properties` (`chat.*`) |

---

## 4. Uy vazifalari — `/api/homework` (SA, A, T)

T — faqat **o'z guruhlari** (guruh o'qituvchisi) yoki o'zi yaratgan vazifa; boshqasi → 403. O'quvchi login qilmaydi: holat, baho va izohni
o'qituvchi belgilaydi. ACC, SH, SM → 403.

| Metod, yo'l | Rollar | Tavsif |
|---|---|---|
| `GET /api/homework` | SA, A, T | Faol vazifalar, muddat ↓. Query: `groupId` (T — o'z guruhi, aks holda 403), `from`, `to` (topshirish muddati; `from > to` → 400 `homework.dates.invalid`), `page` (0), `size` (20). T — faqat o'zinikilar |
| `GET /api/homework/{id}` | SA, A, T | `HomeworkResponse` |
| `POST /api/homework` | SA, A, T | Yaratish → 201 |
| `PUT /api/homework/{id}` | SA, A, T | To'liq yangilash (yuborilmagan `attachmentUrl` — fayl olib tashlanadi) |
| `DELETE /api/homework/{id}` | SA, A | Yumshoq o'chirish; keyin ro'yxatda yo'q, `/{id}/students` → 404 |
| `GET /api/homework/{id}/students` | SA, A, T | O'quvchilar bo'yicha holat (§4.2) |
| `PUT /api/homework/{id}/students` | SA, A, T | Holat/baho/izohni belgilash — ko'plikda, upsert (§4.3) |
| `GET /api/homework/group/{groupId}`, `GET/POST /{id}/submissions`, `PUT /submissions/{id}` | — | **deprecated** (endi egalik tekshiriladi) — `GET ?groupId=` va `/{id}/students` ni ishlating |

### 4.1 `POST /api/homework`
```json
{ "title": "1-dars mashqlari", "description": "12–15-betlar", "groupId": 4,
  "assignedDate": "2026-09-15", "dueDate": "2026-09-18", "marks": 10,
  "attachmentUrl": "/api/files/0c6f2a…e1.pdf", "attachmentName": "mashq.pdf" }
```
| Maydon | Qoida |
|---|---|
| `title` | Majburiy (`homework.title.required`) |
| `groupId` | **Majburiy** (`homework.group.required`); T — o'z guruhi |
| `dueDate` | Majburiy (`homework.dueDate.required`); `assignedDate` dan oldin emas (`homework.dates.invalid`) |
| `assignedDate` | Berilmasa — bugun |
| `marks` | Maksimal ball, ixtiyoriy, ≥ 0 |
| `attachmentUrl` / `attachmentName` | Fayl avval `POST /api/files/upload` (§4.4) orqali yuklanadi; javobdagi `url` → `attachmentUrl`, `originalName` → `attachmentName`. Boshqa manzil → 400 `homework.attachment.invalid`. Fayl ochiq URL orqali beriladi (`GET /api/files/{nom}`) |

T yaratsa `teacherId` = o'zi; SA/A yaratsa — guruh o'qituvchisi (yoki `teacherId`).
**`HomeworkResponse`:** `id, uuid, title, description, subjectId, subjectName, classId, className, groupId, groupName, teacherId, teacherName,
assignedDate, dueDate, marks, attachmentUrl, attachmentName, isActive, createdAt`.

### 4.2 `GET /api/homework/{id}/students`
```json
{ "homework": { …HomeworkResponse },
  "summary": { "total": 12, "submitted": 8, "late": 2, "notSubmitted": 2, "graded": 9, "averageMark": 8.44 },
  "students": [ { "studentId": 5, "studentName": "Ali Valiyev", "inGroup": true, "submissionId": 31,
                  "status": "SUBMITTED", "marksObtained": 9, "remarks": "Yaxshi", "submittedAt": "2026-09-17T18:20:00",
                  "fileUrl": null, "updatedAt": "2026-09-17T19:00:00" } ] }
```
- Ro'yxat: guruhning **hozirgi** faol o'quvchilari + guruhdan chiqqan, lekin oldin belgilangan o'quvchilar (`inGroup: false`). Ism bo'yicha.
- Belgilanmagan o'quvchi — `status: "NOT_SUBMITTED"`, `submissionId: null`.
- `status` ∈ `SUBMITTED` (topshirdi), `LATE` (kech topshirdi), `NOT_SUBMITTED` (topshirmadi). Eski `PENDING` — `NOT_SUBMITTED` (V65).
- `averageMark` — baholanganlar o'rtachasi (2 kasr), baho yo'q — `null`.

### 4.3 `PUT /api/homework/{id}/students`
```json
{ "items": [ { "studentId": 5, "status": "SUBMITTED", "marksObtained": 9, "remarks": "Yaxshi" },
             { "studentId": 6, "status": "LATE", "submittedAt": "2026-09-19T10:00:00" },
             { "studentId": 7, "status": "NOT_SUBMITTED" } ] }
```
- 1–200 ta element (`homework.items.required`). **Hammasi yoki hech biri**: xatoda butun so'rov bekor, `data.index` — qaysi element.
- Upsert: o'quvchida yozuv bo'lsa yangilanadi, bo'lmasa yaratiladi. Javob — yangilangan §4.2.
- `submittedAt` — ixtiyoriy; SUBMITTED/LATE da berilmasa avvalgisi yoki hozir. `NOT_SUBMITTED` da `submittedAt` va baho tozalanadi.

| Xato | Kod |
|---|---|
| `status` yo'q / noma'lum (`DONE`) | 400 `homework.status.required` / `homework.status.invalid` |
| O'quvchi guruhda emas (va oldin belgilanmagan) | 400 `homework.student.notInGroup` |
| Bir o'quvchi ikki marta | 400 `homework.student.duplicate` |
| Baho < 0 yoki > `marks` | 400 `homework.mark.range` |
| `NOT_SUBMITTED` ga baho | 400 `homework.mark.notSubmitted` |
| Begona o'qituvchi | 403 |
| Vazifa o'chirilgan / yo'q | 404 |

### 4.4 `POST /api/files/upload` — fayl yuklash (uy vazifasi, shartnoma)
Barcha xodimlar. `multipart/form-data`, qism `file`. Javob **200**:
```json
{ "url": "/api/files/0c6f2a…e1.pdf", "filename": "0c6f2a…e1.pdf", "originalName": "1-dars mashqlari.pdf" }
```
| Tur | Kengaytma | `Content-Type` | Qo'shimcha tekshiruv |
|---|---|---|---|
| Rasm | `jpg`, `jpeg`, `png`, `webp`, `gif` | `image/jpeg`, `image/png`, `image/webp`, `image/gif` | tomoni ≤ 12 000 px, ≤ 50 MP |
| PDF | `pdf` | `application/pdf` | fayl `%PDF-` bilan boshlanadi |
| Word | `doc`, `docx` | `application/msword`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | doc — OLE2, docx — ZIP sarlavhasi |
| Excel | `xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | ZIP sarlavhasi |

- Hajm ≤ **4 MB** (frontendda oldindan tekshiring; nginx 10 MB dan kattasini 413 bilan qaytaradi).
- Fayl nomida kengaytma bo'lishi, kengaytma va `Content-Type` bir-biriga mos bo'lishi shart. `xls`, `txt`, `zip`, `html`, `svg`, audio — **yo'q**
  (audio — faqat chatda, `POST /api/chat/upload`).
- Xatolar (400, `ErrorResponse.message`, `code` yo'q): "Fayl bo'sh", "Faqat rasm (…) yoki hujjat (pdf, doc, docx, xlsx) qabul qilinadi",
  "Fayl hajmi 4MB dan oshmasligi kerak", "Fayl mazmuni .pdf formatiga mos emas".
- Fayl `GET /api/files/{filename}` orqali ochiq beriladi (rasm va PDF — `inline`, Word/Excel — `attachment`).

---

## 5. Guruhga ko'chirish — `/api/groups/{fromId}/promote` (SA, A)

Bir guruhdagi bir nechta o'quvchini boshqa guruhga o'tkazish (yangi o'quv yili, keyingi daraja). Har o'quvchi uchun billing-v2 ko'chirish
qoidasi (`POST /api/students/{id}/transfer-group` bilan bir xil):
- eski yozilma yopiladi (`exitReasonCode = TRANSFERRED`, chiqish — bugun), yangisi ochiladi (`joinDate = date`);
- **balans to'liq ko'chadi** (`TRANSFER_OUT` / `TRANSFER_IN`; qarz bo'lsa qarz ham, `debtSince` saqlanadi);
- hisob uzluksiz: yangi yozilma eski yozilmaning **keyingi hisoblanmagan davri**dan hisoblanadi (joriy davr ikki marta olinmaydi);
- narx shartlari ko'chadi: individual narx (`monthlyPriceOverride`), chegirma %, dars narxi; individual narx bo'lmasa — **yangi kurs narxi**.

| Metod, yo'l | Tavsif |
|---|---|
| `POST /api/groups/{fromId}/promote/preview` | Hech narsa yozmaydi — o'quvchilar bo'yicha narx, balans, ogohlantirishlar |
| `POST /api/groups/{fromId}/promote` | Ko'chirish → **201**. Sarlavha `Idempotency-Key` (tavsiya: dialog ochilganda UUID, ≤ 64) |

So'rov (ikkalasida bir xil):
```json
{ "targetGroupId": 9, "studentIds": [5, 6, 7], "date": "2026-10-05", "note": "B1 → B2" }
```
| Maydon | Qoida |
|---|---|
| `targetGroupId` | Majburiy (`group.promote.targetRequired`); `fromId` bilan bir xil → 400 `group.promote.sameGroup` |
| `studentIds` | 1–200 ta (`group.promote.studentsRequired`); takrorlar olib tashlanadi; qayta ishlash tartibi — id o'sishida |
| `date` | Yangi guruhga qo'shilish sanasi, **bugun … bugun + 31** (`group.promote.date.invalid`); berilmasa — bugun |
| `note` | ≤ 500; eski yozilmaning `exitNotes` iga yoziladi |

### 5.1 Preview javobi
```json
{ "fromGroup":   { "id": 4, "name": "B1 kechki", "status": "COMPLETED", "courseName": "English B1", "maxStudents": 15, "activeStudents": 12, "freeSeats": 3 },
  "targetGroup": { "id": 9, "name": "B2 kechki", "status": "FORMING",   "courseName": "English B2", "maxStudents": 15, "activeStudents": 0,  "freeSeats": 15 },
  "date": "2026-10-05", "canApply": false,
  "warnings": [ { "code": "SOURCE_CLOSED", "blocking": false } ],
  "students": [
    { "studentId": 5, "studentName": "Ali Valiyev", "studentGroupId": 41, "paymentType": "MONTHLY",
      "oldPrice": 600000, "newPrice": 800000, "priceDiff": 200000, "balance": -600000, "debt": 600000,
      "nextBillingDate": "2026-10-15", "warnings": [ { "code": "DEBTOR", "blocking": false }, { "code": "PRICE_CHANGED", "blocking": false } ] },
    { "studentId": 7, "studentName": "Vali Karimov", "studentGroupId": 43, "paymentType": "MONTHLY",
      "oldPrice": 600000, "newPrice": 800000, "priceDiff": 200000, "balance": 0, "debt": 0,
      "nextBillingDate": "2026-10-01", "warnings": [ { "code": "FROZEN", "blocking": true }, { "code": "PRICE_CHANGED", "blocking": false } ] } ] }
```
- `oldPrice` / `newPrice` — chegirmali oylik narx (PER_LESSON da — chegirmali dars narxi). `priceDiff = newPrice − oldPrice`.
- `balance` (manfiy — qarz), `debt` — **bugungi** holat (bugungi davr hali yozilmagan bo'lsa apply paytida biroz farq qilishi mumkin). Shu summa yangi guruhga ko'chadi.
- `nextBillingDate` — yangi yozilma qaysi kundan hisoblanadi.
- `canApply = false` — kamida bitta `blocking: true` ogohlantirish bor.

| Kod | Daraja | Blok | Ma'nosi |
|---|---|---|---|
| `TARGET_CLOSED` | guruh | ✅ | Maqsad guruh `COMPLETED` yoki `CANCELLED` |
| `TARGET_FULL` | guruh | ✅ | Maqsadning faol o'quvchilari + tanlanganlar > `maxStudents` |
| `SOURCE_CLOSED` | guruh | — | Manba guruh yakunlangan/bekor (odatiy holat — ma'lumot uchun) |
| `NOT_IN_GROUP` | o'quvchi | ✅ | Manba guruhda faol yozilmasi yo'q (narx/balans maydonlari `null`) |
| `ALREADY_IN_TARGET` | o'quvchi | ✅ | Maqsad guruhda allaqachon bor |
| `FROZEN` | o'quvchi | ✅ | Yozilma muzlatilgan — avval muzlatishdan chiqaring |
| `DEBTOR` | o'quvchi | — | Qarzi bor — qarz yangi guruhga ko'chadi |
| `TRIAL` | o'quvchi | — | Sinov davrida — sinov yangi guruhda davom etadi |
| `PRICE_CHANGED` | o'quvchi | — | Yangi guruhda narx boshqa |

### 5.2 `POST …/promote`
- So'rov qayta tekshiriladi (maqsad guruh qulf ostida). Bloklovchi ogohlantirish bo'lsa → **409** `group.promote.blocked`,
  `data = { "group": ["TARGET_FULL"], "students": [ { "studentId": 7, "codes": ["FROZEN"] } ] }` — **hech narsa o'zgarmaydi**.
- **Hammasi yoki hech biri**: bitta o'quvchida xato (masalan billing) — butun amal bekor.
- Javob **201**:
```json
{ "batchId": 3, "fromGroupId": 4, "targetGroupId": 9, "date": "2026-10-05", "idempotentReplay": null,
  "students": [ { "studentId": 5, "studentName": "Ali Valiyev", "fromStudentGroupId": 41, "toStudentGroupId": 77, "movedBalance": -600000 } ] }
```
- **Idempotency-Key**: o'sha kalit + o'sha so'rov → **200**, `X-Idempotent-Replay: true`, `idempotentReplay: true`, avvalgi natija (ikkinchi
  ko'chirish yo'q). O'sha kalit boshqa so'rov bilan (boshqa o'quvchilar/guruh/sana) → 409 `group.promote.idempotency.mismatch`. Kalit > 64 →
  400 `group.promote.idempotency.keyTooLong`.
- Audit: `action = TRANSFER`, `entityType = Group`, `entityId = fromId` (`GET /api/audit-logs/entity/Group/{id}`). Har amal
  `group_transfer_batches` ga yoziladi (kim, qachon, kimlar).
- Billing o'chiq → 503 `billing.maintenance`; parallel amal → 409 `concurrency.busy`.

---

## 6. Xato kodlari (yangi)

| Kod | HTTP |
|---|---|
| `analytics.period.invalid`, `analytics.groupBy.invalid`, `analytics.groupBy.tooFine`, `analytics.staff.roleInvalid` | 400 |
| `analytics.staff.roleForbidden` | 403 |
| `homework.group.required`, `homework.dates.invalid`, `homework.items.required`, `homework.status.required`, `homework.status.invalid`, `homework.student.notInGroup`, `homework.student.duplicate`, `homework.mark.range`, `homework.mark.notSubmitted`, `homework.attachment.invalid` | 400 |
| `group.promote.targetRequired`, `group.promote.studentsRequired`, `group.promote.sameGroup`, `group.promote.date.invalid`, `group.promote.idempotency.keyTooLong` | 400 |
| `group.promote.blocked` (`data.group`, `data.students`), `group.promote.idempotency.mismatch` | 409 |

## 7. Migratsiya — `V65__homework_group_transfer.sql`

V64 dan keyin, qo'lda, `crm_user` bilan (deploy-v2 §3.3). Idempotent.
- `homeworks.attachment_url`, `attachment_name`;
- `homework_submissions.status`: katta harfga, `PENDING`/noma'lum → `NOT_SUBMITTED`, default `NOT_SUBMITTED`; UNIQUE `(homework_id, student_id)`
  (yo'q bo'lsa; dublikat bo'lsa NOTICE, qo'yilmaydi);
- `group_transfer_batches` jadvali + qisman UNIQUE `idempotency_key`.

Tekshiruv: `prod-schema-check.sql` (V65 qatorlari).

# O'quvchi / ota-ona Telegram Mini App — API shartnomasi (frontend uchun)

> Bu hujjat — **amalda qurilgan** holat (branch `billing-v2`, migratsiya **V66**). Mini App (`https://webapp.adizone.uz`) faqat shu shartnoma bo'yicha ishlaydi.
> Dizayn va sabablar: [telegram-platform.md](telegram-platform.md) (§3, bosqich 2 — MVP), maket: [mockups/telegram-miniapp-mockup.html](mockups/telegram-miniapp-mockup.html) (variant 1 "Tungi" / 2 "Yorug'" — `Telegram.WebApp.colorScheme` / `themeParams` bo'yicha).
> Javoblar `ApiResponse {success, message, data}` ichida; xatolar `ErrorResponse {timestamp, status, error, message, code, data?}`. Mantiq uchun **`code`** ga tayaning, `message` — faqat ko'rsatish uchun.
>
> **O'zgarish (2026-10-03):** `balance.nextPaymentDate`/`nextPaymentAmount` → `balance.nextPayment {date, amount} | null` + `nextPaymentState` (`SCHEDULED | HOLD | NONE`, §3.4); davomatda `total`/`rate` faqat belgilangan darslar, yangi `unmarked` (+ `unmarkedLessons`) — §3.3.

## 0. Umumiy

| Mavzu | Qoida |
|---|---|
| Baza URL | `https://api.adizone.uz` |
| CORS | `https://webapp.adizone.uz` (va dev: `http://localhost:5173`, `:5174`, `:3000`) |
| Avtorizatsiya | `Authorization: Bearer <app token>`. Cookie yo'q. Admin tokeni bu yerda ishlamaydi (401), app tokeni admin API'da ishlamaydi (401) |
| Til | Faqat o'zbekcha (MVP). Server matnlari (`message`) `Accept-Language: uz` |
| Sana | `"2026-09-15"` (Asia/Tashkent). Vaqt — `"18:30"` (matn). `linkedAt` — `"2026-09-15T12:00:00"` |
| Summalar | UZS, JSON son (`700000.00`). `balance < 0` — qarz. Formatlash ("700 000 UZS") frontendda |
| Kodlar | Hafta kuni `MONDAY…SUNDAY`, oy `YYYY-MM` — o'zbekchaga frontend o'giradi |
| `studentId` | Har ekranda ixtiyoriy query. Berilmasa — `profile.defaultStudentId`. Faqat `profile.students[].id` dan biri; boshqasi → **403** `app.student.forbidden` |
| To'lov | **Onlayn to'lov yo'q** (MVP). "To'lash" (MainButton) yashirin; o'rniga "Qanday to'lash mumkin" (`howToPay`) |

---

## 1. Kirish oqimi

```
Mini App ochiladi (bot /start dagi "Ilovani ochish" inline tugmasi yoki Menu button)
  Telegram.WebApp.ready(); expand()
  POST /api/app/auth { initData: Telegram.WebApp.initData }
     ├─ 200  → token (30 daq) + profile           → bosh sahifa
     ├─ 403 app.notLinked                          → "Hisobni ulash" ekrani (maket)
     │     "Raqamni ulashish" → Telegram.WebApp.requestContact(cb)
     │       cb(true)  → kontakt BOTGA ketadi (server bog'laydi) → 1 s oraliq bilan POST /api/app/auth, ≤ 10 marta
     │       cb(false) → ekranda qolish
     │     bog'lanmadi (raqam topilmadi) — bot chatida xabar; ekranda: yordam telefoni (data.supportPhone)
     ├─ 401 app.auth.initDataExpired               → "Ilovani qayta oching" (Telegram.WebApp.close())
     ├─ 401 app.auth.invalidInitData               → shu
     └─ 503 app.auth.notConfigured                 → "Xizmat vaqtincha ishlamayapti"

Har so'rovda 401 (token eskirdi / uzildi) → bir marta qayta POST /api/app/auth (o'sha initData bilan), so'ng so'rovni takrorlash.
initData 1 soatdan eski bo'lsa auth ham 401 initDataExpired beradi → "Ilovani qayta oching".
```

**Muhim:**
- `initData` ni **o'zgartirmasdan** yuboring (`Telegram.WebApp.initData` satri aynan). `initDataUnsafe` ga tayanmang.
- Klaviatura (`KeyboardButton`) dan ochilgan Mini App'da `initData` **bo'sh** bo'ladi — shuning uchun bot "Ilovani ochish" ni **inline** tugma qilib yuboradi.
- `requestContact` javobi (`responseUnsafe`) ishonchsiz — server faqat bot orqali kelgan kontaktni qabul qiladi (`contact.user_id == from.id`).
- Token **localStorage'ga yozmang** — xotirada (o'zgaruvchida) saqlang; Mini App yopilsa yo'qolishi normal.

### Bot (minimal)
| Xabar | Bot javobi |
|---|---|
| `/start` (yoki boshqa matn), ulanmagan | 1) "Xush kelibsiz" + inline **Ilovani ochish** (`web_app`); 2) reply klaviatura **Telefon raqamni yuborish** (`request_contact`) |
| `/start`, ulangan | "Hisobingiz ulangan" + **Ilovani ochish** |
| kontakt (o'z raqami) | topildi → "✅ Hisob ulandi. Farzand(lar): …" + **Ilovani ochish**; topilmadi → "Raqam topilmadi … +998 77 337 32 33"; 24 soatda 5 ta "topilmadi" dan keyin → "Urinishlar soni oshib ketdi" |
| kontakt (birovniki / qo'lda yozilgan) | "Faqat o'zingizning raqamingizni yuboring" |
| `/stop` | uzish (Mini App tokeni darhol yaroqsiz) |

### Kim nimani ko'radi (telefon bo'yicha moslash)
| Telefon qayerda | `kind` | `students[].relation` |
|---|---|---|
| Faol ota-ona kartasi (`parents.phone`) | PARENT | barcha farzandlari (ARCHIVED dan tashqari) — `PARENT` |
| O'quvchi kartasidagi "ota-ona telefoni" | PARENT | o'sha o'quvchilar — `PARENT` |
| O'quvchining o'z telefoni, bitta o'quvchi | STUDENT | `SELF` |
| O'quvchining o'z telefoni, bir nechta o'quvchi (aka-uka) | PARENT | hammasi `PARENT` |
| Ham o'quvchi, ham boshqa o'quvchining ota-onasi | PARENT | o'zi `SELF` + farzandlar `PARENT` |

Ota-ona **hamma narsani** ko'radi (jadval, davomat, to'lov). Bir nechta o'quvchi bo'lsa — **farzand almashtirgich** (`profile.students[]`), tanlangan `id` har so'rovda `studentId`.
Ro'yxat har `POST /api/app/auth` da (≤ 30 daqiqada bir) CRM bo'yicha **qayta quriladi**: yangi farzand qo'shilsa — paydo bo'ladi; o'quvchi arxivlansa — yo'qoladi; birortasi qolmasa — 403 `app.notLinked`.

---

## 2. `POST /api/app/auth` (ochiq)

```json
// so'rov
{ "initData": "query_id=AAH...&user=%7B%22id%22%3A123...%7D&auth_date=1758000000&hash=9f2c..." }
```
```json
// 200
{ "success": true, "data": {
  "token": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 1800,
  "profile": {
    "identityId": 12, "kind": "PARENT", "telegramFirstName": "Dilnoza",
    "phoneMasked": "+998 90 *** ** 67", "defaultStudentId": 101,
    "students": [
      { "id": 101, "firstName": "Ali",  "lastName": "Karimov", "relation": "PARENT",
        "groups": [ { "id": 7, "name": "Turk tili B1", "courseName": "Turk tili" } ] },
      { "id": 102, "firstName": "Vali", "lastName": "Karimov", "relation": "PARENT", "groups": [] }
    ],
    "support": { "phone": "+998 77 337 32 33", "address": "Toshkent sh., Chilonzor t., ..." }
  } } }
```
```json
// 403
{ "status": 403, "error": "Forbidden", "code": "app.notLinked",
  "message": "Hisob ulanmagan — botga telefon raqamingizni yuboring",
  "data": { "supportPhone": "+998 77 337 32 33" } }
```

`GET /api/app/me` — xuddi shu `profile` (token bilan; tokenni yangilamaydi).

---

## 3. Ekranlar (`ROLE_APP` token)

### 3.1 Bosh sahifa — `GET /api/app/home?studentId`
```json
{ "student": { "id": 101, "firstName": "Dilnoza", "lastName": "Karimova", "initials": "DK" },
  "groups": [ { "id": 7, "name": "Turk tili B1", "courseName": "Turk tili", "teacherName": "Madina Rahimova",
                "weekdays": ["MONDAY","WEDNESDAY","FRIDAY"], "startTime": "18:30", "endTime": "20:00",
                "room": "5-xona", "format": "OFFLINE", "status": "ACTIVE" } ],
  "nextLesson": { "date": "2026-09-25", "groupId": 7, "groupName": "Turk tili B1", "startTime": "18:30",
                  "endTime": "20:00", "room": "5-xona", "status": "PLANNED", "teacherName": "Madina Rahimova",
                  "substituteTeacherName": null, "movedTo": null, "movedFrom": null, "note": null,
                  "startsInMinutes": 390 },
  "balance": { "balance": 0.00, "debt": 0.00, "status": "PAID", "debtSince": null,
               "nextPayment": { "date": "2026-10-01", "amount": 700000.00 }, "nextPaymentState": "SCHEDULED" },
  "attendance": { "month": "2026-09", "present": 7, "late": 1, "absent": 1, "excused": 1, "total": 10, "rate": 80,
                  "unmarked": 1 } }
```
- `nextLesson` — bugundan 14 kun ichida birinchi `PLANNED`/`EXTRA` dars (bugungi tugamagan dars ham); yo'q bo'lsa `null`. `startsInMinutes` — dars boshlanishigacha (boshlangan bo'lsa 0): "3 soatdan keyin".
- `groups[].status`: `ACTIVE` | `FROZEN` (muzlatilgan) | `TRIAL` (sinov). `format`: `ONLINE` | `OFFLINE` | `null`.
- `balance` — 3.4 dagi `balance` bilan aynan bir xil (keyingi to'lov qoidalari o'sha yerda).
- `attendance` — joriy oy, 3.3 dagi qoidalar: `total`/`rate` faqat **belgilangan** darslar bo'yicha, `unmarked` alohida.

### 3.2 Jadval — `GET /api/app/schedule?studentId&from&to`
`from`, `to` — ixtiyoriy (standart: joriy hafta dushanba…yakshanba); `from ≤ to`, oraliq **≤ 62 kun** → aks holda 400 `app.schedule.range.invalid`.
```json
{ "from": "2026-09-14", "to": "2026-09-20", "address": "Toshkent sh., Chilonzor t., ...",
  "groups": [ /* 3.1 dagi groups bilan bir xil */ ],
  "lessons": [
    { "date": "2026-09-14", "status": "PLANNED", "startTime": "18:30", "endTime": "20:00", "room": "5-xona",
      "groupId": 7, "groupName": "Turk tili B1", "teacherName": "Madina Rahimova", "substituteTeacherName": "Aziza Karimova", ... },
    { "date": "2026-09-16", "status": "CANCELLED", "note": null, ... },
    { "date": "2026-09-18", "status": "MOVED", "movedTo": "2026-09-19", ... },
    { "date": "2026-09-19", "status": "EXTRA", "movedFrom": "2026-09-18", ... },
    { "date": "2026-09-23", "status": "CANCELLED", "note": "Mustaqillik kuni", ... } ] }
```
| `status` | Ma'nosi | Qo'shimcha maydon |
|---|---|---|
| `PLANNED` | oddiy dars | `substituteTeacherName` — o'rinbosar bo'lsa ("Bugun darsni X o'tadi") |
| `CANCELLED` | bekor qilingan yoki bayram | `note` — bayram nomi (bekor qilish sababi ko'rsatilmaydi) |
| `MOVED` | dars boshqa kunga ko'chdi (asl kun) | `movedTo` |
| `EXTRA` | qo'shimcha yoki ko'chirib kelingan dars | `movedFrom` (ko'chirilgan bo'lsa) |

Faqat o'quvchi guruhda bo'lgan kunlar (qo'shilgan sanadan) ko'rsatiladi. Saralash: sana, vaqt.

### 3.3 Davomat — `GET /api/app/attendance?studentId&month=2026-09`
`month` ixtiyoriy (standart — joriy oy); noto'g'ri → 400 `app.month.invalid`.
```json
{ "month": "2026-09",
  "counts": { "present": 7, "late": 1, "absent": 1, "excused": 1 }, "total": 10, "rate": 80,
  "unmarked": 1,
  "unmarkedLessons": [ { "date": "2026-09-14", "groupId": 7, "groupName": "Turk tili B1" } ],
  "days": [ { "date": "2026-09-02", "groupId": 7, "groupName": "Turk tili B1", "status": "PRESENT", "note": null },
            { "date": "2026-09-09", "groupId": 7, "groupName": "Turk tili B1", "status": "EXCUSED", "note": "Kasal" } ],
  "lastMissed": { "date": "2026-09-07", "weekday": "MONDAY", "groupName": "Turk tili B1" } }
```
- **`total`** — faqat **belgilangan** darslar (davomat yozuvi bor) = `present + late + absent + excused`.
- **`rate`** = (`present` + `late`) / `total` × 100 (butun). Belgilangan dars 0 bo'lsa → **`null`** ("—" ko'rsating, 0% emas).
- **`unmarked`** — dars bo'lgan, lekin o'qituvchi hali belgilamagan darslar soni; `rate` va `total` ga **kirmaydi**. Sanaladi: jadval bo'yicha `PLANNED`/`EXTRA` dars (bekor, ko'chirilgan va bayram kunlari emas), o'quvchi guruhda bo'lgan kun, **o'tgan kunlar va bugun boshlanish vaqti o'tgan** darslar. Kelajak oyi — 0. `unmarkedLessons` — ular ro'yxati (kalendarda "belgilanmagan" deb ko'rsatish uchun); `days` da ular yo'q.
- `status`: `PRESENT` (keldi), `LATE` (kechikdi), `ABSENT` (kelmadi), `EXCUSED` (sababli).
- `note` — faqat `EXCUSED` sababi; o'qituvchining ichki izohi hech qachon berilmaydi.
- `lastMissed` — bugungacha oxirgi **sababsiz** qoldirilgan dars (oydan qat'i nazar), yo'q bo'lsa `null`.

### 3.4 To'lov — `GET /api/app/payments?studentId` (faqat ko'rish)
```json
{ "balance": { "balance": -700000.00, "debt": 700000.00, "status": "OVERDUE", "debtSince": "2026-09-01",
               "nextPayment": { "date": "2026-10-01", "amount": 500000.00 }, "nextPaymentState": "SCHEDULED" },
  "enrollments": [ { "studentGroupId": 55, "groupId": 7, "groupName": "Turk tili B1", "courseName": "Turk tili",
                     "paymentType": "MONTHLY", "monthlyFee": 700000.00, "discountPercent": 0.00, "finalFee": 700000.00,
                     "balance": -700000.00, "debt": 700000.00, "status": "OVERDUE", "debtSince": "2026-09-01",
                     "nextPayment": null, "nextPaymentState": "NONE" },
                   { "studentGroupId": 61, "groupId": 9, "groupName": "Ingliz tili A2", "...": "...",
                     "balance": 0.00, "debt": 0.00, "status": "PAID", "debtSince": null,
                     "nextPayment": { "date": "2026-10-01", "amount": 500000.00 }, "nextPaymentState": "SCHEDULED" } ],
  "history": [ { "id": 9001, "date": "2026-09-01", "periodFrom": "2026-09-01", "periodTo": "2026-09-30",
                 "groupName": "Turk tili B1", "method": "CLICK", "methodLabel": "Click", "amount": 700000.00,
                 "status": "PAID", "receiptNumber": "2026-000123" },
               { "id": 8990, "date": "2026-08-05", "status": "CANCELLED", ... } ],
  "howToPay": { "onlinePayment": false, "cashierAddress": "Toshkent sh., Chilonzor t., ...",
                "supportPhone": "+998 77 337 32 33" } }
```
- Manba — billing v2 snapshot: har so'rovda ledger (`balance_transactions`) va davrlardan (`billing_periods`) **yozuvsiz** hisoblanadi (billing-v2 §4). Saqlangan `student_groups.next_payment_date` / `balance` ustunlari ishlatilmaydi (eski v1 yozuvlarda eskirgan bo'lishi mumkin). Server hech narsa yozmaydi.
- `balance.status` (umumiy): qarz bo'lsa `OVERDUE` (muddati o'tgan) / `PENDING` (kutilmoqda); qarz yo'q — `PAID`; hamma guruh muzlatilgan — `FROZEN`; hammasi sinovda — `TRIAL`; guruh yo'q — `null`.
- **Keyingi to'lov** — `nextPayment {date, amount}` faqat `nextPaymentState = "SCHEDULED"` da; aks holda `nextPayment: null`:

  | `nextPaymentState` | Qachon | UI |
  |---|---|---|
  | `SCHEDULED` | snapshot sanasi **bugun yoki keyin** | "Keyingi to'lov 01.10.2026 — 500 000 UZS" |
  | `HOLD` | yozilma billing migratsiyasida ushlab turilgan (`billing_hold`) | "To'lov jadvali tekshirilmoqda — markazga murojaat qiling" |
  | `NONE` | sana yo'q (sinov, muzlatilgan, narx yo'q, guruh yo'q) **yoki sana o'tib ketgan** | sanani ko'rsatmang. Qarz bo'lsa — `debt` va `debtSince` ("01.09.2026 dan beri qarz") |

  Umumiy `balance` da: `SCHEDULED` yozilmalar ichida eng yaqin sana va shu sanadagi summalar yig'indisi; birortasi ham `SCHEDULED` bo'lmasa — biror yozilma `HOLD` bo'lsa `HOLD`, aks holda `NONE`. Qarz holatida snapshot sanasi = `debtSince` (o'tgan) — shuning uchun qarzdorda `NONE` bo'ladi, qarz `debt` da ko'rinadi.
- `PER_LESSON` yozilmada `monthlyFee`/`finalFee` — bitta dars narxi.
- `history` — oxirgi 100 ta; `PAID` ("Qabul qilindi") va `CANCELLED` ("Bekor qilingan"). `amount` — chegirmadan keyingi summa. Davr yorlig'i ("Sentabr uchun") — `periodFrom` dan frontendda.
- `howToPay` — "Qanday to'lash mumkin": kassa manzili (`center.address`) va yordam telefoni (`center.supportPhone`, tel: havola). `onlinePayment: false` — "To'lash" tugmasi ko'rsatilmaydi.

### 3.5 Profil — `GET /api/app/profile?studentId`
```json
{ "student": { "id": 101, "fullName": "Dilnoza Karimova", "phoneMasked": "+998 90 *** ** 67", "birthDate": null },
  "enrollments": [ { "groupId": 7, "groupName": "Turk tili B1", "courseName": "Turk tili", "format": "OFFLINE",
                     "room": "5-xona", "status": "ACTIVE", "teacherName": "Madina Rahimova", "joinDate": "2026-09-01" } ],
  "link": { "kind": "PARENT", "phoneMasked": "+998 90 *** ** 12", "linkedAt": "2026-09-15T12:00:00",
            "students": [ { "id": 101, "fullName": "Dilnoza Karimova", "relation": "PARENT" } ] },
  "support": { "phone": "+998 77 337 32 33", "address": "Toshkent sh., ..." } }
```
- "Qo'ng'iroq qilish" → `tel:` `support.phone`. "Menejerga yozish" (chat) — keyingi bosqich, MVP'da yashiring.
- "Bildirishnomalar" bo'limi — keyingi bosqich (bosqich 3), MVP'da yashiring.

### 3.6 Uzish — `POST /api/app/profile/unlink`
`200 {success: true}`. Identity uziladi, **joriy token darhol 401**. Qayta ulash — botda `/start` → raqam yuborish. UI: tasdiqlash oynasi (`Telegram.WebApp.showConfirm`), so'ng "Hisobni ulash" ekrani.

---

## 4. Xato kodlari

| HTTP | `code` | Qachon | UI |
|---|---|---|---|
| 400 | (validatsiya) | `initData` bo'sh | — |
| 400 | `app.schedule.range.invalid` | `from > to` yoki > 62 kun | sana tanlovini cheklang |
| 400 | `app.month.invalid` | `month` ≠ `YYYY-MM` | — |
| 401 | `app.auth.invalidInitData` | imzo/maydon yaroqsiz, `user` yo'q, `auth_date` kelajakda | "Ilovani qayta oching" |
| 401 | `app.auth.initDataExpired` | `auth_date` 1 soatdan eski | "Ilovani qayta oching" |
| 401 | `app.auth.unauthorized` | token yo'q / eskirgan (30 daq) / uzilgan / admin tokeni | bir marta qayta `POST /api/app/auth` |
| 403 | `app.notLinked` | Telegram hisobi bog'lanmagan yoki o'quvchi qolmagan | "Hisobni ulash" ekrani; `data.supportPhone` |
| 403 | `app.student.forbidden` | `studentId` shu hisobga tegishli emas (yoki arxivlangan) | almashtirgichni qayta yuklang (`/me`) |
| 403 | `app.forbidden` | token bor, lekin ruxsat yo'q | — |
| 503 | `app.auth.notConfigured` | serverda bot tokeni yoki app JWT kaliti yo'q | "Xizmat vaqtincha ishlamayapti" |

---

## 5. Server tomoni (ops)

| Endpoint | Kim | Izoh |
|---|---|---|
| `POST /api/telegram/webhook` | Telegram | `X-Telegram-Bot-Api-Secret-Token` = `TELEGRAM_WEBHOOK_SECRET`, aks holda 401. `update_id` bo'yicha takror tashlanadi |
| `POST /api/admin/telegram/set-webhook` | SUPER_ADMIN | Tanasiz. `setWebhook(url = TELEGRAM_WEBHOOK_URL, secret_token = TELEGRAM_WEBHOOK_SECRET, allowed_updates = ["message"])`. Javob: `{url, allowedUpdates, description}`. Xatolar: 503 `telegram.notConfigured`, 400 `telegram.webhookSecret.invalid` / `telegram.webhookUrl.invalid`, 502 `telegram.setWebhook.failed` |

Env'lar — [../ops/env.md](../ops/env.md). Ishga tushirish: env'larni qo'yish → restart → V66 → SA `set-webhook` → BotFather: Mini App domeni `webapp.adizone.uz` (va ixtiyoriy Menu button).

**Hali yo'q (keyingi bosqichlar):** uy vazifasi, e'lonlar, bildirishnoma sozlamalari, bot push-xabarlari (dars/to'lov eslatmasi), chat, onlayn to'lov, CRM'dagi "Telegram ulangan / uzish" (xodim tomoni), rate limit (IP / foydalanuvchi bo'yicha).

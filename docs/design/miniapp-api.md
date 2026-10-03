# O'quvchi / ota-ona Telegram Mini App — API shartnomasi (frontend uchun)

> Bu hujjat — **amalda qurilgan** holat (branch `billing-v2`, migratsiyalar **V66, V68–V70**). Mini App (`https://webapp.adizone.uz`) faqat shu shartnoma bo'yicha ishlaydi.
> Dizayn va sabablar: [telegram-platform.md](telegram-platform.md) (§3 — bosqich 2, **§11 — bosqich 3**), maket: [mockups/telegram-miniapp-mockup.html](mockups/telegram-miniapp-mockup.html) (variant 1 "Tungi" / 2 "Yorug'" — `Telegram.WebApp.colorScheme` / `themeParams` bo'yicha).
> Javoblar `ApiResponse {success, message, data}` ichida; xatolar `ErrorResponse {timestamp, status, error, message, code, data?}`. Mantiq uchun **`code`** ga tayaning, `message` — faqat ko'rsatish uchun.
>
> **O'zgarish (2026-10-03):** `balance.nextPaymentDate`/`nextPaymentAmount` → `balance.nextPayment {date, amount} | null` + `nextPaymentState` (`SCHEDULED | HOLD | NONE`, §3.4); davomatda `total`/`rate` faqat belgilangan darslar, yangi `unmarked` (+ `unmarkedLessons`) — §3.3.
>
> **Bosqich 3 (2026-10-03):** qo'lda raqam bilan ulash (§1.1), `profile.roles` / `profile.teacher` (§2), sabab bildirish (§5), chat (§6), o'qituvchi rejimi (§7), CRM endpointlari (§8), yangi xato kodlari (§4).

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

### 1.1 Qo'lda raqam bilan ulash — `POST /api/app/link/manual` (ochiq, initData bilan)

"Hisobni ulash" ekranida ikkinchi yo'l ("Raqamni qo'lda kiritish") — Telegram raqami markazdagidan boshqa bo'lsa.

```json
// so'rov
{ "initData": "<Telegram.WebApp.initData>", "phone": "+998 90 123 45 67" }
// 200 — markaz xodimi (SA, A, SALES_HEAD) tasdiqlaydi; natija — bot xabari
{ "success": true, "message": "So'rov yuborildi, markaz tasdiqlaydi",
  "data": { "requestId": 31, "status": "PENDING", "createdAt": "2026-09-15T12:00:00" } }
```
| Javob | `code` | UI |
|---|---|---|
| 200 PENDING | — | "So'rov yuborildi. Markaz tasdiqlagach botda xabar keladi" (takror yuborish — o'sha so'rov; boshqa raqam — eskisi bekor) |
| 404 | `app.phoneNotFound` | "Bu raqam markaz bazasida topilmadi" + `support.phone` |
| 409 | `app.link.alreadyLinked` | hisob allaqachon ulangan — `POST /api/app/auth` |
| 429 | `app.link.rateLimited` | 24 soatda 5 urinish (so'rov + "topilmadi") — "Ertaga qayta urinib ko'ring" |
| 401 | `app.auth.invalidInitData` / `initDataExpired` | "Ilovani qayta oching" |

Tasdiqlangandan keyin: bot "✅ So'rovingiz tasdiqlandi" + **Ilovani ochish**; Mini App `POST /api/app/auth` — 200. Rad etilsa: bot "❌ … Sabab: …".
Topilganlar (kim ekani) javobda **oshkor qilinmaydi**.

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
| **TEACHER** rolidagi faol xodim (`users.phone` yoki o'qituvchi profili telefoni), aynan bitta | o'quvchilar bo'lmasa `TEACHER` | — (o'qituvchi rejimi, §7); o'quvchilar ham bo'lsa — ikkalasi |

Boshqa xodim rollari (admin, buxgalter, menejer) telefoni app'da **xodim rejimini bermaydi**.

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
    "identityId": 12, "kind": "PARENT", "roles": ["PARENT", "TEACHER"], "telegramFirstName": "Dilnoza",
    "phoneMasked": "+998 90 *** ** 67", "defaultStudentId": 101,
    "students": [
      { "id": 101, "firstName": "Ali",  "lastName": "Karimov", "relation": "PARENT",
        "groups": [ { "id": 7, "name": "Turk tili B1", "courseName": "Turk tili" } ] },
      { "id": 102, "firstName": "Vali", "lastName": "Karimov", "relation": "PARENT", "groups": [] }
    ],
    "teacher": { "userId": 45, "teacherId": 9, "fullName": "Madina Rahimova" },
    "support": { "phone": "+998 77 337 32 33", "address": "Toshkent sh., Chilonzor t., ..." }
  } } }
```
- `roles` — menyu uchun: `STUDENT` | `PARENT` (o'quvchilar bo'lsa) va `TEACHER` (o'qituvchi rejimi). Faqat o'qituvchi: `kind: "TEACHER"`, `students: []`, `defaultStudentId: null` — o'quvchi ekranlari (`/home` …) 403 `app.student.forbidden`, ularni ko'rsatmang.
- `teacher` — o'qituvchi rejimi bo'lsa, aks holda `null`. Huquq har so'rovda bazadan tekshiriladi (xodim bloklansa — `/api/app/teacher/**` darhol 403).
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

**Bosqich 3 kodlari:**

| HTTP | `code` | Qachon |
|---|---|---|
| 404 | `app.phoneNotFound` | qo'lda kiritilgan raqam bazada yo'q (§1.1) |
| 409 | `app.link.alreadyLinked` | qo'lda so'rov, lekin hisob allaqachon ulangan |
| 429 | `app.link.rateLimited` | 24 soatda 5 ta qo'lda urinish |
| 400 | `app.absence.dateOutOfRange` / `app.absence.noLesson` / `app.absence.lessonPassed` | sabab sanasi bugun…+14 dan tashqarida / o'sha kuni dars yo'q / bugungi dars tugagan (§5) |
| 400 | `app.absence.typeInvalid` / `app.absence.commentRequired` / `app.absence.commentTooLong` | tur noto'g'ri / `OTHER` izohsiz / izoh > 500 |
| 403 | `app.group.forbidden` | o'quvchi bu guruhda (ochiq yozilmada) o'qimaydi |
| 403 | `app.absence.forbidden` | begona bildirishni bekor qilish |
| 409 | `app.absence.duplicate` / `app.absence.notActive` | shu darsga faol bildirish bor / allaqachon bekor qilingan |
| 400 | `app.chat.targetInvalid` / `app.chat.imageOnly` / `app.chat.messageNotFound` | noma'lum manzil / rasm emas / xabar bu suhbatda yo'q (§6) |
| 403 | `app.chat.forbidden` | begona suhbat yoki o'qimaydigan o'qituvchiga yozish |
| 409 | `chat.external.closed` | suhbat yopilgan |
| 403 | `app.forbidden` | `/api/app/teacher/**` — o'qituvchi rejimi yo'q (yoki xodim bloklangan) |
| 403 | `app.teacher.profileMissing` | xodim useriga o'qituvchi profili bog'lanmagan |
| 400 | `app.attendance.future` / `app.attendance.statusInvalid` | kelajak sanasi / holat noto'g'ri (§7) |
| 403 | `attendance.locked` | o'tgan kun, amaldagi ochish ruxsati yo'q; `data.reason`: `PAST_DATE` \| `UNLOCK_EXPIRED` |
| 403 | (kodsiz, `message`) | mavjud davomat qoidalari: begona guruh, darsni boshqa o'qituvchi o'tadi (`substitution.lessonTaken`) |

---

## 5. Sabab bildirish (o'quvchi / ota-ona)

`POST /api/app/absence-notices`
```json
{ "studentId": 101, "groupId": 7, "lessonDate": "2026-09-16", "type": "ABSENT", "comment": "Kasal" }
// 200
{ "message": "Sabab o'qituvchiga yuborildi", "data": {
  "id": 55, "studentId": 101, "studentName": "Ali Karimov", "groupId": 7, "groupName": "Turk tili B1",
  "lessonDate": "2026-09-16", "type": "ABSENT", "comment": "Kasal", "status": "ACTIVE",
  "createdAt": "2026-09-15T12:00:00", "cancelledAt": null, "canCancel": true } }
```
- `type`: `ABSENT` (kelmaydi), `LATE` (kechikadi), `OTHER` (boshqa — `comment` majburiy). `comment` ≤ 500.
- Faqat `profile.students[].groups[]` dagi guruh; sana — bugun … +14 kun, **dars bor kun** (`/schedule` dagi `PLANNED`/`EXTRA`); bugungi dars tugagan bo'lsa — rad.
- Dars kunini tanlash uchun `/schedule` dan foydalaning. Bitta dars — bitta faol bildirish.
- O'qituvchi (va shu kungi o'rinbosar) app'da o'qituvchi rejimida ulangan bo'lsa — darhol bot xabari; CRM davomat ekranida o'quvchi yonida ko'rinadi. **Davomatni o'zgartirmaydi.**

`GET /api/app/absence-notices?studentId` — shu Telegram hisobi yuborganlar (yangilari oldin). `POST /api/app/absence-notices/{id}/cancel` — o'zinikini, dars kuni o'tmagan bo'lsa (`canCancel`).

---

## 6. Chat

```
GET  /api/app/chats/contacts           → [{target, userId, name, label, groups[]}]
POST /api/app/chats {target, teacherUserId?} → suhbat (bor bo'lsa — o'shasi)
GET  /api/app/chats                    → [{id, side, target, title, label, lastMessageText, lastMessageType,
                                           lastMessageAt, lastMessageMine, unread, status}]
GET  /api/app/chats/{id}/messages?before&size   (yangi → eski, size ≤ 100, standart 50)
POST /api/app/chats/{id}/messages      JSON {text}  yoki multipart: image (≤ 4MB, faqat rasm) + text (ixtiyoriy izoh)
POST /api/app/chats/{id}/read {messageId}
```
| `target` | Kim javob beradi | `label` |
|---|---|---|
| `TEACHER` (`teacherUserId` — `contacts` dan) | o'sha o'qituvchi | `ustoz` |
| `SUPPORT` ("Menejer") | umumiy navbat: barcha administrator va sotuv bo'limi xodimlari | `menejer` |
| `DIRECTOR` | direktor (SUPER_ADMIN) | `direktor` |

Xabar:
```json
{ "id": 901, "type": "TEXT", "text": "Salom!", "attachments": null, "createdAt": "2026-09-15T12:01:00",
  "mine": false, "senderName": "Madina Rahimova", "senderLabel": "ustoz", "deleted": false }
// rasm: "type": "IMAGE", "attachments": [{ "url": "/api/files/<uuid>.jpg", "name": "a.jpg",
//        "contentType": "image/jpeg", "size": 81234, "width": 1280, "height": 960 }]
```
- **Real vaqt yo'q** (MVP): ochiq chat ekranida 5 s polling (`messages` — eng oxirgi sahifa), ro'yxatda 30 s. Ilova yopiq bo'lsa — bot xabari ("💬 Madina Rahimova (ustoz): …" + **Javob berish**). Bot xabari 5 daqiqada bitta; **21:00–08:00** — 08:00 da bitta.
- `side`: `CLIENT` — o'quvchi/ota-ona sifatida; `STAFF` — o'qituvchi rejimida, ota-onaning sizga yozgan suhbati (`title` — "Ota-ona: Ali Karimov", `label: "mijoz"`). Javob yuborish bir xil endpoint orqali.
- O'qilmagan: `unread`; ko'rilganda `read` (eng oxirgi `messageId`). Xodimning username, telefoni, rol kodi berilmaydi.
- `status: "CLOSED"` — yozib bo'lmaydi (409 `chat.external.closed`).

---

## 7. O'qituvchi rejimi (`roles` da `TEACHER`)

`ROLE_APP_TEACHER` kerak — aks holda 403 `app.forbidden`.

`GET /api/app/teacher/today?date` (standart — bugun)
```json
{ "date": "2026-09-16", "lessons": [
  { "groupId": 7, "groupName": "Turk tili B1", "date": "2026-09-16", "startTime": "18:30", "endTime": "20:00",
    "room": "5-xona", "status": "PLANNED", "role": "ORIGINAL", "substituteTeacherName": null,
    "canMark": true, "marked": 0, "total": 12, "notices": 1 } ] }
```
- `role`: `ORIGINAL` — o'z guruhi; `SUBSTITUTE` — shu kuni o'rinbosar. `substituteTeacherName` bor bo'lsa — darsni boshqa o'qituvchi o'tadi (`canMark: false`).

`GET /api/app/teacher/attendance/{groupId}?date`
```json
{ "groupId": 7, "groupName": "Turk tili B1", "date": "2026-09-16", "editable": true, "lockReason": null,
  "unlockRequest": null,
  "students": [ { "studentId": 101, "fullName": "Ali Karimov", "status": null, "notes": null, "excused": null,
                  "excuseReason": null, "notice": { "id": 55, "type": "ABSENT", "comment": "Kasal",
                                                    "createdAt": "2026-09-15T12:00:00" } } ] }
```
`lockReason`: `null` | `FUTURE` | `PAST_DATE` (ochish so'rovi kerak) | `UNLOCK_EXPIRED` | `SUBSTITUTED` | `NO_LESSON`.

`PUT /api/app/teacher/attendance/{groupId}?date` — `{ "items": [ {"studentId": 101, "status": "ABSENT", "notes": "Kasal", "excused": true, "excuseReason": "Kasal"} ] }` → yangilangan `attendance` ko'rinishi.
- `status`: `PRESENT` | `LATE` | `ABSENT` | `EXCUSED`; `ABSENT`/`LATE`/`EXCUSED` — sabab (`notes` yoki `excuseReason`) majburiy (CRM qoidasi, 400).
- Bugun (Asia/Tashkent, 23:59 gacha) — ochiq. O'tgan kun — tasdiqlangan ochish ruxsati amal qilsa (48 soat), aks holda **403 `attendance.locked`**. Kelajak — 400.
- Faqat shu guruh o'quvchilari (aks holda 403 `app.student.forbidden`); belgilash CRM'dagi bilan bir xil (audit, to'lov, ota-onaga xabar).

`POST /api/app/teacher/unlock-requests` `{groupId, date, note}` → `{id, status: "PENDING", note, requestedAt, reviewedAt, expiresAt}`; `GET /api/app/teacher/unlock-requests?groupId&date` — o'z so'rovlari. Tasdiqlash — CRM'da (SA, A).

Chatlar — §6 (`side: STAFF`).

---

## 8. CRM endpointlari (xodim JWT, admin panel uchun)

| Endpoint | Rollar | Izoh |
|---|---|---|
| `GET /api/app-link-requests?status` | SA, A, SH | `status`: `PENDING` (standart, eskilari oldin) \| `APPROVED` \| `REJECTED` \| `CANCELLED` \| `ALL`. Qator: `{id, status, phone, telegramUserId, telegramUsername, firstName, matchSummary, createdAt, decidedAt, decidedByName, rejectReason, identityId}` |
| `POST /api/app-link-requests/{id}/approve` | SA, A, SH | telefon bo'yicha qayta moslash → identity ulanadi, bot xabari. 409 `appLinkRequest.notPending` / `appLinkRequest.noMatch` |
| `POST /api/app-link-requests/{id}/reject` `{reason}` | SA, A, SH | `reason` majburiy (≤ 500) → 400 `appLinkRequest.reason.required` |
| `GET /api/absence-notices?groupId&date` | SA, A, T | T — o'z guruhi yoki shu kungi o'rinbosar (aks holda 403). Faol bildirishlar: `{id, studentId, studentName, groupId, groupName, lessonDate, type, comment, status, submittedAs, createdAt}` |
| `GET /api/attendance/group/{id}?date` | (o'zgarmagan) | har qatorda `absenceNotice` (yuqoridagi shakl) yoki `null` |
| `GET /api/chat/conversations` va boshqa `/api/chat/**`, STOMP | (o'zgarmagan) | `type: "EXTERNAL"` suhbatlar: `title` — "Ota-ona: …", `externalTarget`, `externalStatus`; app xabarida `senderType: "APP"`, `senderId: null`, `senderAppIdentityId`. Xodim javobi — mavjud `/app/chat.send` |

---

## 9. Server tomoni (ops)

| Endpoint | Kim | Izoh |
|---|---|---|
| `POST /api/telegram/webhook` | Telegram | `X-Telegram-Bot-Api-Secret-Token` = `TELEGRAM_WEBHOOK_SECRET`, aks holda 401. `update_id` bo'yicha takror tashlanadi |
| `POST /api/admin/telegram/set-webhook` | SUPER_ADMIN | Tanasiz. `setWebhook(url = TELEGRAM_WEBHOOK_URL, secret_token = TELEGRAM_WEBHOOK_SECRET, allowed_updates = ["message"])`. Javob: `{url, allowedUpdates, description}`. Xatolar: 503 `telegram.notConfigured`, 400 `telegram.webhookSecret.invalid` / `telegram.webhookUrl.invalid`, 502 `telegram.setWebhook.failed` |

Env'lar — [../ops/env.md](../ops/env.md). Ishga tushirish: env'larni qo'yish → restart → V66 (V68–V70) → SA `set-webhook` → BotFather: Mini App domeni `webapp.adizone.uz` (va ixtiyoriy Menu button).

**Bot xabarlari navbati** (`telegram_outbox`, §11.5): 5 soniyada yuboriladi (`telegram.outbox.enabled`, default `true`; bot tokeni bo'lmasa ishlamaydi). Xato — 1 / 5 / 30 / 120 daqiqadan keyin qayta, 5-urinishda `FAILED`. Yuborilgan/xato qatorlar 30 kundan keyin o'chiriladi.

**Hali yo'q (keyingi bosqichlar):** uy vazifasi, e'lonlar, bildirishnoma sozlamalari, dars/to'lov eslatmasi push'lari, app uchun STOMP (real vaqt), suhbatni yopish endpointi, onlayn to'lov, CRM'dagi "Telegram ulangan / uzish" (xodim tomoni), rate limit (IP bo'yicha).

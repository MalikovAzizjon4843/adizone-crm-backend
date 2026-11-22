# "Adizone Qo'ng'iroqlar" — backend va API shartnomasi

> **Kimga:** Android (Kotlin) ilovani yozadigan sessiya — u **faqat shu hujjatga** tayanadi; CRM frontend (lid / o'quvchi
> kartasidagi qo'ng'iroqlar, statistika, qurilmalar). Holat — **amalda qurilgan** (branch `billing-v2`, migratsiya
> **V90**, kod: `src/main/java/com/crm/calls/`).
>
> Javoblar `ApiResponse {success, message, data}` ichida; xatolar
> `ErrorResponse {timestamp, status, error, message, code, data?}`. Mantiq uchun **`code`** ga tayaning, `message` —
> faqat ko'rsatish uchun (til — `Accept-Language: uz | ru | en`, standart `uz`).

## 0. Maqsad va umumiy oqim

Xodim (SUPER_ADMIN, ADMIN, SALES_HEAD, SALES_MANAGER) o'z Android telefonida (o'z SIM raqami) ilovani o'rnatadi. Ilova:

1. telefon qo'ng'iroqlar jurnalini (CallLog) CRM'ga sinxron qiladi → qo'ng'iroq lid / o'quvchi / ota-onaga bog'lanadi,
   notanish raqamdan kiruvchi yoki javobsiz qo'ng'iroq **avtomatik lid** bo'ladi;
2. telefon jiringlaganda **CRM kartasini** ko'rsatadi (kim, lid bosqichi, guruhi, qarzdorligi, oxirgi izoh, ochiq vazifa);
3. qo'ng'iroqdan keyin **natija** oynasini ochadi → natija lid timeline'iga, bosqich va keyingi vazifa CRM'ga yoziladi;
4. qo'ng'iroq **yozuvini** (telefonning o'z yozuv funksiyasi saqlagan fayl) yuklaydi;
5. CRM'dagi "📞 Telefonimdan qo'ng'iroq qilish" tugmasidan kelgan **so'rovni** oladi va raqamni teradi.

Ekranlar (dizayn): kirish → ruxsatlar → qo'ng'iroqlar ro'yxati → kiruvchi qo'ng'iroqda CRM kartasi (overlay) →
qo'ng'iroqdan keyingi natija → CRM'dan qo'ng'iroq so'rovi; CRM — lid / o'quvchi kartasida "Qo'ng'iroqlar" bo'limi.

| Mavzu | Qoida |
|---|---|
| Baza URL | `https://api.adizone.uz` |
| Ilova avtorizatsiyasi | `X-Device-Token: cd_…` (§3). Admin JWT ilova yo'llarida (`/api/calls/app/**`) ishlamaydi — 401; qurilma tokeni CRM yo'llarida ishlamaydi — 401 |
| Versiya sarlavhasi | Har so'rovda `X-App-Version: 1.0.3` (tavsiya). `min-supported` dan past → **426** `calls.app.upgradeRequired` |
| Vaqt (ilova → server) | `startedAt` — ISO-8601 **zona bilan**: `Instant.ofEpochMilli(CallLog.DATE).toString()` → `"2026-11-21T05:12:00Z"` |
| Vaqt (server → ilova) | `LocalDateTime` — **Toshkent vaqti, zonasiz**: `"2026-11-21T10:12:00"` |
| Telefon | Server `+998XXXXXXXXX` ga keltiradi (`+998 90 123-45-67`, `901234567`, `8 90 123 45 67` — hammasi bitta). Xorijiy raqam — `+` va raqamlar |
| Sahifalash | `PageResponse {content, pageNumber, pageSize, totalElements, totalPages, last}`, `size` ≤ 100 |

---

## 1. Ma'lumot modeli (V90, V91)

| Jadval | Asosiy ustunlar | Qoidalar |
|---|---|---|
| `call_devices` | `user_id`, `device_uid`, `model`, `android_version`, `app_version`, `token_hash` (SHA-256 hex), `fcm_token`, `perm_call_log`, `perm_recordings`, `perm_battery_opt`, `created_at`, `last_seen_at`, `last_sync_at`, `revoked_at`, `revoked_by`, **V91:** `sync_mode` (ALL \| KNOWN_ONLY), `sync_from` | Token o'zi saqlanmaydi. Sinxron sozlamasi — §3.3. Bitta xodim — **bitta faol** qurilma; bitta `device_uid` — bitta faol hisob (qisman UNIQUE) |
| `calls` | `device_id`, `user_id`, `direction` (IN \| OUT \| MISSED \| REJECTED), `phone_raw`, `phone_normalized`, `started_at`, `duration_sec`, `device_call_key`, `contact_type` (LEAD \| STUDENT \| PARENT \| UNKNOWN), `lead_id`, `student_id`, `parent_id`, `auto_lead_created`, `result_code`, `note` (≤ 2000), `result_by`, `result_at`, `created_at` | **UNIQUE (device_id, device_call_key)** — takror yuborishda dublikat yo'q. PARENT: `student_id` — farzandi, `parent_id` — `parents` da bo'lsa |
| `call_recordings` | `call_id` (UNIQUE), `path`, `mime`, `size_bytes`, `duration_sec`, `sha256`, `uploaded_at`, `deleted_at` | Retention: `path = NULL`, `deleted_at` — metama'lumot qoladi |
| `call_requests` | `user_id`, `device_id`, `phone`, `lead_id` / `student_id`, `status` (PENDING \| DELIVERED \| DIALED \| CANCELLED \| EXPIRED), `created_by`, `created_at`, `expires_at`, `delivered_at`, `dialed_at`, `closed_at` | Xodimda bitta ochiq so'rov |
| `call_exclusions` | `user_id`, `phone_normalized`, `label` | UNIQUE (user, raqam) — bu raqamlar sinxron qilinmaydi |

Qo'shimcha indekslar: `students(phone)`, `students(parent_phone)`, `parents(phone)`, `users(phone)` (lookup < 200 ms).
Lid manbasi yangi kod: **`PHONE_CALL`** (yorlig'i "Qo'ng'iroq", `source.PHONE_CALL`).

---

## 2. Ruxsatlar

| Kim | Ilova | CRM ro'yxat / yozuv | Qurilmalar |
|---|---|---|---|
| SUPER_ADMIN | ✅ | hammasi | ro'yxat, revoke |
| ADMIN | ✅ | hammasi | — (403) |
| SALES_HEAD | ✅ | hammasi | — |
| SALES_MANAGER | ✅ | **faqat o'z qo'ng'iroqlari** (boshqasiniki — 404, filtr majburan o'zi) | — |
| TEACHER, ACCOUNTANT, … | ❌ 403 `calls.role.notAllowed` | ❌ 403 | — |

Ilovadagi amallar qurilma egasi nomidan, uning roli bilan bajariladi (mavjud `LeadAccessService` qoidalari): SALES_MANAGER
boshqa operatorning lidida bosqich / vazifa o'zgartira olmaydi, kartada faqat "kimniki" ko'rinadi (§4.4). Qarz **summasi**
faqat SUPER_ADMIN / ADMIN ga.

---

## 3. Autentifikatsiya oqimi

```
1) POST /api/auth/login {username, password}
     → 200 data: {accessToken (JWT), refreshToken, userId, username, firstName, lastName, role, expiresIn}
     → 401 "Invalid username or password";  403 code auth.roleNotAllowed (STUDENT/PARENT)
2) POST /api/calls/devices/register   Authorization: Bearer <accessToken>
     {deviceUid, model, androidVersion, appVersion, fcmToken?, syncMode?, syncFrom?}
     → 201 data: {deviceId, deviceToken: "cd_…", user: {id, name, role}, syncFrom, settings,
                  device: {syncMode, syncFrom}}
3) Bundan keyin hamma so'rov: X-Device-Token: cd_…   (JWT va refresh endi kerak emas — ularni saqlamang)
```

* `deviceUid` — ilova birinchi ishga tushganda yaratadigan UUID (EncryptedSharedPreferences da), 1–100 belgi.
* `deviceToken` — **faqat shu javobda** ko'rsatiladi; serverda faqat SHA-256. Ilova uni Android Keystore bilan
  shifrlangan joyda saqlaydi. Muddati yo'q — revoke qilinmaguncha amal qiladi.
* Qayta ro'yxat: shu xodim + shu `deviceUid` → o'sha qurilma, **yangi token** (eskisi darhol yaroqsiz). Boshqa
  `deviceUid` → xodimning eski qurilmasi revoke. Shu telefonda boshqa xodim kirsa → oldingi hisob revoke.
* `syncFrom` (javob) — sinxronni shu vaqtdan davom ettiring: shu telefonda shu xodimning eng oxirgi sinxron qilingan
  qo'ng'irog'i va qurilmaning `device.syncFrom` idan **kechrog'i** (`null` — ikkalasi yo'q): qayta o'rnatishdan keyin
  CallLog'ni shu vaqtdan (−1 soat zaxira bilan) o'qing; takrorlar baribir DUPLICATE bo'ladi.
* `syncMode` / `syncFrom` (so'rov) — §3.3; faqat **yangi** qurilma qatorida qo'llanadi.
* Ilova so'rovlariga 401 kelsa, `code` bo'yicha:

| `code` | Ma'nosi | Ilova |
|---|---|---|
| `calls.device.unauthorized` | token yo'q / noto'g'ri | kirish ekrani |
| `calls.device.revoked` | SA qurilmani o'chirgan yoki boshqa joyda qayta kirilgan | "Qurilma o'chirilgan" → kirish ekrani |
| `calls.device.userInactive` | xodim bloklangan / roli o'zgargan | "Administratorga murojaat qiling" |

`settings` (register, heartbeat):
```json
{"autoLead": true, "syncMaxBatch": 200, "syncMaxAgeDays": 30, "recordingMaxBytes": 52428800,
 "recordingRetentionDays": 180, "requestPollSeconds": 15, "fcmEnabled": false,
 "latestVersion": "1.0.0", "minSupportedVersion": "1.0.0"}
```

### 3.3 Qurilma sinxron sozlamasi (V91) — `sync_mode`, `sync_from`

Xodim ilovani **shaxsiy telefoniga** o'rnatsa, oilaviy / shaxsiy qo'ng'iroqlari CRM'ga tushmasligi kerak.

| Maydon | Qiymat | Ma'nosi |
|---|---|---|
| `syncMode` | `ALL` (standart) | Hamma qo'ng'iroq (istisnolar §5 dan tashqari); notanish raqamdan IN / MISSED → avto-lid |
| | `KNOWN_ONLY` | Faqat CRM'dagi lid / o'quvchi / ota-onaga mos kelgan qo'ng'iroq saqlanadi. Mos kelmagani (xorijiy raqam ham) — `SKIPPED`, `reason: UNKNOWN_CONTACT`, bazaga **hech narsa yozilmaydi**, avto-lid **ochilmaydi** |
| `syncFrom` | vaqt (zona bilan yuboriladi, server Toshkent vaqtida saqlaydi) | Bundan oldingi qo'ng'iroq — `SKIPPED`, `reason: BEFORE_SYNC_FROM`. `syncMaxAgeDays` (30) baribir amal qiladi |

* **Birinchi kirish** (yangi qurilma qatori): `syncFrom` — ilova tanlagan vaqt (masalan "oxirgi 7 kun" → hozir − 7 kun),
  berilmasa **hozir** — ya'ni eski qo'ng'iroqlar olinmaydi. `syncMode` berilmasa `ALL`.
  Ilova kirish ekranida so'rashi tavsiya etiladi: "Bu telefon ishchimi yoki shaxsiymi?" → `ALL` / `KNOWN_ONLY`.
* Shu xodim + shu `deviceUid` qayta kirsa — saqlangan sozlama qoladi (so'rovdagisi e'tiborsiz).
* V91 dan oldingi qurilmalar: `ALL`, `syncFrom = null` (chegara yo'q) — xatti-harakat o'zgarmaydi.

`GET /api/calls/app/settings` → `{"syncMode": "ALL", "syncFrom": "2026-11-22T09:30:00"}`

`PUT /api/calls/app/settings` `{"syncMode": "KNOWN_ONLY", "syncFrom": "2026-11-15T00:00:00+05:00"}` — berilmagan
maydon o'zgarmaydi; javob — yangi holat. 400: `calls.settings.syncModeInvalid`, `calls.settings.syncFromFuture`
(> 10 daqiqa kelajakda). `syncFrom` ni orqaga surish — eski qo'ng'iroqlarni qayta yuborish imkoniyati (dublikat bo'lmaydi).
O'zgarish retroaktiv emas: avval saqlangan qo'ng'iroqlar o'chmaydi (kerak bo'lsa — §4.8).

---

## 4. Ilova API — `/api/calls/app/**` (`X-Device-Token`)

### 4.1 Kontakt moslashuvi (server qoidasi)

Kanonik raqam bo'yicha (bazadagi `+998…`, `998…`, `9 xonali` yozilishlar ham): **ochiq lid** → **o'quvchi**
(arxivlanmagan, avval ACTIVE) → **ota-ona** (`parents.phone` yoki `students.parent_phone`) → **yopiq lid** (eng oxirgisi)
→ UNKNOWN. Xorijiy raqam CRM bilan solishtirilmaydi (UNKNOWN, avtomatik lid yo'q).

### 4.2 `POST /sync` — qo'ng'iroqlarni yuborish

```json
{"calls": [
  {"key": "1532:1763701920000", "direction": "IN",  "phone": "+998 90 123-45-67",
   "startedAt": "2026-11-21T05:12:00Z", "durationSec": 75},
  {"key": "1533:1763702100000", "direction": "MISSED", "phone": "933334455",
   "startedAt": "2026-11-21T05:15:00Z", "durationSec": 0}
]}
```
→ 200
```json
{"created": 1, "duplicates": 1, "skipped": 0, "rejected": 0, "results": [
  {"key": "1532:1763701920000", "status": "DUPLICATE", "callId": 812,
   "contact": {"type": "STUDENT", "studentId": 44}},
  {"key": "1533:1763702100000", "status": "CREATED", "callId": 815,
   "contact": {"type": "LEAD", "leadId": 1207, "name": "Qo'ng'iroq +998933334455", "autoLeadCreated": true}}
]}
```

| Qoida | Tafsilot |
|---|---|
| Partiya | ≤ 200 element (`syncMaxBatch`), aks holda 400 `calls.sync.tooMany`. Bo'sh ro'yxat — 200, nollar |
| `key` | **Idempotentlik kaliti**, 1–100 belgi, shu qurilmada barqaror. Tavsiya: `CallLog._ID + ":" + CallLog.DATE` (faqat `_ID` emas — jurnal tozalansa id qayta ishlatilishi mumkin) |
| Takror | Avval kelgan `key` → `DUPLICATE`, o'sha `callId` va kontakt. Partiya ichidagi takror ham. Tarmoq uzilsa butun partiyani qayta yuboring — xavfsiz |
| `direction` | `CallLog.TYPE` → `INCOMING_TYPE`=IN, `OUTGOING_TYPE`=OUT, `MISSED_TYPE`=MISSED, `REJECTED_TYPE`/`BLOCKED_TYPE`=REJECTED. `VOICEMAIL` — yubormang |
| `durationSec` | 0–86400; MISSED / REJECTED da server 0 qiladi. Chiquvchi javobsiz — OUT, `durationSec = 0` |
| Element natijasi | `CREATED` \| `DUPLICATE` \| `SKIPPED` (bazaga yozilmaydi, `reason`) \| `REJECTED` (`reason` = xato kodi). Har element alohida — bittasining xatosi partiyani yiqitmaydi |
| SKIPPED `reason` | `EXCLUDED` (shaxsiy raqam, §5), `STAFF` (faol xodim / o'qituvchi raqami), `NO_NUMBER` (yashirin raqam), `TOO_OLD` (`syncMaxAgeDays` dan eski), `BEFORE_SYNC_FROM` (qurilma `syncFrom` idan oldin, §3.3), `UNKNOWN_CONTACT` (`KNOWN_ONLY` da CRM'ga mos kelmagan, §3.3) |
| REJECTED `reason` | `calls.sync.invalidKey`, `calls.sync.invalidDirection`, `calls.sync.invalidStartedAt` (yo'q yoki kelajakda > 10 daq), `calls.sync.invalidDuration` |
| Avtomatik lid | `app.calls.auto-lead=true` (standart), qurilma `syncMode = ALL` va **UNKNOWN** O'zbekiston raqamidan **IN / MISSED** → lid: ism `Qo'ng'iroq +998…`, manba `PHONE_CALL`, mas'ul va yaratuvchi — qurilma egasi, bosqich `NEW`, izoh "Javobsiz qo'ng'iroq — 21.11.2026 10:15 (avtomatik lid)". Vazifa majburiyligi (V79) — "Yangi lid: bog'lanish" vazifasi egaga. **Dedupe**: shu raqamli istalgan lid bo'lsa — yangisi yaratilmaydi; bir raqam uchun parallel sinxronlar navbatda |
| Qayta sinxron davri | Ilova oxirgi muvaffaqiyatli `startedAt` ni eslab qoladi va keyingi safar undan (−1 soat) o'qiydi. WorkManager: har 15 daqiqa + qo'ng'iroq tugaganda (PhoneStateListener / CallLog ContentObserver) |

Chiquvchi yoki javob berilgan kiruvchi qo'ng'iroq lid uchun **operator javobi** sifatida direktor dashboardiga tushadi
(`first_response_kind = CALL`).

### 4.3 `POST /calls/{id}/recording` — yozuv yuklash (multipart)

| Qism | Tafsilot |
|---|---|
| `file` | audio: m4a/mp4, aac, mp3, amr/awb, 3gp, ogg/oga, opus, wav, webm. ≤ 50 MB (`recordingMaxBytes`) |
| `sha256` | fayl SHA-256, 64 hex (katta-kichik harf farqi yo'q) — **majburiy** |
| `durationSec` | ixtiyoriy |

→ 200 `{callId, mime, sizeBytes, durationSec, sha256, uploadedAt, duplicate: false}`

| Holat | Javob |
|---|---|
| Server hisoblagan SHA-256 mos emas (yuklashda buzilgan) | 400 `calls.recording.sha256Mismatch` — qayta yuboring |
| Shu qo'ng'iroqqa **o'sha** hash avval yuklangan | 200, `duplicate: true` (diskka yozilmaydi) — qayta urinish xavfsiz |
| Boshqa hash bilan avval yuklangan | 409 `calls.recording.exists` (yoki retention o'chirgan — 409 `calls.recording.deleted`) |
| Qo'ng'iroq boshqa xodimniki / yo'q | 404 `calls.notFound` |
| Bo'sh / katta / audio emas / sha256 formati | 400 `calls.recording.empty` / `tooLarge` / `type` / `sha256Invalid` |
| Qo'ng'iroq retention muddatidan eski | 400 `calls.recording.tooOld` |

Ilova tomoni: yozuv faylini telefon yozuvchisi papkasidan (Samsung `Recordings/Call`, Xiaomi `MIUI/sound_recorder/call_rec`,
… — ruxsat ekranida foydalanuvchi papkani SAF bilan tanlaydi) qo'ng'iroq vaqti ±2 daqiqa va raqam bo'yicha topadi;
avval `sync` (callId olinadi), keyin yuklash. Faqat Wi-Fi da yuklash — ilova sozlamasi.

### 4.4 `GET /lookup?phone=` — kiruvchi qo'ng'iroq kartasi

Telefon jiringlaganda (READ_PHONE_STATE / CallScreeningService), < 200 ms (server keshi 30 s). Javob:

```json
{"phone": "+998901234567", "type": "STUDENT", "name": "Nodira Karimova", "restricted": false,
 "lead": null,
 "student": {"id": 44, "name": "Nodira Karimova", "status": "ACTIVE",
             "groups": [{"id": 7, "name": "IELTS 6.5 — kechki"}],
             "debtor": true, "debtDays": 12, "debtAmount": null},
 "children": null, "parent": null,
 "lastCall": {"id": 801, "startedAt": "2026-11-20T16:02:00", "direction": "OUT", "resultCode": "CALLBACK",
              "note": "Ertaga to'laydi", "by": {"id": 5, "name": "Dilshod A.", "role": "SALES_MANAGER"}},
 "openTask": {"id": 330, "title": "To'lovni so'rash", "dueAt": "2026-11-22T10:00:00",
              "assignee": {"id": 5, "name": "Dilshod A.", "role": "SALES_MANAGER"}},
 "crmPath": "/students/44"}
```

| `type` | To'ldiriladi |
|---|---|
| `LEAD` | `lead: {id, stageCode, stageName, source, owner, createdAt}`, `openTask` (lidning eng yaqin ochiq vazifasi), `crmPath = /leads?lead={id}` |
| `STUDENT` | `student` (guruhlar — faol yozilmalar; `debtor`/`debtDays` — /debtors bilan bir xil ta'rif; **`debtAmount` faqat SA/ADMIN**, qolganlarga `null`) |
| `PARENT` | `name` (ota-ona ismi yoki "<farzand> (ota-ona)"), `parent: {id, name}` (`parents` da bo'lsa), `children: [StudentCard]` |
| `UNKNOWN` | faqat `phone` (+ `lastCall`, agar avval gaplashilgan bo'lsa) |

`restricted: true` — SALES_MANAGER uchun boshqa operatorning lidi: faqat `type`, `name`, `lead.owner`; qolgani `null`.
`lastCall` — shu raqam bilan izohli eng oxirgi qo'ng'iroq (bo'lmasa — eng oxirgi qo'ng'iroq).
400 `calls.phone.invalid` — raqamda raqam yo'q.

### 4.5 `PATCH /calls/{id}/result` — qo'ng'iroqdan keyingi natija

```json
{"resultCode": "TALKED", "note": "Ingliz tili, kechki guruh", "leadStageId": 4,
 "nextTask": {"title": "Sinov darsini eslatish", "dueAt": "2026-11-23T10:00:00", "type": "CALL",
              "comment": null, "allDay": false, "assigneeId": null}}
```
→ 200 `{call: CallItem (§7.1), timelineWritten: true, stageChanged: true, taskId: 912}`

| Maydon | Qoida |
|---|---|
| `resultCode` | **majburiy**: `TALKED` (gaplashildi), `NO_ANSWER` (javob bermadi), `CALLBACK` (qayta qo'ng'iroq), `THINKING` (o'ylab ko'radi), `TRIAL_BOOKED` (sinov darsiga yozildi), `REJECTED` (rad etdi), `OTHER` |
| `note` | ≤ 2000 |
| `leadStageId` | faqat lid qo'ng'irog'ida (aks holda 400 `calls.result.notLead`); bosqichlar — `GET /api/calls/app/lead-stages` (§4.7) |
| `nextTask` | `title`, `dueAt` (kelajakda, Toshkent) majburiy; `type` CALL \| MEETING \| MESSAGE \| OTHER (standart CALL); `assigneeId` — standart qurilma egasi (SALES_MANAGER faqat o'zini) |

Natija mavjud servislar orqali, **bitta tranzaksiyada**:
* **lid** — timeline izohi `📞 Kiruvchi qo'ng'iroq · 2:35 — Gaplashildi. Ingliz tili, kechki guruh`; `leadStageId` →
  bosqich o'tishi (vazifa majburiyligi: bosqich vazifa talab qilsa va ochiq vazifa bo'lmasa — 400 `lead.task.required`,
  `nextTask` bering; yopilgan bosqichda ochiq vazifalar bekor); `nextTask` → lid vazifasi;
* **o'quvchi / ota-ona** — izoh hozircha faqat `calls.note` da (keyingi bosqichda `student_notes`); `nextTask` → o'quvchiga
  bog'langan vazifa;
* **UNKNOWN** — `nextTask` → mustaqil vazifa (tavsifida telefon).

Boshqa operatorning lidi (SALES_MANAGER): natija qo'ng'iroqda saqlanadi, timeline'ga yozilmaydi (`timelineWritten: false`);
`leadStageId` yoki `nextTask` berilsa — 403 `calls.result.leadForbidden`, hech narsa saqlanmaydi. Qayta PATCH — natija
yangilanadi va timeline'ga yangi izoh. Boshqa xodimning qo'ng'irog'i — 404.

### 4.6 CRM'dan qo'ng'iroq so'rovi — ilova tomoni

* `GET /requests/pending` → `[RequestItem]` (PENDING / DELIVERED, muddati o'tmagan; yangilari oldin).
  FCM o'chiq (`settings.fcmEnabled = false`) bo'lsa ilova ochiq paytda har `requestPollSeconds` (15 s) so'raydi.
* FCM yoqilgan bo'lsa — `data` push: `{"type": "CALL_REQUEST", "requestId": "57", "phone": "+998…", "contactName": "…"}`
  (high priority, TTL = so'rov muddati). Push kelganda ham `pending` bilan tekshiring.
* `POST /requests/{id}/ack {"status": "DELIVERED" | "DIALED" | "CANCELLED"}` → `RequestItem`.
  Ilova: so'rovni ko'rsatdi → DELIVERED; foydalanuvchi "Qo'ng'iroq" bosdi va `ACTION_CALL` ochildi → DIALED; "Bekor" → CANCELLED.
  O'sha holat qayta — 200 (idempotent). PENDING→har uchchalasi; DELIVERED→DIALED|CANCELLED; yopilgan → 409
  `calls.request.invalidTransition`; muddati o'tgan → 409 `calls.request.expired`; begona — 404 `calls.request.notFound`.

`RequestItem`: `{id, userId, phone, status, lead: {id, name}|null, student: {id, name}|null, contactName, createdBy,
createdAt, expiresAt, deliveredAt, dialedAt, closedAt}`.

### 4.7 `GET /search?q=` — terish ekrani qidiruvi

Lid / o'quvchi / ota-ona — ism yoki telefon bo'yicha. `q` — kamida **2** belgi (aks holda 400 `calls.search.tooShort`),
natija ko'pi bilan **20** ta. `q` da harf bo'lmasa — telefon qidiruvi: raqamlari telefon ichida (9 dan ko'p bo'lsa oxirgi
9 tasi, ya'ni `+998 90 123…` ham, `90123…` ham topadi). Ism — registrsiz, qism bo'yicha.

```json
[{"type": "LEAD", "leadId": 1207, "name": "Jasur Toshmatov", "phone": "+998901234567", "subtitle": "Bog'lanildi",
  "restricted": false, "owner": {"id": 7, "name": "Dilnoza K.", "role": "SALES_MANAGER"}},
 {"type": "LEAD", "name": "Jasur Aliyev", "restricted": true, "owner": {"id": 9, "name": "Aziz M.", "role": "SALES_MANAGER"}},
 {"type": "STUDENT", "studentId": 44, "name": "Jasur Karimov", "phone": "+998935556677", "subtitle": "ACTIVE",
  "restricted": false},
 {"type": "PARENT", "studentId": 44, "parentId": 12, "name": "Malika Karimova", "phone": "+998977001122",
  "subtitle": "Jasur Karimov", "restricted": false}]
```

* Ruxsat — `lookup` bilan bir xil: SALES_MANAGER boshqa operatorning lidini faqat "kimniki" darajasida ko'radi
  (`restricted: true` — `leadId`, `phone`, `subtitle` yo'q). Arxivlangan o'quvchi chiqmaydi.
* Tartib: ochiq lidlar → o'quvchilar → ota-onalar (`parents` va `students.parent_phone`) → yopiq lidlar.
* Ota-ona `parents` jadvalida bo'lmasa (faqat `students.parent_phone`) — `parentId` yo'q, nom "Farzand (ota-ona)".

### 4.8 O'z qo'ng'iroqlari, yozuvi, o'chirish

| Endpoint | Tafsilot |
|---|---|
| `GET /calls?recorded&from&to&page&size` | Qurilma egasining serverdagi qo'ng'iroqlari, yangidan eskiga, `PageResponse<CallItem>` (CRM `GET /api/calls` bilan bir xil shakl). `recorded=true` — yozuvi mavjud (retention o'chirmagan), `false` — yozuvsiz, yo'q — hammasi. `from`/`to` — `yyyy-MM-dd` (Toshkent), `size` ≤ 100 |
| `GET /calls/{id}/recording` | Yozuv oqimi, `Range` → **206**. Faqat o'z qo'ng'irog'i — boshqasiniki **404** `calls.notFound`; yozuv yo'q — 404 `calls.recording.notFound`; retention o'chirgan — **410** `calls.recording.deleted` |
| `DELETE /calls/{id}` | Noto'g'ri yuborilgan (shaxsiy) qo'ng'iroq — **yozuvi bilan** (fayl diskdan COMMIT dan keyin). Faqat o'ziniki (**404**), serverga tushganidan (`created_at`) **7 kun** ichida (**409** `calls.delete.tooOld`) |

`DELETE /calls/{id}` → 200
```json
{"callId": 815, "recordingDeleted": true, "leadId": 1207, "leadDeleted": true}
```
* Qo'ng'iroq **avto-lid** ochgan bo'lsa (`autoLeadCreated`) va lidda boshqa faollik bo'lmasa — lid ham butunlay o'chadi
  (`LeadHardDeleteService` — `DELETE /api/leads/{id}` bilan bir xil mantiq va audit `LEAD_DELETED`, `mode: CALL_APP`).
* **Faollik** (lid qoladi, `leadKeptReason: "HAS_ACTIVITY"`): shu lidga boshqa qo'ng'iroq (istalgan xodim), izoh,
  eslatma, bosqich tarixi, qayta biriktirish, CRM so'rovi, qo'lda yaratilgan yoki yopilgan vazifa (avtomatik
  "Yangi lid: bog'lanish" ochiq turgani — faollik emas), bosqich / ism / summa o'zgargan.
* Boshqa sabablar: `NOT_AUTO_LEAD` (qo'ng'iroq mavjud lidga bog'langan), `lead.delete.*` (konvert / to'lov — lid qoladi).
  Lidga bog'lanmagan qo'ng'iroqda `leadId` va `leadKeptReason` yo'q.

### 4.9 Boshqa

| Endpoint | Tafsilot |
|---|---|
| `GET /exclusions` | `[{phone, label}]` |
| `PUT /exclusions` `{items: [{phone, label?}]}` | Butun ro'yxatni almashtiradi, ≤ 500; raqam normallashtiriladi, takrorlar birlashadi. 400 `calls.exclusions.phoneInvalid` / `tooMany`. Retroaktiv emas |
| `GET /lead-stages` | `[{id, code, nameUz, nameRu, nameEn, color, sortOrder, kind (OPEN \| CONVERTED \| REJECTED …), requiresAmount, requiresTask, isActive, …}]` — `GET /api/lead-stages` bilan bir xil. Natija ekranida faqat `isActive` lari; `requiresTask = true` bosqich tanlansa va lidda ochiq vazifa bo'lmasa `nextTask` so'rang. Kunda bir marta keshlang |
| `GET /version` | **tokensiz**: `{latest, minSupported, apkUrl}` (env). Ilova ishga tushganda va kuniga bir marta |
| `POST /heartbeat` `{appVersion, androidVersion, fcmToken, permissions: {callLog, recordingsFolder, batteryOptimizationIgnored}}` | Har 6 soatda va ilova ochilganda. → `{serverTime, pendingRequests, updateAvailable, upgradeRequired, settings}`. SA qurilmalar ro'yxatida ruxsatlar holati ko'rinadi |
| `POST /logout` | Qurilma o'zini revoke qiladi; token darhol yaroqsiz |

---

## 5. Ruxsatlar (Android) va maxfiylik

| Ruxsat | Nima uchun | Yo'q bo'lsa |
|---|---|---|
| `READ_CALL_LOG` | sinxron | ilova ishlamaydi (ruxsat ekrani) |
| `READ_PHONE_STATE` (+ `READ_CALL_LOG` raqam uchun) | kiruvchi qo'ng'iroqda karta | karta yo'q, sinxron ishlaydi |
| `CALL_PHONE` | CRM so'rovida raqamni terish (`ACTION_CALL`) | `ACTION_DIAL` (foydalanuvchi o'zi bosadi) |
| `SYSTEM_ALERT_WINDOW` | karta overlay | bildirishnoma ko'rinishida |
| `POST_NOTIFICATIONS` | natija eslatmasi, so'rov | — |
| Yozuvlar papkasi (SAF tree URI) | yozuv yuklash | yozuvsiz |
| Batareya optimizatsiyasidan chiqarish | fon sinxroni | sinxron kechikadi |

Ilova Google Play'ga emas, APK (`apkUrl`) orqali tarqatiladi — `READ_CALL_LOG` Play siyosatiga to'g'ri kelmaydi.
**Shaxsiy raqamlar** (`/exclusions`) va **xodim raqamlari** (faol `users.phone`, faol o'qituvchi `teachers.phone`) serverga
yetganda tashlanadi va hech qayerda saqlanmaydi; ilova exclusion raqamlarni yubormasligi ham tavsiya etiladi (server baribir
tekshiradi). Yashirin raqam — saqlanmaydi.

---

## 6. CRM'dan qo'ng'iroq so'rovi (CRM API)

| Endpoint | Rol | Tafsilot |
|---|---|---|
| `POST /api/calls/requests` `{phone?, leadId?, studentId?, userId?}` | SA, A, SH, SM | **201** `RequestItem` (PENDING, `expiresAt = now + 120 s`). `phone` berilmasa — lid / o'quvchi telefonidan. `userId` — kimning telefonida (standart o'zi; boshqa xodim — faqat SA/A/SH, aks holda 403 `calls.request.otherUserForbidden`). Xodimning faol qurilmasi yo'q → **409 `calls.request.noDevice`** ("Xodimning faol telefoni yo'q — ilovaga kirsin"). Xodimning oldingi ochiq so'rovi CANCELLED bo'ladi. 400 `calls.phone.invalid`, `calls.request.targetSingle`; lidga ruxsat yo'q — 403 |
| `GET /api/calls/requests/{id}` | yaratuvchi, nishon xodim, SA/A/SH | Holatni kuzatish (frontend 2–3 s da so'raydi: "Telefonda ochildi ✓") |
| `POST /api/calls/requests/{id}/cancel` | o'sha | Ochiq so'rovni bekor qilish; yopilgan — 409 |

FCM: `app.calls.fcm.enabled=false` (standart). Yoqish: Firebase loyihasi + service account JSON (§9).

---

## 7. CRM API — qo'ng'iroqlar

### 7.1 Ro'yxatlar

| Endpoint | Tafsilot |
|---|---|
| `GET /api/calls?userId&from&to&direction&contactType&phone&page&size` | `PageResponse<CallItem>`, yangilari oldin. `from/to` — `startedAt` sanasi (ISO). `direction` IN\|OUT\|MISSED\|REJECTED, `contactType` LEAD\|STUDENT\|PARENT\|UNKNOWN (noto'g'ri — 400 `calls.filter.*`). SM — `userId` e'tiborsiz, faqat o'zi |
| `GET /api/calls/{id}` | `CallItem` |
| `GET /api/leads/{id}/calls?page&size` | Lid kartasi. Lidga ruxsat (`LeadAccessService`) + SM faqat o'z qo'ng'iroqlari |
| `GET /api/students/{id}/calls?page&size` | O'quvchi kartasi (o'zi va ota-ona qo'ng'iroqlari — `student_id`). TEACHER / ACCOUNTANT — 403 |

`CallItem`:
```json
{"id": 815, "direction": "MISSED", "phone": "+998933334455", "phoneRaw": "933334455",
 "startedAt": "2026-11-21T10:15:00", "durationSec": 0,
 "user": {"id": 5, "name": "Dilshod A.", "role": "SALES_MANAGER"},
 "contactType": "LEAD", "lead": {"id": 1207, "name": "Qo'ng'iroq +998933334455"}, "student": null, "parent": null,
 "autoLeadCreated": true, "resultCode": "CALLBACK", "note": "Kechqurun qayta", "resultBy": {…}, "resultAt": "…",
 "recording": {"available": true, "mime": "audio/mp4", "sizeBytes": 812345, "durationSec": 74,
               "uploadedAt": "…", "deletedAt": null}}
```
`recording: null` — yozuv yuklanmagan; `available: false` + `deletedAt` — retention o'chirgan.

### 7.2 Statistika — `GET /api/calls/stats?from&to`

Standart — oxirgi 30 kun. SM — faqat o'z qatori.
```json
{"from": "2026-10-23", "to": "2026-11-21",
 "rows": [{"userId": 5, "name": "Dilshod A.", "total": 214, "incoming": 80, "outgoing": 102, "missed": 27,
           "rejected": 5, "talkSeconds": 38211, "missedNotCalledBack": 6}],
 "totals": {"userId": null, "name": null, "total": 214, …}}
```
`talkSeconds` — IN + OUT davomiyligi. `missedNotCalledBack` — davrdagi MISSED / REJECTED **raqamlar** (xodim bo'yicha
noyob, eng oxirgi javobsiz), ulardan **keyin** shu raqam bilan hech kim (istalgan xodim) chiquvchi qo'ng'iroq qilmagan va
javob berilgan kiruvchi bo'lmagan.

### 7.3 Yozuvni tinglash

| Endpoint | Tafsilot |
|---|---|
| `GET /api/calls/{id}/recording` | JWT bilan oqim, `Accept-Ranges: bytes`; `Range: bytes=0-` → **206** (pleyerda o'tkazish). Ruxsat — §2 (SM faqat o'ziniki, aks holda 404). 404 `calls.recording.notFound`, **410** `calls.recording.deleted` |
| `GET /api/calls/{id}/recording/link` | `<audio src>` uchun imzolangan havola: `{url: "/api/calls/recordings/stream?callId=815&exp=…&sig=…", expiresAt}` — 10 daqiqa. Brauzer `<audio>` sarlavha yubora olmaydi, shuning uchun frontend shu havolani ishlatadi |
| `GET /api/calls/recordings/stream?callId&exp&sig` | Tokensiz, HMAC imzo + muddat; Range bilan. Noto'g'ri / eskirgan — 403 `calls.recording.linkInvalid` |

### 7.4 Qurilmalar (SA)

| Endpoint | Tafsilot |
|---|---|
| `GET /api/calls/devices` | `[{id, user, deviceUid, model, androidVersion, appVersion, active, createdAt, lastSeenAt, lastSyncAt, revokedAt, permissions: {callLog, recordingsFolder, batteryOptimizationIgnored}, pushRegistered}]` — faollari oldin |
| `DELETE /api/calls/devices/{id}` | Revoke (qator o'chmaydi, qo'ng'iroqlar qoladi). Ilova keyingi so'rovda 401 `calls.device.revoked` |

---

## 8. Xato kodlari (jamlanma)

| HTTP | `code` |
|---|---|
| 400 | `calls.device.uidInvalid`, `calls.phone.invalid`, `calls.settings.syncModeInvalid`, `calls.settings.syncFromFuture`, `calls.search.tooShort`, `calls.sync.tooMany`, `calls.recording.*` (`sha256Invalid`, `sha256Mismatch`, `empty`, `tooLarge`, `type`, `duration`, `tooOld`), `calls.result.*` (`codeInvalid`, `noteTooLong`, `notLead`, `stageInvalid`, `taskInvalid`), `calls.request.targetSingle`, `calls.request.ackInvalid`, `calls.exclusions.*`, `calls.filter.*`, `lead.task.required`, `task.dueAt.future` |
| 401 | `calls.device.unauthorized`, `calls.device.revoked`, `calls.device.userInactive` |
| 403 | `calls.role.notAllowed`, `calls.forbidden`, `calls.result.leadForbidden`, `calls.request.otherUserForbidden`, `calls.recording.linkInvalid` |
| 404 | `calls.notFound`, `calls.recording.notFound`, `calls.request.notFound` |
| 409 | `calls.delete.tooOld`, `calls.recording.exists`, `calls.recording.deleted`, `calls.request.noDevice`, `calls.request.invalidTransition`, `calls.request.expired` |
| 410 | `calls.recording.deleted` (tinglashda) |
| 426 | `calls.app.upgradeRequired` |

---

## 9. Sozlamalar (`app.calls.*`, env)

| Kalit | Env | Standart |
|---|---|---|
| `auto-lead` | `APP_CALLS_AUTO_LEAD` | `true` |
| `recording-dir` | `APP_CALLS_RECORDING_DIR` | `${app.upload.dir}/calls` = `/opt/crm/uploads/calls` |
| `recording-max-bytes` | `APP_CALLS_RECORDING_MAX_BYTES` | 52428800 (50 MB) |
| `recording-retention-days` | `APP_CALLS_RECORDING_RETENTION_DAYS` | 180 (0 — hech qachon) |
| `retention-cron` | — | `0 10 4 * * *` (Toshkent) |
| `sync-max-batch` / `sync-max-age-days` | — / `APP_CALLS_SYNC_MAX_AGE_DAYS` | 200 / 30 |
| `request-ttl-seconds` / `request-poll-seconds` | `APP_CALLS_REQUEST_TTL_SECONDS` / `APP_CALLS_REQUEST_POLL_SECONDS` | 120 / 15 |
| `lookup-cache-seconds`, `recording-link-ttl-seconds` | — | 30, 600 |
| `app.latest-version` / `app.min-supported-version` / `app.apk-url` | `APP_CALLS_APP_LATEST_VERSION` / `APP_CALLS_APP_MIN_VERSION` / `APP_CALLS_APP_APK_URL` | 1.0.0 / 1.0.0 / bo'sh |
| `fcm.enabled` / `fcm.project-id` / `fcm.credentials-file` | `APP_CALLS_FCM_ENABLED` / `APP_CALLS_FCM_PROJECT_ID` / `APP_CALLS_FCM_CREDENTIALS_FILE` | false / — / — (service account JSON, `chmod 600`, repoda emas) |

`spring.servlet.multipart.max-file-size` **50MB**, `max-request-size` **52MB** (classpath `application.yml`). Prod
`/opt/crm/application-prod.yml` da kichikroq qiymat bo'lsa — o'sha yerda ham ko'taring. Boshqa yuklashlar o'z chegarasini
servisda tekshiradi (4 MB).

---

## 10. Ops

1. **Migratsiya**: V89 dan keyin, yangi JAR'dan oldin: `psql -X -v ON_ERROR_STOP=1 -f V90__calls.sql` (idempotent).
   Tekshirish — `docs/ops/prod-schema-check.sql` (V90 bo'lagi).
2. **nginx**: hozir `client_max_body_size 10m` (server bloki). Yozuv yo'li uchun alohida:
   ```nginx
   location ~ ^/api/calls/app/calls/[0-9]+/recording$ {
       client_max_body_size 55m;
       proxy_request_buffering off;      # 50 MB ni nginx diskiga ikki marta yozmaslik
       proxy_read_timeout 120s;
       proxy_pass http://127.0.0.1:8080;  # mavjud proxy sozlamalari (Host, X-Forwarded-*) bilan bir xil
   }
   ```
   Yozuv oqimi (`/api/calls/*/recording`, `/api/calls/recordings/stream`) uchun `proxy_buffering off` tavsiya (Range).
3. **Disk**: `/opt/crm/uploads/calls/yyyy/MM/` (`crm` foydalanuvchisi yozadi). Baho: o'rtacha 1 daqiqa ≈ 0.5–1 MB (AMR/m4a);
   10 xodim × kuniga 40 yozuv × 2 daq ≈ 0.4–0.8 GB/kun → 180 kunda **70–140 GB**. `df -h /opt/crm` monitoring; joy
   kam bo'lsa `APP_CALLS_RECORDING_RETENTION_DAYS` ni kamaytiring. Zaxira nusxaga (backup) yozuvlar kiritilmasin yoki alohida.
4. **Retention**: `CallMaintenanceJob` har kecha 04:10 — qo'ng'iroqdan 180 kun o'tgan fayllar o'chadi
   (`call_recordings.path = NULL`, `deleted_at`), metama'lumot va qo'ng'iroq qoladi; muddati o'tgan so'rovlar EXPIRED.
5. **Ochiq `GET /api/files/**`** yozuvlarni bermaydi (faqat ildiz katalogdagi fayllar) — yozuvlar faqat §7.3 orqali.
6. **Bitta nusxa**: avtomatik lid dedupe'ning parallel himoyasi JVM qulfi — ilova bitta nusxada ishlaydi (hozirgi deploy).

## 11. Keyingi bosqichlar / ochiq savollar

* O'quvchi izohlari — `student_notes` jadvali (hozir `calls.note`).
* SALES_MANAGER uchun lid kartasida boshqa xodimlarning qo'ng'iroqlari ko'rinsinmi (hozir — yo'q).
* Exclusion qo'shilganda avvalgi qo'ng'iroqlarni o'chirish (hozir retroaktiv emas).
* Javobsiz qo'ng'iroq uchun avtomatik "qayta qo'ng'iroq" vazifasi / qo'ng'iroqcha (hozir faqat statistika).
* Avtomatik lid ismini kontakt kitobidan olish (ilova `contactName` yuborishi) — buyurtmachi bilan maxfiylik kelishilsin.

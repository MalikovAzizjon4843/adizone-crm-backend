# Telegram platformasi: bitta bot, uch vazifa (dizayn hujjati)

> **Holat:** loyiha (2026-10-02, `billing-v2` branch). **Bosqich 2 (Mini App MVP) qurildi — 2026-10-03, V66**; amaldagi shartnoma va hujjatdan farqlar (bitta `POST /api/app/auth`, bog'lash faqat botda, CHOOSE o'rniga hammasi bitta identity + almashtirgich, domen `webapp.adizone.uz`, env `TELEGRAM_APP_JWT_SECRET`) — [miniapp-api.md](miniapp-api.md).
> **Manba:**
> - maket: [mockups/telegram-miniapp-mockup.html](mockups/telegram-miniapp-mockup.html) — ekranlar: hisobni ulash, bosh sahifa, jadval, davomat, to'lov, profil;
> - [phase5-audit.md](../audit/phase5-audit.md) §9 (chat), §13 (Q1, Q11, Q13, Q16);
> - mavjud `service/TelegramService.java`, `util/PhoneUtils.java`.
>
> **Bog'liq:** [billing-v2.md](billing-v2.md) (§4 holat, §8 snapshot), [leaves-exams-contracts.md](leaves-exams-contracts.md) (ta'til, o'rinbosar, sozlamalar).
> **Yo'llar:** Java — `src/main/java/com/crm/` ga nisbatan. **TAXMIN** — kod yozilganda tasdiqlanadigan da'vo.

> ### ⚠️ Darhol: bot tokeni repozitoriyda
> `src/main/resources/application.yml:130-132` da `telegram.bot-token` qiymati **ochiq matnda** va `enabled: true`. Bu `d7603fb` commiti (2026-04-06) bilan kiritilgan va `origin/main`, `origin/billing-v2` ga push qilingan.
>
> `docs/ops/env.md` esa token `TELEGRAM_BOT_TOKEN` env'dan olinishini aytadi — kod va hujjat mos emas. Token ochiq deb hisoblanadi.
>
> **Kod ishidan oldin bajariladigan qadamlar:**
> 1. `@BotFather` → `/revoke` — yangi token olinadi.
> 2. `application.yml` → `bot-token: ${TELEGRAM_BOT_TOKEN:}`, `enabled: ${TELEGRAM_ENABLED:false}`.
> 3. Yangi token faqat `crm.env` da saqlanadi.
>
> Git tarixini tozalash (`git filter-repo`) — ixtiyoriy: token bekor qilingach eski qiymat zararsiz. Bu hujjatda token qiymati keltirilmagan.

## Mundarija
- §0 Kontekst, qarorlar, mavjud holat
- §1 Arxitektura
- §2 Xodim xabarnomalari (Q16)
- §3 O'quvchi / ota-ona Mini App (Q11, Q1)
- §4 Chat ko'prigi (Q13)
- §5 Ma'lumotlar modeli va migratsiyalar
- §6 Xavfsizlik
- §7 Hosting va frontend
- §8 Testlar strategiyasi
- §9 Bosqichlar
- §10 Ochiq savollar (taklif bilan)
- §11 Bosqich 3 — qo'lda ulash, sabab bildirish, chat ko'prigi, o'qituvchi rejimi (qarorlar, 2026-10-03)

---

## §0. Kontekst

### 0.1 Qarorlar

| # | Qaror (phase5-audit §13) | Bu hujjatdagi aksi |
|---|---|---|
| Q1 | STUDENT/PARENT **admin paneliga** kira olmaydi | O'zgarmaydi. Mini App foydalanuvchisi `users` jadvalida **emas** — alohida `app_identities`, alohida JWT, faqat `/api/app/**` (§3.3) |
| Q11 | Uy vazifasi topshirig'ini o'qituvchi belgilaydi | Mini App uy vazifasini faqat **ko'rsatadi** — topshirish yo'q |
| Q13 | Ichki chat — faqat xodimlar | Ichki chat o'zgarmaydi. Yangi, alohida turdagi **EXTERNAL** suhbat (§4): xodim ↔ muayyan o'quvchi/ota-ona, faqat xodim boshlaydi |
| Q16 | Xodim bildirishnomalari (taklif: in-app + Telegram) | §2: hodisalar katalogi, outbox; in-app qo'ng'iroqcha (S-04) shu yozuvlardan |

### 0.2 Mavjud holat (kod)

- **`TelegramService.sendMessage(chatId, html)`:**
  - sinxron `RestTemplate`, so'rov oqimi ichida chaqiriladi;
  - xatoni yutadi (`false`);
  - qayta urinish, limit va navbat yo'q.
- **Iste'molchilar:**
  - `AttendanceService` — kelmagan o'quvchining ota-onasiga;
  - `PaymentReminderService` — 10:00 da to'lov eslatmasi;
  - `DirectorDigestService` — chat id lar env'dan.
- **`parents.telegram_chat_id`:** **hech kim to'ldirmaydi.** Bot `/start` ni qabul qilmaydi, webhook yo'q (ParentService: "bot orqali bog'lanadi" — amalda yo'q). Shu sabab ota-onaga xabarlar hozir yuborilmaydi.
- **Matnlar:** `"Adizone o'quv markazi"` kodda qattiq yozilgan (S-10).
- **`PhoneUtils.canonical(raw)`:** `+998XXXXXXXXX` yoki `null`. Telegram kontaktidagi `998901234567` (plyussiz) ham to'g'ri o'giriladi.

### 0.3 Maket haqida
- Maketda 5 ta variant bor (Tungi, Yorug', Qizil, Liquid Glass, Ochiq glass). Topshiriq bo'yicha **faqat 1 (tungi) va 2 (yorug')** ishlatiladi: Telegram `themeParams` / `colorScheme` bo'yicha avtomatik (§7.2). 3–5 — dizayn eskizlari, ilovaga kirmaydi.
- Maketdagi "Yopish" (BackButton/close) va "To'lash" (MainButton) — Telegram'ning o'z elementlari.
- "To'lash" — §10 Q-1.

---

## §1. Arxitektura

```
                       ┌──────────────── Telegram ────────────────┐
  xodim / o'quvchi ───►│  @adizone_bot (bitta bot)                │
                       │   • chat: /start link_…, kontakt, /stop  │
                       │   • Menu button → Mini App (web_app)     │
                       └───────┬──────────────────────▲───────────┘
             webhook (update)  │                      │ sendMessage (Bot API)
                               ▼                      │
 ┌────────────────────────── CRM backend (api.adizone.uz) ───────────────────────────┐
 │  TelegramWebhookController  ──►  UpdateRouter ──► StaffLinkHandler                │
 │   (secret header, update_id dedupe)        └──► AppContactHandler                 │
 │                                                                                    │
 │  NotificationService.publish(event) ─► notifications (in-app) + telegram_outbox   │
 │        ▲ (biznes servislar, o'z tranzaksiyasida)        │                          │
 │        │                                     OutboxWorker (@Scheduled, SKIP LOCKED)│
 │        │                                     └─► TelegramClient (limit, retry) ────┘
 │  /api/** (admin JWT, STAFF_ROLES)          /api/app/** (app JWT, alohida filter)   │
 └────────────▲───────────────────────────────────────▲──────────────────────────────┘
              │                                       │
     admin.adizone.uz (adizone-admin)       app.adizone.uz (Mini App, yengil Vue)
```

- **Bitta bot** — xodimlar ham, o'quvchi/ota-onalar ham. Chat turi va bog'lanish kimligini aniqlaydi (§2.1, §3.2). Bot username — §10 Q-3.
- **Webhook**, polling emas:
  - `POST /api/telegram/webhook`;
  - `setWebhook` da `secret_token` beriladi; Telegram uni `X-Telegram-Bot-Api-Secret-Token` sarlavhasida qaytaradi, mos kelmasa 401;
  - `allowed_updates = ["message", "my_chat_member"]`;
  - yo'l SecurityConfig'da `permitAll` (`/api/meta/webhook` kabi), himoya — sarlavha.
- **`telegram_updates`** jadvali `update_id` bo'yicha takrorni tashlaydi: Telegram 2xx olmaguncha qayta yuboradi. Handler tez javob beradi, og'ir ish outbox'ga.
- **`TelegramClient`** (yangi) — mavjud `TelegramService.sendMessage` o'rnini bosadi:
  - `sendMessage`, `setChatMenuButton`, `setMyCommands`, `setWebhook`;
  - Telegram javobini tasniflaydi (§2.4);
  - eski `TelegramService` 1-bosqichda outbox'ga yo'naltiriladi (davomat, to'lov eslatmasi, digest).

---

## §2. Xodim xabarnomalari (Q16)

### 2.1 Botni bog'lash (deep-link)

```
CRM profil → "Telegram'ni ulash"
  POST /api/me/telegram/link-token           (STAFF)
    ← { deepLink: "https://t.me/<bot>?start=link_<token>", expiresAt }
xodim havolani bosadi → Telegram → bot: "/start link_<token>"
  webhook: token tekshiriladi → user_telegram_links yoziladi → botda: "✅ Ulandi: Dilnoza K. (ADMIN)"
CRM profil sahifasi GET /api/me/telegram → { linked: true, username, linkedAt } (polling 3 s, 2 daqiqa)
```

**Token:**
- 24 bayt `SecureRandom` → base64url, 32 belgi. `start` parametri chegarasi: ≤ 64 belgi, `[A-Za-z0-9_-]`; `link_` + 32 = 37.
- Bazada faqat **SHA-256 xeshi** saqlanadi (`telegram_link_tokens`): `user_id`, `expires_at` = +10 daqiqa, `used_at`.
- Bir martalik. Yangi token so'ralsa — eskilari bekor.

**Webhook qoidalari:**
- `chat.type = private` va `from.id = chat.id` — guruhga qo'shilgan bot orqali bog'lanmaydi.
- Token muddati o'tgan yoki ishlatilgan → botda "Havola eskirgan, CRM'dan qayta oling".
- User nofaol → rad.
- **Bir Telegram hisobi ↔ bitta xodim** (`UNIQUE telegram_user_id`); **bir xodim ↔ bitta Telegram** (`UNIQUE user_id WHERE active`). Qayta bog'lash eskisini almashtiradi va eski chatga "Hisob boshqa Telegram'ga ko'chirildi" deb yozadi.
- Bir Telegram hisobi bir vaqtda xodim ham, ota-ona ham bo'lishi mumkin (o'qituvchining farzandi o'qisa). `user_telegram_links` va `app_identities` alohida jadvallar. Bot menyusi ikkalasiga ham ishlaydi.

**Uzish:**
- CRM profildan `DELETE /api/me/telegram`;
- botda `/stop`;
- Telegram `403 bot was blocked` (§2.4) → `active = false`;
- xodim bloklansa (`UserService.setActive(false)`) — link nofaol bo'ladi, navbatdagi xabarlari `SKIPPED`.

### 2.2 Hodisalar katalogi

Har hodisa: `code`, manba (qayerda `publish` chaqiriladi), qabul qiluvchilar, ustuvorlik, dedupe kaliti.

**Umumiy qoidalar:**
- Hodisani qilgan xodimning o'ziga yuborilmaydi (masalan admin o'z ta'tilini tasdiqlasa).
- Nofaol yoki bog'lanmagan qabul qiluvchi uchun Telegram qatori yaratilmaydi; in-app yozuv baribir yoziladi.

| Kod | Manba (trigger) | Qabul qiluvchi | Ustuvorlik | Dedupe kaliti | Matn (uz, qisqa) + tugma |
|---|---|---|---|---|---|
| `LEAD_NEW` | `LeadService.create`, `MetaLeadIngestService`, `POST /api/leads/public` (commit'dan keyin) | `lead.assignedUser` faol bo'lsa — u; aks holda **sotuv bo'limi** = barcha faol `SALES_MANAGER` (§10 Q-4) | NORMAL | `lead:{id}:new` | "🆕 Yangi lid: {ism}, {manba}" + [CRM'da ochish] |
| `LEAD_ASSIGNED` | `LeadService` tayinlash (`ASSIGN`) | yangi mas'ul | NORMAL | `lead:{id}:assign:{userId}` | "👤 Sizga lid tayinlandi: {ism}" |
| `TASK_ASSIGNED` | `TaskService` yaratish/qayta tayinlash | `task.assignedTo` | NORMAL | `task:{id}:assign:{userId}` | "📋 Yangi vazifa: {sarlavha}, muddat {vaqt}" |
| `TASK_DUE_SOON` | scheduler, har 5 daqiqa: `dueAt ∈ (now, now+30m]`, OPEN | `task.assignedTo` | HIGH | `task:{id}:due:{dueAt}` | "⏰ 30 daqiqadan keyin: {sarlavha}" |
| `TASK_OVERDUE` | scheduler: `dueAt < now`, OPEN, bir marta | `task.assignedTo`; 24 soatdan keyin ham ochiq — vazifani bergan xodim | NORMAL | `task:{id}:overdue:{dueAt}` | "⚠️ Muddati o'tdi: {sarlavha}" |
| `DEBTORS_DAILY` | scheduler 09:30: kecha PENDING → OVERDUE ga o'tgan SG lar (billing-v2 §4) | barcha faol `ACCOUNTANT` (+ SA, §10 Q-5) | DIGEST | `debtors:{sana}` | "💰 Bugun {n} ta yangi qarzdor, jami qarz {summa}" + [Ro'yxat] |
| `PAYMENT_CANCELLED` | `PaymentService.cancelPayment` | `ACCOUNTANT` lar + SA (moliyaviy nazorat) | NORMAL | `payment:{id}:cancel` | "↩️ To'lov bekor qilindi: {chek}, {summa}, sabab" |
| `LEAVE_REQUESTED` | ta'til submit (leaves-exams-contracts §1) | faol SA, A (ariza beruvchidan tashqari) | NORMAL | `leave:{id}:new` | "🏖 Ta'til so'rovi: {xodim}, {sanalar}" + [Ko'rish] |
| `LEAVE_DECIDED` | approve/reject | ta'tildagi xodim (+ ariza bergan, agar boshqa bo'lsa) | NORMAL | `leave:{id}:decided` | "✅ Ta'til tasdiqlandi (haqli)" / "❌ Rad etildi: {izoh}" |
| `UNLOCK_REQUESTED` | `AttendanceUnlockRequestService.create` | faol SA, A | HIGH | `unlock:{id}:new` | "🔓 Davomatni ochish so'rovi: {o'qituvchi}, {guruh}, {sana}" |
| `UNLOCK_DECIDED` | approve/reject | so'ragan o'qituvchi | HIGH | `unlock:{id}:decided` | "🔓 Ruxsat berildi, {soat} soat amal qiladi" |
| `ATTENDANCE_MISSING` | scheduler: dars tugaganidan 30 daqiqa o'tdi, davomat yo'q (`AttendanceMetricsService` rejasi) | guruh o'qituvchisi yoki shu kungi o'rinbosar; ertasi 10:00 da ham yo'q — SA, A | NORMAL | `att:{groupId}:{sana}:{stage}` | "📝 {guruh} davomati belgilanmagan" |
| `SUBSTITUTION_ASSIGNED` | o'rinbosar tayinlandi | o'rinbosar | HIGH | `subst:{id}` | "🔁 {sana} {vaqt}: {guruh} — o'rinbosar siz" |
| `PAYROLL_APPROVED` | `PayrollService.approve` | oylik egasi | NORMAL | `payroll:{id}:approved` | "💵 {oy} oyligingiz tasdiqlandi: {net}" — summa faqat egasiga |
| `PAYROLL_PAID` | `PayrollService.markAsPaid` | oylik egasi | NORMAL | `payroll:{id}:paid` | "💵 Oylik to'landi" |
| `CHAT_MESSAGE_STAFF` | EXTERNAL suhbatda o'quvchi/ota-ona yozdi (§4), xodim CRM'da onlayn emas | suhbatdagi xodim(lar) | NORMAL | `chat:{convId}:{userId}` (5 daqiqa oynasi — §2.5) | "💬 {o'quvchi}: {matn 100 belgi}" |
| `DIRECTOR_DIGEST` | mavjud `DirectorDigestService` (20:00) | env'dagi chat id lar → bosqich 1 da: bog'langan SA lar | DIGEST | `digest:{sana}` | mavjud matn |

**Shablon va til:**
- Shablonlar kodda emas, `notification_templates (code, locale, text)` jadvalida. Boshlang'ich qiymatlar migratsiyada, SA tahrirlaydi (S-10).
- Qiymatlar HTML-escape qilinadi (`parse_mode=HTML`).
- Til: `users.locale` (yangi, default `uz`).
- **CRM havolasi:** `inline_keyboard` url tugmasi `https://admin.adizone.uz/...`. Telegram ichida ochiladi, CRM o'z sessiyasini so'raydi.

**PII minimal:**
- Telefon, pasport, manzil yuborilmaydi.
- Oylik summasi faqat egasiga.
- Qarz summasi faqat ACCOUNTANT/SA ga.
- Matnda o'quvchi — ism va familiyaning birinchi harfi ("Dilnoza K.").

**Xodim afzalliklari:**
- `notification_preferences (user_id, event_code, telegram_enabled)`.
- Profil sahifasida ro'yxat; default hammasi yoqiq.
- **O'chirib bo'lmaydigan:** `UNLOCK_DECIDED`, `SUBSTITUTION_ASSIGNED` — ish jarayoniga zarur.

### 2.3 Outbox (tranzaksion)

`NotificationService.publish(event)` biznes amali bilan **bitta tranzaksiyada** yozadi. Amal rollback bo'lsa xabar ham yo'q:
1. `notifications` — har qabul qiluvchiga in-app yozuv (qo'ng'iroqcha, S-04);
2. `telegram_outbox` — bog'langan va xabarnoma yoqilgan qabul qiluvchiga `PENDING` qator. `dedupe_key` UNIQUE → `INSERT … ON CONFLICT (dedupe_key) DO NOTHING`.

**`OutboxWorker`:**
- `@Scheduled(fixedDelay = 1s)`.
- Tanlash: `SELECT … WHERE status='PENDING' AND not_before <= now() ORDER BY priority DESC, id FOR UPDATE SKIP LOCKED LIMIT 50`.
- Tanlanganlar `SENDING` (`locked_at`) ga o'tadi, keyin tranzaksiyadan **tashqarida** yuboriladi.
- Natija qatorga yoziladi: `SENT` + `telegram_message_id` / qayta urinish / `FAILED` / `SKIPPED`.
- `SENDING` da 5 daqiqadan ortiq qolgan qator (jarayon yiqilgan) → `PENDING` ga qaytadi. Telegram'da idempotentlik yo'q: kamdan-kam takror xabar bo'lishi mumkin — **at-least-once**, ataylab qabul qilingan.
- Bir nechta instansda ham to'g'ri (SKIP LOCKED). Hozir instans bitta.

### 2.4 Qayta urinish va Telegram javoblari

| Javob | Ma'nosi | Amal |
|---|---|---|
| 200 `ok` | yuborildi | `SENT` |
| 429 `parameters.retry_after = N` | limit | `not_before = now + N s`, `attempts` oshmaydi; global limitlovchi N soniya to'xtaydi |
| 403 "bot was blocked by the user" / "user is deactivated" | foydalanuvchi botni bloklagan | `SKIPPED`; link (xodim) yoki identity (o'quvchi) `telegram_active = false`; qayta `/start` qilsa tiklanadi |
| 400 "chat not found" | chat id noto'g'ri yoki eskirgan | `SKIPPED`, link nofaol |
| 400 "can't parse entities" / "message is too long" | shablon xatosi | `FAILED`, qayta urinilmaydi, ERROR log (matnsiz, faqat kod va xato) |
| 5xx, timeout, tarmoq | vaqtinchalik | qayta urinish: 10 s, 30 s, 2 daq, 10 daq, 1 soat, 6 soat; 7-urinishda `FAILED` |

**Telegram limitlari** (Bot API FAQ; TAXMIN: raqamlar rasmiy hujjatdagi tavsiyalar):
- umumiy ~30 xabar/s → limitlovchi **25/s** (token bucket);
- bitta chatga ~1 xabar/s → har chat uchun 1/s;
- guruh chatiga 20/daqiqa;
- matn ≤ 4096 belgi — shablon bundan oshsa kesiladi va "…" qo'shiladi;
- `callback_data` ≤ 64 bayt;
- `start` parametri ≤ 64 belgi.

Ommaviy yuborishda (e'lon) outbox tezlikni o'zi tekislaydi.

### 2.5 Sokin soatlar va dedupe

**Sokin soatlar:**
- Default **21:00–08:00 Asia/Tashkent** (§10 Q-6). Xodim profilida o'zgartiradi: `users.quiet_from/quiet_to`, NULL = default.
- `NORMAL` va `DIGEST` → `not_before` = sokin oyna tugashi (08:00).
- `HIGH` → darhol, lekin `disable_notification = true` (ovozsiz).
- O'quvchi/ota-ona xabarlari (§3.6) uchun ham shu qoida; ularda faqat global default.

**Dedupe:**
- `dedupe_key` UNIQUE — bir hodisa bir qabul qiluvchiga bir marta (scheduler qayta ishga tushsa ham).
- **Yig'ish:** bir qabul qiluvchiga 5 daqiqa ichida bir xil kodli ≥ 3 ta `NORMAL` xabar → bitta yig'ma xabar ("🆕 4 ta yangi lid") + ro'yxat havolasi. Worker yuborishdan oldin guruhlaydi (`event_code`, `recipient`, `created_at` oynasi).
- `CHAT_MESSAGE_*` — suhbat bo'yicha 5 daqiqalik oyna: birinchi xabar push, keyingilari shu oynada push qilinmaydi (in-app'da ko'rinadi).

**Saqlash muddati:** `telegram_outbox` — `SENT`/`SKIPPED` 30 kundan keyin o'chiriladi, `FAILED` 90 kun; `notifications` — 90 kun.

### 2.6 API (xodim)

| Metod | Yo'l | Rollar | Izoh |
|---|---|---|---|
| POST | `/api/me/telegram/link-token` | STAFF | `{deepLink, expiresAt}` |
| GET | `/api/me/telegram` | STAFF | `{linked, telegramUsername, linkedAt, active}` |
| DELETE | `/api/me/telegram` | STAFF | uzish |
| GET/PUT | `/api/me/notification-preferences` | STAFF | `[{eventCode, label, telegramEnabled, locked}]`, `quietFrom/quietTo` |
| GET | `/api/notifications?unreadOnly&page&size` | STAFF | in-app (S-04) |
| GET | `/api/notifications/unread-count` | STAFF | — |
| POST | `/api/notifications/{id}/read`, `/read-all` | STAFF | — |
| GET | `/api/admin/telegram/outbox?status&eventCode` | SA | diagnostika: FAILED/SKIPPED lar, `POST /{id}/retry` |
| POST | `/api/telegram/webhook` | permitAll + secret sarlavha | Telegram update'lari |

---

## §3. O'quvchi / ota-ona Mini App (Q11, Q1)

### 3.1 Kirish oqimi

```
Mini App ochiladi (bot Menu button yoki /start dagi "Ilovani ochish" tugmasi)
  → POST /api/app/auth/telegram { initData }
       ├─ initData yaroqsiz / eskirgan                     → 401 app.auth.invalidInitData
       ├─ telegram_user_id bog'langan (app_identities)     → { status: LINKED, token, identity }
       └─ bog'lanmagan                                     → { status: NEEDS_CONTACT }
  NEEDS_CONTACT: "Raqamni ulashish" → Telegram.WebApp.requestContact()
       → foydalanuvchi "Ulashish" bosadi → kontakt BOTGA keladi (webhook: message.contact)
       → server: pending_contact (telegram_user_id → telefon) saqlanadi
  Mini App: POST /api/app/auth/telegram qayta (requestContact callback'idan keyin, 1 s oraliq, ≤ 10 marta)
       ├─ moslik 1 ta                                     → LINKED + token
       ├─ moslik bir nechta                               → { status: CHOOSE, candidates[] } → POST /api/app/auth/choose
       └─ moslik yo'q                                     → { status: NOT_FOUND, support } (maket: "Hisob topiladi" ekrani)
```

### 3.2 initData HMAC tekshiruvi

Telegram WebApp spetsifikatsiyasi bo'yicha (TAXMIN: kod yozilganda rasmiy hujjat bilan qayta solishtiriladi):

1. `initData` — URL query satri. Parse qilinadi, `hash` ajratib olinadi.
2. `data_check_string` = qolgan juftliklar `key=<url-decoded value>`, kalit bo'yicha alifbo tartibida, `\n` bilan ulangan. Yangi qo'shilgan `signature` maydoni ham ichida qoladi — faqat `hash` olib tashlanadi.
3. `secret_key = HMAC_SHA256(key = "WebAppData", message = bot_token)`.
4. `expected = hex(HMAC_SHA256(key = secret_key, message = data_check_string))`.
5. `MessageDigest.isEqual(expected, hash)` — vaqtga chidamli taqqoslash.
6. **`auth_date`:** `now − auth_date ≤ 1 soat` (sozlanadi: `app.telegram.init-data-max-age`). Kelajakdagi `auth_date` (> now + 60 s) ham rad.
7. `user` JSON dan `id` (telegram_user_id), `language_code`, `first_name` olinadi. `user` yo'q → rad.

**Ehtiyot choralari:**
- initData, `hash`, token **logga yozilmaydi** — faqat telegram_user_id.
- Rate limit: bitta IP dan 20/daqiqa, bitta telegram_user_id dan 10/daqiqa.

### 3.3 App JWT (admin JWT'dan ajratilgan)

| | Admin JWT (mavjud) | App JWT (yangi) |
|---|---|---|
| Kalit | `jwt.secret` | **`app.jwt.secret`** (alohida env `APP_JWT_SECRET`) |
| `sub` | username | `app_identities.id` |
| Claim'lar | `tv` (token versiyasi) | `typ=app`, `aud=adizone-app`, `kind` (STUDENT/PARENT), `iv` (identity versiyasi — uzilganda oshadi) |
| Muddat | 8 soat + refresh | **30 daqiqa**, refresh token **yo'q** — muddati tugasa Mini App o'sha initData bilan qayta `auth/telegram` qiladi. initData 1 soatdan eski bo'lsa — "Ilovani qayta oching" |
| Ishlaydi | `/api/**` (STAFF_ROLES) | **faqat `/api/app/**`** |

**Ajratish:**
- Alohida `SecurityFilterChain`, `@Order(1)`, `securityMatcher("/api/app/**")` va `AppJwtFilter`. App JWT boshqa kalit bilan imzolangani uchun admin zanjirida imzo tekshiruvidan o'tmaydi.
- Admin token `/api/app/**` da — 401 (zanjir faqat app kalitini biladi).
- `/api/app/auth/telegram` va `/api/app/auth/choose` — permitAll (initData bilan himoyalangan).
- **Q1 bilan moslik:** `POST /api/auth/login` STUDENT/PARENT uchun 403 `auth.roleNotAllowed` bo'lib qoladi. Mini App foydalanuvchisi `users` jadvaliga yozilmaydi va parol bilan kira olmaydi.

### 3.4 Telefon bo'yicha bog'lash (requestContact)

**Ishonchli manba — bot update'i** (`message.contact`). Mini App ichidagi `requestContact` javobi (`responseUnsafe`) — ishonchsiz, faqat UI uchun.

**Qoidalar:**
- `contact.user_id == from.id` — **o'z raqami**. Boshqa odamning kontakt kartasini yuborib birovning hisobiga ulanib bo'lmaydi. Mos kelmasa — rad va botda tushuntirish.
- `PhoneUtils.canonical(contact.phone_number)`. `null` (chet el raqami va h.k.) → NOT_FOUND.

**Qidiruv (kanonik telefon bo'yicha, faqat faol yozuvlar):**

| Moslik | Natija (`kind`) | Bog'lanadigan o'quvchilar |
|---|---|---|
| `parents.phone` (faol ota-ona) | PARENT | `student_parents` orqali barcha farzandlar (ARCHIVED emas) |
| `students.parent_phone` (Parent yozuvisiz eski maydon) | PARENT | shu maydoni mos o'quvchilar |
| `students.phone` | STUDENT | shu o'quvchi |

**Bir nechta moslik:**
- Bir xil telefon bir nechta **o'quvchida** (aka-uka ota-ona raqamini yozgan) → avtomatik **PARENT** (hamma farzand ko'rinadi). O'quvchi o'z hisobi kerak bo'lsa — markaz o'quvchiga alohida raqam yozadi.
- Telefon ham **o'quvchi**, ham **ota-ona** yozuvida (masalan katta o'quvchi o'z farzandi uchun ham to'laydi) → `CHOOSE`: "O'zim o'qiyman" / "Farzandlarim".
- **Nomzodlar** maskalangan holda ko'rsatiladi: "Dilnoza K. — Turk tili B1". Tanlov faqat server qaytargan `candidateId` lardan. Ular imzolangan, 10 daqiqa yaroqli — boshqa o'quvchining id sini yuborib ulanib bo'lmaydi.

**Topilmadi:**
- "Raqam topilmadi. Markazda ro'yxatdan o'tgan raqamni ulashing yoki menejerga yozing" + qo'llab-quvvatlash kontakti (§10 Q-2).
- Urinish `app_link_attempts` ga yoziladi; 24 soatda 5 tadan ortiq NOT_FOUND → 24 soat blok (raqam terib qidirishga qarshi).

**Hayot sikli:**
- **Qayta tekshiruv:** har so'rovda (keshlangan, 5 daqiqa) identity ning o'quvchilari hali ham shu telefonga bog'langanmi va ARCHIVED emasmi. CRM'da telefon o'zgarsa yoki o'quvchi chiqib ketsa, ruxsat avtomatik tushadi. Identity'da birorta ham o'quvchi qolmasa → 401 `app.auth.unlinked`, qayta ulash.
- **CRM tomoni:**
  - o'quvchi kartasida "Telegram: ulangan (o'quvchi / ota-ona: +998 90 *** ** 67)";
  - "Uzish" tugmasi (SA, A) → identity `UNLINKED`, `iv` oshadi, JWT darhol yaroqsiz.
- **`parents.telegram_chat_id`** PARENT identity bog'langanda avtomatik to'ldiriladi. Mavjud davomat va to'lov xabarlari ishlay boshlaydi — outbox orqali (§3.6).

### 3.5 `/api/app/**` endpointlari (maket ekranlari bo'yicha)

Har so'rovda `studentId` (ixtiyoriy) identity'ning ruxsat etilgan o'quvchilaridan biri bo'lishi shart, aks holda **403** `app.student.forbidden`. Berilmasa — tanlangan (default: birinchi) o'quvchi. Ota-ona almashtirgichi — `GET /me` dagi `students[]`.

| Ekran (maket) | Metod / yo'l | Javob maydonlari (asosiy) | Manba |
|---|---|---|---|
| Hisobni ulash | `POST /api/app/auth/telegram`, `POST /api/app/auth/choose` | `status, token, expiresIn, identity, candidates[]` | §3.1 |
| — | `GET /api/app/me` | `kind, displayName, phoneMasked, students[{id, firstName, lastNameInitial, groups[{id, name}]}], selectedStudentId, locale` | identity |
| Bosh sahifa | `GET /api/app/home?studentId` | `student{firstName, initials}`; `nextLesson{date, start, end, room, teacherName, startsInMinutes}`; `balance{amount, debt, status, nextPaymentDate, nextPaymentAmount}`; `attendance{month, present, total, rate}`; `homework[≤3]{title, dueDate, status}`; `notices[≤3]{title, date, place}` | GroupScheduleService + lesson_exceptions + holidays; **BillingStatusService snapshot (o'qish)**; attendance; homeworks; notices |
| Jadval | `GET /api/app/schedule?studentId&from&to` | `groups[{name, weekdays, start, end, room, address}]`, `lessons[{date, start, end, room, status: PLANNED/CANCELLED/MOVED/EXTRA, teacherName, substitute?}]` | jadval + istisnolar + o'rinbosar (o'quvchiga faqat ism). `address` — Sozlamalar `center.address` |
| Davomat | `GET /api/app/attendance?studentId&month=2026-09` | `counts{present, late, absent, excused}`, `days[{date, status, note?}]`, `lastMissed{date, weekday}` | `attendance`. `note` — faqat EXCUSED sababi; o'qituvchining ichki izohi ko'rsatilmaydi |
| To'lov | `GET /api/app/payments?studentId` | `balance{amount, debt, status, nextPaymentDate, nextPaymentAmount}`, `fee{monthly, discountPercent, final}` (yozilma bo'yicha), `history[{date, periodLabel, method, amount, status, receiptNumber}]` | billing-v2: SG snapshot (`balance`, `debt_since`, `next_payment_*`), `EnrollmentPricing`, `payments` (PAID va CANCELLED; CANCELLED "bekor qilingan" deb). **Faqat o'qish** — hech qanday yozuv yo'q |
| Uy vazifasi | `GET /api/app/homework?studentId&status` | `[{title, description, dueDate, status: NEW/SUBMITTED/GRADED/OVERDUE, marks?}]` | `homeworks` + `homework_submissions` (o'qituvchi belgilaydi — Q11) |
| E'lonlar | `GET /api/app/notices?studentId` | `[{title, content, date, isRead}]` | `notices` — auditoriya `ALL/STUDENTS/PARENTS` (Q12 rollarga `STUDENT`, `PARENT` qo'shiladi). O'qilganlik `app_notice_reads` da |
| Profil | `GET /api/app/profile?studentId` | `fullName, phoneMasked, enrollments[{course, format, room, status, teacherName}]`, `support{managerTelegram, phone}` | `support` — Sozlamalar (§10 Q-2) |
| Profil → Bildirishnomalar | `GET/PUT /api/app/notification-settings` | `lessonReminder{enabled, minutesBefore: 60}`, `paymentReminder{enabled, daysBefore: 3}`, `newHomework{enabled}`, `absence{enabled}` (faqat PARENT) | `app_notification_prefs` |
| Chat (§4) | `GET /api/app/conversations`, `GET …/{id}/messages`, `POST …/{id}/messages` | §4.4 | §4 |

- **O'qituvchi ismi** o'quvchiga to'liq ("Madina Rahimova"). Telefoni va Telegram'i berilmaydi.
- **Pul summalari** `BigDecimal` → JSON raqam. Formatlash ("700 000 UZS") frontendda.

### 3.6 O'quvchi / ota-onaga xabarlar (bot push)

Hammasi §2.3 outbox orqali; qabul qiluvchi — `app_identity`.

| Kod | Trigger | Kimga | Default | Dedupe kaliti |
|---|---|---|---|---|
| `LESSON_REMINDER` | scheduler: darsdan `minutesBefore` oldin (CANCELLED dars — yo'q) | STUDENT (+ PARENT, agar yoqsa) | yoqiq, 60 daq | `lesson:{groupId}:{date}:{identityId}` |
| `PAYMENT_REMINDER` | `nextPaymentDate − daysBefore` kuni 10:00 (mavjud `PaymentReminderService` shu bilan almashtiriladi) | PARENT (bo'lmasa STUDENT) | yoqiq, 3 kun | `payrem:{sgId}:{nextPaymentDate}` |
| `HOMEWORK_NEW` | uy vazifasi yaratildi | guruh o'quvchilari (+ ota-ona) | yoqiq | `hw:{id}:{identityId}` |
| `ABSENCE` | davomat ABSENT/LATE (mavjud `AttendanceService` xabari) | PARENT | yoqiq | `abs:{studentId}:{date}` |
| `NOTICE_NEW` | e'lon (auditoriya STUDENTS/PARENTS) | auditoriya | yoqiq | `notice:{id}:{identityId}` |
| `CHAT_MESSAGE_APP` | EXTERNAL suhbatda xodim yozdi (§4) | suhbatdagi identity lar | yoqiq | `chat:{convId}:{identityId}` (5 daq) |

Tugma: `web_app` (Mini App'ni kerakli ekranda ochadi): `https://app.adizone.uz/#/payments?studentId=…`, chat uchun `startapp=chat_<convId>`.

---

## §4. Chat ko'prigi (Q13)

### 4.1 Qoidalar

- **Faqat xodim boshlaydi**, o'quvchi kartasidan ("Telegram orqali yozish"). O'quvchi/ota-ona yangi suhbat ochmaydi — faqat mavjudiga javob beradi. Bitta istisno: "Menejerga yozish" (maket profili) — §10 Q-9.
- **Kim boshlay oladi:**
  - SA, A — istalgan o'quvchi bilan;
  - TEACHER — faqat o'z faol guruhidagi o'quvchi bilan (`assertOwnsStudent`);
  - SALES_MANAGER, ACCOUNTANT — §10 Q-9 (taklif: ha, ACC qarzdorlar bilan).
- **Suhbat turi `EXTERNAL`:**
  - `subject_student_id` majburiy;
  - auditoriya: `STUDENT` / `PARENT` / `BOTH`;
  - bir o'quvchi + auditoriya + boshlagan xodim uchun bitta faol suhbat (qayta bosilsa o'shasi ochiladi).
  - Ichki chatdan ajratilgan: ichki suhbatlar ro'yxatida alohida bo'lim; ichki `DIRECT/GROUP` ga app identity qo'shib bo'lmaydi.
- **Identity ulanmagan** → 409 `chat.external.notLinked`. CRM'da "O'quvchi/ota-ona Telegram ilovaga ulanmagan" va ulash yo'riqnomasi.
- **Kirish huquqi uzilsa** (o'quvchi chiqdi, identity uzildi): suhbat `CLOSED` — tarix xodimga ko'rinadi, app tomonga yo'q.
- **Saqlash va moderatsiya:**
  - xabarlar o'chirilmaydi: soft delete, SA ko'radi;
  - fayllar — rasm/PDF, ≤ 4MB, CH-02 qoidalari;
  - app tomonidan yuborish limiti 20 xabar/daqiqa;
  - EXTERNAL xabarlar auditga tushadi.

### 4.2 Ishtirokchi modeli

`conversation_participants` va `messages` "kim" ni ikki turda saqlaydi:

| Jadval | O'zgarish |
|---|---|
| `conversations` | `type` += `EXTERNAL`; + `subject_student_id` FK, `audience` (`STUDENT/PARENT/BOTH`), `status` (`OPEN/CLOSED`) |
| `conversation_participants` | `user_id` → NULLABLE; + `app_identity_id` FK; + `participant_kind` (`USER/APP`); `CHECK ((participant_kind='USER' AND user_id IS NOT NULL AND app_identity_id IS NULL) OR (participant_kind='APP' AND app_identity_id IS NOT NULL AND user_id IS NULL))`; UNIQUE (conversation_id, user_id) va (conversation_id, app_identity_id) — alohida qisman indekslar |
| `messages` | `sender_id` → NULLABLE; + `sender_app_identity_id`; shu turdagi CHECK |

- **Kod tomoni:** `ChatAccessService` "joriy ishtirokchi" ni `Participant(kind, id)` qiymat obyekti sifatida beradi. `requireParticipant(conversationId, participant)` ikkala tur uchun bitta so'rov.
- **Mavjud kod:** `createDirect` / `createGroup` faqat `USER`. EXTERNAL uchun alohida servis metodi (`ChatExternalService`).
- **Ko'rinish:**
  - xodim tomonda ishtirokchi — "Dilnoza Karimova (o'quvchi)" / "Dilnoza K. onasi (ota-ona)";
  - app tomonda xodim — "Madina Rahimova (ustoz)"; xodimning `username`, telefon va `role` kodi berilmaydi.

### 4.3 Real vaqt va CH-01 kengaytmasi

**Xodim tomoni:** mavjud STOMP (`/topic/conversation.{id}`).
- EXTERNAL suhbat ham shu topik.
- Interceptor (CH-01) o'zgarmaydi: oq ro'yxat + **a'zolik tekshiruvi** — endi `Participant` bo'yicha.

**App tomoni (MVP):** REST + qisqa polling (ochiq chat ekranida 5 s) + bot push (yopiq bo'lsa).

**Keyingi bosqich — STOMP:** `/ws/app?token=<app JWT>` alohida endpoint, alohida `JwtHandshakeInterceptor` (app kaliti).
- Principal nomi `app:<identityId>`.
- **CH-01 qoidalari app principal uchun:**

| Manzil | USER principal | APP principal |
|---|---|---|
| `/topic/conversation.{id}` | faol a'zo bo'lsa | faol a'zo (APP qatori) va suhbat `EXTERNAL`, `OPEN` bo'lsa |
| `/topic/presence` | ruxsat | **rad** — xodimlar onlayn holati o'quvchiga ko'rinmaydi |
| `/user/queue/errors` | ruxsat | ruxsat |
| wildcard / boshqa | rad | rad |
| SEND `/app/chat.send` | a'zo | a'zo + EXTERNAL + OPEN + limit |
| SEND `/app/chat.edit`, `chat.delete` | muallif (15 daq), SA/A | faqat o'z xabari, 15 daqiqa |
| SEND `/app/chat.typing`, `chat.read` | a'zo | a'zo |

Ikkala tomon uchun ham a'zolik har SUBSCRIBE va SEND da tekshiriladi. Identity uzilsa, uning WS sessiyalari `SimpUserRegistry` orqali yopiladi (CH-03 dagi xodim uchun bilan bir xil mexanizm).

### 4.4 API

| Metod | Yo'l | Kim | Izoh |
|---|---|---|---|
| POST | `/api/chat/conversations/external` | STAFF (§4.1) | `{studentId, audience}` → `ConversationResponse` (+ `subjectStudentId, audience, status`) |
| GET | `/api/chat/conversations?type=EXTERNAL` | STAFF | mavjud ro'yxatga filtr |
| POST | `/api/chat/conversations/{id}/close` | SA, A, boshlagan xodim | — |
| GET | `/api/app/conversations` | app | `[{id, staffName, staffRole: "ustoz"/"menejer", lastMessage, unread}]` |
| GET | `/api/app/conversations/{id}/messages?before&size` | app | mavjud keyset kursor bilan bir xil shakl |
| POST | `/api/app/conversations/{id}/messages` | app | `{text, attachments[]}`; `clientId` idempotentlik |
| POST | `/api/app/conversations/{id}/read` | app | `{messageId}` |
| POST | `/api/app/uploads` | app | rasm/PDF ≤ 4MB, CH-02 tekshiruvi |

**Bot orqali javob yozish** (botdagi xabarga "reply") MVP'da yo'q: bot "Javob berish" `web_app` tugmasini yuboradi. Keyin qo'shilishi mumkin — `reply_to_message.message_id` → outbox qatori → suhbat.

---

## §5. Ma'lumotlar modeli va migratsiyalar

V58 (xavfsizlik), V59 (shartnoma raqami), V60 (e'lon auditoriyasi — `notice_target_roles`) bor; V61–V63 — leaves-exams-contracts. Bu hujjat **V64–V67** ni band qiladi. Hammasi qo'lda, idempotent; `prod-schema-check.sql` ga qo'shiladi.

| Jadval | Asosiy ustunlar | Cheklovlar / indekslar |
|---|---|---|
| `telegram_updates` | `update_id` PK, `received_at`, `kind` | 7 kundan keyin tozalanadi |
| `telegram_link_tokens` | `id`, `user_id` FK, `token_hash` CHAR(64), `expires_at`, `used_at`, `created_at` | UNIQUE `token_hash` |
| `user_telegram_links` | `id`, `user_id` FK, `telegram_user_id` BIGINT, `chat_id` BIGINT, `telegram_username`, `active`, `linked_at`, `deactivated_at`, `deactivated_reason` | UNIQUE `telegram_user_id` (faol); UNIQUE `user_id WHERE active` |
| `users` (+) | `locale` VARCHAR(5) DEFAULT 'uz', `quiet_from`, `quiet_to` TIME NULL | — |
| `notification_preferences` | `user_id`, `event_code`, `telegram_enabled` | PK (user_id, event_code) |
| `notifications` (legacy jadval — X-06 — qayta ishlatiladi va kengaytiriladi) | mavjud `user_id, title, message, is_read, notification_type, reference_id, reference_type, created_at` + `event_code`, `link`, `read_at`, `dedupe_key` | UNIQUE (`user_id`, `dedupe_key`); `idx (user_id, is_read, created_at DESC)` |
| `notification_templates` | `code`, `locale`, `text`, `updated_by`, `updated_at` | PK (code, locale) |
| `telegram_outbox` | `id`, `recipient_kind` (USER/APP), `recipient_id`, `chat_id`, `event_code`, `dedupe_key`, `text`, `reply_markup` JSONB, `priority`, `disable_notification`, `status`, `attempts`, `not_before`, `locked_at`, `last_error` VARCHAR(500), `telegram_message_id`, `created_at`, `sent_at` | UNIQUE `dedupe_key`; `idx (status, not_before, priority)` |
| `app_identities` | `id`, `telegram_user_id` BIGINT, `chat_id`, `kind` (STUDENT/PARENT), `phone_canonical`, `parent_id` FK NULL, `status` (ACTIVE/UNLINKED/BLOCKED), `identity_version` INT, `telegram_active`, `locale`, `selected_student_id`, `linked_at`, `last_seen_at` | UNIQUE `telegram_user_id`; `idx phone_canonical` |
| `app_identity_students` | `identity_id`, `student_id`, `relation` (SELF/PARENT), `linked_at` | PK (identity_id, student_id) |
| `app_pending_contacts` | `telegram_user_id` PK, `phone_canonical`, `received_at` | 15 daqiqadan keyin o'chiriladi |
| `app_link_attempts` | `id`, `telegram_user_id`, `phone_hash`, `result` (LINKED/NOT_FOUND/CHOOSE/REJECTED), `created_at` | `idx (telegram_user_id, created_at)` |
| `app_notification_prefs` | `identity_id`, `code`, `enabled`, `param` INT (`minutesBefore` / `daysBefore`) | PK (identity_id, code) |
| `app_notice_reads` | `notice_id`, `identity_id`, `read_at` | PK |
| `conversations`, `conversation_participants`, `messages` (+) | §4.2 | §4.2 |

| Skript | Mazmuni |
|---|---|
| **V64__telegram_staff.sql** | `telegram_updates`, `telegram_link_tokens`, `user_telegram_links`, `users.locale/quiet_*`, `notification_preferences`, `notifications` kengaytmasi (IF NOT EXISTS jadval + ustunlar), `notification_templates` + boshlang'ich matnlar (`ON CONFLICT DO NOTHING`), `telegram_outbox` |
| **V65__telegram_app.sql** | `app_identities`, `app_identity_students`, `app_pending_contacts`, `app_link_attempts`, `app_notification_prefs`, `app_notice_reads`. Mavjud `parents.telegram_chat_id` qiymatlari bo'lsa — `app_identities` ga ko'chirilmaydi: telefon tasdig'isiz bog'lash yo'q |
| **V66__chat_external.sql** | §4.2 ustunlari va CHECK'lar; mavjud qatorlar `participant_kind='USER'` bilan to'ldiriladi; `messages.sender_id` NULLABLE |
| **V67__notices_app_reads.sql** | Rollar to'plami V60 da bajarildi (`notice_target_roles`, STUDENT/PARENT ham qo'llab-quvvatlanadi). Bu skript faqat Mini App uchun `app_notice_reads` va auditoriyasi STUDENT/PARENT bo'lgan e'lonlar uchun indeks |

pgtest `schema-locations` ga qo'shiladi; H2 uchun test sxemasi (§8).

---

## §6. Xavfsizlik

| Xavf | Chora |
|---|---|
| Bot tokeni oshkor | **Hozir oshkor** (yuqoridagi ⚠️): revoke + faqat env. Token logga, hujjatga va xato javobiga chiqmaydi |
| Soxta webhook | `secret_token` sarlavhasi (≥ 32 belgi, env), `update_id` dedupe; webhook faqat HTTPS |
| Soxta initData / replay | HMAC (§3.2), `auth_date ≤ 1 soat`, vaqtga chidamli taqqoslash, rate limit |
| Birovning raqami bilan ulanish | Faqat bot update'idagi `message.contact`, `contact.user_id == from.id` |
| Raqam terib qidirish (enumeration) | NOT_FOUND limiti (5 / 24 soat), maskalangan nomzodlar, imzolangan `candidateId` |
| App token bilan admin API'ga kirish | Alohida kalit, `aud`, alohida filter zanjiri; testda tekshiriladi (§8) |
| O'quvchi A ning B ma'lumotini so'rashi (IDOR) | Har `/api/app/**` so'rovida `studentId ∈ identity.students` (servis qatlamida, bitta yordamchi orqali) |
| Ota-ona huquqi eskirishi | Identity har so'rovda qayta tekshiriladi (5 daq kesh); uzishda `identity_version` oshadi → JWT yaroqsiz |
| Chat orqali ma'lumot sizishi | EXTERNAL faqat xodim boshlaydi; app principal presence va boshqa suhbatlarni ko'rmaydi; CH-01 oq ro'yxati |
| Telegram'ga PII | Xabarlarda telefon/pasport/manzil yo'q; qarz/oylik summasi faqat tegishli odamga |
| Mini App iframe'da | `app.adizone.uz` da `X-Frame-Options` qo'yilmaydi (Telegram Web `web.telegram.org` iframe'da ochadi); `Content-Security-Policy: frame-ancestors https://web.telegram.org https://*.telegram.org` (TAXMIN: domenlar ro'yxati tekshiriladi); API faqat `Authorization` sarlavhasi bilan — cookie yo'q, CSRF xavfi yo'q |
| CORS | `app.adizone.uz` allaqachon `ALLOWED_ORIGIN_PATTERNS` da. `/api/app/**` uchun faqat shu origin (admin origin'lari kerak emas) |
| Fayllar | App tomonidan yuklangan fayllar `GET /api/files/**` permitAll orqali emas (CH-04): `/api/app/files/{id}` + a'zolik |
| Saqlash muddati | outbox 30/90 kun; urinishlar 90 kun; pending kontakt 15 daqiqa; uzilgan identity'da `chat_id` va telefon 30 kundan keyin NULL qilinadi |

---

## §7. Hosting va frontend

### 7.1 `app.adizone.uz` — alohida yengil ilova
- **Repozitoriy:** yangi `adizone-app` (yoki `adizone-admin` monorepo ichida alohida paket). Admin bilan aralashtirilmaydi: bundle kichik (Mini App mobil tarmoqda tez ochilishi kerak) va bog'liqliklar minimal.
- **Stack:**
  - Vite, Vue 3 + TS, vue-router (hash rejimi);
  - TanStack Query, vue-i18n (uz/ru);
  - `https://telegram.org/js/telegram-web-app.js` (rasmiy skript);
  - shadcn/Tailwind **yo'q** — oddiy CSS o'zgaruvchilari (maket tokenlari).
- **Joylash:** Vercel (admin bilan bir xil). `app.adizone.uz` → statik. API `https://api.adizone.uz`.
- **BotFather:** Mini App URL, Menu button (`setChatMenuButton`), domen.
- **Hajm maqsadi:** birinchi yuklash ≤ 150 KB gzip (TAXMIN).

### 7.2 Mavzu (maket variant 1 / 2)

`Telegram.WebApp.colorScheme` (`dark` → variant 1 "Tungi", `light` → variant 2 "Yorug'") va `themeParams` CSS o'zgaruvchilariga:
- `--tg-theme-bg-color`, `--tg-theme-text-color`, `--tg-theme-hint-color`, `--tg-theme-button-color`, `--tg-theme-button-text-color`, `--tg-theme-secondary-bg-color`.

Maketdagi brend ranglari (aksent, karta foni) faqat themeParams'da bo'lmagan joylarda. `themeChanged` hodisasida qayta qo'llanadi.

### 7.3 Telegram elementlari
- `BackButton` — ichki ekranlarda.
- `MainButton` — maketdagi "To'lash" uchun; MVP'da yashirin (§10 Q-1).
- `HapticFeedback` — tugmalarda.
- `expand()` — ochilganda.
- `requestContact()` — ulash ekranida.
- `startapp` parametri (`chat_<id>`, `payments`) — deep link.

---

## §8. Testlar strategiyasi

**Backend** — mavjud uslub, H2 + pgtest:

| Soha | Testlar |
|---|---|
| initData | Testda ma'lum bot token bilan initData **yaratiladi** (HMAC hisoblab) → qabul; bitta maydon o'zgartirilgan → 401; `hash` yo'q → 401; `auth_date` 2 soat oldin → 401; kelajakda → 401; `user` yo'q → 401 |
| Telefon moslash | `998901234567`, `+998 90 123 45 67`, `901234567` → bitta kanonik; o'quvchi / ota-ona / `parent_phone` / ikki o'quvchi (→ PARENT) / o'quvchi + ota-ona (→ CHOOSE); `contact.user_id ≠ from.id` → rad; NOT_FOUND limiti; begona `candidateId` → 403 |
| JWT ajratish | app token → `/api/students` 401; admin token → `/api/app/home` 401; app token muddati o'tgan → 401; uzishdan keyin (`identity_version`) → 401; STUDENT admin login hali ham 403 (Q1 regressiyasi) |
| IDOR | identity A `studentId = B` → 403 barcha `/api/app/**` endpointlarida (parametrlangan test) |
| Ma'lumot to'g'riligi | `/api/app/payments` billing-v2 snapshot bilan mos (qarz, keyingi to'lov), CANCELLED to'lov "bekor qilingan"; `/attendance` sanoqlar; `/schedule` CANCELLED/MOVED istisnolari va bayramlar |
| Bog'lash (xodim) | token bir martalik, 10 daqiqa; guruh chatidan → rad; qayta bog'lash eskisini almashtiradi; bloklangan xodim |
| Outbox | Soxta `TelegramClient` (interfeys, test bean; WireMock shart emas): 200 → SENT; 429 `retry_after` → `not_before`, attempts o'zgarmaydi; 403 → SKIPPED + link nofaol; 400 parse → FAILED, qayta urinish yo'q; 5xx → backoff jadvali; `dedupe_key` takrori — bitta qator; rollback bo'lgan biznes amal → outbox qatori yo'q |
| Sokin soatlar | `MutableClock` 22:00 → NORMAL `not_before` = 08:00, HIGH darhol `disable_notification`; yig'ish (3 ta LEAD_NEW → 1 xabar) |
| Qabul qiluvchi qoidalari | Tayinlanmagan lid → barcha faol SM, tayinlangan → faqat u; o'z amali — o'ziga yo'q; nofaol/bog'lanmagan — Telegram qatori yo'q, in-app bor |
| Worker parallelligi | Ikki worker bir vaqtda — har qator bir marta (`FOR UPDATE SKIP LOCKED`). PostgreSQL'da; H2 da SKIP LOCKED qo'llab-quvvatlanishi tekshiriladi, bo'lmasa test faqat pgtest (TAXMIN) |
| Webhook | Noto'g'ri secret sarlavha → 401; takroriy `update_id` → bir marta ishlanadi |
| Chat ko'prigi | Xodim (TEACHER, begona o'quvchi) → 403; ulanmagan → 409; app principal: o'z EXTERNAL suhbatiga SUBSCRIBE ok, ichki suhbatga / `/topic/presence` / wildcard → rad; CLOSED suhbatga SEND → rad; xodim xabari → `CHAT_MESSAGE_APP` outbox (5 daq oynasi) |

**Frontend (Mini App):**
- Vitest + `window.Telegram.WebApp` soxta obyekti (initData, themeParams, requestContact callback);
- har ekran uchun query va UI holatlari (yuklanmoqda, bo'sh, xato, 401 → qayta auth);
- mavzu almashuvi (dark/light).

**Qo'lda:** Telegram **test muhiti** (`api.telegram.org/bot<token>/test/…`) va alohida test bot (BotFather); Android, iOS, Desktop, Web klientlarida ochish.

---

## §9. Bosqichlar

| Bosqich | Mazmun | Backend | Frontend | Bog'liqlik |
|---|---|---|---|---|
| **0 (darhol, ops)** | Token revoke, `application.yml` → env, `TELEGRAM_ENABLED` | 0.5 kun | — | — |
| **1. Bot asosi + xodim xabarnomalari** | Webhook, `TelegramClient`, outbox + worker, limitlar, sokin soatlar, dedupe; xodimni bog'lash; in-app `notifications` + API; hodisalar: `LEAD_NEW`, `LEAD_ASSIGNED`, `TASK_*`, `LEAVE_*`, `UNLOCK_*`, `PAYROLL_*`, `PAYMENT_CANCELLED`, `DEBTORS_DAILY`; mavjud davomat/to'lov/digest xabarlari outbox'ga | 6–8 kun | admin: profil "Telegram'ni ulash", afzalliklar, qo'ng'iroqcha — 3–4 kun | Q-3, Q-4, Q-5, Q-6 |
| **2. Mini App MVP** | initData, app JWT va zanjir, requestContact + moslash (CHOOSE, ota-ona almashtirgichi), `/api/app/me`, `/home`, `/attendance`, `/payments` (faqat ko'rish); CRM: "Telegram ulangan" va uzish | 6–8 kun | `app.adizone.uz`: ulash, bosh sahifa, davomat, to'lov, profil (asosiy) — 6–8 kun | Q-1 (to'lash tugmasi yashirin), Q-2 |
| **3. Mini App to'liq** | `/schedule`, `/homework`, `/notices`, `/notification-settings`; o'quvchi xabarlari (`LESSON_REMINDER`, `PAYMENT_REMINDER`, `HOMEWORK_NEW`, `ABSENCE`, `NOTICE_NEW`) | 4–5 kun | jadval, uy vazifasi, e'lonlar, sozlamalar — 4 kun | leaves-exams-contracts (o'rinbosar jadvalda) |
| **4. Chat ko'prigi** | V66, `Participant` modeli, `ChatExternalService`, `/api/app/conversations*`, push; CH-01 kengaytmasi (keyin: app STOMP) | 6–8 kun | admin: o'quvchi kartasidan suhbat; Mini App: chat ekrani — 5–6 kun | Q-9 |
| **5. Onlayn to'lov** | Click / Payme / Uzum merchant API → billing-v2 `PaymentBookingService` (idempotentlik kaliti = provayder tranzaksiyasi), onlayn kassa (`acceptOnlinePayment`), webhook imzolari, qaytarish | har provayder 4–6 kun | MainButton "To'lash" — 2 kun | **Q-1: merchant shartnomalari** |

---

## §10. Ochiq savollar (taklif bilan)

| # | Savol | Taklif |
|---|---|---|
| **Q-1** | Maketdagi "To'lash" (Click / Payme / Uzum): markazda merchant shartnomalari bormi? MVP'da faqat to'lov ma'lumoti ko'rsatilsinmi? | **MVP'da faqat ko'rish:** balans, qarz, keyingi to'lov, tarix. "To'lash" tugmasi (MainButton) **yashirin**; o'rniga "Qanday to'lash mumkin" — kassa manzili va ish vaqti (Sozlamalardan). Onlayn to'lov — 5-bosqich, faqat shartnoma (merchant ID, kalitlar, test muhiti) bo'lgan provayderlar uchun. Tartib taklifi: birinchi bo'lib qaysi biri bilan shartnoma bor bo'lsa — o'sha |
| Q-2 | Qo'llab-quvvatlash kontaktlari: maketda `@adizone_manager` va `+998 77 337 32 33`, Sozlamalarda (D12) `+998 90 335 13 45`. Qaysi biri? | Sozlamalarga alohida kalitlar: `support.telegram`, `support.phone` (rekvizit telefonidan farqli bo'lishi mumkin). Qiymatlarini buyurtmachi beradi |
| Q-3 | Bot: mavjud bot (token oshkor — revoke qilinadi) yoki yangi bot? Username va BotFather hisobi kimda? | Mavjud botni saqlash (revoke + yangi token). Username ota-onalarga allaqachon tarqatilgan bo'lishi mumkin. BotFather hisobi markaz telefoniga |
| Q-4 | Tayinlanmagan lid: "sotuv bo'limi" — barcha SALES_MANAGER lar yoki umumiy Telegram guruhi? | Barcha faol SM larga shaxsiy xabar. Ixtiyoriy: Sozlamalarda `telegram.salesGroupChatId` (guruh chati) — bo'lsa shaxsiy o'rniga guruhga |
| Q-5 | Qarzdorlar: har yangi qarzdor uchun alohida xabarmi yoki kunlik xulosa? Kimga (ACCOUNTANT / SA / ADMIN)? | Kunlik xulosa 09:30, ACCOUNTANT + SA |
| Q-6 | Sokin soatlar: 21:00–08:00? Yakshanba to'liq sokinmi? | 21:00–08:00 har kuni; yakshanba — faqat HIGH. O'quvchi/ota-ona uchun ham shu |
| Q-7 | Ota-ona nimalarni ko'radi: davomat, to'lov, uy vazifasi, chat — hammasimi? Katta yoshli (18+) o'quvchi ota-onadan yashirishni so'rasa? | Hammasi (MVP). 18+ cheklovi — keyin, admin tomonidan o'quvchi kartasida "ota-onaga ko'rsatilmasin" belgisi |
| Q-8 | Bir telefon bir nechta o'quvchida: avtomatik "ota-ona" deb olish to'g'rimi? | Ha (§3.4). O'quvchi o'z hisobini xohlasa — markaz uning o'z raqamini yozadi |
| Q-9 | Chat: o'qituvchilardan tashqari kim yoza oladi (SM, ACC)? Maketdagi "Menejerga yozish" — Telegram'dagi `@adizone_manager` ga havolami yoki CRM ichidagi suhbatmi? | SA, A, TEACHER (o'z o'quvchisi), ACC (qarzdorlar). "Menejerga yozish" MVP'da — Sozlamalardagi `support.telegram` ga oddiy havola. Keyin — CRM'dagi umumiy "Menejer" navbatiga EXTERNAL suhbat (o'quvchi boshlaydi, istalgan SA/A javob beradi) |
| Q-10 | Mini App tillari: faqat o'zbek (lotin) yoki rus ham? | uz + ru (Telegram `language_code` bo'yicha, profilda almashtirish) |
| Q-11 | Maketdagi 3–5 variantlar (Qizil, Liquid Glass, Ochiq glass) kerakmi? | Yo'q — faqat Telegram mavzusi bo'yicha 1 (dark) / 2 (light) |

---

## §11. Bosqich 3 — qo'lda ulash, sabab bildirish, chat ko'prigi, o'qituvchi rejimi

> **Holat:** qarorlar buyurtmachidan (2026-10-03). Bu bo'lim §9 dagi 3–4-bosqichlar rejasini **aniqlashtiradi va o'rnini bosadi**; amaldagi shartnoma — [miniapp-api.md](miniapp-api.md). Migratsiyalar **V68–V70**.

### 11.0 Qarorlar

| # | Qaror | Oqibat |
|---|---|---|
| D1 | Kontakt orqali ulash **o'zgarmaydi** (§3.4) | Qo'lda ulash — qo'shimcha yo'l, markaz tasdiqlaydi |
| D2 | Qo'lda raqam: initData + telefon → bazada bo'lsa **so'rov** (PENDING), bo'lmasa 404 `app.phoneNotFound`; 24 soatda 5 urinish | CRM'da SA, A, SALES_HEAD tasdiqlaydi / rad etadi; natija — bot xabari |
| D3 | Sabab bildirish: o'quvchi/ota-ona dars kuniga "kelmayman / kechikaman / boshqa" yozadi | Faqat o'quvchining guruhi, bugun … +14 kun ichidagi **dars bor** kun. O'qituvchi (va shu kungi o'rinbosar) ga bot xabari; CRM davomatida o'quvchi yonida ko'rinadi |
| D4 | Chat: app foydalanuvchisi **o'zi boshlay oladi** (§4.1 dagi "faqat xodim boshlaydi" o'rniga) — o'z o'qituvchilari, "Menejer", direktor | §11.3 |
| D5 | "Menejer" — umumiy navbat: barcha faol ADMIN + SALES_HEAD + SALES_MANAGER; direktor — barcha faol SUPER_ADMIN | Ular suhbatning oddiy ishtirokchilari — CRM chat (STOMP) va CH-01 a'zolik tekshiruvi o'zgarmaydi |
| D6 | App tomoni MVP'da STOMP'ga ulanmaydi — REST + polling; xodim yozsa bot push | §4.3 dagi "app STOMP" keyingi bosqich; CH-01 ga APP principal qo'shilmaydi |
| D7 | Sokin soatlar **21:00–08:00** (Asia/Tashkent): chat va ulash xabarlari navbatda turadi (08:00 da ketadi); sabab bildirish o'qituvchiga darhol, lekin ovozsiz | `telegram_outbox` (§2.3 ning minimal varianti, §11.5) |
| D8 | O'qituvchi rejimi: telefon **TEACHER rolidagi faol xodim** (`users.phone` yoki o'qituvchi profili telefoni) ga mos kelsa, identity shu xodimga bog'lanadi | App JWT da rol; davomat **mavjud** `AttendanceService` / `AttendanceAccessService` / `AttendanceUnlockRequestService` orqali, xodim nomidan (run-as) |
| D9 | Boshqa xodim rollari (A, SA, ACC, SM, SH) app'da xodim rejimini **olmaydi** | Admin huquqlarini telefon orqali Telegram'ga o'tkazib bo'lmaydi |
| D10 | Faqat o'zbekcha; onlayn to'lov yo'q (o'zgarmaydi) | — |

### 11.1 Qo'lda ulash (D2)

```
Mini App "Hisobni ulash" → "Raqamni qo'lda kiritish"
  POST /api/app/link/manual { initData, phone }          (ochiq, initData HMAC)
    ├─ identity allaqachon ulangan                → 409 app.link.alreadyLinked
    ├─ 24 soatda ≥ 5 qo'lda urinish               → 429 app.link.rateLimited
    ├─ PhoneUtils.canonical = null / moslik yo'q  → 404 app.phoneNotFound   (urinish sanaladi)
    └─ moslik bor (o'quvchi / ota-ona / o'qituvchi) → app_link_requests PENDING → 200 { requestId, status }
         avvalgi PENDING so'rov: o'sha raqam — o'shasi qaytadi; boshqa raqam — eskisi CANCELLED
CRM: GET  /api/app-link-requests?status        (SA, A, SH) → ro'yxat + topilganlar ("O'quvchi: Ali Karimov; O'qituvchi: …")
     POST /api/app-link-requests/{id}/approve  → telefon bo'yicha QAYTA moslash, identity ulanadi (kontakt bilan bir xil),
                                                bot: "✅ So'rovingiz tasdiqlandi"
     POST /api/app-link-requests/{id}/reject { reason } → bot: "❌ So'rov rad etildi: {sabab}"
```
- Qo'lda ulangan identity kontakt bilan ulangandan farq qilmaydi; telefon tasdig'i — xodim qarori (`decided_by` saqlanadi).
- Topilganlar javobda **oshkor qilinmaydi** (faqat "so'rov yuborildi"); 404 raqam yo'qligini bildiradi — shuning uchun urinish limiti.

### 11.2 Sabab bildirish (D3)

| Qoida | Qiymat |
|---|---|
| Kim | identity'ning bog'langan o'quvchisi (`studentId` — IDOR) |
| Guruh | o'quvchining **ochiq** yozilmasi, sana yozilma davri ichida; begona guruh → 403 `app.group.forbidden` |
| Sana | `bugun ≤ lessonDate ≤ bugun + 14`; o'sha kuni dars bor (jadval + bayram + istisno, `PLANNED` / `EXTRA`); bugungi dars tugagan bo'lsa — rad |
| Tur | `ABSENT` (kelmaydi), `LATE` (kechikadi), `OTHER` (izoh majburiy) |
| Takror | bitta o'quvchi + guruh + sana uchun bitta faol bildirish → 409 `app.absence.duplicate` |
| Bekor qilish | o'z yozuvi va dars kuni hali o'tmagan |
| Xabar | guruh o'qituvchisi (yoki shu kungi o'rinbosar) app'da o'qituvchi rejimida ulangan bo'lsa — bot (HIGH: darhol, sokin soatda ovozsiz) |
| CRM | `GET /api/absence-notices?groupId&date` (SA, A — hammasi; TEACHER — `AttendanceAccessService.assertCanRead`); `GET /api/attendance/group/{id}` javobida `absenceNotice` |

Bildirish davomatni **o'zgartirmaydi** — o'qituvchi o'zi belgilaydi (sababli / sababsiz).

### 11.3 Chat ko'prigi (D4–D6)

| Jadval | O'zgarish (§4.2 dan soddalashtirilgan) |
|---|---|
| `conversations` | `type` += `EXTERNAL`; + `external_identity_id`, `external_target` (`TEACHER` / `SUPPORT` / `DIRECTOR`), `external_staff_user_id` (TEACHER uchun), `external_key` UNIQUE (`ext:{identity}:{target}:{user}`), `external_last_read_message_id` (app kursori), `status` (`OPEN` / `CLOSED`) |
| `conversation_participants` | **o'zgarmaydi** — faqat xodimlar. TEACHER — o'qituvchi; SUPPORT — faol A + SH + SM; DIRECTOR — faol SA. Har app xabarida sinxronlanadi (yangi xodim qo'shiladi; nofaol yoki rol o'zgargan — `left_at`) |
| `messages` | `sender_id` → NULLABLE; + `sender_app_identity_id`. Xodim xabari — `sender_id`, app xabari — `sender_app_identity_id` |

- **App kimga yoza oladi:** o'z o'qituvchilari (identity o'quvchilarining ochiq guruhlari o'qituvchi useri), `SUPPORT`, `DIRECTOR`. Boshqa o'qituvchi → 403 `app.chat.forbidden`.
- Bir identity + target + xodim — bitta suhbat (`external_key`); qayta ochilsa o'shasi.
- **Xodim tomoni:** CRM chat ro'yxatida `type: EXTERNAL`, sarlavha "Ota-ona: Ali Karimov" / "O'quvchi: …"; xabarlar mavjud `/app/chat.send` (STOMP) orqali, a'zolik — CH-01. App xabari `/topic/conversation.{id}` ga commit'dan keyin (`senderType: APP`).
- **App tomoni:** `GET /api/app/chats`, `…/{id}/messages?before&size`, `POST …/{id}/messages` (matn ≤ 4000 va/yoki bitta rasm ≤ 4MB, CH-02), `POST …/{id}/read`. Xodimning faqat ismi va "ustoz / menejer / direktor" yorlig'i.
- **Push:** xodim yozsa — identity chatiga bot xabari (outbox, NORMAL): 5 daqiqalik oynada bitta, sokin soatlarda 08:00 ga bitta. App foydalanuvchisi o'qituvchiga yozsa va o'qituvchi app'da ulangan bo'lsa — o'qituvchiga ham.
- O'qituvchi rejimidagi identity `/api/app/chats` da o'zi ishtirokchi bo'lgan EXTERNAL suhbatlarni ham ko'radi (`side: STAFF`) va xodim sifatida javob yozadi (`ChatService.send`). Ichki (DIRECT / GROUP) chatlar app'da ko'rinmaydi.
- `CLOSED` suhbatga app yozolmaydi (409 `app.chat.closed`); yopish endpointi — keyingi bosqich.

### 11.4 O'qituvchi rejimi (D8–D9)

- **Moslash:** `role = TEACHER`, faol user, telefon (`users.phone` yoki shu userga bog'langan `teachers.phone`) — aynan **bitta** xodim; bir nechta → rejim berilmaydi. Telefon ham o'quvchi/ota-ona, ham o'qituvchi bo'lsa — ikkalasi (`roles: ["PARENT","TEACHER"]`).
- `app_identities.staff_user_id` (FK users, ON DELETE SET NULL; bitta xodim — bitta identity, yangi ulanish eskisidan oladi). Faqat o'qituvchi bo'lsa `kind = TEACHER`.
- **Huquq har so'rovda bazadan:** identity ACTIVE, `staff_user_id` bor, user faol va TEACHER → `ROLE_APP_TEACHER`. Qayta moslash har `POST /api/app/auth` da.
- **Endpointlar** (`/api/app/teacher/**`):

| Endpoint | Mantiq |
|---|---|
| `GET /today?date` | o'z guruhlari + shu kungi o'rinbosarliklar; `role: ORIGINAL / SUBSTITUTE`, holat, belgilangan / jami, bildirishlar, `canMark` |
| `GET /attendance/{groupId}?date` | `AttendanceAccessService.assertCanRead` (xodim nomidan) → o'quvchilar + holat + sabab bildirish; `editable`, `lockReason`, ochish so'rovi |
| `PUT /attendance/{groupId}?date` | sana > bugun → 400; sana < bugun va amaldagi ochish ruxsati yo'q → **403 `attendance.locked`**; guruhda yo'q o'quvchi → 403; so'ng `AttendanceService.markAttendance` (o'rinbosar qoidalari, audit, billing, ota-ona xabari — o'zgarmaydi) |
| `POST /unlock-requests`, `GET /unlock-requests` | `AttendanceUnlockRequestService.createRequest` / `getMyRequests` |

- "Bugun" — Asia/Tashkent, 23:59 gacha (mavjud qoida).

### 11.5 Outbox (minimal)

`telegram_outbox (id, chat_id, text, reply_markup, silent, priority, status, attempts, not_before, dedupe_key UNIQUE, event_code, last_error, created_at, sent_at)`:
- biznes amali bilan **bir tranzaksiyada** yoziladi (`INSERT … ON CONFLICT DO NOTHING` — dedupe);
- `TelegramOutboxWorker` (5 s): `PENDING` va `not_before ≤ now` → `TelegramBotApi.sendMessage`; muvaffaqiyat — `SENT`; xato — 1 / 5 / 30 / 120 daqiqa, 5-urinishda `FAILED`;
- NORMAL: sokin soatda `not_before` = keyingi 08:00; HIGH: darhol, sokin soatda `silent = true`.
- §2 dagi to'liq outbox (429 / 403 tasnifi, global limit) — 1-bosqich bilan birga.

### 11.6 Migratsiyalar

| Skript | Mazmuni |
|---|---|
| `V68__app_link_requests_teacher.sql` | `app_identities.staff_user_id` (+ FK, unikal indeks), `app_link_requests` |
| `V69__absence_notices.sql` | `absence_notices` (+ qisman UNIQUE faol bildirish) |
| `V70__chat_external_outbox.sql` | `conversations` EXTERNAL ustunlari, `messages.sender_id` NULLABLE + `sender_app_identity_id`, `telegram_outbox` |

### 11.7 Testlar
IDOR: begona o'quvchi / guruh / suhbat / bildirish / o'qituvchi guruhi → 403. Qo'lda ulash: 404, limit, tasdiq → identity va bot xabari, rad. Sabab: sana oynasi, dars yo'q kun, takror, bekor qilish, o'qituvchiga push. Chat: kontaktlar, yaratish (takror — o'sha), matn / rasm, xodim javobi → push (sokin soatda 08:00), CRM ro'yxatida EXTERNAL. O'qituvchi: today, davomat o'qish / yozish, o'tgan kun → 403 `attendance.locked`, ochish so'rovi.

# Bildirishnomalar — yagona xizmat (V84)

> Holat: 2026-10-17, `billing-v2`. Asos: bildirishnomalar diagnozi (A–D). Bog'liq hujjatlar:
> `telegram-platform.md` §11.5 (outbox), `miniapp-api.md`, `docs/audit/api-inventory.md` (2026-10-17 bo'limi).

## 1. Maqsad

Barcha ogohlantirish va eslatmalar bitta kirish nuqtasidan o'tadi — `NotificationService.dispatch(NotificationRequest)`.
Mavjud ikki mexanizm qayta ishlatiladi, parallel yozilmagan:

| Kanal | Mexanizm | Qachon yoziladi |
|---|---|---|
| CRM qo'ng'iroqchasi | `user_notifications` + STOMP `/user/queue/notifications` (`UserNotificationListener`) | biznes tranzaksiyasi **commit'dan keyin** |
| Telegram bot | `telegram_outbox` → `TelegramOutboxWorker` | chaqiruvchining **tranzaksiyasida** (rollback — xabar yo'q) |

Eski `service/TelegramService` (sinxron, tranzaksiya ichida, qayta urinishsiz) va `Parent.telegramChatId`
(hech kim to'ldirmagan — xabarlar faqat logga tushardi) **olib tashlandi**. `parents.telegram_chat_id` ustuni bazada
qoladi (kod ishlatmaydi).

## 2. `dispatch` — qabul qiluvchi turiga qarab kanal

```
NotificationRequest
  type            NotificationType (hodisa kodi = qo'ng'iroqcha turi = outbox event_code)
  variant         shablon varianti (bulk, auto) yoki null
  staffUserIds / staffRoles / excludeUserId     → xodimlar
  studentIds + relations (SELF, PARENT)         → o'quvchiga ulangan AppIdentity lar
  chatIds                                        → aniq chat (direktor xulosasi)
  args            shablon argumentlari (tayyor satrlar)
  dedupeKey       takror himoyasi
  staffTelegramKey  xodim boti uchun alohida kalit (chat — 5 daqiqalik oyna)
  priority        HIGH | NORMAL (outbox sokin soatlari)
  link, entityType, entityId, collapse          → qo'ng'iroqcha
  appPath         bot tugmasi: Mini App ichidagi yo'l (/#/payments)
```

| Qabul qiluvchi | Kanal |
|---|---|
| Xodim (`staffUserIds`, `staffRoles`; faqat faol, `excludeUserId` dan tashqari) | qo'ng'iroqcha; tur `staffTelegram()` bo'lsa va xodim Telegram'ga ulangan bo'lsa (o'qituvchi rejimi, `AppIdentity.staffUserId`) — **bot ham** |
| O'quvchi (`studentIds`) | ulangan FAOL `AppIdentity` lar (`AppIdentityStudent`, `relations` bo'yicha; chat id bor) — bot. Bir hodisada bir identity'ga bitta xabar. **Ulanmagan o'quvchi — xato emas**, `DispatchResult.unlinked` da sanaladi |
| `chatIds` | bot |

Natija — `DispatchResult(bell, telegram, duplicates, unlinked)` (statistika, testlar).

### Takroriy yuborishdan himoya

* Bot: `telegram_outbox.dedupe_key` (UNIQUE, `ON CONFLICT DO NOTHING`). Kalit = `dedupeKey` + qabul qiluvchi
  qo'shimchasi: `:u<userId>` (xodim), `:i<identityId>` (o'quvchi/ota-ona), `:c<chatId>`.
* Qo'ng'iroqcha: `user_notifications.dedupe_key` (V84, `UNIQUE (user_id, dedupe_key)`, NULL — cheklovsiz) —
  shu xodimda shu kalit bo'lsa yangi qator yozilmaydi.
* Bekor qilish: `NotificationService.cancelTelegram(prefix)` — kaliti prefiks bilan boshlanadigan **PENDING**
  qatorlar `CANCELLED` bo'ladi, kalit `<kalit>:x<id>` ga almashadi (shu hodisa qayta yuz bersa yana qo'yiladi).
  Yuborilganlariga tegilmaydi.

### Kalitlar

| Hodisa | Kalit |
|---|---|
| Darsga kelmadi | `absent:{attendanceId}` |
| Qarz eslatmasi | `pay:{studentId}:{sana}` — bir o'quvchiga bir kunda bir marta |
| Direktor xulosasi | `digest:{sana}` |
| E'lon | `notice:{noticeId}` |
| Vazifa | `task:{id}:soon:{muddat yyyyMMddHHmm}`, `task:{id}:overdue:{muddat}` — muddat surilsa yangi muddat uchun yana |
| Ichki chat (bot) | `TelegramOutboxService.windowKey("chat:{id}:internal")` — 5 daqiqada bitta, tunda bitta |

### Sokin soatlar

Outbox'niki (21:00–08:00, Asia/Tashkent): NORMAL — 08:00 gacha kutadi; HIGH — darhol, ovozsiz. Qo'ng'iroqcha
sokin soatni bilmaydi (CRM ichida).

## 3. Shablonlar (uz asosiy, ru)

`messages*.properties`: `notify.<TUR>[.<variant>].title|body` — qo'ng'iroqcha (doim o'zbekcha; xodim tili
saqlanmaydi), `notify.<TUR>[.<variant>].telegram` — bot (HTML), **qabul qiluvchi tilida**: `AppIdentity.language`
(V84; `uz` | `ru`, NULL — `uz`). Til Mini App har `POST /api/app/auth` da yangilanadi: `Accept-Language` sarlavhasi,
bo'lmasa Telegram `language_code`.

* Shablonlar doim MessageFormat'dan o'tadi — apostrof IKKI marta (`''`).
* Argumentlar tayyor satr (summa `630 000`, sana `17.10.2026`); bot matnida HTML-escape qilinadi, shablonning o'zi — yo'q.
* Bot shabloni bo'lmagan tur botga yuborilmaydi. Tugma yozuvi — `notify.button.open`.
* `messages_en.properties` da ham bir xil kalitlar (test: `templates_sameNotifyKeysInAllBundles_everyTypeHasTemplate`).

## 4. Hodisalar

| `type` | Kimga | Kanal | Trigger | Joyi |
|---|---|---|---|---|
| `STUDENT_ABSENT` | ulangan ota-ona + o'quvchining o'zi | bot, HIGH, tugma `/#/attendance` | davomat ABSENT (bugun/kecha uchun); ABSENT → boshqa holat — yuborilmagani bekor | `AttendanceService.markAttendance` |
| `PAYMENT_OVERDUE` | ulangan ota-ona + o'quvchi | bot, NORMAL, `/#/payments` | cron `app.billing.reminder-cron` (10:00): OVERDUE kuni va har 3 kunda, muzlatilganga yo'q | `PaymentReminderService.run` |
| `DIRECTOR_DIGEST` | `app.dashboard.digest.chat-ids` | bot | cron `app.dashboard.digest.cron` (20:00), flag `app.dashboard.digest.enabled` (o'zgarmagan) | `DirectorDigestService.send` |
| `CHAT_INTERNAL` | ichki suhbatning boshqa faol a'zolari | qo'ng'iroqcha (yig'iladi) + ulangan o'qituvchi boti | xabar yuborildi; suhbat oxirigacha o'qilsa — o'qilgan (`NOTIFICATIONS_READ` push) | `ChatService.send` / `markRead` |
| `LEAD_ASSIGNED` | yangi mas'ul (o'zini biriktirsa — yo'q) | qo'ng'iroqcha | qo'lda (`assignLead`), ommaviy — bitta yig'ma `bulk` ("N ta lid"), avto (Meta, sayt, standart mas'ul) — `auto` | `LeadService`, `LeadBulkAssignService`, `LeadSettingsService.assignIfUnassigned` |
| `NOTICE_NEW` | e'lon auditoriyasi rollari (bo'sh — hamma), muallifdan tashqari | qo'ng'iroqcha + ulangan o'qituvchi boti | e'lon ko'rinadigan bo'lganda (yaratildi chop etilgan holda yoki qoralama chop etildi); bir marta | `NoticeService` |
| `TASK_DUE_SOON` | vazifa egasi | qo'ng'iroqcha + ulangan bo'lsa bot | `TaskDeadlineReminderJob` har 15 daqiqa: ochiq, muddatiga ≤ 1 soat ("kun davomida"ga yo'q) | `service/TaskDeadlineReminderJob` |
| `TASK_OVERDUE` | vazifa egasi | qo'ng'iroqcha + bot | o'sha job: muddati o'tgan (oxirgi 24 soat ichida) | o'sha |

Flag: `app.tasks.reminder.enabled` (env `APP_TASK_REMINDER_ENABLED`, standart `true`), cron `app.tasks.reminder.cron`.

### 4.1 O'quvchi / ota-ona kanali flag'lari (standart O'CHIQ)

| Kalit (env) | Tur |
|---|---|
| `app.notify.student-absent.enabled` (`APP_NOTIFY_STUDENT_ABSENT_ENABLED`, default `false`) | `STUDENT_ABSENT` |
| `app.notify.payment-overdue.enabled` (`APP_NOTIFY_PAYMENT_OVERDUE_ENABLED`, default `false`) | `PAYMENT_OVERDUE` |

Bitta joyda tekshiriladi — `dispatch` ning o'quvchi kanali (`NotifyProperties.studentChannelEnabled(type)`). O'chiq
bo'lsa outbox'ga yozilmaydi, `log.info` va `DispatchResult.disabled` (o'quvchilar soni; qarz eslatmasida
`PaymentReminderService.Summary.disabled`). Xodimlar qo'ng'iroqchasi va xodim boti flag'ga bog'liq emas. Flag
yoqilganda eski hodisalar qayta yuborilmaydi — faqat yoqilgandan keyingilari.
Avvalgi turlar (`APP_LINK_REQUEST`, `CHAT_EXTERNAL`, `ABSENCE_NOTICE`, `ATTENDANCE_UNLOCK_*`, `LEAVE_*`) o'zgarmagan.

## 5. Telegram xatolari (`TelegramOutboxWorker`)

`TelegramBotApi.send` → `SendResult(ok, errorCode, description, retryAfter)`. `last_error` — haqiqiy javob
(`HTTP 403: Forbidden: bot was blocked by the user`), token yashirilgan, 500 belgigacha.

| Javob | Qaror |
|---|---|
| ok | SENT |
| 403 | FAILED, qayta urinilmaydi; shu chatning qolgan PENDING qatorlari FAILED; chatning faol `AppIdentity` lari uziladi (`BOT_BLOCKED`) — /start + kontakt bilan qayta ulanadi |
| 429 | urinish sanalmaydi; qator `retry_after` soniyadan keyin (yo'q bo'lsa 30); butun worker shu muddatgacha to'xtaydi |
| boshqa 4xx | FAILED, qayta urinilmaydi |
| 5xx / tarmoq | 1 / 5 / 30 / 120 daqiqa backoff, 5-urinishda FAILED |

## 6. Hali yo'q (keyingi bosqichlar)

* Dars eslatmasi va "to'lov muddati yaqin" — shu `dispatch` ustiga (o'quvchi kanali tayyor).
* Mini App ichida bildirishnomalar/e'lonlar ekrani va sozlamalari (`/api/app/notices`, `notification-settings`).
* Bir foydalanuvchiga kunlik limit, foydalanuvchi bo'yicha turini o'chirish.
* adizone-admin: yangi turlar uchun ikonka/yo'l (`modules/notifications/types.ts`, `lib/ui.ts`, `lib/links.ts`),
  `NOTIFICATIONS_READ` push'ini qabul qilish. Hozir noma'lum tur umumiy ikonka bilan, havolasiz ko'rinadi.

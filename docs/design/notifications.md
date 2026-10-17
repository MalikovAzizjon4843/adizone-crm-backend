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
  priority        HIGH | NORMAL (faqat navbat tartibi)
  origin          AUTOMATIC (standart) | MANUAL — sokin soat qarori (§2 "Sokin soatlar")
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
| Ichki chat (bot) | `TelegramOutboxService.windowKey("chat:{id}:internal")` — 5 daqiqada bitta (kecha-kunduz; chat MANUAL — tunda ham darhol) |

### Sokin soatlar

21:00–08:00 (Asia/Tashkent), qaror — `NotificationRequest.origin` (`MessageOrigin`, buyurtmachi 17.10.2026);
priority endi faqat navbat tartibi (HIGH oldin):

| `origin` | Hodisalar | Tunda |
|---|---|---|
| `AUTOMATIC` (standart) | `STUDENT_ABSENT`, `PAYMENT_OVERDUE`, `PAYMENT_DUE_SOON`, `DIRECTOR_DIGEST`, `TASK_*` (xodim boti) | **hech qaysi priority bilan ketmaydi** — `not_before` keyingi 08:00, ovoz bilan |
| `AUTOMATIC` | `LESSON_REMINDER` | umuman yuborilmaydi (job tunda o'tkazib yuboradi — kechikkan eslatma ma'nosiz) |
| `MANUAL` | chat (`CHAT_INTERNAL`, Mini App chat push'lari), qo'lda chop etilgan e'lon (`NOTICE_NEW`, `NOTICE_APP`), ota-ona bildirgan sabab (`ABSENCE_NOTICE`), ulash so'roviga javob | **darhol**, ovozsiz (`disable_notification`); kunduzi — ovoz bilan |

Chat kaliti (`windowKey`) kecha-kunduz bir xil: 5 daqiqada bitta. Qo'ng'iroqcha sokin soatni bilmaydi (CRM ichida).

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
| `PAYMENT_OVERDUE` | ulangan ota-ona + o'quvchi | bot, NORMAL, `/#/payments` | cron `app.billing.reminder-cron` (10:00): OVERDUE kuni va keyin har `app.notify.payment-overdue.repeat-days` kunda (standart 1 — har kuni), muzlatilganga yo'q | `PaymentReminderService.run` |
| `DIRECTOR_DIGEST` | `app.dashboard.digest.chat-ids` | bot | cron `app.dashboard.digest.cron` (20:00), flag `app.dashboard.digest.enabled` (o'zgarmagan) | `DirectorDigestService.send` |
| `CHAT_INTERNAL` | ichki suhbatning boshqa faol a'zolari | qo'ng'iroqcha (yig'iladi) + ulangan o'qituvchi boti | xabar yuborildi; suhbat oxirigacha o'qilsa — o'qilgan (`NOTIFICATIONS_READ` push) | `ChatService.send` / `markRead` |
| `LEAD_ASSIGNED` | yangi mas'ul (o'zini biriktirsa — yo'q) | qo'ng'iroqcha | qo'lda (`assignLead`), ommaviy — bitta yig'ma `bulk` ("N ta lid"), avto (Meta, sayt, standart mas'ul) — `auto` | `LeadService`, `LeadBulkAssignService`, `LeadSettingsService.assignIfUnassigned` |
| `NOTICE_NEW` | e'lon auditoriyasi rollari (bo'sh — hamma), muallifdan tashqari | qo'ng'iroqcha + ulangan o'qituvchi boti | e'lon ko'rinadigan bo'lganda (yaratildi chop etilgan holda yoki qoralama chop etildi); bir marta | `NoticeService` |
| `TASK_DUE_SOON` | vazifa egasi | qo'ng'iroqcha + ulangan bo'lsa bot | `TaskDeadlineReminderJob` har 15 daqiqa: ochiq, muddatiga ≤ 1 soat ("kun davomida"ga yo'q) | `service/TaskDeadlineReminderJob` |
| `TASK_OVERDUE` | vazifa egasi | qo'ng'iroqcha + bot | o'sha job: muddati o'tgan (oxirgi 24 soat ichida) | o'sha |
| `PAYMENT_DUE_SOON` | ulangan ota-ona + o'quvchi | bot, `/#/payments` | `PaymentDueSoonJob`, cron `app.notify.payment-due-soon.cron` (10:00): keyingi to'lov = bugun + `days-before` (3). Ro'yxat va summa — `DebtorService.expected` (kutilayotgan to'lovlar, R2) bilan AYNAN bir xil: oldindan to'langan davr sanani suradi, oxirgi davr darslar bo'yicha bo'lsa — o'sha summa (+ dars soni, variant `lessons`); summa 0 — yo'q; bir o'quvchi — bitta xabar (guruhlar yig'indisi). Kalit `due:{studentId}:{sana}` | `service/PaymentDueSoonJob` |
| `LESSON_REMINDER` | shu kuni faol yozilmalar (sinov ham; muzlatilgan / chiqqan — yo'q) | bot, `/#/schedule` | `LessonReminderJob` har 15 daqiqa: boshlanishiga `minutes-before` (120) qolgan darslar, oyna `(now+M−15, now+M]`; ACTIVE guruh, bayram va CANCELLED / MOVED (asl kun) — yo'q, EXTRA / ko'chirilgan kun — bor (`GroupScheduleService.hasLessonOn`). Yuborish payti sokin soatga (21:00–08:00) to'g'ri kelsa — **umuman yuborilmaydi**. Kalit `lesson:{groupId}:{sana}:{studentId}` | `service/LessonReminderJob` |
| `NOTICE_APP` | e'lon auditoriyasida `STUDENT` → o'quvchilar (SELF), `PARENT` → ota-onalar | bot | e'lon ko'rinadigan bo'lganda (NOTICE_NEW bilan bir paytda); "hamma" (bo'sh) auditoriya — o'quvchilarga yo'q. Kalit `notice-app:{id}` | `NoticeService` |

Flag: `app.tasks.reminder.enabled` (env `APP_TASK_REMINDER_ENABLED`, standart `true`), cron `app.tasks.reminder.cron`.

### 4.1 O'quvchi / ota-ona kanali flag'lari (standart O'CHIQ)

| Kalit (env) | Tur |
|---|---|
| `app.notify.student-absent.enabled` (`APP_NOTIFY_STUDENT_ABSENT_ENABLED`, default `false`) | `STUDENT_ABSENT` |
| `app.notify.payment-overdue.enabled` (`APP_NOTIFY_PAYMENT_OVERDUE_ENABLED`, default `false`); `repeat-days` (`APP_NOTIFY_PAYMENT_OVERDUE_REPEAT_DAYS`, standart 1 — har kuni) | `PAYMENT_OVERDUE` |
| `app.notify.payment-due-soon.enabled` (`APP_NOTIFY_PAYMENT_DUE_SOON_ENABLED`, default `false`); `days-before` (3), `cron` | `PAYMENT_DUE_SOON` |
| `app.notify.lesson-reminder.enabled` (`APP_NOTIFY_LESSON_REMINDER_ENABLED`, default `false`); `minutes-before` (120), `cron` | `LESSON_REMINDER` |
| `app.notify.notice-to-students.enabled` (`APP_NOTIFY_NOTICE_TO_STUDENTS_ENABLED`, default `false`) | `NOTICE_APP` |

Bitta joyda tekshiriladi — `dispatch` ning o'quvchi kanali (`NotifyProperties.studentChannelEnabled(type)`). O'chiq
bo'lsa outbox'ga yozilmaydi, `log.debug` (job'lar aylanish xulosasini `info` da yozadi) va `DispatchResult.disabled` (o'quvchilar soni; qarz eslatmasida
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

## 6. Mini App e'lonlari (V85)

* `GET /api/app/notices` — identity munosabatlaridan auditoriya: SELF → `STUDENT`, PARENT → `PARENT`. Faqat **aynan
  shu rolga** qaratilgan (targetRoles, yoki eski `targetRole` / `publishedTo` = STUDENTS | PARENTS), faol, chop
  etilgan, vaqti kelgan, muddati o'tmagan e'lonlar (`NoticeRepository.findAppVisible`). "Hamma" (bo'sh auditoriya)
  e'lon — xodimlar uchun, ilovada ko'rinmaydi. Javob: `{items: [{id, title, content, noticeType, publishedAt,
  expiresAt, read}], unread}`.
* `POST /api/app/notices/{id}/read` — idempotent, `app_notice_reads`; ko'rinmaydigan e'lon — 404 `app.notice.notFound`.
* Auditoriya modeli o'zgarmadi: `notice_target_roles.role` allaqachon `STUDENT` / `PARENT` ni qabul qiladi.
* E'lon chop etilganda: xodim rollari → `NOTICE_NEW` (qo'ng'iroqcha), `STUDENT` / `PARENT` → `NOTICE_APP` (bot,
  flag `notice-to-students`). E'lon o'chirilsa `app_notice_reads` ham tozalanadi.

## 7. Bildirishnoma sozlamalari (Mini App, V85)

* `GET /api/app/notification-settings` → `[{topic, enabled, available}]`, mavzular: `absent` (STUDENT_ABSENT),
  `payment_overdue`, `payment_due_soon`, `lesson` (LESSON_REMINDER), `notice` (NOTICE_APP). `enabled` — foydalanuvchi
  tanlovi (qator yo'q — `true`), `available` — server flag'i.
* `PUT /api/app/notification-settings` `{"lesson": false}` — qisman; noma'lum mavzu — 400
  `app.notifySettings.unknownTopic`, `null` — 400 `app.notifySettings.valueRequired`.
* `dispatch` o'quvchi kanalida hurmat qiladi: o'chirgan identity'ga yozilmaydi, `DispatchResult.optedOut` da sanaladi.
  Tartib: server flag (o'chiq — `disabled`) → foydalanuvchi sozlamasi (`optedOut`) → dedupe.

## 8. Hali yo'q (keyingi bosqichlar)

* Mini App frontend: e'lonlar ekrani va sozlamalar sahifasi (backend tayyor).
* Bir foydalanuvchiga kunlik limit.
* adizone-admin: yangi turlar uchun ikonka/yo'l (`modules/notifications/types.ts`, `lib/ui.ts`, `lib/links.ts`),
  `NOTIFICATIONS_READ` push'ini qabul qilish. Hozir noma'lum tur umumiy ikonka bilan, havolasiz ko'rinadi.

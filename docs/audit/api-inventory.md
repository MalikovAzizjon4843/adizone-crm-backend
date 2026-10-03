# Adizone CRM backend — API inventarizatsiyasi

> Bu hujjat yangi Vue 3 + TS admin frontend uchun **barcha REST endpointlarni** jamlaydi. Holat 2026-09-29 (`main` @ `876897a`), tahlil faqat kod o'qish orqali qilingan.
> Har bir yo'l `src/main/java/com/crm/` ga nisbatan beriladi. Jadvallarda qisqartma ishlatilgan: `controller/X.java` ichida faqat `:qator`.
> Koddan tasdiqlanmagan har bir da'vo **TAXMIN** deb belgilangan.
> Tushuntirishlar, biznes qoidalar va muammolar: [backend-audit.md](backend-audit.md). Qisqa xulosa: [backend-summary.md](backend-summary.md).
> Jadvallarda "Muammolar #T1" yoki "(Muammolar #A2)" ko'rinishidagi havolalar bor. Ular backend-audit.md dagi quyidagi bo'limlarga olib boradi:
> - 1-bo'lim → §9.7
> - 2-bo'lim → §9.8
> - 3-bo'lim → §9.9
> - 4-bo'lim → §9.10

## Yangilanishlar (2026-10-02, phase 5)

> Bu hujjatning qolgan qismi oldingi holatda (qator raqamlari eskirgan bo'lishi mumkin). Quyidagi o'zgarishlar tegishli jadvallarga kiritilgan; to'liq ro'yxat va sabablar — [phase5-audit.md §14](phase5-audit.md).

| Endpoint | O'zgarish |
|---|---|
| Barcha "authenticated" yo'llar, `/ws` | Faqat STAFF (SA, A, SM, T, ACC). ST/P login → 403 `auth.roleNotAllowed` |
| Parol bilan bog'liq hamma joy | Parol 8–72 belgi; parol tiklansa/almashtirilsa eski access tokenlar 401 |
| `POST /api/users`, `create-for-teacher` | Band login → 409 `user.username.taken` ("200 ALREADY_EXISTS" yo'q) |
| `/api/users/**` (ADMIN aktor) | ADMIN/SA hisoblari va rollari — faqat SA (403) |
| `GET /api/users`, `GET /api/teachers` | `page` berilsa `PageResponse` + `q`, `role`/`status`, `active`; `page` yo'q — eski massiv |
| `PUT /api/teachers/{id}` | Qisman yangilash (yuborilmagan maydon o'zgarmaydi) |
| `DELETE /api/teachers/{id}`, PUT `status: INACTIVE` | Faol guruhlar bo'lsa 409 `teacher.hasActiveGroups` (`data.groups`) |
| `POST /api/teachers/{userId}/ensure-profile` | SKIPPED → 409 `teacher.profile.*` |
| `/api/notices/**` | Auditoriya `targetRoles`; lenta, `unread-count`, detal joriy rol bo'yicha |
| Imtihonlar | `eligible-students`, `register-student`, `calculate-payment` — TEACHER faqat o'z guruhlari; `changeReason` → `editNote` aliasi |
| `POST /api/contracts/generate` | Raqam `CTR-YYYY-NNNNN`, har yil 00001 dan |
| Har qanday endpoint | Baza cheklovi: 409 `error.conflict.*` / 400 `error.data.invalid`; yetishmagan parametr 400 `error.param.missing` |

## Yangilanishlar (2026-10-02, filtrlar va ta'til preview)

> Batafsil (javob shakli, xato kodlari): [leaves-exams-contracts-api.md](../design/leaves-exams-contracts-api.md) §1.8, §2, §4, §6.

| Endpoint | Rollar | O'zgarish |
|---|---|---|
| `GET /api/substitutions/my` | T | Query `from` (standart bugun), `to` (standart `from` + 1 yil, ko'pi bilan 1 yil). Endi o'qituvchi **asosiy yoki o'rinbosar** bo'lgan faol belgilar; har yozuvda `role`: `ORIGINAL` / `SUBSTITUTE`. 400 `substitution.range.invalid` / `substitution.range.tooLong` |
| `GET /api/exams` | SA, A, **ACC**, T | Query `groupId`, `from`, `to` (imtihon sanasi), `status` = `ACTIVE` (standart) / `UPCOMING` / `PAST` / `INACTIVE`. 400 `exam.status.invalid`, `exam.dates.invalid`. TEACHER — o'z guruhi yoki o'ziga biriktirilgan (guruhsiz ham) |
| `GET /api/exams/{id}` | SA, A, **ACC**, T | ACCOUNTANT o'qiy oladi; `GET /api/exams/{id}/eligible-students` ham (yaratish/tahrir/o'chirish/natijalar — 403) |
| `GET /api/contracts` | SA, A | Query `q` (o'quvchi ismi/familiyasi, telefoni yoki ota-ona telefoni, shartnoma raqami; `%`/`_` oddiy belgi), `from`, `to` (shartnoma sanasi). 400 `contract.dates.invalid` |
| `GET /api/leaves/{id}/deduction-preview` | SA, A | **Yangi.** Ta'til haqsiz deb hisoblanganda ayirma (PENDING ham), oylar bo'yicha `[{month, year, workDays, unpaidDays, fixedSalary, amount}]`; formula payroll bilan bir xil, bayram ish kuni emas; qoida yo'q → `fixedSalary`/`amount` null |

## Yangilanishlar (2026-10-03, phase 6 — "Tez orada" sahifalari)

> To'liq shartnoma: [phase6-api.md](../design/phase6-api.md). Migratsiya V65.

| Endpoint | Rollar | O'zgarish |
|---|---|---|
| `GET /api/analytics/overview` | SA, A | **Yangi.** Moliya, o'quvchilar, lidlar, guruhlar + oldingi davr va vaqt qatorlari |
| `GET /api/analytics/staff?role=TEACHER\|SALES\|ADMIN` | SA, A; SH — faqat `SALES` | **Yangi javob** (`role` bilan). `role` siz — eski javob, deprecated |
| `GET /api/analytics/dashboard`, `/revenue`, `/students`, `/marketing/sources`, `/staff/summary`, `/staff/{userId}/trend` | SA, A | **Deprecated** |
| `GET /api/homework?groupId&from&to`, `GET/PUT /api/homework/{id}/students` | SA, A, T (o'z guruhi) | Filtrlar, o'quvchilar bo'yicha holat/baho; fayl biriktirish; eski submission endpointlari deprecated va endi egalikni tekshiradi |
| `POST /api/groups/{fromId}/promote/preview`, `/promote` | SA, A | **Yangi.** Guruhga ommaviy ko'chirish, Idempotency-Key, audit `TRANSFER` |
| Chat `chat.*` matnlari | — | Argumentsiz 9 ta o'zbekcha matnda ikki apostrof xatosi tuzatildi |

## Yangilanishlar (2026-10-02, SALES_HEAD qarorlari)

> SH ning to'liq ruxsatlar jadvali — [§0.2a](#02a-sales_head-sh-effektiv-ruxsatlari).

| Endpoint | O'zgarish |
|---|---|
| `DELETE /api/tasks/{id}` | Begona vazifani faqat SA/A o'chiradi; SH (va SM) — faqat o'zi yaratganini. Avval SH hammasini o'chira olardi |
| `DELETE /api/leads/notes/{id}` | Begona izohni faqat SA/A o'chiradi; SH — faqat o'zinikini. Avval SH hammasini o'chira olardi |
| `GET /api/exams/{id}/eligible-students` | ACCOUNTANT ham (faqat o'qish). `/results` — ACC uchun yopiq |
| `POST /api/notices` | `targetRoles: ["SALES_HEAD"]` qabul qilinadi (avval ham enum bo'yicha qabul qilinardi; V60 eski `target_role` ko'chirishida ham SALES_HEAD bor) |
| `POST /api/leads/{id}/convert`, `/api/leads/operators`, dashboard operatorlari | O'zgarish yo'q — SH allaqachon bor edi, test bilan tasdiqlandi |

## Yangilanishlar (2026-10-03, onboarding holati — V67)

> Xodim qaysi frontend turlarini ("tour") ko'rganini saqlash. Jadval `user_onboarding (user_id, tour_key, seen_at)`, PK `(user_id, tour_key)`, FK `users` ON DELETE CASCADE. Batafsil — [Users](#users--controllerusercontrollerjava) jadvali.

| Endpoint | Rollar | Javob | Izoh |
|---|---|---|---|
| `GET /api/users/me/onboarding` | **barcha STAFF** (SA, A, SH, SM, ACC, T) | `ApiResponse<[{key, seenAt}]>`, `key` bo'yicha tartiblangan | Faqat joriy foydalanuvchi yozuvlari |
| `PUT /api/users/me/onboarding/{key}` | barcha STAFF | `ApiResponse<{key, seenAt}>` 200 | Idempotent: qayta chaqiruv xato emas, birinchi `seenAt` saqlanadi. Kalit noto'g'ri → 400 `onboarding.key.invalid`; 200 tadan ortiq kalit → 409 `onboarding.limit` |
| `DELETE /api/users/me/onboarding` | barcha STAFF | `ApiResponse<{deleted: n}>` 200 | Hammasini tozalash ("turlarni qayta ko'rish"); bo'sh bo'lsa `deleted: 0` |

## Yangilanishlar (2026-10-03, xodim bildirishnomalari — V71; Telegram ilova CRM endpointlari — V68–V70)

> **Bildirishnomalar markazi (CRM qo'ng'iroqchasi, phase5-audit S-04).** Jadval `user_notifications (user_id, type, title, body, link, entity_type, entity_id, read_at, created_at)`, FK `users` ON DELETE CASCADE; 90 kundan eskilari har kecha 04:00 da o'chiriladi. Legacy `notifications` jadvali (kodsiz, 0 qator, `read_at`/`link` yo'q) **ishlatilmaydi** va o'chirilmaydi (X-06 — alohida qaror).
> Bildirishnoma biznes amali **commit bo'lgandan keyin** yoziladi va push qilinadi (rollback — bildirishnoma yo'q); amalni bajargan xodimning o'ziga yuborilmaydi. Matn — faqat o'zbekcha, yozilish paytida tayyorlanadi.

| Endpoint | Rollar | Javob | Izoh |
|---|---|---|---|
| `GET /api/notifications/me?page&size` | **barcha STAFF** | `ApiResponse<PageResponse<{id, type, title, body, link, entityType, entityId, read, readAt, createdAt}>>` | Faqat o'zinikini, yangilari oldin; `page` 0 dan, `size` 1..100 (standart 20) |
| `GET /api/notifications/me/unread-count` | barcha STAFF | `{count}` | Qo'ng'iroqcha belgisi |
| `POST /api/notifications/{id}/read` | barcha STAFF | bitta bildirishnoma (`read: true`) | Idempotent; begona yoki yo'q → **404** `notification.notFound` |
| `POST /api/notifications/read-all` | barcha STAFF | `{updated: n}` | Faqat o'zinikini |
| STOMP `SUBSCRIBE /user/queue/notifications` | barcha STAFF | `{type: "NOTIFICATION", notification: {...yuqoridagi qator}, unreadCount}` | CH-01 oq ro'yxatiga qo'shildi (`ChatChannelInterceptor`); har sessiyaning o'z navbati |

| `type` | Qachon | Kimga | `entityType` / `link` |
|---|---|---|---|
| `APP_LINK_REQUEST` | Telegram ilovada yangi qo'lda ulash so'rovi | faol SA, A, SH | `AppLinkRequest` / `/app-link-requests` |
| `CHAT_EXTERNAL` | ilova foydalanuvchisi EXTERNAL suhbatga yozdi | suhbatning faol xodim a'zolari; shu suhbat bo'yicha **o'qilmagani yangilanadi** (har xabarga yangi qator emas) | `Conversation` / `/chat?conversation={id}` |
| `ABSENCE_NOTICE` | o'quvchi / ota-ona sabab bildirdi | guruh o'qituvchisi + shu kungi o'rinbosar (useri bo'lsa) | `AbsenceNotice` / `/attendance?groupId=&date=` |
| `ATTENDANCE_UNLOCK_REQUEST` | `POST /api/attendance/unlock-requests` (CRM yoki ilova) | faol SA, A | `AttendanceUnlockRequest` / `/attendance/unlock-requests` |
| `ATTENDANCE_UNLOCK_DECIDED` | `PATCH …/{id}/approve` yoki `/reject` | so'ragan o'qituvchi | `AttendanceUnlockRequest` / `/attendance?groupId=&date=` |
| `LEAVE_REQUEST` | `POST /api/leaves` | faol SA, A (ariza beruvchidan tashqari) | `Leave` / `/leaves/{id}` |
| `LEAVE_DECIDED` | `POST /api/leaves/{id}/approve` yoki `/reject` | ta'tildagi xodim (+ ariza bergan, boshqa bo'lsa) | `Leave` / `/leaves/{id}` |

`link` — frontend yo'li uchun ishora; ishonchli havola — `entityType` + `entityId`.

**Telegram ilova — CRM endpointlari** (batafsil: [miniapp-api.md](../design/miniapp-api.md) §8):

| Endpoint | Rollar | Izoh |
|---|---|---|
| `GET /api/app-link-requests?status` | SA, A, SH | Qo'lda ulash so'rovlari; `status` PENDING (standart) \| APPROVED \| REJECTED \| CANCELLED \| ALL |
| `POST /api/app-link-requests/{id}/approve` | SA, A, SH | Identity ulanadi, bot xabari; 409 `appLinkRequest.notPending` / `noMatch` |
| `POST /api/app-link-requests/{id}/reject` `{reason}` | SA, A, SH | Sabab majburiy (≤ 500) — ilovada `GET /api/app/link/manual/status` da va bot xabarida ko'rinadi |
| `GET /api/absence-notices?groupId&date` | SA, A, T | O'quvchi/ota-ona sabab bildirishlari; T — o'z guruhi yoki o'rinbosarlik kuni |
| `GET /api/attendance/group/{id}?date` | (o'zgarmagan) | Har qatorda `absenceNotice` yoki `null` |
| `/api/chat/**`, STOMP | (o'zgarmagan) | `type: EXTERNAL` suhbatlar (`externalTarget`, `externalStatus`); ilova xabarida `senderType: APP`, `senderId: null` |

## 0. Qanday o'qish kerak

### 0.1 Endpointlar soni
| Bo'lim | Controller → yo'llar soni | Jami |
|---|---|---|
| 1. Auth, foydalanuvchilar, o'qituvchilar, o'quvchilar | Auth 7, User 12, Role 1, Teacher 18, TeacherDashboard 1, Student 25, Parent 9, Settings 1, Enum 3, Search 1 | 78 |
| 2. O'quv jarayoni | Group 13, Course 5, Classroom 5, Academic 25, Attendance 5, AttendanceUnlockRequest 6, Homework 9, Exam 13, Notice 10, Leave 8, Promotion 6, Contract 12 | 117 |
| 3. CRM, lidlar, analitika, tizim | Lead 21, LeadStage 5, LeadImport 4, Import 4, MetaAdmin 11, MetaWebhook 2, Marketing 1, Task 10, Dashboard 1, Analytics 8 (7 metod), AuditLog 3, AdminRepair 8 | 78 |
| 4. Moliya, fayllar, chat (REST) | Payment 11, Finance 3, Expense 2, CashRegister 12, Payroll 10, SalaryRule 4, BonusPenalty 10, File 2, Chat 11 | 65 |
| **Jami** | 43 controller | **338 yo'l / 337 metod** |
| WebSocket (STOMP) | 5 ta `@MessageMapping` va server topiklari | [backend-audit.md §7](backend-audit.md) |

### 0.2 Rol qisqartmalari
| Qisqartma | Rol |
|---|---|
| **SA** | SUPER_ADMIN |
| **A** | ADMIN |
| **SH** | SALES_HEAD (sotuv bo'limi rahbari) — effektiv ruxsatlari: [§0.2a](#02a-sales_head-sh-effektiv-ruxsatlari) |
| **SM** | SALES_MANAGER |
| **T** | TEACHER |
| **ACC** | ACCOUNTANT |
| **ST** | STUDENT |
| **P** | PARENT |

- Manba: `entity/enums/UserRole.java`.
- "Har qanday auth" yoki **AUTH** — eski hujjat atamasi. **2026-10-02 dan** `authenticated` qoidalarining hammasi **STAFF** = SA, A, SH, SM, T, ACC (`SecurityConfig.STAFF_ROLES`); ST/P login qila olmaydi (403 `auth.roleNotAllowed`). Batafsil — [phase5-audit.md §14](phase5-audit.md).
- **"Effektiv rollar"** = `config/SecurityConfig.java` dagi URL qoidasi ∩ `@PreAuthorize`. SecurityConfig'da birinchi mos kelgan qoida ishlaydi (qoidalar jadvali: backend-audit.md §2.1).
- RoleHierarchy yo'q: SA har joyda alohida sanaladi.
- TEACHER uchun egalik (o'z guruhi yoki o'z o'quvchisi) servisda `service/TeacherAccessService.java` orqali tekshiriladi:
  - `assertOwnsGroup` — `:82-92`
  - `assertOwnsStudent` — `:94-102`
  - `resolveTeacherScope` — `:66-71`

  Jadvallarda bu tekshiruv qayerda bor va qayerda yo'qligi ko'rsatilgan.

### 0.2a SALES_HEAD (SH) effektiv ruxsatlari

> Holat 2026-10-02 (`billing-v2`). Quyidagi jadvallardagi "Rollar" ustuni SH dan oldin yozilgan — SH uchun **shu jadval ustun**.
> Effektiv = `SecurityConfig` URL qoidasi ∩ `@PreAuthorize` ∩ servis tekshiruvi (`LeadAccessService`). SH `STAFF_ROLES` da.
> Testlar: `security/SalesHeadAccessTest`.

| Soha | Endpoint | SH | Izoh (SM bilan farq) |
|---|---|---|---|
| Lidlar | `GET /api/leads`, `/kanban-stats`, `/{id}`, `/{id}/comments`, `/notes`, `/tasks`, `/timeline`, `/history` | ✅ hamma lid | SM — faqat o'ziga biriktirilgan |
| | `POST /api/leads`, `PATCH /{id}/status`, `/amount`, `POST /{id}/comments`, `/notes` | ✅ hamma lid | `assignedUserId` — istalgan operator (SM — faqat o'zi) |
| | `PATCH /api/leads/{id}/assign`, `GET /api/leads/operators` | ✅ | SM — 403. Operatorlar ro'yxatida SA, A, **SH**, SM |
| | `GET /api/leads/stats` | ✅ | SM — 403 |
| | `POST /api/leads/{id}/convert` | ✅ hamma lid | SM — faqat o'z lidi |
| | `PUT /api/leads/notes/{id}` | faqat o'z izohi | hammaga bir xil (muallif) |
| | `DELETE /api/leads/notes/{id}` | faqat **o'z** izohi | begona izoh — faqat SA/A (2026-10-02 dan; avval SH ham o'chirardi) |
| | `GET /api/leads/export` | ❌ 403 | faqat SA/A |
| Lid importi | `/api/leads/import/**`, `/api/import/**` | ❌ 403 | SA/A (partiyani o'chirish — SA) |
| Lid bosqichlari | `GET /api/lead-stages` | ✅ | barcha xodimlar |
| | `POST/PUT/DELETE /api/lead-stages/**`, `PATCH /reorder` | ❌ 403 | SA/A |
| Meta | `/api/meta/**` | ❌ 403 | SA/A |
| Vazifalar | `GET /api/tasks`, `/my`, `/stats`, `/{id}` | ✅ jamoa vazifalari | SM — faqat o'ziga biriktirilgan |
| | `POST /api/tasks` (`assignedTo` — boshqa xodim), `PATCH /{id}`, `/complete`, `/postpone`, `/reassign` | ✅ | SM boshqaga yoza olmaydi. Mas'ul: SA, A, SH, SM (faol) |
| | `DELETE /api/tasks/{id}` | faqat **o'zi yaratgan** | begona vazifa — faqat SA/A (2026-10-02 dan; avval SH ham o'chirardi) |
| Dashboard | `GET /api/dashboard/director` | ✅ faqat `funnel`, `operators` | qolgan bo'limlar `null` + `meta.hiddenSections` |
| | `/director/funnel/leads`, `/director/operators`, `/operators/{userId}/leads`, `/operators/{userId}/tasks`, `/director/trend?metric=funnel.*\|operators.*` | ✅ | operatorlar: ADMIN, **SH**, SM |
| | `/director/collections/**`, `/debtors`, `/attendance/**`, `/trials/**`, `/retention/**` | ❌ 403 `dashboard.section.forbidden` | |
| | `/api/dashboard/stats`, `/api/analytics/**` | ❌ 403 | SA/A |
| E'lonlar | `GET /api/notices`, `/active`, `/latest`, `/unread-count`, `/{id}`; `POST /read-all`, `/{id}/read` | ✅ | lenta — `targetRoles` da SALES_HEAD bo'lgan yoki hammaga e'lonlar |
| | `POST/PUT/DELETE /api/notices` | ❌ 403 | SA/A. `targetRoles` da `SALES_HEAD` qabul qilinadi |
| Ta'til | `POST /api/leaves`, `GET /my`, `/summary` (o'zi), `/{id}` (o'zi), `POST /{id}/cancel` (o'z PENDING) | ✅ | barcha xodimlar kabi |
| | `GET /api/leaves`, `/pending/count`, `approve`, `reject`, `/affected-lessons`, `/deduction-preview` | ❌ 403 | SA/A |
| Chat | `/api/chat/**`, WebSocket | ✅ | a'zolik bo'yicha (`ChatAccessService`) |
| Oylik | `/api/payroll/**`, `/api/salary-rules/**` | ❌ 403 | SH — oylik **oluvchi**: qoida `role: SALES_HEAD` (`fixedSalary`, `perNewStudent`), hisob SM kabi |
| Bonus/jarima | `/api/bonus-penalties/**` | ❌ 403 | SH — `targetType: STAFF` bonus **oluvchisi** (SA/A/ACC yozadi) |
| Foydalanuvchilar | `/api/users/**` | ❌ 403 | SA/A (SH ni A yaratadi) |

### 0.3 Umumiy konventsiyalar
**Autentifikatsiya:** `Authorization: Bearer <accessToken>` sarlavhasi. Token faqat `sub/iat/exp` saqlaydi, ichida rol yo'q. Rolni login javobidan yoki `GET /api/auth/me` dan oling.

**Envelope:** `ApiResponse<T>` = `{ success: boolean, message: string|null, data: T, meta?: any }` (`dto/response/ApiResponse.java:4-28`).
- `meta` faqat `GET /api/payments` da keladi.
- Istisnolar (envelope'siz): `GET /api/teachers/{id}/kpi`, `GET /api/teachers/me/kpi`, `GET /api/timetable/by-room`, barcha eksport va shablon endpointlari (`byte[]`), `GET /api/files/{name}`, Meta webhook (`text/plain`).

**Sahifalash** — uch xil shakl:
- **`PageResponse<T>`** = `{content, pageNumber, pageSize, totalElements, totalPages, last}` (`dto/response/PageResponse.java:5-12`) — ko'pchilik endpointlarda.
- **Spring `Page<T>`** (`PageImpl` to'g'ridan-to'g'ri, Boot 3.2) = `{content, pageable:{pageNumber,pageSize,offset,paged,unpaged,sort}, totalElements, totalPages, last, first, size, number, numberOfElements, sort, empty}`. Qayerda: `GET /api/payments`, `/api/expenses`, `/api/finance/expenses`, `/api/cash-registers/{id}/transactions`.
- **Maxsus shakllar:** lid timeline `{items, page: PageInfo, openTasks}`, chat tarixi — kursorli `List`.
- `page` 0 dan boshlanadi. **`sort` parametri hech qayerda yo'q.** `size` uchun yuqori chegara odatda yo'q. Default `size` 20, ba'zi joylarda 50. Manfiy `page` → 400.

**Sana va vaqt** (Jackson sozlanmagan, Boot defaultlari):
- `LocalDate` → `"2026-09-29"`
- `LocalDateTime` → `"2026-09-29T10:15:30.123"` (zonasiz)
- `LocalTime` → `"HH:mm:ss"` (TAXMIN)
- `Instant` → `"...Z"`
- Query parametrlar: `@DateTimeFormat(ISO.DATE)` bor joyda noto'g'ri format 400 qaytaradi. `String` + `LocalDate.parse` joylarda esa **500** (payments, cash-registers, bonus summary).

**Pul:** `BigDecimal` JSON'da **number** bo'lib chiqadi, valyuta UZS. Frontendda float yaxlitlashiga e'tibor bering. Ko'rsatish uchun `Intl.NumberFormat` ishlating; kerak bo'lsa decimal kutubxona oling.

**Enumlar:** JSON'da `name()` sifatida keladi (`@JsonValue` yo'q). `/api/enums` faqat payment-methods, task-types va task-statuses ni `{value,label,icon}` ko'rinishida beradi. Barcha enum qiymatlari: backend-audit.md §5.2.
- `PaymentMethod` legacy aliaslari (`PLASTIC`, `ONLINE`, `BANK_TRANSFER`) faqat `String` maydonlarda va filtrlarda qabul qilinadi. Body'dagi enum maydonida ular 400 beradi (`entity/enums/PaymentMethod.java:36-40`).

**Telefon:** `@JsonDeserialize(PhoneDeserializer)` bor maydonlar kanonik `+998XXXXXXXXX` ga keltiriladi (`config/PhoneDeserializer.java:26-33`, `util/PhoneUtils.java:31-50`). Istalgan formatda yuborish mumkin.

**Multipart:**
- Maydon nomi hamma joyda `file`.
- Limit: fayl 4MB, so'rov 8MB (`application.yml:7-8`). Oshsa 400 (413 emas).

**Xatolar** — uch xil shakl (batafsil: backend-audit.md §4.2):
| Holat | Shakl |
|---|---|
| 400 / 404 / 409 | `ErrorResponse {timestamp, status, error, message, validationErrors?}` |
| 401 / 403 / 404-route / 405 | `ApiResponse {success:false, message}` |
| 500 | `{timestamp, status, error, message, path}` |

Majburiy query param yuborilmasa **500** qaytadi.

**Til:** `Accept-Language` sarlavhasi: `uz` (default), `ru`, `en`. To'liq tarjima qilinadigani faqat validatsiya xabarlari.

**HTTP semantikasi:**
- Create odatda 201 (istisnolar jadvallarda ko'rsatilgan).
- DELETE → 200 + `ApiResponse<Void>`.
- Ko'p DELETE aslida soft-delete (`isActive=false` yoki `status` o'zgaradi). Qaysi biri qaysi ekani jadvallarda "Izoh" ustunida.

---

## 1. Auth, foydalanuvchilar, o'qituvchilar, o'quvchilar

### Auth — `controller/AuthController.java`

- Base path: `/api/auth` (`AuthController.java:28`). Class-level `@PreAuthorize`: yo'q.
- SecurityConfig: `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout` → `permitAll` (`config/SecurityConfig.java:73-77`); qolganlari → `anyRequest().authenticated()` (`SecurityConfig.java:199`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | /api/auth/login | AuthController#login (`AuthController.java:37`) | public | body `LoginRequest` (@Valid) | `ApiResponse<AuthResponse>` 200, message "Login successful" | yo'q | Xato parol → 401 `ApiResponse` (`GlobalExceptionHandler.java:74-82`). Foydalanuvchining BARCHA eski refresh tokenlari o'chiriladi (`AuthService.java:80`) → bitta faol sessiya. `lastLogin` yangilanadi. Audit LOGIN/LOGIN_FAILED. |
| POST | /api/auth/refresh | AuthController#refresh (`:43`) | public | body `RefreshTokenRequest` (@Valid) | `ApiResponse<AuthResponse>` 200, "Token refreshed" | yo'q | Rotation: o'sha DB yozuvida token qiymati yangilanadi, eski refresh token endi ishlamaydi (`AuthService.java:137-140`). Noto'g'ri/revoked → 401; muddati o'tgan → revoke + 401 (`:122-130`). Nofaol user → 401 (UsernameNotFoundException → AuthenticationException handler). |
| POST | /api/auth/logout | AuthController#logout (`:50`) | public | body `RefreshTokenRequest` (@Valid, `refreshToken` majburiy) | `ApiResponse<Void>` 200, "Logged out", data=null | yo'q | Faqat refresh tokenni revoke qiladi (`AuthService.java:156-162`); topilmasa ham 200. Access token server tomonda bekor qilinmaydi (stateless) — muddati tugaguncha ishlaydi. |
| GET | /api/auth/me | AuthController#getCurrentUser (`:57`) | Har qanday auth | — | `ApiResponse<UserResponse>` | yo'q | `AuthService.getCurrentUser` (`AuthService.java:164-181`). |
| PUT | /api/auth/profile | AuthController#updateProfile (`:64`) | Har qanday auth | body `UpdateUserRequest` (**@Valid YO'Q**) | `ApiResponse<UserResponse>`, "Profile updated" | yo'q | Faqat firstName/lastName/email/phone o'zgaradi; `role`/`isActive` e'tiborsiz (`:70-73`). Validatsiya va unikallik tekshiruvi ishlamaydi (Muammolar #A3). |
| POST | /api/auth/profile/photo | AuthController#uploadProfilePhoto (`:91`) | Har qanday auth | multipart `file` (required) | `ApiResponse<UserResponse>`, "Rasm saqlandi" | yo'q | Fayl nomi `<uuid>_profile_<userId>.jpg`, `photoUrl` qaytadi. Xato → 400 "Rasm yuklashda xatolik: …" (`:105-110`). |
| PUT | /api/auth/change-password | AuthController#changeOwnPassword (`:113`) | Har qanday auth | body `ChangePasswordRequest` (@Valid) | `ApiResponse<Void>`, "Password changed" | yo'q | **`currentPassword` tekshirilmaydi**, refresh tokenlar revoke qilinmaydi (`:117-121`) — Muammolar #A1. |

#### Auth batafsil (frontend shartnomasi)

- **Login request** `LoginRequest` (`dto/request/LoginRequest.java:7-12`): `username: String` @NotBlank; `password: String` @NotBlank.
- **Login/Refresh response** `AuthResponse` (`dto/response/AuthResponse.java:5-15`):
  - `accessToken: String` (JWT HS256)
  - `refreshToken: String` (UUID v4 matni, JWT EMAS — `AuthService.java:79`, `:137`)
  - `tokenType: String` = `"Bearer"`
  - `userId: Long`, `username: String`, `firstName: String`, `lastName: String`
  - `role: UserRole` (enum string)
  - `expiresIn: Long` = har doim `86400` (qattiq yozilgan, `AuthService.java:99`, `:151`) — **haqiqiy muddatga mos EMAS**, qarang Muammolar #A2.
- **Refresh request / Logout request** `RefreshTokenRequest` (`dto/request/RefreshTokenRequest.java:7-11`): `refreshToken: String` @NotBlank.
- **Tokenlar qayerda**: ikkalasi ham faqat **JSON body** da. Cookie ishlatilmaydi (Set-Cookie yo'q). CORS `allowCredentials(true)` yoqilgan (`SecurityConfig.java:235`), lekin kerak emas. `exposedHeaders: Authorization, Content-Type` (`:234`).
- **Muddatlar**: access token = `jwt.expiration: 28800000` ms = **8 soat** (`application.yml:58`; izohda xato "24 hours"); refresh token = `jwt.refresh-expiration: 604800000` ms = **7 kun** (`application.yml:59`, `AuthService.java:85`, `:139`).
- **RefreshToken entity** (`entity/RefreshToken.java:15-42`, jadval `refresh_tokens`): `id: Long`, `token: String` (unique, len 500), `user: User` (ManyToOne LAZY, `user_id`), `expiresAt: LocalDateTime`, `isRevoked: Boolean` (default false), `createdAt: LocalDateTime` (@PrePersist). Repository: `findByTokenAndIsRevokedFalse`, `deleteByUserId`, `revokeAllByUserId`, `findExpiredTokens` (`repository/RefreshTokenRepository.java:17-32`).
- **Sessiya bekor qilish**: login → user'ning barcha refresh tokenlari DELETE (`AuthService.java:80`); admin parol tiklashi (`UserService.java:346`) va userni nofaol qilish (`UserService.java:397-399`) → `revokeAllByUserId`.
- **401 oqimi**: token yo'q/yaroqsiz/muddati o'tgan → filtr xatoni yutadi (`JwtAuthenticationFilter.java:58-60`) → entry point `401 {success:false, message:"Avtorizatsiya talab qilinadi"}` (`SecurityConfig.java:202-208`). Ruxsat yo'q → `403 {success:false, message:"Ruxsat yo'q"}` (`:209-215`). Frontend: 401 da bir marta `/api/auth/refresh`, u ham 401 bo'lsa → login sahifasi.
- **`/me`**: `GET /api/auth/me` → `UserResponse` (pastda).

#### DTO tafsilotlari (Auth)

- `ChangePasswordRequest` (`dto/request/ChangePasswordRequest.java:7-11`): `newPassword: String` @NotBlank (uzunlik cheklovi YO'Q); `currentPassword: String` (ixtiyoriy, **ishlatilmaydi**).
- `UpdateUserRequest` (`dto/request/UpdateUserRequest.java:16-37`) — qisman yangilash:
  - `firstName: String` @Size(max=100)
  - `lastName: String` @Size(max=100)
  - `email: String` @Email, @Size(max=255)
  - `phone: String` @Pattern(`^$|^\+998\d{9}$|^\d{9}$`) + `@JsonDeserialize(PhoneDeserializer)`
  - `role: UserRole`
  - `isActive: Boolean`
- `UserResponse` (`dto/response/UserResponse.java:6-18`): `id: Long`, `username: String`, `email: String`, `firstName: String`, `lastName: String`, `phone: String`, `role: UserRole`, `isActive: Boolean`, `lastLogin: LocalDateTime`, `createdAt: LocalDateTime`, `photoUrl: String`.

---

### Users — `controller/UserController.java`

- Base path: `/api/users` (`UserController.java:35`). Class-level `@PreAuthorize`: yo'q (har metodda alohida).
- SecurityConfig: `/api/users/**` → SA, A (`SecurityConfig.java:151-152`; `/**` `/api/users` ning o'zini ham qamraydi).
- **Istisno (2026-10-03):** `/api/users/me/onboarding`, `/api/users/me/onboarding/**` → barcha STAFF rollar (qoida `/api/users/**` va `DELETE /api/**` dan oldin). Controller — `controller/UserOnboardingController.java`, servis — `service/UserOnboardingService.java`; foydalanuvchi id si so'rovdan emas, SecurityContext'dan olinadi.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | /api/users | UserController#createUser | SA, A | body `CreateUserRequest` (@Valid; `password` 8–72) | `ApiResponse<UserResponse>` **201** | yo'q | **2026-10-02:** band login (registrsiz) → **409** `user.username.taken` (avvalgi "200 ALREADY_EXISTS" olib tashlandi, U-06). Rol STUDENT/PARENT → 403 `user.role.notAllowed`; ADMIN/SA rolini faqat SA beradi → 403 `user.role.adminGrant`. |
| GET | /api/users/username-preview | #previewUsername (`:66`) | SA, A | query `firstName: String` (ixt.), `lastName: String` (ixt.) | `ApiResponse<UsernamePreviewResponse>` | yo'q | "Nigina Yunusova" → `N.Yunusova`, band bo'lsa `N.Yunusova2` (`UserService.java:208-230`). |
| POST | /api/users/{id}/reset-password | #resetPassword (`:76`) | SA, A | path `id: Long` | `ApiResponse<PasswordResetResponse>` "Parol tiklandi" | yo'q | Vaqtinchalik parol FAQAT shu javobda. O'zini tiklash → 400; A → SA ni tiklasa → 403 (`UserService.java:335-340`). Sessiyalar revoke. |
| PATCH | /api/users/{id}/status | #setStatus (`:84`) | SA, A | path `id`; body `UserStatusRequest` (@Valid) | `ApiResponse<UserResponse>` | yo'q | O'zini/SA ni nofaol qilish taqiqlangan (`UserService.java:380-387`). Nofaol → refresh tokenlar revoke, Teacher profili sinxron. |
| POST | /api/users/create-for-teacher/{teacherId} | #createForTeacher | SA, A | path `teacherId`; body `CreateUserRequest` (`password` majburiy, 8–72) | `ApiResponse<UserResponse>` **201** | yo'q | Band login → 409 `user.username.taken`; profilda login bor → 409 `teacher.user.alreadyLinked`; mavjud userga bog'lash YO'Q (U-01). |
| POST | /api/users/create-for-student/{studentId} | #createForStudent | SA, A | — | doim **403** `user.role.notAllowed` | yo'q | Q1: STUDENT/PARENT logini yo'q. |
| GET | /api/users | #getAllUsers | SA, A | query (hammasi ixtiyoriy): `page: int` (0 dan), `size: int`=20 (1..200), `q: string` (login/ism/familiya/telefon/email, registrsiz; `%`/`_` oddiy belgi), `role: UserRole` (takrorlanadi), `active: boolean` | `page` **yo'q** → `ApiResponse<UserResponse[]>` (eski shakl, id bo'yicha); `page` **bor** → `ApiResponse<PageResponse<UserResponse>>` (familiya, ism bo'yicha) | ixtiyoriy | U-10 (2026-10-02). Noto'g'ri `role` → 400. |
| GET | /api/users/{id} | #getUserById (`:168`) | SA, A | path `id: Long` | `ApiResponse<UserResponse>` | yo'q | 404 `ErrorResponse`. |
| PUT | /api/users/{id} | #updateUser (`:176`) | SA, A | path `id`; body `UpdateUserRequest` (@Valid) | `ApiResponse<UserResponse>` "User updated" | yo'q | `@Audited` (`:178-180`). Email/telefon unikalligi tekshiriladi (`UserService.java:164`). Rol o'zgarsa Teacher profili sinxron. |
| PUT | /api/users/{id}/password | #changePassword (`:189`) | SA, A | path `id`; body `ChangePasswordRequest` (@Valid) | `ApiResponse<Void>` "Password changed" | yo'q | SA himoyasi va sessiya revoke YO'Q (Muammolar #U1). |
| POST | /api/users/{id}/photo | #uploadUserPhoto (`:201`) | SA, A | path `id`; multipart `file` | `ApiResponse<UserResponse>` "Rasm saqlandi" | yo'q | |
| DELETE | /api/users/{id} | #deleteUser (`:222`) | **SA** (SecurityConfig:151 SA,A ∩ @PreAuthorize SA `:223`) | path `id` | `ApiResponse<Void>` 200 | yo'q | Soft-delete = `setActive(id,false)` (`:230`). |
| GET | /api/users/me/onboarding | UserOnboardingController#list | **barcha STAFF** | — | `ApiResponse<OnboardingItemResponse[]>` — `[{key: string, seenAt: LocalDateTime}]`, `key` bo'yicha | yo'q | **2026-10-03, V67.** Faqat joriy foydalanuvchi yozuvlari. |
| PUT | /api/users/me/onboarding/{key} | UserOnboardingController#markSeen | **barcha STAFF** | path `key`: 1–80 belgi, `^[a-z0-9]+([._-][a-z0-9]+)*$` (masalan `dashboard.intro`, `leads.kanban-v2`) | `ApiResponse<OnboardingItemResponse>` 200 | yo'q | Idempotent (`INSERT … ON CONFLICT DO NOTHING`), birinchi `seenAt` (Asia/Tashkent) saqlanadi. 400 `onboarding.key.invalid` (katta harf, bo'sh joy, kirill, ketma-ket/chetdagi `. _ -`, > 80); 409 `onboarding.limit` — 200 ta kalitdan keyin yangisi (mavjudini qayta belgilash mumkin). Bazada ham CHECK `ck_user_onboarding_key`. |
| DELETE | /api/users/me/onboarding | UserOnboardingController#reset | **barcha STAFF** | — | `ApiResponse<{deleted: int}>` 200 | yo'q | Joriy foydalanuvchining barcha belgilari o'chadi ("turlarni qayta ko'rish"). Boshqa foydalanuvchiniki uchun yo'l yo'q. |

#### DTO tafsilotlari (Users)

- `CreateUserRequest` (`dto/request/CreateUserRequest.java:14-46`):
  - `firstName: String` @NotBlank @Size(max=100)
  - `lastName: String` @NotBlank @Size(max=100)
  - `username: String` @Size(max=100) — bo'sh bo'lsa avtomatik
  - `password: String` @NotBlank @Size(min=8, max=72) — `PasswordPolicy` (Q20)
  - `phone: String` @Pattern(`^$|^\+998\d{9}$|^\d{9}$`) + `PhoneDeserializer`
  - `email: String` @Email @Size(max=255)
  - `role: UserRole` @NotNull
  - `isActive: Boolean` = true (default)
- `UserStatusRequest` (`dto/request/UserStatusRequest.java:8-11`): `active: Boolean` @NotNull.
- `UsernamePreviewResponse` (`dto/response/UsernamePreviewResponse.java:13-17`): `username: String`, `available: boolean`.
- `PasswordResetResponse` (`dto/response/PasswordResetResponse.java:13-16`): `username: String`, `temporaryPassword: String`.
- `UpdateUserRequest`, `ChangePasswordRequest`, `UserResponse` — Auth bo'limida.

---

### Roles — `controller/RoleController.java`

- Base path `/api/roles` (`RoleController.java:17`). `@PreAuthorize` yo'q.
- SecurityConfig: `/api/roles/**` → SA, A (`SecurityConfig.java:155-156`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | /api/roles | RoleController#getRoles (`:20`) | SA, A | — | `ApiResponse<List<{value:string,label:string}>>` (`Map<String,String>`) | yo'q | 7 ta rol, uzbekcha label (`:33-43`): SUPER_ADMIN "Super Admin", ADMIN "Admin", SALES_MANAGER "Sotuv menejeri", TEACHER "O'qituvchi", ACCOUNTANT "Buxgalter", STUDENT "O'quvchi", PARENT "Ota-ona". |

---

### Teachers — `controller/TeacherController.java`

- Base path `/api/teachers` (`TeacherController.java:28`). Class-level `@PreAuthorize`: yo'q.
- SecurityConfig: faqat `/api/teachers/me/**` → TEACHER (`SecurityConfig.java:173-174`); `DELETE /api/**` → SA, A (`:196-197`); qolgan hamma narsa → `anyRequest().authenticated()` (`:199`). Ya'ni `@PreAuthorize` qo'yilmagan metodlar **har qanday auth** foydalanuvchiga ochiq.
- `{id:\d+}` regex `/search`, `/stats`, `/export`, `/kpi/ranking`, `/me/...` bilan to'qnashuvni oldini oladi.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | /api/teachers | #getAllTeachers | SA, A, ACC | query: `activeOnly: boolean`=true, `page: int` (ixt.), `size: int`=20 (1..200), `q: string` (ism/familiya/telefon/kod/fan), `status: ACTIVE\|INACTIVE\|ON_LEAVE` (takrorlanadi; berilsa `activeOnly` e'tiborsiz) | `page` yo'q → `ApiResponse<TeacherResponse[]>` (eski shakl); `page` bor → `ApiResponse<PageResponse<TeacherResponse>>` (familiya, ism) | ixtiyoriy | T-06 (2026-10-02). Noto'g'ri `status` → 400. `groups` batch bilan yuklanadi. |
| GET | /api/teachers/me/kpi | #myKpi (`:44`) | T | query `period: String`="monthly" (`monthly`/`daily`, boshqasi → monthly), `from: LocalDate` ISO (ixt.), `to: LocalDate` ISO (ixt.) | **`TeacherKpiDto` (ApiResponse O'RALMAGAN)** | yo'q | Default: `to`=bugun, `from`=oyning 1-kuni (`service/TeacherKpiService.java:237-250`). |
| GET | /api/teachers/kpi/ranking | #getKpiRanking (`:59`) | SA, A | `period`="monthly", `from`, `to` (ISO, ixt.) | `ApiResponse<TeacherKpiRankingResponse>` | yo'q | Faqat faol o'qituvchilar. |
| GET | /api/teachers/{id} | #getTeacherById (`:73`) | **Har qanday auth** | path `id: Long` (\d+) | `ApiResponse<TeacherResponse>` | yo'q | |
| GET | /api/teachers/{id}/kpi | #getKpi (`:78`) | **Har qanday auth** (SecurityConfig:199; @PreAuthorize YO'Q; servisda egalik tekshiruvi YO'Q — `service/TeacherService.java:275-276`) | path `id`; `period`="monthly", `from`, `to` | **`TeacherKpiDto` (O'RALMAGAN)** | yo'q | TEACHER boshqa o'qituvchining KPI va moliyaviy ma'lumotini (balance/bonus/advance/penalty) ko'ra oladi — Muammolar #T1. |
| GET | /api/teachers/{id}/kpi/trend | #getKpiTrend (`:92`) | **Har qanday auth** | path `id`; `months: Integer` (default 12, 1..12, `TeacherService.java:53`, `:500-504`), `period`="monthly", `from`, `to` | `ApiResponse<List<TeacherKpiTrendPointDto>>` | yo'q | `from`/`to` berilsa `months` e'tiborsiz. `daily` da max 31 kun (`TeacherService.java:55`, `:555-563`). |
| GET | /api/teachers/me/kpi/trend | #myKpiTrend (`:106`) | T | `months`, `period`, `from`, `to` (yuqoridagidek) | `ApiResponse<List<TeacherKpiTrendPointDto>>` | yo'q | User'ga Teacher profili bog'lanmagan bo'lsa 404 (TAXMIN, `findTeacherByUserId`). |
| GET | /api/teachers/{id}/kpi/daily | #getKpiDaily (`:122`) | **Har qanday auth** | path `id`; `year: Integer`, `month: Integer` (1..12), `from`, `to` (ISO) | `ApiResponse<List<TeacherKpiTrendPointDto>>` | yo'q | `from`+`to` yoki `year`+`month` juft bo'lishi shart, aks holda 400; hech biri bo'lmasa joriy oy (`TeacherService.java:524-551`). |
| GET | /api/teachers/me/kpi/daily | #myKpiDaily (`:136`) | T | yuqoridagidek | `ApiResponse<List<TeacherKpiTrendPointDto>>` | yo'q | |
| POST | /api/teachers | #createTeacher (`:151`) | SA, A | body `TeacherRequest` (@Valid) | `ApiResponse<TeacherResponse>` **201** "Teacher created" | yo'q | |
| PUT | /api/teachers/{id} | #updateTeacher | SA, A | path `id`; body `TeacherRequest` — **qisman** (@Valid yo'q) | `ApiResponse<TeacherResponse>` | yo'q | **T-01:** yuborilmagan (`null`) maydon o'zgarmaydi; matnli ixtiyoriy maydon `""` → tozalanadi; `firstName/lastName/phone` yuborilsa bo'sh bo'lmasin (400). `status: INACTIVE` + faol guruhlar → **409** `teacher.hasActiveGroups` (T-03, butun PUT bekor). |
| DELETE | /api/teachers/{id} | #deleteTeacher | SA, A | path `id` | `ApiResponse<Void>` | yo'q | Soft: INACTIVE + user bloklanadi. ACTIVE/FORMING guruhlari bo'lsa **409** `teacher.hasActiveGroups`, `data = {teacherId, groups:[{id, groupName, status}]}` (T-03) — avval guruhlar boshqa o'qituvchiga o'tkaziladi. `PATCH /api/users/{id}/status` (login bloklash) bu tekshiruvsiz. |
| GET | /api/teachers/search | #searchTeachers (`:172`) | **Har qanday auth** | query `q: String` (**required**), `page: int`=0, `size: int`=20 | `ApiResponse<PageResponse<TeacherResponse>>` | PageResponse, createdAt DESC (`TeacherService.java:148-157`) | `q` yo'q bo'lsa → 500 (Muammolar #G2). |
| GET | /api/teachers/stats | #getStats (`:180`) | SA, A | — | `ApiResponse<{total:long, active:long, byStatus:{[status:string]:long}}>` | yo'q | `TeacherService.java:159-169`. |
| POST | /api/teachers/{id}/photo | #uploadPhoto (`:186`) | SA, A | path `id` (\d+); multipart `file` | `ApiResponse<TeacherResponse>` "Rasm saqlandi" | yo'q | |
| POST | /api/teachers/import | #importTeachersFromFile (`:204`) | SA, A | multipart `file` (required=false, bo'sh → 400) | `ApiResponse<ImportResult>` "Import tugadi" | yo'q | `/api/import/teachers` bilan dublikat (`controller/ImportController.java:46`). |
| POST | /api/teachers/sync-from-users | #syncFromUsers (`:220`) | **SA** | — | `ApiResponse<{total:int, created:int, linked:int, updated:int, errors:string[]}>` | yo'q | Idempotent (`service/TeacherProfileSyncService.java:33`, `:60-64`). |
| POST | /api/teachers/{userId}/ensure-profile | #ensureProfile | **SA** | path `userId` — **User** id | `ApiResponse<{linkedCount, createdCount, skippedCount, items[{type, teacherId, userId, username, action: CREATED\|LINKED\|EXISTS, reason}]}>` | yo'q | **T-07:** SKIPPED → **409**, `code`: `teacher.profile.roleNotTeacher` \| `contactTaken` \| `notCreated` \| `failed`; `data` = item (`teacherId, userId, reason, code`). |
| GET | /api/teachers/export | #exportTeachers (`:228`) | SA, A | — | `ResponseEntity<byte[]>` `text/csv`, `Content-Disposition: attachment; filename="teachers.csv"` | yo'q | Ustunlar: ID,UUID,First Name,Last Name,Phone,Email,Subject,Status,Hire Date,Created At (`TeacherService.java:175`). Blob sifatida yuklash. |

#### DTO tafsilotlari (Teachers)

- `TeacherRequest` (`dto/request/TeacherRequest.java:13-56`):
  - `firstName: String` @NotBlank; `lastName: String` @NotBlank
  - `phone: String` @NotBlank + `PhoneDeserializer` (**@Pattern YO'Q**)
  - `email: String` (validatsiyasiz); `subjectSpecialization: String`; `monthlySalary: BigDecimal`; `hireDate: LocalDate`; `notes: String`; `userId: Long`
  - `gender: String`; `dateOfBirth: LocalDate`; `fatherName`, `motherName`, `address`, `permanentAddress`, `passportInfo: String`
  - `qualification`, `workExperience: String`; `joiningDate: LocalDate`
  - `status: String` — erkin matn; ma'lum qiymatlar `ACTIVE`, `INACTIVE`, `ON_LEAVE` (`entity/Teacher.java:23-25`)
  - `basicSalary: BigDecimal`; `medicalLeaves`, `casualLeaves`, `maternityLeaves`, `sickLeaves: Integer`
  - `photoUrl: String`; `groupIds: List<Long>` (guruhlarga o'qituvchi biriktiradi)
- `TeacherResponse` (`dto/response/TeacherResponse.java:15-60`): `id: Long`, `userId: Long`, `uuid: UUID`, `firstName`, `lastName`, `phone`, `email`, `subjectSpecialization: String`, `monthlySalary: BigDecimal`, `hireDate: LocalDate`, `isActive: Boolean`, `notes: String`, `activeGroupsCount: int`, `teacherCode: String`, `gender: String`, `dateOfBirth: LocalDate`, `fatherName`, `motherName`, `address`, `permanentAddress`, `passportInfo`, `qualification`, `workExperience: String`, `joiningDate: LocalDate`, `status: String`, `basicSalary: BigDecimal`, `medicalLeaves`, `casualLeaves`, `maternityLeaves`, `sickLeaves: Integer`, `photoUrl: String`, `groups: List<TeacherResponse.GroupSummary>`, `createdAt: LocalDateTime`.
  - `TeacherResponse.GroupSummary` (`:55-59`): `id: Long`, `groupName: String`, `courseName: String`.
- `TeacherKpiDto` (`dto/response/TeacherKpiDto.java:9-28`): `teacherId: Long`, `teacherName: String`, `balance`, `bonus`, `advance`, `penalty: BigDecimal`, `studentProgress: double`, `groups: List<TeacherKpiGroupDto>`, `conversion: TeacherKpiConversionDto`, `teacherAttendance: TeacherKpiAttendanceDto`, `studentAttendance: TeacherKpiStudentAttendanceDto`, `satisfaction: TeacherKpiSatisfactionDto`, `current: TeacherKpiScoresDto`, `trend: List<TeacherKpiTrendPointDto>`, `period: String` ("monthly"|"daily").
  - `TeacherKpiGroupDto` (`TeacherKpiGroupDto.java:6-13`): `groupId: Long`, `groupName: String`, `studentCount: long`, `roomName: String`, `capacity: Integer`, `freeSeats: Integer`.
  - `TeacherKpiConversionDto` (`:6-10`): `newStudents: long`, `paidStudents: long`, `conversionRate: double`.
  - `TeacherKpiAttendanceDto` (`:8-13`): `plannedLessons`, `conductedLessons`, `missedLessons: long`, `penaltyAmount: BigDecimal`.
  - `TeacherKpiStudentAttendanceDto` (`:6-11`): `presentRate`, `absentUnexcusedRate`, `absentExcusedRate`, `notMarkedRate: double`.
  - `TeacherKpiSatisfactionDto` (`:6-12`): `veryUnhappy`, `unhappy`, `neutral`, `satisfied`, `verySatisfied: int`.
  - `TeacherKpiScoresDto` (`:12-20`): `attendanceRate`, `paymentRate`, `onTimePaymentRate`, `retentionRate`, `overallScore: Double` (null = ma'lumot yetarli emas), `insufficientData: Boolean`.
  - `TeacherKpiTrendPointDto` (`:12-17`): `label: String` (monthly `"2026-07"`, daily `"2026-07-15"`), `overallScore: Double`, `insufficientData: Boolean`.
- `TeacherKpiRankingResponse` (`dto/response/TeacherKpiRankingResponse.java:16-23`): `period: String`, `from: LocalDate`, `to: LocalDate`, `teachers: List<TeacherKpiRankingItemDto>` (default []).
  - `TeacherKpiRankingItemDto` (`:12-25`): `rank: int`, `teacherId: Long`, `teacherName`, `photoUrl: String`, `groupCount`, `studentCount: int`, `attendanceRate`, `paymentRate`, `onTimePaymentRate`, `retentionRate`, `overallScore: Double`, `insufficientData: Boolean`.
- `ImportResult` (`dto/response/ImportResult.java:15-34`): `totalRows`, `imported`, `validRows`, `skipped: int`, `errors: List<ImportIssue>`, `warnings: List<ImportIssue>`; `ImportIssue`: `row: int`, `reason: String`.

---

### Teacher dashboard — `controller/TeacherDashboardController.java`

- Base path `/api/teacher` (birlikda!) (`TeacherDashboardController.java:17`).
- SecurityConfig: `/api/teacher/**` → TEACHER (`SecurityConfig.java:175-176`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | /api/teacher/dashboard | #getTeacherDashboard (`:23`) | T | — | `ApiResponse<Map<String,Object>>` | yo'q | Shakl pastda. Teacher profili topilmasa 404 (TAXMIN). |

Javob shakli (`service/TeacherService.java:192-267`), DTO yo'q — Map:
```
{
  teacherId: Long, teacherName: String, totalGroups: int,
  groups: [{ id, groupName, courseName|null, studentCount: long,
             scheduleDays: [{ dayOfWeek: String, startTime: String|"" , endTime: String|"" }] }],
  todayLessons: [{ groupId, groupName, startTime, endTime, roomNumber: String|"", studentCount }],   // startTime bo'yicha tartiblangan
  totalStudents: long,
  salary?: BigDecimal, salaryStatus?: String    // faqat joriy oy payroll bo'lsa (kalit umuman bo'lmasligi mumkin)
}
```
`dayOfWeek`, `startTime`, `endTime` — String (`entity/GroupScheduleDay.java:26-32`); `todayDay` `MONDAY…SUNDAY` bilan case-insensitive solishtiriladi (`TeacherService.java:228-234`). Faqat `GroupStatus.ACTIVE` guruhlar.

---

### Students — `controller/StudentController.java`

- Base path `/api/students` (`StudentController.java:28`). Class-level `@PreAuthorize`: yo'q.
- SecurityConfig (tartib bo'yicha): `POST /api/students/*/transfer-group` → SA, A (`SecurityConfig.java:114-115`); `GET /api/students, /api/students/**` → SA, A, SM, ACC, T (`:160-161`); `/api/students/**` (qolgan metodlar, DELETE ham) → SA, A, SM, ACC (`:162-163`). `:196` dagi DELETE qoidasigacha yetib bormaydi.
- TEACHER uchun servis darajasida cheklov faqat `GET /api/students` (`service/StudentService.java:68-80`) va `GET /api/students/{id}` (`StudentService.java:108`) da bor.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | /api/students | #getAllStudents (`:36`) | SA, A, SM, ACC, T | query `page: int`=0, `size: int`=20, `search: String` (ixt.), `status: StudentStatus` (ixt.) | `ApiResponse<PageResponse<StudentResponse>>` | PageResponse, createdAt DESC (`StudentService.java:65`) | `search` berilsa `status` **e'tiborsiz** (`StudentService.java:71-83`). T faqat o'z guruhlaridagi faol o'quvchilarni ko'radi. Noto'g'ri `status` → 400. |
| GET | /api/students/{id}/groups | #getStudentGroupHistory (`:46`) | SA, A, SM, ACC, T | path `id: Long` | `ApiResponse<List<StudentDetailResponse.GroupSummary>>` | yo'q | joinDate DESC. T uchun egalik tekshiruvi yo'q. |
| GET | /api/students/archived | #getArchivedStudents (`:52`) | SA, A | — | `ApiResponse<List<StudentResponse>>` | yo'q | "Arxiv yoki muzlatilgan + balans signali" (`StudentService.java:114-118`). |
| GET | /api/students/frozen | #getFrozenStudents (`:58`) | SA, A, ACC | — | `ApiResponse<List<FrozenStudentResponse>>` | yo'q | |
| GET | /api/students/{id} | #getStudentById (`:64`) | SA, A, SM, ACC, T | path `id` (\d+) | `ApiResponse<StudentDetailResponse>` | yo'q | T — faqat o'z o'quvchisi (`assertOwnsStudent`). |
| POST | /api/students/{id}/freeze/preview | #previewFreeze (`:69`) | SA, A | path `id`; body `FreezeStudentRequest` (**ixtiyoriy**, bo'sh body ruxsat) | `ApiResponse<FreezeStudentResponse>` | yo'q | Bazaga yozmaydi. |
| POST | /api/students/{id}/freeze | #freezeStudent (`:81`) | SA, A | path `id`; body `FreezeStudentRequest` (ixt.) | `ApiResponse<FreezeStudentResponse>` "O'quvchi muzlatildi" | yo'q | Allaqachon FROZEN → 400. |
| POST | /api/students/{id}/unfreeze | #unfreezeStudent (`:93`) | SA, A | path `id`; body `UnfreezeStudentRequest` (@Valid) | `ApiResponse<StudentDetailResponse>` "Muzlatishdan chiqarildi" | yo'q | |
| GET | /api/students/{id}/balance-history | #getBalanceHistory (`:102`) | SA, A | path `id`; query `groupId: Long` (ixt.), `from: LocalDate` ISO, `to: LocalDate` ISO (ixt.) | `ApiResponse<List<BalanceHistoryItemDto>>` | yo'q | |
| POST | /api/students/{id}/balance-adjust | #adjustBalance (`:113`) | **SA** | path `id`; body `BalanceAdjustRequest` (@Valid) | `ApiResponse<BalanceHistoryItemDto>` "Balans tuzatildi" | yo'q | |
| POST | /api/students | #createStudent (`:122`) | SA, A | body `StudentRequest` (@Valid) | `ApiResponse<StudentResponse>` **201** "Student created" | yo'q | `admissionNumber`/`admissionDate` bo'sh bo'lsa avtomatik (`StudentService.java:140-151`). |
| PUT | /api/students/{id} | #updateStudent (`:130`) | SA, A | path `id`; body `StudentRequest` (@Valid) | `ApiResponse<StudentResponse>` "Student updated" | yo'q | To'liq PUT (barcha @NotBlank kerak). |
| POST | /api/students/{id}/transfer-group | #transferGroup (`:137`) | SA, A | path `id`; body `TransferGroupRequest` (@Valid) | `ApiResponse<StudentDetailResponse>` "Guruh o'zgartirildi" | yo'q | |
| PATCH | /api/students/{id}/payment-start-date | #updatePaymentStartDate (`:146`) | SA, A, ACC | path `id`; body `PaymentStartDateRequest` (@Valid) | `ApiResponse<StudentDetailResponse>` | yo'q | |
| DELETE | /api/students/{id} | #deleteStudent (`:156`) | SA, A (SecurityConfig:162 ∩ `:157`) | path `id` | `ApiResponse<Void>` "Student deactivated" | yo'q | Soft: status=LEFT (`StudentService.java:534-538`). |
| GET | /api/students/search | #searchStudents (`:163`) | SA, A, SM, ACC, T | query `q: String` (**required**), `page`=0, `size`=20 | `ApiResponse<PageResponse<StudentResponse>>` | PageResponse, createdAt DESC | `GET /api/students?search=` bilan deyarli dublikat, lekin T uchun **cheklovsiz** (Muammolar #S1). |
| POST | /api/students/import | #importStudentsFromFile (`:171`) | SA, A | multipart `file` (required=false, bo'sh → 400) | `ApiResponse<ImportResult>` "Import tugadi" | yo'q | `/api/import/students` bilan dublikat (`ImportController.java:34`). |
| POST | /api/students/import/validate | #validateStudentImportFile (`:184`) | SA, A | multipart `file` | `ApiResponse<ImportResult>` "Tekshiruv tugadi" | yo'q | Dry-run. |
| GET | /api/students/import/template | #downloadStudentImportTemplate (`:196`) | SA, A | — | `ResponseEntity<byte[]>` xlsx, `attachment; filename="oquvchilar_shablon.xlsx"` | yo'q | |
| GET | /api/students/export | #exportStudents (`:208`) | SA, A | — | `ResponseEntity<byte[]>` `text/csv`, `students.csv` | yo'q | Ustunlar: ID,UUID,First Name,Last Name,Phone,Admission Number,Gender,Status,Marketing Source,Created At (`StudentService.java:598`). |
| POST | /api/students/{id}/photo | #uploadPhoto (`:218`) | SA, A | path `id` (\d+); multipart `file` | `ApiResponse<StudentResponse>` "Rasm saqlandi" | yo'q | |
| GET | /api/students/stats | #getStats (`:236`) | SA, A | — | `ApiResponse<{total, active, frozen, finished, left: long, byGender:{[g]:long}, bySource:{[MarketingSource]:long}}>` | yo'q | `StudentService.java:574-592`. |
| GET | /api/students/left | #getLeftStudents (`:243`) | SA, A, SM, ACC, T | — | `ApiResponse<List<StudentResponse>>` | **yo'q** (hammasi) | LEFT + GRADUATED. |
| GET | /api/students/trial | #getTrialStudents (`:251`) | SA, A, SM, ACC, T | — | `ApiResponse<List<{studentId, studentName, phone, groupId, groupName, joinDate: LocalDate, lessonsAttended}>>` | yo'q | Faol TRIAL enrollmentlar (`StudentService.java:898-915`). |
| GET | /api/students/{id}/history | #getHistory (`:258`) | SA, A, SM, ACC, T | path `id` | `ApiResponse<List<{id, fromStatus: String, toStatus: String, reason, notes, changedAt: LocalDateTime}>>` | yo'q | changedAt DESC (`StudentService.java:919-935`). |

#### DTO tafsilotlari (Students)

- `StudentRequest` (`dto/request/StudentRequest.java:19-66`):
  - `firstName: String` @NotBlank; `lastName: String` @NotBlank
  - `phone: String` @NotBlank + `PhoneDeserializer` (@Pattern YO'Q)
  - `status: String` @NotBlank — `StudentStatus` nomi, registr farqsiz; noto'g'ri qiymat **jimgina ACTIVE** bo'ladi (`StudentService.java:635-641`)
  - `marketingSource: String` @NotBlank — `MarketingSource` nomi; noto'g'ri → jimgina `OTHER` (`StudentService.java:628-634`)
  - `admissionNumber: String`; `admissionDate: LocalDate`; `groupId: Long`
  - `studyFormat: StudyFormat` (ONLINE|OFFLINE; faqat `groupId` bilan ma'noli)
  - `courseId: Long`; `parentPhone: String` + `PhoneDeserializer`; `address`, `notes: String`; `birthDate: LocalDate`; `gender: String`; `referralStudentId: Long`; `photoUrl: String`
  - `parents: List<StudentParentRequest>`
- `StudentParentRequest` (`dto/request/StudentParentRequest.java:8-25`): `fullName: String` (ustun), `firstName`, `lastName: String`, `phone: String` + `PhoneDeserializer`, `relation: String` (FATHER|MOTHER|OTHER), `address: String`, `isPrimary: Boolean`. Validatsiya yo'q.
- `FreezeStudentRequest` (`dto/request/FreezeStudentRequest.java:6-11`): `groupId: Long` (null = barcha faol guruhlar), `reason: String`, `note: String`.
- `UnfreezeStudentRequest` (`dto/request/UnfreezeStudentRequest.java:9-19`): `groupId: Long` @NotNull; `paymentStartDate: LocalDate` (ixt., default bugun).
- `BalanceAdjustRequest` (`dto/request/BalanceAdjustRequest.java:10-17`): `groupId: Long` @NotNull; `amount: BigDecimal` @NotNull (manfiy bo'lishi mumkin — TAXMIN); `note: String` @NotBlank.
- `TransferGroupRequest` (`dto/request/TransferGroupRequest.java:8-23`): `fromGroupId: Long` (ixt.); `toGroupId: Long` @NotNull; `studyFormat: StudyFormat` (ixt., berilmasa eski yozuvdan); `reason`, `note: String`.
- `PaymentStartDateRequest` (`dto/request/PaymentStartDateRequest.java:9-14`): `paymentStartDate: LocalDate` @NotNull; `isTrial: Boolean` (true=TRIAL, false=to'lovli, null=o'zgarmaydi).
- `StudentResponse` (`dto/response/StudentResponse.java:18-45`): `id: Long`, `uuid: UUID`, `firstName`, `lastName`, `phone`, `parentPhone: String`, `birthDate: LocalDate`, `gender: String`, `marketingSource: MarketingSource`, `status: StudentStatus`, `notes`, `address`, `photoUrl`, `admissionNumber: String`, `admissionDate: LocalDate`, `referralStudentId: Long`, `currentGroupId: Long`, `currentGroupName: String`, `studyFormat: StudyFormat`, `paymentStatus: PaymentStatus`, `paymentStartDate`, `nextPaymentDate: LocalDate`, `monthlyFee`, `balance: BigDecimal`, `createdAt: LocalDateTime`.
- `StudentDetailResponse` (`dto/response/StudentDetailResponse.java:21-99`): `StudentResponse` ning barcha maydonlari (`:22-46`, `createdAt` bilan) + `activeGroups: List<GroupSummary>`, `paymentHistory: List<PaymentSummary>`, `attendanceSummary: Map<String,Integer>`, `parents: List<StudentParentInfo>`.
  - `GroupSummary` (`:58-70`): `groupId: Long`, `groupName`, `courseName`, `teacherName: String`, `paymentStatus: String` (enum emas!), `joinDate`, `leaveDate: LocalDate`, `isActive: Boolean`, `monthlyPrice: BigDecimal`, `studyFormat: StudyFormat`.
  - `PaymentSummary` (`:76-85`): `id: Long`, `receiptNumber: String`, `amount: BigDecimal`, `formattedAmount: String`, `paymentDate: LocalDate`, `paymentMethod: PaymentMethod`, `groupName: String`, `status: PaymentStatus`.
  - `StudentParentInfo` (`:91-98`): `parentId: Long`, `fullName`, `phone`, `address`, `relation: String`, `isPrimary: Boolean`.
- `FrozenStudentResponse` (`dto/response/FrozenStudentResponse.java:15-23`): `studentId: Long`, `fullName`, `phone: String`, `frozenDate: LocalDate`, `balance: BigDecimal`, `lastGroupId: Long`, `lastGroupName: String`.
- `FreezeStudentResponse` (`dto/response/FreezeStudentResponse.java:16-39`): `studentId: Long`, `totalBalance: BigDecimal`, `groups: List<FrozenGroupBreakdown>`; `FrozenGroupBreakdown`: `groupId: Long`, `groupName: String`, `lessonsAttended: Integer`, `lessonsUsed: int` (alias), `lessonPrice`, `used`, `paid`, `balance: BigDecimal`.
- `BalanceHistoryItemDto` (`dto/response/BalanceHistoryItemDto.java:16-26`): `date: LocalDateTime`, `type: BalanceTransactionType`, `typeLabel: String`, `amount`, `balanceAfter: BigDecimal`, `note: String`, `groupId: Long`, `groupName`, `createdBy: String`.
- Enumlar:
  - `StudentStatus` (`entity/enums/StudentStatus.java:3-11`): ACTIVE, FROZEN, FINISHED, LEFT, GRADUATED, SUSPENDED, ARCHIVED.
  - `StudyFormat` (`entity/enums/StudyFormat.java:12-15`): ONLINE, OFFLINE.
  - `MarketingSource` (`entity/enums/MarketingSource.java:4-15`): INSTAGRAM, TELEGRAM, YOUTUBE, FACEBOOK, TARGET, SELF_CALL, FORMER_STUDENT, REFERRAL, WALK_IN, OFFLINE, LEAD, OTHER.
  - `PaymentStatus` (`entity/enums/PaymentStatus.java:8-16`): PAID, PENDING, OVERDUE, PARTIAL, CANCELLED, TRIAL, SUSPENDED, ARCHIVED, FROZEN.
  - `PaymentMethod` (`entity/enums/PaymentMethod.java:15-23`): CASH, CARD, CLICK, PAYME, UZUM, TERMINAL, BANK, CASH_AND_CARD, OTHER.
  - `BalanceTransactionType` (`entity/enums/BalanceTransactionType.java:5-16`): LESSON_CHARGE, LESSON_REFUND, PAYMENT, PERIOD_CHARGE, PERIOD_REFUND, FREEZE, UNFREEZE, MANUAL_ADJUST.
- `ImportResult` — Teachers bo'limida.

---

### Parents — `controller/ParentController.java`

- Base path `/api/parents` (`ParentController.java:16`). Class-level `@PreAuthorize` yo'q.
- SecurityConfig: `/api/parents/**` → SA, A, ACC (`SecurityConfig.java:149-150`) — barcha metodlar uchun.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | /api/parents | #getAllParents (`:22`) | SA, A, ACC | query `page: int`=0, `size: int`=20 | `ApiResponse<PageResponse<ParentResponse>>` | PageResponse, faqat `isActive=true`, createdAt DESC (`service/ParentService.java:33-37`) | Qidiruv yo'q. |
| GET | /api/parents/{id} | #getParentById (`:29`) | SA, A, ACC | path `id: Long` | `ApiResponse<ParentResponse>` | yo'q | Nofaollarni ham qaytaradi. |
| POST | /api/parents | #createParent (`:34`) | SA, A | body `ParentRequest` (@Valid) | `ApiResponse<ParentResponse>` **201** "Parent created" | yo'q | `relation` default "OTHER" (`ParentService.java:51`). `studentId` berilsa bog'lanadi (TAXMIN). |
| PUT | /api/parents/{id} | #updateParent (`:41`) | SA, A | path `id`; body `ParentRequest` (@Valid) | `ApiResponse<ParentResponse>` "Parent updated" | yo'q | |
| DELETE | /api/parents/{id} | #deleteParent (`:48`) | SA, A (SecurityConfig:149 ∩ `:49`) | path `id` | `ApiResponse<Void>` "Parent deactivated" | yo'q | Soft: isActive=false (`ParentService.java:87-91`). |
| GET | /api/parents/student/{studentId} | #getParentsByStudent (`:55`) | SA, A, ACC | path `studentId: Long` | `ApiResponse<List<ParentResponse>>` | yo'q | |
| POST | /api/parents/{id}/link/{studentId} | #linkToStudentByPath (`:60`) | SA, A | path `id`, `studentId` | `ApiResponse<Void>` "Parent linked to student" | yo'q | Allaqachon bog'langan → 400. |
| POST | /api/parents/{id}/students | #linkToStudent (`:68`) | SA, A | path `id`; body `Map<String,Object>`: `{studentId: number (majburiy), relation?: string, isPrimary?: boolean}` | `ApiResponse<Void>` | yo'q | DTO/validatsiya yo'q; `studentId` yo'q → NPE → 500 (`ParentService.java:103`). |
| DELETE | /api/parents/{id}/students/{studentId} | #unlinkFromStudent (`:76`) | SA, A | path `id`, `studentId` | `ApiResponse<Void>` "Parent unlinked from student" | yo'q | StudentParent yozuvi **jismoniy** o'chiriladi (`ParentService.java:131`). |

#### DTO tafsilotlari (Parents)

- `ParentRequest` (`dto/request/ParentRequest.java:9-26`): `fullName: String` @NotBlank; `phone: String` @NotBlank + `PhoneDeserializer`; `address: String`; `telegramChatId: String`; `relation: String` (FATHER|MOTHER|OTHER, erkin matn); `studentId: Long`.
- `ParentResponse` (`dto/response/ParentResponse.java:12-36`): `id: Long`, `fullName`, `phone`, `address`, `relation: String`, `studentId: Long`, `studentName: String`, `isActive: Boolean`, `linkedStudents: List<LinkedStudentResponse>`, `createdAt: LocalDateTime`. (`telegramChatId` javobda YO'Q.)
  - `LinkedStudentResponse` (`:28-35`): `studentId: Long`, `firstName`, `lastName`, `phone`, `relation: String`, `isPrimary: Boolean`.

---

### Settings — `controller/SettingsController.java`

- Base path `/api/settings` (`SettingsController.java:12`).
- SecurityConfig: `GET /api/settings/academic-year` → permitAll (`SecurityConfig.java:90`); boshqa GET → authenticated (`:91`); boshqa metodlar → SA, A (`:92`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | /api/settings/academic-year | #getAcademicYear (`:15`) | **public** | — | `ApiResponse<String>` masalan `"2026 / 2027"` | yo'q | Sentabrdan boshlab yangi yil (`:18-24`); server sanasi bo'yicha, bazada sozlanmaydi. |

---

### Enums — `controller/EnumController.java`

- Base path `/api/enums` (`EnumController.java:23`). SecurityConfig: maxsus qoida yo'q → `anyRequest().authenticated()` (`SecurityConfig.java:199`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | /api/enums/payment-methods | #getPaymentMethods (`:27`) | Har qanday auth | — | `ApiResponse<List<EnumOptionDto>>` | yo'q | 9 ta, `icon` emoji bilan (`entity/enums/PaymentMethod.java:15-23`). |
| GET | /api/enums/task-types | #getTaskTypes (`:39`) | Har qanday auth | — | `ApiResponse<List<EnumOptionDto>>` | yo'q | CALL, MEETING, MESSAGE, OTHER (`entity/enums/TaskType.java:14-17`). |
| GET | /api/enums/task-statuses | #getTaskStatuses (`:51`) | Har qanday auth | — | `ApiResponse<List<EnumOptionDto>>` | yo'q | OPEN, DONE, CANCELLED (`entity/enums/TaskStatus.java:15-17`); `icon: null`. |

- `EnumOptionDto` (`dto/response/EnumOptionDto.java:13-17`): `value: String`, `label: String`, `icon: String|null` (NON_NULL yo'q → `"icon": null` chiqadi).

---

### Global search — `controller/SearchController.java`

- Base path `/api/search` (`SearchController.java:24`); class-level `@Transactional(readOnly=true)` (`:26`).
- SecurityConfig: `/api/search/**` → authenticated (`SecurityConfig.java:193`); metodda `@PreAuthorize("isAuthenticated()")` (`:34`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | /api/search | #globalSearch (`:33`) | **Har qanday auth** (ST, P ham) | query `q: String` (**required**; <2 belgi → bo'sh `{}`) | `ApiResponse<Map<String,Object>>` | yo'q; har turdan max 5 | Shakl pastda. |

Javob shakli (`SearchController.java:43-102`):
```
{
  students: [{ id, type:"student", name, subtitle: phone, url:"/students/student-details/{id}", photoUrl }],
  teachers: [{ id, type:"teacher", name, subtitle: subjectSpecialization, url:"/teachers/teacher-details/{id}", photoUrl }],
  groups:   [{ id, type:"group",   name: groupName, subtitle: courseName|null, url:"/groups/{id}" }],   // photoUrl kaliti YO'Q
  total: int
}
```
`q` < 2 belgi bo'lsa `data: {}` — kalitlarsiz (`:36-38`).

---


## 2. O'quv jarayoni (guruh, akademik, davomat, imtihon, e'lon, ta'til, shartnoma)

> Bo'lim eslatmasi: `POST /api/timetable` `daysOfWeek` bor-yo'qligiga qarab ro'yxat yoki bitta obyekt qaytaradi (`ResponseEntity<?>`). Contract'da `pageNumber` `Page` dan olinadi, boshqa joylarda so'rovdagi qiymatdan.

### Guruhlar — `controller/GroupController.java`

- Base path: `/api/groups` (`controller/GroupController.java:24`). Class-level `@PreAuthorize` yo'q.
- SecurityConfig qoidalari:
  - GET `/api/groups/**` → SA, A, ACC, T (`config/SecurityConfig.java:106-107`).
  - POST/PUT/PATCH uchun maxsus qoida yo'q, `anyRequest().authenticated()` ishlaydi (`:199`).
  - DELETE → SA, A (`:196-197`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/groups` | `getAllGroups` (`GroupController.java:31-36`) | SA, A, ACC, T | query `status: GroupStatus` (ixtiyoriy; enum, **katta harf, case-sensitive**) | `ApiResponse<List<GroupResponse>>` | yo'q | TEACHER faqat o'z guruhlarini ko'radi (`service/GroupService.java:67-72`). Ro'yxatda `studentGroups`/`students` to'ldirilmaydi (`toResponse(g,false,…)`, `GroupService.java:80`). Noto'g'ri enum → 400 (TypeMismatch). |
| GET | `/api/groups/{id}/schedule` | `getSchedule` (`:38-43`) | SA, A, ACC, T | path `id: Long` | `ApiResponse<List<GroupResponse.ScheduleDayResponse>>` | yo'q | TEACHER uchun egalik tekshiruvi bor (`GroupService.java:92-96`). |
| GET | `/api/groups/{id}/lesson-days` | `getLessonDays` (`:45-49`) | SA, A, ACC, T | path `id: Long` | `ApiResponse<GroupLessonDaysResponse>` | yo'q | Avval `GroupScheduleDay`, u bo'sh bo'lsa `Timetable` dan olinadi. Kun katta harfda, vaqt `"HH:mm"` (`GroupService.java:99-138`). |
| GET | `/api/groups/{id}/suspended-students` | `getSuspendedStudents` (`:51-56`) | SA, A | path `id: Long` | `ApiResponse<List<SuspendedStudentResponse>>` | yo'q | URL qoidasi ACC/T ga ruxsat beradi, lekin `@PreAuthorize` ularni rad etadi. |
| GET | `/api/groups/{id}` | `getGroupById` (`:58-62`) | SA, A, ACC, T | path `id: Long` | `ApiResponse<GroupResponse>` | yo'q | `studentGroups` va `students` (alias) to'ldiriladi. TEACHER uchun egalik tekshiruvi bor (`GroupService.java:85-89`). |
| POST | `/api/groups` | `createGroup` (`:64-69`) | SA, A | body `GroupRequest` (@Valid) | **201** `ApiResponse<GroupResponse>`, message "Group created" | yo'q | `status` berilmasa ACTIVE (`GroupService.java:219-221`). |
| PUT | `/api/groups/{id}` | `updateGroup` (`:71-76`) | SA, A | path `id`; body `GroupRequest` (@Valid) | `ApiResponse<GroupResponse>` | yo'q | **To'liq almashtirish**: `teacherId: null` yuborilsa o'qituvchi olib tashlanadi (`GroupService.java:263-268`). `status` bo'sh bo'lsa joriy status saqlanadi (`:258-261`). `scheduleDays`/`schedules` ikkalasi ham null bo'lsa jadval o'zgarmaydi (`:273-279`). |
| PATCH | `/api/groups/{id}/status` | `updateStatus` (`:78-85`) | SA, A | path `id`; **query** `status: String` (majburiy; case-insensitive) | `ApiResponse<GroupResponse>`, message "Status yangilandi" | yo'q | Noto'g'ri qiymat → 400 (`GroupService.java:653-662`). Param umuman bo'lmasa → 500 (TAXMIN). |
| DELETE | `/api/groups/{id}` | `deleteGroup` (`:87-92`) | SA, A | path `id` | `ApiResponse<Void>` (`data:null`), message "Group cancelled" | yo'q | **Soft**: status CANCELLED ga o'tadi (`GroupService.java:510-514`). |
| POST | `/api/groups/students` | `addStudentToGroup` (`:94-102`) | SA, A | body `StudentGroupRequest` (@Valid) | **201** `ApiResponse<String>`, `data:"OK"` | yo'q | Guruh to'lgan bo'lsa yoki o'quvchi allaqachon a'zo bo'lsa → 400 (`GroupService.java:527,533`). |
| DELETE | `/api/groups/{groupId}/students/{studentId}` | `removeStudentFromGroup` (`:104-110`) | SA, A | path `groupId`, `studentId` | `ApiResponse<Void>` | yo'q | Faqat `isActive=false` va `leaveDate` o'rnatiladi, keyin payment schedule tozalanadi (`GroupService.java:600-608`). Student statusi o'zgarmaydi. |
| POST | `/api/groups/{groupId}/remove-student` | `removeStudentWithReason` (`:112-122`) | SA, A | path `groupId`; body `RemoveStudentRequest` (**@Valid yo'q**) | `ApiResponse<String>`, `data:"OK"` | yo'q | `reason` bo'yicha student statusi o'zgaradi: GRADUATED/LEFT/SUSPENDED, TRANSFERRED bo'lsa o'zgarmaydi. Status tarixi yoziladi (`GroupService.java:613-650`). |
| POST | `/api/groups/{groupId}/students/create-and-add` | `createAndAddStudent` (`:124-132`) | SA, A | path `groupId`; body `StudentCreateAndAddRequest` (@Valid) | **201** `ApiResponse<StudentResponse>` | yo'q | `StudentService.createAndAddStudentToGroup`. |

**DTO tafsilotlari**

`GroupRequest` (`dto/request/GroupRequest.java:12-55`):
- `groupName: String` — `@NotBlank` (:13-14)
- `courseId: Long` — `@NotNull` (:15-16)
- `teacherId: Long` (:17)
- `room: String` (:18)
- `classroomId: Long` (:20)
- `maxStudents: Integer = 20` — `@Min(1)` (:21-22)
- `startDate: LocalDate` — `@NotNull` (:23-24)
- `endDate: LocalDate` (:25)
- `notes: String` (:26)
- `status: String` — FORMING/ACTIVE/COMPLETED/CANCELLED, case-insensitive (:27-32)
- `schedules: List<ScheduleRequest>` — legacy (:34)
- `scheduleDays: List<ScheduleDayRequest>` (:35)
- `ScheduleDayRequest` va `ScheduleRequest` bir xil tuzilgan (:37-54): `{dayOfWeek: String, startTime: String, endTime: String, roomId: Long, roomNumber: String}`

`RemoveStudentRequest` (`dto/request/RemoveStudentRequest.java:6-11`):
- `studentId: Long`
- `reason: String` — GRADUATED | LEFT | TRANSFERRED | SUSPENDED | OTHER
- `notes: String`
- Validatsiya yo'q.

`StudentGroupRequest` (`dto/request/StudentGroupRequest.java:12-34`):
- `studentId: Long` — `@NotNull`
- `groupId: Long` — `@NotNull`
- `joinDate: LocalDate`
- `isTrial: Boolean = false`
- `paymentStartDate: LocalDate`
- `monthlyFee: BigDecimal`
- `discountPercentage: BigDecimal`
- `monthlyPriceOverride: BigDecimal` — legacy
- `paymentType: PaymentType` — MONTHLY | PER_LESSON (`entity/enums/PaymentType.java:3`)
- `lessonPrice: BigDecimal`
- `studyFormat: StudyFormat` — ONLINE | OFFLINE (`entity/enums/StudyFormat.java:12`)
- `notes: String`

`StudentCreateAndAddRequest` (`dto/request/StudentCreateAndAddRequest.java:14-42`):
- `firstName: String` — `@NotBlank`
- `lastName: String` — `@NotBlank`
- `phone: String` — `@NotBlank`, `@JsonDeserialize(using=PhoneDeserializer)` (:22-24)
- `gender: String`
- `marketingSource: String` — bu yerda String, lekin `StudentResponse` da enum
- `parentPhone: String` — `@JsonDeserialize(PhoneDeserializer)` (:29-30)
- `paymentStartDate: LocalDate` — `@NotNull`
- `monthlyFee: BigDecimal`
- `paymentType: PaymentType`
- `lessonPrice: BigDecimal`
- `isTrial: Boolean = false`

`GroupResponse` (`dto/response/GroupResponse.java:16-77`):
- Maydonlar: `id: Long`, `uuid: UUID`, `groupName: String`, `courseId: Long`, `courseName: String`, `teacherId: Long`, `teacherName: String`, `room: String`, `classroomId: Long`, `classroomName: String`, `monthlyFee: BigDecimal` (course.monthlyPrice), `coursePrice: BigDecimal`, `maxStudents: Integer`, `currentStudents: Integer`, `startDate: LocalDate`, `endDate: LocalDate`, `notes: String`, `status: GroupStatus` (FORMING | ACTIVE | COMPLETED | CANCELLED, `entity/enums/GroupStatus.java:2`), `schedules: List<ScheduleResponse>`, `scheduleDays: List<ScheduleDayResponse>`, `studentGroups: List<StudentSummary>`, `students: List<StudentSummary>` (alias), `createdAt: LocalDateTime`.
- `StudentSummary` (:49-63): `{studentId: Long, fullName: String, studentName: String (alias), phone: String, joinedAt: LocalDate, joinDate: LocalDate (alias), paymentStatus: String, paymentStartDate: LocalDate, nextPaymentDate: LocalDate, monthlyFee: BigDecimal, status: String}`
- `ScheduleDayResponse` (:69-76): `{id: Long, dayOfWeek: String, startTime: String, endTime: String, roomNumber: String, roomId: Long}` — vaqt satr ko'rinishida, qanday saqlangan bo'lsa shunday qaytadi.

`ScheduleResponse` (`dto/response/ScheduleResponse.java:6-13`):
- `{id: Long, dayOfWeek: java.time.DayOfWeek, startTime: LocalTime, endTime: LocalTime, roomId: Long, roomNumber: String}`
- Diqqat: `scheduleDays` dagi ma'lumotning o'zi, lekin boshqa turlarda keladi.

`GroupLessonDaysResponse` (`dto/response/GroupLessonDaysResponse.java:14-29`):
- `{groupId: Long, groupName: String, days: List<LessonDayItem>}`
- `LessonDayItem`: `{dayOfWeek: String, startTime: String "HH:mm", endTime: String, roomName: String}`

`SuspendedStudentResponse` (`dto/response/SuspendedStudentResponse.java:11-19`):
- `{studentId: Long, studentName: String, groupId: Long, groupName: String, suspendedAt: LocalDateTime, suspensionReason: String, daysSinceSuspended: Long}`

`StudentResponse` (`dto/response/StudentResponse.java:18-45`):
- Maydonlar: `id: Long`, `uuid: UUID`, `firstName: String`, `lastName: String`, `phone: String`, `parentPhone: String`, `birthDate: LocalDate`, `gender: String`, `marketingSource: MarketingSource`, `status: StudentStatus`, `notes: String`, `address: String`, `photoUrl: String`, `admissionNumber: String`, `admissionDate: LocalDate`, `referralStudentId: Long`, `currentGroupId: Long`, `currentGroupName: String`, `studyFormat: StudyFormat`, `paymentStatus: PaymentStatus`, `paymentStartDate: LocalDate`, `nextPaymentDate: LocalDate`, `monthlyFee: BigDecimal`, `balance: BigDecimal`, `createdAt: LocalDateTime`.
- Enum qiymatlari:
  - `MarketingSource` (`entity/enums/MarketingSource.java:3`): INSTAGRAM, TELEGRAM, YOUTUBE, FACEBOOK, TARGET, SELF_CALL, FORMER_STUDENT, REFERRAL, WALK_IN, OFFLINE, LEAD, OTHER
  - `StudentStatus` (`entity/enums/StudentStatus.java:3`): ACTIVE, FROZEN, FINISHED, LEFT, GRADUATED, SUSPENDED, ARCHIVED
  - `PaymentStatus` (`entity/enums/PaymentStatus.java:7`): PAID, PENDING, OVERDUE, PARTIAL, CANCELLED, TRIAL, SUSPENDED, ARCHIVED, FROZEN

---

### Kurslar — `controller/CourseController.java`

- Base path: `/api/courses` (`CourseController.java:13`). Class-level `@PreAuthorize` yo'q.
- SecurityConfig qoidalari:
  - GET `/api/courses/**` → SA, A, T (`SecurityConfig.java:108-109`).
  - POST/PUT → `:199` (authenticated).
  - DELETE → `:196`.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/courses` | `getAllCourses` (`:18-23`) | SA, A, T | query `activeOnly: boolean` (default `true`) | `ApiResponse<List<CourseResponse>>` | yo'q | ACCOUNTANT kira olmaydi. |
| GET | `/api/courses/{id}` | `getCourseById` (`:25-29`) | SA, A, T | path `id` | `ApiResponse<CourseResponse>` | yo'q | |
| POST | `/api/courses` | `createCourse` (`:31-36`) | SA, A | body `CourseRequest` (@Valid) | **201** `ApiResponse<CourseResponse>` | yo'q | |
| PUT | `/api/courses/{id}` | `updateCourse` (`:38-42`) | SA, A | path `id`; body `CourseRequest` (@Valid) | `ApiResponse<CourseResponse>` | yo'q | |
| DELETE | `/api/courses/{id}` | `deleteCourse` (`:44-49`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | **Soft**: `isActive=false` (`service/CourseService.java:70-74`). |

**DTO**

`CourseRequest` (`dto/request/CourseRequest.java:6-14`):
- `courseName: String` — `@NotBlank`
- `description: String`
- `durationMonths: Integer` — `@NotNull`, `@Min(1)`
- `lessonsCount: Integer` — `@NotNull`, `@Min(1)`
- `monthlyPrice: BigDecimal` — `@NotNull`, `@DecimalMin("0.0")`
- `lessonPrice: BigDecimal` — `@DecimalMin("0.0")`, ixtiyoriy

`CourseResponse` (`dto/response/CourseResponse.java:7-18`):
- `{id: Long, uuid: UUID, courseName: String, description: String, durationMonths: Integer, lessonsCount: Integer, monthlyPrice: BigDecimal, lessonPrice: BigDecimal, isActive: Boolean, createdAt: LocalDateTime}`

---

### Xonalar — `controller/ClassroomController.java`

- Base path: `/api/classrooms` (`ClassroomController.java:17`). Class-level `@PreAuthorize` yo'q.
- SecurityConfig qoidalari:
  - GET `/api/classrooms` va `/api/classrooms/**` → SA, A, T (`SecurityConfig.java:104-105`).
  - Boshqa metodlar → SA, A (`:179-180`).
  - DELETE ham `:179` ga tushadi, chunki u `:196` dan oldin turadi.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/classrooms` | `getAll` (`:23-27`) | SA, A, T | — | `ApiResponse<List<ClassroomResponse>>` | yo'q | Nofaol xonalar ham qaytadi, roomNumber bo'yicha tartiblangan (`service/ClassroomService.java:23-27`). |
| GET | `/api/classrooms/{id}` | `getById` (`:29-33`) | SA, A, T | path `id` | `ApiResponse<ClassroomResponse>` | yo'q | |
| POST | `/api/classrooms` | `create` (`:35-42`) | SA, A | body `ClassroomRequest` (@Valid) | **201** `ApiResponse<ClassroomResponse>`, message "Xona qo'shildi" | yo'q | |
| PUT | `/api/classrooms/{id}` | `update` (`:44-52`) | SA, A | path `id`; body `ClassroomRequest` (**@Valid yo'q**) | `ApiResponse<ClassroomResponse>` | yo'q | `roomNumber` bo'sh bo'lsa ham qabul qilinadi. |
| DELETE | `/api/classrooms/{id}` | `delete` (`:54-59`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | **Hard delete** (`ClassroomService.java:79-84`). FK bo'lsa 500 bo'lishi mumkin (TAXMIN). |

**DTO**

`ClassroomRequest` (`dto/request/ClassroomRequest.java:7-15`):
- `roomNumber: String` — `@NotBlank`
- `capacity: Integer`
- `roomType: String` — THEORY | PRACTICE | LAB | OTHER; enum emas, oddiy satr
- `description: String`
- `isActive: Boolean`

`ClassroomResponse` (`dto/response/ClassroomResponse.java:11-19`):
- `{id: Long, roomNumber: String, capacity: Integer, roomType: String, description: String, isActive: Boolean, createdAt: LocalDateTime}`

---

### Akademik (Classes / Sections / Subjects / Timetable) — `controller/AcademicController.java`

- Class-level `@RequestMapping` yo'q, har bir metod to'liq yo'lni o'zi yozadi (`AcademicController.java:14-16`). Class-level `@PreAuthorize` ham yo'q.
- Barcha operatsiyalar `service/AcademicService.java` ga tushadi.

#### A) Classes — `/api/classes`

SecurityConfig'da `/api/classes` uchun alohida qoida yo'q:
- GET/POST/PUT → `anyRequest().authenticated()` (`SecurityConfig.java:199`)
- DELETE → `:196`

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/classes` | `getAllClasses` (`:22-27`) | **AUTH** (hamma rol) | query `page: int=0`, `size: int=20` | `ApiResponse<PageResponse<ClassResponse>>` | PageResponse, `createdAt DESC`, faqat `isActive=true` (`AcademicService.java:46-54`) | `@PreAuthorize` yo'q |
| GET | `/api/classes/{id}` | `getClassById` (`:29-32`) | **AUTH** | path `id` | `ApiResponse<ClassResponse>` | yo'q | `@PreAuthorize` yo'q |
| POST | `/api/classes` | `createClass` (`:34-39`) | SA, A | body `ClassRequest` (@Valid) | **201** `ApiResponse<ClassResponse>` | yo'q | |
| PUT | `/api/classes/{id}` | `updateClass` (`:41-46`) | SA, A | path `id`; body `ClassRequest` (@Valid) | `ApiResponse<ClassResponse>` | yo'q | |
| DELETE | `/api/classes/{id}` | `deleteClass` (`:48-53`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | Soft: `isActive=false` (`AcademicService.java:80-84`) |

#### B) Sections — `/api/sections`

SecurityConfig qoidalari Classes bilan bir xil (`:199`, DELETE `:196`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/sections` | `getAllSections` (`:57-62`) | **AUTH** | query `page=0`, `size=20` | `ApiResponse<PageResponse<SectionResponse>>` | PageResponse, `createdAt DESC` (`AcademicService.java:89`) | |
| GET | `/api/sections/{id}` | `getSectionById` (`:64-67`) | **AUTH** | path `id` | `ApiResponse<SectionResponse>` | yo'q | |
| POST | `/api/sections` | `createSection` (`:69-74`) | SA, A | body `SectionRequest` (@Valid) | **201** `ApiResponse<SectionResponse>` | yo'q | |
| PUT | `/api/sections/{id}` | `updateSection` (`:76-81`) | SA, A | path `id`; body `SectionRequest` (@Valid) | `ApiResponse<SectionResponse>` | yo'q | |
| DELETE | `/api/sections/{id}` | `deleteSection` (`:83-88`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | Soft (`AcademicService.java:129-133`) |

#### C) Subjects — `/api/subjects`

SecurityConfig qoidalari Classes bilan bir xil.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/subjects` | `getAllSubjects` (`:92-97`) | **AUTH** | query `page=0`, `size=20` | `ApiResponse<PageResponse<SubjectResponse>>` | PageResponse (`AcademicService.java:138`) | |
| GET | `/api/subjects/{id}` | `getSubjectById` (`:99-102`) | **AUTH** | path `id` | `ApiResponse<SubjectResponse>` | yo'q | |
| POST | `/api/subjects` | `createSubject` (`:104-109`) | SA, A | body `SubjectRequest` (@Valid) | **201** `ApiResponse<SubjectResponse>` | yo'q | |
| PUT | `/api/subjects/{id}` | `updateSubject` (`:111-116`) | SA, A | path `id`; body `SubjectRequest` (@Valid) | `ApiResponse<SubjectResponse>` | yo'q | |
| DELETE | `/api/subjects/{id}` | `deleteSubject` (`:118-123`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | Soft (`AcademicService.java:176-180`) |

#### D) Timetable — `/api/timetable`

SecurityConfig qoidalari:
- GET `/api/timetable/grid` → SA, A, T (`SecurityConfig.java:100-101`).
- GET `/api/timetable/**` → SA, A, T (`:102-103`). Bu qoida `/api/timetable` ning o'zini ham qamraydi (PathPattern'da `/**` nol segmentni ham qabul qiladi).
- POST/PUT → `:199` (authenticated).
- DELETE → `:196`.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/timetable` | `getAllTimetable` (`:127-133`) | SA, A, T | query `page=0`, `size=20` | `ApiResponse<PageResponse<TimetableResponse>>` | PageResponse, `createdAt DESC` (`AcademicService.java:185-199`) | TEACHER uchun `findByTeacherScope` bilan filtrlanadi. |
| GET | `/api/timetable/group/{groupId}` | `getTimetableByGroup` (`:135-139`) | SA, A, T | path `groupId` | `ApiResponse<List<TimetableResponse>>` | yo'q | TEACHER uchun `assertOwnsGroup` (`AcademicService.java:216-220`). |
| GET | `/api/timetable/by-room` | `getByRoom` (`:141-145`) | SA, A, T | query `dayOfWeek: String` (majburiy; MONDAY..SUNDAY, case-insensitive) | **`RoomTimetableDto` — ApiResponse envelope YO'Q** | yo'q | Noto'g'ri kun → 400 (`AcademicService.java:675-684`). TEACHER faqat o'z darslarini ko'radi (`:392-399`). |
| GET | `/api/timetable/grid` | `getTimetableGrid` (`:147-152`) | SA, A, T | query `dayOfWeek: String` (majburiy) | `ApiResponse<TimetableGridResponse>` | yo'q | Faqat faol xonalar. Qat'iy qiymatlar: `startTime:"08:00"`, `endTime:"22:00"`, `slotMinutes:30`. `color` groupId bo'yicha palitradan olinadi (`AcademicService.java:273-313`). |
| GET | `/api/timetable/{id}` | `getTimetableById` (`:154-158`) | SA, A, T | path `id` | `ApiResponse<TimetableResponse>` | yo'q | TEACHER o'z darsi bo'lmasa → 403 (`AcademicService.java:401-410`). |
| GET | `/api/timetable/class/{classId}` | `getTimetableByClass` (`:160-164`) | SA, A, T | path `classId` | `ApiResponse<List<TimetableResponse>>` | yo'q | TEACHER uchun natija filtrlanadi, 403 qaytmaydi (`:209-214`). |
| GET | `/api/timetable/teacher/{teacherId}` | `getTimetableByTeacher` (`:166-170`) | SA, A, T | path `teacherId` | `ApiResponse<List<TimetableResponse>>` | yo'q | TEACHER boshqa teacherId so'rasa → 403 (`:223-231`). |
| POST | `/api/timetable` | `createTimetable` (`:172-184`) | SA, A | body `TimetableRequest` (@Valid) | **201**, `ResponseEntity<?>`: `daysOfWeek` bo'sh bo'lmasa `ApiResponse<List<TimetableResponse>>` ("Timetable entries created"), aks holda `ApiResponse<TimetableResponse>` ("Timetable entry created") | yo'q | **Javob shakli polimorf.** Xona/o'qituvchi to'qnashuvi yoki start ≥ end bo'lsa → 400 (`AcademicService.java:534-561`). `dayOfWeek` ham, `daysOfWeek` ham berilmasa → 400 (`:629-631`). |
| PUT | `/api/timetable/{id}` | `updateTimetable` (`:186-191`) | SA, A | path `id`; body `TimetableRequest` (@Valid) | `ApiResponse<TimetableResponse>` | yo'q | Faqat bitta `dayOfWeek` ishlatiladi, `daysOfWeek` e'tiborsiz qoldiriladi. `dayOfWeek` bo'sh bo'lsa → 400 (`AcademicService.java:517`). Null ID lar mavjud bog'lanishni o'chirmaydi (`:521-530`). |
| DELETE | `/api/timetable/{id}` | `deleteTimetable` (`:193-198`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | **Hard delete** (`AcademicService.java:353-355`). |

Yo'l tartibi: `/grid` va `/by-room` literal segmentlar bo'lgani uchun `/{id}` dan ustun turadi (Spring MVC'da literal naqsh aniqroq hisoblanadi).

**DTO (Academic)**

`ClassRequest` (`dto/request/ClassRequest.java:7-11`):
- `className: String` — `@NotBlank`
- `classCode: String`

`SectionRequest` (`dto/request/SectionRequest.java:7-14`):
- `sectionName: String` — `@NotBlank`
- `classId: Long`
- `teacherId: Long`
- `room: String`
- `maxStudents: Integer`

`SubjectRequest` (`dto/request/SubjectRequest.java:7-13`):
- `subjectName: String` — `@NotBlank`
- `subjectCode: String`
- `classId: Long`
- `teacherId: Long`

`TimetableRequest` (`dto/request/TimetableRequest.java:10-35`):
- `groupId: Long`
- `classId: Long`
- `sectionId: Long`
- `subjectId: Long`
- `teacherId: Long`
- `classroomId: Long`
- `dayOfWeek: String` — eski format, bitta kun
- `daysOfWeek: List<String>` — yangi format, bir necha kun
- `startTime: LocalTime` — `@NotNull`; `"HH:mm"` yoki `"HH:mm:ss"`
- `endTime: LocalTime` — `@NotNull`
- `academicYear: String`
- `roomNumber: String` — `classroomId` bo'lmasa, xona raqam bo'yicha qidiriladi

`ClassResponse` (`dto/response/ClassResponse.java:7-13`):
- `{id: Long, className: String, classCode: String, isActive: Boolean, createdAt: LocalDateTime}`

`SectionResponse` (`dto/response/SectionResponse.java:7-18`):
- `{id: Long, sectionName: String, classId: Long, className: String, teacherId: Long, teacherName: String, room: String, maxStudents: Integer, isActive: Boolean, createdAt: LocalDateTime}`

`SubjectResponse` (`dto/response/SubjectResponse.java:7-17`):
- `{id: Long, subjectName: String, subjectCode: String, classId: Long, className: String, teacherId: Long, teacherName: String, isActive: Boolean, createdAt: LocalDateTime}`

`TimetableResponse` (`dto/response/TimetableResponse.java:8-28`):
- Maydonlar: `id: Long`, `groupId: Long`, `groupName: String`, `classId: Long`, `className: String`, `sectionId: Long`, `sectionName: String`, `subjectId: Long`, `subjectName: String`, `teacherId: Long`, `teacherName: String`, `classroomId: Long`, `roomName: String`, `roomNumber: String`, `dayOfWeek: String`, `startTime: LocalTime`, `endTime: LocalTime`, `academicYear: String`, `createdAt: LocalDateTime`.

`RoomTimetableDto` (`dto/response/RoomTimetableDto.java:8-11`):
- `{classrooms: List<ClassroomBriefDto>, lessons: List<RoomLessonDto>}`
- `ClassroomBriefDto` (`dto/response/ClassroomBriefDto.java:10-14`): `{id: Long, name: String, capacity: Integer}`
- `RoomLessonDto` (`dto/response/RoomLessonDto.java:6-19`): `{id: Long, classroomId: Long, groupId: Long, groupName: String, teacherId: Long, teacherName: String, startTime: String "HH:mm", endTime: String, studentCount: long, capacity: Integer, freeSeats: Integer, courseColor: String}`. `courseColor` hech qachon to'ldirilmaydi, doim `null` (`AcademicService.java:238-266`).

`TimetableGridResponse` (`dto/response/TimetableGridResponse.java:15-52`):
- `{dayOfWeek: String, startTime: String, endTime: String, slotMinutes: int, rooms: List<TimetableGridRoomDto>, entries: List<TimetableGridEntryDto>}`
- `TimetableGridRoomDto`: `{id: Long, name: String, capacity: Integer}`
- `TimetableGridEntryDto`: `{id: Long, roomId: Long, groupId: Long, groupName: String, courseName: String, teacherName: String, startTime: String "HH:mm", endTime: String, color: String "#rrggbb"}`

---

### Davomat — `controller/AttendanceController.java`

- Base path: `/api/attendance` (`AttendanceController.java:17`).
- Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")` (`:19`).
- SecurityConfig: `/api/attendance/**` → SA, A, T (`SecurityConfig.java:165-166`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | `/api/attendance/mark` | `markAttendance` (`:23-28`) | SA, A, T | body `AttendanceRequest` (@Valid) | **200** (201 emas) `ApiResponse<List<AttendanceResponse>>`, message "Attendance marked" | yo'q | Upsert, kalit: (student, group, sana). To'liq qoidalar quyidagi oqimda. |
| GET | `/api/attendance/missing/my` | `getMyMissingAttendance` (`:30-37`) | **faqat T** (metod `@PreAuthorize` class darajasidagisini bekor qiladi, `:31`) | query `from: LocalDate` (ISO, ixtiyoriy), `to: LocalDate` (ixtiyoriy) | `ApiResponse<TeacherMissingAttendanceResponse>` | yo'q | Default oraliq: bugun−30 kun … bugun. `to` kelajakda bo'lsa bugungacha qisqartiriladi (`service/AttendanceService.java:220-227`). Faqat `missingCount>0` bo'lgan guruhlar qaytadi. |
| GET | `/api/attendance/missing` | `getMissingAttendance` (`:39-46`) | SA, A, T | query `groupId: Long` (**majburiy**), `from`, `to` (ixtiyoriy) | `ApiResponse<MissingAttendanceResponse>` | yo'q | TEACHER uchun `assertOwnsGroup` (`AttendanceService.java:194-199`). |
| GET | `/api/attendance/group/{groupId}` | `getGroupAttendance` (`:48-54`) | SA, A, T | path `groupId`; query `date: LocalDate` (ixtiyoriy, default bugun) | `ApiResponse<List<AttendanceResponse>>` | yo'q | Shu kunga davomat bo'lmasa, faol a'zolar "bo'sh shablon" sifatida qaytadi: `id:null`, `status:null`, `notes:""`, `groupName:null` (`AttendanceService.java:376-399`). |
| GET | `/api/attendance/student/{studentId}` | `getStudentAttendance` (`:56-61`) | SA, A, T | path `studentId` | `ApiResponse<List<AttendanceResponse>>` | yo'q (to'liq tarix, sana DESC) | TEACHER uchun `assertOwnsStudent`. Tekshiruvdan o'tgach o'quvchining **barcha** guruhlardagi davomati qaytadi (`AttendanceService.java:401-406`). |

**DTO**

`AttendanceRequest` (`dto/request/AttendanceRequest.java:8-20`):
- `groupId: Long` — `@NotNull`
- `date: LocalDate` — `@NotNull`
- `attendances: List<StudentAttendanceItem>` — `@NotNull`. Bo'sh ro'yxat ham qabul qilinadi; elementlarga `@Valid` qo'yilmagan.
- `StudentAttendanceItem`:
  - `studentId: Long`
  - `status: AttendanceStatus` — PRESENT | ABSENT | EXCUSED | LATE (`entity/enums/AttendanceStatus.java:2`); null bo'lsa PRESENT
  - `notes: String`
  - `excused: Boolean`
  - `excuseReason: String`

`AttendanceResponse` (`dto/response/AttendanceResponse.java:7-19`):
- `{id: Long, studentId: Long, studentName: String, groupId: Long, groupName: String, attendanceDate: LocalDate, status: AttendanceStatus, notes: String, excused: Boolean, excuseReason: String, createdAt: LocalDateTime}`

`MissingAttendanceResponse` (`dto/response/MissingAttendanceResponse.java:15-30`):
- `{groupId: Long, groupName: String, missingDates: List<MissingDateItem>, missingCount: int}`
- `MissingDateItem`: `{date: LocalDate, dayOfWeek: String (MONDAY…), startTime: String "HH:mm"}`

`TeacherMissingAttendanceResponse` (`dto/response/TeacherMissingAttendanceResponse.java:14-17`):
- `{totalMissing: int, groups: List<MissingAttendanceResponse>}`

---

### Davomat qulfini ochish so'rovlari — `controller/AttendanceUnlockRequestController.java`

- Base path: `/api/attendance/unlock-requests` (`AttendanceUnlockRequestController.java:20`). Class-level `@PreAuthorize` yo'q.
- SecurityConfig: `/api/attendance/**` → SA, A, T (`SecurityConfig.java:165-166`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | `/api/attendance/unlock-requests` | `createRequest` (`:26-32`) | **T** | body `AttendanceUnlockCreateDto` (@Valid) | **201** `ApiResponse<AttendanceUnlockResponseDto>`, "Unlock request submitted" | yo'q | Guruh teacherga tegishli bo'lishi shart. Shu teacher+guruh+sana uchun PENDING so'rov allaqachon bo'lsa → 400 "So'rov allaqachon yuborilgan" (`service/AttendanceUnlockRequestService.java:40-64`). |
| GET | `/api/attendance/unlock-requests` | `getRequests` (`:35-44`) | SA, A | query `status: String` (default `"PENDING"`; PENDING/APPROVED/REJECTED, case-insensitive; noto'g'risi → 400), `groupId: Long` (ixtiyoriy), `date: LocalDate` (ixtiyoriy) | `ApiResponse<List<AttendanceUnlockResponseDto>>` | yo'q (`createdAt DESC`) | "Barcha statuslar" rejimi yo'q: `status` har doim filtr sifatida qo'llanadi (`AttendanceUnlockRequestService.java:71-78, 180-189`). |
| GET | `/api/attendance/unlock-requests/my` | `getMyRequests` (`:52-59`) | **T** | query `groupId`, `date` (ixtiyoriy) | `ApiResponse<List<AttendanceUnlockResponseDto>>` | yo'q | Filtr bo'lmasa teacherning barcha so'rovlari, hamma statuslar bilan qaytadi (`:89-98`). |
| PATCH | `/api/attendance/unlock-requests/{id}/approve` | `approveRequest` (`:61-67`) | SA, A | path `id`; body `AttendanceUnlockApproveDto` (**ixtiyoriy**, `required=false`) | `ApiResponse<AttendanceUnlockResponseDto>`, "Request approved" | yo'q | Faqat PENDING holatdagi so'rovni tasdiqlash mumkin, aks holda 400. `penaltyAmount>0` bo'lsa o'qituvchiga `BonusPenalty(PENALTY)` yaratiladi (`:101-132`). |
| PATCH | `/api/attendance/unlock-requests/{id}/reject` | `rejectRequest` (`:69-73`) | SA, A | path `id`; body yo'q | `ApiResponse<AttendanceUnlockResponseDto>`, "Request rejected" | yo'q | Faqat PENDING holatdagi so'rovni rad etish mumkin (`:135-151`). |
| GET | `/api/attendance/unlock-requests/count` | `getPendingCount` (`:75-79`) | SA, A | — | `ApiResponse<Long>`, `data` = PENDING soni | yo'q | Qaytadigan qiymat raqam, ob'ekt emas. `/api/notices/unread-count` bilan solishtiring. |

**DTO**

`AttendanceUnlockCreateDto` (`dto/request/AttendanceUnlockCreateDto.java:8-16`):
- `groupId: Long` — `@NotNull`
- `attendanceDate: LocalDate` — `@NotNull`
- `note: String` — javobda `teacherNote` nomi bilan qaytadi

`AttendanceUnlockApproveDto` (`dto/request/AttendanceUnlockApproveDto.java:7-10`):
- `penaltyAmount: BigDecimal`
- `penaltyReason: String`
- Validatsiya yo'q.

`AttendanceUnlockResponseDto` (`dto/response/AttendanceUnlockResponseDto.java:15-28`):
- `{id: Long, teacherId: Long, teacherName: String, groupId: Long, groupName: String, attendanceDate: LocalDate, status: UnlockRequestStatus, teacherNote: String, reviewedById: Long, reviewedByName: String, reviewedAt: LocalDateTime, createdAt: LocalDateTime}`
- `UnlockRequestStatus` = PENDING | APPROVED | REJECTED (`entity/enums/UnlockRequestStatus.java:3-7`).
- Javobda `penaltyAmount` yo'q, ya'ni jarima bu DTO orqali ko'rinmaydi.

#### Davomat qulfi va ochish oqimi (endpointlar darajasida)

1. **Qulf qoidasi.** Qoida `POST /api/attendance/mark` ichida ishlaydi (`service/AttendanceService.java:56-80`).
   - Avval guruh topiladi. TEACHER bo'lsa `assertOwnsGroup` tekshiriladi, ya'ni **TEACHER faqat o'z guruhiga davomat yoza oladi**, boshqa guruh uchun 403 "Bu guruh sizga tegishli emas" (`TeacherAccessService.java:82-92`).
   - `assertGroupHasLessonOnDate`: shu hafta kunida guruhning `GroupScheduleDay` yoki `Timetable` yozuvi bo'lishi kerak, aks holda 400 "Bu kunda guruhda dars yo'q". Bu tekshiruv admin uchun ham ishlaydi (`:170-191`).
   - Qulf: `date < bugun` va foydalanuvchi admin (SA/A) bo'lmasa, `existsByTeacherIdAndGroupIdAndAttendanceDateAndStatus(..., APPROVED)` talab qilinadi. U bo'lmasa `ForbiddenException` → 403 "Bu kun uchun ruxsat kerak. Admindan so'rang." (`:70-80`).
   - Bugungi kun hech qanday ruxsatsiz ochiq. Admin (SA/A) har qanday o'tgan kunni ruxsatsiz yoza oladi.
   - Kelajakdagi sanalar **bloklanmagan**: dars kuni bo'lsa, TEACHER ertangi davomatni ham yoza oladi (`:70` faqat `isBefore(today)` ni tekshiradi).
   - Har bir element bo'yicha: status ABSENT, EXCUSED yoki LATE bo'lsa `notes` yoki `excuseReason` majburiy, aks holda 400 "Sabab kiritilishi shart" (`:84-92`). `status=ABSENT` va `excused=true` kelsa status EXCUSED ga aylantiriladi (`:116-124`).
   - Qo'shimcha ta'sirlar:
     - PER_LESSON balans: LESSON_CHARGE yoki LESSON_REFUND yoziladi (`:332-367`).
     - `studentPaymentLifecycleService` chaqiriladi.
     - ABSENT bo'lsa ota-onaga Telegram xabari yuboriladi (`:139-164`).
     - Audit yozuvi qoldiriladi (`:53-55`).
2. **So'rov.** TEACHER `POST /unlock-requests` `{groupId, attendanceDate, note}` yuboradi va status PENDING bo'ladi.
3. **Ko'rish.**
   - Admin `GET /unlock-requests?status=PENDING` va `GET /unlock-requests/count` (badge) dan foydalanadi.
   - TEACHER `GET /unlock-requests/my?groupId=&date=` orqali o'z so'rovining statusini tekshiradi. Kontroller izohida frontend aynan shu guruh va kunni so'rashi kerakligi aytilgan (`AttendanceUnlockRequestController.java:46-51`).
4. **Qaror.**
   - Admin `PATCH /{id}/approve` qiladi (ixtiyoriy `{penaltyAmount, penaltyReason}`): status APPROVED, `reviewedBy/At` yoziladi, ixtiyoriy jarima o'qituvchiga qo'shiladi.
   - Yoki admin `PATCH /{id}/reject` qiladi: status REJECTED.
   - Ikkala holatda ham so'rov PENDING bo'lmasa → 400.
5. **Foydalanish.** APPROVED bo'lgach TEACHER o'sha (guruh, sana) uchun `POST /api/attendance/mark` ni qayta chaqiradi.
   - Ruxsat **iste'mol qilinmaydi va muddati tugamaydi**: APPROVED holati abadiy qoladi va o'sha kunni istalgancha qayta tahrirlash mumkin (qarang: Muammolar).
6. **Holatlar diagrammasi:** `PENDING → APPROVED` yoki `PENDING → REJECTED`. Oxirgi holatdan qaytish yo'q; REJECTED dan keyin yangi so'rov yaratish mumkin.

---

### Uy vazifalari — `controller/HomeworkController.java`

- Base path: `/api/homework` (**birlik shaklida**, `HomeworkController.java:16`). Class-level `@PreAuthorize` yo'q.
- SecurityConfig: `/api/homework/**` → SA, A, T (`SecurityConfig.java:167-168`). Bu qoida `:196` DELETE qoidasidan oldin turadi, lekin `@PreAuthorize` DELETE'ni baribir SA/A bilan cheklaydi.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/homework` | `getAllHomeworks` (`:22-28`) | SA, A, T | query `page=0`, `size=20` | `ApiResponse<PageResponse<HomeworkResponse>>` | PageResponse, `createdAt DESC`, faqat faollar | TEACHER faqat o'zinikini ko'radi (`service/HomeworkService.java:33-43`). |
| GET | `/api/homework/group/{groupId}` | `getHomeworkByGroup` (`:30-37`) | SA, A, T | path `groupId`; query `page=0`, `size=`**`50`** | `ApiResponse<PageResponse<HomeworkResponse>>` | PageResponse | `assertOwnsGroup`. |
| GET | `/api/homework/{id}` | `getHomeworkById` (`:39-43`) | SA, A, T | path `id` | `ApiResponse<HomeworkResponse>` | yo'q | TEACHER uchun: vazifa uniki yoki uning guruhiga tegishli bo'lishi kerak (`:138-149`). |
| POST | `/api/homework` | `createHomework` (`:45-50`) | SA, A, T | body `HomeworkRequest` (@Valid) | **201** `ApiResponse<HomeworkResponse>` | yo'q | TEACHER bo'lsa `teacher` majburan joriy o'qituvchiga o'rnatiladi. `assignedDate` bo'sh bo'lsa bugungi sana (`:60-72`). |
| PUT | `/api/homework/{id}` | `updateHomework` (`:52-57`) | SA, A, T | path `id`; body `HomeworkRequest` (@Valid) | `ApiResponse<HomeworkResponse>` | yo'q | TEACHER `teacherId` orqali vazifani boshqa o'qituvchiga o'tkazishi mumkin (`:166-168`). |
| DELETE | `/api/homework/{id}` | `deleteHomework` (`:59-64`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | Soft: `isActive=false`. |
| GET | `/api/homework/{id}/submissions` | `getSubmissions` (`:66-70`) | SA, A, T | path `id` | `ApiResponse<List<HomeworkSubmissionResponse>>` | yo'q | Kirish huquqi tekshiriladi. |
| POST | `/api/homework/{id}/submissions` | `addSubmission` (`:72-78`) | SA, A, T (`@PreAuthorize("isAuthenticated()")`, lekin URL qoidasi `:167` cheklaydi) | path `id`; body `HomeworkSubmissionRequest` (@Valid) | **201** `ApiResponse<HomeworkSubmissionResponse>` | yo'q | Egalik **tekshirilmaydi** (`:100-121`). Takroriy topshiriq → 409. `status` default "SUBMITTED". |
| PUT | `/api/homework/submissions/{submissionId}` | `updateSubmission` (`:80-86`) | SA, A, T | path `submissionId`; body `HomeworkSubmissionRequest` (@Valid; `studentId` majburiy, lekin ishlatilmaydi) | `ApiResponse<HomeworkSubmissionResponse>` | yo'q | Egalik **tekshirilmaydi** (`:123-131`). Faqat `marksObtained`, `remarks` va `status` yangilanadi. |

**DTO**

`HomeworkRequest` (`dto/request/HomeworkRequest.java:11-23`):
- `title: String` — `@NotBlank`
- `description: String`
- `subjectId: Long`
- `classId: Long`
- `groupId: Long`
- `teacherId: Long`
- `assignedDate: LocalDate`
- `dueDate: LocalDate` — `@NotNull`
- `marks: BigDecimal`

`HomeworkSubmissionRequest` (`dto/request/HomeworkSubmissionRequest.java:10-18`):
- `studentId: Long` — `@NotNull`
- `submittedAt: LocalDateTime`
- `fileUrl: String`
- `remarks: String`
- `marksObtained: BigDecimal`
- `status: String` — erkin satr

`HomeworkResponse` (`dto/response/HomeworkResponse.java:11-29`):
- `{id: Long, uuid: UUID, title: String, description: String, subjectId: Long, subjectName: String, classId: Long, className: String, groupId: Long, groupName: String, teacherId: Long, teacherName: String, assignedDate: LocalDate, dueDate: LocalDate, marks: BigDecimal, isActive: Boolean, createdAt: LocalDateTime}`

`HomeworkSubmissionResponse` (`dto/response/HomeworkSubmissionResponse.java:9-21`):
- `{id: Long, homeworkId: Long, homeworkTitle: String, studentId: Long, studentName: String, submittedAt: LocalDateTime, fileUrl: String, remarks: String, marksObtained: BigDecimal, status: String, createdAt: LocalDateTime}`

---

### Imtihonlar — `controller/ExamController.java`

- Base path: `/api/exams` (`ExamController.java:17`). Class-level `@PreAuthorize` yo'q.
- SecurityConfig qoidalari:
  - GET `/api/exams`, `/api/exams/{id}`, `/api/exams/{id}/eligible-students` → SA, A, ACC, T; boshqa GET (`/results` va h.k.) `/api/exams/**` → SA, A, T (2026-10-02).
  - POST `/api/exams/**` → SA, A, T (`:169-170`).
  - PUT `/api/exams/**` → SA, A, T (`:171-172`).
  - DELETE → `:196`.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/exams` | `getAllExams` (`:23-29`) | SA, A, ACC, T | query `groupId`, `from`, `to`, `status` (ACTIVE/UPCOMING/PAST/INACTIVE), `page=0`, `size=20` | `ApiResponse<PageResponse<ExamResponse>>` | PageResponse, `createdAt DESC`, faqat faollar | TEACHER uchun filtrlanadi (`service/ExamService.java:49-63`). |
| POST | `/api/exams/{id}/register-student` | `registerStudent` (`:31-39`) | SA, A, T | path `id`; **query** `studentId: Long` (majburiy) | **201** `ApiResponse<ExamRegistrationResponse>`, "Ro'yxatdan o'tdi" | yo'q | Egalik tekshirilmaydi. Takroriy ro'yxat → 409 (`ExamService.java:293-300`). |
| GET | `/api/exams/{id}/eligible-students` | `getEligibleStudents` (`:41-45`) | SA, A, ACC, T | path `id` | `ApiResponse<List<StudentResponse>>` | yo'q | Tizimdagi **barcha** o'quvchilar ichidan tanlanadi: `present ≥ 8` va faol guruhda `paymentStatus="PAID"` (`ExamService.java:272-290, :32`). Teacher scope yo'q. |
| POST | `/api/exams/{id}/calculate-payment` | `calculatePayment` (`:47-54`) | SA, A, T (`isAuthenticated()`, lekin URL `:169` cheklaydi) | path `id`; **query** `studentId: Long` | `ApiResponse<Map<String,Object>>` | yo'q | Faqat preview, yozuv qilinmaydi. Map kalitlari quyida. |
| GET | `/api/exams/{id}` | `getExamById` (`:56-60`) | SA, A, ACC, T | path `id` | `ApiResponse<ExamResponse>` | yo'q | `assertExamAccess` (`ExamService.java:396-407`). |
| POST | `/api/exams` | `createExam` (`:62-67`) | SA, A, T | body `ExamRequest` (@Valid) | **201** `ApiResponse<ExamResponse>` | yo'q | `groupId` berilgan bo'lsa guruh o'quvchilari avtomatik ro'yxatga olinadi (`ExamService.java:73-87`). |
| PUT | `/api/exams/{id}` | `updateExam` (`:69-74`) | SA, A, T | path `id`; body `ExamRequest` (@Valid) | `ApiResponse<ExamResponse>` | yo'q | |
| DELETE | `/api/exams/{id}` | `deleteExam` (`:76-81`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | Soft: `isActive=false`. |
| GET | `/api/exams/{id}/results` | `getResultsByExam` (`:83-87`) | SA, A, T | path `id` | `ApiResponse<List<ExamResultResponse>>` | yo'q | Har bir ro'yxatdagi o'quvchi uchun natija qaytadi; natijasi yo'q o'quvchi uchun "bo'sh" yozuv qaytadi (`ExamService.java:109-136`). |
| POST | `/api/exams/{id}/results` | `addResult` (`:89-95`) | SA, A, T | path `id`; body `ExamResultRequest` (@Valid, lekin DTO'da annotatsiya yo'q) | **201** `ApiResponse<ExamResultResponse>` | yo'q | `studentId` majburiy (servisda tekshiriladi, → 400). Takror → 409. `isPassed` serverda hisoblanadi (`:146-170`). |
| PUT | `/api/exams/{examId}/results/{resultId}` | `updateResult` (`:97-105`) | SA, A, T | path `examId`, `resultId`; body `ExamResultRequest` (**@Valid yo'q**) | `ApiResponse<ExamResultResponse>` | yo'q | `editNote` majburiy (→ 400). Natija shu examga tegishli bo'lmasa → 400. `editNote` "eski → yangi, sabab: …" formatida saqlanadi (`:177-211`). |
| PUT | `/api/exams/results/{resultId}` | `updateResultLegacy` (`:107-113`) | SA, A, T | path `resultId`; body `ExamResultRequest` | `ApiResponse<ExamResultResponse>` | yo'q | **@deprecated** (javadoc, `:107`). `@Deprecated` annotatsiyasi qo'yilmagan. |
| GET | `/api/exams/students/{studentId}/results` | `getResultsByStudent` (`:115-119`) | SA, A, T | path `studentId` | `ApiResponse<List<ExamResultResponse>>` | yo'q | `assertOwnsStudent`. |

`calculate-payment` `data` kalitlari (`ExamService.java:221-269`, LinkedHashMap):
- Doim bor: `examDate: LocalDate`, `examName: String`.
- To'lov tarixi va imtihon sanasi bo'lsa: `lastPaidUntil: LocalDate`.
- Qarzdorlik bo'lsa: `unpaidDays: long`, `monthlyPrice: BigDecimal`, `dailyRate: double`, `amountDue: BigDecimal`, `message: String`.
- Qarzdorlik bo'lmasa: `amountDue: 0`, `message: "To'lov kerak emas"`.
- To'lov tarixi yo'q bo'lsa: faqat `message: "To'lov tarixi topilmadi"`.

**DTO**

`ExamRequest` (`dto/request/ExamRequest.java:11-26`):
- `examName: String` — `@NotBlank`
- `examType: String` — erkin satr
- `classId: Long`
- `groupId: Long`
- `subjectId: Long`
- `examDate: LocalDate`
- `startTime: LocalTime`
- `endTime: LocalTime`
- `totalMarks: BigDecimal`
- `passMarks: BigDecimal`
- `academicYear: String`

`ExamResultRequest` (`dto/request/ExamResultRequest.java:8-45`):
- `studentId: Long` — POST'da majburiy (servisda tekshiriladi)
- `marksObtained: BigDecimal` — eski nom
- `score: BigDecimal` — yangi alias, ustun turadi (`resolveScore`, :32-37)
- `grade: String`
- `remarks: String` — eski nom
- `notes: String` — yangi alias, ustun turadi (`resolveNotes`, :39-44)
- `editNote: String` — PUT'da majburiy
- `isPassed: Boolean` — deprecated, e'tiborsiz qoldiriladi

`ExamResponse` (`dto/response/ExamResponse.java:12-31`):
- `{id: Long, uuid: UUID, examName: String, examType: String, classId: Long, className: String, groupId: Long, groupName: String, subjectId: Long, subjectName: String, examDate: LocalDate, startTime: LocalTime, endTime: LocalTime, totalMarks: BigDecimal, passMarks: BigDecimal, academicYear: String, isActive: Boolean, createdAt: LocalDateTime}`

`ExamRegistrationResponse` (`dto/response/ExamRegistrationResponse.java:12-23`):
- `{id: Long, examId: Long, studentId: Long, studentName: String, paymentStatus: String ("PAID" | "PENDING"), amountDue: BigDecimal, amountPaid: BigDecimal, registrationDate: LocalDate, status: String ("REGISTERED"), notes: String}`

`ExamResultResponse` (`dto/response/ExamResultResponse.java:10-40`):
- `{id: Long, examId: Long, examName: String, studentId: Long, studentName: String, marksObtained: BigDecimal, totalMarks: BigDecimal, passMarks: BigDecimal, grade: String, remarks: String, isPassed: Boolean, editNote: String, editedAt: LocalDateTime, editedBy: String, createdAt: LocalDateTime}`
- Jackson aliaslari (`@JsonProperty` getterlar): `score` (= marksObtained, :18-21), `notes` (= remarks, :26-29), `passed` (= isPassed, :32-35).
- JSON'da ikkala nom ham keladi: `marksObtained` va `score`, `remarks` va `notes`, `isPassed` va `passed`.

---

### E'lonlar — `controller/NoticeController.java`

- Base path: `/api/notices` (`NoticeController.java:18`). Class-level `@PreAuthorize` yo'q.
- SecurityConfig qoidalari:
  - GET `/api/notices/**` → AUTH (`SecurityConfig.java:112`).
  - POST `/api/notices/read-all` va `/api/notices/*/read` → AUTH (`:183-184`).
  - Boshqa POST → SA, A (`:185-186`).
  - PUT → SA, A (`:187-188`).
  - DELETE → `:196`.
- `{id}` naqshi regex bilan cheklangan: `{id:\d+}`.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/notices` | `getAllNotices` | STAFF | query `page=0`, `size=20` (1..100) | `ApiResponse<PageResponse<NoticeResponse>>` | PageResponse | SA/A — hamma e'lonlar (`createdAt DESC`); boshqa xodim — faqat o'z roliga ko'rinadigan faol e'lonlar (N-01, N-02). |
| GET | `/api/notices/active` | `getActive` | STAFF | query `limit` (1..50, default 50) | `ApiResponse<List<NoticeResponse>>` | yo'q | Bell lentasi — faol va **joriy rolga** ko'rinadigan (N-01; SA/A ham o'z roli bo'yicha). |
| GET | `/api/notices/latest` | `getLatest` | STAFF | query `limit` (default 5) | `ApiResponse<List<NoticeResponse>>` | yo'q | `/active` bilan bir xil. |
| GET | `/api/notices/unread-count` | `getUnreadCount` | STAFF | — | `ApiResponse<{count}>` | yo'q | Joriy rolga ko'rinadigan o'qilmaganlar (N-01). |
| POST | `/api/notices/read-all` | `markAllRead` | STAFF | — | `ApiResponse<Void>` | yo'q | Faqat o'ziga ko'rinadigan faol e'lonlar. |
| POST | `/api/notices/{id}/read` | `markRead` | STAFF | path `id` | `ApiResponse<Void>` | yo'q | Ko'rinmaydigan e'lon → 404. |
| GET | `/api/notices/{id}` | `getNoticeById` | STAFF | path `id` | `ApiResponse<NoticeResponse>` | yo'q | SA/A — istalgan; boshqalar — faqat ko'rinadigan faol e'lon, aks holda 404. |
| POST | `/api/notices` | `createNotice` | SA, A | body `NoticeRequest` (@Valid) | **201** `ApiResponse<NoticeResponse>` | yo'q | Auditoriya: `targetRoles` (`[]` — hamma) yoki eski `targetRole` / `publishedTo` (ALL, TEACHERS, STUDENTS, PARENTS); noma'lum → 400 `notice.audience.invalid`. |
| PUT | `/api/notices/{id}` | `updateNotice` | SA, A | path `id`; body `NoticeRequest` (@Valid) | `ApiResponse<NoticeResponse>` | yo'q | Auditoriya maydonlari (`targetRoles`/`targetRole`/`publishedTo`) yuborilmasa — **o'zgarmaydi** (avval `targetRole` NULL bo'lardi). `expiresAt` hali ham har doim qayta yoziladi. |
| DELETE | `/api/notices/{id}` | `deleteNotice` (`:89-95`) | SA, A | path `id` | `ApiResponse<Void>`, message i18n `notice.deleted` | yo'q | **Hard delete**, `notice_reads` ham o'chiriladi (`NoticeService.java:140-146`). |

**DTO**

`NoticeRequest` (`dto/request/NoticeRequest.java:10-28`):
- `title: String` — `@NotBlank`
- `content: String` — `@NotBlank`
- `noticeDate: LocalDate`
- `targetRoles: UserRole[]` — auditoriya (N-01, 2026-10-02); `[]` — hamma; berilmasa eski maydonlar o'qiladi
- `publishedTo: String` — **deprecated**: ALL | TEACHERS | STUDENTS | PARENTS
- `noticeType: String`
- `targetRole: String` — **deprecated** (bitta rol)
- `isActive: Boolean`
- `isPublished: Boolean`
- `publishedAt: LocalDateTime`
- `expiresAt: LocalDateTime` — ustun turadi
- `expiryDate: LocalDate` — `expiresAt` bo'lmasa kun oxiri (23:59:59) ga aylantiriladi
- `createdById: Long`

`NoticeResponse` (`dto/response/NoticeResponse.java:13-32`):
- **2026-10-02:** + `targetRoles: string[]` (effektiv auditoriya; eski yozuvlarda `publishedTo`/`targetRole` dan hisoblanadi), `audienceAll: boolean`. Yangi yozuvlarda `publishedTo` = `ALL` yoki `ROLES`, `targetRole` = null.
- `{id: Long, uuid: UUID, title: String, content: String, noticeDate: LocalDate, publishedTo: String, noticeType: String, targetRole: String, isActive: Boolean, isPublished: Boolean, publishedAt: LocalDateTime, expiresAt: LocalDateTime, expiryDate: LocalDate, isExpired: Boolean, isRead: Boolean, createdByName: String, createdAt: LocalDateTime}`
- `createdByName` aslida username qaytaradi, to'liq ism emas (`NoticeService.java:229`).

---

### Ta'tillar (Leave) — `controller/LeaveController.java`

- Base path: `/api/leaves` (`LeaveController.java:16`). Class-level `@PreAuthorize` yo'q.
- SecurityConfig'da `/api/leaves` uchun qoida **yo'q**:
  - GET/POST/PATCH → `anyRequest().authenticated()` (`SecurityConfig.java:199`).
  - DELETE → `:196`.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/leaves` | `getAllLeaves` (`:22-29`) | SA, A | query `page=0`, `size=20`, `status: String` (ixtiyoriy; aniq satr bo'yicha taqqoslanadi) | `ApiResponse<PageResponse<LeaveResponse>>` | PageResponse, `createdAt DESC` | |
| GET | `/api/leaves/teacher/{teacherId}` | `getLeavesByTeacher` (`:31-38`) | SA, A | path `teacherId`; query `page=0`, `size=`**`50`** | `ApiResponse<PageResponse<LeaveResponse>>` | PageResponse | Teacher'ning `user` i orqali qidiriladi. `user` bo'lmasa bo'sh sahifa qaytadi (`service/LeaveService.java:41-52`). |
| GET | `/api/leaves/pending` | `getPendingLeaves` (`:40-44`) | SA, A | — | `ApiResponse<List<LeaveResponse>>` | yo'q | |
| GET | `/api/leaves/{id}` | `getLeaveById` (`:46-49`) | **AUTH (har kim)** | path `id` | `ApiResponse<LeaveResponse>` | yo'q | `@PreAuthorize` va egalik tekshiruvi yo'q. |
| GET | `/api/leaves/{id}/deduction-preview` | `deductionPreview` | SA, A | path `id` | `ApiResponse<List<LeaveDeductionMonth>>` = `[{month, year, workDays, unpaidDays, fixedSalary, amount}]` | yo'q | 2026-10-02. Ta'til haqsiz deb hisoblanadi (holatidan qat'i nazar); formula `SalaryCalculationService.leaveDeductionAmount` (payroll bilan bitta). |
| GET | `/api/leaves/user/{userId}` | `getLeavesByUser` (`:51-57`) | **AUTH (har kim)** | path `userId`; query `page=0`, `size=20` | `ApiResponse<PageResponse<LeaveResponse>>` | PageResponse | Ixtiyoriy `userId` ni so'rash mumkin. |
| POST | `/api/leaves` | `submitLeave` (`:59-63`) | **AUTH (har kim)** | body `LeaveSubmitRequest` (@Valid) | **201** `ApiResponse<LeaveResponse>` | yo'q | `teacherId` amalda **majburiy** (`teacherRepository.findById(request.getTeacherId())`, `LeaveService.java:66-67`). `requester` ni so'rovdan olish mumkin, ya'ni boshqa foydalanuvchi nomidan ham ariza berish mumkin. Status "PENDING". |
| PATCH | `/api/leaves/{id}/status` | `updateStatus` (`:65-71`) | SA, A | path `id`; body `Map<String,Object>`: `{status: String (majburiy), reason?: String, approvedById?: Long}` | `ApiResponse<LeaveResponse>` | yo'q | `status` validatsiyasiz saqlanadi. `status` bo'lmasa NPE → 500. REJECTED bo'lsa `reason` matnga qo'shiladi. Tasdiqlovchi joriy foydalanuvchi emas, `approvedById` dan olinadi (`LeaveService.java:111-129`). |
| DELETE | `/api/leaves/{id}` | `deleteLeave` (`:73-78`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | Hard delete. |

**DTO**

`LeaveSubmitRequest` (`dto/request/LeaveSubmitRequest.java:10-27`):
- `requesterId: Long`
- `teacherId: Long` — amalda majburiy
- `leaveType: String` — `@NotBlank`; frontend qiymatlari: sick/casual/medical/maternity/other (TAXMIN)
- `fromDate: LocalDate` — `@NotNull`
- `toDate: LocalDate` — `@NotNull`; `from ≤ to` tekshirilmaydi
- `reason: String`

`LeaveResponse` (`dto/response/LeaveResponse.java:10-25`):
- `{id: Long, uuid: UUID, requesterId: Long, requesterName: String (username), teacherName: String, leaveType: String, fromDate: LocalDate, toDate: LocalDate, reason: String, status: String (PENDING | APPROVED | REJECTED — enum emas), approvedById: Long, approvedByName: String (username), approvedAt: LocalDateTime, createdAt: LocalDateTime}`

---

### Ko'chirishlar (Promotion) — `controller/PromotionController.java`

- Base path: `/api/promotions` (`PromotionController.java:18`). Class-level `@PreAuthorize` yo'q.
- SecurityConfig: `/api/promotions/**` → SA, A (`SecurityConfig.java:147-148`). Bu barcha metodlarni, shu jumladan annotatsiyasiz GET'larni ham qamraydi.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/promotions` | `getAllPromotions` (`:25-30`) | SA, A (URL orqali) | query `page=0`, `size=20` | `ApiResponse<PageResponse<PromotionResponse>>` | PageResponse | `@PreAuthorize` yo'q |
| GET | `/api/promotions/{id}` | `getPromotionById` (`:32-35`) | SA, A | path `id` | `ApiResponse<PromotionResponse>` | yo'q | |
| GET | `/api/promotions/student/{studentId}` | `getByStudent` (`:37-40`) | SA, A | path `studentId` | `ApiResponse<List<PromotionResponse>>` | yo'q | |
| POST | `/api/promotions` | `createPromotion` (`:42-47`) | SA, A | body `PromotionRequest` (@Valid) | **201** `ApiResponse<PromotionResponse>`, "Student promoted" | yo'q | Class/section asosidagi eski model. |
| POST | `/api/promotions/bulk-promote` | `bulkPromote` (`:49-60`) | SA, A | body `BulkPromoteRequest` (@Valid) | `ApiResponse<Map<String,Object>>`: `{count: int, message: String}` (`service/PromotionService.java:113-114, 162-163`) | yo'q | Manba guruhning barcha faol a'zolari yopiladi (exitReason TRANSFERRED) va maqsad guruhda yangi StudentGroup yaratiladi (`joinDate = targetYear-targetMonth-01`, `paymentStatus` PENDING). **Har qanday xato 500 + `ApiResponse.error("Bulk promotion failed: "+msg)`** ga aylanadi, 404/400 ham (`PromotionController.java:52-59`). |
| DELETE | `/api/promotions/{id}` | `deletePromotion` (`:62-67`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | |

**DTO**

`PromotionRequest` (`dto/request/PromotionRequest.java:9-21`):
- `studentId: Long` — `@NotNull`
- `fromClassId: Long`
- `toClassId: Long`
- `fromSectionId: Long`
- `toSectionId: Long`
- `fromAcademicYear: String`
- `toAcademicYear: String`
- `promotionDate: LocalDate`
- `promotedById: Long`
- `remarks: String`

`BulkPromoteRequest` (`dto/request/BulkPromoteRequest.java:9-29`):
- `sourceGroupId: Long` — `@NotNull`
- `targetGroupId: Long` — `@NotNull`
- `sourceMonth: int` — `@Min(1) @Max(12)`
- `sourceYear: int` — validatsiya yo'q, default 0
- `targetMonth: int` — `@Min(1) @Max(12)`
- `targetYear: int` — validatsiya yo'q; 0 bo'lsa `LocalDate.of(0, …)` hosil bo'ladi
- `remarks: String`

`PromotionResponse` (`dto/response/PromotionResponse.java:9-27`):
- `{id: Long, studentId: Long, studentName: String, fromClassId: Long, fromClassName: String, toClassId: Long, toClassName: String, fromSectionId: Long, fromSectionName: String, toSectionId: Long, toSectionName: String, fromAcademicYear: String, toAcademicYear: String, promotionDate: LocalDate, promotedByName: String, remarks: String, createdAt: LocalDateTime}`
- `sourceMonth`/`targetMonth` va guruh ma'lumotlari bu javobga kirmaydi.

---

### Shartnomalar — `controller/ContractController.java`

- Base path: `/api` (`ContractController.java:23`). Ichida ikkita resurs bor: `/contract-templates` va `/contracts`.
- Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")` (`:25`).
- SecurityConfig'da alohida qoida yo'q: `:199` (authenticated), DELETE → `:196`. Effektiv rollar hamma joyda SA, A.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/contract-templates` | `getAllTemplates` (`:30-33`) | SA, A | — | `ApiResponse<List<ContractTemplateDto>>` | yo'q | |
| GET | `/api/contract-templates/{id}` | `getTemplate` (`:35-38`) | SA, A | path `id` | `ApiResponse<ContractTemplateDto>` | yo'q | |
| POST | `/api/contract-templates` | `createTemplate` (`:40-45`) | SA, A | body `ContractTemplateCreateDto` (@Valid, lekin DTO'da annotatsiya **yo'q**) | **201** `ApiResponse<ContractTemplateDto>` | yo'q | `isDefault=true` bo'lsa boshqa shablonlarning default belgisi olib tashlanadi (`service/ContractService.java:58-65`). |
| PUT | `/api/contract-templates/{id}` | `updateTemplate` (`:47-53`) | SA, A | path `id`; body `ContractTemplateCreateDto` | `ApiResponse<ContractTemplateDto>` | yo'q | |
| DELETE | `/api/contract-templates/{id}` | `deleteTemplate` (`:55-59`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | Hard delete. |
| GET | `/api/contracts` | `getAllContracts` (`:61-71`) | SA, A | query `studentId: Long?`, `status: String?` (DRAFT/SIGNED/ACCEPTED; noto'g'ri qiymat **jimgina e'tiborsiz qoldiriladi**, `ContractService.java:306-315`), `q: String?` (ism/telefon/raqam, `%`/`_` oddiy belgi), `from`/`to: LocalDate?` (shartnoma sanasi), `page=0`, `size=20` | `ApiResponse<PageResponse<ContractDto>>` | PageResponse; sort qat'iy: `contractDate DESC, createdAt DESC` (`ContractController.java:67-68`) | |
| GET | `/api/contracts/student/{studentId}` | `getByStudent` (`:73-76`) | SA, A | path `studentId` | `ApiResponse<List<ContractDto>>` | yo'q | |
| GET | `/api/contracts/{id}` | `getContract` (`:78-81`) | SA, A | path `id` | `ApiResponse<ContractDto>` | yo'q | |
| POST | `/api/contracts/generate` | `generateContract` (`:83-88`) | SA, A | body `ContractCreateDto` | **201** `ApiResponse<ContractDto>` | yo'q | `studentId` null → 400. `templateId` null bo'lsa default shablon olinadi. Shablon `{{studentName}}`, `{{groupName}}`, `{{monthlyFee}}` kabi placeholderlar bilan to'ldiriladi. Status DRAFT (`ContractService.java:111-133, 183-217`). |
| PATCH | `/api/contracts/{id}/accept-offer` | `acceptOffer` (`:90-94`) | SA, A | path `id` | `ApiResponse<ContractDto>` | yo'q | Faqat `type=OFFER` uchun ishlaydi, aks holda 400. Status ACCEPTED ga o'tadi (`:136-145`). |
| PATCH | `/api/contracts/{id}/sign` | `markSigned` (`:96-100`) | SA, A | path `id` | `ApiResponse<ContractDto>` | yo'q | Status SIGNED ga o'tadi, holat tekshiruvi yo'q (`:148-152`). |
| DELETE | `/api/contracts/{id}` | `deleteContract` (`:102-106`) | SA, A | path `id` | `ApiResponse<Void>` | yo'q | Hard delete. |

**DTO**

`ContractTemplateCreateDto` (`dto/request/ContractTemplateCreateDto.java:7-12`):
- `title: String`
- `type: ContractType` — OFFLINE | OFFER (`entity/enums/ContractType.java:3`)
- `content: String`
- `isDefault: Boolean`
- Validatsiya yo'q.

`ContractCreateDto` (`dto/request/ContractCreateDto.java:6-10`):
- `studentId: Long` — servisda majburiy
- `templateId: Long` — ixtiyoriy

`ContractTemplateDto` (`dto/response/ContractTemplateDto.java:15-23`):
- `{id: Long, uuid: String, title: String, type: ContractType, content: String, isDefault: boolean (primitiv!), createdAt: LocalDateTime}`

`ContractDto` (`dto/response/ContractDto.java:17-32`):
- `{id: Long, uuid: String, contractNumber: String, studentId: Long, studentName: String, templateId: Long, templateTitle: String, type: ContractType, renderedContent: String, status: ContractStatus, offerAccepted: boolean, acceptedAt: LocalDateTime, contractDate: LocalDate, createdAt: LocalDateTime}`
- `ContractStatus` = DRAFT | SIGNED | ACCEPTED (`entity/enums/ContractStatus.java:3`).

---


## 3. CRM: lidlar, bosqichlar, import, Meta, vazifalar, dashboard, analitika, audit, admin-repair

### Lidlar — `controller/LeadController.java`

- Base path: `/api/leads` (`controller/LeadController.java:51`)
- Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SALES_MANAGER')")` (`controller/LeadController.java:53`)
- SecurityConfig: `POST /api/leads/public` → `permitAll` (`config/SecurityConfig.java:86`); `/api/leads/**` → SUPER_ADMIN, ADMIN, SALES_MANAGER (`config/SecurityConfig.java:134-135`). DELETE uchun ham shu qoida birinchi mos keladi (`:196` gacha yetmaydi).
- Qamrov: SALES_MANAGER faqat o'ziga biriktirilgan lidlarni ko'radi/o'zgartiradi — `LeadAccessService` orqali servisda (`controller/LeadController.java:41-44`, `service/LeadService.java:210-214`).

| METHOD | path | Controller#metod (fayl:qator) | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | `/api/leads/public` | `createPublicLead` (`LeadController.java:61`) | ANONIM (permitAll: `SecurityConfig.java:86` + `@PreAuthorize("permitAll()")` `:62`) | body: `LeadRequest` (@Valid) | `ApiResponse<LeadResponse>`, **201**, message "Ariza qabul qilindi" | yo'q | Sayt formasi. status har doim `Lead.DEFAULT_STATUS` (NEW), format default "OFFLINE", source default "WEBSITE", ikkalasi `toUpperCase()` (`service/LeadService.java:112-130`). Rate-limit/captcha/dublikat tekshiruvi YO'Q — Muammolar ga qarang |
| POST | `/api/leads` | `createLead` (`:75`) | SA, ADMIN, SM | body: `LeadCreateRequest` (@Valid) | `ApiResponse<LeadResponse>`, **201**, "Lid yaratildi" | yo'q | Kanban "tez qo'shish". `status` berilsa faol bosqich kodi bo'lishi shart (`requireActiveCode`), aks holda NEW. `assignedUserId` berilmasa: SM o'ziga oladi, ADMIN/SA da null. SM boshqaga biriktira olmaydi → 403 (`service/LeadService.java:147-203`). Telefon dublikati tekshirilmaydi (`service/LeadService.java:138-140`) |
| GET | `/api/leads` | `getAll` (`:82`) | SA, ADMIN, SM | query: batafsil quyida "Lead filtrlari" | `ApiResponse<PageResponse<LeadResponse>>` | PageResponse; `page` int default 0; `size` int default 20 (1..100 ga qisiladi); sort YO'Q — qat'iy `createdAt DESC` (`service/LeadService.java:216-218`) | Kanban ustunlari uchun asosiy manba |
| GET | `/api/leads/export` | `exportLeads` (`:96`) | SA, ADMIN (`:97`) | query: `fromDate: String` (yyyy-MM-dd, ixt.), `toDate: String` (ixt.), `status: String` (ixt., vergul bilan ko'p), `operatorId: Long` (ixt.) | `byte[]` — `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `Content-Disposition: attachment; filename="lidlar_<yyyy-MM-dd>.xlsx"` (`:104-111`) | yo'q | ENVELOPE YO'Q. Filtr `buildLeadSpec` bilan bir xil, `search`/`unassigned` qo'llanmaydi (`service/LeadService.java:1090-1092`). Ustunlar: №, Ism, Telefon, Manba, Operator, Holat, Izohlar soni, Sana (`service/LeadService.java:1126`) |
| GET | `/api/leads/stats` | `getStats` (`:114`) | SA, ADMIN (`:115`) | — | `ApiResponse<LeadStatsResponse>` | yo'q | Butun baza bo'yicha |
| GET | `/api/leads/kanban-stats` | `getKanbanStats` (`:124`) | SA, ADMIN, SM | — | `ApiResponse<LeadKanbanStatsResponse>` | yo'q | Ustun sarlavhalari: har bosqich uchun count + totalAmount; SM uchun o'z lidlari (`service/LeadService.java:721-764`). Bo'sh bosqichlar ham `count=0` bilan qaytadi, tartib `lead_stages.sortOrder` |
| GET | `/api/leads/operators` | `getOperators` (`:129`) | SA, ADMIN (`:130`) | — | `ApiResponse<List<LeadOperatorResponse>>` | yo'q | Faol SA/ADMIN/SM foydalanuvchilar, ism bo'yicha tartib (`service/LeadService.java:594-603`). Operator filtri/biriktirish dropdowni uchun |
| GET | `/api/leads/{id}` | `getById` (`:135`) | SA, ADMIN, SM (SM faqat o'zinikini) | path: `id: Long` (`\d+`) | `ApiResponse<LeadResponse>` | yo'q | |
| PATCH | `/api/leads/{id}/assign` | `assignLead` (`:141`) | SA, ADMIN (`:142`) | path `id`; body: `LeadAssignRequest` (@Valid YO'Q) | `ApiResponse<LeadResponse>`, "Operator biriktirildi" | yo'q | `userId: null` → biriktirishni olib tashlash. Faqat SA/ADMIN/SM rolli userga (`service/LeadService.java:300-314`) |
| PATCH | `/api/leads/{id}/status` | `updateStatus` (`:152`) | SA, ADMIN, SM | path `id`; body: `LeadStatusRequest` (@Valid) | `ApiResponse<LeadResponse>`, "Status yangilandi" | yo'q | Kanban drag&drop. Faol bosqich kodi shart. CONVERTED turdagi bosqichga qo'lda o'tkazish TAQIQLANGAN → 400 (faqat `/convert` orqali) (`service/LeadService.java:347-349`). Bosqich `requiresAmount` bo'lsa va lidda summa yo'q bo'lsa `amount` majburiy → 400 (`:362-365`). Manfiy summa → 400. `requiresAmount` bosqichda tizim izohi yoziladi |
| PATCH | `/api/leads/{id}/amount` | `updateAmount` (`:165`) | SA, ADMIN, SM | path `id`; body: `LeadAmountRequest` (@Valid) | `ApiResponse<LeadResponse>`, "Summa yangilandi" | yo'q | `amount: null` — olib tashlash, lekin `requiresAmount` bosqichda → 400 (`service/LeadService.java:417-419`). O'zgarmasa audit/izoh yozilmaydi |
| POST | `/api/leads/{id}/comments` | `addComment` (`:174`) | SA, ADMIN, SM | path `id`; body: `LeadCommentRequest` (@Valid) | `ApiResponse<LeadCommentResponse>`, **201**, "Izoh qo'shildi" | yo'q | ESKI izoh tizimi (`lead_comments`), tahrirlanmaydi. Yangisi — `/notes` |
| GET | `/api/leads/{id}/comments` | `getComments` (`:183`) | SA, ADMIN, SM | path `id`; query `page` int=0, `size` int=20 (1..100) | `ApiResponse<PageResponse<LeadCommentResponse>>` | PageResponse, `createdAt DESC` (`service/LeadService.java:490-492`) | |
| GET | `/api/leads/{id}/tasks` | `getTasks` (`:192`) | SA, ADMIN, SM | path `id` | `ApiResponse<List<TaskResponse>>` | yo'q | Ochiqlar yuqorida, keyin yopilganlar (`controller/LeadController.java:191`) |
| GET | `/api/leads/{id}/timeline` | `getTimeline` (`:202`) | SA, ADMIN, SM | path `id`; query `page` int=0, `size` int=**50** (1..200, `service/LeadTimelineService.java:71,115`) | `ApiResponse<LeadTimelineResponse>` | O'Z shakli: `{items, page: PageInfo, openTasks}` — PageResponse EMAS | Xotirada sahifalanadi (`service/LeadTimelineService.java:115-125`) |
| POST | `/api/leads/{leadId}/notes` | `addNote` (`:211`) | SA, ADMIN, SM | path `leadId: Long`; body `LeadNoteRequest` (@Valid) | `ApiResponse<LeadNoteResponse>`, **201**, "Izoh qo'shildi" | yo'q | Yangi izoh tizimi (`lead_notes`) |
| GET | `/api/leads/{leadId}/notes` | `getNotes` (`:219`) | SA, ADMIN, SM | path `leadId` | `ApiResponse<List<LeadNoteResponse>>` | yo'q (to'liq ro'yxat) | |
| PUT | `/api/leads/notes/{id}` | `updateNote` (`:226`) | SA, ADMIN, SM — faqat MUALLIF (admin ham boshqaning izohini tahrirlay olmaydi) (`service/LeadService.java:544-553`) | path `id` (izoh id); body `LeadNoteRequest` | `ApiResponse<LeadNoteResponse>`, "Izoh yangilandi" | yo'q | Matn `trim()` qilinadi |
| DELETE | `/api/leads/notes/{id}` | `deleteNote` (`:235`) | SA, ADMIN, SM — muallif yoki SA/ADMIN (`service/LeadService.java:557-564`) | path `id` | `ApiResponse<Void>` (data=null), 200, "Izoh o'chirildi" | yo'q | 204 EMAS, 200 |
| GET | `/api/leads/{id}/history` | `getHistory` (`:242`) | SA, ADMIN, SM | path `id` | `ApiResponse<List<LeadStatusHistoryResponse>>` | yo'q | Yangidan eskiga |
| POST | `/api/leads/{id}/convert` | `convertToStudent` (`:253`) | SA, ADMIN, SM | path `id`; body `LeadConvertRequest` (@Valid, MAJBURIY) | `ApiResponse<LeadConvertResponse>`, **201**, "O'quvchiga o'tkazildi" | yo'q | `studyFormat` qaysi CONVERTED bosqichga tushishini hal qiladi. Javobdagi `id` — yangi O'QUVCHI id si |

#### Lead filtrlari (`GET /api/leads`) — kanban uchun

Controller: `controller/LeadController.java:82-94`; mantiq: `service/LeadService.java:206-223`, `buildLeadSpec` `service/LeadService.java:864-910`.

| Parametr | Tur | Required | Default | Xatti-harakat |
|---|---|---|---|---|
| `page` | int | yo'q | 0 | `max(page,0)` |
| `size` | int | yo'q | 20 | `min(max(size,1),100)` — 100 dan katta so'rov jimgina 100 ga qisqaradi |
| `status` | String | yo'q | — | Bosqich kodi (`lead_stages.code`). Vergul bilan bir nechta: `status=NEW,CONTACTED` → `IN (...)`. Har biri `trim()` + `toUpperCase()`. Kod mavjudligi TEKSHIRILMAYDI (ataylab — o'chirilgan bosqichdagi eski lidlar uchun, `:871-872`); noma'lum kod → bo'sh natija, 400 emas |
| `search` | String | yo'q | — | `lower(fullName) LIKE %term%` YOKI `phone LIKE %term%` (telefon uchun registr o'zgartirilmaydi, trim qilinadi) (`:891-897`) |
| `assignedUserId` | Long | yo'q | — | `assignedUser.id = X`. **SALES_MANAGER uchun e'tiborsiz** — majburan o'z id si qo'yiladi (`:212-213`) |
| `unassigned` | Boolean | yo'q | — | Faqat `true` ta'sir qiladi: `assignedUser IS NULL`. SM uchun e'tiborsiz (`:214`). `assignedUserId` bilan birga berilsa ikkala shart AND → bo'sh natija |
| `fromDate` | String `yyyy-MM-dd` | yo'q | — | `createdAt >= fromDate 00:00`. Noto'g'ri format → 400 "Noto'g'ri fromDate formati (yyyy-MM-dd)" (`:912-918`) |
| `toDate` | String `yyyy-MM-dd` | yo'q | — | `createdAt < toDate+1 kun 00:00` (ya'ni kun INKLYUZIV) |

Kanban uchun xulosa:
- Alohida "board" endpointi YO'Q. Frontend bosqichlarni `GET /api/lead-stages` dan oladi, sarlavha sonlarini `GET /api/leads/kanban-stats` dan, har ustun kartalarini `GET /api/leads?status=<code>&size=..&page=..` bilan (ustun boshiga bitta so'rov, max 100 karta/sahifa).
- "Biriktirilmagan" ustuni — `GET /api/leads?unassigned=true` (faqat SA/ADMIN; `kanban-stats.unassigned` bosqichdan QAT'I NAZAR, ustunlar bilan kesishadi — `dto/response/LeadKanbanStatsResponse.java:17-19`).
- `source`, `format`, `course`, `taskState` (vazifa rangi), summa bo'yicha filtr YO'Q. Saralash o'zgartirib bo'lmaydi (faqat `createdAt DESC`).
- Kartadagi vazifa holati `LeadResponse.taskState` / `nextTaskDueAt` / `nextTaskTitle` — har so'rovda batch hisoblanadi (`dto/response/LeadResponse.java:44-51`).

#### DTO tafsilotlari (Lead)

**Request**

- `LeadRequest` (`dto/request/LeadRequest.java:8-26`) — faqat `/public`:
  - `fullName: String` — `@NotBlank(message="{lead.fullName.required}")` (`:11-12`)
  - `phone: String` — `@NotBlank`, `@JsonDeserialize(PhoneDeserializer)` (`:14-16`)
  - `parentPhone: String` — `@JsonDeserialize(PhoneDeserializer)` (`:18-19`)
  - `address: String`, `course: String`, `format: String` (erkin matn, UPPERCASE ga o'giriladi; default "OFFLINE"), `source: String` (erkin matn, UPPERCASE; default "WEBSITE"), `notes: String` (`:21-25`)
  - `@Size` cheklovlari YO'Q.
- `LeadCreateRequest` (`dto/request/LeadCreateRequest.java:17-44`) — `LeadRequest` ning barcha maydonlari (xuddi shu validatsiya, `:20-34`) + 
  - `status: String` — bosqich kodi, berilmasa NEW (`:37`)
  - `assignedUserId: Long` — berilmasa SM → o'zi, ADMIN/SA → null (`:43`)
- `LeadAssignRequest` (`dto/request/LeadAssignRequest.java:6-8`): `userId: Long` (null = olib tashlash, validatsiya yo'q)
- `LeadStatusRequest` (`dto/request/LeadStatusRequest.java:9-21`): `status: String` `@NotBlank(message="{leadStatus.status.required}")` (`:11-12`); `amount: BigDecimal` ixtiyoriy (`:20`)
- `LeadAmountRequest` (`dto/request/LeadAmountRequest.java:16-18`): `amount: BigDecimal` — ataylab `@NotNull` emas (null = olib tashlash)
- `LeadCommentRequest` (`dto/request/LeadCommentRequest.java:7-11`): `text: String` `@NotBlank(message="{leadComment.text.required}")`
- `LeadNoteRequest` (`dto/request/LeadNoteRequest.java:7-11`): `text: String` `@NotBlank(message="{leadNote.text.required}")`
- `LeadConvertRequest` (`dto/request/LeadConvertRequest.java:12-28`):
  - `studyFormat: StudyFormat` — `@NotNull(message="{leadConvert.studyFormat.required}")` (`:19-20`); enum `StudyFormat` = ONLINE, OFFLINE (`entity/enums/StudyFormat.java:12-15`)
  - `groupId: Long`, `paymentStartDate: LocalDate`, `monthlyFee: BigDecimal`, `paymentType: PaymentType` (enum MONTHLY, PER_LESSON — `entity/enums/PaymentType.java:3-6`), `lessonPrice: BigDecimal`, `isTrial: Boolean` (`:22-27`)

**Response**

- `LeadResponse` (`dto/response/LeadResponse.java:17-55`):
  `id: Long`, `uuid: UUID`, `fullName: String`, `phone: String`, `parentPhone: String`, `address: String`, `course: String`, `format: String`, `status: String` (bosqich kodi), `statusLabel: String` (joriy tildagi nom), `source: String`, `amount: BigDecimal` (null bo'lishi mumkin), `notes: String`, `converted: Boolean`, `studentId: Long`, `studentName: String`, `assignedUserId: Long`, `assignedUserName: String`, `assignedAt: LocalDateTime`, `commentsCount: long`, `lastCommentText: String`, `nextTaskDueAt: LocalDateTime`, `nextTaskTitle: String`, `taskState: LeadTaskState`, `createdAt: LocalDateTime`, `updatedAt: LocalDateTime`
  - enum `LeadTaskState` = NONE (kulrang), PLANNED (yashil), TODAY (sariq), OVERDUE (qizil) (`entity/enums/LeadTaskState.java:14-23`)
- `LeadStatsResponse` (`dto/response/LeadStatsResponse.java:15-27`):
  `total: long`, **`new: long`** (Java nomi `newCount`, `@JsonProperty("new")` `:18-19`), `converted: long`, `rejected: long`, `byStatus: Map<String, Long>` (bosqich kodi → son, sortOrder tartibida), `byOperator: List<LeadOperatorStatsResponse>`, `unassigned: long`
  - `LeadOperatorStatsResponse` (`dto/response/LeadOperatorStatsResponse.java:12-17`): `userId: Long`, `name: String`, `count: long`, `converted: long`
- `LeadKanbanStatsResponse` (`dto/response/LeadKanbanStatsResponse.java:25-28`): `columns: List<LeadKanbanColumnDto>`, `unassigned: LeadKanbanColumnDto`
  - `LeadKanbanColumnDto` (`dto/response/LeadKanbanColumnDto.java:24-31`): `status: String` (unassigned da null), `statusLabel: String` (unassigned da null), `count: long`, `totalAmount: BigDecimal` (hech qachon null emas, 0)
- `LeadOperatorResponse` (`dto/response/LeadOperatorResponse.java:12-15`): `id: Long`, `fullName: String`
- `LeadCommentResponse` (`dto/response/LeadCommentResponse.java:14-22`): `id: Long`, `leadId: Long`, `authorId: Long`, `authorFullName: String`, `text: String`, `statusAtComment: String`, `createdAt: LocalDateTime`
- `LeadNoteResponse` (`dto/response/LeadNoteResponse.java:15-24`): `id: Long`, `uuid: UUID`, `leadId: Long`, `text: String`, `createdById: Long`, `createdByName: String`, `createdAt: LocalDateTime`, `updatedAt: LocalDateTime`
- `LeadStatusHistoryResponse` (`dto/response/LeadStatusHistoryResponse.java:14-26`): `id: Long`, `fromStatus: String`, `fromStatusLabel: String`, `toStatus: String`, `toStatusLabel: String`, `changedById: Long`, `changedByName: String`, `changedAt: LocalDateTime`, `note: String`, `daysInPreviousStatus: Long` (birinchi yozuvda null)
- `LeadTimelineResponse` (`dto/response/LeadTimelineResponse.java:22-43`): `items: List<LeadTimelineItemDto>`, `page: PageInfo`, `openTasks: List<TaskResponse>` (OPEN vazifalar, muddat o'sish tartibida)
  - `PageInfo` (`:36-42`): `pageNumber: int`, `pageSize: int`, `totalElements: long`, `totalPages: int`, `last: boolean` (PageResponse bilan bir xil nomlar, lekin `content` yo'q)
  - `LeadTimelineItemDto` (`dto/response/LeadTimelineItemDto.java:24-81`), **`@JsonInclude(NON_NULL)`** (`:28`) — turga aloqasiz maydonlar javobda yo'q:
    `type: String` (TASK_COMPLETED | TASK_CREATED | STATUS_CHANGED | NOTE | ASSIGNEE_CHANGED | LEAD_CREATED — `service/LeadTimelineService.java:49-54`, enum emas), `at: LocalDateTime`, `actorId: Long` (ASSIGNEE_CHANGED da null), `actorName: String`, `title: String`, `text: String`, `taskType: TaskType`, `taskTypeLabel: String`, `dueAt: LocalDateTime`, `fromValue: String`, `toValue: String`, `daysInPrevious: Long`, `refId: Long`, `editable: Boolean` (faqat NOTE: true = `lead_notes`, PUT/DELETE mumkin; false = eski `lead_comments`)
- `LeadConvertResponse` (`dto/response/LeadConvertResponse.java:15-21`): `id: Long` (o'quvchi id), `admissionNumber: String`, `paymentStatus: PaymentStatus` (PAID, PENDING, OVERDUE, PARTIAL, CANCELLED, TRIAL, SUSPENDED, ARCHIVED, FROZEN — `entity/enums/PaymentStatus.java:7-17`), `nextPaymentDate: LocalDate`, `leadId: Long`
- `TaskResponse` — Vazifalar bo'limida.

---

### Lid bosqichlari (voronka) — `controller/LeadStageController.java`

- Base path: `/api/lead-stages` (`controller/LeadStageController.java:24`)
- Class-level `@PreAuthorize`: YO'Q (`:23-26`). Yozish metodlarida `hasAnyRole('SUPER_ADMIN','ADMIN')`.
- SecurityConfig: `/api/lead-stages/**` → `authenticated()` (`config/SecurityConfig.java:146`) — bu qoida `DELETE /api/**` (`:196`) dan OLDIN, ya'ni DELETE ni faqat `@PreAuthorize` cheklaydi.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/lead-stages` | `getAll` (`LeadStageController.java:30`) | Har qanday autentifikatsiyadan o'tgan (shu jumladan TEACHER, ACCOUNTANT, STUDENT, PARENT) | — | `ApiResponse<List<LeadStageResponse>>` | yo'q | `sortOrder ASC, id ASC`, FAOL EMASLARI HAM qaytadi (`service/LeadStageService.java:72-73`) — frontend `isActive` bo'yicha filtrlashi kerak |
| POST | `/api/lead-stages` | `create` (`:35`) | SA, ADMIN (`:36`) | body `LeadStageRequest` (@Valid) | `ApiResponse<LeadStageResponse>`, **201**, "Bosqich yaratildi" | yo'q | `code` `nameUz` dan avtomatik; `kind` har doim OPEN; `isActive` default true (`service/LeadStageService.java:97`) |
| PUT | `/api/lead-stages/{id}` | `update` (`:44`) | SA, ADMIN (`:45`) | path `id: Long` (`\d+`); body `LeadStageRequest` | `ApiResponse<LeadStageResponse>`, "Bosqich yangilandi" | yo'q | `code`, `kind` o'zgarmaydi. To'liq PUT — nameUz/Ru/En/color har doim majburiy |
| DELETE | `/api/lead-stages/{id}` | `delete` (`:53`) | SA, ADMIN (`:54`) | path `id` | `ApiResponse<Void>`, 200, "Bosqich o'chirildi" | yo'q | CONVERTED/REJECTED (`kind.isFinal()`) → 400; bosqichda lid bo'lsa → 400 (`service/LeadStageService.java:149-156`). Hard delete |
| PATCH | `/api/lead-stages/reorder` | `reorder` (`:61`) | SA, ADMIN (`:62`) | body: `List<Long>` (JSON massiv, masalan `[3,1,2]`), validatsiyasiz | `ApiResponse<List<LeadStageResponse>>`, "Tartib yangilandi" | yo'q | Bo'sh ro'yxat → 400. Ro'yxatda yo'q bosqichlar tegilmaydi (`service/LeadStageService.java:164-186`); `sortOrder` 1 dan qayta yoziladi |

#### DTO tafsilotlari (LeadStage)

- `LeadStageRequest` (`dto/request/LeadStageRequest.java:19-44`):
  - `nameUz: String` `@NotBlank(message="{leadStage.nameUz.required}")` (`:21-22`)
  - `nameRu: String` `@NotBlank` (`:24-25`)
  - `nameEn: String` `@NotBlank` (`:27-28`)
  - `color: String` `@NotBlank` — ruxsat: `secondary | info | warning | success | danger` (`:30-32`; servisda tekshiriladi, boshqasi → 400 `service/LeadStageService.java:387-393`)
  - `requiresAmount: Boolean` — berilmasa mavjud qiymat (yaratishda false) (`:38`)
  - `sortOrder: Integer` — berilmasa oxiriga (`:41`)
  - `isActive: Boolean` (`:43`)
- `LeadStageResponse` (`dto/response/LeadStageResponse.java:16-34`): `id: Long`, `uuid: UUID`, `code: String`, `nameUz: String`, `nameRu: String`, `nameEn: String`, `color: String`, `sortOrder: Integer`, `kind: StageKind` (OPEN | CONVERTED | REJECTED — `entity/enums/StageKind.java:12-19`), `requiresAmount: Boolean`, `isActive: Boolean` (JSON: `isActive`), `deletable: boolean`, `createdAt: LocalDateTime`, `updatedAt: LocalDateTime`

---

### Lid importi (amoCRM) — `controller/LeadImportController.java`

- Base path: `/api/leads/import` (`controller/LeadImportController.java:27`)
- Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")` (`:29`)
- SecurityConfig: `/api/leads/**` → SA, ADMIN, SM (`config/SecurityConfig.java:134`) ∩ class-level → SA, ADMIN.
- Ikki bosqichli oqim: `preview` (fayl vaqtincha diskka saqlanadi, 1 soat TTL, `service/LeadImportService.java:104`) → `execute` (`importId` bilan). Kutayotgan importlar XOTIRADA (`:116-121`) — restart/ko'p instansiyada yo'qoladi.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | `/api/leads/import/preview` | `preview` (`LeadImportController.java:35`) | SA, ADMIN | **multipart/form-data**, maydon nomi **`file`** (`@RequestParam(name="file") MultipartFile` `:37`) | `ApiResponse<LeadImportPreviewResponse>`, "Fayl tahlil qilindi" | yo'q | Bazaga yozmaydi. Format: Excel (`WorkbookFactory` — .xlsx va .xls, `service/LeadImportService.java:389`); CSV QO'LLAB-QUVVATLANMAYDI. Birinchi varaq, 1-qator sarlavha; majburiy ustunlar: `Основной контакт`, `Этап сделки` (`:397-398`). Boshqa tanilgan ustunlar: `Рабочий телефон (контакт)`, `Мобильный телефон (контакт)`, `Ответственный`, `Дата создания` (dd.MM.yyyy HH:mm[:ss]), `Теги сделки`, `Online yoki Offline (контакт)`, 3 ta izoh ustuni, `Примечание 1..5` (`:74-99`). Bo'sh fayl → 400 |
| POST | `/api/leads/import/execute` | `execute` (`:42`) | SA, ADMIN | body JSON `LeadImportExecuteRequest` (@Valid) | `ApiResponse<LeadImportResult>`, "Import tugadi" | yo'q | Muddati o'tgan `importId` → 400 (`service/LeadImportService.java:190-193`). CONVERTED bosqichga xaritalash → 400 (`:665`). Har qator alohida tranzaksiyada |
| GET | `/api/leads/import/{importBatch}` | `countBatch` (`:50`) | SA, ADMIN | path `importBatch: String` | `ApiResponse<Map<String,Object>>` = `{importBatch: String, count: long}` (`:53-55`) | yo'q | O'chirishdan oldin ko'rsatish uchun |
| DELETE | `/api/leads/import/{importBatch}` | `deleteBatch` (`:62`) | **faqat SUPER_ADMIN** (`:63`) | path `importBatch: String`; query `confirm: Boolean` (majburiy mantiqan: `true` bo'lmasa 400 "Tasdiqlash kerak: ?confirm=true qo'shing" `:67-70`) | `ApiResponse<Map<String,Object>>` = `{importBatch: String, deleted: long}`, "Import partiyasi o'chirildi" | yo'q | Qaytarib bo'lmaydi. Topilmasa 400 (`service/LeadImportService.java:366-369`) |

Shablon yuklab olish endpointi lid importi uchun YO'Q (amoCRM eksport fayli kutiladi).

#### DTO tafsilotlari (LeadImport)

- `LeadImportExecuteRequest` (`dto/request/LeadImportExecuteRequest.java:10-35`):
  - `importId: String` `@NotBlank(message="{leadImport.importId.required}")` (`:12-13`)
  - `stageMapping: Map<String, String>` `@NotNull` — amoCRM bosqich nomi → `lead_stages.code`; qiymat null/yo'q → shu qatorlar import qilinmaydi (`:15-24`)
  - `operatorMapping: Map<String, Long>` — amoCRM mas'ul ismi → user id; null = biriktirilmagan (`:27`)
  - `skipDuplicates: Boolean` — bazada shu telefonli lid bo'lsa o'tkazish (`:30`)
  - `importTag: String` `@NotBlank` — partiya belgisi (`:33-34`)
- `LeadImportPreviewResponse` (`dto/response/LeadImportPreviewResponse.java:24-83`):
  `importId: String`, `fileName: String`, `expiresAt: LocalDateTime`, `totalRows: int`, `validPhones: int`, `invalidPhones: int`, `duplicatesInFile: int`, `duplicatesInDb: int`, `sourceStages: List<NameCount>`, `operators: List<NameCount>`, `blockedStages: List<String>` (CONVERTED kodlar — xaritalash ro'yxatidan chiqarish kerak), `sampleRows: List<SampleRow>` (birinchi 5)
  - `NameCount` (`:61-64`): `name: String`, `count: long`
  - `SampleRow` (`:70-82`): `row: int`, `fullName: String`, `phone: String`, `phoneValid: boolean`, `stage: String`, `operator: String`, `source: String`, `format: String`, `createdAt: LocalDateTime`, `notes: String`, `noteCount: int`
- `LeadImportResult` (`dto/response/LeadImportResult.java:21-54`):
  `importBatch: String`, `totalRows: int`, `created: int`, `skipped: int`, `failed: int`, `notesCreated: int`, `errors: List<RowError>` (qator SAQLANMAGAN), `warnings: List<RowError>` (saqlangan, lekin e'tibor kerak)
  - `RowError` (`:50-53`): `row: int` (Excel qator raqami, 1-asosli), `reason: String`

---

### Umumiy import (o'quvchi/o'qituvchi) — `controller/ImportController.java`

- Base path: `/api/import` (`controller/ImportController.java:28`)
- Class-level `@PreAuthorize`: YO'Q; har metodda `hasAnyRole('SUPER_ADMIN','ADMIN')` (`:35,47,59,71`)
- SecurityConfig: `/api/import/**` → SA, ADMIN (`config/SecurityConfig.java:153-154`)

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | `/api/import/students` | `importStudents` (`ImportController.java:34`) | SA, ADMIN | **multipart**, maydon **`file`** (`:37`) | `ApiResponse<ImportResult>`, "Import tugadi" | yo'q | Bo'sh fayl → 400 "Fayl bo'sh" (`:38-40`). Excel (.xlsx/.xls — `WorkbookFactory`, `service/ImportService.java:118`), faqat 1-varaq; **2-qator sarlavha**, ma'lumot 3-qatordan (`service/ImportService.java:123-132`); majburiy ustunlar Ism, Familiya, Telefon |
| POST | `/api/import/teachers` | `importTeachers` (`:46`) | SA, ADMIN | **multipart**, maydon **`file`** (`:49`) | `ApiResponse<ImportResult>`, "Import tugadi" | yo'q | Excel, 1-varaq; **1-qator sarlavha**, ma'lumot 2-qatordan (`service/ImportService.java:653`) — o'quvchilardan farqli |
| GET | `/api/import/template/students` | `downloadStudentTemplate` (`:58`) | SA, ADMIN | — | `byte[]` xlsx, Content-Type `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `attachment; filename="oquvchilar_shablon.xlsx"` (`:62-67`) | yo'q | ENVELOPE YO'Q. 3 varaq: "O'quvchilar" (1-qator izoh, 2-qator sarlavha), "NAMUNA", "QIYMATLAR" (`service/ImportService.java:518-594`). Sarlavhalar: Ism*, Familiya*, Telefon*, Ota-ona telefoni, Tug'ilgan sana, Jins, Qayerdan kelgan, Manzil, Qabul sanasi, Izoh, Guruh nomi, To'lov turi, Oylik to'lov, Dars narxi, To'lov boshlanish sanasi, Sinov darsi, Ota F.I.O, Ota telefoni, Ota manzili, Ona F.I.O, Ona telefoni, Ona manzili (`service/ImportService.java:67-74`) |
| GET | `/api/import/template/teachers` | `downloadTeacherTemplate` (`:70`) | SA, ADMIN | — | `byte[]`, Content-Type **`application/octet-stream`**, `attachment; filename=teachers_template.xlsx` (qo'shtirnoqsiz) (`:99-103`) | yo'q | Shablon controller ichida quriladi (`:73-97`). Sarlavhalar: Ism*, Familiya*, Telefon*, Email, Fan/Ixtisoslik, Maosh (UZS) |

#### DTO tafsilotlari (Import)

- `ImportResult` (`dto/response/ImportResult.java:15-34`): `totalRows: int`, `imported: int`, `validRows: int` (dry-run uchun; haqiqiy importda = imported), `skipped: int`, `errors: List<ImportIssue>`, `warnings: List<ImportIssue>`
  - `ImportIssue` (`:30-33`): `row: int`, `reason: String`
- Xatolar ro'yxati shakli ikkala importda bir xil g'oya (`{row, reason}`), lekin sinf nomlari farqli: `LeadImportResult.RowError` vs `ImportResult.ImportIssue`.

---

### Meta Lead Ads sozlamalari — `controller/MetaAdminController.java`

- Base path: `/api/meta` (`controller/MetaAdminController.java:35`)
- Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")` (`:37`)
- SecurityConfig: `/api/meta/**` → SA, ADMIN (`config/SecurityConfig.java:141-142`); `/api/meta/webhook` undan oldin permitAll (`:84`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | `/api/meta/forms/sync` | `sync` (`MetaAdminController.java:46`) | SA, ADMIN | — | `ApiResponse<MetaSyncResultDto>`, "Formalar sinxronlandi" | yo'q | Graph API chaqiradi; sozlamalar saqlanadi |
| GET | `/api/meta/forms` | `listForms` (`:52`) | SA, ADMIN | — | `ApiResponse<List<MetaFormDto>>` | yo'q | |
| GET | `/api/meta/forms/{formId}` | `getForm` (`:57`) | SA, ADMIN | path `formId: String` (`\d+`) | `ApiResponse<MetaFormDetailDto>` | yo'q | `formId` — Meta ID (matn), DB id emas |
| PUT | `/api/meta/forms/{formId}` | `updateForm` (`:63`) | SA, ADMIN | path `formId`; body `MetaFormSettingsRequest` (@Valid) | `ApiResponse<MetaFormDetailDto>`, "Sozlamalar saqlandi" | yo'q | Qismli: null maydon tegilmaydi, bo'sh matn = tozalash (PUT bo'lsa ham PATCH semantikasi) |
| PUT | `/api/meta/forms/{formId}/mapping` | `updateMapping` (`:72`) | SA, ADMIN | path `formId`; body `List<MetaQuestionMappingRequest>` (@Valid) | `ApiResponse<MetaFormDetailDto>`, "Mapping saqlandi" | yo'q | Tana: `[{"questionKey":"...","crmField":"PHONE"}]` |
| POST | `/api/meta/forms/{formId}/backfill` | `backfill` (`:89`) | SA, ADMIN | path `formId`; query `since: String` (yyyy-MM-dd, ixt.; noto'g'ri → 400 `:139-148`), `dryRun: boolean` default **true** | `ApiResponse<MetaBackfillResultDto>`; message dryRun ga qarab | yo'q | Birinchi marta har doim dryRun=true tavsiya (`:83-85`) |
| GET | `/api/meta/events` | `listEvents` (`:105`) | SA, ADMIN | query `status: String` (PENDING \| PROCESSING \| PROCESSED \| FAILED \| SKIPPED yoki bo'sh; `:104`), `page` int=0, `size` int=**50** | `ApiResponse<PageResponse<MetaWebhookEventDto>>` | PageResponse | size cheklovi servisda — **TAXMIN** (tekshirilmadi) |
| POST | `/api/meta/events/{id}/retry` | `retryEvent` (`:114`) | SA, ADMIN | path `id: Long` | `ApiResponse<MetaWebhookEventDto>`, "Event navbatga qaytarildi" | yo'q | |
| GET | `/api/meta/status` | `status` (`:122`) | SA, ADMIN | — | `ApiResponse<MetaStatusDto>` | yo'q | Sirlar qiymati qaytmaydi, faqat bool |
| POST | `/api/meta/subscribe` | `subscribe` (`:128`) | SA, ADMIN | — | `ApiResponse<JsonNode>` (Graph API xom javobi), "Sahifa leadgen ga obuna qilindi" | yo'q | Shakli Meta ga bog'liq — tiplanmagan |
| GET | `/api/meta/subscribe` | `subscriptions` (`:134`) | SA, ADMIN | — | `ApiResponse<JsonNode>` (Graph xom) | yo'q | Nomlash: GET `/subscribe` = obunalar ro'yxati |

#### DTO tafsilotlari (Meta)

- `MetaFormSettingsRequest` (`dto/request/MetaFormSettingsRequest.java:18-41`), null = tegilmaydi:
  - `leadType: MetaFormType` (STUDENT | HR | IGNORE | UNMAPPED — `entity/enums/MetaFormType.java:13-22`) (`:20`)
  - `defaultStageCode: String` `@Size(max=50)` — mavjud bosqich kodi, aks holda 400 (`:22-24`)
  - `defaultStudyFormat: String` `@Size(max=20)` — ONLINE | OFFLINE (matn, enum emas) (`:26-28`)
  - `defaultSource: String` `@Size(max=30)` — `MarketingSource` nomi (`:30-32`)
  - `autoCreateTask: Boolean` (`:34`)
  - `taskTimeQuestionKey: String` `@Size(max=255)` (`:36-38`)
  - `active: Boolean` (`:40`)
- `MetaQuestionMappingRequest` (`dto/request/MetaQuestionMappingRequest.java:14-21`): `questionKey: String` `@NotBlank` (XOM kalit, o'zgartirmasdan qaytarish kerak); `crmField: MetaCrmField` (null = olib tashlash)
  - enum `MetaCrmField` = FULL_NAME, FIRST_NAME, LAST_NAME, PHONE, PHONE_ALT, PREFERRED_CALL_TIME, PLANNED_START, PURPOSE, NOTE, IGNORE (`entity/enums/MetaCrmField.java:18-37`)
- `MetaSyncResultDto` (`dto/response/MetaSyncResultDto.java:15-25`): `total: int`, `created: int`, `updated: int`, `questionsCreated: int`, `questionsUpdated: int`, `warnings: List<String>`
- `MetaFormDto` (`dto/response/MetaFormDto.java:16-45`): `id: Long`, `formId: String`, `pageId: String`, `name: String`, `status: String` (Meta: ACTIVE | ARCHIVED), `locale: String`, `leadsCount: Integer`, `leadType: MetaFormType`, `defaultStageCode: String`, `defaultStudyFormat: String`, `defaultSource: String`, `autoCreateTask: Boolean`, `taskTimeQuestionKey: String`, `active: Boolean`, `syncedAt: Instant`, `questionsCount: long`, `unmappedQuestionsCount: long`
- `MetaFormDetailDto` (`dto/response/MetaFormDetailDto.java:15-20`): `form: MetaFormDto`, `questions: List<MetaQuestionDto>`
  - `MetaQuestionDto` (`dto/response/MetaQuestionDto.java:16-38`): `id: Long`, `questionKey: String`, `label: String`, `type: String`, `options: Map<String,String>` (optionKey → label), `crmField: MetaCrmField` (null = sozlanmagan), `sortOrder: Integer`
- `MetaBackfillResultDto` (`dto/response/MetaBackfillResultDto.java:15-49`): `formId: String`, `formName: String`, `dryRun: boolean`, `since: String`, `total: int`, `created: int`, `duplicates: int`, `skipped: int`, `errors: int`, `outOfRange: int`, `pages: int`, `errorMessages: List<String>` (birinchi 20)
- `MetaWebhookEventDto` (`dto/response/MetaWebhookEventDto.java:20-35`): `id: Long`, `leadgenId: String`, `formId: String`, `formName: String`, `pageId: String`, `adId: String`, `createdTimeMs: Long`, `status: String`, `attempts: Integer`, `errorMessage: String`, `leadId: Long`, `receivedAt: Instant`, `processedAt: Instant` (`rawPayload` ataylab yo'q)
- `MetaStatusDto` (`dto/response/MetaStatusDto.java:15-54`): `enabled: boolean`, `apiVersion: String`, `pageId: String`, `systemTokenConfigured: boolean`, `appSecretConfigured: boolean`, `verifyTokenConfigured: boolean`, `taskAssigneeConfigured: boolean`, `signatureVerified: boolean` (UI da qizil ogohlantirish kerak, `:31-40`), `pageTokenCached: boolean`, `lastSyncedAt: Instant`, `formsTotal: long`, `formsUnmapped: long`, `eventsPending: long`, `eventsFailed: long`, `eventsProcessed: long`, `eventsSkipped: long`

---

### Meta webhook (tashqi, frontend uchun EMAS) — `controller/MetaWebhookController.java`

- Base path: `/api/meta/webhook` (`controller/MetaWebhookController.java:45`)
- `@PreAuthorize` YO'Q. SecurityConfig: `permitAll` barcha metodlar uchun (`config/SecurityConfig.java:84`). Himoya — `X-Hub-Signature-256` HMAC-SHA256.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/meta/webhook` | `verify` (`MetaWebhookController.java:71`) | ANONIM (Meta) | query `hub.mode`, `hub.verify_token`, `hub.challenge` (hammasi String, ixt.) | `text/plain` — `hub.challenge` aynan; mos kelmasa **403** bo'sh tana | yo'q | ENVELOPE YO'Q |
| POST | `/api/meta/webhook` | `receive` (`:103`) | ANONIM (Meta, imzo bilan) | header `X-Hub-Signature-256` (ixt.); body xom `byte[]` (Meta leadgen JSON) | `text/plain`: `"EVENT_RECEIVED"` 200; imzo yomon → 403 `"invalid signature"` | yo'q | Saqlashda xato bo'lsa ham 200 (`:116-125`). Faqat `field == "leadgen"` o'zgarishlar event sifatida yoziladi |

---

### Marketing — `controller/MarketingController.java`

- Base path: `/api/marketing` (`controller/MarketingController.java:10`)
- `@PreAuthorize`: YO'Q. SecurityConfig da maxsus qoida YO'Q → `anyRequest().authenticated()` (`config/SecurityConfig.java:199`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/marketing/sources` | `getMarketingSources` (`MarketingController.java:15`) | Har qanday autentifikatsiyadan o'tgan | — | `ApiResponse<Map<String, Long>>` — `MarketingSource` nomi → o'quvchilar soni; faqat bazada uchraydigan manbalar (`repository/StudentRepository.java:56-57`) | yo'q | Controller to'g'ridan-to'g'ri repository ishlatadi (servissiz). `/api/analytics/marketing/sources` ning dublikati, lekin boshqa shakl va boshqa ruxsat |

`MarketingSource` enum: INSTAGRAM, TELEGRAM, YOUTUBE, FACEBOOK, TARGET, SELF_CALL, FORMER_STUDENT, REFERRAL, WALK_IN, OFFLINE, LEAD, OTHER (`entity/enums/MarketingSource.java:3-16`).

---

### Vazifalar — `controller/TaskController.java`

- Base path: `/api/tasks` (`controller/TaskController.java:28`)
- Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SALES_MANAGER')")` (`:30`)
- SecurityConfig: `/api/tasks/**` → SA, ADMIN, SM (`config/SecurityConfig.java:136-137`); DELETE uchun ham shu qoida (`:196` dan oldin).
- SM qamrovi servisda: `assignedTo` majburan o'zi (`service/TaskService.java:329-331`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | `/api/tasks` | `create` (`TaskController.java:35`) | SA, ADMIN, SM | body `TaskCreateRequest` (@Valid) | `ApiResponse<TaskResponse>`, **201**, "Vazifa yaratildi" | yo'q | `leadId` va `studentId` birga → 400 |
| GET | `/api/tasks` | `getAll` (`:42`) | SA, ADMIN, SM | query: `page` int=0, `size` int=20 (1..100), `assignedTo: Long` (SM uchun e'tiborsiz), `status: String` (OPEN\|DONE\|CANCELLED, noto'g'ri → 400), `type: String` (CALL\|MEETING\|MESSAGE\|OTHER, noto'g'ri → 400), `leadId: Long`, `studentId: Long`, `fromDate: String` yyyy-MM-dd, `toDate: String` yyyy-MM-dd (ikkalasi `dueAt` bo'yicha, toDate inklyuziv) — `service/TaskService.java:469-506` | `ApiResponse<PageResponse<TaskResponse>>` | PageResponse; sort qat'iy `dueAt ASC` (`service/TaskService.java:333-336`) | |
| GET | `/api/tasks/my` | `getMy` (`:58`) | SA, ADMIN, SM | query `filter: String` = `today \| overdue \| week \| all` (bo'sh = all; boshqasi → 400), `page`=0, `size`=20 (1..100) | `ApiResponse<PageResponse<TaskResponse>>` | PageResponse, `dueAt ASC` | Faqat joriy userning OPEN vazifalari. `today` muddati o'tganlarni o'z ichiga OLMAYDI; `week` o'tganlarni OLADI (`service/TaskService.java:352-397`) |
| GET | `/api/tasks/stats` | `getStats` (`:66`) | SA, ADMIN, SM | — | `ApiResponse<TaskStatsResponse>` | yo'q | SM uchun `byUser` bo'sh |
| GET | `/api/tasks/{id}` | `getById` (`:71`) | SA, ADMIN, SM (qamrov) | path `id: Long` | `ApiResponse<TaskResponse>` | yo'q | |
| PATCH | `/api/tasks/{id}` | `update` (`:76`) | SA, ADMIN, SM | body `TaskUpdateRequest` (@Valid) | `ApiResponse<TaskResponse>`, "Vazifa yangilandi" | yo'q | Qismli; mas'ul/muddat uchun alohida endpointlar |
| PATCH | `/api/tasks/{id}/complete` | `complete` (`:84`) | SA, ADMIN, SM | body `TaskCompleteRequest` (@Valid) | `ApiResponse<TaskCompleteResponse>`, "Vazifa bajarildi" | yo'q | Yopilgan vazifa → 400 (`service/TaskService.java:513`); `nextTask.dueAt` o'tmishda → 400 (`:237`) |
| PATCH | `/api/tasks/{id}/reassign` | `reassign` (`:92`) | SA, ADMIN, SM | body `TaskReassignRequest` (@Valid) | `ApiResponse<TaskResponse>`, "Mas'ul o'zgartirildi" | yo'q | Faol bo'lmagan user → 400 (`service/TaskService.java:557`) |
| PATCH | `/api/tasks/{id}/postpone` | `postpone` (`:100`) | SA, ADMIN, SM | body `TaskPostponeRequest` (@Valid) | `ApiResponse<TaskResponse>`, "Muddat keyinga surildi" | yo'q | Yangi muddat kelajakda bo'lishi shart (`service/TaskService.java:295`) |
| DELETE | `/api/tasks/{id}` | `delete` (`:108`) | SA, ADMIN; SM faqat o'zi yaratgan vazifani (`service/TaskService.java:310-316`) | path `id` | `ApiResponse<Void>`, 200, "Vazifa o'chirildi" | yo'q | Hard delete |

#### DTO tafsilotlari (Task)

- `TaskCreateRequest` (`dto/request/TaskCreateRequest.java:12-50`):
  - `title: String` `@NotBlank(message="{task.title.required}")` (`:14-15`)
  - `description: String` (`:17`)
  - `type: String` — CALL \| MEETING \| MESSAGE \| OTHER, default CALL (matn, enum emas) (`:19-20`)
  - `dueAt: LocalDateTime` `@NotNull(message="{task.dueAt.required}")` (`:22-23`)
  - `allDay: Boolean` — true → dueAt 23:59 ga (`:25-26`)
  - `assignedTo: Long` — default joriy user (`:29`)
  - `leadId: Long`, `studentId: Long` (`:36-38`)
  - `@JsonIgnore @AssertTrue(message="{task.target.single}") isTargetValid()` — ikkalasi birga bo'lmasin (`:45-49`)
- `TaskUpdateRequest` (`dto/request/TaskUpdateRequest.java:14-21`): `title: String`, `description: String`, `type: String`, `dueAt: LocalDateTime`, `allDay: Boolean` — hammasi ixtiyoriy, validatsiya yo'q
- `TaskCompleteRequest` (`dto/request/TaskCompleteRequest.java:11-51`):
  - `result: String` `@NotBlank(message="{task.result.required}")` (`:18-19`)
  - `nextTask: NextTask` `@Valid` ixtiyoriy (`:33-34`)
  - `NextTask` (`:37-50`): `type: String` (default CALL), `title: String` (bo'sh bo'lsa tur nomi), `dueAt: LocalDateTime` `@NotNull`, `allDay: Boolean`
- `TaskPostponeRequest` (`dto/request/TaskPostponeRequest.java:9-15`): `dueAt: LocalDateTime` `@NotNull`; `allDay: Boolean`
- `TaskReassignRequest` (`dto/request/TaskReassignRequest.java:7-11`): `userId: Long` `@NotNull(message="{task.assignedTo.required}")`
- `TaskResponse` (`dto/response/TaskResponse.java:18-45`): `id: Long`, `uuid: UUID`, `title: String`, `description: String`, `type: TaskType`, `typeLabel: String`, `status: TaskStatus`, `statusLabel: String`, `dueAt: LocalDateTime`, `allDay: Boolean`, `state: LeadTaskState` (yopilganda NONE), `assignedToId: Long`, `assignedToName: String`, `createdById: Long`, `createdByName: String`, `leadId: Long`, `leadName: String`, `studentId: Long`, `studentName: String`, `completedAt: LocalDateTime`, `completedById: Long`, `completedByName: String`, `result: String`, `createdAt: LocalDateTime`, `updatedAt: LocalDateTime`
  - `TaskType` = CALL ("Qo'ng'iroq"), MEETING ("Uchrashuv"), MESSAGE ("Xabar"), OTHER ("Boshqa") (`entity/enums/TaskType.java:12-17`) — JSON da faqat nomi
  - `TaskStatus` = OPEN, DONE, CANCELLED (`entity/enums/TaskStatus.java:13-17`)
- `TaskCompleteResponse` (`dto/response/TaskCompleteResponse.java:25-40`): `task: TaskResponse`, `leadId: Long`, `leadHasOpenTask: boolean` (false → "yangi vazifa qo'shing" oynasi), `nextTask: TaskResponse` (null bo'lishi mumkin)
- `TaskStatsResponse` (`dto/response/TaskStatsResponse.java:23-31`): `overdue: long`, `today: long`, `upcoming: long`, `noTask: long` (ochiq vazifasiz yopilmagan LIDLAR soni), `byUser: List<TaskUserStatsDto>`
  - `TaskUserStatsDto` (`dto/response/TaskUserStatsDto.java:12-18`): `userId: Long`, `name: String`, `overdue: long`, `today: long`, `totalOpen: long`

---

### Dashboard — `controller/DashboardController.java`

- Base path: `/api/dashboard` (`controller/DashboardController.java:14`)
- Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")` (`:16`)
- SecurityConfig: `/api/dashboard/**` → SA, ADMIN (`config/SecurityConfig.java:118-119`)

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/dashboard/stats` | `getStats` (`DashboardController.java:21`) | SA, ADMIN | — | `ApiResponse<DashboardStatsDto>` | yo'q | Oy boshi → bugun oralig'i (`service/DashboardService.java:32-57`) |

- `DashboardStatsDto` (`dto/response/DashboardStatsDto.java:12-25`), hammasi `long`, ma'nosi `service/DashboardService.java:40-54` dan:
  - `newOrders` — shu oy yaratilgan lidlar (`:40`)
  - `firstLessonStudents` — shu oy birinchi darsi bo'lgan o'quvchilar (StudentGroup) (`:41-42`)
  - `newStudents` — shu oy yaratilgan o'quvchilar (`:43`)
  - `activeStudents` — status ACTIVE (butun vaqt) (`:44`)
  - `leftFromOrder` — REJECTED bosqichidagi lidlar, **butun vaqt** (oy bilan cheklanmagan) (`:45`)
  - `leftFromActive` — shu oy guruhdan chiqqanlar (`:46-47`)
  - `newLeftStudents` — shu oy LEFT ga o'tgan (updatedAt bo'yicha) (`:48-49`)
  - `debtors` — `balance < 0` o'quvchilar (`:50`)
  - `groups` — ACTIVE guruhlar (`:51`)
  - `firstPaymentStudents` — shu oy birinchi to'lov (`:52`)
  - `frozen` — FROZEN o'quvchilar (`:53`)
  - `archived` — ARCHIVED o'quvchilar (`:54`)

---

### Analitika — `controller/AnalyticsController.java`

- Base path: `/api/analytics` (`controller/AnalyticsController.java:16`)
- Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")` (`:18`)
- SecurityConfig: `/api/analytics/**` → SA, ADMIN (`config/SecurityConfig.java:120-121`)

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/analytics/dashboard` | `getDashboard` (`AnalyticsController.java:23`) | SA, ADMIN | — | `ApiResponse<DashboardResponse>` | yo'q | `/api/dashboard/stats` dan boshqa dashboard |
| GET | `/api/analytics/revenue` | `getRevenue` (`:28`) | SA, ADMIN | query `period: String` (`daily \| monthly \| yearly`, boshqasi/bo'sh → monthly), `count: Integer` (default daily 30 / monthly 6 / yearly 3; max 365 / 36 / 10; min 1), `months: Integer` (ESKI alias: faqat period va count berilmasa → `monthly, months`) (`:33-38`; `service/AnalyticsService.java:164-275`) | `ApiResponse<Map<String,Object>>` = `{period: String, points: [{label: String, amount: BigDecimal}]}` | yo'q | `label`: daily `yyyy-MM-dd`, monthly `yyyy-MM`, yearly `yyyy` (`service/AnalyticsService.java:36-37,182,191,201`). Bo'sh bucket 0 bilan to'ldiriladi. Summa = PAID to'lovlar |
| GET | `/api/analytics/students` | `getStudentAnalytics` (`:41`) | SA, ADMIN | — | `ApiResponse<Map<String,Object>>` = `{total: long, active: long, frozen: long, finished: long, left: long, byMarketingSource: Map<String,Long>, debtors: int}` (`service/AnalyticsService.java:290-305`) | yo'q | |
| GET | `/api/analytics/marketing/sources` va `/api/analytics/marketing-sources` | `getMarketingSources` (`:46`) | SA, ADMIN | — | `ApiResponse<Map<String,Object>>` = `{bySource: Map<String,Long> (BARCHA MarketingSource qiymatlari, 0 bilan), total: long}` (`service/AnalyticsService.java:278-287`) | yo'q | Bitta metod, ikki yo'l (alias) |
| GET | `/api/analytics/staff/summary` | `getStaffSummary` (`:51`) | SA, ADMIN | query `period: String` default "monthly" (faqat `daily`/`monthly`, boshqasi → monthly — `service/TeacherKpiService.java:229-235`), `from: LocalDate` ISO (ixt.), `to: LocalDate` ISO (ixt.) | `ApiResponse<StaffSummaryResponse>` | yo'q | Default oraliq: oy boshi → bugun (`service/TeacherKpiService.java:237-250`). Top ro'yxatlar 5 tadan (`service/StaffAnalyticsService.java:181-217`) |
| GET | `/api/analytics/staff/{userId}/trend` | `getStaffTrend` (`:60`) | SA, ADMIN | path `userId: Long` (regex yo'q); query `period` default "monthly", `count: Integer` (oylar, default 6, max 12), `from`, `to` (ISO date) | `ApiResponse<StaffTrendResponse>` | yo'q | daily: `from`..`to` (default oy boshi) har kun; monthly: max 12 oy (`service/StaffAnalyticsService.java:128-168`). User topilmasa 404 |
| GET | `/api/analytics/staff` | `getStaffAnalytics` (`:71`) | SA, ADMIN | query `period` default "monthly", `from`, `to` (ISO date) | `ApiResponse<StaffAnalyticsResponse>` | yo'q | Faol SA/ADMIN/SM/TEACHER/ACCOUNTANT xodimlar; tartib: insufficientData oxirida, keyin overallScore↓, paymentsAmount↓, ism (`service/StaffAnalyticsService.java:106-113`) |

#### DTO tafsilotlari (Dashboard/Analytics — grafiklar uchun)

- `DashboardResponse` (`dto/response/DashboardResponse.java:13-35`), manba `service/AnalyticsService.java:40-148`:
  - `totalStudents: long`, `activeStudents: long`, `totalTeachers: long`, `activeTeachers: long`, `totalGroups: long`, `activeGroups: long`, `totalCourses: long`
  - `debtorCount: long` — `studentGroupRepository.findDebtors(today).size()` (`:56`) — `DashboardStatsDto.debtors` dan BOSHQA ta'rif
  - `monthlyRevenue: BigDecimal` (shu oy, `cashAmount` summasi), `monthlyExpenses: BigDecimal`, `netProfit: BigDecimal` (= revenue − expenses)
  - `attendanceRate: double` — shu oy, foiz, 1 kasr xonasi (masalan 87.5) (`:66-69`)
  - `totalParents: long`, `pendingLeaves: long`, `unpaidPayroll: long`
  - `studentsBySource: Map<String, Long>` — faqat bazada uchraydigan manbalar (`:71-73`)
  - `revenueChart: List<Map<String,Object>>` — oxirgi ~6 oy, element `{month: int (1-12), year: int, revenue: BigDecimal}` (`:75-83`); bo'sh oylar YO'Q (to'ldirilmaydi)
  - `studentGrowthChart: List<Map<String,Object>>` — element `{month: Number, year: Number, count: Long}` (`:85-93`); bo'sh oylar yo'q
  - `latestNotices: List<NoticeResponse>` — 5 ta (`:99-114`); faqat qisman to'ldiriladi: `id, uuid, title, content, noticeDate, publishedTo, noticeType, isPublished, publishedAt, expiresAt, expiryDate, isExpired(false), createdAt`; qolganlari (`targetRole, isActive, isRead, createdByName`) null
    - `NoticeResponse` to'liq maydonlari (`dto/response/NoticeResponse.java:13-32`): `id: Long`, `uuid: UUID`, `title: String`, `content: String`, `noticeDate: LocalDate`, `publishedTo: String`, `noticeType: String`, `targetRole: String`, `isActive: Boolean`, `isPublished: Boolean`, `publishedAt: LocalDateTime`, `expiresAt: LocalDateTime`, `expiryDate: LocalDate`, `isExpired: Boolean`, `isRead: Boolean`, `createdByName: String`, `createdAt: LocalDateTime`
  - `recentPayments: List<PaymentResponse>` — oxirgi 10 ta (`:116-124`); faqat `id, uuid, receiptNumber, formattedAmount ("800 000 so'm"), amount, paymentDate, paymentMethod, status` to'ldiriladi — `studentName`, `groupName` va boshqalar NULL
    - `PaymentResponse` to'liq maydonlari (`dto/response/PaymentResponse.java:16-44`): `id: Long`, `uuid: UUID`, `studentId: Long`, `studentName: String`, `groupId: Long`, `groupName: String`, `amount: BigDecimal`, `discountAmount: BigDecimal`, `bonusDiscount: BigDecimal`, `balanceUsed: BigDecimal`, `payable: BigDecimal`, `cashAmount: BigDecimal`, `receiptNumber: String`, `formattedAmount: String`, `paymentDate: LocalDate`, `paymentMethod: PaymentMethod`, `status: PaymentStatus`, `periodFrom: LocalDate`, `periodTo: LocalDate`, `description: String`, `createdAt: LocalDateTime`, `cashRegisterId: Long`, `cashRegisterName: String`
- `StaffAnalyticsResponse` (`dto/response/StaffAnalyticsResponse.java:16-21`): `period: String`, `from: LocalDate`, `to: LocalDate`, `staff: List<StaffMemberMetricsDto>`
  - `StaffMemberMetricsDto` (`dto/response/StaffMemberMetricsDto.java:14-41`): `userId: Long`, `fullName: String`, `role: String`, `photoUrl: String`, `leadsAssigned: Long`, `leadsConverted: Long`, `conversionRate: Double`, `paymentsReceived: Long`, `paymentsAmount: BigDecimal`, `studentsCreated: Long`; faqat TEACHER uchun: `groupCount: Integer`, `studentCount: Integer`, `attendanceRate: Double`, `paymentRate: Double`, `onTimePaymentRate: Double`, `retentionRate: Double`, `overallScore: Double`, `attendanceMarkedCount: Long`, `unlockRequestCount: Long`; `insufficientData: Boolean`, `activityLabel: String` (masalan "Faoliyat yo'q")
- `StaffSummaryResponse` (`dto/response/StaffSummaryResponse.java:17-56`): `period: String`, `from: LocalDate`, `to: LocalDate`, `totalStaff: int`, `byRole: Map<String, Long>`, `topByConversion: List<TopConversionItem>`, `topByPayments: List<TopPaymentItem>`, `topByKpi: List<TopKpiItem>`
  - `TopConversionItem` (`:31-35`): `userId: Long`, `fullName: String`, `conversionRate: Double`
  - `TopPaymentItem` (`:41-45`): `userId: Long`, `fullName: String`, `paymentsAmount: BigDecimal`
  - `TopKpiItem` (`:51-55`): `userId: Long`, `fullName: String`, `overallScore: Double` (faqat TEACHER)
- `StaffTrendResponse` (`dto/response/StaffTrendResponse.java:15-31`): `userId: Long`, `period: String`, `points: List<StaffTrendPointDto>`
  - `StaffTrendPointDto` (`:24-30`): `label: String` (daily `yyyy-MM-dd`, monthly `yyyy-MM` — `service/StaffAnalyticsService.java:49-50`), `leadsConverted: Long`, `paymentsAmount: BigDecimal`, `overallScore: Double`, `insufficientData: Boolean`

---

### Audit jurnali — `controller/AuditLogController.java`

- Base path: `/api/audit-logs` (`controller/AuditLogController.java:24`)
- Class-level `@PreAuthorize`: YO'Q; metod darajasida.
- SecurityConfig: maxsus qoida YO'Q → `anyRequest().authenticated()` (`config/SecurityConfig.java:199`); cheklov faqat `@PreAuthorize` da.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/audit-logs` | `search` (`AuditLogController.java:30`) | faqat SUPER_ADMIN (`:31`) | query: `from: LocalDate` ISO, `to: LocalDate` ISO (inklyuziv, `23:59:59.999...`), `userId: Long`, `action: String` (aniq tenglik), `entityType: String` (aniq), `entityId: Long`, `q: String` (summary va boshqa matnlarda LIKE, registrsiz), `page` int=0, `size` int=**50** (1..200) — `service/AuditLogService.java:38-110` | `ApiResponse<PageResponse<AuditLogResponse>>` | PageResponse, `createdAt DESC` | |
| GET | `/api/audit-logs/entity/{entityType}/{entityId}` | `getEntityHistory` (`:47`) | SA, ADMIN (`:48`) | path `entityType: String` (masalan `Student`, `Lead`), `entityId: Long` | `ApiResponse<List<AuditLogResponse>>` | yo'q (to'liq ro'yxat) | `createdAt DESC` |
| GET | `/api/audit-logs/filters` | `getFilters` (`:56`) | faqat SUPER_ADMIN (`:57`) | — | `ApiResponse<Map<String,Object>>` = `{actions: List<String>, entityTypes: List<String>}` (bazadagi distinct qiymatlar, `service/AuditLogService.java:71-76`) | yo'q | Dropdownlar uchun |

- `AuditLogResponse` (`dto/response/AuditLogResponse.java:11-25`): `id: Long`, `createdAt: LocalDateTime`, `userId: Long`, `username: String`, `userRole: String`, `action: String`, `entityType: String`, `entityId: Long`, `entityLabel: String`, `summary: String`, `details: Object` (parse qilingan JSON, odatda `{"changes":[...]}`, null bo'lishi mumkin — tiplanmagan), `ipAddress: String`
- **Saqlash muddati (Q14, 2026-10-02):** oddiy yozuvlar **180** kun (`app.audit.retention-days`), moliyaviy — **365** kun (`app.audit.financial-retention-days`). Moliyaviy: amal `PAYMENT`, `PAYMENT_CANCEL`, `REFUND` yoki obyekt `Payment`, `Payroll`, `CashRegister`, `CashTransaction`, `Balance`, `BonusPenalty`, `SalaryRule`, `Expense`, `Income`, `ExamRegistration`. Har kecha 03:30 (Asia/Tashkent).

---

### Admin ta'mirlash (bir martalik) — `controller/AdminRepairController.java`

- Base path: `/api/admin/repair` (`controller/AdminRepairController.java:23`)
- Class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")` (`:25`)
- SecurityConfig: `/api/admin/**` → SUPER_ADMIN (`config/SecurityConfig.java:157-158`)
- Hammasi `ApiResponse<Map<...>>` — tiplanmagan.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | `/api/admin/repair/link-teacher-users` | `linkTeacherUsers` → `TeacherProfileSyncService.repairTeacherLinks` | SUPER_ADMIN | — | `ApiResponse<Map<String,Object>>` = `{linkedCount, createdCount, skippedCount, remainingUnlinked, usersWithoutProfile, items[{type: TEACHER|USER, teacherId, userId, username, action: LINKED|CREATED|SKIPPED, reason}]}` | yo'q | **02.10.2026 (payroll-v2 §12):** 1) egasiz profillarni TEACHER userga bog'laydi; 2) profili yo'q TEACHER userlarga profil yaratadi (sync-from-users qoidasi, har user alohida tranzaksiyada). Avval 2-qadam yo'q edi — `remainingUnlinked = 0` bo'lsa ham user'larda profil yo'q bo'lishi mumkin edi |
| POST | `/api/admin/repair/link-timetable-rooms` | `linkTimetableRooms` (`:42`) | SUPER_ADMIN | — | `ApiResponse<Map<String,Integer>>` = `{updated, skipped}` (`service/GroupService.java:853`) | yo'q | |
| POST | `/api/admin/repair/recalculate-payment-dates` | `recalculatePaymentDates` (`:49`) | SUPER_ADMIN | — | `ApiResponse<Map<String,Object>>` = `{studentsProcessed, groupsRecalculated, aggregatesUpdated, paymentsFixed, inconsistentActiveWithLeaveDate, inconsistentInactiveWithoutLeaveDate, details: [{studentId, name, groups, paymentStatus, nextPaymentDate, monthlyFee, changed}]}` (`service/PaymentScheduleService.java:853+`) | yo'q | dryRun YO'Q — darhol yozadi |
| POST | `/api/admin/repair/fix-payment-periods` | `fixPaymentPeriods` (`:56`) | SUPER_ADMIN | — | `ApiResponse<Map<String,Integer>>` = `{paymentsFixed, groupsRecalculated}` (`service/PaymentScheduleService.java:846-849`) | yo'q | dryRun yo'q |
| POST | `/api/admin/repair/migrate-lead-statuses` | `migrateLeadStatuses` (`:63`) | SUPER_ADMIN | — | `ApiResponse<Map<String,Object>>` = `{migrated: {DAY_1_WORKED: int, ..., ENROLLED_OFFLINE: int}, totalUpdated: int}` (`service/LeadService.java:846-862`) | yo'q | Eski status nomlari → yangi |
| GET | `/api/admin/repair/verify-balances` | `verifyBalances` (`:70`) | SUPER_ADMIN | — | `ApiResponse<Map<String,Object>>` = `{checked, mismatchCount, totalDiff, mismatched: [{studentGroupId, studentId, studentName, groupName, paymentType, stored, expected, diff, components: {cashIn, periodCost, lessonCost, carriedLedger, ledgerSum}, missingPeriodCharges, wrongCredits, strayLessonCharges, legacyFreezeEntries, hasLegacyFreezeTransfer, unlinkedPayments}]}` (`service/BalanceTransactionService.java:141+`) | yo'q | Faqat o'qish |
| POST | `/api/admin/repair/rebuild-monthly-ledger` | `rebuildMonthlyLedger` (`:81`) | SUPER_ADMIN | query `dryRun: boolean` default **true** | `ApiResponse<Map<String,Object>>` = `{dryRun, checked, withIssues, applied, skipped, totalDiff, failedCount, failed: [{studentGroupId, error}], details}` (`service/MonthlyLedgerRepairService.java:46+`) | yo'q | |
| POST | `/api/admin/repair/note-student-attribution` | `noteStudentAttribution` (`:94`) | SUPER_ADMIN | — | `ApiResponse<Map<String,Object>>` = `{message: String, action: "none"}` | yo'q | Hech narsa qilmaydi — statik matn (`:95-101`) |

---


## 4. Moliya, oylik, bonus/jarima, fayllar, chat (REST)

> Belgi: `[PUL]` — BigDecimal pul maydoni.

### To'lovlar — `controller/PaymentController.java`

- Base path: `/api/payments` (`PaymentController.java:22`). Class-level `@PreAuthorize`: **yo'q**; metod darajasida.
- SecurityConfig: `/api/payments/**` → `SUPER_ADMIN, ADMIN, SALES_MANAGER, ACCOUNTANT` (`config/SecurityConfig.java:130-131`). Bu qoida `DELETE /api/**` (`:196`) dan oldin turadi.
- Jadval:

| METHOD | path | Controller#metod (fayl:qator) | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/payments` | `getAll` (`PaymentController.java:33-55`) | SA, A, ACC | query: `page:int` (def 0), `size:int` (def **50**), `studentId:Long?`, `groupId:Long?`, `status:String?` (PaymentStatus nomi, case-insensitive; noto'g'ri → filtr e'tiborsiz, `service/PaymentService.java:552-561`), `from:String?`, `to:String?` (ISO `yyyy-MM-dd`, `paymentDate` bo'yicha, inklyuziv; noto'g'ri format → `DateTimeParseException` → **500**, `PaymentService.java:548-550`) | `ApiResponse<Page<PaymentResponse>>` + `meta: PaymentSummary` (hisoblanmasa `meta` yo'q, `PaymentController.java:48-54`) | Spring `Page`; sort qat'iy `createdAt DESC` (`PaymentService.java:455`), sort parametri yo'q | `meta` = filtrga mos BARCHA qatorlar aggregati, sahifaniki emas |
| GET | `/api/payments/stats` | `getStats` (`:57-61`) | SA, A, ACC | — | `ApiResponse<Map<String,Object>>`: `{totalCollected, totalPending, thisMonth, lastMonth}` — hammasi BigDecimal (`PaymentService.java:591-596`) | yo'q | `totalCollected`=SUM(cash_amount) PAID; `totalPending`=SUM(amount) PENDING; `thisMonth/lastMonth` = naqd PAID shu/o'tgan oy |
| GET | `/api/payments/archived` | `getArchivedSuspended` (`:63-67`) | SA, A, ACC | — | `ApiResponse<List<SuspendedStudentResponse>>` | yo'q | 3+ kun oldin SUSPENDED bo'lgan enrollmentlar (`PaymentService.java:427-441`) |
| GET | `/api/payments/history` | `getHistory` (`:69-73`) | SA, A, ACC | — | `ApiResponse<List<PaymentHistoryResponse>>` | **yo'q — butun jadval** (`PaymentService.java:575-579`) | Katta hajm xavfi |
| POST | `/api/payments/preview` | `previewPayment` (`:75-81`) | SA, A, ACC | body: `PaymentPreviewRequest` (`@Valid`) | `ApiResponse<PaymentPreviewResponse>` (200) | yo'q | Hech narsa saqlanmaydi; batafsil pastda |
| POST | `/api/payments` | `createPayment` (`:83-88`) | SA, A, ACC | body: `PaymentRequest` (`@Valid`) | `ApiResponse<PaymentResponse>`, **201**, message `"Payment recorded"` | yo'q | Ledger, Income, kassa, bonus/jarima qo'llash, jadval qayta hisobi (`PaymentService.java:78-194`) |
| GET | `/api/payments/student/{studentId}` | `getStudentPayments` (`:90-93`) | **SA, A, SALES_MANAGER, ACC** (faqat URL qoidasi — `@PreAuthorize` YO'Q) | path: `studentId:Long` | `ApiResponse<List<PaymentResponse>>` | yo'q; `paymentDate DESC` (`PaymentService.java:444-447`) | Mavjud bo'lmagan student → bo'sh ro'yxat |
| GET | `/api/payments/expected` | `getExpected` (`:95-102`) | SA, A, ACC | query: `from:LocalDate?`, `to:LocalDate?` (ISO DATE, `@DateTimeFormat` → noto'g'ri → 400). Default: `from = bugun+1`, `to = joriy oy oxiri` (`config/PaymentScheduleConfig.java:18-25`) | `ApiResponse<ExpectedPaymentsResponse>` | yo'q | Faqat `nextPaymentDate > bugun` (`service/PaymentScheduleService.java:666`) |
| GET | `/api/payments/debtors` | `getDebtors` (`:104-108`) | SA, A, ACC | — | `ApiResponse<DebtorsListResponse>` | yo'q | |
| GET | `/api/payments/calculate-debt` | `calculateDebt` (`:110-117`) | SA, A, ACC | query: `studentId:Long` **required**, `groupId:Long` **required** | `ApiResponse<Map<String,Object>>` — pastda | yo'q | Eski formula (kun/30 × narx), ledger bilan mos emas |
| GET | `/api/payments/debtors/summary` | `getDebtorsSummary` (`:119-124`) | SA, A, ACC | — | `ApiResponse<Map<String,Object>>`: `{totalDebtors:long, overdue7Plus:long, totalDebt:BigDecimal}` (`service/PaymentScheduleService.java:813-820`) | yo'q | |

Eslatma: To'lovni **tahrirlash / o'chirish / bekor qilish endpointi YO'Q** (controllerda faqat GET/POST).

#### DTO tafsilotlari

**`PaymentPreviewRequest`** (`dto/request/PaymentPreviewRequest.java:9-19`)
- `studentId: Long` — `@NotNull(message="{paymentPreview.studentId.required}")` (`:11-12`)
- `groupId: Long` — ixtiyoriy; **hisobga ta'sir qilmaydi** (`service/PaymentService.java:332-333`)
- `amount: BigDecimal` [PUL] — to'liq (gross) summa; null/manfiy → 0 (`PaymentService.java:301`)
- `discountAmount: BigDecimal` [PUL] — null/manfiy → 0; `discount > gross` → `IllegalArgumentException` → **400** (`payment.discount.tooLarge`, `PaymentService.java:303-305`)
- `useBalance: Boolean` — `true` bo'lsagina balans ishlatiladi

**`PaymentPreviewResponse`** (`dto/response/PaymentPreviewResponse.java:15-29`) — barcha maydonlar BigDecimal [PUL]:
- `gross` — so'rovdagi summa (0 ga clamp)
- `discount`
- `payable` = `gross - discount`
- `balanceUsed` = `useBalance ? min(max(studentBalance,0), payable) : 0`
- `cashAmount` = `payable - balanceUsed` (kassaga tushadigan real pul)
- `studentBalance` — o'quvchining joriy `Student.balance` (null → 0)
- `balanceAfter` = `studentBalance - balanceUsed`
- Formula manbai: `PaymentService.calculate` (`PaymentService.java:298-320`); preview va create **aynan bir xil** formulani ishlatadi (`:100-101`, `:334-335`). Student topilmasa → 404 (`:328-330`). Bonus/jarima (`applyBonuses`) preview da hisobga OLINMAYDI — create da keyin qo'llanadi (`:137-150`) → preview va haqiqiy yozuvdagi `discountAmount`/`bonusDiscount` farq qilishi mumkin.

**`PaymentRequest`** (`dto/request/PaymentRequest.java:11-44`)
- `studentId: Long` — `@NotNull` (`:13-14`)
- `groupId: Long` — ixtiyoriy; berilsa faol enrollment qidiriladi, topilmasa studentning birinchi faol enrollmenti (`PaymentService.java:348-357`)
- `amount: BigDecimal` [PUL] — `@NotNull`, `@DecimalMin("0.0")` (0 ruxsat) (`:16-18`); gross
- `paymentMethod: PaymentMethod` — default `CASH` (`:19`); JSON da aniq `null` yuborilsa null saqlanadi
- `paymentDate: LocalDate` — null → bugun (`PaymentService.java:86`)
- `periodFrom: LocalDate`, `periodTo: LocalDate` — null bo'lsa avtomatik (`PaymentService.java:382-424`)
- `description: String`, `notes: String`
- `discountAmount: BigDecimal` [PUL]
- `cashRegisterId: Long` — berilsa va `cashAmount > 0` bo'lsa kassaga kirim yoziladi (`PaymentService.java:163-177`)
- `paymentMethodForCash: String` — PaymentMethod nomi (aliaslar ok); noto'g'ri → 400 `payment.methodForCash.invalid` (`PaymentService.java:678-690`)
- `cashPart: BigDecimal` [PUL], `cardPart: BigDecimal` [PUL] — faqat `CASH_AND_CARD`; `cashPart + cardPart == cashAmount` shart, aks holda 400 (`service/CashRegisterService.java:609-629`)
- `useBalance: Boolean`
- `balanceAmount: BigDecimal` [PUL] — **ishlatilmaydi**, faqat ma'lumot (`:37-41`)
- `applyBonuses: Boolean` — null → `true` (`PaymentService.java:692-694`)

**`PaymentResponse`** (`dto/response/PaymentResponse.java:16-44`)
- `id: Long`, `uuid: UUID`, `studentId: Long`, `studentName: String`, `groupId: Long?`, `groupName: String?`
- [PUL] `amount: BigDecimal` (gross), `discountAmount` (null→0; bonus chegirmasi ham QO'SHILGAN, `PaymentService.java:142-144`), `bonusDiscount` (null→0), `balanceUsed` (null→0), `payable` (eski yozuvda `amount+balanceUsed`, `:603-608`), `cashAmount` (null → amount)
- `receiptNumber: String` (`RCP-00001` shakli, `:88-89`), `formattedAmount: String` (`"800 000 so'm"`, `:721-728`)
- `paymentDate: LocalDate`, `paymentMethod: PaymentMethod`, `status: PaymentStatus` (create da doim `PAID`, `:125`)
- `periodFrom: LocalDate`, `periodTo: LocalDate` (entity da `periodStart/periodEnd`, getter alias `entity/Payment.java:111-126`)
- `description: String`, `createdAt: LocalDateTime`, `cashRegisterId: Long?`, `cashRegisterName: String?`
- `notes` javobda YO'Q.

**`PaymentSummary`** (`meta`) (`dto/response/PaymentSummary.java:18-31`): `totalAmount: BigDecimal` [PUL] (SUM gross), `totalCashAmount: BigDecimal` [PUL] (SUM COALESCE(cash_amount, amount)), `totalCount: long`.

**`SuspendedStudentResponse`** (`dto/response/SuspendedStudentResponse.java:11-19`): `studentId: Long`, `studentName: String`, `groupId: Long`, `groupName: String`, `suspendedAt: LocalDateTime`, `suspensionReason: String`, `daysSinceSuspended: Long?`.

**`PaymentHistoryResponse`** (`dto/response/PaymentHistoryResponse.java:14-25`): `receiptNumber`, `studentName`, `groupName`, `amount: BigDecimal` [PUL], `paymentDate: LocalDate`, `paymentMethod: PaymentMethod`, `periodFrom/periodTo: LocalDate`, `status: PaymentStatus`, `description`. (`id` YO'Q.)

**`ExpectedPaymentsResponse`** (`dto/response/ExpectedPaymentsResponse.java:17-50`): `totalStudents: long`, `totalAmount: BigDecimal` [PUL], `days: List<DayBucket>`;
`DayBucket {date: LocalDate, studentCount: int, dayTotal: BigDecimal [PUL], students: List<ExpectedStudent>}`;
`ExpectedStudent {studentId: Long, fullName, phone, groupName, amount: BigDecimal [PUL], paymentStatus: String, daysUntil: long}`.

**`DebtorsListResponse`** (`dto/response/DebtorsListResponse.java:17-40`): `totalDebtors: long`, `overdue7Plus: long`, `totalDebt: BigDecimal` [PUL], `students: List<DebtorStudent>`;
`DebtorStudent {studentId: Long, fullName, phone, groupName, nextPaymentDate: LocalDate, daysOverdue: long, amount: BigDecimal [PUL], monthsUnpaid: long, totalDebt: BigDecimal [PUL]}`.

**`calculate-debt` javobi** (`PaymentService.java:730-787`, `LinkedHashMap`):
- enrollment topilmasa: `{debt: 0 (int), message: "Guruh topilmadi"}` (HTTP 200)
- aks holda: `{studentId: Long, groupId: Long, joinDate: LocalDate, daysSinceJoin: long, monthlyPrice: BigDecimal, totalShouldPay: long, totalPaid: BigDecimal, debt: long, message: String}`.

**Enumlar:** `PaymentStatus` = `PAID, PENDING, OVERDUE, PARTIAL, CANCELLED, TRIAL, SUSPENDED, ARCHIVED, FROZEN` (`entity/enums/PaymentStatus.java:7-17`).

---

### Moliya hisoboti — `controller/FinanceController.java`

- Base path: `/api/finance` (`FinanceController.java:15`). Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")` (`:17`).
- SecurityConfig: `/api/finance/**` → SA, A, ACC (`SecurityConfig.java:122-123`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/finance/expenses` | `getExpenses` (`FinanceController.java:21-30`) | SA, A, ACC | query: `from:LocalDate?`, `to:LocalDate?` (ISO, `@DateTimeFormat`), `category:String?` (ExpenseCategory, case-insens.; noto'g'ri → e'tiborsiz), `page:int` (def 0), `size:int` (def 20) | `ApiResponse<Page<ExpenseResponse>>` | Spring `Page`; sort `expenseDate DESC` (`service/FinanceService.java:53`) | `GET /api/expenses` bilan **aynan dublikat** |
| POST | `/api/finance/expenses` | `createExpense` (`:32-36`) | SA, A, ACC | body: `ExpenseRequest` (`@Valid`) | `ApiResponse<ExpenseResponse>`, **201**, `"Expense recorded"` | yo'q | `POST /api/expenses` dublikati |
| GET | `/api/finance/report` | `getReport` (`:38-43`) | SA, A, ACC | query: `from:LocalDate?` (def: oy boshi), `to:LocalDate?` (def: bugun) (`FinanceService.java:142-143`) | `ApiResponse<FinanceReportResponse>` `{totalIncome, totalExpenses, payrollPaid, payrollByRole, netProfit, incomeByCategory, expenseByCategory, period}` | yo'q | **Payroll v2:** `payrollPaid` = Σ PAID `netSalary` (`paidAt` davrda, CANCELLED kirmaydi), `payrollByRole`; `netProfit = income − expenses − payrollPaid` ([`payroll-v2-api.md` §6](../design/payroll-v2-api.md)) |

**`FinanceReportResponse`** (`dto/response/FinanceReportResponse.java:6-13`): `totalIncome: BigDecimal` [PUL] (SUM payment cash_amount, `FinanceService.java:145-147`), `totalExpenses: BigDecimal` [PUL], `netProfit: BigDecimal` [PUL], `incomeByCategory: Map<String,BigDecimal>` [PUL] (kalit — `IncomeCategory` nomi: `STUDENT_PAYMENT`, `OTHER_INCOME`), `expenseByCategory: Map<String,BigDecimal>` [PUL] (kalit — `ExpenseCategory` nomi), `period: String` (`"2026-09-01 to 2026-09-29"` shakli, `:169`).
Diqqat: `totalIncome` Payment jadvalidan, `incomeByCategory` esa Income jadvalidan olinadi — yig'indilar mos kelmasligi mumkin (**TAXMIN**).

(`ExpenseRequest`/`ExpenseResponse` — keyingi bo'limda.)

---

### Xarajatlar — `controller/ExpenseController.java`

- Base path: `/api/expenses` (`ExpenseController.java:19`). Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")` (`:21`).
- SecurityConfig: `/api/expenses`, `/api/expenses/**` → SA, A, ACC (`SecurityConfig.java:124-125`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/expenses` | `getExpenses` (`ExpenseController.java:26-35`) | SA, A, ACC | query: `from:LocalDate?`, `to:LocalDate?`, `category:String?`, `page:int` (0), `size:int` (20) | `ApiResponse<Page<ExpenseResponse>>` | Spring `Page`, `expenseDate DESC` | Frontend hozir shuni ishlatadi |
| POST | `/api/expenses` | `createExpense` (`:37-42`) | SA, A, ACC | body: `ExpenseRequest` (`@Valid`) | `ApiResponse<ExpenseResponse>`, **201**, `"Expense recorded"` | yo'q | `cashRegisterId` berilsa kassadan chiqim (`FinanceService.java:106-125`) |

Xarajatni tahrirlash/o'chirish endpointi YO'Q.

**`ExpenseRequest`** (`dto/request/ExpenseRequest.java:8-25`)
- `category: ExpenseCategory` — `@NotNull` (`:9`)
- `title: String` — `@NotBlank` (`:10`)
- `amount: BigDecimal` [PUL] — `@NotNull`, `@DecimalMin("0.01")` (`:11`)
- `expenseDate: LocalDate` — `@NotNull` (`:12`)
- `teacherId: Long` — ixtiyoriy; topilmasa 404 (`FinanceService.java:98-102`)
- `description: String`, `notes: String`
- `cashRegisterId: Long`
- `paymentMethodForCash: String` — **noto'g'ri qiymat jimgina `CASH` bo'ladi** (`FinanceService.java:136-138`) (Payment dagi 400 dan farqli)
- `cashPart: BigDecimal` [PUL], `cardPart: BigDecimal` [PUL] — CASH_AND_CARD uchun, yig'indi = amount

**`ExpenseResponse`** (`dto/response/ExpenseResponse.java:9-21`): `id: Long`, `uuid: UUID`, `category: ExpenseCategory`, `title`, `amount: BigDecimal` [PUL], `expenseDate: LocalDate`, `teacherName: String?` (`teacherId` YO'Q), `description`, `createdAt: LocalDateTime`, `cashRegisterId: Long?`, `cashRegisterName: String?`. (`notes` javobda yo'q.)

**Enum** `ExpenseCategory` = `SALARY, RENT, MARKETING, UTILITIES, EQUIPMENT, OTHER` (`entity/enums/ExpenseCategory.java:2`).

---

### Kassalar — `controller/CashRegisterController.java`

- Base path: `/api/cash-registers` (`CashRegisterController.java:29`). Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")` (`:31`).
- SecurityConfig: `/api/cash-registers/**` → SA, A, ACC (`SecurityConfig.java:132-133`) — `DELETE /api/**` (`:196`) dan OLDIN, shuning uchun DELETE ham ACC ga ochiq.
- Hech bir request DTO da `@Valid`/validatsiya annotatsiyasi YO'Q — tekshiruv servisda.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/cash-registers` | `getAll` (`:36-40`) | SA, A, ACC | query: `status:String?` (`ACTIVE`/`ARCHIVED`; noto'g'ri → **400** `"Noto'g'ri kassa holati"`, `service/CashRegisterService.java:733-739`) | `ApiResponse<List<CashRegisterDto>>` | yo'q | |
| GET | `/api/cash-registers/{id}` | `getById` (`:42-45`) | SA, A, ACC | path `id:Long` | `ApiResponse<CashRegisterDto>` | yo'q | 404 agar yo'q |
| POST | `/api/cash-registers` | `create` (`:47-52`) | SA, A, ACC | body: `CashRegisterCreateDto` (validatsiyasiz) | `ApiResponse<CashRegisterDto>`, **201**, `"Kassa yaratildi"` | yo'q | `name` null bo'lishi mumkin (servis tekshirmaydi, `CashRegisterService.java:86-104`) |
| PUT | `/api/cash-registers/{id}` | `update` (`:54-60`) | SA, A, ACC | path `id`; body: `CashRegisterCreateDto` (null maydonlar o'zgarmaydi — aslida PATCH semantikasi, `:107-124`) | `ApiResponse<CashRegisterDto>`, `"Kassa yangilandi"` | yo'q | `moderatorId` ni null qilib bo'lmaydi |
| DELETE | `/api/cash-registers/{id}` | `delete` (`:62-66`) | **SA, A, ACC** | path `id` | `ApiResponse<Void>` (`data:null`), `message` = `"O'chirildi"` yoki `"Tranzaksiyalari bor, arxivlandi"` (`CashRegisterService.java:127-137`) | yo'q | Soft/hard — natija message matnida |
| PATCH | `/api/cash-registers/{id}/status` | `updateStatus` (`:68-78`) | SA, A, ACC | body: `CashRegisterStatusUpdateDto {status}`; bo'sh → **400 `ApiResponse.error`** (controllerning o'zida, `:72-75`) | `ApiResponse<CashRegisterDto>`, `"Kassa holati yangilandi"` | yo'q | Xato shakli boshqa endpointlardan farq qiladi |
| GET | `/api/cash-registers/{id}/balance` | `getBalance` (`:80-83`) | SA, A, ACC | path `id` | `ApiResponse<CashBalanceDto>` | yo'q | |
| GET | `/api/cash-registers/{id}/transactions` | `getTransactions` (`:85-103`) | SA, A, ACC | query: `from:String?`, `to:String?` (ISO; noto'g'ri → `DateTimeParseException` → **500**, `:126-131`), `studentId:Long?`, `teacherId:Long?`, `type:String?` (`INCOME/EXPENSE/TRANSFER`, noto'g'ri → e'tiborsiz), `paymentMethod:String?` (aliaslar ok, noto'g'ri → e'tiborsiz), `page:int` (0), `size:int` (20) | `ApiResponse<Page<CashTransactionDto>>` | Spring `Page`; sort `transactionDate DESC, createdAt DESC` (`:98-99`) | |
| GET | `/api/cash-registers/{id}/transactions/export` | `exportTransactions` (`:105-124`) | SA, A, ACC | yuqoridagi filtrlar (page/size siz) | `ResponseEntity<byte[]>` — `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `Content-Disposition: attachment; filename=cash-transactions-{id}.xlsx` | yo'q | Envelope YO'Q; frontend `responseType:'blob'`. Ustunlar: ID, Sana, Turi, **Yo'nalish (IN/OUT)**, Usul, O'quvchi, O'qituvchi, Nomi, **Summa (±)** (chiqim manfiy), Izoh, Holat, Yaratuvchi |
| POST | `/api/cash-registers/{id}/income` | `addIncome` (`:133-139`) | SA, A, ACC | body: `IncomeCreateDto` | `ApiResponse<CashTransactionDto>`, **201**, `"Kirim qo'shildi"` | yo'q | `studentId` berilsa `Student.balance += amount` (`CashRegisterService.java:395-400`) |
| POST | `/api/cash-registers/{id}/expense` | `addExpense` (`:141-147`) | SA, A, ACC | body: `ExpenseCreateDto` | `ApiResponse<CashTransactionDto>`, **201**, `"Chiqim qo'shildi"` | yo'q | Balans manfiyga tushishi mumkin (`:405-407`) |
| POST | `/api/cash-registers/transfer` | `transfer` (`:149-154`) | SA, A, ACC | body: `TransferDto` | `ApiResponse<List<CashTransactionDto>>` (chiqim+kirim juftligi), **201**, `"O'tkazma bajarildi"` | yo'q | Manba=maqsad → 400; balans yetmasa 400 (`:536-554`, `:667-679`) |

**`CashRegisterCreateDto`** (`dto/request/CashRegisterCreateDto.java:6-11`): `name: String`, `moderatorId: Long` (User id; yo'q → 404), `acceptOnlinePayment: Boolean`, `archived: Boolean`. Validatsiya yo'q.

**`CashRegisterStatusUpdateDto`** (`dto/request/CashRegisterStatusUpdateDto.java:6-8`): `status: String` (`ACTIVE`|`ARCHIVED`).

**`IncomeCreateDto`** (`dto/request/IncomeCreateDto.java:10-21`): `transactionType: String` (aslida tranzaksiya NOMI sifatida yoziladi, `CashRegisterService.java:389`), `studentId: Long`, `amount: BigDecimal` [PUL], `paymentMethod: PaymentMethod` (enum, JSON), `cashPart/cardPart: BigDecimal` [PUL], `transactionDate: LocalDate`, `note: String`. Onlayn usul (CLICK/PAYME/UZUM) + kassa `acceptOnlinePayment=false` → 400 (`CashRegisterService.java:349`).

**`ExpenseCreateDto`** (`dto/request/ExpenseCreateDto.java:10-22`): `studentId: Long`, `amount: BigDecimal` [PUL], `periodMonth: LocalDate`, `totalAmount: BigDecimal` [PUL], `paymentMethod: PaymentMethod`, `cashPart/cardPart: BigDecimal` [PUL], `transactionDate: LocalDate`, `note: String`. (Nomi `dto/request/ExpenseRequest` bilan chalkashadi — bu KASSA chiqimi.)

**`TransferDto`** (`dto/request/TransferDto.java:9-19`): `fromCashRegisterId: Long` (majburiy, servis), `toCashRegisterId: Long` (majburiy), `amount: BigDecimal` [PUL] (>0), `paymentMethod: PaymentMethod` (majburiy, `:726-731`), `cashPart/cardPart: BigDecimal` [PUL], `note: String`.

**`CashRegisterDto`** (`dto/response/CashRegisterDto.java:9-21`): `id: Long`, `uuid: String` (boshqa DTO larda `UUID`), `name`, `moderatorId: Long`, `moderatorName: String`, [PUL] `balance`, `plasticBalance`, `cashBalance` (`balance = plastic + cash`, `CashRegisterService.java:695-697`), `status: CashRegisterStatus`, `acceptOnlinePayment: boolean`, `archived: boolean`.

**`CashBalanceDto`** (`dto/response/CashBalanceDto.java:14-19`): `cashRegisterId: Long`, [PUL] `balance`, `cashBalance`, `plasticBalance`.

**`CashTransactionDto`** (`dto/response/CashTransactionDto.java`): `id: Long`, `uuid: String`, `cashRegisterId: Long`, `type: CashTransactionType` (INCOME/EXPENSE/TRANSFER/REVERSAL), `paymentMethod: PaymentMethod`, `studentId: Long`, `studentName`, `teacherId: Long`, `teacherName`, `transactionName: String`, [PUL] `amount` (doim musbat), **`direction: "IN"|"OUT"`**, [PUL] **`signedAmount`** (IN → +, OUT → −), **`paymentId: Long?`**, **`payrollId: Long?`**, **`relatedTxId: Long?`** (REVERSAL → asl yozuv; TRANSFER kirim qatori → chiqim qatori) — 02.10.2026, `cashPart`, `cardPart` (faqat CASH_AND_CARD), `note`, `status: CashTransactionStatus`, `periodMonth: LocalDate`, [PUL] `totalAmount`, `transactionDate: LocalDate`, `createdAt: LocalDateTime`, `createdByName: String`.

**Enumlar:** `CashRegisterStatus` = `ACTIVE, ARCHIVED`; `CashTransactionType` = `INCOME, EXPENSE, TRANSFER`; `CashTransactionStatus` = `COMPLETED, PENDING, CANCELLED` (`entity/enums/*.java`).

---

### Oylik (Payroll) — `controller/PayrollController.java`

> **Payroll v2 (02.10.2026) bilan yangilandi.** To'liq shartnoma (DTO, misollar, xato kodlari):
> [`docs/design/payroll-v2-api.md`](../design/payroll-v2-api.md); dizayn — [`payroll-v2.md`](../design/payroll-v2.md).

- Base path: `/api/payroll`. Har endpointda `@PreAuthorize` (avval GET larda yo'q edi).
- SecurityConfig: `/api/payroll/**` → SA, A, ACC.
- Holat: `PayrollStatus` enum `DRAFT → APPROVED → PAID`, `APPROVED/PAID → CANCELLED`. Holat faqat amallar orqali.

| METHOD | path | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| GET | `/api/payroll/calculate` | SA, A, ACC | query `month`, `year` **req** | `ApiResponse<List<SalaryCalculationDto>>` | Preview; cutover oyidan oldin → 400 `payroll.beforeCutover` |
| GET | `/api/payroll/calculate/{userId}` | SA, A, ACC | path `userId` (User id); `month`, `year` | `ApiResponse<SalaryCalculationDto>` | |
| POST | `/api/payroll/generate` | SA, A, ACC | query **yoki** body `{month, year, recalculate}` | `ApiResponse<PayrollGenerateResult>` `{created, recalculated, skipped[{userId, fullName, reason, message, payrollId}], totalAmount}`, 200 | Yetishmayotganlarga DRAFT; DRAFT faqat `recalculate=true`; APPROVED/PAID tegilmaydi |
| GET | `/api/payroll` | SA, A, ACC | `page`, `size`, `status` (enum; noto'g'ri → 400), `month`, `year`, `userId` | `ApiResponse<PageResponse<PayrollResponse>>` | sort yil↓, oy↓, id↓ |
| GET | `/api/payroll/{id}` | SA, A, ACC | | `ApiResponse<PayrollResponse>` | |
| GET | `/api/payroll/teacher/{teacherId}` | SA, A, ACC | Teacher id | `ApiResponse<List<PayrollResponse>>` | |
| POST | `/api/payroll/{id}/recalculate` | SA, A, ACC | — | `ApiResponse<PayrollResponse>` | faqat DRAFT (409 `payroll.notDraft`) |
| POST | `/api/payroll/{id}/approve` | SA, A | body ixtiyoriy `{expectedNetSalary}` | `ApiResponse<PayrollResponse>` | Bonuslar APPLIED; farq → 409 `payroll.netChanged` + `data.netSalary` |
| POST | `/api/payroll/{id}/pay` | SA, A, ACC | `PayrollPayDto` (+ `idempotencyKey`), sarlavha `Idempotency-Key` | `ApiResponse<PayrollResponse>` | faqat APPROVED; qulf ostida; takror kalit — o'sha javob |
| POST | `/api/payroll/{id}/cancel` | **SA** | `{reason}` (3–500) | `ApiResponse<PayrollResponse>` | APPROVED/PAID → CANCELLED; bonuslar PENDING; PAID → kassaga REVERSAL |
| DELETE | `/api/payroll/{id}` | SA, A | | `ApiResponse<Void>` | faqat DRAFT |
| ~~POST~~ | ~~`/api/payroll`~~ | | | **405** | olib tashlandi (v2) |
| ~~PUT~~ | ~~`/api/payroll/{id}`~~ | | | **405** | olib tashlandi (v2) |

**`PayrollResponse`** (asosiy maydonlar): `id`, `uuid`, `userId`, `userName`, `role`, `teacherId`, `teacherName`, `month`, `year`,
`status: PayrollStatus`, [PUL] `basicSalary`, `allowances`, `deductions` (0), `grossSalary`, `bonusPenaltyAdjustment`, `netSalary`,
`paidStudentCount: Integer`, `paidStudentUnits: BigDecimal` (kasr bo'lishi mumkin), `newStudentCount`, `kpiApplied`, `kpiAmount`,
`calculationDetails: object` (avval string edi), `calcVersion`, `paymentDate`, `paymentMethod*`, `cashRegisterId/Name`,
`cashTransactionId`, `approvedAt/ByName`, `paidAt/ByName`, `cancelledAt/ByName`, `cancelReason`, `notes`, `createdByName`,
`createdAt`, `updatedAt`.

**`SalaryCalculationDto`**: `userId`, `fullName`, `role`, `month`, `year`, [PUL] `baseSalary`, `paidStudentCount`,
`paidStudentUnits`, [PUL] `perStudentAmount`, `newStudentCount`, [PUL] `newStudentAmount`, `kpiApplied`, [PUL] `kpiAmount`,
`totalActiveStudents`, [PUL] `grossAmount`, `bonusPenaltyAdjustment`, `totalAmount` (= net), `calculable`, `message`,
`calculationDetails: object` (v1 dagi `details`/`students` olib tashlandi).

---

### Oylik qoidalari — `controller/SalaryRuleController.java`

- Base path: `/api/salary-rules` (`SalaryRuleController.java:17`). Class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")` (`:19`).
- SecurityConfig: `/api/salary-rules`, `/api/salary-rules/**` → faqat SUPER_ADMIN (`SecurityConfig.java:128-129`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/salary-rules` | `getAll` (`:24-27`) | **faqat SA** | — | `ApiResponse<List<SalaryRuleResponse>>` | yo'q; `role ASC, id ASC` (`service/SalaryRuleService.java:25-26`) | Nofaol qoidalar ham qaytadi |
| POST | `/api/salary-rules` | `create` (`:29-33`) | SA | body `SalaryRuleRequest` (`@Valid`) | `ApiResponse<SalaryRuleResponse>`, **201**, `"Salary rule created"` | yo'q | |
| PUT | `/api/salary-rules/{id}` | `update` (`:35-40`) | SA | path `id`; body `SalaryRuleRequest` | `ApiResponse<SalaryRuleResponse>`, `"Salary rule updated"` | yo'q | |
| DELETE | `/api/salary-rules/{id}` | `delete` (`:42-46`) | SA | path `id` | `ApiResponse<Void>`, `"Salary rule deactivated"` | yo'q | **Soft**: `isActive=false` (`SalaryRuleService.java:47-50`) |

`GET /api/salary-rules/{id}` — **qo'shildi** (payroll v2). `PUT` — qoida APPROVED/PAID oylikda ishlatilgan bo'lsa 409 `salaryRule.inUse`; `POST` (`effectiveFrom` bilan) shu doiradagi oldinroq boshlangan faol qoidani `effectiveFrom − 1` da yopadi, shu sanadan yoki keyin boshlanganini nofaol qiladi (ishlatilgan bo'lsa 409 `salaryRule.overlapsUsed`). Batafsil: [`payroll-v2-api.md` §4](../design/payroll-v2-api.md).

**`SalaryRuleRequest`** (payroll v2 — yakuniy nomlar): `role: UserRole` — `@NotNull` (TEACHER/ADMIN/SALES_MANAGER); `userId: Long?` (shaxsiy qoida, roli `role` ga teng); [PUL] `fixedSalary`, `perPayingStudent` (TEACHER), `perNewStudent` (ADMIN, SALES), `kpiBonus` (ADMIN) — hammasi ≥ 0; `kpiThreshold: Integer` (ADMIN); `isActive: Boolean` (null → true); `effectiveFrom: LocalDate?`, `effectiveTo: LocalDate?`. Noma'lum/eski nomlar (`baseSalary`, `perStudentFee`, `newStudentBonus`) → 400 `salaryRule.field.unknown`; rolga tegishli bo'lmagan nol emas maydon → 400 `salaryRule.field.notApplicable`.

**`SalaryRuleResponse`**: `id`, `role`, `userId?`, `userName?`, [PUL] `fixedSalary`, `perPayingStudent`, `perNewStudent`, `kpiThreshold`, [PUL] `kpiBonus`, `isActive`, `effectiveFrom`, `effectiveTo`, `createdAt`, `updatedAt` (so'rov bilan bir xil nomlar).

**Enum** `UserRole` = `SUPER_ADMIN, ADMIN, SALES_MANAGER, TEACHER, ACCOUNTANT, STUDENT, PARENT` (`entity/enums/UserRole.java:3-11`).

---

### Bonus / Jarima — `controller/BonusPenaltyController.java`

- Base path: `/api/bonus-penalties` (`BonusPenaltyController.java:24`). Class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")` (`:26`).
- SecurityConfig: **maxsus qoida YO'Q** → GET/POST/PUT/PATCH `anyRequest().authenticated()` (`SecurityConfig.java:199`), DELETE → `DELETE /api/**` SA, A (`:196-197`).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/bonus-penalties` | `getAll` (`:31-44`) | SA, A, ACC | query: `kind:String?` (BONUS/PENALTY), `targetType:String?` (STUDENT/TEACHER/STAFF), `studentId:Long?`, `teacherId:Long?`, `userId:Long?` (STAFF, payroll v2), `status:String?` (PENDING/APPLIED/CANCELLED) — noto'g'ri enum → e'tiborsiz (`service/BonusPenaltyService.java:339-348`); `page:int` (0), `size:int` (20) | `ApiResponse<PageResponse<BonusPenaltyDto>>` | **PageResponse**; sort `createdAt DESC` (`:40-41`) | |
| GET | `/api/bonus-penalties/summary` | `getSummary` (`:46-52`) | SA, A, ACC | query: `from:String?` (def oy boshi), `to:String?` (def bugun); ISO, noto'g'ri → **500** (`:115-120`) | `ApiResponse<BonusPenaltySummaryDto>` | yo'q | `effectiveDate` oralig'ida, CANCELLED dan tashqari (`BonusPenaltyService.java:76-102`) |
| GET | `/api/bonus-penalties/preview/teacher/{teacherId}` | `previewForTeacher` (`:54-64`) | SA, A, ACC | path `teacherId:Long`; query `upToDate:String?` (def bugun) | `ApiResponse<BonusPenaltyPreviewDto>` | yo'q | PENDING yozuvlar bo'yicha |
| GET | `/api/bonus-penalties/preview/staff/{userId}` | `previewForStaff` | SA, A, ACC | path `userId:Long` (ADMIN/SALES xodim); query `upToDate:String?` (def bugun) | `ApiResponse<BonusPenaltyPreviewDto>` | yo'q | **Payroll v2:** STAFF PENDING yozuvlari |
| GET | `/api/bonus-penalties/preview/student/{studentId}` | `previewForStudent` (`:66-76`) | SA, A, ACC | path `studentId:Long`; query `upToDate:String?` | `ApiResponse<BonusPenaltyPreviewDto>` | yo'q | To'lov formasida ishlatiladi (TAXMIN) |
| GET | `/api/bonus-penalties/{id}` | `getById` (`:78-81`) | SA, A, ACC | path `id` | `ApiResponse<BonusPenaltyDto>` | yo'q | |
| POST | `/api/bonus-penalties` | `create` (`:83-88`) | SA, A, ACC | body `BonusPenaltyCreateDto` (`@Valid`) | `ApiResponse<BonusPenaltyDto>`, **201**, `"Yozuv yaratildi"` | yo'q | status=PENDING; `effectiveDate` null → bugun |
| PUT | `/api/bonus-penalties/{id}` | `update` (`:90-96`) | SA, A, ACC | body `BonusPenaltyCreateDto` | `ApiResponse<BonusPenaltyDto>`, `"Yozuv yangilandi"` | yo'q | Faqat PENDING, aks holda 400 (`BonusPenaltyService.java:235-240`) |
| PATCH | `/api/bonus-penalties/{id}/cancel` | `cancel` (`:98-102`) | SA, A, ACC | — (body yo'q) | `ApiResponse<BonusPenaltyDto>`, `"Yozuv bekor qilindi"` | yo'q | Faqat PENDING |
| DELETE | `/api/bonus-penalties/{id}` | `delete` (`:104-108`) | **SA, A** (ACC URL darajasida 403) | path `id` | `ApiResponse<Void>`, `"Yozuv o'chirildi"` | yo'q | Faqat PENDING; hard delete |
| GET | `/api/bonus-penalties/teacher/{teacherId}` | `getByTeacher` (`:110-113`) | SA, A, ACC | path `teacherId` | `ApiResponse<List<BonusPenaltyDto>>` | yo'q; `effectiveDate DESC` | |

**`BonusPenaltyCreateDto`** (`dto/request/BonusPenaltyCreateDto.java`): `kind: BonusPenaltyKind` — `@NotNull`; `targetType: BonusTargetType` (`STUDENT`/`TEACHER`/**`STAFF`**) — `@NotNull`; `studentId: Long` (STUDENT uchun majburiy); `teacherId: Long` (TEACHER uchun majburiy); **`userId: Long`** (STAFF uchun majburiy, roli ADMIN/SALES_MANAGER — aks holda 400 `bonus.staff.userRequired` / `bonus.staff.roleInvalid`); `amount: BigDecimal` [PUL] — `@NotNull @DecimalMin("0.01")` (`:20-22`), doim musbat (belgi `kind` dan); `reason: String`; `effectiveDate: LocalDate`.

**`BonusPenaltyDto`** (`dto/response/BonusPenaltyDto.java:19-36`): `id: Long`, `uuid: String`, `kind: BonusPenaltyKind`, `targetType: BonusTargetType`, `studentId: Long?`, `studentName`, `teacherId: Long?`, `teacherName`, `userId: Long?`, `userName` (STAFF), `appliedToPayrollId: Long?` (oylikka qo'llangan — tasdiqlashda), [PUL] `amount` (musbat), `reason`, `status: BonusPenaltyStatus`, `effectiveDate: LocalDate`, `createdAt: LocalDateTime`, `createdByName: String` (ism familiya), `targetName: String` (target turiga qarab), [PUL] `signedAmount` (PENALTY → manfiy, `BonusPenaltyService.java:397-402`).

**`BonusPenaltySummaryDto`** (`dto/response/BonusPenaltySummaryDto.java:14-18`): [PUL] `totalBonus`, [PUL] `totalPenalty`, `count: long`.
**`BonusPenaltyPreviewDto`** (`dto/response/BonusPenaltyPreviewDto.java:14-19`): [PUL] `totalBonus`, [PUL] `totalPenalty`, [PUL] `net`, `count: long`.

**Enumlar:** `BonusPenaltyKind` = `BONUS, PENALTY`; `BonusTargetType` = `STUDENT, TEACHER`; `BonusPenaltyStatus` = `PENDING, APPLIED, CANCELLED`.

---

### Fayllar — `controller/FileController.java`

- Base path: `/api/files` (`FileController.java:23`). Class-level `@PreAuthorize`: yo'q.
- SecurityConfig: `GET /api/files/**` → **permitAll** (`SecurityConfig.java:85`); `POST /api/files/**` → authenticated (`:194`); DELETE → SA/A (`:196`, lekin endpoint yo'q).

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| POST | `/api/files/upload` | `uploadFile` (`FileController.java:29-50`) | har qanday autentifikatsiyalangan (`@PreAuthorize("isAuthenticated()")`, `:30`) | multipart, maydon nomi **`file`** (`:32`) | `ApiResponse<Map<String,String>>` `{url: "/api/files/<uuid><ext>", filename: "<uuid><ext>"}`, **200** (201 emas), `"Fayl yuklandi"` | yo'q | Faqat "rasm" (header bo'yicha) |
| GET | `/api/files/{filename}` | `getFile` (`:52-74`) | **OCHIQ (JWT siz)** | path `filename:String` (bitta segment) | `ResponseEntity<Resource>` — `Content-Type` = `Files.probeContentType` (yo'q → `application/octet-stream`), `Content-Disposition: inline; filename="..."` | yo'q | Topilmasa/har qanday xato → 404 body siz |

Upload tafsilotlari:
- Ruxsat: `file.getContentType()` `image/` bilan boshlanishi (`service/FileStorageService.java:40-43`) — **mijoz yuborgan header**, fayl mazmuni tekshirilmaydi. Bo'sh fayl → 400 (`:37-39`).
- Hajm: `MAX_BYTES = 4MB` (`FileStorageService.java:18`, `:44-46`) + Spring `max-file-size: 4MB`, `max-request-size: 8MB` (`src/main/resources/application.yml:6-8`). Oshsa → 400 `chat.upload.tooLarge` xabari (`GlobalExceptionHandler.java:91-95`).
- Kengaytma: original fayl nomidagi oxirgi `.` dan keyingi qism **cheklovsiz** olinadi; nuqta bo'lmasa `.jpg` (`FileController.java:35-37`). Saqlangan nom `UUID + ext` (`:38`).
- Saqlash joyi: `app.upload.dir` (default `/opt/crm/uploads`, `FileStorageService.java:30`; `application.yml:48-49`) — tekis papka, chat biriktirmalari ham shu yerda.
- Qaytgan URL — **nisbiy** `/api/files/<name>` (`FileStorageService.java:24`, `:77`); `app.base-url` (`getBaseUrl`, `:89-91`) hech qayerda ishlatilmaydi. Frontend o'zi `BASE_URL` qo'shadi (eski front `utils/fileUrl.js:14`).
- Path traversal: `resolveSafePath` `normalize()+startsWith` bilan himoyalangan (`FileStorageService.java:80-87`).
- Xatolik: har qanday exception → 400 `"Fayl yuklashda xatolik: " + e.getMessage()` (`FileController.java:44-49`) — ichki xabar sizib chiqadi.
- Fayl o'chirish endpointi YO'Q.

---

### Ichki chat (REST) — `controller/ChatController.java`

- Base path: `/api/chat` (`ChatController.java:42`). Class-level `@PreAuthorize`: yo'q (faqat `upload` da `isAuthenticated()`, `:174`).
- SecurityConfig: `/api/chat/**` → authenticated, rol cheklovisiz (`SecurityConfig.java:190-192`). Suhbatga kirish `ChatAccessService.requireParticipant` → a'zo bo'lmasa **403** (`service/ChatAccessService.java:66-70`). Joriy user topilmasa 403 (`:38-50`).
- `{id:\d+}` — regex bilan faqat raqamli id.

| METHOD | path | Controller#metod | Effektiv rollar | Request | Response | Pagination | Izoh |
|---|---|---|---|---|---|---|---|
| GET | `/api/chat/conversations` | `conversations` (`:52-56`) | istalgan auth | — | `ApiResponse<List<ConversationResponse>>` | yo'q (hammasi) | |
| GET | `/api/chat/conversations/{id}/messages` | `messages` (`:68-77`) | a'zo | path `id:Long`; query `before:Long?` (kursor — oldingi sahifaning eng eski xabar id si), `around:Long?` (xabar atrofidagi oyna), `size:Integer?` (def **50**, max **100**, `<=0` → 50; `service/ChatService.java:79-80`, `:408-413`) | `ApiResponse<List<ChatMessageResponse>>` — **yangi → eski** | **Kursor** (keyset), PageResponse emas; "last" belgisi yo'q — `size` dan kam kelsa tugagan (TAXMIN) | `before`+`around` birga → 400 (`ChatService.java:361-363`); `around` bu suhbatda bo'lmasa → 400 |
| GET | `/api/chat/conversations/{id}/messages/search` | `searchInConversation` (`:86-93`) | a'zo | path `id`; query `q:String?` (min 2 belgi, aks holda bo'sh ro'yxat) | `ApiResponse<List<ChatMessageResponse>>` | limit 20 (`ChatService.java:83`, `:490`) | Keyin `?around=<id>` bilan sakrash |
| GET | `/api/chat/search` | `search` (`:101-106`) | istalgan auth | query `q:String?` (min 2) | `ApiResponse<ChatSearchResponse>` | har bo'lim max 20 (`ChatService.java:437`) | `q` qisqa → `{conversations:[], messages:[]}` |
| GET | `/api/chat/presence` | `presence` (`:116-120`) | istalgan auth | — | `ApiResponse<List<PresenceResponse>>` | yo'q | Faqat suhbatdoshlar; keyin `/topic/presence` |
| POST | `/api/chat/conversations/direct` | `direct` (`:123-129`) | istalgan auth | body `DirectConversationRequest` (`@Valid`) | `ApiResponse<ConversationResponse>`, **200** (yangi yaratilsa ham) | yo'q | O'zi bilan → 400; nofaol user → 400 (`ChatService.java:525-556`) |
| POST | `/api/chat/conversations/group` | `group` (`:132-138`) | istalgan auth | body `GroupConversationRequest` (`@Valid`) | `ApiResponse<ConversationResponse>`, **201**, `"Guruh yaratildi"` | yo'q | Yaratuvchi avtomatik a'zo; boshqa a'zo bo'lmasa 400; user topilmasa 404 (`ChatService.java:576-606`) |
| PATCH | `/api/chat/conversations/{id}/pin` | `pin` (`:141-148`) | a'zo | body `ConversationPinRequest` (`@Valid`) | `ApiResponse<ConversationResponse>` | yo'q | Faqat joriy user uchun |
| GET | `/api/chat/users` | `users` (`:151-155`) | istalgan auth | — | `ApiResponse<List<ChatUserResponse>>` | yo'q | Barcha faol userlar (o'zidan tashqari), ism bo'yicha (`ChatService.java:626-634`) |
| POST | `/api/chat/upload` | `upload` (`:173-182`) | istalgan auth | `multipart/form-data` (`consumes`, `:173`): `file` **req**, `durationMs:Integer?`, `waveform:String?` | `ApiResponse<ChatUploadResponse>`, **200**, `"Fayl yuklandi"` | yo'q | Xabar yaratilmaydi — javob `/app/chat.send` `attachments[]` ga qo'yiladi |
| GET | `/api/chat/unread-count` | `unreadCount` (`:185-190`) | istalgan auth | — | `ApiResponse<UnreadCountResponse>` `{count: long}` | yo'q | |

Chat upload tafsilotlari (`service/ChatAttachmentService.java`):
- Ruxsat etilgan Content-Type (header, `;` dan keyingisi kesiladi, lowercase, `:216-224`): `image/*` (istalgan), `application/pdf`, `application/msword`, `…wordprocessingml.document`, `application/vnd.ms-excel`, `…spreadsheetml.sheet`, `application/zip`, `application/x-zip-compressed`, `text/plain` (`:51-60`); audio: `audio/webm`, `audio/ogg`, `audio/mpeg`, `audio/mp4`, `audio/wav` (`:69-75`). Boshqasi → 400 (`:138-149`).
- Audio uchun `durationMs` **majburiy**, `1..300000` ms (5 daqiqa, `entity/MessageAttachment.java:41`; `ChatAttachmentService.java:158-167`). `waveform` ixtiyoriy: vergul bilan ajratilgan butun sonlar `0..100`, max 50 ta (`MessageAttachment.java:44-45`; `ChatAttachmentService.java:182-209`). Audio bo'lmasa ikkalasi e'tiborsiz.
- Hajm: faqat Spring multipart 4MB (`application.yml:7`); `FileStorageService.save` hajmni TEKSHIRMAYDI (`FileStorageService.java:65-78`) — servis izohidagi "ikkinchi to'siq" (`ChatAttachmentService.java:92-94`) haqiqatga mos emas.
- Kengaytma: original nomdan, `<=10` belgi bo'lsa, lowercase; aks holda `.bin` (`:258-266`). Nom `UUID+ext`, ko'rsatiladigan `fileName` — yo'lsiz original nom, max 255 (`:242-256`).
- Rasm o'lchami `ImageIO` bilan (SVG/ba'zi WebP → null) (`:231-239`).
- Saqlash: `FileStorageService.save` → `app.upload.dir`, URL `/api/files/<uuid><ext>` — ya'ni **chat biriktirmalari ham JWT siz ochiq GET orqali olinadi**.

**`DirectConversationRequest`** (`dto/request/DirectConversationRequest.java:8-12`): `userId: Long` — `@NotNull`.
**`GroupConversationRequest`** (`dto/request/GroupConversationRequest.java:15-23`): `title: String` — `@NotBlank @Size(max=255)`; `userIds: List<Long>` — `@NotEmpty`.
**`ConversationPinRequest`** (`dto/request/ConversationPinRequest.java:8-12`): `pinned: Boolean` — `@NotNull`.

**`ConversationResponse`** (`dto/response/ConversationResponse.java:21-60`): `id: Long`, `type: ConversationType`, `title: String`, `photoUrl: String?`, `participants: List<ChatUserResponse>`, `lastMessageText: String?`, `lastMessageAt: LocalDateTime`, `lastMessageSenderId: Long?`, `lastMessageType: MessageType?`, `unreadCount: long`, `lastReadMessageId: Long?`, `peerLastReadMessageId: Long?` (GROUP da null), `isPinned: Boolean`, `isMuted: Boolean` (JSON kalitlari `isPinned`/`isMuted` — Lombok wrapper Boolean).

**`ChatMessageResponse`** (`dto/response/ChatMessageResponse.java:23-73`): `type: String` (doim `"MESSAGE"` — hodisa turi), `id: Long`, `uuid: UUID`, `conversationId: Long`, `conversationTitle: String` (**`@JsonInclude(NON_NULL)`**, faqat umumiy qidiruvda, `:42-43`), `senderId: Long`, `senderName`, `senderPhotoUrl`, `text: String?`, `messageType: MessageType`, `replyToId: Long?`, `clientId: String?`, `attachments: List<ChatAttachmentResponse>?` (o'chirilganda null), `replyTo: ChatReplyPreviewResponse?`, `createdAt`, `editedAt`, `deletedAt: LocalDateTime?`.
**`ChatReplyPreviewResponse`** (`dto/response/ChatReplyPreviewResponse.java:21-26`): `id: Long`, `senderName`, `text: String?`, `type: MessageType`.
**`ChatAttachmentResponse`** (`dto/response/ChatAttachmentResponse.java:10-28`): `id: Long`, `fileUrl`, `fileName`, `fileSize: Long`, `contentType`, `width/height: Integer?`, `durationMs: Integer?`, `waveform: String?`, `sortOrder: Integer`.
**`ChatUploadResponse`** (`dto/response/ChatUploadResponse.java:17-34`): `fileUrl: String`, `fileName: String`, `fileSize: Long`, `contentType: String`, `width/height: Integer?`, `durationMs: Integer?`, `waveform: String?`.
**`ChatSearchResponse`** (`dto/response/ChatSearchResponse.java:18-22`): `conversations: List<ConversationResponse>`, `messages: List<ChatMessageResponse>`.
**`ChatUserResponse`** (`dto/response/ChatUserResponse.java:21-33`): `id: Long`, `fullName`, `username`, `role: UserRole`, `photoUrl`, `online: boolean`, `lastSeenAt: LocalDateTime?`.
**`PresenceResponse`** (`dto/response/PresenceResponse.java:19-29`): `type: String` (doim `"PRESENCE"`), `userId: Long`, `online: boolean`, `lastSeenAt: LocalDateTime?`.
**`UnreadCountResponse`** (`dto/response/UnreadCountResponse.java:10-12`): `count: long`.

**Enumlar:** `MessageType` = `TEXT, IMAGE, FILE, VOICE, SYSTEM`; `ConversationType` = `DIRECT, GROUP` (`entity/enums/*.java`).

---


## 5. WebSocket (STOMP)

REST emas — to'liq tavsif (endpoint `/ws`, handshake `?token=`, `/app/chat.*` destination'lar, `/topic/conversation.{id}`, `/topic/presence`, `/user/queue/errors`, payload DTO'lari): [backend-audit.md §7](backend-audit.md).

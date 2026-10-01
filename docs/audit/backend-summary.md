# Adizone CRM backend — qisqa xulosa (frontend uchun)

> Bu hujjat yangi Vue 3 + TS admin frontend jamoasi uchun. Holat 2026-09-29 (`main` @ `876897a`), faqat kod o'qilgan.
> To'liq ma'lumot: [backend-audit.md](backend-audit.md) va [api-inventory.md](api-inventory.md). Yo'llar `src/main/java/com/crm/` ga nisbatan.

## 1. Stack va springdoc
- **Stack:** Spring Boot 3.2.0, Java 17, PostgreSQL, jjwt 0.11.5, Apache POI, STOMP WebSocket (`pom.xml:10`, `:22`).
- **Sxema:** `ddl-auto: update` quradi. Flyway yo'q (README noto'g'ri).
- **Profillar:** yo'q, bitta `application.yml`.
- **springdoc-openapi: YO'Q.** `/v3/api-docs` mavjud emas.
  - Minimal qo'shish ~0.5 kun: dependency + SecurityConfig'da ruxsat + Bearer scheme.
  - Sifatli TS generatsiyasi uchun yana ~2–4 kun: `Map`, `ResponseEntity<?>`, Spring `Page` va `String`-enumlarni tiplash kerak.
  - Tavsiya: hozircha tiplarni `api-inventory.md` asosida qo'lda yozish.

## 2. Endpointlar soni (43 controller, 338 REST yo'l)
| Modul | Soni | Modul | Soni |
|---|---|---|---|
| Auth | 7 | Lead | 21 |
| User | 12 | LeadStage | 5 |
| Role | 1 | LeadImport | 4 |
| Teacher | 18 | Import | 4 |
| TeacherDashboard | 1 | MetaAdmin | 11 |
| Student | 25 | MetaWebhook (tashqi) | 2 |
| Parent | 9 | Marketing | 1 |
| Settings | 1 | Task | 10 |
| Enum | 3 | Dashboard | 1 |
| Search | 1 | Analytics | 8 |
| Group | 13 | AuditLog | 3 |
| Course | 5 | AdminRepair (SA) | 8 |
| Classroom | 5 | Payment | 11 |
| Academic (classes/sections/subjects/timetable) | 25 | Finance | 3 |
| Attendance | 5 | Expense | 2 |
| AttendanceUnlockRequest | 6 | CashRegister | 12 |
| Homework | 9 | Payroll | 10 |
| Exam | 13 | SalaryRule (SA) | 4 |
| Notice | 10 | BonusPenalty | 10 |
| Leave | 8 | File | 2 |
| Promotion | 6 | Chat (REST) | 11 |
| Contract | 12 | **WS STOMP** | 5 `@MessageMapping` |

## 3. Xavfsizlik va CORS
- **Auth oqimi:**
  - `POST /api/auth/login` → `{accessToken, refreshToken, tokenType, userId, username, firstName, lastName, role, expiresIn}`.
  - `POST /api/auth/refresh {refreshToken}` → xuddi shu shakl. Rotation bor: eski refresh token o'ladi.
  - `POST /api/auth/logout {refreshToken}`.
  - `GET /api/auth/me` → `UserResponse`.
  - Tokenlar faqat JSON body'da keladi, cookie ishlatilmaydi.
- **Muddatlar:** access **8 soat** (`application.yml:58`), refresh **7 kun** (`:59`). ⚠️ `expiresIn` doim 86400 qaytadi (`service/AuthService.java:99`), unga ishonmang.
- **JWT claim'lari:** faqat `sub/iat/exp`. `role`, `userId`, `teacherId` yo'q. Rol har so'rovda DB'dan olinadi.
- **Refresh token:** DB'dagi UUID, JWT emas. Har login userning **boshqa sessiyalarini o'chiradi** (`AuthService.java:80`).
- **Frontend uchun:**
  - 401 kelsa bitta refresh qiling (mutex bilan). U ham 401 bo'lsa → login sahifasi.
  - 401/403 body: `{success:false, message}`.
- **Rollar:** SUPER_ADMIN, ADMIN, SALES_MANAGER, TEACHER, ACCOUNTANT, STUDENT, PARENT. RoleHierarchy yo'q.
  - Effektiv ruxsat = URL qoidasi (`config/SecurityConfig.java:66-199`, birinchi mos kelgani ishlaydi) ∩ `@PreAuthorize`.
  - TEACHER egaligi `service/TeacherAccessService.java` da tekshiriladi, lekin hamma joyda emas.
- **Public endpointlar:**
  - `/api/auth/login|refresh|logout`
  - `/api/meta/webhook`
  - `GET /api/files/**`
  - `POST /api/leads/public`
  - `GET /api/settings/academic-year`
  - `/actuator/health`
  - `/ws` (token handshake'da, `?token=`)
- **CORS:** yagona ro'yxat `config/SecurityConfig.java:45-55` (`ALLOWED_ORIGIN_PATTERNS`).
  - WebSocket ham shu ro'yxatni ishlatadi (`config/WebSocketConfig.java:44-45`).
  - **`https://app.adizone.uz` ni shu ro'yxatga bitta qator qilib qo'shish kerak.** Env orqali sozlab bo'lmaydi, kod o'zgarishi va deploy kerak.
  - Hozir ro'yxatda: admin.adizone.uz, adizone.uz, www, `*.vercel.app`, localhost 3000/5173/5174.
  - `Content-Disposition` exposed emas, shuning uchun eksport fayl nomini frontend o'zi beradi.
- **Rate limit:** yo'q. Captcha: yo'q.
- **Audit:** `@Audited` 54 ta metodda turibdi; o'qish `GET /api/audit-logs` orqali (faqat SA), 90 kun saqlanadi. LeadNote, chat, fayl, Meta sozlamalari va change-password audit qilinmaydi.

## 4. Javob formatlari
- **Asosiy envelope:** `ApiResponse<T> {success, message, data, meta?}`.
- **Sahifalash** — 3 xil + chat:
  - `PageResponse {content, pageNumber, pageSize, totalElements, totalPages, last}` — ko'pchilik endpointlarda.
  - **Spring `Page`** (`number`, `size`, `pageable`...) — `/api/payments`, `/api/expenses`, `/api/finance/expenses`, `/api/cash-registers/{id}/transactions`.
  - Lid timeline — `{items, page, openTasks}`.
  - Chat — kursor (`before`, `around`).
  - `page` 0 dan boshlanadi. `sort` parametri yo'q.
- **Envelope'siz javoblar:** `/api/teachers/{id}/kpi`, `/api/teachers/me/kpi`, `/api/timetable/by-room`; eksportlar `byte[]`.
- **`Map` javoblari (DTO'siz):** teacher dashboard, `*/stats`, search, analitika grafiklari, `calculate-debt`. Bularni frontendda qo'lda tiplash kerak.
- **Xatolar** — 3 shakl:
  - `ErrorResponse {timestamp, status, error, message, validationErrors?}` — 400/404/409.
  - `ApiResponse {success:false, message}` — 401/403/405/404-route.
  - `{timestamp, status, error, message, path}` — 500.
  - Validatsiya xatolari: `validationErrors: {maydon: xabar}`.
  - **500 bo'lib ketadigan holatlar:** majburiy query param yo'q bo'lsa, `String`-sana noto'g'ri bo'lsa, unique buzilsa.
- **Sana formati:** `LocalDate` `"yyyy-MM-dd"`, `LocalDateTime` zonasiz ISO. Pul `BigDecimal` → JSON number (UZS).
- **Enumlar:** `name()` sifatida keladi. `/api/enums` faqat 3 ta enumni beradi, qolgan 26 tasini frontend qattiq yozadi (ro'yxat: audit §5.2). Ko'p status maydonlari erkin `String`.
- **Til:** `Accept-Language` (uz default, ru, en). Faqat validatsiya xabarlari to'liq tarjima qilinadi; qolgan matnlar o'zbek va ingliz tilida aralash.
- **Upload:** multipart maydon nomi `file`, limit 4MB/8MB. Qaytgan URL **nisbiy** (`/api/files/<uuid>.<ext>`), host'ni frontend o'zi qo'shadi.
- **WS (STOMP):** endpoint `/ws` (SockJS yo'q), `?token=<access>`.
  - Klient yuboradi: `/app/chat.send|read|typing|edit|delete`.
  - Klient obuna bo'ladi: `/topic/conversation.{id}` (hodisa turlari MESSAGE/READ/TYPING/EDITED/DELETED), `/topic/presence`, `/user/queue/errors`.
  - Chatdan tashqari real-time hodisa yo'q.

## 5. Eng muhim biznes qoidalar
- **To'lov rejimlari:** `MONTHLY` va `PER_LESSON`.
  - Balans ledger orqali hisoblanadi (`BalanceTransaction`: PAYMENT +, PERIOD_CHARGE −, LESSON_CHARGE −, LESSON_REFUND +, FREEZE/UNFREEZE, MANUAL_ADJUST).
  - Avtomatik oylik yechish **yo'q**: MONTHLY'da PERIOD_CHARGE faqat to'lov paytida yoziladi.
  - Proratsiya yo'q.
  - `POST /api/payments/preview` bazaga yozmasdan hisoblaydi, lekin bonus/jarimani hisobga olmaydi.
  - To'lovni bekor qilish yoki o'chirish API'si **yo'q**.
- **To'lov usullari:** CASH, CARD, CLICK, PAYME, UZUM, TERMINAL, BANK, CASH_AND_CARD (naqd + karta summasi jami summaga teng bo'lishi shart), OTHER.
- **Holat va sana:** `nextPaymentDate` va holat (PAID, OVERDUE…) sanaga asoslanadi, balansga emas. Kunlik job'lar: 00:05 (holatlar), 08:00 (30 kun davomatsiz o'quvchi avto-FROZEN), 10:00 (Telegram eslatma).
- **Muzlatish:** `POST /api/students/{id}/freeze/preview` → `freeze` → `unfreeze {groupId, paymentStartDate}`.
- **Davomat:**
  - PER_LESSON'da PRESENT, ABSENT va LATE pul yechadi; EXCUSED yechmaydi. MONTHLY'da balansga ta'sir qilmaydi.
  - TEACHER faqat o'z guruhiga yozadi. O'tgan kunlar qulflangan va faqat APPROVED unlock so'rovi bilan ochiladi (`/api/attendance/unlock-requests`). Ruxsat muddatsiz.
- **Lid:**
  - Bosqichlar sozlanadi (`/api/lead-stages`, 9 ta seed). `code` o'zgarmaydi. CONVERTED va REJECTED turidagi bosqichlarni o'chirib bo'lmaydi.
  - Konvertatsiya → Student + Parent (agar `parentPhone` bo'lsa) + StudentGroup (agar `groupId` bo'lsa). Qayta konvertatsiya mumkin emas.
  - Telefon dublikati tekshirilmaydi.
  - Import: xlsx, amoCRM formati: preview → execute → batch delete.
- **Meta:** webhook → imzo tekshiruvi → event navbati (har 15 s) → Graph API → lid. 30 kunlik dublikat oynasi bor.
- **Vazifalar:**
  - Faqat mas'ul (assignee) yoki SA/ADMIN ko'radi va yopadi. SALES_MANAGER faqat o'ziga vazifa yaratadi.
  - Vazifa yopilganda keyingi vazifa ixtiyoriy; javobdagi `leadHasOpenTask=false` frontendga signal beradi.
  - `CANCELLED` statusi ishlatilmaydi.
- **Oylik:**
  - Hisob `SalaryRule` bo'yicha (faqat SA boshqaradi).
  - Oqim: `POST /api/payroll/generate?month&year` → `/{id}/pay` (kassa chiqimi yoziladi).
  - Bonus va jarimalar keyingi generate'da qo'shiladi.
  - KPI (`TeacherKpiService`) oylikka ta'sir **qilmaydi**.
- **Kassa:** kassalar (ochiq yoki arxiv), kirim, chiqim, o'tkazma. To'lov va oylik `cashRegisterId` bilan kassaga tushadi.

## 6. Muammolar (§9 ning qisqa ro'yxati)
**Xavfsizlik**
1. 🔴 `application.yml` da sirlar repoda: DB paroli, `jwt.secret`, Meta tokenlari, Telegram tokeni.
2. 🔴 `/api/teachers/{id}/kpi`, `/kpi/trend`, `/kpi/daily`, shuningdek `GET /api/teachers`, `/{id}`, `/search` istalgan rolga ochiq. Javobda moliya va pasport ma'lumotlari bor (`controller/TeacherController.java:38-172`).
3. 🔴 ADMIN SUPER_ADMIN parolini o'zgartira oladi va SA rolini bera oladi (`controller/UserController.java:189`, `service/UserService.java:96`).
4. 🔴 `change-password` joriy parolni tekshirmaydi (`controller/AuthController.java:113-122`).
5. 🔴 `GET /api/files/**` ochiq. Upload kengaytmani cheklamaydi, natijada stored XSS mumkin va chat fayllari JWT'siz ochiladi.
6. 🔴 STOMP SEND kadrlari tekshirilmaydi, soxta chat hodisalari yuborish mumkin (`config/ChatChannelInterceptor.java:40-57`).
7. 🔴 Leave moduli istalgan rolga ochiq, `requesterId` va `approvedById` klientdan olinadi.
8. 🔴 Rate limit va captcha yo'q (login, `/api/leads/public`). Meta webhook'da imzo o'chiq bo'lsa istalgan so'rov qabul qilinadi.
9. 🟠 TEACHER scope bo'shliqlari: students search/left/trial/history, exam eligible-students, homework submissions.
10. 🟠 URL qoidasi va `@PreAuthorize` mos emas: ACCOUNTANT kassani o'chira oladi; bonus-penalties, contracts, leaves, audit-logs, marketing uchun URL qoidasi yo'q.

**Pul**

11. 🔴 `POST /api/payroll/{id}/pay` ni ikki marta chaqirsa, kassadan ikki marta chiqim yoziladi. `PUT` = upsert. `DELETE` kassani va bonuslarni qaytarmaydi.
12. 🟠 Ko'p guruhli o'quvchida PERIOD_CHARGE noto'g'ri hisoblanadi (`service/PaymentService.java:403`).
13. 🟠 Oylikdan kam to'lov, hatto 0 ham, `nextPaymentDate` ni 1 oyga suradi.
14. 🟠 "Qarzdor"ning 4 dan ortiq ta'rifi bor, dashboard va analytics raqamlari mos kelmaydi.
15. 🟠 To'lovni bekor qilish yo'q. Preview va create farq qiladi.
16. 🟠 `@Version` yoki lock yo'q; chek raqami `count()+1` (poyga holatida 500).

**Frontend shartnomasi**

17. 🟡 Javob shakllari 10 xil, xato shakllari 3 xil, sahifalash 4 xil. Majburiy param yuborilmasa 500 qaytadi.
18. 🟡 `expiresIn` noto'g'ri; bitta sessiya cheklovi bor.
19. 🟡 26 ta enum API'da yo'q; `ContractTemplateDto.isDefault` JSON'da `"default"` bo'lib chiqishi mumkin (TAXMIN).
20. 🟡 Kanban uchun alohida endpoint yo'q (N ustun = N so'rov), `sort` va manba bo'yicha filtr yo'q.
21. 🟡 Dublikat endpointlar: import ×2, `finance/expenses`, `students/search`, `notices/active=latest`. AdminRepair'dagi 3 ta endpoint bir martalik.

**Boshqa**

22. 🟡 Vaqt zonasi sozlanmagan (~133 ta `now()`); cron'lar JVM zonasida ishlaydi.
23. 🟡 Manba papkasida git kuzatayotgan eski `.class` fayllar bor; MapStruct va opencsv ishlatilmaydi.
24. **TODO/FIXME:** yo'q.
25. **`frontend-api-usage.md`:** topilmadi, shuning uchun solishtirish **tekshirilmadi**. Eski frontend grep qilinganda (TAXMIN) 7 ta mos kelmaydigan chaqiruv topildi (audit §9.1).

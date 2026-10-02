# Adizone CRM backend — frontend uchun audit

> **Maqsad:** yangi Vue 3 + TS admin frontend uchun to'liq API shartnomasi va biznes qoidalar. Backend o'zgarmaydi.
> **Holat:** 2026-09-29, `main` @ `876897a`. Faqat kod o'qildi: build, mvn va test ishga tushirilmadi, repodagi kod o'zgartirilmadi.
> **Yo'llar:** Java fayllari `src/main/java/com/crm/` ga nisbatan (masalan `service/PaymentService.java:88`). Resurslar esa `src/main/resources/...` yoki `application.yml:NN` ko'rinishida.
> **TAXMIN:** koddan to'g'ridan-to'g'ri tasdiqlanmagan har bir da'vo shu so'z bilan belgilangan.
> **Sirlar:** parol, token va kalitlarning qiymatlari bu hujjatga ko'chirilmagan.
> **Qo'shimcha hujjatlar:** to'liq endpoint jadvali [api-inventory.md](api-inventory.md) da, qisqa xulosa [backend-summary.md](backend-summary.md) da.

## Mundarija
- §1 Stack
- §2 Xavfsizlik
- §3 API inventarizatsiya (xulosa; to'liq jadval — api-inventory.md)
- §4 Javob va xato formati
- §5 Domen modeli
- §6 Biznes qoidalar (A–E moliya; 6.1–6.6 lid, Meta, vazifa, Telegram, audit, rate limit)
- §7 WebSocket (STOMP)
- §8 Fayllar
- §9 Muammolar va nomuvofiqliklar

---

## §1. Stack

### 1.1 Versiyalar va bog'liqliklar (`pom.xml`)
| Narsa | Qiymat | Manba |
|---|---|---|
| Spring Boot | **3.2.0** (parent) | `pom.xml:7-12` |
| Java | **17** | `pom.xml:22`, `:154-155` |
| Web / JPA / Security / Validation / AOP / Actuator | spring-boot-starter-* | `pom.xml:29-52` |
| WebSocket (STOMP) | spring-boot-starter-websocket | `pom.xml:54-57` |
| DB | PostgreSQL driver (runtime) | `pom.xml:60-64` |
| JWT | jjwt 0.11.5 (api/impl/jackson) | `pom.xml:24`, `:67-83` |
| MapStruct | 1.5.5.Final — **e'lon qilingan, lekin ishlatilmaydi** (`@Mapper` topilmadi) | `pom.xml:23`, `:86-90`, `:164-168` |
| Lombok | optional | `pom.xml:93-97` |
| Excel | Apache POI 5.2.5 (poi, poi-ooxml) | `pom.xml:118-127` |
| CSV | opencsv 5.9 — **e'lon qilingan, lekin ishlatilmaydi** (`com.opencsv` import yo'q) | `pom.xml:128-132` |
| Test | spring-boot-starter-test, spring-security-test, H2. Faqat 1 ta test fayli bor (`src/test/.../CrmApplicationTests.java`) | `pom.xml:100-115` |
| Kompilyator | `<parameters>true</parameters>`: `@Audited` SpEL argumentlarni nomi bilan o'qishi uchun | `pom.xml:156-157` |
| Flyway / Liquibase | **YO'Q**. README "Flyway" deydi (`README.md:15`), lekin sxemani `ddl-auto: update` quradi (§5.5) | `application.yml:26` |

Ilova klassi `CrmApplication.java:9-12`: `@EnableJpaAuditing`, `@EnableScheduling`, `@EnableAsync` (audit yozuvlari uchun).

### 1.2 springdoc-openapi
- **Yo'q.** `pom.xml` da `springdoc`, `swagger` yoki `openapi` bog'liqligi yo'q; `src/main/java` da ham hech qanday moslik topilmadi. `/v3/api-docs` manzili **mavjud emas**.
- **Qo'shish qancha ish (baho, QO'SHILMADI):**
  1. `org.springdoc:springdoc-openapi-starter-webmvc-ui` qo'shiladi. Spring Boot 3.2 bilan 2.3.x–2.5.x versiyalari mos (TAXMIN: versiya tanlovi mos kelish jadvaliga qarab tekshirilishi kerak).
  2. `SecurityConfig` da `/v3/api-docs/**` va `/swagger-ui/**` uchun `permitAll` yoki faqat SUPER_ADMIN qoidasi qo'shiladi (`config/SecurityConfig.java:66-199`, `anyRequest` dan oldin).
  3. Bearer JWT uchun `@SecurityScheme` / `OpenAPI` bean yoziladi.
  - **Minimal ishlaydigan holat: ~0.5 kun.**
- **Sifatli TS generatsiyasi uchun qo'shimcha ~2–4 kun:**
  - `Map<String,Object>` qaytaradigan ~15+ endpointga DTO yoki `@Schema` yozish kerak (§9.2).
  - `ResponseEntity<?>` bo'lgan joylar bor (masalan `POST /api/timetable`).
  - Envelope'siz javoblar bor (`TeacherKpiDto`, `RoomTimetableDto`).
  - Spring `Page` serializatsiyasi PageImpl'ning ichki shaklida chiqadi.
  - Enumlar erkin `String` sifatida kelgan joylar bor.
  - Bularsiz generator `any` / `object` tiplarini chiqaradi.
- **Tavsiya:** frontend tiplarini hozircha [api-inventory.md](api-inventory.md) asosida qo'lda yozish. springdoc esa alohida backend vazifasi sifatida rejalashtirilsin.

### 1.3 `application.yml` tuzilishi (qiymatsiz)
Fayl bitta: `src/main/resources/application.yml`. **Profillar yo'q**: `application-*.yml` fayllari ham, `spring.profiles` ham yo'q. Prod va dev sozlamalari bitta faylda turibdi.

| Kalit | Nima uchun | Qator |
|---|---|---|
| `spring.application.name` | nom | `:2-3` |
| `spring.servlet.multipart.max-file-size / max-request-size` | upload limiti (4MB / 8MB) | `:5-8` |
| `spring.datasource.*` (url, username, password, hikari.*) | PostgreSQL | `:10-21` |
| `spring.jpa.hibernate.ddl-auto` = `update`, `properties.hibernate.*` (batch, order) | sxema | `:23-35` |
| `server.port` (8080), `server.servlet.context-path` (`/`) | | `:37-40` |
| `app.audit.enabled`, `app.audit.retention-days` | audit (§2.5) | `:42-47` |
| `app.upload.dir` | fayllar papkasi (§8) | `:48-49` |
| `app.base-url` | (ishlatilmaydi — §8) | `:50` |
| `app.schema.drop-enum-checks` | `EnumCheckConstraintCleaner` | `:51-54` |
| `jwt.secret`, `jwt.expiration`, `jwt.refresh-expiration` | JWT (§2.2) | `:56-59` |
| `logging.*` | `com.crm: DEBUG` | `:61-66` |
| `management.endpoints.web.exposure.include` (health, info, metrics), `endpoint.health.show-details` | actuator | `:68-75` |
| `meta.*` (enabled, api-version, page-id, system-user-token, app-secret, verify-token, graph-base-url, verify-signature, task-assignee-user-id, page-token-ttl-hours, duplicate-window-days, request-timeout-seconds) | Meta Lead Ads (§6.2) | `:82-105` |
| `telegram.bot-token`, `telegram.enabled` | Telegram (§6.4) | `:107-109` |

> ⚠️ **Muhim:** `application.yml` git'da kuzatiladi (`git ls-files` tasdiqladi) va unda quyidagi sirlar **ochiq matnda** turibdi (qiymatlari bu yerga ko'chirilmadi):
> - DB paroli (`:13`) va izohga olingan yana bir sir (`:14`)
> - `jwt.secret` (`:57`)
> - `meta.system-user-token` (`:88`), `meta.app-secret` (`:89`), `meta.verify-token` (`:90`)
> - `telegram.bot-token` (`:108`)
>
> Bu frontendga bevosita ta'sir qilmaydi, lekin backend jamoasi uchun birinchi darajali xavf (§9.1).

**Jackson:** `spring.jackson.*` ham, maxsus `ObjectMapper` ham yo'q, ya'ni Spring Boot defaultlari ishlaydi:
- `LocalDate` → `"2026-09-29"`
- `LocalDateTime` → `"2026-09-29T10:15:30.123"` (zonasiz, server lokal vaqti)
- `Instant` → ISO `...Z`
- enum → `name()` (`@JsonValue` yo'q)
- `BigDecimal` → JSON number
- `null` maydonlar ham chiqadi (`"x": null`); istisno — `@JsonInclude(NON_NULL)` qo'yilgan joylar

**Vaqt zonasi:** `TimeZone.setDefault` ham, `hibernate.jdbc.time_zone` ham yo'q. Barcha `now()` va cron'lar JVM zonasida ishlaydi (§9.4).

---

## §2. Xavfsizlik

### 2.1 SecurityConfig (`config/SecurityConfig.java`)
- `@EnableWebSecurity`, `@EnableMethodSecurity` (`:34-35`). CSRF o'chiq (`:64`), sessiya STATELESS (`:217`), JWT filtri `UsernamePasswordAuthenticationFilter` dan oldin (`:219`), parollar BCrypt (`:256-259`).
- **Rollar** (`entity/enums/UserRole.java`): `SUPER_ADMIN, ADMIN, SALES_MANAGER, TEACHER, ACCOUNTANT, STUDENT, PARENT`. Authority `ROLE_<rol>` ko'rinishida (`security/CustomUserDetailsService.java:34`).
  - RoleHierarchy **yo'q**: SUPER_ADMIN ADMIN huquqlarini avtomatik olmaydi, har bir qoidada alohida sanaladi.
  - STUDENT va PARENT login qila oladi (login'da rol cheklovi yo'q), shuning uchun `authenticated()` bilan himoyalangan joylar ularga ham ochiq.
- **Qoidalar tartib bilan tekshiriladi, birinchi mos kelgani ishlaydi** (`:66-199`):

| # | Matcher | Ruxsat | Qator |
|---|---|---|---|
| 1 | `OPTIONS /**` | permitAll | `:68` |
| 2 | `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout` | **public** | `:73-77` |
| 3 | `/api/meta/webhook` | **public** (imzo bilan himoyalangan, §6.2) | `:84` |
| 4 | `GET /api/files/**` | **public** | `:85` |
| 5 | `POST /api/leads/public` | **public** | `:86` |
| 6 | `GET /api/settings/academic-year` | **public** | `:90` |
| 7 | `GET /api/settings/**` / boshqa `/api/settings/**` | authenticated / SA,A | `:91-92` |
| 8 | `/actuator/health` | **public** | `:93` |
| 9 | `/ws`, `/ws/**` | **public** (token handshake'da, §7) | `:98` |
| 10 | `GET /api/timetable/**`, `GET /api/classrooms/**`, `GET /api/courses/**`, `GET /api/exams/**` | SA,A,T | `:100-111` |
| 11 | `GET /api/groups/**` | SA,A,ACC,T | `:106-107` |
| 12 | `GET /api/notices/**` | authenticated | `:112` |
| 13 | `POST /api/students/*/transfer-group` | SA,A | `:114-115` |
| 14 | `/api/dashboard/**`, `/api/analytics/**` | SA,A | `:118-121` |
| 15 | `/api/finance/**`, `/api/expenses/**`, `/api/payroll/**`, `/api/cash-registers/**` | SA,A,ACC | `:122-127`, `:132-133` |
| 16 | `/api/salary-rules/**` | **faqat SA** | `:128-129` |
| 17 | `/api/payments/**` | SA,A,SM,ACC | `:130-131` |
| 18 | `/api/leads/**`, `/api/tasks/**` | SA,A,SM | `:134-137` |
| 19 | `/api/meta/**` | SA,A | `:141-142` |
| 20 | `/api/lead-stages/**` | authenticated (yozish `@PreAuthorize` bilan cheklangan) | `:146` |
| 21 | `/api/promotions/**`, `/api/users/**`, `/api/import/**`, `/api/roles/**` | SA,A | `:147-156` |
| 22 | `/api/parents/**` | SA,A,ACC | `:149-150` |
| 23 | `/api/admin/**` | **faqat SA** | `:157-158` |
| 24 | `GET /api/students/**` / boshqa `/api/students/**` | SA,A,SM,ACC,T / SA,A,SM,ACC | `:160-163` |
| 25 | `/api/attendance/**`, `/api/homework/**`, `POST/PUT /api/exams/**` | SA,A,T | `:165-172` |
| 26 | `/api/teachers/me/**`, `/api/teacher/**` | T | `:173-176` |
| 27 | `/api/classrooms/**` (yozish) | SA,A | `:179-180` |
| 28 | `POST /api/notices/read-all`, `POST /api/notices/*/read` / boshqa POST/PUT notices | authenticated / SA,A | `:183-188` |
| 29 | `/api/chat/**`, `/api/search/**`, `POST /api/files/**` | authenticated | `:192-194` |
| 30 | `DELETE /api/**` | SA,A (faqat yuqoridagi qoidalarga tushmagan DELETE'lar uchun!) | `:196-197` |
| 31 | `anyRequest()` | authenticated | `:199` |

- **URL qoidasi yo'q modullar** (ular faqat `anyRequest().authenticated()` va `@PreAuthorize` ga tayanadi):
  - `/api/teachers/**` (`/me` dan tashqari)
  - `/api/leaves`, `/api/bonus-penalties`, `/api/audit-logs`, `/api/contracts`, `/api/marketing`
  - `/api/classes`, `/api/sections`, `/api/subjects`, `/api/enums`
  - `/api/auth/me|profile|change-password`

  Ulardan `@PreAuthorize` ham yo'q bo'lganlari **7 ta rolning hammasiga ochiq** (§9.1).
- **`@PreAuthorize`** metod yoki klass darajasida keng ishlatilgan. Effektiv ruxsat = URL qoidasi ∩ `@PreAuthorize`. Har bir endpoint uchun effektiv rollar [api-inventory.md](api-inventory.md) da ko'rsatilgan.
- **TEACHER scope** (egalik) servis darajasida `service/TeacherAccessService.java` da tekshiriladi:
  - `assertOwnsGroup` `:82-92`
  - `assertOwnsStudent` `:94-102`
  - `resolveTeacherScope` `:66-71`

  Bu tekshiruv hamma joyda chaqirilmaydi (§9.1).
- **401 / 403 javoblari** (filtr darajasi): `401 {success:false, message:"Avtorizatsiya talab qilinadi"}` (`:202-208`), `403 {success:false, message:"Ruxsat yo'q"}` (`:209-215`). Bu matnlar i18n qilinmagan, qattiq yozilgan.
- **Actuator:** `/actuator/health` public; `info` va `metrics` exposed (`application.yml:72`), ammo URL qoidasi `anyRequest().authenticated()`. Shuning uchun istalgan rol (STUDENT ham) `/actuator/metrics` ni o'qiy oladi (TAXMIN: bu ataylab qilinmagan).

### 2.2 JWT
| Narsa | Qiymat | Manba |
|---|---|---|
| Algoritm | HS256, kalit `jwt.secret` (Base64 decode) | `security/jwt/JwtUtils.java:51-60`, `:84-87` |
| **Access muddati** | `jwt.expiration` = 28 800 000 ms = **8 soat** (yml izohida xato yozilgan: "24 hours") | `application.yml:58` |
| **Refresh muddati** | `jwt.refresh-expiration` = 604 800 000 ms = **7 kun** | `application.yml:59`, `service/AuthService.java:85`, `:139` |
| **Claim'lar** | faqat `sub` (= username), `iat`, `exp`. **rol, userId, teacherId claim'lari YO'Q**; `generateToken(userDetails)` bo'sh `extraClaims` bilan chaqiriladi | `JwtUtils.java:39-60`, `service/AuthService.java:77`, `:135` |
| Tekshirish | Har so'rovda user bazadan yuklanadi (`loadUserByUsername`), nofaol user darhol 401 oladi. Rol ham DB'dan olinadi, shuning uchun rol o'zgarishi darhol kuchga kiradi | `security/jwt/JwtAuthenticationFilter.java:36-64`, `security/CustomUserDetailsService.java:23-36` |
| Yaroqsiz yoki muddati o'tgan token | Filtr xatoni yutadi (`log.error`), so'rov anonim holda davom etadi va himoyalangan yo'lda 401 oladi | `JwtAuthenticationFilter.java:58-60` |
| Refresh token | **JWT emas**: tasodifiy UUID, `refresh_tokens` jadvalida saqlanadi (`entity/RefreshToken.java`). `JwtUtils.generateRefreshToken` ishlatilmaydi | `service/AuthService.java:79-88` |

**Endpointlar** (`controller/AuthController.java`), javoblar `ApiResponse<AuthResponse>` ichida:

| Endpoint | Qator | Request | Nima qiladi |
|---|---|---|---|
| `POST /api/auth/login` | `:37-41` | `{username, password}` (`@NotBlank`) | Muvaffaqiyatli bo'lsa **userning barcha eski refresh tokenlarini o'chiradi** (`AuthService.java:80`), ya'ni bir vaqtda bitta sessiya. Audit: `LOGIN` / `LOGIN_FAILED` |
| `POST /api/auth/refresh` | `:43-48` | `{refreshToken}` | **Rotation**: shu DB yozuvidagi token yangi UUID bilan almashtiriladi, eski token o'ladi (`AuthService.java:137-140`). Noto'g'ri yoki revoke qilingan token → 401. Muddati o'tgan → revoke + 401 |
| `POST /api/auth/logout` | `:50-55` | `{refreshToken}` | Faqat refresh tokenni revoke qiladi; access token muddati tugaguncha ishlayveradi. Token topilmasa ham 200 |
| `GET /api/auth/me` | `:57-62` | — | `UserResponse {id, username, email, firstName, lastName, phone, role, isActive, lastLogin, createdAt, photoUrl}`. **teacherId yo'q**: o'qituvchi portali `/api/teachers/me/**` va `/api/teacher/dashboard` dan foydalanadi |

**`AuthResponse`** (`dto/response/AuthResponse.java`): `{accessToken, refreshToken, tokenType:"Bearer", userId, username, firstName, lastName, role, expiresIn}`.
- ⚠️ `expiresIn` **doim 86400** qaytadi (qattiq yozilgan: `service/AuthService.java:99`, `:151`), haqiqiy muddat esa 8 soat.
- Frontend `exp` ni JWT'dan o'qishi yoki 401 da refresh qilishi kerak.

**Frontend oqimi (tavsiya):**
1. `Authorization: Bearer <accessToken>` sarlavhasini yuboring.
2. 401 kelsa bir marta `/api/auth/refresh` chaqiring (bir vaqtda faqat bitta refresh — mutex; rotation sababli parallel refresh'lardan biri 401 oladi).
3. Refresh ham 401 bo'lsa → login sahifasi.

Tokenlar cookie'da emas, faqat JSON body'da keladi. Tokenni qayerda saqlash frontend qarori.

### 2.3 CORS
- **Yagona joy:** `config/SecurityConfig.java:45-55` dagi `public static final List<String> ALLOWED_ORIGIN_PATTERNS`. Ro'yxatda:
  - `https://admin.adizone.uz`, `https://adizone.uz`, `https://www.adizone.uz`
  - `https://*.vercel.app`
  - `http://localhost:3000`, `http://localhost:5173`, `http://localhost:5174`
  - `http://127.0.0.1:5173`, `http://127.0.0.1:3000`
- **Konfiguratsiya** (`:224-241`):
  - `allowedOriginPatterns` = shu ro'yxat
  - methods: GET, POST, PUT, DELETE, PATCH, OPTIONS
  - headers: `*`
  - exposed headers: `Authorization`, `Content-Type` (`Content-Disposition` **exposed emas**, §9.2)
  - `allowCredentials(true)`, `maxAge` 3600
- **WebSocket** ham shu ro'yxatni ishlatadi: `config/WebSocketConfig.java:43-45`, `setAllowedOriginPatterns(SecurityConfig.ALLOWED_ORIGIN_PATTERNS...)`.
- **`app.adizone.uz` qo'shish uchun:** ro'yxatga bitta qator (`"https://app.adizone.uz"`) qo'shiladi (`SecurityConfig.java:45-55`), shunda REST va WS ikkalasi ham avtomatik qamraladi. Env yoki yml orqali sozlash imkoni **yo'q**: har o'zgarish kod va deploy talab qiladi. Vite dev porti 5173/5174 allaqachon ro'yxatda.
- **E'tibor:** `https://*.vercel.app` + `allowCredentials(true)` — istalgan Vercel sayti credentials bilan so'rov yubora oladi. Auth cookie emas, Bearer bo'lgani uchun xavfi past, lekin ro'yxat keragidan keng (§9.1).

### 2.4 Rate limit
**Yo'q.** `RateLimit`, `Bucket4j`, `throttle`, `loginAttempt`, `lockout`, `resilience4j`, `captcha` bo'yicha grep hech narsa topmadi. Natijada:
- login brute-force cheklanmaydi (faqat `LOGIN_FAILED` audit yoziladi — `service/AuthService.java:104-118`);
- `POST /api/leads/public` (spam) va `/api/meta/webhook` cheklanmaydi.

Frontend 429 kodini kutmasligi mumkin.

### 2.5 Audit (`@Audited`)
- **Mexanizm** (`audit/`):
  - Annotatsiya: `Audited.java:22-41` (action, entity, SpEL: summary / entityId / label).
  - Aspect: `AuditAspect.java:70-106`, faqat `app.audit.enabled=true` bo'lsa. Exception bilan tugagan metod yozilmaydi. Yozuv tranzaksiya `afterCommit` da qilinadi.
  - Yozuvchi: `AuditRecorder.java:31-45` (`@Async` + `REQUIRES_NEW`).
  - Retention: `AuditRetentionJob.java:22-34`, har kuni 03:30, `retention-days` = 90.
- **Qamrov:** **54 ta metod**. 53 tasi servislarda, 1 tasi controllerda (`controller/UserController.java:178`). To'liq ro'yxat §6.5 da.
  - Qamralgan domenlar: Lead, LeadStage, Task, Student, Group / StudentGroup, Payment, Payroll, CashRegister, Attendance, User, Notice (delete), ExamResult, Balance, Import.
  - **Audit qilinmaydigan amallar:** LeadNote, Meta sozlamalari, chat, fayl yuklash, `change-password`, o'qituvchi importi, lid eksporti (`AuditAction.EXPORT` e'lon qilingan, lekin ishlatilmaydi).
- **O'qish endpointlari** (`controller/AuditLogController.java`):
  - `GET /api/audit-logs` — SA. Filtrlar: `from`, `to`, `userId`, `action`, `entityType`, `entityId`, `q`, `page`, `size` (50, max 200).
  - `GET /api/audit-logs/entity/{type}/{id}` — SA, A.
  - `GET /api/audit-logs/filters` — SA.

---

## §3. API inventarizatsiya (xulosa)

To'liq jadval (METHOD, path, controller#metod, effektiv rollar, request/response DTO maydonlari va validatsiya, pagination, izoh) **[api-inventory.md](api-inventory.md)** da. Bu yerda faqat umumiy ko'rinish.

| Modul guruhi | Controllerlar | REST yo'llar |
|---|---|---|
| Auth / foydalanuvchilar / o'qituvchilar / o'quvchilar | Auth 7, User 12, Role 1, Teacher 18, TeacherDashboard 1, Student 25, Parent 9, Settings 1, Enum 3, Search 1 | **78** |
| O'quv jarayoni | Group 13, Course 5, Classroom 5, Academic 25 (classes 5, sections 5, subjects 5, timetable 10), Attendance 5, AttendanceUnlockRequest 6, Homework 9, Exam 13, Notice 10, Leave 8, Promotion 6, Contract 12 | **117** |
| CRM / lidlar / analitika / tizim | Lead 21, LeadStage 5, LeadImport 4, Import 4, MetaAdmin 11, MetaWebhook 2, Marketing 1, Task 10, Dashboard 1, Analytics 8 yo'l (7 metod), AuditLog 3, AdminRepair 8 | **78** |
| Moliya / fayl / chat | Payment 11, Finance 3, Expense 2, CashRegister 12, Payroll 10, SalaryRule 4, BonusPenalty 10, File 2, Chat 11 | **65** |
| **Jami REST** | 43 controller | **338 yo'l** (337 handler metod) |
| WebSocket (STOMP) | ChatSocketController: 5 ta `@MessageMapping` + 2 server topik (§7) | — |

**Frontend uchun umumiy konventsiyalar:**
- Hamma yo'llar `/api/...` bilan boshlanadi; `context-path` `/` (`application.yml:40`).
- ID lar `Long`. URL'larda `id` ishlatiladi; `uuid` faqat javobda keladi.
- Sahifalash **0 dan** boshlanadi (`page`, `size`). **`sort` parametri hech qayerda qabul qilinmaydi**: tartib serverda qattiq belgilangan, odatda `createdAt DESC`. `size` uchun yuqori chegara ko'p joyda yo'q.
- Telefon maydonlari `PhoneDeserializer` (`config/PhoneDeserializer.java:26-33`) orqali kanonik `+998XXXXXXXXX` shakliga keltiriladi (`util/PhoneUtils.java:31-50`). Frontend istalgan formatni yuborishi mumkin.
- Til `Accept-Language` sarlavhasidan olinadi: `uz` (default), `ru`, `en` (§4.3).
- Eksport va shablon endpointlari `byte[]` qaytaradi, envelope'siz (CSV yoki XLSX, `Content-Disposition: attachment`).

---

## §4. Javob va xato formati

### 4.1 Muvaffaqiyatli javob shakllari
| # | Shakl | Qayerda | Misol |
|---|---|---|---|
| S1 | **`ApiResponse<T>`** `{success, message, data, meta?}` (`dto/response/ApiResponse.java:4-28`). `meta` faqat NON_NULL bo'lsa chiqadi; `message` esa `null` bo'lib ham keladi | Deyarli barcha endpointlar | `{"success":true,"message":"Student created","data":{...}}` |
| S2 | `ApiResponse<PageResponse<T>>`. `PageResponse` = `{content, pageNumber, pageSize, totalElements, totalPages, last}` (`dto/response/PageResponse.java:5-12`) | students, teachers/search, parents, leads, tasks, lead comments, payroll, bonus-penalties, contracts, homework, exams, notices, leaves, meta events, audit-logs va boshqalar | `{"success":true,"data":{"content":[...],"pageNumber":0,"pageSize":20,"totalElements":57,"totalPages":3,"last":false}}` |
| S3 | `ApiResponse<Page<T>>`: Spring `PageImpl` to'g'ridan-to'g'ri serializatsiya qilinadi (Boot 3.2 → `{content, pageable{...}, totalElements, totalPages, last, first, size, number, numberOfElements, sort{...}, empty}`) | `GET /api/payments`, `/api/finance/expenses`, `/api/expenses`, `/api/cash-registers/{id}/transactions` | `data.number` / `data.size` (S2 da esa `pageNumber` / `pageSize`) |
| S4 | `ApiResponse<T>` + **`meta`** aggregat | faqat `GET /api/payments` (`controller/PaymentController.java:48-54`) | `{"success":true,"data":{...page},"meta":{...jami summalar}}` |
| S5 | **Envelope'siz** DTO | `GET /api/teachers/{id}/kpi`, `GET /api/teachers/me/kpi` (`controller/TeacherController.java:44-89`), `GET /api/timetable/by-room` (`controller/AcademicController.java:141-144`) | `{"teacherId":5,"teacherName":"...",...}` |
| S6 | Maxsus sahifa: `ApiResponse<LeadTimelineResponse>` → `{items, page: PageInfo, openTasks}` | `GET /api/leads/{id}/timeline` (`dto/response/LeadTimelineResponse.java:24-42`) | |
| S7 | Kursorli ro'yxat `ApiResponse<List<T>>` (`?before=` / `?around=`) | chat tarixi (`controller/ChatController.java:68-77`) | |
| S8 | `ApiResponse<Map<String,Object>>` (DTO yo'q) | `/api/teacher/dashboard`, `*/stats`, `/api/search`, `/api/students/trial`, `/{id}/history`, analytics grafiklari va boshqalar (§9.2) | |
| S9 | `byte[]` fayl (CSV/XLSX) yoki `Resource` | export / template endpointlari, `GET /api/files/{name}` | |
| S10 | `text/plain` | `GET/POST /api/meta/webhook` (Meta uchun, frontend uchun emas) | |

Boshqa odatlar:
- Create odatda **201**, lekin istisnolar bor: `POST /api/payroll/generate`, `/api/files/upload`, `/api/chat/upload`, `/api/chat/conversations/direct` → 200.
- DELETE **200** va `ApiResponse<Void>` qaytaradi (204 emas).
- `POST /api/users*`: username band bo'lsa **200 + `message:"ALREADY_EXISTS"`** qaytadi (409 emas, `controller/UserController.java:49-59`).

### 4.2 Xato shakllari (`exception/GlobalExceptionHandler.java`)
Xatolar **uch xil JSON shaklda** keladi:
- **E1** `ErrorResponse {timestamp, status, error, message, validationErrors?}` (`exception/ErrorResponse.java:12-20`, NON_NULL)
- **E2** `ApiResponse {success:false, message}`
- **E3** `Map {timestamp, status, [error], message, [path]}`

| Exception | Status | Shakl | Qator | Izoh |
|---|---|---|---|---|
| `ResourceNotFoundException` | 404 | E1 | `:35-38` | |
| `BadRequestException` | 400 | E1 | `:40-43` | |
| `IllegalArgumentException` | 400 | E1 | `:45-49` | masalan CASH_AND_CARD taqsimoti, manfiy `page` |
| `DuplicateResourceException` | 409 | E1 | `:51-54` | |
| `UnauthorizedException` | 401 | E2 | `:56-60` | masalan refresh token noto'g'ri |
| `AccessDeniedException` (`@PreAuthorize`) | 403 | E2 `"Ruxsat yo'q"` | `:62-66` | |
| `ForbiddenException` (servis, masalan TEACHER scope) | 403 | E2 | `:68-72` | |
| `BadCredentialsException` | 401 | E2 `"Invalid username or password"` (inglizcha, i18n emas) | `:74-78` | |
| `AuthenticationException` | 401 | E2 | `:80-84` | |
| `MaxUploadSizeExceededException` | 400 | E1, `chat.upload.tooLarge` xabari | `:91-95` | 413 emas |
| `MultipartException` | 400 | E1, "Excel (.xlsx) yuboring…" + ichki matn | `:97-102` | rasm upload uchun ham shu chalg'ituvchi matn chiqadi |
| `MissingServletRequestPartException` | 400 | E1, "…student import uchun 'file' kerak" | `:104-108` | |
| `MethodArgumentNotValidException` (`@Valid`) | 400 | E1 + **`validationErrors: {maydon: xabar}`**, `error` va `message` = `error.validationFailed` | `:110-128` | xabarlar tarjima qilingan |
| `jakarta.persistence.EntityNotFoundException` | 404 | **E3** `{timestamp, status, message}` | `:130-137` | |
| `MethodArgumentTypeMismatchException` | 400 | E1, `error.typeMismatch` | `:139-144` | masalan `?status=XYZ` enum param |
| `HttpMessageNotReadableException` | 400 | E1, `error.malformedRequest` | `:146-151` | buzuq JSON yoki noto'g'ri enum qiymati body'da |
| `NoResourceFoundException` | 404 | E2, `error.endpointNotFound` | `:160-167` | |
| `HttpRequestMethodNotSupportedException` | 405 | E2, `error.methodNotAllowed` | `:170-179` | |
| **Boshqa har qanday** `Exception` | 500 | **E3** `{timestamp, status, error:"Internal Server Error", message: error.unexpected, path}` | `:185-195` | |
| Filtr darajasidagi 401 / 403 | 401 / 403 | E2 (`config/SecurityConfig.java:201-215`) | | |

**Handler'i yo'q, shuning uchun 500 bo'lib ketadigan holatlar** (frontend 400 kutadi, lekin 500 oladi):
- `MissingServletRequestParameterException`: majburiy query param yuborilmasa. Masalan `q` (`/api/students/search`, `/api/teachers/search`, `/api/search`), `groupId` (`/api/attendance/missing`), `status` (`PATCH /api/groups/{id}/status`).
- `DateTimeParseException`: sana `String` sifatida qabul qilinib `LocalDate.parse` qilinadigan joylarda (payments, cash-registers, bonus summary).
- `DataIntegrityViolationException`: masalan chek raqami to'qnashuvi.

Frontend uchun tavsiya: xato matnini quyidagi tartibda o'qish — `body.message ?? body.error`, maydon xatolari uchun `body.validationErrors`.

### 4.3 i18n
- `config/MessageConfig.java:22-58`:
  - `ReloadableResourceBundleMessageSource`, basename `classpath:messages`
  - default locale **uz**, `fallbackToSystemLocale=false`, `useCodeAsDefaultMessage=true` (kalit topilmasa kalit nomi qaytadi)
  - validator MessageSource'ga ulangan (`@NotBlank(message="{kalit}")` tarjima qilinadi)
  - `AcceptHeaderLocaleResolver`: qo'llanadigan tillar `uz`, `ru`, `en`; default `uz`
- Fayllar: `messages.properties` (uz), `messages_ru.properties`, `messages_en.properties`, har biri 324 qator.
- Servislarda tarjima `config/Messages.java:24-26` → `messages.get(key, args)` orqali olinadi.
- **Qamrov to'liq emas:** ~80 ta `messages.get(...)` chaqiruviga qarshi 115+ ta `new BadRequestException("...")` va 80+ ta `new ResourceNotFoundException("...")` qattiq yozilgan matn bilan bor (o'zbekcha yoki inglizcha aralash).
  - Muvaffaqiyat xabarlari (`"Student created"`, `"Kassa yaratildi"`) ham tarjima qilinmaydi.
  - 401/403 matnlari ham qattiq yozilgan.
  - **Natija:** `Accept-Language: ru` faqat validatsiya xabarlari va ba'zi servis xatolari uchun ishlaydi. UI matnlari uchun frontend o'z i18n'iga tayanishi kerak, backend `message` ni esa faqat "xato tafsiloti" sifatida ko'rsatish kerak.

---

## §5. Domen modeli

Yo'llar `src/main/java/com/crm/` ga nisbatan. Migratsiyalar: `src/main/resources/db/migration/`.

### 5.0 Umumiy konventsiyalar

- **ID**: barcha entity'larda `Long id` (`GenerationType.IDENTITY`, PostgreSQL `BIGSERIAL`). Ko'pchilikda qo'shimcha `uuid` bor:
  - `UUID` turi (`@UuidGenerator`): Course, Exam, Expense, Group, Homework, Income, Lead, LeadNote, LeadStage, Leave, Message, MessageAttachment, Notice, Parent, Payment, Payroll, Student, Task, Teacher, User, Conversation.
  - `String(36)` turi (`@PrePersist` da `UUID.randomUUID()`): BonusPenalty, CashRegister, CashTransaction, Contract, ContractTemplate.
  - Frontend URL'larida hamma joyda **`id` (Long)** ishlatiladi (masalan `/api/leads/{id:\d+}`), `uuid` faqat javobda keladi — **TAXMIN** (controller'lar §API bo'limida).
- **BaseEntity** (`entity/BaseEntity.java:14-25`): `@MappedSuperclass` + `AuditingEntityListener` → `createdAt` (`created_at`, NOT NULL, updatable=false) va `updatedAt` (`updated_at`). Meros oluvchilar: ClassEntity, Classroom, Conversation, Course, Exam, ExamResult, Group, Homework, HomeworkSubmission, LeadNote, LeadStage, Leave, Notice, Parent, Payroll, Promotion, Section, Student, Subject, Task, Teacher, Timetable, User.
  Qolganlari `createdAt/updatedAt` ni o'zi `@PrePersist/@PreUpdate` da `LocalDateTime.now()` bilan to'ldiradi (bir xil semantika).
- **Soft-delete**: yagona `deleted` flag YO'Q. Har bir entity o'zicha: `isActive` (Boolean), `archived`, `status = LEFT/CANCELLED/INACTIVE/ARCHIVED`, `Message.deletedAt`, `ConversationParticipant.leftAt`, `StudentGroup.isActive=false + leaveDate`. Ba'zilari **hard delete** (Task, Contract, ContractTemplate, Leave, BonusPenalty(PENDING), LeadStage, CashTransaction(expense), LeadNote).
- **Pul**: `BigDecimal` (`precision 12, scale 2`; kassa/bonus 15,2). JSON'da raqam.
- **Sana**: `LocalDate` / `LocalDateTime` (timezone'siz); Meta jadvallarida `Instant`.
- Bog'lanishlar deyarli hammasi `@ManyToOne(fetch = LAZY)`; `@OneToMany` faqat bir nechta joyda (pastda). `@ManyToMany` **umuman yo'q** (M:N lar oraliq entity orqali: StudentGroup, StudentParent, ConversationParticipant, NoticeRead).

### 5.1 Entity'lar (55 fayl: 54 entity + BaseEntity)

Belgilar: **B** = BaseEntity dan meros; `→X` = `@ManyToOne X`; `U(...)` = unique constraint.

#### O'quv jarayoni (asosiy)

| Entity (fayl:qator) | Jadval | Asosiy maydonlar | Bog'lanishlar / constraintlar |
|---|---|---|---|
| Student `entity/Student.java:16-23` **B** | `students` | firstName, lastName (NOT NULL), phone (32, NOT NULL), parentPhone, birthDate, gender(String), marketingSource (`MarketingSource`, def OTHER :54), **status** (`StudentStatus`, NOT NULL, def ACTIVE :63), notes, photoUrl, address, admissionNumber, admissionDate, convertedFromLeadId (Long, FK emas :81), attributedUserId (Long :89), paymentStartDate, nextPaymentDate, monthlyFee, **balance** (def 0 :106), **paymentStatus** (`PaymentStatus`, def PENDING :112) | →Student referralStudent (:58), →User createdBy (:85); `@OneToMany(mappedBy="student", cascade=ALL)` studentGroups, payments, attendances (:114-124). Phone unique EMAS (servisda tekshiriladi) |
| StudentGroup `entity/StudentGroup.java:13-20` | `student_groups` | joinDate (NOT NULL), leaveDate, nextPaymentDate, paymentStartDate, studyFormat (`StudyFormat`, converter :51-53), isTrial, **isActive** (:61), discountPercentage, monthlyPriceOverride, paymentType (`PaymentType`, def MONTHLY :73), lessonPrice, lessonsPurchased, lessonsUsed, balance, notes, lessonsAttended, firstLessonDate, lastPaymentDate, nextPaymentDue, **paymentStatus (String!** def "PENDING" :113), suspendedAt, suspensionReason, exitReason (String 50 :123), exitDate, exitNotes | →Student (NOT NULL :28), →Group (NOT NULL :32). (student,group) unique **YO'Q** — bir o'quvchi bir guruhga qayta qo'shilsa yangi qator |
| Group `entity/Group.java:13-20` **B** | `groups` | groupName (NOT NULL), room (String 100), maxStudents (def 20), currentStudents (def 0), startDate (NOT NULL), endDate, **status** (`GroupStatus`, def ACTIVE :64), notes | →Course (NOT NULL :35), →Teacher (:39), →Classroom (:43); `@OneToMany(mappedBy="group", cascade=ALL)` schedules→GroupSchedule (:69), `@OneToMany(mappedBy="group")` studentGroups (:73) |
| GroupScheduleDay `entity/GroupScheduleDay.java:8-15` | `group_schedule_days` | dayOfWeek (**String** 20), startTime/endTime (**String** 10), createdAt | →Group (NOT NULL), →Classroom room (:36). Migratsiyada U(group_id, day_of_week) — `V25__timetable_days.sql:12` (entity'da yo'q) |
| GroupSchedule `entity/GroupSchedule.java:9-16` | `group_schedules` | dayOfWeek (`java.time.DayOfWeek` STRING), startTime/endTime (`LocalTime`) | →Group. **Ishlatilmaydi** (Muammolar) |
| Course `entity/Course.java:10-17` **B** | `courses` | courseName, description, durationMonths, lessonsCount, monthlyPrice (NOT NULL), lessonPrice, isActive | — |
| Attendance `entity/Attendance.java:10-18` | `attendance` | attendanceDate, **status** (`AttendanceStatus`, def PRESENT :38), notes, **excused** (Boolean :49), excuseReason, createdAt/updatedAt | →Student, →Group (NOT NULL), →User markedBy. **U(student_id, group_id, attendance_date)** (:12) |
| AttendanceUnlockRequest `entity/AttendanceUnlockRequest.java:9-16` | `attendance_unlock_requests` | attendanceDate, **status** (`UnlockRequestStatus`, def PENDING :36), reviewedAt, teacherNote, createdAt | →Teacher (NOT NULL), →Group (NOT NULL), →User reviewedBy |
| Classroom `entity/Classroom.java:6-13` **B** | `classrooms` | roomName, roomNumber, capacity, floor, roomType(String), description, isActive | — |
| Timetable `entity/Timetable.java:8-11` **B** | `timetable` | subjectName, dayOfWeek (**String** 10), startTime/endTime (LocalTime), academicYear | →Group, →ClassEntity, →Section, →Subject, →Teacher, →Classroom |
| Exam `entity/Exam.java:12-15` **B** | `exams` | examName, examType(String), examDate, startTime, endTime, totalMarks, passMarks, academicYear, isActive | →ClassEntity, →Group, →Subject, →Teacher |
| ExamResult `entity/ExamResult.java:9-13` **B** | `exam_results` | marksObtained, grade, remarks, isPassed, editNote, editedAt | →Exam, →Student (NOT NULL), →User editedBy. **U(exam_id, student_id)** |
| ExamRegistration `entity/ExamRegistration.java:10-17` | `exam_registrations` | paymentStatus (**String** def "PENDING" :33), amountDue, amountPaid, registrationDate, status (**String** def "REGISTERED" :48), notes | →Exam, →Student. U(exam_id, student_id) faqat `V27__exam_registrations.sql:12` da |
| Homework `entity/Homework.java:11-14` **B** | `homeworks` | title, description, assignedDate, dueDate (NOT NULL), marks, isActive | →Subject, →ClassEntity, →Group, →Teacher |
| HomeworkSubmission `entity/HomeworkSubmission.java:9-13` **B** | `homework_submissions` | submittedAt, fileUrl, remarks, marksObtained, status (**String** def "PENDING" :41) | →Homework, →Student. **U(homework_id, student_id)** |
| ClassEntity `entity/ClassEntity.java:9-12` **B** | `classes` | className, classCode, isActive | `@OneToMany(mappedBy="classEntity", cascade=ALL)` sections, subjects (:28-34) — maktab modelidan meros |
| Section `entity/Section.java:6-9` **B** | `sections` | sectionName, room, maxStudents (30), isActive | →ClassEntity, →Teacher |
| Subject `entity/Subject.java:6-9` **B** | `subjects` | subjectName, subjectCode, isActive | →ClassEntity, →Teacher |
| Promotion `entity/Promotion.java:8-11` **B** | `promotions` | from/toAcademicYear, source/targetMonth/Year, promotionDate, remarks | →Student, →ClassEntity from/toClass, →Section from/toSection, →User promotedBy |
| Parent `entity/Parent.java:11-18` **B** | `parents` | fullName, phone, telegramChatId, address, relation (**String** def "OTHER" :42), isActive | `@OneToMany(mappedBy="parent", cascade=ALL)` studentParents (:48) |
| StudentParent `entity/StudentParent.java:6-10` | `student_parents` | relation (String), isPrimary | →Student, →Parent. **U(student_id, parent_id)** |
| StudentStatusHistory `entity/StudentStatusHistory.java:9-16` | `student_status_history` | fromStatus/toStatus (**String** 50), reason (100), notes, balanceSnapshot (:45), metaJson (:49), changedAt | →Student (NOT NULL), →User changedBy (:53) |
| StudentPaymentPlan `entity/StudentPaymentPlan.java:10-17` | `student_payment_plans` | planStartDate, planEndDate, monthlyAmount, dueDay (1), status (String "ACTIVE"), notes | →Student, →Group. **Ishlatilmaydi** |

#### Moliya

| Entity | Jadval | Asosiy maydonlar | Bog'lanishlar / constraintlar |
|---|---|---|---|
| Payment `entity/Payment.java:14-21` | `payments` | amount (NOT NULL), paymentDate, paymentMethod (`PaymentMethod`, def CASH :56), **status** (`PaymentStatus`, def PAID :60), periodStart→ustun `period_from` (:64), periodEnd→`period_to` (:68) + `@Transient getPeriodFrom/To` aliaslar (:110-126), description, discountAmount, bonusDiscount, **receiptNumber (U, 32 :82)**, notes, balanceUsed, payableAmount, cashAmount | →Student (NOT NULL), →Group, →StudentGroup, →CashRegister, →User receivedBy |
| BalanceTransaction `entity/BalanceTransaction.java:10-21` | `balance_transactions` | **type** (`BalanceTransactionType` :37), amount (ishorali), balanceAfter, referenceId (Long, polimorf), note, createdAt | →StudentGroup (nullable), →Student (NOT NULL), →User createdBy. Ledger — faqat INSERT |
| CashRegister `entity/CashRegister.java:13-18` | `cash_registers` | name, plasticBalance, cashBalance, balance, **status** (`CashRegisterStatus` def ACTIVE :45), acceptOnlinePayment, **archived** (boolean :50) | →User moderator |
| CashTransaction `entity/CashTransaction.java:16-21` | `cash_transactions` | **type** (`CashTransactionType` :36), paymentMethod, transactionName, amount, cashPart, cardPart, note, **status** (`CashTransactionStatus` def COMPLETED :69), periodMonth, totalAmount, transactionDate | →CashRegister (NOT NULL), →CashRegister targetCashRegister (TRANSFER), →Student, →Teacher, →User createdBy |
| Income `entity/Income.java:13-16` | `income` | category (`IncomeCategory`), amount, description, incomeDate, notes | →Payment, →User receivedBy |
| Expense `entity/Expense.java:13-20` | `expenses` | category (`ExpenseCategory`), title, amount, expenseDate, description, receiptUrl, notes | →Teacher, →User approvedBy, →CashRegister |
| Payroll `entity/Payroll.java:12-15` **B** | `payroll` | month, year, basicSalary, allowances, deductions, netSalary, bonusPenaltyAdjustment, paidStudentCount, newStudentCount, kpiApplied, kpiAmount, calculationDetails(TEXT), paymentDate, paymentMethod, **status (String** def "PENDING" :84), notes | →Teacher, →User user, →User createdBy, →CashRegister. U(user_id, month, year) WHERE user_id NOT NULL — faqat `V40__salary_rules_and_attribution.sql:61` |
| SalaryRule `entity/SalaryRule.java:11-18` | `salary_rules` | role (`UserRole` :26), baseSalary, perStudentFee, newStudentBonus, kpiThreshold, kpiBonus, isActive, effectiveFrom | →User user (nullable = rol bo'yicha umumiy qoida) |
| BonusPenalty `entity/BonusPenalty.java:16-21` | `bonus_penalties` | kind (`BonusPenaltyKind`), targetType (`BonusTargetType`), amount, reason, **status** (`BonusPenaltyStatus` def PENDING :54), appliedToPayrollId, appliedToPaymentId (Long, FK emas), effectiveDate | →Student, →Teacher, →User createdBy |
| Contract `entity/Contract.java:14-19` | `contracts` | **contractNumber (U, 32 :29)**, type (`ContractType`), renderedContent, **status** (`ContractStatus` def DRAFT :48), offerAccepted, acceptedAt, contractDate | →Student (NOT NULL), →ContractTemplate (NOT NULL :37) |
| ContractTemplate `entity/ContractTemplate.java:12-17` | `contract_templates` | title, type (`ContractType` def OFFLINE), content(TEXT, placeholder'li), isDefault | — |

#### Lid / CRM

| Entity | Jadval | Asosiy maydonlar | Bog'lanishlar / constraintlar |
|---|---|---|---|
| Lead `entity/Lead.java:11-18` | `leads` | fullName, phone (50), parentPhone, address, course (**String**), format (**String** def "OFFLINE"), **status (String 50** = `lead_stages.code`, def "NEW" :21,63), source (**String** 30, def "WEBSITE" :67), amount (nullable :77), notes, **converted** (Boolean :84), assignedAt, importBatch, **metaLeadgenId (U :117)**, metaFormId, metaRawJson | →Student (:88), →User createdBy, →User assignedUser (:96). `status` va `lead_stages` orasida FK YO'Q — faqat matn |
| LeadStage `entity/LeadStage.java:27-36` **B** | `lead_stages` | **code** (U, 50, updatable=false :48), nameUz/nameRu/nameEn, color (:61), sortOrder (:64), **kind** (`StageKind`, converter :66-69), requiresAmount (:84), isActive (:88) | — (5.3 ga qarang) |
| LeadStatusHistory `entity/LeadStatusHistory.java:20-29` | `lead_status_history` | fromStatus, toStatus (NOT NULL), changedAt, note | →Lead (NOT NULL), →User changedBy |
| LeadComment `entity/LeadComment.java:8-15` | `lead_comments` | text, createdAt, statusAtComment (String 50) | →Lead, →User author (NOT NULL) |
| LeadNote `entity/LeadNote.java:15-24` **B** | `lead_notes` | text | →Lead (NOT NULL), →User createdBy |
| Task `entity/Task.java:29-39` **B** | `tasks` | title, description, **type** (`TaskType`, converter, def CALL :58), **status** (`TaskStatus`, converter, def OPEN :63), dueAt (NOT NULL), allDay, completedAt, result | →User assignedTo (NOT NULL :75), →User createdBy, →Lead (:83), →Student (:87), →User completedBy |
| MetaLeadForm `entity/MetaLeadForm.java:31-41` | `meta_lead_forms` | **formId (U, 64 :55)**, pageId, name, status (String, Meta'dan), locale, leadsCount, **leadType** (`MetaFormType`, converter, def UNMAPPED :77), defaultStageCode, defaultStudyFormat (String), defaultSource (String), autoCreateTask, taskTimeQuestionKey, **active** (def false :122), syncedAt (Instant) | — |
| MetaLeadFormQuestion `entity/MetaLeadFormQuestion.java:25-37` | `meta_lead_form_questions` | questionKey, label, type, optionsJson, **crmField** (`MetaCrmField`, converter :73), sortOrder | →MetaLeadForm form (NOT NULL). **U(form_id, question_key)** |
| MetaWebhookEvent `entity/MetaWebhookEvent.java:22-33` | `meta_webhook_events` | **leadgenId (U :50)**, formId, pageId, adId, createdTimeMs, rawPayload, **status (String**, konstantalar :35-39), attempts (MAX 5 :42), errorMessage, leadId (Long), receivedAt, processedAt | — |

#### Foydalanuvchi / xodim / tizim

| Entity | Jadval | Asosiy maydonlar | Bog'lanishlar / constraintlar |
|---|---|---|---|
| User `entity/User.java:11-18` **B** | `users` | **username (U)**, **email (U)**, password (hash; hujjatga ko'chirilmaydi), firstName, lastName, phone, **role** (`UserRole` NOT NULL :48), isActive, lastLogin, lastSeenAt, photoUrl | — |
| Teacher `entity/Teacher.java:14-21` **B** | `teachers` | firstName, lastName, phone, email, subjectSpecialization, monthlySalary, hireDate, **isActive** (:67), notes, teacherCode, gender, dateOfBirth, father/motherName, address, permanentAddress, passportInfo, qualification, workExperience, joiningDate, **status (String** def "ACTIVE" :107; ACTIVE/INACTIVE/ON_LEAVE :23-25), basicSalary, medical/casual/maternity/sickLeaves, photoUrl | **`@OneToOne` →User (`user_id` U :40-42)**; `@OneToMany(mappedBy="teacher")` groups (:194). `ux_teachers_user_id` — `V50__teacher_user_link.sql:49` |
| Leave `entity/Leave.java:11-14` **B** | `leave_requests` | leaveType (String 30), fromDate, toDate, reason, **status (String** def "PENDING" :46), approvedAt | →User requester (nullable, V30), →Teacher, →User approvedBy |
| Notice `entity/Notice.java:11-18` **B** | `notices` | title, content, noticeDate, publishedTo (String "ALL"), noticeType (String "GENERAL"), targetRole (String), isActive, isPublished, publishedAt, expiresAt | →User createdBy |
| NoticeRead `entity/NoticeRead.java:9-16` | `notice_reads` | readAt | →Notice, →User. **U(notice_id, user_id)** `uk_notice_reads_notice_user` |
| Conversation `entity/Conversation.java:25-34` **B** | `conversations` | **type** (`ConversationType`, converter, def DIRECT :47), title, **directKey (U, 64 :55** "minId:maxId" :66-70), lastMessageAt | →User createdBy |
| ConversationParticipant `entity/ConversationParticipant.java:19-32` | `conversation_participants` | lastReadMessageId, isPinned, isMuted, joinedAt, **leftAt** (soft-leave :67) | →Conversation, →User. **U(conversation_id, user_id)** `uk_conv_participant` |
| Message `entity/Message.java:23-37` | `messages` | text (TEXT_MAX=4000 :40), **type** (`MessageType`, converter, def TEXT :64), replyToId (Long), editedAt, **deletedAt** (soft-delete :74), createdAt | →Conversation, →User sender |
| MessageAttachment `entity/MessageAttachment.java:26-35` | `message_attachments` | fileUrl, fileName, fileSize, contentType, width, height, durationMs, waveform, sortOrder. Limitlar: MAX_PER_MESSAGE=10, VOICE_MAX 5 min, waveform ≤50 nuqta, 0..100 (:38-45) | →Message |
| RefreshToken `entity/RefreshToken.java:8-15` | `refresh_tokens` | token (U, 500), expiresAt, isRevoked | →User |
| AuditLog `entity/AuditLog.java:15-27` | `audit_logs` | userId (Long), username, userRole (String), action (String 30), entityType, entityId, entityLabel, summary, detailsJson, ipAddress, userAgent, createdAt | FK yo'q (denormalizatsiya); indekslar :17-20 |

### 5.2 Enum'lar (29 ta) va JSON/DB ko'rinishi

**Umumiy qoida**: hech bir enumda `@JsonValue`/`@JsonCreator` yo'q va Jackson'da enum sozlamasi yo'q (grep: `JsonValue|JsonCreator|WRITE_ENUMS|READ_ENUMS|ACCEPT_CASE_INSENSITIVE` — natija bo'sh). Demak **JSON'da enum `name()` sifatida chiqadi va kiradi** (katta harf, aniq moslik; noto'g'ri qiymat → Jackson deserializatsiya xatosi). Label/icon maydonlari JSON'ga **chiqmaydi** — faqat `/api/enums/*` orqali.
Ko'p DTO'larda status **String** qabul qilinadi va servisda `valueOf(toUpperCase())` bilan tahlil qilinadi (registr muhim emas) — masalan `GroupService.java:653`, `CashRegisterService.java:735`, `AttendanceUnlockRequestService.java:180`.

DB saqlash: `@Enumerated(STRING)` → ustunda nom. 8 ta enum uchun `entity/converter/*` (`@Converter`, autoApply YO'Q — maydonda `@Convert` bilan): yozishda `name()`, o'qishda **noma'lum qiymat default'ga tushadi** (xato bermaydi).

| Enum (fayl) | Qiymatlar | DB / converter | Qayerda |
|---|---|---|---|
| AttendanceStatus `enums/AttendanceStatus.java:2` | PRESENT, ABSENT, EXCUSED, LATE | STRING | Attendance.status |
| BalanceTransactionType `enums/BalanceTransactionType.java:3-16` | LESSON_CHARGE (PER_LESSON, manfiy), LESSON_REFUND (+), PAYMENT (+), PERIOD_CHARGE (MONTHLY, manfiy), PERIOD_REFUND (+), FREEZE, UNFREEZE, MANUAL_ADJUST | STRING(30) | BalanceTransaction.type |
| BonusPenaltyKind | BONUS, PENALTY | STRING | BonusPenalty.kind |
| BonusPenaltyStatus | PENDING, APPLIED, CANCELLED | STRING | BonusPenalty.status |
| BonusTargetType | STUDENT, TEACHER | STRING | BonusPenalty.targetType |
| CashRegisterStatus | ACTIVE, ARCHIVED | STRING | CashRegister.status |
| CashTransactionStatus | COMPLETED, PENDING, CANCELLED | STRING | CashTransaction.status |
| CashTransactionType | INCOME, EXPENSE, TRANSFER | STRING | CashTransaction.type |
| ContractStatus | DRAFT, SIGNED, ACCEPTED | STRING | Contract.status |
| ContractType | OFFLINE, OFFER | STRING | Contract.type, ContractTemplate.type |
| ConversationType `enums/ConversationType.java:12-34` | DIRECT, GROUP | `ConversationTypeConverter` (noma'lum/null → DIRECT) | Conversation.type |
| ExpenseCategory | SALARY, RENT, MARKETING, UTILITIES, EQUIPMENT, OTHER | STRING | Expense.category |
| GroupStatus `enums/GroupStatus.java:2` | FORMING, ACTIVE, COMPLETED, CANCELLED | STRING | Group.status |
| IncomeCategory | STUDENT_PAYMENT, OTHER_INCOME | STRING | Income.category |
| LeadTaskState `enums/LeadTaskState.java:14-37` | NONE, PLANNED, TODAY, OVERDUE | **Saqlanmaydi** — hisoblanadi (`resolve(dueAt, now)`: null→NONE, o'tgan→OVERDUE, bugun tugaguncha→TODAY, aks holda PLANNED) | `LeadResponse.taskState` (`dto/response/LeadResponse.java:51`), `TaskResponse.state` (`dto/response/TaskResponse.java:30`); `service/TaskService.java:614-616`, `service/LeadService.java:1064` |
| MarketingSource `enums/MarketingSource.java:3-16` | INSTAGRAM, TELEGRAM, YOUTUBE, FACEBOOK, TARGET, SELF_CALL, FORMER_STUDENT, REFERRAL, WALK_IN, OFFLINE, LEAD, OTHER | STRING | Student.marketingSource; MetaLeadForm.defaultSource (String sifatida) |
| MessageType `enums/MessageType.java:21-46` | TEXT, IMAGE, FILE, VOICE, SYSTEM | `MessageTypeConverter` (→ TEXT) | Message.type |
| MetaCrmField `enums/MetaCrmField.java:18-37` | FULL_NAME, FIRST_NAME, LAST_NAME, PHONE, PHONE_ALT, PREFERRED_CALL_TIME, PLANNED_START, PURPOSE, NOTE, IGNORE | `MetaCrmFieldConverter` (noma'lum → **null**) | MetaLeadFormQuestion.crmField |
| MetaFormType `enums/MetaFormType.java:13-40` | STUDENT, HR, IGNORE, UNMAPPED | `MetaFormTypeConverter` (→ UNMAPPED) | MetaLeadForm.leadType |
| PaymentMethod `enums/PaymentMethod.java:13-93` | CASH "Naqd" 💵 (CASH), CARD "Karta" 💳, CLICK "Click" 📱 (online), PAYME "Payme" 🔷 (online), UZUM "Uzum" 🟠 (online), TERMINAL "Terminal" 🖥️, BANK "Bank o'tkazmasi" 🏦, CASH_AND_CARD "Naqd + Karta" 💵💳 (SPLIT), OTHER "Boshqa" ❓. Ichki `CashBucket {CASH, NON_CASH, SPLIT}`. Legacy aliaslar `parseOrNull` da: PLASTIC→CARD, ONLINE→OTHER, BANK_TRANSFER→BANK (:36-40) | STRING. Aliaslar faqat servis `parseOrNull` orqali; to'g'ridan-to'g'ri enum maydonli DTO'da "PLASTIC" Jackson xatosi bo'ladi (**TAXMIN** — DTO turiga bog'liq) | Payment, CashTransaction, Payroll |
| PaymentStatus `enums/PaymentStatus.java:7-17` | PAID, PENDING, OVERDUE, PARTIAL, CANCELLED, TRIAL, SUSPENDED, ARCHIVED, FROZEN | STRING | Payment.status, Student.paymentStatus (StudentGroup.paymentStatus esa String — shu qiymatlar) |
| PaymentType | MONTHLY, PER_LESSON | STRING | StudentGroup.paymentType |
| StageKind `enums/StageKind.java:12-41` | OPEN, CONVERTED, REJECTED (`isFinal()` = != OPEN) | `StageKindConverter` (→ OPEN) | LeadStage.kind |
| StudentStatus `enums/StudentStatus.java:3-11` | ACTIVE, FROZEN, FINISHED, LEFT, GRADUATED, SUSPENDED, ARCHIVED | STRING | Student.status |
| StudyFormat `enums/StudyFormat.java:12-27` | ONLINE, OFFLINE | `StudyFormatConverter` (noma'lum → **null**) | StudentGroup.studyFormat (Lead.format va MetaLeadForm.defaultStudyFormat — String) |
| TaskStatus `enums/TaskStatus.java:13-50` | OPEN "Ochiq", DONE "Bajarildi", CANCELLED "Bekor qilindi" | `TaskStatusConverter` (→ OPEN) | Task.status |
| TaskType `enums/TaskType.java:12-52` | CALL "Qo'ng'iroq" 📞, MEETING "Uchrashuv" 🤝, MESSAGE "Xabar" 💬, OTHER "Boshqa" 📌 | `TaskTypeConverter` (→ OTHER) | Task.type |
| UnlockRequestStatus | PENDING, APPROVED, REJECTED | STRING(20) | AttendanceUnlockRequest.status |
| UserRole `enums/UserRole.java:3-11` | SUPER_ADMIN, ADMIN, SALES_MANAGER, TEACHER, ACCOUNTANT, STUDENT, PARENT | STRING | User.role, SalaryRule.role |

Enum bo'lmagan, lekin "yopiq ro'yxat" String statuslar (frontend o'zi bilishi kerak):
- Teacher.status: ACTIVE / INACTIVE / ON_LEAVE (`entity/Teacher.java:23-25`, noma'lum → 400 `:189-190`).
- Leave.status: PENDING / APPROVED / REJECTED (validatsiya YO'Q — `service/LeaveService.java:128-129`).
- Payroll.status: PENDING / PAID (`service/PayrollService.java:122,141,303`).
- StudentGroup.paymentStatus: TRIAL/PENDING/PAID/OVERDUE/SUSPENDED/ARCHIVED/FROZEN (`entity/StudentGroup.java:110`).
- StudentGroup.exitReason: GRADUATED, LEFT, TRANSFERRED, SUSPENDED, FROZEN, OTHER (`entity/StudentGroup.java:121`).
- MetaWebhookEvent.status: PENDING, PROCESSING, PROCESSED, FAILED, SKIPPED (`entity/MetaWebhookEvent.java:35-39`).
- ExamRegistration.status "REGISTERED", .paymentStatus "PENDING"; HomeworkSubmission.status "PENDING"; Notice.publishedTo "ALL", noticeType "GENERAL"; Parent.relation "OTHER".
- Lead.source: amalda MarketingSource qiymatlari + **WEBSITE** (default) + META (`service/LeadTimelineService.java:60`); tarjima `service/LeadService.java:1187-1206`.
- Lead.format: "OFFLINE"/"ONLINE" (`entity/Lead.java:146-147`).

#### EnumController — `/api/enums` (`controller/EnumController.java:22-61`)

Faqat **3 ta** enum beriladi, barchasi `ApiResponse<List<EnumOptionDto>>`, `EnumOptionDto {value, label, icon}` (`dto/response/EnumOptionDto.java:14-16`). Ruxsat: har qanday autentifikatsiyalangan foydalanuvchi (`EnumController.java:19-20`).

| Endpoint | Enum | value | label | icon |
|---|---|---|---|---|
| `GET /api/enums/payment-methods` (:27) | PaymentMethod (9 ta) | name() | uz label | emoji |
| `GET /api/enums/task-types` (:39) | TaskType (4 ta) | name() | uz label | emoji |
| `GET /api/enums/task-statuses` (:51) | TaskStatus (3 ta) | name() | uz label | **null** (icon yo'q; NON_NULL bo'lmasa `"icon": null` — **TAXMIN**) |

Qolgan 26 enum uchun endpoint **yo'q** — frontend ularni o'zi hardcode qilishi kerak (yoki yangi endpoint qo'shish kerak). Lead bosqichlari uchun `/api/lead-stages` (5.3) ishlatiladi.

### 5.3 Sozlanadigan ma'lumotnomalar

#### LeadStage (`lead_stages`) — lid voronkasi

- Maydonlar: yuqoridagi jadval. `code` o'zgarmas (`updatable=false`, `entity/LeadStage.java:47`); `Lead.status`, `lead_status_history.from/to_status`, `lead_comments.status_at_comment` shu kodni **matn** sifatida saqlaydi (FK yo'q, `entity/LeadStage.java` class javadoc).
- `color` faqat: `secondary, info, warning, success, danger` (`service/LeadStageService.java:44-45`; xato `leadStage.color.invalid`).
- `kind`: OPEN / CONVERTED / REJECTED. Kod faqat `kind` ga tayanadi (nomga emas).
- **Seed** (`config/LeadStageSeeder.java:66-94`) — `ApplicationRunner`, `@Order(100)`, faqat jadval **bo'sh** bo'lsa (:61-64); o'chirish: `app.lead-stages.seed=false` (:44-47, default true):

| sort | code | nameUz | nameRu | nameEn | color | kind | requiresAmount |
|---|---|---|---|---|---|---|---|
| 1 | NEW | Yangi | Новый | New | secondary | OPEN | – |
| 2 | CONTACTED | Bog'lanildi | Связались | Contacted | info | OPEN | – |
| 3 | ONLINE_ENROLLED | Online yozildi | Онлайн записан | Enrolled online | warning | OPEN | – |
| 4 | OFFLINE_ENROLLED | Offline yozildi | Офлайн записан | Enrolled offline | warning | OPEN | – |
| 5 | ONLINE_PAID | Online to'ladi | Онлайн оплатил | Paid online | success | OPEN | ✔ |
| 6 | OFFLINE_PAID | Offline to'ladi | Офлайн оплатил | Paid offline | success | OPEN | ✔ |
| 7 | CONVERTED_ONLINE | Online o'quvchi | Онлайн ученик | Online student | success | CONVERTED | – |
| 8 | CONVERTED_OFFLINE | Offline o'quvchi | Офлайн ученик | Offline student | success | CONVERTED | – |
| 9 | REJECTED | Rad etildi | Отклонён | Rejected | danger | REJECTED | – |

- **Yaratish** (`service/LeadStageService.java:83-102`): kind har doim OPEN (yakuniy bosqich qo'shib bo'lmaydi); `code` = `nameUz` slug (apostrof tushadi, lotin bo'lmasa `STAGE`, dublikatda `_2`, `_3`…, max 50; :402-423); sortOrder berilmasa `max+1`.
- **Tahrirlash** (:115-139): nameUz/Ru/En, color, sortOrder, requiresAmount, isActive. `code` va `kind` DTO'da yo'q — o'zgarmaydi.
- **O'chirish** (:144-162, hard delete): `kind` yakuniy (CONVERTED/REJECTED) bo'lsa 400 `leadStage.delete.systemStage`; bosqichda lid bo'lsa 400 `leadStage.delete.hasLeads` ("Bosqichda {0} ta lid bor…"). Frontend uchun `LeadStageResponse.deletable = !kind.isFinal()` (:454).
- **Tartib** `PATCH /api/lead-stages/reorder` (:174-187): id'lar ro'yxati → pozitsiya 1..n; ro'yxatda yo'qlar o'z sortOrder'ida qoladi; bo'sh ro'yxat → 400.
- **Faolsizlantirish** (`isActive=false`): bosqichga yangi lid o'tkazib bo'lmaydi (`leadStage.code.inactive`), mavjud lidlar qoladi.
- Kesh: servis ichki keshi, har yozuvdan keyin `invalidateCache()`.

#### Boshqa sozlanadigan jadvallar

- **SalaryRule** (`salary_rules`): rol bo'yicha umumiy (`user=null`) yoki shaxsiy (`user` berilgan) qoida. Tanlash: avval faol shaxsiy, keyin faol rol qoidasi; `effectiveFrom <= asOf`, eng so'nggisi (`repository/SalaryRuleRepository.java:22-58`). O'chirish = `isActive=false` (`service/SalaryRuleService.java:47-52`). Unique yo'q.
- **ContractTemplate**: `isDefault` bitta bo'lishi kerak — create/update'da `isDefault=true` bo'lsa boshqalari tozalanadi (`service/ContractService.java:58-75`). `templateId` berilmasa default ishlatiladi, bo'lmasa 400 "Standart shartnoma shabloni topilmadi" (:158-164). O'chirish — hard delete (:78-80).
- **MetaLeadForm** (`meta_lead_forms`) — Meta'dan sync qilinadi; admin sozlaydi (`service/MetaAdminService.java:119-160`): leadType, defaultStageCode (faol `lead_stages.code` bo'lishi shart), defaultStudyFormat (ONLINE/OFFLINE), defaultSource (MarketingSource), autoCreateTask (+ taskTimeQuestionKey majburiy), active. **MetaLeadFormQuestion.crmField** mapping (`updateMapping` :164).
- **Course**, **Classroom** — oddiy ma'lumotnomalar (isActive).
- **CashRegister** — kassalar ro'yxati (acceptOnlinePayment, status).
- Alohida `settings` / `roles` jadvali **YO'Q**: rollar `UserRole` enumida qattiq; sozlamalar `application.yml` da (`app.*`).

### 5.4 Holat mashinalari

#### 5.4.1 Lid bosqichlari (Lead.status + StageKind)

- Holatlar: `lead_stages` dagi kodlar (dinamik). Yangi lid: `NEW` (`entity/Lead.java:21`) yoki Meta formasi `defaultStageCode`.
- **Oddiy o'tish** `PATCH /api/leads/{id}/status` {status, amount?} → `LeadService.updateStatus` (`service/LeadService.java:325-383`):
  - kod mavjud va faol bo'lishi shart (`LeadStageService.requireActiveCode` :196-210 → `leadStage.code.required/unknown/inactive`).
  - **CONVERTED turdagi bosqichga qo'lda KIRISH taqiqlangan** (:347-349) → 400 `lead.status.convertedManually`: "Bu bosqichga qo'lda o'tib bo'lmaydi. "O'quvchiga o'tkazish" amalini ishlating". CONVERTED'dan **chiqish** ruxsat etilgan.
  - REJECTED ga o'tish erkin (maxsus tekshiruv yo'q).
  - `amount < 0` → `lead.amount.negative`; `requiresAmount` bosqichida lidda summa bo'lmasa → `lead.amount.required` (:362-365); bunday bosqichga o'tganda avtomatik tizim izohi "To'lov qabul qilindi: N UZS" (:378-380).
  - Bir xil bosqichga qayta saqlash tarix yozmaydi (:370-373).
- **Konvertatsiya** `POST /api/leads/{id}/convert` → `LeadService.convertToStudent` (`service/LeadService.java:609-704`):
  - Taqiq: `converted=true` yoki status CONVERTED turida yoki `student != null` → 400 "Bu lid allaqachon o'quvchiga aylantirilgan" (:614-618); telefon bo'yicha o'quvchi bor → 409 `DuplicateResourceException` (:622-625).
  - Student (ACTIVE, paymentStatus PENDING) yaratadi, ixtiyoriy guruhga qo'shadi (`GroupService.addStudentToGroup`), status = `convertedCodeFor(studyFormat)` → CONVERTED_ONLINE / CONVERTED_OFFLINE, topilmasa birinchi CONVERTED bosqich (`service/LeadStageService.java:252-266`); `converted=true`, `lead.student` o'rnatiladi (:686-688).
- **Tarix**: `lead_status_history` — `recordStatusChange` (`service/LeadService.java:925-933`), changedBy = joriy user, note (konvertda "O'quvchiga aylantirildi"). `GET /api/leads/{id}/history` (`getHistory` :242).
- **LeadTaskState** — lidning eng yaqin OCHIQ vazifasidan hisoblanadi (NONE/PLANNED/TODAY/OVERDUE), konvertatsiyaga aloqasi yo'q.
- Tizim bosqichlari (CONVERTED/REJECTED) yo'q bo'lsa → 400 `leadStage.systemStage.missing` (:364-370).

#### 5.4.2 Guruh statusi (GroupStatus)

- Holatlar: FORMING, ACTIVE, COMPLETED, CANCELLED. **O'tish cheklovi YO'Q** — istalgan → istalgan.
- Yaratish `POST /api/groups`: `status` ixtiyoriy String, berilmasa ACTIVE (`service/GroupService.java:219-221`) — commit 876897a (`dto/request/GroupRequest.java` ga `status` qo'shildi). Commit izohi: frontend formasi default FORMING yuboradi, ya'ni UI'dan yaratilgan guruhlar endi FORMING bo'ladi, ko'p guruh tanlagichlari esa faqat ACTIVE ni ko'rsatadi.
- Tahrirlash `PUT /api/groups/{id}`: status berilsa o'zgaradi, berilmasa saqlanadi (:258-261).
- `PATCH /api/groups/{id}/status` → `updateStatus` (:502-506).
- Uchalasi ham `parseStatus` (:653-662): bo'sh/noma'lum → 400 `group.status.invalid` "Noto'g'ri guruh holati: {0}. Ruxsat etilgan: FORMING, ACTIVE, COMPLETED, CANCELLED".
- `DELETE /api/groups/{id}` → soft: `status=CANCELLED` (:510-514).
- Status ta'siri: Dashboard/Analytics faqat ACTIVE ni sanaydi (`service/DashboardService.java:51`); import faqat ACTIVE/FORMING guruhga qo'shadi (`service/ImportService.java:64-65`); **UI'dagi `addStudentToGroup` status tekshirmaydi** (:520-598). Tarix jadvali yo'q (faqat AuditLog, `@Audited`).

#### 5.4.3 O'quvchi statusi (StudentStatus) va StudentGroup

**StudentGroup holati** alohida enum bilan emas, maydonlar kombinatsiyasi bilan ifodalanadi:
- *Faol*: `isActive=true` (`leaveDate`, `exitDate` null). `paymentStatus` (String) = TRIAL/PENDING/PAID/OVERDUE.
- *Chiqqan*: `isActive=false`, `leaveDate`, `exitDate`, `exitReason` (GRADUATED/LEFT/TRANSFERRED/SUSPENDED/OTHER), `exitNotes`.
- *Muzlatilgan*: `isActive=false`, `exitReason="FROZEN"`, `paymentStatus="FROZEN"` (`service/StudentService.java:969-974`). `frozenFrom/To` maydoni **yo'q**.
- *Arxivlangan (avto)*: `isActive=false`, `paymentStatus="ARCHIVED"` (`service/StudentPaymentLifecycleService.java:107-110`).
- `suspendedAt/suspensionReason` — hech qayerda yozilmaydi.

**Student.status o'tishlari**:

| O'tish | Metod | Shart / xato | Tarix |
|---|---|---|---|
| (yangi) → ACTIVE | `StudentService.createStudent` :133-160; `LeadService.convertToStudent` :638; import `StudentImportRowService.java:77` | tel dublikat → 409 | yo'q |
| ACTIVE/… → FROZEN | `POST /api/students/{id}/freeze` → `freezeStudent` (`service/StudentService.java:953-1016`): faol guruhlar yopiladi, PER_LESSON uchun `FREEZE` balans tranzaksiyasi, `paymentStatus=FROZEN`, `nextPaymentDate=null` | allaqachon FROZEN → `student.freeze.already`; faol guruh yo'q → `student.freeze.noActiveGroup` (:1039) | ✔ reason, balanceSnapshot, notes |
| FROZEN → ACTIVE | `POST /api/students/{id}/unfreeze` → `unfreezeStudent` (:1126-1215): guruhga qayta qo'shadi, `UNFREEZE` tranzaksiyalari (:1189-1202), `paymentStatus=PENDING` | FROZEN emas → `student.unfreeze.notFrozen`; shu guruhda faol → `student.unfreeze.alreadyActive`; guruh yo'q → 404 | ✔ |
| → GRADUATED / LEFT / SUSPENDED | `POST /api/groups/{groupId}/remove-student` → `GroupService.removeStudentFromGroup(groupId, studentId, reason, notes)` (`service/GroupService.java:613-649`) — reason bo'yicha switch (:629-636); TRANSFERRED/OTHER statusni o'zgartirmaydi | faol yozuv yo'q → 404 | ✔ (reason, notes) |
| (status o'zgarmaydi) guruh almashtirish | `POST /api/students/{id}/transfer-group` → `transferGroup` (:354-432) | maqsad yo'q → `student.transfer.targetRequired`; shu guruhda → `student.transfer.alreadyInGroup`; to'lgan → `group.full` | ✔ from=to=eski status, reason TRANSFERRED |
| → LEFT | `DELETE /api/students/{id}` → `deleteStudent` (:534-538) — faqat status, guruhlar yopilmaydi | — | **yo'q** |
| → istalgan | `PUT /api/students/{id}` → `buildFromRequest` (:636-642): `StudentRequest.status` @NotBlank; noma'lum qiymat → jimgina ACTIVE | — | **yo'q** |
| ACTIVE → FROZEN (avto) | `@Scheduled(cron="0 0 8 * * *")` `archiveInactiveStudents` (`service/StudentPaymentLifecycleService.java:89-125`): 30 kun davomat yo'q, TRIAL emas | — | **yo'q** |
| `GroupService.removeStudentFromGroup(studentId, groupId)` (`DELETE /api/groups/{g}/students/{s}`, :600-608) | faqat StudentGroup yopiladi, Student.status o'zgarmaydi | 404 | yo'q |

- FINISHED va ARCHIVED hech qachon yozilmaydi (faqat sanaladi: `service/StudentService.java:579`, `service/DashboardService.java:54`).
- **Tarix**: `student_status_history` (`GET` — `StudentService.getStudentHistory` :919). `changedBy` hech qayerda o'rnatilmaydi (grep `setChangedBy` — faqat LeadService builder orqali lead tarixida).

#### 5.4.4 To'lov: PaymentStatus / PaymentType / PaymentMethod / BalanceTransactionType

- **Payment.status**: yaratishda doim PAID (`service/PaymentService.java:125`). Bekor qilish / o'chirish endpointi yo'q (PaymentController'da DELETE/PATCH yo'q). Ya'ni Payment uchun amalda holat mashinasi yo'q.
- **Student.paymentStatus** (enum) va **StudentGroup.paymentStatus** (String) — billing holati, `PaymentScheduleService` qayta hisoblaydi:
  - SG darajasi (`service/PaymentScheduleService.java:554-579`): to'lov bor → PAID; SUSPENDED/ARCHIVED/FROZEN "yopishqoq" (saqlanadi); trial → TRIAL; aks holda PENDING; muddat o'tgan → OVERDUE (:141, :578).
  - Student darajasi = SG'lar ichida "eng yomoni": ustuvorlik SUSPENDED/ARCHIVED/FROZEN(5) > OVERDUE > PENDING > TRIAL > PAID (:258-264, :207).
  - Freeze → FROZEN, unfreeze → PENDING, guruhga qo'shish → TRIAL/PENDING (`service/GroupService.java:555`).
  - PARTIAL, CANCELLED — hech qayerda o'rnatilmaydi; SUSPENDED faqat saqlanadi, hech qayerda qo'yilmaydi.
- **PaymentType**: MONTHLY (davr sotib olinadi → `PERIOD_CHARGE`, `service/PaymentService.java:253`) yoki PER_LESSON (davomatda `LESSON_CHARGE`/`LESSON_REFUND`, `service/AttendanceService.java:340-373`; billable = PRESENT/ABSENT/LATE, EXCUSED emas).
- **BalanceTransactionType** yozuvchilari: PAYMENT (`PaymentService.java:223`), PERIOD_CHARGE (:253), LESSON_CHARGE/REFUND (`AttendanceService.java:355,362`), FREEZE (`StudentService.java:989`), UNFREEZE (:1189-1202), MANUAL_ADJUST (`BalanceTransactionService.java:211`, `MonthlyLedgerRepairWorker.java:85`, `BalanceExpectationService.java:319`). **PERIOD_REFUND hech qayerda yozilmaydi** (faqat o'qiladi `BalanceExpectationService.java:72`).
- **PaymentMethod**: CASH → kassa `cashBalance`; CASH_AND_CARD → split (cashPart/cardPart); qolganlari → `plasticBalance`. Online metodlar (CLICK/PAYME/UZUM) faqat `acceptOnlinePayment=true` kassaga (`service/CashRegisterService.java:349` "Bu kassa onlayn to'lovlarni qabul qilmaydi").

#### 5.4.5 Davomat va qulf

- AttendanceStatus: PRESENT/ABSENT/EXCUSED/LATE. ABSENT/EXCUSED/LATE uchun izoh yoki excuseReason majburiy → 400 "Sabab kiritilishi shart" (`service/AttendanceService.java:86-92`). `ABSENT + excused=true` → EXCUSED ga aylantiriladi (:117-121).
- Dars kuni bo'lmasa (GroupScheduleDay) → 400 "Bu kunda guruhda dars yo'q (…)" — admin uchun ham (:171-181).
- **Qulf — sana asosida**: admin bo'lmagan foydalanuvchi uchun `date < bugun` bo'lsa, shu (teacher, group, date) uchun APPROVED unlock so'rov bo'lishi kerak, aks holda 403 "Bu kun uchun ruxsat kerak. Admindan so'rang." (:70-80). Soat/daqiqa asosidagi qulf yo'q; kelajakdagi sana cheklanmagan.
- **UnlockRequestStatus**: PENDING → APPROVED | REJECTED (`service/AttendanceUnlockRequestService.java`):
  - yaratish (:40-66): shu kun uchun PENDING bor → "So'rov allaqachon yuborilgan".
  - `PATCH /api/attendance/unlock-requests/{id}/approve` (:101-132): PENDING emas → "So'rov allaqachon ko'rib chiqilgan"; ixtiyoriy `penaltyAmount` → o'qituvchiga PENALTY BonusPenalty yaratiladi.
  - `.../{id}/reject` (:135-150): xuddi shu tekshiruv.
  - APPROVED ruxsat muddatsiz (qayta ishlatiladi).

#### 5.4.6 Task (TaskStatus, TaskType)

- OPEN → DONE: `PATCH /api/tasks/{id}/complete` → `TaskService.complete` (`service/TaskService.java:182-217`): result majburiy ("Bajarilish natijasi majburiy"), ixtiyoriy `nextTask` (muddati kelajakda — `task.dueAt.future`). Javob `leadHasOpenTask`.
- Yopilgan vazifa ustida complete/reassign/postpone → 400 "Vazifa allaqachon yopilgan" (:512-513). Postpone: yangi muddat kelajakda (:295).
- **CANCELLED hech qayerda o'rnatilmaydi**; `DELETE` — hard delete, faqat muallif yoki full-access (:306-321).
- Yaratishda lid VA o'quvchi bir vaqtda → `task.target.single` (:94).

#### 5.4.7 Contract (ContractStatus)

- DRAFT (yaratishda, `service/ContractService.java:129`) → SIGNED (`PATCH /api/contracts/{id}/sign`, :148-152, **shartsiz**) yoki → ACCEPTED (`PATCH /api/contracts/{id}/accept-offer`, :136-145; faqat `type=OFFER`, aks holda "Faqat OFFER shartnomalar qabul qilinadi"; `offerAccepted=true`, `acceptedAt`). Hard delete (:155-157). Qayta o'tishlar (SIGNED→ACCEPTED va h.k.) cheklanmagan.

#### 5.4.8 CashRegister / CashTransaction

- CashRegister: ACTIVE ↔ ARCHIVED (`PATCH /api/cash-registers/{id}/status`, `service/CashRegisterService.java:140-146`; noto'g'ri → "Noto'g'ri kassa holati"). `archived` boolean status bilan sinxron yoziladi (:94-98, :116-118, :144). DELETE: tranzaksiyasi bor → arxivlanadi ("Tranzaksiyalari bor, arxivlandi"), yo'q → hard delete (:127-137). ARCHIVED kassaga yozishni bloklovchi tekshiruv topilmadi (grep `CashRegisterStatus.ARCHIVED` faqat setterlarda).
- CashTransaction: doim COMPLETED (:366, :469); PENDING/CANCELLED ishlatilmaydi. Chiqimni o'chirish — balans qaytariladi va hard delete (:519-530). TRANSFER — ikkita yozuv (out/in, :562, :575); balans yetmasa "Naqd/Plastik balans yetarli emas" (:670-674).

#### 5.4.9 BonusPenalty

- PENDING → APPLIED: avtomatik — payroll hisoblashda (`BonusPenaltyService.applyPendingForTeacher` :138-160, chaqiruvchi `PayrollService.java:151,192,245`) yoki o'quvchi to'lovida (`applyPendingForStudent` :197-219, `PaymentService.java:138`); `appliedToPayrollId/PaymentId` yoziladi.
- PENDING → CANCELLED: `PATCH /api/bonus-penalties/{id}/cancel` (:243-248).
- Tahrir/bekor/o'chirish faqat PENDING da (`ensurePending` :292-296): "Faqat kutilayotgan yozuvlar tahrirlanadi" / "…bekor qilinadi" / "Qo'llangan yozuvlar o'chirilmaydi".

#### 5.4.10 Leave (ta'til)

- Yaratish → "PENDING" (`service/LeaveService.java:71-92`). `PATCH /api/leaves/{id}/status` body `{status, reason?, approvedById?}` → `approveOrReject` (:126-146): **status erkin matn**, validatsiya va o'tish tekshiruvi yo'q; `status` kalit bo'lmasa NPE (500); `approvedById` body'dan olinadi (joriy user emas). Tasdiqlash o'qituvchini ON_LEAVE ga o'tkazmaydi. Hard delete (:149-151).

#### 5.4.11 Teacher statusi (V50/V51)

- Holatlar: ACTIVE, INACTIVE, ON_LEAVE (String). Invariant (`entity/Teacher.java:131-178`): INACTIVE ⇔ `isActive=false`; ACTIVE/ON_LEAVE ⇔ `isActive=true`. Setterlar bir-birini yangilaydi, `@PrePersist/@PreUpdate enforceStatusConsistency` zidlikda NOFAOL ni tanlaydi. Noma'lum status → `IllegalArgumentException` → 400 "Noma'lum o'qituvchi statusi: X (ACTIVE, INACTIVE yoki ON_LEAVE)" (:189-190; `exception/GlobalExceptionHandler.java:46-48`).
- Yagona kirish nuqtasi `StaffStatusService.changeTeacherStatus` (`service/StaffStatusService.java:66-84`): INACTIVE ga o'tsa bog'langan TEACHER user bloklanadi (sessiyalari yopiladi), INACTIVE dan chiqsa ochiladi; ACTIVE↔ON_LEAVE userga tegmaydi. Chaqiruvlar: `PUT /api/teachers/{id}` (status berilsa, :42-48), `DELETE /api/teachers/{id}` → INACTIVE (soft, :52-54).
- Teskari yo'nalish: user roli TEACHER dan o'zgarsa profil INACTIVE (`service/TeacherService.java:700-715`); user yaratilganda profil ACTIVE/INACTIVE (:686).
- **V50** (`V50__teacher_user_link.sql`): `teachers.user_id` ga FK `fk_teachers_user` + dublikat tekshiruvi (topilsa migratsiya xato bilan to'xtaydi) + `ux_teachers_user_id` UNIQUE (:49). Entity'da `@ManyToOne` → `@OneToOne`.
- **V51** (`V51__teacher_status_consistency.sql`): status'ni UPPER/TRIM, bo'shini is_active dan to'ldiradi, zidlikda ikkalasini INACTIVE qiladi, noma'lum statuslarni faqat NOTICE qiladi. Idempotent.
- Tarix jadvali yo'q (AuditLog/log).

#### 5.4.12 Boshqa kichik holatlar

- Payroll.status: PENDING → PAID (`service/PayrollService.java:303`); PAID payroll qayta hisoblansa PENDING ga qaytadi (:113-122, :141).
- MetaWebhookEvent: PENDING → PROCESSING → PROCESSED | FAILED | SKIPPED, `attempts ≤ 5`; admin retry → PENDING, attempts=0 (`service/MetaAdminService.java:223-233`).
- Message: `editedAt` (tahrir), `deletedAt` (soft-delete). ConversationParticipant: `leftAt`.

### 5.5 DB sxemasi qanday boshqariladi

- **Hibernate `ddl-auto: update`** (`src/main/resources/application.yml:26`) — asosiy mexanizm: yo'q jadval/ustunlarni yaratadi, hech narsani o'chirmaydi, mavjud ustunga UNIQUE/CHECK ni kafolatlamaydi.
- **Flyway yo'q**: `pom.xml` da flyway/liquibase dependency topilmadi; `application.yml` da flyway sozlamasi yo'q. README (`README.md:15`) "Migration | Flyway" deydi — **eskirgan**. Bundan tashqari versiyalar dublikat: `V31__leave_teacher_id.sql` + `V31__student_exit.sql`, `V35__group_forming_status.sql` + `V35__lead_assignment_comments.sql` — Flyway bunday to'plamni rad etgan bo'lardi.
- `db/migration/V24…V51` (30 fayl) — **qo'lda** ishga tushiriladigan idempotent SQL skriptlar (`IF NOT EXISTS`, `DO $$`). Tasdiq: `docs/META_LEAD_ADS.md:112-116` ("V49 ni **qo'lda** bajaring… Flyway yo'q, sxemani ddl-auto: update quradi — lekin u UNIQUE indekslarni kafolatlamaydi"), `V50__teacher_user_link.sql:6`. Deploy jarayonida avtomatik ishlashi **TAXMIN: yo'q** (CI/skript topilmadi). Ular asosan: ddl-auto qila olmaydigan UNIQUE/partial indekslar, FK nomlari, ma'lumot ko'chirish (masalan V44 lid statuslari, V51 teacher status).
- Migratsiyadagi, lekin entity'da e'lon qilinmagan constraintlar: `group_schedule_days UNIQUE(group_id, day_of_week)` (V25:12), `exam_registrations UNIQUE(exam_id, student_id)` (V27:12), `uk_payroll_user_month_year` partial (V40:61), `uk_leads_meta_leadgen_id` partial (V49:116), `ux_teachers_user_id` (V50:49). Yangi (bo'sh) bazada faqat ddl-auto ishlasa, bular **bo'lmaydi**.
- `lead_stages`, `tasks`, `lead_notes` jadvallari uchun migratsiya yo'q — faqat ddl-auto yaratadi.
- **EnumCheckConstraintCleaner** (`config/EnumCheckConstraintCleaner.java:25-73`): `ApplicationRunner` (ddl-auto'dan keyin). Hibernate 6 `@Enumerated(STRING)` ustunlar uchun `CHECK (col IN (...))` yaratadi, `update` rejimi ularni yangilamaydi → enumga yangi qiymat qo'shilsa INSERT 500 beradi. Shu sababli startup'da `pg_constraint` dan `public` sxemadagi `pg_get_constraintdef LIKE '%= ANY %ARRAY[%'` bo'lgan **barcha** CHECK'larni `ALTER TABLE … DROP CONSTRAINT` qiladi (:36-42, :64). Xato ilovani to'xtatmaydi. O'chirish: `app.schema.drop-enum-checks=false` (yml:54 da `true`). Natija: enum qiymatlari faqat Java darajasida tekshiriladi.
- `LeadStageSeeder` ham xuddi shu mexanizm (ApplicationRunner, `@Order(100)`).


---

## §6. Biznes qoidalar (frontend bilishi shart bo'lganlar)

Tuzilish: **A–E** — moliya (to'lov, davomat, oylik/KPI/bonus, kassa, shartnoma/aksiya); **6.1–6.6** — lid, Meta, vazifa, Telegram, audit, rate limit.


Barcha yo'llar `src/main/java/com/crm/` ga nisbatan. Pul tipi hamma joyda `BigDecimal`, DB da `numeric(12,2)` (BonusPenalty `15,2`). Valyuta — UZS, butun so'm (kasr ishlatilmaydi, lekin backend kasrni rad ham etmaydi).

---

### 0. Umumiy model (bir qarashda)

| Tushuncha | Qayerda saqlanadi | Kim o'zgartiradi |
|---|---|---|
| Enrollment balansi | `StudentGroup.balance` (`entity/StudentGroup.java:90-92`) | FAQAT `BalanceTransactionService.record()` (`service/BalanceTransactionService.java:47-87`) — bitta istisno pastda (§D, `addIncome`) |
| O'quvchi balansi | `Student.balance` = **barcha** SG balanslari yig'indisi (faol + nofaol) | `syncStudentBalanceFromGroups()` (`service/BalanceTransactionService.java:91-100`) |
| Balans daftari (ledger) | `BalanceTransaction` {type, amount(±), balanceAfter, referenceId, note, createdBy, createdAt} (`entity/BalanceTransaction.java`) | `record()` |
| To'lov | `Payment` {amount=gross, discountAmount, bonusDiscount, balanceUsed, payableAmount, cashAmount, periodStart/End, status} (`entity/Payment.java`) | faqat `PaymentService.createPayment` |
| To'lov holati (o'quvchi) | `Student.paymentStatus` (enum) va `StudentGroup.paymentStatus` (**String**) | `PaymentScheduleService.recalculate*` (yagona manba) |
| Keyingi to'lov sanasi | `StudentGroup.nextPaymentDate`, `Student.nextPaymentDate` (= eng erta SG sanasi) | `PaymentScheduleService.recalculate*` |

**Muhim:** balans (pul) va to'lov holati/sanasi (PAID/OVERDUE, nextPaymentDate) — **ikki mustaqil tizim**. Holat sana asosida hisoblanadi, balans asosida EMAS (§A.8).

---

### A) To'lov

#### A.1 To'lov rejimlari: `PaymentType` = `MONTHLY | PER_LESSON` (`entity/enums/PaymentType.java`)

- Rejim **enrollment (StudentGroup) darajasida**: `StudentGroup.paymentType`, default `MONTHLY` (`entity/StudentGroup.java:71-73`).
- Tanlanadigan joylar:
  - `POST` guruhga qo'shish — `request.paymentType ?? MONTHLY` (`service/GroupService.java:540-541`).
  - Yangi o'quvchi + guruh — `req.paymentType ?? MONTHLY`; MONTHLY bo'lsa va na `monthlyFee`, na kurs narxi bo'lmasa 400 `student.monthlyFee.required` (`service/StudentService.java:468-480`).
  - **transfer-group** — rejim ko'chirilmaydi, yangi SG builder default = `MONTHLY`, narx = kurs narxi (individual `monthlyPriceOverride` yo'qoladi) (`service/StudentService.java:398-418`).
  - **unfreeze** — har doim `MONTHLY` majburan (`service/StudentService.java:1166`).
  - bulk promote — `paymentType`/`lessonPrice` ko'chirilmaydi → MONTHLY (`service/PromotionService.java:130-138`).
- `StudentPaymentPlan` entity (`entity/StudentPaymentPlan.java`) — **ishlatilmaydi** (faqat entity + repository, hech bir servis chaqirmaydi). Frontendda "to'lov rejasi" UI bo'lmasin.

#### A.2 Narxlarni aniqlash

**Oylik narx** `PaymentScheduleService.resolveMonthlyFee(sg)` (`service/PaymentScheduleService.java:602-615`):
```
sg.monthlyPriceOverride (>0) → group.course.monthlyPrice (>0) → student.monthlyFee → 0
```
`recalculate` har safar `monthlyPriceOverride` bo'sh/0 bo'lsa kurs narxi bilan to'ldiradi (`:156-169`).

**Dars narxi** `resolveLessonPrice(sg)` (`service/PaymentScheduleService.java:406-425`):
```
sg.lessonPrice (>0) → course.lessonPrice (>0) → monthlyFee / oyiga_darslar (scale 2, HALF_UP)
oyiga_darslar = max(1, course.lessonsCount / course.durationMonths) yoki 8 (default)   (:493-499)
```
Muzlatish uchun alohida variant `resolveFreezeLessonPrice` — oyiga_darslar o'rniga davrdagi jadval bo'yicha darslar soni (`:431-461`).

`StudentGroup.discountPercentage` saqlanadi (`service/GroupService.java:577`), lekin **hech qayerda hisobda ishlatilmaydi** — frontend "chegirma %" maydonini ko'rsatsa, u pulga ta'sir qilmasligini bilsin.

#### A.3 To'lov yaratish: `POST /api/payments` → `PaymentService.createPayment` (`service/PaymentService.java:78-194`)

Request (`dto/request/PaymentRequest.java`): `studentId*`, `groupId`, `amount*` (≥0), `paymentMethod` (default CASH), `paymentDate` (default bugun), `periodFrom`, `periodTo`, `description`, `notes`, `discountAmount`, `cashRegisterId`, `paymentMethodForCash`, `cashPart`, `cardPart`, `useBalance`, `balanceAmount` (**e'tiborsiz** — faqat ma'lumot), `applyBonuses` (null = true).

**Qadam-baqadam:**
1. Chek raqami: `"RCP-" + %05d(count()+1)` (`:88-89`) — race bor (Muammolar).
2. Enrollment: `groupId` berilsa o'sha faol SG, topilmasa **o'quvchining birinchi faol SG si** (jim fallback); `groupId` yo'q bo'lsa ham birinchi faol SG (`:348-357`).
3. Hisob — `calculate()` (`:298-320`), frontend qiymatlariga ishonilmaydi:
   ```
   gross       = max(amount, 0)
   discount    = max(discountAmount, 0);  discount > gross → 400 "payment.discount.tooLarge"
   payable     = gross - discount
   balanceUsed = useBalance==true ? min(max(student.balance, 0), payable) : 0
   cashAmount  = payable - balanceUsed          // kassaga tushadigan REAL pul
   balanceAfter= student.balance - balanceUsed   // faqat preview uchun
   ```
   DIQQAT: `student.balance` — barcha guruhlar (nofaol ham) yig'indisi.
4. Davr — `resolvePaymentPeriod()` (`:382-424`):
   ```
   periodStart = periodFrom ?? sg.nextPaymentDate ?? student.nextPaymentDate
                 ?? sg.paymentStartDate ?? student.paymentStartDate ?? sg.joinDate ?? bugun
   fee         = student.monthlyFee (>0) ?? resolveMonthlyFee(sg)      // DIQQAT: student.monthlyFee = barcha faol guruhlar yig'indisi!
   chargeMonths= floor(gross / fee)          // PeriodChargeFormula.months, 0 bo'lishi mumkin
   periodEnd   = periodTo ?? periodStart + max(chargeMonths,1) oy - 1 kun
   agar periodFrom VA periodTo ikkalasi berilsa: chargeMonths = MONTHS.between(periodFrom, periodTo+1)
   ```
   Natija: **fee dan kam to'lov ham nextPaymentDate ni to'liq 1 oyga suradi** (clamp `max(...,1)`), lekin PERIOD_CHARGE yozilmaydi (pul balansda qoladi).
5. `Payment` saqlanadi: `status = PAID` (har doim), `amount = gross`, `payableAmount`, `cashAmount`, `balanceUsed`, `discountAmount` (`:113-133`).
6. Ledger — `writeLedgerForPayment()` (`:208-257`), faqat enrollment bor bo'lsa:
   - **KREDIT** `PAYMENT`, `+cashAmount` (0 bo'lsa ham audit uchun yoziladi), `referenceId = payment.id`.
   - **DEBET** faqat `MONTHLY`: `PERIOD_CHARGE`, `-(chargeMonths × fee − discount)` (`service/PeriodChargeFormula.java:55-62`); `chargeMonths=0` yoki chegirma ≥ davr qiymati bo'lsa yozilmaydi.
   - Chegirma MONTHLY da balansga NEYTRAL: `kredit − debet = gross − balanceUsed − months×fee` (`service/PeriodChargeFormula.java:24-27`).
   - Enrollment yo'q (o'quvchi hech bir guruhda faol emas) → **ledger umuman yozilmaydi**, pul balansga tushmaydi (`:210-212`).
7. Bonus/jarima (student) — `applyBonuses != false` bo'lsa o'quvchining barcha PENDING (effectiveDate ≤ paymentDate) STUDENT yozuvlari APPLIED qilinadi (`:137-150`, `service/BonusPenaltyService.java:197-220`). **Faqat kosmetik**: `bonusDiscount` va `discountAmount` maydonlari oshiriladi, jarima `notes` ga yoziladi — `cashAmount`, ledger, kassa O'ZGARMAYDI (ledger 6-qadamda allaqachon yozilgan). Preview bonuslarni hisobga olmaydi.
8. Kassa (`:152-182`), faqat `cashAmount > 0`:
   - Har doim `Income{category=STUDENT_PAYMENT, amount=cashAmount}` yoziladi.
   - `cashRegisterId` berilsa — `CashRegisterService.recordIncome(cashAmount, method, cashPart, cardPart)`; method = `paymentMethodForCash` (noto'g'ri nom → 400 `payment.methodForCash.invalid`) ?? `paymentMethod` ?? CASH (`:678-690`).
   - `cashRegisterId` berilmasa — kassa yozuvi YO'Q (faqat Income).
   - `cashAmount = 0` va `balanceUsed > 0` → izohga "To'liq balansdan qoplandı" (`:178-182`).
9. PER_LESSON: `periodEnd = periodStart + max(floor(payable/lessonPrice),1) kun` (taxminiy, `:263-276`); keyin recalc aniqlaydi.
10. `paymentScheduleService.recalculateForStudent(student)` — sana/holat qayta hisoblanadi (`:191`).

#### A.4 To'lov usullari va CASH_AND_CARD validatsiyasi

`PaymentMethod` (`entity/enums/PaymentMethod.java`) — Payment, CashTransaction, Payroll uchun YAGONA ro'yxat:

| Qiymat | Label | Kassa chelagi | Onlayn |
|---|---|---|---|
| CASH | Naqd | CASH | – |
| CARD | Karta | NON_CASH | – |
| CLICK / PAYME / UZUM | … | NON_CASH | **ha** |
| TERMINAL, BANK, OTHER | … | NON_CASH | – |
| CASH_AND_CARD | Naqd + Karta | SPLIT | – |

Eski nomlar qabul qilinadi: `PLASTIC→CARD`, `ONLINE→OTHER`, `BANK_TRANSFER→BANK` (`parseOrNull`). JSON body'da enum sifatida kelganda (masalan `PaymentRequest.paymentMethod`) alias ishlamaydi — faqat String maydonlarda (`paymentMethodForCash`, `PayrollRequest.paymentMethod`) (**TAXMIN**: Jackson enum deserializatsiyasi aliasni bilmaydi).

Validatsiya (`service/CashRegisterService.java:609-629`), faqat kassaga yozilganda:
- SPLIT (CASH_AND_CARD): `cashPart` va `cardPart` MAJBURIY, ikkalasi ≥ 0, `cashPart + cardPart == kassaga_tushadigan_summa` (`compareTo`, scale-sezgir emas). To'lovda bu summa = **`cashAmount`** (gross emas, payable emas!). Xato → 400 (IllegalArgumentException → `exception/GlobalExceptionHandler.java:46-49`).
- Boshqa usullarda yuborilgan `cashPart/cardPart` e'tiborsiz qoldiriladi (null saqlanadi).
- Onlayn usul (CLICK/PAYME/UZUM) + kassada `acceptOnlinePayment=false` → 400 "Bu kassa onlayn to'lovlarni qabul qilmaydi" (`:348-350`).
- Summa ≤ 0 → 400 "Summa musbat bo'lishi kerak" (`:719-724`) — shuning uchun `cashAmount=0` bo'lsa kassa chaqirilmaydi.

#### A.5 Preview: `POST /api/payments/preview` (`service/PaymentService.java:323-346`)

Request `PaymentPreviewRequest`: `studentId*`, `groupId` (hisobga ta'sir qilmaydi), `amount`, `discountAmount`, `useBalance`.
Response `PaymentPreviewResponse`: `gross, discount, payable, balanceUsed, cashAmount, studentBalance, balanceAfter` — `createPayment` bilan aynan bir xil `calculate()`.
Preview **hisoblamaydi**: davr (periodStart/End), oylar soni, PERIOD_CHARGE, bonus/jarima, kassa. Hech narsa saqlanmaydi. Rollar: SUPER_ADMIN, ADMIN, ACCOUNTANT.

Frontend qoidasi: summani o'zi hisoblamasin; CASH_AND_CARD bo'lsa `cashPart+cardPart` ni preview'dagi `cashAmount` ga tenglashtirsin.

#### A.6 Balans qanday hisoblanadi (formula)

Saqlangan balans = ledger yig'indisi: `sg.balance = Σ BalanceTransaction.amount` (`record()` da `after = before + amount`, `service/BalanceTransactionService.java:68-71`). `student.balance = Σ sg.balance` (barcha SG).

Mustaqil "kutilgan" balans (diagnostika) — `BalanceExpectationService.compute` (`service/BalanceExpectationService.java:156-287`):
```
expected = cashIn                         // Σ payment.cashAmount (PAID, shu SG ga bog'langan)
         − periodCost                     // MONTHLY: Σ PeriodChargeFormula.debit(months(gross, fee_sg), discount, fee_sg)
         − lessonCost                     // PER_LESSON: lessonPrice × billable_davomat (paymentStartDate dan)
         + carriedLedger                  // PERIOD_REFUND + MANUAL_ADJUST(ta'mirdan tashqari) + UNFREEZE + FREEZE(faqat PER_LESSON)
diff = expected − sg.balance
```
Musbat balans = oldindan to'langan / ortiqcha; manfiy = qarz.

#### A.7 BalanceTransaction turlari (`entity/enums/BalanceTransactionType.java`)

| Tur | Belgi | Qachon | Kim yozadi | Fayl:qator |
|---|---|---|---|---|
| PAYMENT | + cashAmount | Har bir to'lov (enrollment bo'lsa) | qo'lda (to'lov) | `service/PaymentService.java:221-226` |
| PERIOD_CHARGE | − (months×fee − discount) | MONTHLY to'lov, months ≥ 1 | qo'lda (to'lov) | `service/PaymentService.java:251-256` |
| LESSON_CHARGE | − lessonPrice | PER_LESSON: davomat billable bo'lmagandan → billable (PRESENT/ABSENT/LATE) | davomat | `service/AttendanceService.java:352-358` |
| LESSON_REFUND | + lessonPrice | PER_LESSON: billable → EXCUSED | davomat | `service/AttendanceService.java:359-366` |
| FREEZE | ± delta | Faqat PER_LESSON muzlatishda: `(paid − used) − sg.balance` | qo'lda (freeze) | `service/StudentService.java:982-993` |
| UNFREEZE | −carry / +carry yoki 0 | Muzlatishdan chiqarish | qo'lda (unfreeze) | `service/StudentService.java:1184-1206` |
| MANUAL_ADJUST | ± | SUPER_ADMIN qo'lda tuzatish; yoki `[ledger-repair]` prefiksli avtomatik ta'mir | qo'lda / admin repair | `service/BalanceTransactionService.java:193-213`, `service/MonthlyLedgerRepairWorker.java:83-88` |
| PERIOD_REFUND | + | Enum izohi: "to'lov bekor qilinsa" — **HECH QAYERDA YOZILMAYDI** | — | faqat o'qiladi `service/BalanceExpectationService.java:71-73` |

Scheduler hech qachon ledger yozmaydi (quyidagi A.9 ga qarang). Tur yorliqlari (UI uchun tayyor): `typeLabel()` (`service/BalanceTransactionService.java:215-229`), `GET /api/students/{id}/balance-history` javobida `typeLabel` maydoni bor.

#### A.8 To'lov holati va keyingi to'lov sanasi (`PaymentScheduleService`)

`recalculate(sg)` (`service/PaymentScheduleService.java:104-154`):
- **MONTHLY** `calculateNextPaymentDate` (`:294-314`): enrollmentning oxirgi to'lovi (eng katta periodEnd) → `periodEnd + 1`; periodEnd null → `paymentDate + 1 oy`; to'lov yo'q → `paymentStartDate ?? joinDate`. DIQQAT: SG ning o'z to'lovi bo'lmasa **o'quvchining istalgan guruhdagi oxirgi to'lovi** olinadi (`findLastPaymentForEnrollment`, `:501-520`) — yangi/ikkinchi guruhda paymentStartDate e'tiborsiz qolishi mumkin.
- **PER_LESSON** (`:316-401`): `purchased = floor(Σgross_to'lovlar / lessonPrice)`, `used = count(PRESENT/ABSENT/LATE, joinDate dan)`; `remaining ≤ 0` → kecha (yoki startDate) → OVERDUE; aks holda guruh jadvali bo'yicha qolgan darslar tugaydigan sana (jadval yo'q bo'lsa `ertaga + remaining kun`).
- **Holat** `resolvePaymentStatus` (`:547-581`):
  ```
  o'quvchining (istalgan guruh) BIRORTA to'lovi bor → PAID
  aks holda: SUSPENDED / FROZEN / ARCHIVED saqlanadi (erta qaytadi), isTrial → TRIAL, yo'qsa PENDING
  nextPaymentDate < bugun va ARCHIVED emas → OVERDUE
  PER_LESSON: remaining ≤ 0 va trial emas → OVERDUE (:137-143)
  ```
- O'quvchi agregati (`:171-208`): `nextPaymentDate = min(sg)`, `paymentStartDate = min(sg)`, `monthlyFee = Σ faol SG narxi`, `paymentStatus = eng og'ir` (SUSPENDED/ARCHIVED/FROZEN=5 > OVERDUE=4 > PENDING/PARTIAL/CANCELLED=3 > TRIAL=2 > PAID=1, `:259-270`).
- Faol guruhi yo'q o'quvchi: oxirgi to'lov periodEnd+1 (yoki +1 oy), status PENDING bo'lsa PAID ga (`:210-238`).
- `PaymentStatus` enumida PARTIAL, CANCELLED bor, lekin to'lovlar uchun hech qachon o'rnatilmaydi (to'lov har doim PAID).

#### A.9 Oylik hisob-kitob, proratsiya, scheduled joblar

- **Avtomatik oylik yechish YO'Q.** MONTHLY davr qiymati faqat to'lov paytida (PERIOD_CHARGE) yechiladi. Kun oxirida/oy boshida hech kim balansdan pul yechmaydi → to'lamagan o'quvchining balansi manfiy bo'lmaydi, u faqat sana bo'yicha OVERDUE bo'ladi.
- **Proratsiya YO'Q.** Billing "yubiley" asosida: davr `periodStart` dan N oy (`plusMonths`). Oy o'rtasida qo'shilsa — `paymentStartDate` (default bugun, `service/GroupService.java:537-538`) dan to'liq oy hisoblanadi. Istisno: imtihon to'lovi 30-kunlik proratsiya (`service/ExamPaymentCalculatorService.java`, `monthly × days / 30`, HALF_UP 2) va eskirgan `calculate-debt` (A.13).
- Scheduled joblar (`@EnableScheduling` — `CrmApplication.java:11`, cron JVM zonasi bo'yicha):

| Cron | Metod | Nima qiladi |
|---|---|---|
| `0 5 0 * * *` (00:05) | `PaymentScheduleService.updateOverdueStatusesDaily` (`service/PaymentScheduleService.java:1031-1045`) | ACTIVE/SUSPENDED/FROZEN o'quvchilar uchun `recalculateAllForStudent` → OVERDUE/nextPaymentDate. Ledger yozmaydi. |
| `0 0 8 * * *` (08:00) | `StudentPaymentLifecycleService.archiveInactiveStudents` (`service/StudentPaymentLifecycleService.java:89-127`) | Faol SG (TRIAL emas) bo'yicha oxirgi **ABSENT bo'lmagan** davomat 30 kundan eski bo'lsa: SG `isActive=false`, `leaveDate=bugun`, `paymentStatus="ARCHIVED"`, o'quvchi `status=FROZEN`. Hech qachon davomati bo'lmaganlar tegilmaydi. Ledger/tarix yozilmaydi. |
| `0 0 10 * * *` (10:00) | `PaymentReminderService.sendPaymentReminders` (`service/PaymentReminderService.java:22-60`) | Qarzdorlar (A.12) orasida `daysOverdue ≥ 3` → ota-onalarga Telegram (har kuni takrorlanadi), summa = `student.monthlyFee`. |
| (o'chirilgan) | `checkOverduePayments` (`service/StudentPaymentLifecycleService.java:83-87`) | ishlamaydi |

Moliyaga aloqasiz joblar: `audit/AuditRetentionJob.java:22` (03:30), `service/TokenCleanupService.java:21` (03:00), `service/LeadImportService.java:571` (15 daq), `service/MetaLeadProcessingService.java:44` (15 s).

#### A.10 Muzlatish (freeze) — balansga ta'siri

Endpointlar (SUPER_ADMIN, ADMIN): `POST /api/students/{id}/freeze/preview`, `POST /api/students/{id}/freeze` (body ixtiyoriy: `groupId, reason, note`), `POST /api/students/{id}/unfreeze` (`groupId*`, `paymentStartDate`).

`computeFreezeBreakdown` (`service/StudentService.java:1056-1112`) — preview va freeze bir xil:
- **MONTHLY**: `balance = sg.balance` (qayta hisoblanmaydi), `paid = Σ cashAmount`, `used = paid − balance` (faqat ko'rsatish uchun), `lessonPrice = 0`.
- **PER_LESSON**: `lessonPrice = resolveFreezeLessonPrice`, `used = lessonPrice × billable_darslar(paymentStartDate dan)`, `paid = Σ cashAmount` (SG, 0 bo'lsa student+group), `balance = max(paid − used, 0)`.

`freezeStudent` (`:953-1025`): tanlangan SG(lar) → `isActive=false`, `leaveDate/exitDate=bugun`, `exitReason="FROZEN"`, `paymentStatus="FROZEN"`. PER_LESSON da `FREEZE` yozuvi (delta = hisoblangan − joriy). MONTHLY da ledger yozuvi yo'q, **to'langan davrning ishlatilmagan qismi qaytarilmaydi**. O'quvchi: `status=FROZEN`, `nextPaymentDate=null`, `paymentStatus=FROZEN` — **faqat bitta guruh (`groupId`) muzlatilsa ham** (`:999-1001`). Tarixga `balanceSnapshot` yoziladi.

`unfreezeStudent` (`:1126-1219`): yangi SG (MONTHLY, kurs narxi, `paymentStartDate ?? bugun`). Balans ko'chirish **faqat shu guruhning** oldingi FROZEN SG sidan va faqat `carry > 0` bo'lsa (UNFREEZE −carry eski SG ga, +carry yangi SG ga). Boshqa guruhga qaytsa yoki qarz (manfiy) bo'lsa — ko'chirilmaydi (balans eski SG da qoladi, lekin `student.balance` yig'indisida ko'rinadi).

#### A.11 Guruhdan chiqish / ko'chirish ta'siri

- **transfer-group** `POST /api/students/{id}/transfer-group` (SUPER_ADMIN, ADMIN; `service/StudentService.java:354-434`): `fromGroupId` bo'lsa eski SG yopiladi (`exitReason = reason ?? "TRANSFERRED"`); `toGroup` to'lganini tekshirish; yangi SG `joinDate=paymentStartDate=nextPaymentDate=bugun`, MONTHLY, kurs narxi; o'quvchi `paymentStatus=PENDING`, `monthlyFee` = yangi kurs narxi. **Balans ko'chirilmaydi** (ledger yozuvi yo'q), to'langan davr qaytarilmaydi. Keyin recalc → A.8 dagi fallback tufayli yangi SG `nextPaymentDate` = eski guruhdagi oxirgi `periodEnd+1` bo'lishi mumkin. `fromGroupId` berilmasa eski guruh yopilmaydi (o'quvchi ikki guruhda qoladi).
- **Guruhdan chiqarish** (`service/GroupService.java:600-650`): SG `isActive=false`, `leaveDate`; reason bo'yicha o'quvchi statusi (GRADUATED/LEFT/SUSPENDED; TRANSFERRED — o'zgarmaydi). Balans/davr bo'yicha hech narsa qilinmaydi (refund yo'q).
- **updateStudent** `groupId` bilan — boshqa faol guruhlarni jim yopadi, balans ko'chmaydi (`service/StudentService.java:209-234`).
- **bulk promote** (`service/PromotionService.java:92-165`) — eski SG yopiladi, yangi SG (`joinDate` = maqsad oyning 1-kuni) ochiladi; recalc chaqirilmaydi, sig'im/takror tekshiruvi yo'q.

#### A.12 Qarzdorlar ro'yxati

`GET /api/payments/debtors` → `PaymentScheduleService.getDebtorsByDate` (`service/PaymentScheduleService.java:732-811`). **Balansga emas, sanaga asoslangan**:
```
o'quvchi.status = ACTIVE, faol SG (isActive, leaveDate null) bor,
nextPaymentDate ≤ bugun, paymentStatus ∈ {PENDING, OVERDUE, SUSPENDED}  (TRIAL, PAID chiqariladi)
daysOverdue  = DAYS(nextPaymentDate, bugun)
amount       = student.monthlyFee (barcha faol guruhlar yig'indisi)
monthsUnpaid = max(1, MONTHS(due oyining 1-kuni, bugungi oyning 1-kuni))
totalDebt    = amount × monthsUnpaid
```
Tartib: `daysOverdue` kamayish bo'yicha. `overdue7Plus` = daysOverdue ≥ 7. `groupName` = faqat birinchi faol guruh. `GET /api/payments/debtors/summary` → {totalDebtors, overdue7Plus, totalDebt}.

`GET /api/payments/expected` (`:652-729`): default `from = ertaga`, `to = joriy oy oxiri` (`config/PaymentScheduleConfig.java:18-25`; `SETTING_EXPECTED_PAYMENTS_UNTIL` hali ishlatilmaydi); `nextPaymentDate ∈ (bugun, to] ∩ [from, to]`, TRIAL emas; kunlar bo'yicha guruhlangan; amount = `student.monthlyFee`.

Boshqa (mos KELMAYDIGAN) "qarzdor" ta'riflari — Muammolar bo'limiga qarang.

#### A.13 To'lovni o'chirish / bekor qilish

- **Mavjud emas.** `PaymentController` da DELETE/PUT/cancel endpoint yo'q (`controller/PaymentController.java:33-124`), servisda ham metod yo'q. `PERIOD_REFUND` hech qachon yozilmaydi, `PaymentStatus.CANCELLED` to'lovga o'rnatilmaydi.
- Frontend "to'lovni bekor qilish" tugmasini ko'rsatmasin. Xato to'lovni tuzatish yo'li hozircha: SUPER_ADMIN `POST /api/students/{id}/balance-adjust` (`groupId*, amount*, note*` → MANUAL_ADJUST) + kassada alohida chiqim. Payment yozuvi, Income va nextPaymentDate o'zgarmaydi.
- `GET /api/payments/calculate-debt` (`service/PaymentService.java:730-787`) — eskirgan formula: `(kunlar/30) × oylik − Σgross`, `double` arifmetika; balans/ledger bilan bog'liq emas. Yangi UI da ishlatmaslik tavsiya.

#### A.14 `db/manual/unify_payment_methods.sql` nima uchun

`src/main/resources/db/manual/unify_payment_methods.sql` — **qo'lda** ishga tushiriladigan (Flyway o'chiq) bir martalik tozalash skripti: eski usul nomlarini yagona `PaymentMethod` enumiga keltiradi.
- `cash_transactions`: PLASTIC→CARD, ONLINE→OTHER.
- `payroll.payment_method`: BANK_TRANSFER→BANK, PLASTIC→CARD, ONLINE→OTHER, qolgan tanilmaganlar → NULL (aks holda Hibernate `@Enumerated(STRING)` o'qishda yiqiladi).
- `payments`: ONLINE→OTHER, BANK_TRANSFER→BANK, PLASTIC→CARD.
- Tekshiruv SELECT'lari; eski CASH_AND_CARD yozuvlarida `cash_part/card_part` NULL — kod ularni butun summa naqd deb oladi va `log.warn` yozadi (`service/CashRegisterService.java:644-649`).
Frontend uchun oqibat: API faqat 9 ta qiymat qaytaradi (CASH, CARD, CLICK, PAYME, UZUM, TERMINAL, BANK, CASH_AND_CARD, OTHER); filtrlarda eski nomlar ham qabul qilinadi.

#### A.15 Admin diagnostika/ta'mir (SUPER_ADMIN, `/api/admin/repair/**`)

- `GET /verify-balances` — har bir SG uchun A.6 formulasi, farqlar komponentlari bilan (`service/BalanceTransactionService.java:141-187`).
- `POST /rebuild-monthly-ledger?dryRun=true` (default dryRun) — MONTHLY SG farqini bitta `[ledger-repair]` MANUAL_ADJUST bilan yopadi; bog'lanmagan to'lov yoki MONTHLY da UNFREEZE bo'lsa o'tkazib yuboradi (`service/MonthlyLedgerRepairWorker.java:35-91`).
- `POST /recalculate-payment-dates`, `POST /fix-payment-periods` — sana/holat va bo'sh periodlarni to'ldirish (`service/PaymentScheduleService.java:823-926`).

---

### B) Davomat

#### B.1 Pul yechadigan statuslar
`AttendanceStatus = PRESENT | ABSENT | EXCUSED | LATE`. **Billable** (PER_LESSON da pul yechadi, darslar sanog'iga kiradi): `PRESENT, ABSENT, LATE`; `EXCUSED` — yechmaydi (`service/AttendanceService.java:369-373`, `service/PaymentScheduleService.java:47-48`).
- `status=ABSENT` + `excused=true` → saqlashda `EXCUSED` ga aylanadi (`service/AttendanceService.java:116-120`).
- ABSENT/EXCUSED/LATE uchun `notes` yoki `excuseReason` majburiy → aks holda 400 "Sabab kiritilishi shart" (`:86-92`).
- `status` null → PRESENT.

#### B.2 PER_LESSON
`applyBalanceForAttendanceChange` (`:332-367`): faqat faol SG va `paymentType=PER_LESSON`, `lessonPrice > 0`:
- oldin billable emas (yoki yangi yozuv) → endi billable: `LESSON_CHARGE −lessonPrice`;
- billable → EXCUSED: `LESSON_REFUND +lessonPrice`;
- billable ↔ billable (PRESENT→LATE va h.k.) — pul o'zgarmaydi.
Keyin `recalculate` (qolgan darslar, OVERDUE). Trial (`isTrial`) PER_LESSON o'quvchidan ham yechiladi (bepul dars mantiqi yo'q).

#### B.3 MONTHLY da davomat ta'siri
Balansga ta'sir **yo'q** (ikki marta yechmaslik uchun, `:325-331`). Faqat: `lessonsAttended++`, `firstLessonDate` (PRESENT/LATE, `service/StudentPaymentLifecycleService.java:38-63`); 30 kunlik avto-arxiv (A.9) oxirgi ABSENT-bo'lmagan davomatga qaraydi. ABSENT bo'lsa ota-onaga Telegram xabar (`service/AttendanceService.java:139-164`).

#### B.4 Davomat qulfi va ruxsatlar
`POST /api/attendance/mark` (SUPER_ADMIN, ADMIN, TEACHER) → `markAttendance` (`:56-168`):
1. TEACHER faqat **o'z guruhiga** (`group.teacher == current teacher`) — aks holda 403 "Bu guruh sizga tegishli emas" (`service/TeacherAccessService.java:82-92`). Admin/Super admin — cheklovsiz.
2. Dars kuni tekshiruvi (admin uchun ham): sana hafta kuni `GroupScheduleDay` yoki `Timetable` da bo'lmasa 400 "Bu kunda guruhda dars yo'q (Dushanba)" (`:171-191`).
3. **Qulf**: TEACHER uchun `date < bugun` (server sanasi) → faqat shu (teacher, group, date) uchun `APPROVED` unlock so'rovi bo'lsa ruxsat, aks holda 403 "Bu kun uchun ruxsat kerak. Admindan so'rang." (`:67-80`). Soatga asoslangan qulf yo'q — kechagi kun 00:00 dan yopiladi. Admin (ADMIN/SUPER_ADMIN) har qanday sanani o'zgartira oladi.
4. **Kelajak sanaga cheklov YO'Q** — o'qituvchi ertangi darsga ham davomat yoza oladi (PER_LESSON da darhol pul yechiladi).
5. Item'dagi `studentId` guruh a'zosi ekanligi tekshirilmaydi (davomat yoziladi, lekin SG topilmagani uchun pul yechilmaydi).

#### B.5 AttendanceUnlockRequest oqimi (`service/AttendanceUnlockRequestService.java`)
- `POST /api/attendance/unlock-requests` (TEACHER): `{groupId*, attendanceDate*, note}`; o'z guruhi bo'lishi shart; shu kun uchun PENDING bor bo'lsa 400 "So'rov allaqachon yuborilgan" (`:40-63`). Sana validatsiyasi yo'q (bugun/kelajak uchun ham so'rash mumkin).
- `GET /unlock-requests` (SUPER_ADMIN, ADMIN): `status` (default PENDING), `groupId`, `date` filtrlari; `GET /my` (TEACHER): `groupId`, `date` filtrlari (frontend aynan shu sana/guruh bilan so'rasin — `:79-95` izohi); `GET /count` — PENDING soni.
- `PATCH /{id}/approve` (SUPER_ADMIN, ADMIN), body ixtiyoriy `{penaltyAmount, penaltyReason}` → APPROVED; `penaltyAmount > 0` bo'lsa o'qituvchiga `PENALTY` (PENDING, effectiveDate=bugun, sabab default "Davomatni kech kiritish uchun jarima") yaratiladi → keyingi payroll generatsiyasida ushlab qolinadi (`:97-128`).
- `PATCH /{id}/reject` → REJECTED. Faqat PENDING ko'rib chiqiladi, aks holda 400.
- Tasdiqlangan ruxsat **muddatsiz** va ko'p marta ishlatiladi (bekor qilish mexanizmi yo'q).

---

### C) Oylik (Payroll), SalaryRule, KPI, Bonus/Jarima

> **02.10.2026: payroll v2 bilan qayta qurildi** — quyidagi C.1, C.2, C.4, C.5 v1 holatini tasvirlaydi. Joriy qoidalar:
> [`docs/design/payroll-v2.md`](../design/payroll-v2.md), API — [`payroll-v2-api.md`](../design/payroll-v2-api.md).

#### C.1 SalaryRule (`entity/SalaryRule.java`, CRUD faqat SUPER_ADMIN: `/api/salary-rules`)
Qoida turi alohida enum emas — bitta qoida maydonlari: `role*`, `userId` (null = rol uchun umumiy), `baseSalary`, `perStudentFee`, `newStudentBonus`, `kpiThreshold` (int), `kpiBonus`, `isActive`, `effectiveFrom`. Foizli qoida YO'Q. DELETE = soft (`isActive=false`, `service/SalaryRuleService.java:46-52`).

Tanlash `resolveRule(user, asOf = oy oxiri)` (`repository/SalaryRuleRepository.java:45-58`): avval shaxsiy faol qoida (`effectiveFrom ≤ asOf`, eng so'nggi effectiveFrom, NULL oxirida, keyin id desc), bo'lmasa rol qoidasi (`user IS NULL`). Oy o'rtasida kuchga kirgan qoida butun oyga qo'llanadi.

#### C.2 Hisoblash formulasi (`service/SalaryCalculationService.java`)
Hisoblanadigan rollar: `TEACHER, ADMIN, SALES_MANAGER` (`:46-47`). ACCOUNTANT, SUPER_ADMIN — hisoblanmaydi. `calculateAll` da qoidasiz ADMIN ro'yxatdan tushiriladi, qoidasiz TEACHER/SALES "Oylik qoidasi topilmadi" (`calculable=false`) bo'lib ko'rinadi (`:57-82`).

- **TEACHER** (`:148-217`):
  ```
  paidCount = DISTINCT (student, group) juftliklari: shu oyda PAID to'lov bor, group.teacher = shu o'qituvchi (hozirgi)
              (repository/PaymentRepository.java:285-303)
  total = baseSalary + perStudentFee × paidCount + (Σ PENDING BONUS − Σ PENDING PENALTY, effectiveDate ≤ oy oxiri)
  ```
  O'qituvchi profili (Teacher.user) bog'lanmagan → `calculable=false`, "O'qituvchi profili bog'lanmagan".
- **ADMIN** (`:220-271`):
  ```
  newCount = attributed_user_id = shu user bo'lgan, BIRINCHI to'lovi (MIN(id)) shu oyda bo'lgan o'quvchilar
  kpiApplied = kpiThreshold != null && COUNT(student.status = ACTIVE, butun markaz, HOZIRGI) ≥ kpiThreshold
  total = baseSalary + newStudentBonus × newCount + (kpiApplied ? kpiBonus : 0)
  ```
- **SALES_MANAGER** (`:273-313`): `total = baseSalary + newStudentBonus × newCount` (KPI yo'q).
- ADMIN/SALES uchun bonus/jarima qo'llanmaydi (BonusTargetType faqat STUDENT/TEACHER).

#### C.3 KPI (TeacherKpiService) — oylikka ta'sir QILMAYDI
`service/TeacherKpiService.java:145-227` — faqat reyting/analitika (payroll'da `kpiApplied` o'qituvchi uchun doim false). 4 ko'rsatkich, **teng og'irlik**, faqat mavjud (null bo'lmagan) mezonlarning o'rtachasi:
```
attendanceRate    = (PRESENT+LATE) / barcha davomat × 100           — [from,to], o'qituvchining guruhlari
paymentRate       = SG.paymentStatus='PAID' / faol SG × 100          — HOZIRGI holat (from/to ga bog'liq emas)
onTimePaymentRate = (billable − debtor) / billable × 100             — billable = TRIAL emas; debtor = OVERDUE yoki nextPaymentDate < bugun
retentionRate     = (faol + GRADUATED) / (faol + GRADUATED + chiqqan_boshqa) × 100   — chiqishlar [from,to]
overallScore      = mavjudlarining o'rtachasi, round1 (double)
```
Hech qanday ma'lumot yo'q → `insufficientData=true`. Reyting tartibi: insufficient oxirida, keyin score kamayish, keyin ism (`:121-131`). Trend: daily yoki monthly (default 6 oy, max 12) (`:54-89`, `:252-268`).

#### C.4 BonusPenalty (`/api/bonus-penalties`, SUPER_ADMIN/ADMIN/ACCOUNTANT)
- `kind`: BONUS | PENALTY; `targetType`: STUDENT (studentId majburiy) | TEACHER (teacherId majburiy); `amount > 0` (ishora `kind` dan, DTO da `signedAmount`); `effectiveDate` default bugun (`service/BonusPenaltyService.java:257-290`).
- `status`: **PENDING → APPLIED** (avtomatik, tasdiqlash qadami YO'Q) yoki **PENDING → CANCELLED** (`PATCH /{id}/cancel`). Faqat PENDING tahrirlanadi/bekor qilinadi/o'chiriladi (`:234-255`).
- TEACHER: `generatePayroll`/`createPayroll`/`updatePayroll(yangi)` da `effectiveDate ≤ oy oxiri` bo'lgan barcha PENDING → APPLIED, `appliedToPayrollId` (`:138-161`); `net = ΣBONUS − ΣPENALTY` → `payroll.bonusPenaltyAdjustment`, `netSalary += net`.
- STUDENT: to'lov yaratilganda APPLIED (A.3 7-qadam) — pulga ta'sir qilmaydi.
- Preview: `GET /preview/teacher/{id}`, `GET /preview/student/{id}` (`cutoff` ixtiyoriy).

#### C.5 Payroll holatlari va oqim (`service/PayrollService.java`, `/api/payroll`)
Holat — **String**: `"PENDING"` | `"PAID"` (draft/approved yo'q; `createPayroll`/`PUT` da ixtiyoriy string qabul qilinadi).
- `GET /calculate?month&year` / `GET /calculate/{userId}` — faqat preview (C.2).
- `POST /generate?month&year&overwrite=false` (`:86-169`): shu oyda biror payroll bor va `overwrite=false` → 400 "Bu oy uchun oylik yaratilgan". Har bir calculable user uchun: PAID bo'lsa o'tkaziladi; aks holda yaratiladi/yangilanadi:
  ```
  basicSalary = base;  allowances = perStudentAmount + newStudentAmount + kpiAmount;  deductions = 0
  netSalary   = total − preview_bp + applyPendingForTeacher(...)
  paymentMethod = BANK (yangi uchun), status = PENDING
  ```
  Javob: `{created, skipped, totalAmount}`.
- `POST /` (qo'lda, teacherId bo'yicha): `netSalary = basic + allowances + bp` (so'rovdagi `deductions`, `netSalary` e'tiborsiz) (`:175-202`, `:386-405`).
- `PUT /{id}` — upsert (id topilmasa yangi yaratadi); PAID va net o'zgargan bo'lsa eski kassa chiqimlarini (izoh `LIKE "%Oylik to'lovi (m/y)%"`) o'chirib, yangisini yozadi, CASH_AND_CARD da naqd qism saqlanadi (`:208-290`, `:354-370`).
- `POST /{id}/pay` `{paymentMethod, cashRegisterId, paymentMethodForCash, cashPart, cardPart}` (`:301-337`): `status=PAID`, `paymentDate=bugun`; `cashRegisterId` bo'lsa kassaga EXPENSE (`netSalary`, kassa manfiyga tushishi mumkin). Allaqachon PAID ekanligi tekshirilmaydi.
- `DELETE /{id}` (SUPER_ADMIN, ADMIN) — faqat yozuvni o'chiradi (kassa/bonuslar qaytarilmaydi).
- **Qayta hisoblash**: `generate?overwrite=true` — faqat PENDING payrolllar qayta yoziladi; PAID lar tegilmaydi.

---

### D) Kassa

#### D.1 CashRegister (`/api/cash-registers`, SUPER_ADMIN/ADMIN/ACCOUNTANT)
- **Ochish/yopish (smena) tushunchasi YO'Q.** `CashRegisterStatus = ACTIVE | ARCHIVED` (`entity/enums/CashRegisterStatus.java`). `PATCH /{id}/status`, `PUT /{id}` (`archived`), `DELETE /{id}` — tranzaksiyasi bor bo'lsa arxivlanadi ("Tranzaksiyalari bor, arxivlandi"), yo'q bo'lsa o'chiriladi (`service/CashRegisterService.java:126-137`).
- ARCHIVED kassaga kirim/chiqim/o'tkazma bloklanmaydi (status tekshirilmaydi) — frontend o'zi filtrlab ko'rsatsin.
- Balanslar: `cashBalance` (naqd), `plasticBalance` (naqdsiz), `balance = cash + plastic` (`:695-697`). Taqsimot — A.4 jadvalidagi chelak.
- `acceptOnlinePayment` — CLICK/PAYME/UZUM kirimlari uchun shart (faqat kirimda tekshiriladi).

#### D.2 CashTransaction
`type`: `INCOME | EXPENSE | TRANSFER`; `status`: `COMPLETED | PENDING | CANCELLED` — amalda doim COMPLETED (`entity/CashTransaction.java:69`), PENDING/CANCELLED ishlatilmaydi.

| Manba | Kassa yozuvi | Kassa balansi |
|---|---|---|
| O'quvchi to'lovi (`cashRegisterId` bilan) | INCOME, `amount=cashAmount`, student bog'langan, nomi "O'quvchi to'lovi", izoh "To'lov #RCP-…" | + (chelak bo'yicha) |
| `POST /{id}/income` (qo'lda kirim) | INCOME; `transactionType` → `transactionName` | + ; `studentId` bo'lsa `student.balance += amount` to'g'ridan-to'g'ri (ledgersiz!) (`:395-400`) |
| `POST /{id}/expense` (qo'lda chiqim) | EXPENSE (`periodMonth`, `totalAmount`, student) | − (manfiyga tushishi mumkin) |
| `POST /api/expenses` yoki `/api/finance/expenses` + `cashRegisterId` | `Expense` + EXPENSE "Xarajat: {category}" | − (manfiy mumkin) |
| Payroll `pay` + `cashRegisterId` | EXPENSE "Oylik: {ism}", izoh "Oylik to'lovi (m/y)", teacher bog'langan | − (manfiy mumkin) |
| `POST /transfer` | IKKI TRANSFER qatori: manba kassada "Ko'chirish (chiqim)", maqsadda "Ko'chirish (kirim)", `targetCashRegister` o'zaro; sana = bugun | manba − (har chelakda yetarli bo'lishi SHART, aks holda 400 "Naqd/Plastik balans yetarli emas"), maqsad + |

TRANSFER qatorlari bir xil `type` — yo'nalishni `transactionName` yoki `targetCashRegisterId` orqali ajrating (`service/CashRegisterService.java:535-589`). Kassa tranzaksiyasini o'chirish endpointi yo'q (`deleteExpense` faqat payroll ichida ishlatiladi).

`GET /{id}/transactions` — Spring `Page` (from, to, studentId, teacherId, type, paymentMethod filtrlari; eski nomlar ham), `GET /{id}/transactions/export` — XLSX.

#### D.3 Expense / Income kategoriyalari
- `ExpenseCategory = SALARY | RENT | MARKETING | UTILITIES | EQUIPMENT | OTHER` (`entity/enums/ExpenseCategory.java`). `ExpenseRequest`: `category*`, `title*`, `amount* ≥ 0.01`, `expenseDate*`, `teacherId`, `description`, `notes`, `cashRegisterId`, `paymentMethodForCash` (default CASH), `cashPart`, `cardPart`.
- `IncomeCategory = STUDENT_PAYMENT | OTHER_INCOME`. `Income` faqat o'quvchi to'lovida yoziladi (`service/PaymentService.java:152-161`); `OTHER_INCOME` hech qayerda yozilmaydi; kassaga qo'lda kirim `Income` yaratmaydi.
- Payroll to'lovi `Expense(SALARY)` yaratmaydi.

#### D.4 Moliya hisobotlari

> **02.10.2026 (payroll v2 §11 #8):** `/api/finance/report` ga `payrollPaid`, `payrollByRole` qo'shildi; `netProfit = income − expenses − payrollPaid` ([`payroll-v2-api.md` §6](../design/payroll-v2-api.md)).
- `GET /api/finance/report?from&to` (default: oy boshi → bugun) (`service/FinanceService.java:141-171`):
  ```
  totalIncome  = Σ COALESCE(payment.cashAmount, payment.amount), status=PAID, paymentDate ∈ [from,to]
  totalExpenses= Σ Expense.amount, expenseDate ∈ [from,to]
  netProfit    = totalIncome − totalExpenses
  incomeByCategory  = Income jadvalidan (amalda faqat STUDENT_PAYMENT)
  expenseByCategory = Expense jadvalidan
  ```
  Hisobotga **kirmaydi**: payroll to'lovlari, kassa orqali qo'lda kiritilgan kirim/chiqimlar, transferlar.
- `GET /api/payments/stats` (`service/PaymentService.java:582-597`): `totalCollected` (Σ naqd, PAID), `totalPending` (PENDING gross — amalda 0), `thisMonth`, `lastMonth` (naqd, kalendar oy).
- `GET /api/payments` — `data` = Spring `Page<PaymentResponse>` (sort createdAt desc, default size 50), `meta` = `PaymentSummary{totalAmount (Σgross), totalCashAmount (Σnaqd), totalCount}` butun filtr bo'yicha; aggregat yiqilsa `meta` bo'lmaydi (`controller/PaymentController.java:33-55`).
- Analytics oylik tushum = Σ naqd (monthStart..monthEnd), xarajat = Σ Expense (monthStart..**bugun**) (`service/AnalyticsService.java:58-64`).

---

### E) Contract va Promotion

- **Contract** (`service/ContractService.java`): shablondan matn render qilinadi (`{{monthlyFee}}` = `sg.monthlyPriceOverride ?? course.monthlyPrice`, `stripTrailingZeros().toPlainString()`, `:196`, `:241-256`). Holat: `DRAFT → SIGNED` (`markSigned`, istalgan turdan) yoki OFFER turida `DRAFT → ACCEPTED` (`acceptOffer`, boshqa tur → 400) (`:136-152`). Raqam `CTR-%04d(count()+1)`. **To'lovga hech qanday ta'siri yo'q** (summa/davr/chegirma yaratmaydi).
- **Promotion** — bu chegirma aksiyasi EMAS, balki sinf/guruhga **o'tkazish** (akademik ko'chirish) yozuvi (`service/PromotionService.java`). Faqat `bulkPromote` moliyaga tegadi (A.11): eski SG yopiladi, yangi SG `monthlyPriceOverride` va `discountPercentage` ni meros oladi, lekin balans/rejim ko'chmaydi, recalc chaqirilmaydi. Chegirma aksiyalari tizimi yo'q; chegirma faqat to'lovdagi `discountAmount` (summa).

---


---

### 6.1 Lid (Lead)

#### Ko'rish doirasi (hamma lid amallari uchun umumiy)
- `service/LeadAccessService.java:38-42` — operator bo'la oladigan rollar: SUPER_ADMIN, ADMIN, SALES_MANAGER; to'liq huquq: SUPER_ADMIN, ADMIN.
- `resolveOperatorScope()` (`service/LeadAccessService.java:83-92`): ADMIN/SUPER_ADMIN → cheklovsiz; SALES_MANAGER → faqat `assignedUser == o'zi`; boshqa rol → 403.
- `assertCanAccessLead(lead)` (`:103-112`): SALES_MANAGER uchun biriktirilmagan (`assignedUser == null`) lid ham **ko'rinmaydi** (403).
- `getAll` (`service/LeadService.java:206-223`): SALES_MANAGER uchun `assignedUserId` majburan o'ziga, `unassigned` filtri e'tiborsiz; `size` 1..100.
- URL qoidasi: `config/SecurityConfig.java:134-135` (`/api/leads/**` → SUPER_ADMIN, ADMIN, SALES_MANAGER) — bu qoida DELETE catch-all (`:196`) dan OLDIN turadi, shuning uchun `DELETE /api/leads/notes/{id}` SALES_MANAGER ga ham URL darajasida ochiq, cheklov servisda.

#### Yaratish
| Yo'l | Controller | Servis | Qoidalar |
|---|---|---|---|
| `POST /api/leads/public` (ochiq forma, JWT'siz) | `controller/LeadController.java:61-68` (`@PreAuthorize("permitAll()")`, URL: `SecurityConfig.java:86`) | `LeadService.createLead` `service/LeadService.java:107-130` | DTO `dto/request/LeadRequest.java` (fullName, phone — `@NotBlank`; parentPhone, address, course, format, source, notes). `format` default `OFFLINE`, `source` default `WEBSITE` (ikkalasi `toUpperCase`). Status har doim `Lead.DEFAULT_STATUS = "NEW"` (`entity/Lead.java:21`). `assignedUser`/`createdBy` = null → kanbanda "Biriktirilmagan". Anonim chaqiruvchi status/operator bera olmaydi (alohida DTO — `dto/request/LeadCreateRequest.java:8-16` izohi). |
| `POST /api/leads` (xodim) | `controller/LeadController.java:75-80` | `LeadService.createLeadByStaff` `service/LeadService.java:142-174` | `LeadCreateRequest` + `status` (berilmasa NEW; berilsa `LeadStageService.requireActiveCode` — mavjud va faol bosqich bo'lishi shart, `service/LeadStageService.java:196-209`) + `assignedUserId`. Operator qoidasi `resolveLeadAssignee` (`:186-203`): berilmasa SALES_MANAGER o'ziga oladi, ADMIN da null; SALES_MANAGER boshqa odamga biriktira olmaydi (403); nishon rol SUPER_ADMIN/ADMIN/SALES_MANAGER va `isActive=true` bo'lishi shart. `createdBy = joriy user`. |

**Dublikat tekshiruvi (telefon bo'yicha) — YO'Q.** `service/LeadService.java:138-140` izohi ochiq aytadi: na `/public`, na `POST /api/leads` telefon dublikatini tekshirmaydi; `leads.phone` da unique indeks yo'q. Dublikat tekshiruvi faqat: (a) Meta ingest (6.2), (b) lid importi `skipDuplicates=true` bo'lganda (quyida), (c) konvertatsiyada `students.phone` bo'yicha.

**Konvert bosqichida yaratish:** `createLeadByStaff` `requireActiveCode` dan foydalanadi, lekin `isConverted` tekshiruvi yo'q — ya'ni xodim lidni to'g'ridan-to'g'ri `CONVERTED_*` bosqichida yarata oladi (qarang Muammolar).

#### Telefon normalizatsiyasi (PhoneUtils)
- `util/PhoneUtils.java:31-50` `canonical(raw)`: raqamdan boshqa hammasi tashlanadi, keyin:
  - 12 raqam va `998` bilan boshlansa → `+998XXXXXXXXX`;
  - 9 raqam → `+998` + 9 raqam;
  - 10 raqam va `8` bilan boshlansa → `8` tashlanib `+998` + 9 raqam;
  - boshqa hollar → `null` (tanilmadi).
- Kanonik format: **`+998XXXXXXXXX`** (13 belgi).
- `canonicalOrRaw` (`:60-63`) — tanilmasa XOM qiymat qaytadi (validatsiya foydalanuvchi yozganini ko'rsin).
- `canonicalDigits` (`:73-76`) — dublikat kaliti `998XXXXXXXXX`.
- HTTP da qo'llanishi: `config/PhoneDeserializer.java:26-33` (Jackson, validatsiyadan oldin) — `LeadRequest.phone/parentPhone` (`dto/request/LeadRequest.java:15,18`), `LeadCreateRequest.phone/parentPhone` (`dto/request/LeadCreateRequest.java:24,27`). Lid DTO larida `@Pattern` yo'q — tanilmagan xom matn (masalan "abc") ham `leads.phone` ga tushadi.
- Talaba importi PhoneUtils ni ISHLATMAYDI — o'z `normalizePhone`/`phoneDigits` (`service/ImportService.java:995-1018`): faqat shovqin (probel, apostrof, qavs, tire, nuqta) olib tashlanadi, `+998` qo'shilmaydi; 9..13 raqam bo'lsa "yaroqli" (qarang Muammolar).

#### Bosqich (status) o'zgartirish — `PATCH /api/leads/{id}/status`
Controller `controller/LeadController.java:152-159`, servis `LeadService.updateStatus` `service/LeadService.java:320-383`, DTO `dto/request/LeadStatusRequest.java` (`status` `@NotBlank`, `amount` ixtiyoriy).
1. Lid doirasi tekshiriladi (`:328`).
2. Yangi kod `requireActiveCode` — bosqich `lead_stages` da bo'lishi va faol bo'lishi shart (`:330`). Bosqichlar bazada (`entity/Lead.java:54-63`), enum emas.
3. **Konvert bosqichiga qo'lda KIRISH taqiqlangan** (`:347-349`) — faqat `convertToStudent` orqali. Konvert bosqichidan CHIQISH ruxsat etilgan (noto'g'ri konvertatsiyani bekor qilish uchun), o'sha bosqichga qayta saqlash ham bloklanmaydi. REJECTED bosqichi erkin.
4. `amount` berilsa: manfiy → 400 (`:351-357`), berilsa eski qiymat ustiga yoziladi.
5. Bosqich `requiresAmount=true` bo'lsa va lidda hali summa yo'q → 400 `lead.amount.required` (`:362-365`). Bir marta yozilgan summa keyingi to'lov bosqichida qayta so'ralmaydi.
6. Status o'zgarsa → `AuditContext.change` + `LeadStatusHistory` yozuvi (`:370-373`). Bir xil bosqichga qayta o'tish tarix yozmaydi.
7. `requiresAmount` bosqichga o'tganda (yoki o'sha bosqichda qayta saqlanganda) tizim izohi `"To'lov qabul qilindi: 850 000 UZS"` (`LeadComment`) qo'shiladi (`:378-380`, `:386-391`). Anonim bo'lsa izoh yozilmaydi (`:935-939`).

Summani alohida tuzatish — `PATCH /api/leads/{id}/amount` (`controller/LeadController.java:165-172`, `service/LeadService.java:404-439`): manfiy → 400; lid `requiresAmount` bosqichida bo'lsa `amount=null` (o'chirish) → 400; o'zgarmagan qiymat → `AuditContext.skip()`, hech narsa yozilmaydi; aks holda tizim izohi "To'lov summasi o'zgartirildi: X → Y UZS".

Bosqich boshqaruvi (`controller/LeadStageController.java`): o'qish — har qanday autentifikatsiyalangan (`SecurityConfig.java:146`), yozish — SUPER_ADMIN/ADMIN (`LeadStageController.java:36,45,54,62`). O'chirish: yakuniy (CONVERTED/REJECTED kind) bosqich o'chirilmaydi, lidi bor bosqich o'chirilmaydi (`service/LeadStageService.java:144-162`). Konvert bosqichlari bir nechta bo'lishi mumkin (`:225-227`).

#### Izoh (LeadComment) va eslatma (LeadNote)
| | LeadComment | LeadNote |
|---|---|---|
| Endpoint | `POST/GET /api/leads/{id}/comments` (`LeadController.java:174-189`) | `POST/GET /api/leads/{leadId}/notes`, `PUT/DELETE /api/leads/notes/{id}` (`LeadController.java:211-239`) |
| Servis | `addComment` `LeadService.java:461-482`, `getComments` `:484-504` (PageResponse, size 1..100, yangidan eskiga) | `addNote` `:512-524`, `getNotes` `:526-530` (List), `updateNote` `:544-553`, `deleteNote` `:556-564` |
| Maydonlar | author, text, `statusAtComment` (izoh paytidagi bosqich), createdAt (`entity/LeadComment.java:27-37`) | text, createdBy, createdAt, updatedAt (`entity/LeadNote.java:39-43`) |
| Tahrir/o'chirish | Endpoint yo'q | Tahrir — faqat muallif (admin ham emas, `:548-550`); o'chirish — muallif yoki SUPER_ADMIN/ADMIN (`:559-562`) |
| Audit | `@Audited(COMMENT)` `:462` | Audit YO'Q |
| Tizim yozuvlari | To'lov/summa izohlari shu yerga (`:935-947`) | Import izohlari (`service/LeadImportService.java:333-340`), Meta "takroriy murojaat" izohi (`service/MetaLeadIngestService.java:159-162`, `createdBy=null`) |

#### Status tarixi (LeadStatusHistory)
- Entity: fromStatus, toStatus, changedBy (null bo'lishi mumkin), changedAt, note (`entity/LeadStatusHistory.java:40-54`).
- Yoziladi FAQAT: `updateStatus` (`LeadService.java:372`) va `convertToStudent` (`:691-694`, note `"O'quvchiga aylantirildi"`). `recordStatusChange` `:925-933`.
- Yaratilishda (public, staff, import, Meta) tarix yozuvi YO'Q — birinchi yozuvning `daysInPreviousStatus = null` (`:233-240`, `:261-286`).
- `GET /api/leads/{id}/history` (`LeadController.java:242-246`) — yangidan eskiga, `daysInPreviousStatus` qo'shni yozuvlar farqidan.
- Yagona lenta: `GET /api/leads/{id}/timeline` → `service/LeadTimelineService.java:85` (vazifalar, bosqich o'tishlari, izohlar, mas'ul almashuvi; ochiq vazifalar `openTasks` da alohida).

#### Operator biriktirish — `PATCH /api/leads/{id}/assign`
`controller/LeadController.java:141-149` (faqat SUPER_ADMIN/ADMIN), `service/LeadService.java:288-318`: `userId=null` → biriktirishni olib tashlash; nishon rol OPERATOR_ROLES da bo'lishi shart. `isActive` TEKSHIRILMAYDI (createLeadByStaff dan farqli). `@Audited(ASSIGN)` + `AuditContext.change("assignedUser")`.

#### Konvertatsiya (lid → talaba) — `POST /api/leads/{id}/convert`
Controller `controller/LeadController.java:253-260` (tana MAJBURIY, `@Valid`), servis `LeadService.convertToStudent` `service/LeadService.java:605-703`, DTO `dto/request/LeadConvertRequest.java` (`studyFormat` `@NotNull` ONLINE/OFFLINE; ixtiyoriy: groupId, paymentStartDate, monthlyFee, paymentType, lessonPrice, isTrial). Javob `LeadConvertResponse` {id (student), admissionNumber, paymentStatus, nextPaymentDate, leadId}.

Qadamlar:
1. Doira tekshiruvi (`:612`).
2. **Qayta konvertatsiya mumkin EMAS**: `converted=true` YOKI status konvert bosqichida YOKI `lead.student != null` → 400 (`:614-618`). Konvert bosqichidan qo'lda chiqarilgan lid ham `converted=true`/`student` saqlangani uchun qayta konvert qilinmaydi.
3. `studentRepository.findByPhone(lead.getPhone())` topilsa → `DuplicateResourceException` (`:622-625`) — aniq satr solishtiruvi.
4. **Student** yaratiladi (`:632-657`): ism `fullName` ning birinchi so'zi, familiya qolgani (bo'sh bo'lsa `"-"`); phone, address, notes lidan; `status=ACTIVE`, `admissionDate=bugun`, `admissionNumber` avtomatik (`StudentService.generateNextAdmissionNumber`), `convertedFromLeadId`, `marketingSource` (lid `source` → `MarketingSource`, tanilmasa OTHER, `:957-966`), `paymentStatus=PENDING`, `createdBy=konvert qilgan user`, `attributedUserId = lid operatori (bo'lmasa konvert qilgan)`.
5. **Parent**: `lead.parentPhone` bo'lsa `StudentService.syncParentFromPhone` (`service/StudentService.java:245-255`) — relation `OTHER`, `isPrimary=true`.
6. **StudentGroup**: faqat `groupId` berilsa `GroupService.addStudentToGroup` (`service/GroupService.java:520-594`): guruh to'lganmi/allaqachon a'zomi tekshiruvi, `joinDate=bugun`, `paymentStartDate` (default bugun), `paymentType` (default MONTHLY), fee (request → override → kurs narxi → 0), `isTrial` → `paymentStatus TRIAL/PENDING`, `studyFormat`.
7. **To'lov rejasi**: alohida jadval yaratilmaydi; `paymentScheduleService.recalculateForStudent(student)` chaqiriladi (`GroupService.java:590`) — `nextPaymentDate` hisoblanadi. Guruhsiz konvertatsiyada bu qadam yo'q.
8. Lid: `student`, `converted=true`, `status = convertedCodeFor(studyFormat)` — `CONVERTED_ONLINE`/`CONVERTED_OFFLINE`, topilmasa `kind=CONVERTED` bo'lgan birinchi bosqich (`service/LeadStageService.java:252-266`). Status `updateStatus` ni chetlab to'g'ridan-to'g'ri yoziladi (`LeadService.java:682-689`). Tarix yozuvi.
9. Lidning OCHIQ vazifalari yopilmaydi/bekor qilinmaydi (qarang 6.3).
10. Audit: `@Audited(CREATE, entity="Student")` (`:606`) + ichkarida `GroupService.addStudentToGroup` ham `@Audited(CREATE, "StudentGroup")` (`GroupService.java:517`).

#### Lid importi (amoCRM Excel) — `LeadImportController` / `LeadImportService`
Controller `controller/LeadImportController.java` — sinf darajasida SUPER_ADMIN/ADMIN (`:29`); URL `/api/leads/**` (SALES_MANAGER ham) ∩ metod → effektiv ADMIN+.

| Endpoint | Qator | Mazmuni |
|---|---|---|
| `POST /api/leads/import/preview` multipart `file` | `LeadImportController.java:35-40` | Tahlil, bazaga yozilmaydi |
| `POST /api/leads/import/execute` JSON | `:42-47` | `LeadImportExecuteRequest`: importId, stageMapping (majburiy), operatorMapping, skipDuplicates, importTag (majburiy) |
| `GET /api/leads/import/{importBatch}` | `:50-56` | Partiyadagi lidlar soni |
| `DELETE /api/leads/import/{importBatch}?confirm=true` | `:62-75` | Faqat SUPER_ADMIN; `confirm=true` bo'lmasa 400 |

Format va ustunlar (`service/LeadImportService.java:73-99`): birinchi varaq, 1-qator sarlavha; majburiy ustunlar `Основной контакт` va `Этап сделки` (`:397-398`). Qolganlari: `Рабочий телефон (контакт)` (ustuvor) / `Мобильный телефон (контакт)`, `Ответственный`, `Дата создания` (`dd.MM.yyyy HH:mm[:ss]`, `dd.MM.yyyy` yoki Excel sana katagi), `Теги сделки` (manba: "sayt"→WEBSITE, "fb/target/facebook"→INSTAGRAM, aks holda OTHER — `:448-460`), `Online yoki Offline (контакт)`, qo'shimcha anketa ustunlari → `Lead.notes`, `Примечание 1..5` → har biri alohida `LeadNote`. Fayl turi tekshirilmaydi (`WorkbookFactory` xls/xlsx ni o'zi aniqlaydi), diskka `java.io.tmpdir/adizone-lead-import/<uuid>.xlsx` (`:558-568`), `pending` xaritasi xotirada, TTL 1 soat; har 15 daqiqada tozalanadi (`:571-595`).

Preview javobi (`:137-183`): totalRows, validPhones/invalidPhones, duplicatesInFile, duplicatesInDb (kanonik raqam bo'yicha), sourceStages va operators ro'yxati (sanog'i bilan), blockedStages (faol konvert bosqichlari), 5 ta namuna qator, expiresAt.

Execute qoidalari (`:190-350`):
- stageMapping dagi kodlar oldindan `requireActiveCode` (`:203-206`); **konvert bosqichiga xaritalash taqiqlangan** (`:654-667`).
- operatorMapping dagi foydalanuvchilar OPERATOR_ROLES da bo'lishi shart, aks holda butun import 400 (`:626-640`). `isActive` tekshirilmaydi.
- Qator o'tkazib yuboriladi (`skipped`): bosqich xaritalanmagan yoki null; ism bo'sh; `skipDuplicates=true` va kanonik telefon bazada yoki shu faylda oldinroq uchragan (`:292-301`).
- **Dublikat siyosati**: `skipDuplicates=false` (default) → dublikatlar ham yaratiladi. Bazadagi mavjud telefonlar butun `leads` jadvalidan `canonicalDigits` bilan yig'iladi (`:615-624`).
- Telefon tanilmasa ham lid yaratiladi: xom qiymat (50 belgigacha) yoki `"—"` (`:468-475`), javobda `warnings` ga yoziladi.
- `createdAt` amoCRM sanasiga qayta yoziladi (`:327-330`); `createdBy=importchi`, `importBatch=importTag` (50 belgi).
- Har qator o'z `TransactionTemplate` tranzaksiyasida (`:229-254`); yiqilgan qator `errors` ga (rowNum + root xabar ≤300 belgi), qolganlariga ta'sir qilmaydi.
- Natija `LeadImportResult`: importBatch, totalRows, created, skipped, failed, notesCreated, errors[], warnings[].
- Status tarixi va vazifa yaratilmaydi.
- `@Audited(IMPORT, "Lead")` (`:187`).

Partiyani o'chirish (`:364-378`): avval shu lidlarning `LeadNote` lari, keyin lidlar. `LeadComment`, `LeadStatusHistory`, `Task` o'chirilmaydi (qarang Muammolar).

#### Talaba importi — `ImportController` / `ImportService` / `StudentImportRowService`
Endpointlar (hammasi SUPER_ADMIN/ADMIN, URL `/api/import/**` `SecurityConfig.java:153-154`):
- `POST /api/import/students` multipart `file` (`controller/ImportController.java:34-44`), bir xil logika `POST /api/students/import` (`controller/StudentController.java:171-180`);
- `POST /api/students/import/validate` — dry-run (`StudentController.java:183-194`);
- `GET /api/import/template/students` (`ImportController.java:58-68`), `POST /api/import/teachers` (`:46-56`) va `POST /api/teachers/import` (`controller/TeacherController.java:204-214`), `GET /api/import/template/teachers` (`ImportController.java:70-104`).

Format (`service/ImportService.java:67-74`, `:108-198`): birinchi varaq; 1-qator izoh, **2-qator sarlavha**, ma'lumot 3-qatordan. Majburiy ustunlar: Ism, Familiya, Telefon. Qolganlari: Ota-ona telefoni, Tug'ilgan sana, Jins (MALE/FEMALE), Qayerdan kelgan (MarketingSource, tanilmasa OTHER + warning), Manzil, Qabul sanasi, Izoh, Guruh nomi, To'lov turi (MONTHLY/PER_LESSON...), Oylik to'lov, Dars narxi, To'lov boshlanish sanasi, Sinov darsi (HA/YO'Q), Ota/Ona F.I.O, telefoni, manzili.

Qator validatsiyasi (`:215-347`):
- Telefon majburiy; 9..13 raqam bo'lmasa xato; fayl ichida takror → xato; `studentRepository.findByPhone(phone)` topilsa → xato ("allaqachon mavjud") — **dublikat siyosati: rad etish**.
- Guruh nomi normallashtirilib qidiriladi (`:455-468`); topilmasa → qator rad; COMPLETED/CANCELLED → rad; to'lgan → rad; bo'sh → guruhsiz + warning (`:354-384`).
- To'lov summasi: ustun → kurs narxi (warning) → yo'q bo'lsa xato (`:395-423`).
- Xato matnlari insonlashtirilgan (`:491-512`).
- Har qator `StudentImportRowService.importRow` da `REQUIRES_NEW` (`service/StudentImportRowService.java:47-48`): Student, (guruh bo'lsa) StudentGroup + `recalculateForStudent` (`:151-153`), Ota/Ona uchun `Parent` + `StudentParent` (`:102-110`, `:187-201`).
- Natija `ImportResult`: totalRows, imported, validRows, skipped, errors[], warnings[].
- `@Audited(IMPORT, "Student")` (`ImportService.java:97`); dry-run va o'qituvchi importi audit qilinmaydi.

O'qituvchi importi (`ImportService.java:642-720`): sarlavha 1-qator, ustunlar indeks bo'yicha (0..5), telefon bazada bo'lsa rad, butun fayl bitta `@Transactional` va oxirida `saveAll`; fayl ichidagi dublikat telefon tekshirilmaydi; `teacherCode = "TCH-" + (count + n)`.

---

### 6.2 Meta (Facebook/Instagram Lead Ads) webhook oqimi
Manba hujjat: `docs/META_LEAD_ADS.md` (repo ildizida). Sozlamalar: `config/MetaProperties.java` (`meta.*`): enabled, api-version, page-id, system-user-token, app-secret, verify-token, graph-base-url, verify-signature (default true), task-assignee-user-id, page-token-ttl-hours (6), duplicate-window-days (30), request-timeout-seconds (20). `application.yml` da `task-assignee-user-id` bo'sh.

**1. GET verify** — `controller/MetaWebhookController.java:71-92`: `hub.mode == "subscribe"` va `hub.verify_token == meta.verify-token` (bo'sh bo'lmasa) → 200 `text/plain` bilan `hub.challenge`; aks holda 403. URL ochiq (`SecurityConfig.java:84`).

**2. POST leadgen** — `MetaWebhookController.java:103-126`:
- Tana `byte[]` (imzo xom baytlar ustida).
- Imzo `signatureAccepted` (`:190-201`): `verify-signature=false` YOKI `app-secret` bo'sh → tekshirmasdan qabul (har so'rovda WARN). Aks holda `X-Hub-Signature-256: sha256=<hex>` HMAC-SHA256(app-secret, body), `MessageDigest.isEqual` bilan (`:210-228`). Mos kelmasa → 403 `"invalid signature"`.
- `store()` (`:129-173`): `entry[].changes[]` dan faqat `field=="leadgen"`; `leadgen_id` bo'sh bo'lsa o'tkaziladi. `page_id` konfiguratsiyadagi `meta.page-id` bilan SOLISHTIRILMAYDI.
- Saqlashda xato bo'lsa ham 200 `EVENT_RECEIVED` (Meta obunani o'chirmasin).

**3. MetaWebhookEvent saqlanishi** — `MetaEventWorker.saveIfNew` `service/MetaEventWorker.java:195-207` (`REQUIRES_NEW`): `existsByLeadgenId` + UNIQUE (`entity/MetaWebhookEvent.java:49`) → takror jim o'tkaziladi. Maydonlar: leadgenId, formId, pageId, adId (`adgroup_id` dan), createdTimeMs, rawPayload (butun tana), status=PENDING, attempts=0, receivedAt (`entity/MetaWebhookEvent.java:46-93`). Statuslar: PENDING/PROCESSING/PROCESSED/FAILED/SKIPPED, `MAX_ATTEMPTS=5` (`:35-42`). PROCESSING hech qayerda o'rnatilmaydi (**TAXMIN**: ishlatilmaydigan qoldiq).

**4. Scheduler / retry** — `service/MetaLeadProcessingService.java:44-61`: `@Scheduled(fixedDelay=15s)`, `meta.enabled=false` bo'lsa ishlamaydi; bir siklda 20 ta PENDING va `attempts<5` (`repository/MetaWebhookEventRepository.java:32-39`, id bo'yicha o'sish).
- `processOne` (`:70-103`): `beginAttempt` (attempts++ OLDINDAN, `MetaEventWorker.java:219-229`) → Graph `getLead` (tranzaksiyadan tashqarida) → `ensureFormKnown` (forma bazada yo'q bo'lsa bir marta `syncForms`, `:113-129`) → `persist` (`REQUIRES_NEW`, `MetaEventWorker.java:238-256`).
- Xato → `recordFailure` (`MetaEventWorker.java:266-284`): attempts ≥ 5 → FAILED, aks holda PENDING (keyingi siklda, ya'ni ~15 s dan keyin; exponential backoff YO'Q). Token xatosi 190 → kesh tozalanadi.
- Natija → status: CREATED/DUPLICATE_PHONE → PROCESSED, DUPLICATE_LEADGEN/SKIPPED → SKIPPED; `leadId`, `errorMessage` (izoh), `processedAt`.

**5. Graph API** — `service/MetaGraphClient.java`: `GET /{leadgen_id}?fields=id,created_time,form_id,ad_id,adset_id,campaign_id,platform,field_data` (`:51-52`, `:165-168`), sahifa tokeni `/me/accounts` dan olinib `page-token-ttl-hours` keshlanadi (`:92-123`), token `Authorization: Bearer` sarlavhasida, URL loglarda maskalanadi (`:348`).

**6. Mapping (MetaLeadIngestService — yagona lid yaratish joyi)** `service/MetaLeadIngestService.java:115-209`:
1. `leadgen_id` bo'yicha dublikat (`leads.meta_leadgen_id` UNIQUE, `entity/Lead.java:116-117`) → DUPLICATE_LEADGEN.
2. Forma turi (`entity/enums/MetaFormType.java`): IGNORE / HR → SKIPPED (lid yaratilmaydi); UNMAPPED yoki forma topilmasa → lid yaratiladi + izohga ogohlantirish.
3. `extract` (`:226-311`): har `field_data` kaliti uchun `MetaLeadFormQuestion.crmField` (bazada bo'lmasa `MetaFormSyncService.guessField` — `service/MetaFormSyncService.java:224-234`: full_name→FULL_NAME, first_name/ismingiz→FIRST_NAME, last_name/familyangiz→LAST_NAME, phone_number→PHONE, telefon_raqamingiz→PHONE_ALT, boshqasi NOTE). `MetaCrmField` (`entity/enums/MetaCrmField.java`): FULL_NAME, FIRST_NAME, LAST_NAME, PHONE, PHONE_ALT, PREFERRED_CALL_TIME, PLANNED_START, PURPOSE, NOTE, IGNORE. Variant javoblari `options_json` orqali label ga tarjima (`:321-339`). PREFERRED_CALL_TIME/PLANNED_START/PURPOSE/NOTE → `Lead.notes` ga "Label: qiymat" satrlari.
4. Telefon: PHONE birlamchi, PHONE_ALT zaxira; ikkalasi tanilib farqli bo'lsa ikkinchisi izohga; tanilmasa xom qiymat (yoki `"—"`) + izoh (`:276-297`).
5. Ism: FULL_NAME → FIRST+LAST → `"Meta lid NNNN"` (telefon oxirgi 4 raqami); `capitalize` (`:349-383`).
6. **Dublikat oynasi** (`:151-167`): faqat kanonik telefon bo'lsa; `createdAt >= now - duplicate-window-days` va status yopiq bo'lmagan (CONVERTED/REJECTED kind emas) lid (`repository/LeadRepository.java:41-51`, aniq `phone =` solishtiruvi) → yangi lid yaratilmaydi, eng yangi lidga `LeadNote` "Meta'dan takroriy murojaat (...)" qo'shiladi → DUPLICATE_PHONE.
7. Lid: status = forma `defaultStageCode` (yaroqsiz bo'lsa NEW, `:392-404`), source = forma `defaultSource` → platforma `fb*`→FACEBOOK → aks holda INSTAGRAM (`:411-421`), format = forma `defaultStudyFormat` yoki OFFLINE; `assignedUser=null`, `createdBy=null`, `metaLeadgenId`, `metaFormId`, `metaRawJson`; `createdAt` Meta `created_time` ga qayta yoziladi (`:197-201`). Status tarixi yozilmaydi.
8. **Avtomatik vazifa** (`:457-490`): forma `autoCreateTask=true` va `taskTimeQuestionKey` bor va javob (PREFERRED_CALL_TIME) kelgan bo'lsa; mas'ul = lid operatori (doim null) → `meta.task-assignee-user-id` (faol bo'lishi shart); topilmasa vazifa yaratilmaydi (WARN). Vazifa: `CALL`, title "Qo'ng'iroq qilish — {vaqt}", `dueAt = BUGUN 23:59`, `allDay=true`, `createdBy=null`.
9. `MetaLeadForm.active` bayrog'i ingest da HECH QAYERDA o'qilmaydi (qarang Muammolar).

**7. Backfill** — `POST /api/meta/forms/{formId}/backfill?since=yyyy-MM-dd&dryRun=true` (`controller/MetaAdminController.java:89-100`, `service/MetaBackfillService.java`): Graph `/{formId}/leads` 100 tadan, kursor bilan, max 500 sahifa; `since` dan eski lidlar o'tkaziladi, butun sahifa eski bo'lsa to'xtaydi; har lid `MetaBackfillWorker.ingestOne` (`REQUIRES_NEW`) → bir xil `ingest`. `dryRun` default **true**. Sinxron (HTTP so'rov davomida). Backfill `MetaWebhookEvent` yaratmaydi.

**8. Admin sozlash endpointlari** (`controller/MetaAdminController.java`, SUPER_ADMIN/ADMIN — `:37` va `SecurityConfig.java:141-142`):
| Endpoint | Qator | Nimani sozlaydi |
|---|---|---|
| `POST /api/meta/forms/sync` | `:46-50` | Graph dan formalar va savollar; operator sozlamalari ustiga yozilmaydi; yangi forma `UNMAPPED`, `active=false` (`MetaFormSyncService.java:83`) |
| `GET /api/meta/forms` | `:52-55` | Ro'yxat + savollar soni / mapping qilinmaganlar soni |
| `GET /api/meta/forms/{formId}` | `:57-61` | Forma + savollar (xom `questionKey`) |
| `PUT /api/meta/forms/{formId}` | `:63-69` | `MetaFormSettingsRequest`: leadType, defaultStageCode (faol bosqich, `MetaAdminService.java:125-132`), defaultStudyFormat, defaultSource (MarketingSource), autoCreateTask, taskTimeQuestionKey (shu formada bo'lishi shart), active. null → tegilmaydi, "" → tozalanadi. autoCreateTask=true bo'lsa taskTimeQuestionKey majburiy (`:152-156`) |
| `PUT /api/meta/forms/{formId}/mapping` | `:72-78` | `[{questionKey, crmField}]`; kalit AYNAN xom; `crmField=null` → bog'lanish olib tashlanadi (`MetaAdminService.java:163-182`) |
| `POST /api/meta/forms/{formId}/backfill` | `:89-100` | yuqorida |
| `GET /api/meta/events?status=&page=&size=` | `:105-112` | Eventlar (PageResponse, size ≤ MAX_PAGE_SIZE) |
| `POST /api/meta/events/{id}/retry` | `:114-118` | status=PENDING, attempts=0, error/processedAt tozalanadi (`MetaAdminService.java:222-233`) |
| `GET /api/meta/status` | `:122-125` | Sirlar sozlanganmi (qiymatsiz, boolean), signatureVerified, token keshi, lastSyncedAt, forma/event sanoqlari (`MetaAdminService.java:237-266`) |
| `POST/GET /api/meta/subscribe` | `:128-137` | `/{pageId}/subscribed_apps` (leadgen) |

Meta sozlama o'zgarishlari `@Audited` EMAS.

---

### 6.3 Vazifalar (Task)
Controller `controller/TaskController.java` — sinf darajasida SUPER_ADMIN/ADMIN/SALES_MANAGER (`:30`), URL `SecurityConfig.java:136-137`. `DELETE /api/tasks/{id}` ham `/api/tasks/**` qoidasiga mos (DELETE catch-all dan oldin) → SALES_MANAGER ga URL darajasida ochiq.

- **TaskType** (`entity/enums/TaskType.java:14-17`): CALL, MEETING, MESSAGE, OTHER (label + icon); default CALL; noma'lum qiymat → 400 (`TaskService.requireType` `service/TaskService.java:569-575`).
- **TaskStatus** (`entity/enums/TaskStatus.java:67-69`): OPEN, DONE, CANCELLED. CANCELLED ga o'tkazadigan kod YO'Q (grep: `TaskStatus.CANCELLED` hech qayerda ishlatilmaydi).
- **Bog'liqlik**: vazifa lidga YOKI talabaga YOKI hech narsaga bog'lanadi; ikkalasi birga → 400 (`dto/request/TaskCreateRequest.java` `@AssertTrue`, `TaskService.java:93-95`). Lidga bog'lashda `assertCanAccessLead` (`:96-99`); talabaga bog'lashda doira tekshiruvi YO'Q (`:100-103`).
- **"Kun davomida"**: `allDay=true` → `dueAt = o'sha kun 23:59` (`:562-567`).

Endpointlar va qoidalar:
| Endpoint | Servis | Qoida |
|---|---|---|
| `POST /api/tasks` | `create` `:79-123` | assignedTo berilmasa o'ziga; SALES_MANAGER faqat o'ziga (`:538-549`); nishon OPERATOR_ROLES + `isActive`. `@Audited(CREATE)` |
| `GET /api/tasks` (page,size,assignedTo,status,type,leadId,studentId,fromDate,toDate) | `getAll` `:325-342` | SALES_MANAGER uchun `assignedTo` majburan o'ziga; saralash `dueAt ASC`; size ≤100 |
| `GET /api/tasks/my?filter=today|overdue|week|all` | `getMy` `:366-397` | Faqat o'zining OPEN vazifalari. `today` muddati o'tganlarni kiritmaydi; `week` = keyingi 7 kun + muddati o'tganlar |
| `GET /api/tasks/stats` | `getStats` `:407-443` | overdue/today/upcoming (OPEN), noTask (yopiq bo'lmagan va OPEN vazifasi yo'q lidlar, `repository/LeadRepository.java:174-181`), byUser (faqat to'liq huquqda) |
| `GET /api/tasks/{id}` | `getById` `:344-350` | `assertCanAccessTask` |
| `PATCH /api/tasks/{id}` | `update` `:125-161` | Faqat OPEN; title/description/type/dueAt/allDay qismli. `@Audited(UPDATE)` |
| `PATCH /api/tasks/{id}/complete` | `complete` `:177-218` | Faqat OPEN; `result` MAJBURIY (`dto/request/TaskCompleteRequest.java` `@NotBlank`); status=DONE, completedAt, completedBy. `@Audited(TASK_DONE)` |
| `PATCH /api/tasks/{id}/reassign` | `reassign` `:264-280` | Faqat OPEN; nishon OPERATOR_ROLES + faol. SALES_MANAGER uchun "faqat o'ziga" cheklovi YO'Q. `@Audited(ASSIGN)` |
| `PATCH /api/tasks/{id}/postpone` | `postpone` `:282-302` | Faqat OPEN; yangi muddat kelajakda bo'lishi shart. `@Audited(UPDATE)` |
| `DELETE /api/tasks/{id}` | `delete` `:304-321` | SUPER_ADMIN/ADMIN yoki vazifa MUALLIFI (`createdBy`); holatdan qat'i nazar. `@Audited(DELETE)` |
| `GET /api/leads/{id}/tasks` | `getByLead` `:399-405` | Lid doirasi; lidning barcha vazifalari (ochiqlar yuqorida) |

**"Keyingi vazifa" mantiqi** (`TaskService.java:163-262`):
- `complete` tanasida ixtiyoriy `nextTask {type, title, dueAt (@NotNull), allDay}`. **Majburiy EMAS** — berilmasa faqat joriy vazifa yopiladi.
- Berilsa `createFollowUp`: dueAt kelajakda bo'lishi shart (`task.dueAt.future`), title bo'sh bo'lsa tur labeli; lid, talaba va **mas'ul** yopilgan vazifadan meros olinadi (admin boshqaning vazifasini yopsa ham zanjir o'sha operatorda); `createdBy = yopgan user`. Yopish va yangi vazifa bitta tranzaksiyada.
- Javob `TaskCompleteResponse` {task, leadId, leadHasOpenTask, nextTask}; `leadHasOpenTask=false` — frontend uchun "yangi vazifa qo'shing" signali (`:204-217`).

**LeadTaskState** (`entity/enums/LeadTaskState.java:14-36`) — saqlanmaydi, lidning eng yaqin OPEN vazifasi `dueAt` idan hisoblanadi:
- ochiq vazifa yo'q → `NONE`;
- `dueAt < now` → `OVERDUE`;
- `now ≤ dueAt < ertangi 00:00` → `TODAY`;
- aks holda `PLANNED`.
- Eng yaqin ochiq vazifa: `TaskRepository.findOpenByLeadIds` (`repository/TaskRepository.java:29-35`, `dueAt ASC`) → `TaskService.loadNextOpenTasks` (`service/TaskService.java:455-465`) → `LeadResponse.taskState/nextTaskDueAt/nextTaskTitle` (`service/LeadService.java:1061-1065`). `TaskResponse.state` ham shu formulada (`TaskService.java:614-616`).

**Ruxsatlar (kim ko'radi/tahrirlaydi/yopadi)**:
- ADMIN/SUPER_ADMIN — hammasi (`LeadAccessService.hasFullAccess`).
- SALES_MANAGER — `assertCanAccessTask` (`TaskService.java:526-536`): FAQAT `assignedTo == o'zi` (muallif ekanligi yetarli emas). Ro'yxat/stat — o'z vazifalari. O'chirish — muallif bo'lsa (mas'ul bo'lmasa ham) mumkin.
- Klass Javadoc (`TaskService.java:53-55`) "o'zi yaratgan vazifalarni ham ko'radi" deydi — kodga zid.

**Bildirishnomalar**: vazifa bo'yicha hech qanday bildirishnoma (push, WebSocket, Telegram, email) YO'Q — grep: `TaskService`/`MetaLeadIngestService` da `SimpMessagingTemplate`/`TelegramService` chaqiruvi yo'q. Muddat o'tgani haqida scheduler ham yo'q.

---

### 6.4 Telegram integratsiyasi
- `service/TelegramService.java`: `telegram.bot-token`, `telegram.enabled` (`:19-23`). `sendMessage(chatId, text)` (`:27-58`) — `POST https://api.telegram.org/bot<token>/sendMessage`, `parse_mode=HTML`, sinxron `RestTemplate` (timeout sozlanmagan), xato yutiladi (false).
- Qachon yuboriladi:
  1. **Davomat**: `AttendanceService.markAttendance` (`service/AttendanceService.java:56`, blok `:138-161`) — o'quvchi `ABSENT` belgilanganda, har `Parent.telegramChatId` bo'lgan ota-onaga "O'quvchingiz bugun darsga kelmadi" (`TelegramService.buildAttendanceMessage` `:69-81`). chatId yo'q bo'lsa faqat log.
  2. **To'lov eslatmasi**: `service/PaymentReminderService.java:22-61`, `@Scheduled(cron="0 0 10 * * *")` (har kuni 10:00) — `paymentService.getDebtorsLegacy()` dan `daysOverdue ≥ 3` qarzdorlarning ota-onalariga (`buildPaymentMessage` `:83-96`).
- `telegramChatId` faqat qo'lda `ParentRequest.telegramChatId` (`dto/request/ParentRequest.java:20`) orqali to'ldiriladi; bot webhook/`/start` bilan bog'lash YO'Q.
- `sendPaymentReminder` (`TelegramService.java:60-67`) faqat log yozadi (ishlatilmaydi).
- Lid/vazifa/chat bo'yicha Telegram xabari YO'Q.

---

### 6.5 Audit (`@Audited`)
**Mexanizm**:
- `audit/Audited.java:22-41` — `action`, `entity`, `summary`/`entityId`/`label` (SpEL; `#result`, argumentlar nomi, `#a0`/`#p0`).
- `audit/AuditAspect.java:70-106` (`@ConditionalOnProperty app.audit.enabled`, default true): metod exception tashlasa yozilmaydi; tranzaksiya faol bo'lsa `afterCommit` da, aks holda darhol `AuditRecorder.record`. `AuditContext.skip()` → yozilmaydi. Aspect har chaqiruv BOSHIDA `AuditContext.clear()` qiladi (`:72`).
- `audit/AuditContext.java` — ThreadLocal (`:21`): `change(field, old, new)` (`:50`), `entityId`, `label`, `actor` (login uchun), `summary`, `skip`.
- `audit/AuditRecorder.java:31-45` — `@Async` + `REQUIRES_NEW`; username bo'yicha userId aniqlaydi; xato yutiladi. `@EnableAsync` — `CrmApplication.java:12`.
- Foydalanuvchi SecurityContext dan (`AuditAspect.java:132-143`), IP: `X-Forwarded-For` birinchi qiymati → `X-Real-IP` → remoteAddr (`:226-236`), User-Agent ≤255.
- `LOGIN_FAILED` — aspect emas, `AuthService.recordFailedLogin` to'g'ridan-to'g'ri (`service/AuthService.java:104-118`).
- Amallar (`audit/AuditAction.java`): CREATE, UPDATE, DELETE, LOGIN, LOGIN_FAILED, EXPORT (ishlatilmaydi), IMPORT, PAYMENT, REPAIR, STATUS_CHANGE, ASSIGN, COMMENT, TASK_DONE.

**AuditLog saqlaydi** (`entity/AuditLog.java:27-71`): id, createdAt, userId, username, userRole (snapshot, FK emas), action, entityType, entityId, entityLabel, summary (≤500), detailsJson (`{"changes":[{field,old,new}]}`), ipAddress (≤45), userAgent (≤255). Indekslar: created_at, user_id, (entity_type, entity_id), action.

**Retention**: `audit/AuditRetentionJob.java:22-34` — har kuni 03:30, `app.audit.retention-days` (yml: 90; ≤0 → o'chirilmaydi) dan eski yozuvlar o'chiriladi.

**AuditLogController** (`controller/AuditLogController.java`):
- `GET /api/audit-logs` — faqat SUPER_ADMIN (`:31`); filtrlar: `from`, `to` (ISO sana, `to` kun oxirigacha), `userId`, `action`, `entityType`, `entityId`, `q` (summary/entityLabel/username bo'yicha LIKE, registrsiz), `page` (0), `size` (50, max 200); saralash `createdAt DESC` (`service/AuditLogService.java:36-56`, `:78-113`).
- `GET /api/audit-logs/entity/{entityType}/{entityId}` — SUPER_ADMIN/ADMIN (`:47-54`), List.
- `GET /api/audit-logs/filters` — SUPER_ADMIN; bazadagi distinct actions va entityTypes (`AuditLogService.java:70-76`).

**@Audited — TO'LIQ ro'yxat (grep, 54 ta metod)**:
| Fayl:qator | Action | Entity | Metod |
|---|---|---|---|
| `controller/UserController.java:178` | UPDATE | User | `updateUser` (controller darajasida!) |
| `service/AttendanceService.java:53` | UPDATE | Attendance | `markAttendance` |
| `service/AuthService.java:50` | LOGIN | User | `login` |
| `service/BalanceTransactionService.java:190` | UPDATE | Balance | `manualAdjust` |
| `service/CashRegisterService.java:375` | PAYMENT | CashRegister | `addIncome` |
| `service/CashRegisterService.java:488` | PAYMENT | CashRegister | `addExpense` |
| `service/CashRegisterService.java:516` | DELETE | CashTransaction | `deleteExpense` |
| `service/CashRegisterService.java:533` | PAYMENT | CashRegister | `transfer` |
| `service/ExamService.java:174` | UPDATE | ExamResult | `updateResult` |
| `service/GroupService.java:206` | CREATE | Group | `createGroup` |
| `service/GroupService.java:244` | UPDATE | Group | `updateGroup` |
| `service/GroupService.java:499` | UPDATE | Group | `updateStatus` |
| `service/GroupService.java:509` | DELETE | Group | `deleteGroup` |
| `service/GroupService.java:517` | CREATE | StudentGroup | `addStudentToGroup` |
| `service/GroupService.java:597` | DELETE | StudentGroup | `removeStudentFromGroup` |
| `service/GroupService.java:610` | DELETE | StudentGroup | `removeStudentFromGroup` (overload) |
| `service/ImportService.java:97` | IMPORT | Student | `importStudents` |
| `service/LeadImportService.java:187` | IMPORT | Lead | `execute` |
| `service/LeadImportService.java:364` | DELETE | Lead | `deleteBatch` |
| `service/LeadService.java:108` | CREATE | Lead | `createLead` (public) |
| `service/LeadService.java:143` | CREATE | Lead | `createLeadByStaff` |
| `service/LeadService.java:289` | ASSIGN | Lead | `assignLead` |
| `service/LeadService.java:321` | STATUS_CHANGE | Lead | `updateStatus` |
| `service/LeadService.java:405` | UPDATE | Lead | `updateAmount` |
| `service/LeadService.java:462` | COMMENT | Lead | `addComment` |
| `service/LeadService.java:606` | CREATE | Student | `convertToStudent` |
| `service/LeadStageService.java:79` | CREATE | LeadStage | `create` |
| `service/LeadStageService.java:111` | UPDATE | LeadStage | `update` |
| `service/LeadStageService.java:143` | DELETE | LeadStage | `delete` |
| `service/LeadStageService.java:172` | UPDATE | LeadStage | `reorder` |
| `service/MonthlyLedgerRepairService.java:44` | REPAIR | Balance | `rebuildMonthlyLedger` |
| `service/NoticeService.java:142` | DELETE | Notice | `deleteNotice` |
| `service/PaymentService.java:74` | PAYMENT | Payment | `createPayment` |
| `service/PayrollService.java:172` | CREATE | Payroll | `createPayroll` |
| `service/PayrollService.java:205` | UPDATE | Payroll | `updatePayroll` |
| `service/PayrollService.java:298` | PAYMENT | Payroll | `markAsPaid` |
| `service/StudentService.java:129` | CREATE | Student | `createStudent` |
| `service/StudentService.java:183` | UPDATE | Student | `updateStudent` |
| `service/StudentService.java:351` | UPDATE | Student | `transferGroup` |
| `service/StudentService.java:437` | CREATE | Student | `createAndAddStudentToGroup` |
| `service/StudentService.java:533` | DELETE | Student | `deleteStudent` |
| `service/StudentService.java:950` | UPDATE | Student | `freezeStudent` |
| `service/StudentService.java:1123` | UPDATE | Student | `unfreezeStudent` |
| `service/TaskService.java:80` | CREATE | Task | `create` |
| `service/TaskService.java:126` | UPDATE | Task | `update` |
| `service/TaskService.java:178` | TASK_DONE | Task | `complete` |
| `service/TaskService.java:265` | ASSIGN | Task | `reassign` |
| `service/TaskService.java:283` | UPDATE | Task | `postpone` |
| `service/TaskService.java:305` | DELETE | Task | `delete` |
| `service/UserService.java:84` | CREATE | User | `createUser` |
| `service/UserService.java:112` | CREATE | User | `createForTeacher` |
| `service/UserService.java:326` | UPDATE | User | `resetPassword` |
| `service/UserService.java:370` | UPDATE | User | `setActive` |

(`service/AuthService.java:60` va `service/TaskService.java:169` dagi mosliklar — izoh matni, annotatsiya emas.)

Audit qilinMAYDIGAN muhim amallar: LeadNote CRUD, Meta sozlamalari/backfill/retry, chat, fayl yuklash, profil/foydalanuvchi rasmi, parol almashtirish (`/api/auth/change-password`), o'qituvchi importi, lid eksporti (EXPORT konstantasi bor, lekin ishlatilmaydi).

---

### 6.6 Rate limit
**Yo'q.** `pom.xml` va `src/main` bo'yicha grep (`RateLimit`, `rate.limit`, `Bucket4j`, `throttl`, `loginAttempt`, `lockout`, `resilience4j`) — hech narsa topilmadi ("bucket" mosliklari faqat `ExpectedPaymentsResponse.DayBucket` va `PaymentMethod.CashBucket` — rate limitga aloqasi yo'q). Login urinishlari cheklanmaydi (faqat `LOGIN_FAILED` audit yoziladi), `/api/leads/public` va `/api/meta/webhook` ham cheklovsiz.

---

## §7. WebSocket (STOMP)

**Endpoint va konfiguratsiya** — `config/WebSocketConfig.java`:
- STOMP endpoint `/ws` (`:43`), **SockJS YO'Q** (`.withSockJS()` chaqirilmagan) — sof WebSocket.
- CORS: `setAllowedOriginPatterns(SecurityConfig.ALLOWED_ORIGIN_PATTERNS)` (`:44-45`) — REST bilan bitta ro'yxat (`config/SecurityConfig.java:45-55`: admin.adizone.uz, adizone.uz, www.adizone.uz, `https://*.vercel.app`, localhost:3000/5173/5174, 127.0.0.1:5173/3000).
- Handshake interceptor `JwtHandshakeInterceptor`, handler `PrincipalHandshakeHandler` (`:46-47`).
- Broker: xotiradagi `SimpleBroker` `/topic`, `/queue` (`:52`); application destination prefix `/app` (`:53`); user prefix `/user` (`:54`).
- Kiruvchi kanal interceptori `ChatChannelInterceptor` (`:58-60`).
- HTTP darajasida `/ws`, `/ws/**` permitAll (`SecurityConfig.java:98`).

**Handshake auth** — `security/jwt/JwtHandshakeInterceptor.java`:
- Token: `?token=<JWT>` query parametri (`:43`, `:88-94`), zaxira sifatida `Authorization: Bearer` sarlavhasi (`:95-98`).
- Token turi: login beradigan **access JWT** (`JwtUtils`); refresh token UUID bo'lgani uchun ishlamaydi.
- `extractUsername` → `loadUserByUsername` (nofaol hisob istisno) → `isTokenValid` (username + muddat, `security/jwt/JwtUtils.java:63-66`). Xato → HTTP 403, sessiya ochilmaydi (`:102-106`).
- Authentication `attributes["chat.authentication"]` ga qo'yiladi, `PrincipalHandshakeHandler.determineUser` (`security/jwt/PrincipalHandshakeHandler.java:25-31`) uni sessiya `Principal` iga aylantiradi (`principal.getName()` = username).
- Token faqat handshake da tekshiriladi — ulanish token muddati tugagandan keyin ham ochiq qoladi (qarang Muammolar).

**Kiruvchi kadrlar tekshiruvi** — `config/ChatChannelInterceptor.java`:
- CONNECT: `Principal` bo'lmasa `MessagingException` (`:47-50`).
- SUBSCRIBE: faqat `/topic/conversation.{id}` uchun a'zolik tekshiriladi — `ChatAccessService.isParticipant` (faol, `leftAt IS NULL`, `service/ChatAccessService.java:73-77`); a'zo bo'lmasa `MessagingException` (`:59-79`). Boshqa manzillar (`/topic/presence`, `/user/queue/...`) tekshirilmaydi.
- SEND kadrlari umuman tekshirilmaydi (qarang Muammolar).

**@MessageMapping lar** (`controller/ChatSocketController.java`, klient `/app/...` ga yuboradi):
| Destination | Qator | Payload DTO | Servis | Natija topik |
|---|---|---|---|---|
| `/app/chat.send` | `:60-65` | `ChatSendRequest` {conversationId* , text (≤4000, `entity/Message.java:40`), clientId (≤64), replyToId, attachments[] (≤10, `ChatAttachmentRequest` {fileUrl*, fileName*, fileSize, contentType, width, height, durationMs, waveform})} | `ChatService.send` `service/ChatService.java:124-172` (a'zolik, text yoki biriktirma majburiy, replyTo shu suhbatdan) | `/topic/conversation.{id}` ← `ChatMessageResponse` |
| `/app/chat.read` | `:68-75` | `ChatReadRequest` {conversationId*, messageId*} | `ChatService.markRead` `:271-` | kursor oldinga surilsa `ChatReadReceiptResponse` |
| `/app/chat.typing` | `:88-93` | `ChatTypingRequest` {conversationId*, typing*} | `ChatService.typing` `:314-` (bazaga yozilmaydi) | `ChatTypingResponse` |
| `/app/chat.edit` | `:102-107` | `ChatEditRequest` {messageId*, text* ≤4000} | `ChatService.edit` `:186-` — faqat muallif, faqat matnli, o'chirilmagan, 15 daqiqa ichida (`EDIT_WINDOW` `:96`) | `ChatMessageEditedResponse` |
| `/app/chat.delete` | `:115-122` | `ChatDeleteRequest` {messageId*} | `ChatService.delete` `:232-253` — muallif yoki SUPER_ADMIN/ADMIN (`:99-100`); soft delete (`deletedAt`); allaqachon o'chirilgan → jim | `ChatMessageDeletedResponse` |

Xatolar: `@MessageExceptionHandler` (`:132-144`) → `convertAndSendToUser(username, "/queue/errors", {type:"ERROR", message})` — klient `/user/queue/errors` ga obuna bo'ladi.

**Server yuboradigan BARCHA destinationlar** (grep `convertAndSend*` — faqat 2 fayl):
| Destination | Qayerda | Payload (maydonlar) | Qachon |
|---|---|---|---|
| `/topic/conversation.{id}` | `ChatSocketController.java:64` | `ChatMessageResponse`: type=`"MESSAGE"`, id, uuid, conversationId, conversationTitle (NON_NULL), senderId, senderName, senderPhotoUrl, text, messageType (TEXT/IMAGE/FILE/VOICE), replyToId, clientId, attachments[] (`ChatAttachmentResponse`: id, fileUrl, fileName, fileSize, contentType, width, height, durationMs, waveform, sortOrder), replyTo (`ChatReplyPreviewResponse`: id, senderName, text, type), createdAt, editedAt, deletedAt | Yangi xabar |
| `/topic/conversation.{id}` | `:74` | `ChatReadReceiptResponse`: type=`"READ"`, conversationId, userId, messageId | O'qilgan kursori oldinga surilganda |
| `/topic/conversation.{id}` | `:92` | `ChatTypingResponse`: type=`"TYPING"`, conversationId, userId, userName, typing | "Yozmoqda" (yuboruvchiga ham qaytadi; frontend 3 s da o'chiradi) |
| `/topic/conversation.{id}` | `:106` | `ChatMessageEditedResponse`: type=`"EDITED"`, conversationId, messageId, text, editedAt | Tahrir |
| `/topic/conversation.{id}` | `:120-121` | `ChatMessageDeletedResponse`: type=`"DELETED"`, conversationId, messageId, deletedBy | O'chirish |
| `/user/queue/errors` | `:141-142` | `Map {type:"ERROR", message}` | STOMP handler xatosi (faqat yuboruvchiga) |
| `/topic/presence` | `service/ChatPresenceService.java:131-135` | `PresenceResponse`: type=`"PRESENCE"`, userId, online, lastSeenAt | `SessionConnectedEvent` da 0→1 sessiya (`:91-99`), `SessionDisconnectEvent` da 1→0 (`:107-118`, `users.last_seen_at` yoziladi). Barcha ulangan xodimlarga (a'zolikdan qat'i nazar) |

Presence holati xotirada `ConcurrentHashMap<userId, sessiyalar soni>` (`ChatPresenceService.java:51`). Dastlabki surat REST: `GET /api/chat/presence` (`controller/ChatController.java:116-120`, faqat suhbatdoshlar).

**Chat REST bilan bog'liqligi** (`controller/ChatController.java`, `/api/chat/**` — har qanday autentifikatsiyalangan, `SecurityConfig.java:192`):
- Xabar yuborish/o'qish/tahrir/o'chirish/typing — FAQAT STOMP orqali (REST ekvivalenti yo'q).
- REST: suhbatlar ro'yxati (`:52`), tarix `?before`/`?around`/`size` (`:68`), suhbat ichida qidiruv (`:86`), umumiy qidiruv (`:101`), presence (`:116`), `POST /conversations/direct` (`:123`), `POST /conversations/group` (`:132`), `PATCH /conversations/{id}/pin` (`:141`), `GET /users` (`:151`), `POST /upload` (`:173`), `GET /unread-count` (`:185`).
- REST amallar hech qanday WS hodisa yubormaydi: yangi DIRECT/guruh suhbat yaratilsa, a'zolarga real-time xabar ketmaydi (ular ro'yxatni REST bilan yangilashi va yangi topikka obuna bo'lishi kerak).
- Biriktirma oqimi: avval `POST /api/chat/upload` (multipart), keyin javob `attachments[]` sifatida `/app/chat.send` ga.

**Chatdan tashqari real-time hodisalar**: YO'Q. `convertAndSend` faqat `ChatSocketController` va `ChatPresenceService` da; yangi lid (Meta/ochiq forma), vazifa, to'lov va h.k. uchun WS bildirishnoma yo'q.

---

## §8. Fayllar

**Umumiy cheklovlar**: `spring.servlet.multipart.max-file-size: 4MB`, `max-request-size: 8MB` (`src/main/resources/application.yml`). Oshsa → `MaxUploadSizeExceededException` handler (`exception/GlobalExceptionHandler.java:91-95`). Saqlash joyi `app.upload.dir` (yml: `/opt/crm/uploads`, default ham shu — `service/FileStorageService.java:30`), papka tuzilishi YO'Q — hammasi bitta tekis katalogda, katalog yo'q bo'lsa yaratiladi (`:66-69`). Yozish `REPLACE_EXISTING` (`:75`).

**Qaytariladigan URL**: har doim NISBIY `"/api/files/" + filename` (`FileStorageService.URL_PREFIX` `:24`, `:77`). `app.base-url` `FileStorageService` ga inject qilinadi (`:31`), lekin `getBaseUrl()` (`:89-91`) hech qayerda chaqirilmaydi — URL ga qo'shilmaydi; frontend o'zi API host ni qo'shishi kerak.

**Upload endpointlari**:
| Endpoint | Qator | Multipart maydon | Ruxsat (effektiv) | Turi tekshiruvi | Hajm | Saqlash nomi |
|---|---|---|---|---|---|---|
| `POST /api/files/upload` | `controller/FileController.java:29-50` | `file` | Har qanday autentifikatsiyalangan (`isAuthenticated()`, `SecurityConfig.java:194`) | `validateImage`: faqat klient yuborgan `Content-Type` `image/*` bilan boshlanishi (`FileStorageService.java:36-47`) | 4MB (kodda ham) | `UUID + <asl nomdagi oxirgi nuqtadan keyingi kengaytma, filtrsiz>` (default `.jpg`) |
| `POST /api/chat/upload` | `controller/ChatController.java:173-182`, `service/ChatAttachmentService.java:98-135` | `file`, `durationMs`, `waveform` | Autentifikatsiyalangan | Klient `Content-Type`: `image/*` (hammasi, SVG ham), pdf, doc/docx, xls/xlsx, zip, text/plain; audio: webm/ogg/mpeg/mp4/wav (`:31-56`). Audio uchun `durationMs` 1..300000 majburiy, `waveform` ≤50 nuqta 0..100 | Faqat multipart 4MB (`save()` hajmni tekshirmaydi) | `UUID + kengaytma` (≤10 belgi, kichik harf; aks holda `.bin`, `:258-266`) |
| `POST /api/auth/profile/photo` | `controller/AuthController.java:91-111` | `file` | Autentifikatsiyalangan (o'zi) | `validateImage` | 4MB | `UUID_profile_{userId}.jpg` |
| `POST /api/users/{id}/photo` | `controller/UserController.java:201-220` | `file` | SUPER_ADMIN/ADMIN | `validateImage` | 4MB | `UUID_user_{id}.jpg` |
| `POST /api/students/{id}/photo` | `controller/StudentController.java:218-233` | `file` | URL: SA/ADMIN/SALES_MANAGER/ACCOUNTANT ∩ metod SUPER_ADMIN/ADMIN | `validateImage` | 4MB | `UUID_student_{id}.jpg` |
| `POST /api/teachers/{id}/photo` | `controller/TeacherController.java:186-201` | `file` | SUPER_ADMIN/ADMIN | `validateImage` | 4MB | `UUID_teacher_{id}.jpg` |
| `POST /api/leads/import/preview` | `controller/LeadImportController.java:35-40` | `file` | SUPER_ADMIN/ADMIN | Yo'q (POI o'qiy olmasa 400) | 4MB | `tmpdir/adizone-lead-import/UUID.xlsx`, 1 soatdan keyin o'chiriladi |
| `POST /api/import/students`, `POST /api/students/import`, `POST /api/students/import/validate` | `ImportController.java:34`, `StudentController.java:171,183` | `file` | SUPER_ADMIN/ADMIN | Yo'q (POI) | 4MB | Diskka yozilmaydi (stream) |
| `POST /api/import/teachers`, `POST /api/teachers/import` | `ImportController.java:46`, `TeacherController.java:204` | `file` | SUPER_ADMIN/ADMIN | Yo'q | 4MB | Diskka yozilmaydi |

Rasm yangilanganda eski fayl o'chirilmaydi (yetim fayllar qoladi).

**Chat biriktirmasini xabarga bog'lash** (`ChatAttachmentService.attach` `:293-342`, `requireOwnUrl` `:358-374`): `fileUrl` `/api/files/` bilan boshlanishi, `/`, `\`, `..` bo'lmasligi va fayl diskda mavjud bo'lishi shart. Fayl kim tomonidan va qaysi maqsadda yuklanganligi tekshirilmaydi. `contentType` (va shunga ko'ra xabar turi IMAGE/FILE/VOICE) klient `attachments[]` dan olinadi (`:316-318`).

**Yuklab olish — `GET /api/files/{filename}`** (`controller/FileController.java:52-74`):
- URL darajasida **ochiq** — `SecurityConfig.java:85` (`GET /api/files/**` permitAll). JWT'siz har kim fayl nomini bilsa yuklab oladi: foydalanuvchi/o'quvchi/o'qituvchi rasmlari va **shaxsiy chat biriktirmalari** (hujjatlar, ovozli xabarlar) ham. Himoya faqat UUID nomning taxmin qilinmasligiga tayanadi.
- Path traversal: `resolveSafePath` — `uploadDir.resolve(name).normalize()` va `startsWith(uploadDir)` (`FileStorageService.java:80-87`); `{filename}` path variable `/` ni o'z ichiga olmaydi. Himoyalangan. Saqlashda ham xuddi shu tekshiruv (`:70-73`).
- `Content-Type`: `Files.probeContentType(path)` (kengaytma bo'yicha), bo'lmasa `application/octet-stream` (`:63-66`).
- `Content-Disposition: inline; filename="<filename>"` (`:69`) — har doim `inline`, nom escape qilinmaydi.
- Xatoda 404.

---


## §9. Muammolar va nomuvofiqliklar

Bu bo'limda avval **ustuvor ro'yxat** keladi: yangi frontendga yoki xavfsizlikka eng katta ta'sir qiladigan bandlar. Undan keyin har bir modul bo'yicha **to'liq ro'yxatlar** (9.7–9.13).

Belgilar: 🔴 = xavfsizlik yoki pul yo'qotish; 🟠 = ma'lumot noto'g'ri chiqadi; 🟡 = frontend moslashishi kerak.

### 9.0 Ustuvor ro'yxat

**Xavfsizlik va ruxsatlar**
1. 🔴 **Sirlar repoda.** `application.yml` git'da kuzatiladi va ichida ochiq matnda turibdi:
   - DB paroli (`:13`, `:14` izohda)
   - `jwt.secret` (`:57`) — sizib chiqsa, istalgan user nomidan token yasash mumkin
   - Meta tokenlari (`:88-90`) va Telegram bot tokeni (`:108`)

   Rotatsiya qilib, env yoki secret store'ga ko'chirish kerak.
2. 🔴 **`/api/teachers/{id}/kpi`, `/{id}/kpi/trend`, `/{id}/kpi/daily` istalgan autentifikatsiyalangan foydalanuvchiga ochiq**, STUDENT va PARENT ham kiradi.
   - `@PreAuthorize` yo'q: `controller/TeacherController.java:78`, `:92`, `:122`.
   - URL qoidasi yo'q: `config/SecurityConfig.java:173` faqat `/me/**` ni cheklaydi, qolgani `:199` ga tushadi.
   - Egalik tekshiruvi yo'q: `service/TeacherService.java:275-276`.
   - Javobda moliyaviy maydonlar bor: `balance`, `bonus`, `advance`, `penalty` (`dto/response/TeacherKpiDto.java:12-15`).
3. 🔴 **`GET /api/teachers`, `/{id}`, `/search` ham hammaga ochiq.** Javobda pasport, maosh, tug'ilgan sana, manzil keladi (`controller/TeacherController.java:38`, `:73`, `:172`; `dto/response/TeacherResponse.java:25-42`).
4. 🔴 **ADMIN SUPER_ADMIN ustidan nazorat qila oladi:**
   - SA parolini o'zgartiradi: `PUT /api/users/{id}/password` (`controller/UserController.java:189-199`).
   - `role=SUPER_ADMIN` beradi yoki SA'ni nofaol qiladi: `service/UserService.java:96`, `:178-183`.
5. 🔴 **`PUT /api/auth/change-password` joriy parolni tekshirmaydi** va sessiyalarni bekor qilmaydi (`controller/AuthController.java:113-122`). `currentPassword` maydoni e'tiborsiz qoladi (`dto/request/ChangePasswordRequest.java:10`).
6. 🔴 **Fayllar orqali stored XSS va ochiq biriktirmalar.**
   - `GET /api/files/**` public (`config/SecurityConfig.java:85`).
   - Upload faqat klient yuborgan `Content-Type` ni tekshiradi (`service/FileStorageService.java:40-43`); kengaytma filtrsiz olinadi (`controller/FileController.java:34-38`).
   - Fayl `inline` holda beriladi (`:63-70`), ya'ni `.html` yoki `.svg` API domenida ochiladi.
   - Chat biriktirmalari a'zolik tekshiruvisiz yuklab olinadi.
7. 🔴 **STOMP SEND kadrlari tekshirilmaydi** (`config/ChatChannelInterceptor.java:40-57`). Istalgan xodim `/topic/conversation.{id}` va `/topic/presence` ga soxta hodisa yubora oladi.
8. 🔴 **Leave moduli ochiq** (`controller/LeaveController.java:46-63`, `service/LeaveService.java:64-126`):
   - `GET /{id}`, `GET /user/{userId}`, `POST` — istalgan rolga ochiq;
   - `requesterId` va `approvedById` klientdan olinadi.
9. 🔴 **Rate limit yoki captcha yo'q.** Login brute-force va `POST /api/leads/public` spami cheklanmaydi (§2.4).
10. 🔴 **Meta webhook "fail-open":** `app-secret` bo'sh bo'lsa yoki `verify-signature=false` bo'lsa, imzosiz event qabul qilinadi (`controller/MetaWebhookController.java:190-199`).
11. 🟠 **TEACHER scope bo'shliqlari:**
    - `GET /api/students/search`, `/{id}/groups`, `/left`, `/trial`, `/{id}/history` (`service/StudentService.java:548-561`);
    - `GET /api/exams/{id}/eligible-students` — barcha o'quvchilarni qaytaradi (`service/ExamService.java:272-278`);
    - homework submissions (`service/HomeworkService.java:100-131`);
    - `GET /api/search` STUDENT va PARENT ga ham ochiq (`controller/SearchController.java:33-55`).
12. 🟠 **URL va metod ruxsatlari mos emas:**
    - ACCOUNTANT kassani o'chira oladi (`SecurityConfig.java:132` qoidasi `:196` dan oldin turadi).
    - `bonus-penalties`, `audit-logs`, `contracts`, `marketing`, `leaves` uchun URL qoidasi yo'q.
    - SALES_MANAGER'ga `/api/payments/**` URL darajasida ochiq, lekin metod darajasida faqat bitta GET qoldirilgan.
    - `/api/marketing/sources` hammaga ochiq, xuddi shu ma'lumot `/api/analytics/**` da esa faqat SA/A uchun.

**Pul va hisob-kitob**
13. 🔴 **Payroll'ni ikki marta to'lash mumkin:** `POST /api/payroll/{id}/pay` statusni tekshirmaydi, har chaqiruvda kassaga yangi chiqim yoziladi (`service/PayrollService.java:301-337`).
    - `DELETE` kassa chiqimini va bonuslarni qaytarmaydi (`:292-295`).
    - `PUT` topilmagan id uchun yangi yozuv yaratadi (upsert, `:211-216`).
14. 🟠 **Ko'p guruhli o'quvchida PERIOD_CHARGE noto'g'ri:** `student.monthlyFee` (barcha guruhlar yig'indisi) ishlatiladi (`service/PaymentService.java:403-406`).
15. 🟠 **Oylikdan kam to'lov, hatto `amount=0` ham, `nextPaymentDate` ni 1 oyga suradi** va holatni PAID qiladi (`service/PaymentService.java:414`).
16. 🟠 **"Qarzdor"ning 4 dan ortiq bir-biriga mos kelmaydigan ta'rifi bor.** Dashboard, Analytics, KPI va `calculate-debt` turli raqam ko'rsatadi:
    - Dashboard: `service/DashboardService.java:50`
    - Analytics: `service/AnalyticsService.java:56`
    - `getDebtorsByDate`: `service/PaymentScheduleService.java:732`
    - `calculate-debt`: `service/PaymentService.java:759-771`
17. 🟠 **To'lovni bekor qilish yoki o'chirish API'si umuman yo'q**; `PERIOD_REFUND` hech qayerda yozilmaydi. O'quvchi bonus/jarimasi pulga ta'sir qilmaydi (`service/PaymentService.java:137-150`).
18. 🟠 **Preview va create bir xil hisoblamaydi:** preview bonus/jarimani hisobga olmaydi (`service/PaymentService.java:323-346` va `:137-150`).
19. 🟠 **Hech qayerda `@Version` yoki lock yo'q.** Balans, kassa balansi, chek raqami (`count()+1` + unique, `service/PaymentService.java:88-89`) va takroriy bosishlar poyga holatiga ochiq.

**Frontend shartnomasi**
20. 🟡 **Javob envelope'i 10 xil** (§4.1).
    - Sahifalash 4 xil: `PageResponse`, Spring `Page`, lid timeline `PageInfo`, chat kursori.
    - Xato shakli 3 xil (§4.2).
    - Envelope'siz endpointlar: KPI, `timetable/by-room`.
21. 🟡 **Majburiy query param yo'q bo'lsa, noto'g'ri sana-string kelsa yoki unique buzilsa → 500** (400 emas). §4.2 ga qarang.
22. 🟡 **`expiresIn` doim 86400**, haqiqiy muddat 8 soat. Har login boshqa sessiyani o'chiradi (§2.2).
23. 🟡 **26 ta enum `/api/enums` da yo'q.** Faqat 3 tasi (payment-methods, task-types, task-statuses) qaytariladi; qolganlarini frontend qattiq yozishi kerak (§5.2). Ko'p status maydonlari enum emas, erkin `String`:
    - `StudentGroup.paymentStatus`, `Teacher.status`, `Leave.status`, `Payroll.status`
    - `Lead.source`, `Lead.format`
24. 🟡 **`ContractTemplateDto.isDefault` JSON'da `"default"` bo'lib chiqishi mumkin** (TAXMIN, `dto/response/ContractTemplateDto.java:21`).
25. 🟡 **`Content-Disposition` CORS'da exposed emas** (`config/SecurityConfig.java:234`). Frontend eksport faylining nomini sarlavhadan o'qiy olmaydi, uni o'zi berishi kerak.
26. 🟡 **Kanban uchun alohida endpoint yo'q.** Ro'yxat har ustun uchun alohida `GET /api/leads?status=` so'rovi bilan olinadi; `sort` yo'q; source, format va taskState bo'yicha filtr yo'q (`service/LeadService.java:218`, `:864-910`).
27. 🟡 **Real-time faqat chatda.** Yangi lid, vazifa yoki to'lov uchun WS hodisasi yo'q. Yangi suhbat yaratilganda ham WS hodisa yuborilmaydi (§7).

### 9.1 Mavjud frontend bilan solishtirish
- **`frontend-api-usage.md` topilmadi** (`adizone-crm-backend`, `adizone-crm-front` va `adizone.uz` papkalarida qidirildi). Shuning uchun to'liq solishtirish **tekshirilmadi**.
- Agentlar qisman solishtirish qildi: eski `adizone-crm-front/src` da grep, natijalar TAXMIN. Backendda yo'q yoki mos kelmaydigan chaqiruvlar:
  - `POST /api/attendance/mark-bulk`, `GET /api/attendance/teacher/{id}` → 404
  - `/api/exams/{id}/registrations` → 404
  - `POST /api/promotions/promote` → 405
  - `GET /api/exams/{id}/calculate-payment` → 405 (backendda POST)
  - `GET /api/salary-rules/{id}` → 405
  - `POST /api/payroll/generate` body bilan yuboriladi, backend esa query param kutadi
  - `GET /api/groups?studentId=` — backend `studentId` ni e'tiborsiz qoldiradi
- Ishlatilmayotgandek ko'ringan endpointlar ro'yxati 9.8, 9.9 va 9.10 da.

### 9.2 Tiplanmagan (`Map<String,Object>`) javoblar — TS uchun qo'lda tiplash kerak
- `/api/teacher/dashboard` (`service/TeacherService.java:192-267`)
- `/api/students/stats`, `/api/teachers/stats`, `/api/students/trial`, `/api/students/{id}/history`, `/api/teachers/sync-from-users`
- `/api/search`: `q` 2 belgidan qisqa bo'lsa `{}` qaytadi
- Analitika grafiklari: `{label, amount}` yoki `{month, year, revenue}` shaklida (`service/AnalyticsService.java:75-93`, `:176-211`)
- `/api/payments/calculate-debt`
- `PayrollResponse.calculationDetails` — obyekt emas, JSON **string** (`service/PayrollService.java:139`)

### 9.3 Eskirgan, dublikat yoki bir martalik endpointlar (TAXMIN)
**Dublikatlar:**
- `POST /api/students/import` ≡ `POST /api/import/students`
- `POST /api/teachers/import` ≡ `POST /api/import/teachers`
- `/api/finance/expenses` ≡ `/api/expenses`
- `/api/students/search` ≈ `/api/students?search=`
- `/api/parents/{id}/students` ≈ `/{id}/link/{studentId}`
- `/api/notices/active` ≡ `/latest`
- Analytics'dagi `/marketing/sources` ≡ `/marketing-sources`

**Buzilgan yoki eskirgan:**
- `POST /api/users/create-for-student/{studentId}` — `studentId` ishlatilmaydi (`controller/UserController.java:118-156`).
- `PUT /api/exams/results/{resultId}` — javadoc'da `@deprecated` (`controller/ExamController.java:107`).

**`/api/admin/repair/**` (faqat SA):**
- `migrate-lead-statuses`, `link-teacher-users`, `note-student-attribution` — bir martalik yoki hech narsa qilmaydi.
- `recalculate-payment-dates`, `fix-payment-periods` — dryRun'siz, darhol yozadi.
- UI uchun foydali faqat ikkitasi: `verify-balances`, `rebuild-monthly-ledger` (dryRun default).

### 9.4 Vaqt zonasi
~133 ta zonasiz `LocalDate.now()` / `LocalDateTime.now()` bor, cron'lar JVM zonasida ishlaydi. Server UTC bo'lsa (TAXMIN), 00:00–05:00 (Toshkent vaqti) oralig'ida "bugun" kechagi kun bo'lib qoladi. Bu davomat qulfiga, OVERDUE holatiga va default to'lov sanasiga ta'sir qiladi. Tafsilot 9.12 da.

### 9.5 Ortiqcha fayllar
Manba papkasida git kuzatayotgan eski `.class` fayllar bor: `controller/PaymentController.class`, `dto/request/StudentRequest.class`, `service/GroupService.class`, `service/PaymentService.class`. `.gitignore` da `*.class` qoidasi yo'q.

### 9.6 TODO / FIXME
`src/main/java` bo'yicha `TODO|FIXME|XXX|HACK` grep natijasi: **haqiqiy TODO/FIXME yo'q**. Yagona mosliklar `util/PhoneUtils.java:4`, `:66` — bular telefon namunasidagi `XXX`.

Deprecated yoki o'lik kod:
- `service/StudentPaymentLifecycleService.java:83-87` (`@Deprecated checkOverduePayments`, cron o'chirilgan)
- `BalanceTransactionService.recordStudentOnly` (`:102-115`)
- `PaymentService.getDebtorsLegacy` (`:629-643`)
- `StudentPaymentPlan` entity, `GroupSchedule` jadvali, `Group.currentStudents`
- `PaymentScheduleConfig.SETTING_EXPECTED_PAYMENTS_UNTIL` (`config/PaymentScheduleConfig.java:13`)
- `JwtUtils.generateRefreshToken`
- `FileStorageService.getBaseUrl`
- MapStruct va opencsv bog'liqliklari

---

Quyidagi 9.7–9.13 bo'limlari modul bo'yicha tahlilning to'liq muammo ro'yxatlari (manba fragmentlaridan o'zgarishsiz olingan). Raqamlash har bo'limda alohida.

### 9.7 Auth / User / Teacher / Student / Parent / Search (inv1)


#### Ruxsat / xavfsizlik

- **#T1 (MUHIM) `/api/teachers/{id}/kpi` har qanday foydalanuvchiga ochiq.** `controller/TeacherController.java:78-89` da `@PreAuthorize` yo'q; SecurityConfig da `/api/teachers/**` uchun qoida yo'q, faqat `/api/teachers/me/**` (`config/SecurityConfig.java:173`) → `anyRequest().authenticated()` (`:199`). Servisda ham egalik tekshiruvi yo'q (`service/TeacherService.java:275-276`). Natija: TEACHER (shuningdek SM, ACC, hatto STUDENT/PARENT) istalgan o'qituvchining KPI sini va moliyaviy maydonlarini (`balance`, `bonus`, `advance`, `penalty` — `dto/response/TeacherKpiDto.java:12-15`) ko'ra oladi. Xuddi shu: `/{id}/kpi/trend` (`TeacherController.java:92`), `/{id}/kpi/daily` (`:122`); ularning Javadoc'i "Ruxsat: /{id}/kpi bilan bir xil" (`:91`, `:121`) — ya'ni ochiqlik "meros" qilingan. Tavsiya: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")` yoki teacher uchun egalik tekshiruvi.
- **#T2 (MUHIM) O'qituvchi shaxsiy/moliyaviy ma'lumoti hammaga ochiq.** `GET /api/teachers` (`TeacherController.java:38`), `GET /api/teachers/{id}` (`:73`), `GET /api/teachers/search` (`:172`) — `@PreAuthorize` yo'q → har qanday auth. `TeacherResponse` da `passportInfo`, `monthlySalary`, `basicSalary`, `dateOfBirth`, `address`, `permanentAddress`, `fatherName`, `motherName` (`dto/response/TeacherResponse.java:25-42`). STUDENT/PARENT rolidagi hisoblar ham ko'radi.
- **#U1 ADMIN SUPER_ADMIN ustidan to'liq nazoratga ega (himoyalarni aylanib o'tish).** `resetPassword` va `setActive` da SA himoyasi bor (`service/UserService.java:338-340`, `:384-386`), lekin: (a) `PUT /api/users/{id}/password` (`controller/UserController.java:189-199`) ADMIN ga SA parolini to'g'ridan-to'g'ri o'zgartirishga ruxsat beradi, sessiyalarni revoke qilmaydi; (b) `PUT /api/users/{id}` `isActive=false` yoki `role` ni o'zgartiradi — SA/o'zini tekshirish yo'q (`UserService.java:178-183`); (c) `POST /api/users` va `PUT /api/users/{id}` da ADMIN `role=SUPER_ADMIN` bera oladi (`UserService.java:96`, `:178-180`) — imtiyoz oshirish.
- **#A1 O'z parolini o'zgartirishda joriy parol tekshirilmaydi.** `controller/AuthController.java:113-122` — `ChangePasswordRequest.currentPassword` (`dto/request/ChangePasswordRequest.java:10`) e'tiborsiz; o'g'irlangan access token bilan parolni almashtirib, hisobni egallash mumkin. Refresh tokenlar revoke qilinmaydi; `newPassword` uzunligi cheklanmagan (CreateUserRequest da `@Size(min=6)` bor — `CreateUserRequest.java:29`).
- **#S1 TEACHER barcha o'quvchilarni ko'radi (scope bo'shliqlari).** `GET /api/students` va `/{id}` teacher-scoped (`service/StudentService.java:68-80`, `:108`), lekin `GET /api/students/search` (`StudentController.java:163`; `StudentService.java:548-561` scope yo'q), `/{id}/groups` (`:46`), `/left` (`:243`), `/trial` (`:251`), `/{id}/history` (`:258`) — SecurityConfig T ga ruxsat beradi (`SecurityConfig.java:160-161`), servisda cheklov yo'q.
- **#S2 Global qidiruv hamma uchun.** `GET /api/search` (`SearchController.java:33-34`) STUDENT/PARENT ga ham boshqa o'quvchilarning ism + telefonini beradi (`:52-55`); TEACHER scope yo'q.
- **#U2 `createForTeacher` mavjud istalgan userni o'qituvchiga bog'laydi.** `username` band bo'lsa `linkTeacherToUser(teacher, existing)` (`service/UserService.java:120-125`) — bu SA/ADMIN hisobi ham bo'lishi mumkin (TAXMIN: `linkTeacherToUser` rolni tekshirmaydi). Bundan tashqari controller `ALREADY_EXISTS` tarmog'ida servisni chaqiradi (`controller/UserController.java:102-111`), ya'ni "mavjud" deb javob berib ham yon ta'sir qiladi.
- **#A3 `PUT /api/auth/profile` validatsiyasiz.** `@RequestBody` da `@Valid` yo'q (`AuthController.java:66`) → `UpdateUserRequest` dagi `@Email`, `@Pattern`, `@Size` ishlamaydi; `validateEmailAndPhone` (unikallik) ham chaqirilmaydi (admin yo'li `UserService.java:164` da chaqiradi).
- **#R1 SecurityConfig va @PreAuthorize nomuvofiqliklari (zararsiz, lekin chalkash):** `/api/users/{id}` DELETE — URL: SA,A (`SecurityConfig.java:151`), metod: faqat SA (`UserController.java:223`). `/api/students/{id}/balance-history` — ACC URL da ruxsat oladi, metodda yo'q (`StudentController.java:103`), holbuki `/frozen` da ACC bor (`:59`) — TAXMIN: buxgalter balans tarixini ko'ra olmasligi ataylab emas. `/api/students/**` non-GET SM ga URL darajasida ochiq (`SecurityConfig.java:162-163`), lekin hech bir yozish metodi SM ga ruxsat bermaydi.

#### Javob shakli / nomlash nomuvofiqligi

- **#G1 KPI endpointlari ApiResponse o'ramaydi.** `GET /api/teachers/me/kpi` (`TeacherController.java:46`) va `/{id}/kpi` (`:79`) xom `TeacherKpiDto` qaytaradi; qo'shni `/kpi/trend`, `/kpi/daily`, `/kpi/ranking` esa `ApiResponse` ga o'ralgan (`:61`, `:93`, `:123`).
- **#G2 Majburiy query param yo'q bo'lsa 500.** `MissingServletRequestParameterException` uchun handler yo'q → catch-all 500 (`exception/GlobalExceptionHandler.java:185-194`). Ta'sir: `/api/teachers/search` va `/api/students/search` (`q` required), `/api/search` (`q` required).
- **#G3 "Mavjud" holati uchun 200 + success:true.** `POST /api/users`, `create-for-teacher`, `create-for-student` username band bo'lsa `message:"ALREADY_EXISTS"` va 200 (`UserController.java:49-59`, `:102-111`, `:124-134`) — boshqa joylarda dublikat `409 DuplicateResourceException` (`GlobalExceptionHandler.java:51-53`). Frontend `message` ni solishtirishi kerak.
- **#G4 Rollar va enumlar uchun ikki xil shakl.** `/api/roles` → `{value,label}` Map (`RoleController.java:24-27`), `/api/enums/*` → `EnumOptionDto {value,label,icon}` (`EnumController.java:29-33`). `/api/roles` faqat SA/A uchun, `/api/enums` hammaga.
- **#G5 Map qaytaradigan (DTO siz) endpointlar** — TS tiplash qiyin: `/api/teacher/dashboard` (`service/TeacherService.java:192-267`, `salary`/`salaryStatus` kalitlari ixtiyoriy), `/api/students/stats`, `/api/teachers/stats`, `/api/students/trial`, `/api/students/{id}/history`, `/api/teachers/sync-from-users`, `/api/search` (va `q<2` da `{}` — kalitlarsiz, `SearchController.java:36-38`).
- **#G6 Status turlari aralash.** `StudentRequest.status`/`marketingSource` — `String` (`StudentRequest.java:31-35`), javobda enum; noto'g'ri qiymat 400 emas, jimgina `ACTIVE`/`OTHER` (`StudentService.java:628-641`). `StudentDetailResponse.GroupSummary.paymentStatus` — `String` (`StudentDetailResponse.java:63`), `StudentResponse.paymentStatus` — `PaymentStatus` enum (`StudentResponse.java:39`). `TeacherRequest/Response.status` — erkin `String` (`TeacherRequest.java:43`). History `fromStatus/toStatus` — String (`entity/StudentStatusHistory.java:27-30`).
- **#G7 Qidiruv natijasidagi `url` lar eski frontend marshrutlariga qattiq bog'langan** (`/students/student-details/{id}`, `/teachers/teacher-details/{id}`, `/groups/{id}` — `SearchController.java:56`, `:76`, `:93`). Yangi Vue router shu yo'llarni qo'llashi yoki `type`+`id` dan o'zi qurishi kerak.
- **#G8 Base path nomlash:** `/api/teacher/dashboard` (birlik, `TeacherDashboardController.java:17`) va `/api/teachers/me/...` (ko'plik) — bir xil "o'qituvchi portali" uchun ikki prefiks.
- **#G9 Soft-delete xabarlari/semantikasi turlicha:** User → isActive=false, Teacher → status INACTIVE, Student → status LEFT (`StudentService.java:536`), Parent → isActive=false; unlink esa jismoniy o'chirish (`ParentService.java:131`).

#### Auth xatti-harakati

- **#A2 `expiresIn` noto'g'ri.** Doim `86400` (24 soat) qaytadi (`service/AuthService.java:99`, `:151`), haqiqiy access token muddati 8 soat (`application.yml:58`, izoh "24 hours" ham xato). Frontend `expiresIn` ga ishonmasin — JWT `exp` ni o'qisin yoki 401 da refresh qilsin.
- **#A4 Bitta sessiya.** Har login foydalanuvchining barcha refresh tokenlarini o'chiradi (`AuthService.java:80`) → ikkinchi qurilmada login birinchisining refresh'ini buzadi (access token 8 soatgacha ishlayveradi). Frontend refresh 401 ni "boshqa joyda kirildi" deb ko'rsatishi mumkin.
- **#A5 Logout access tokenni bekor qilmaydi** (`AuthService.java:156-162`); noma'lum token bilan ham 200.

#### Validatsiya / mustahkamlik

- **#U3 `create-for-student/{studentId}` — `studentId` ishlatilmaydi.** `controller/UserController.java:118-156` STUDENT rolli user yaratadi, lekin Student yozuviga bog'lamaydi; `@Valid` yo'q (`:122`) — `password` null bo'lsa `passwordEncoder.encode(null)` → IllegalArgumentException → 400 (`GlobalExceptionHandler.java:46-48`); telefon/email unikalligi tekshirilmaydi (UserService chetlab o'tilgan). TAXMIN: chala funksiya.
- **#U4 `create-for-teacher` da `@Valid` yo'q** (`UserController.java:100`) — ataylab (bo'sh maydonlar teacher'dan olinadi), lekin `password` null → 400 yuqoridagidek.
- **#P1 `POST /api/parents/{id}/students` DTO siz `Map`** (`ParentController.java:70-72`); `studentId` yo'q → NPE → 500 (`service/ParentService.java:103`). Xuddi shu ishni `POST /{id}/link/{studentId}` (`ParentController.java:60`) toza bajaradi — dublikat.
- **#U5 `GET /api/users` sahifalashsiz** va nofaollarni ham qaytaradi (`UserController.java:161`); `GET /api/students/left`, `/archived`, `/trial` ham sahifalashsiz (`StudentController.java:54`, `:243`, `:251`).
- **#Q1 Global search samarasiz:** barcha faol o'qituvchilar va BARCHA guruhlar xotiraga yuklanib Java da filtrlanadi (`SearchController.java:61-68`, `:81-86`).
- **#Q2 `defaultFrom` da `daily`/`monthly` bir xil natija** (`service/TeacherKpiService.java:242-245`) — ortiqcha shart.

#### Dublikat / ishlatilmayotgandek (TAXMIN)

- `POST /api/students/import` ≡ `POST /api/import/students` (`StudentController.java:171`, `controller/ImportController.java:34`); `POST /api/teachers/import` ≡ `POST /api/import/teachers` (`TeacherController.java:204`, `ImportController.java:46`). TAXMIN: bittasi eski frontend uchun qolgan.
- `GET /api/students/search` ≈ `GET /api/students?search=` (`StudentController.java:163` vs `:36`) — ikkinchisi teacher-scope bilan; TAXMIN: `/search` eskirgan.
- `POST /api/parents/{id}/students` ≈ `POST /api/parents/{id}/link/{studentId}` (`ParentController.java:68` vs `:60`).
- `POST /api/users/create-for-student/{studentId}` — `studentId` ishlatilmagani sababli (#U3) TAXMIN: frontend undan foydalanmaydi yoki noto'g'ri ishlaydi.
- `POST /api/teachers/sync-from-users` — bir martalik migratsiya/ta'mir vositasi (`TeacherController.java:216-226`); admin UI da kerak bo'lmasligi mumkin (TAXMIN).
- Ushbu fayllarda `TODO`/`FIXME`/`@Deprecated` topilmadi (grep: controller'lar, Auth/User/Teacher/Student/ParentService, asosiy request DTO lar).
- Uy-ro'zg'or: `dto/request/StudentRequest.class` — kompilyatsiya qilingan fayl manba papkasida turibdi (tasodifan commit qilingan, TAXMIN).
- Kosmetik: `UserController.java:222-229` — Javadoc `@PreAuthorize` va metod orasida joylashgan.

### 9.8 O'quv jarayoni modullari (inv2)


#### Ruxsat va xavfsizlik

1. **Leave: har qanday autentifikatsiyalangan foydalanuvchi boshqalarning arizalarini o'qiy oladi va boshqa nomidan ariza bera oladi.**
   - `GET /api/leaves/{id}` (`controller/LeaveController.java:46-49`), `GET /api/leaves/user/{userId}` (`:51-57`) va `POST /api/leaves` (`:59-63`) da `@PreAuthorize` yo'q.
   - SecurityConfig'da `/api/leaves` uchun qoida yo'q, shuning uchun `anyRequest().authenticated()` (`config/SecurityConfig.java:199`) ishlaydi. Natijada STUDENT, PARENT, SALES_MANAGER ham kira oladi.
   - `submitLeave` so'rovdagi `requesterId`/`teacherId` ga ishonadi (`service/LeaveService.java:64-81, 83-99`). Ism bo'yicha User qidirish `userRepository.findAll()` orqali qilinadi (`:92-96`), bu to'liq jadval skani.
   - `PATCH /status` da tasdiqlovchi joriy foydalanuvchidan emas, body'dagi `approvedById` dan olinadi (`LeaveService.java:120-126`). `status` erkin satr, bo'lmasa NPE → 500 (`:113`).
2. **Classes / Sections / Subjects GET'lari hamma uchun ochiq.** `@PreAuthorize` yo'q (`controller/AcademicController.java:22-32, 57-67, 92-102`) va URL qoidasi ham yo'q, shuning uchun `SecurityConfig.java:199` ishlaydi. Timetable GET'lari esa SA/A/T bilan cheklangan (`:100-103`). Bu nomuvofiqlik.
3. **Homework submissions: TEACHER o'ziga tegishli bo'lmagan uy vazifasiga topshiriq qo'sha oladi yoki tahrirlay oladi.** `addSubmission` va `updateSubmission` da `assertHomeworkAccess` chaqirilmaydi (`service/HomeworkService.java:100-131`). `@PreAuthorize("isAuthenticated()")` (`controller/HomeworkController.java:73`) chalg'itadi: amaldagi cheklov faqat URL qoidasidan keladi (`SecurityConfig.java:167-168`).
4. **Homework update: TEACHER vazifani boshqa o'qituvchiga o'tkazishi mumkin.** `buildHomework` `teacherId` ni teacher uchun ham qo'llaydi (`service/HomeworkService.java:166-168`). Create paytida bu ustiga yoziladi (`:66-68`), update paytida yozilmaydi.
5. **Exam: TEACHER scope bo'shliqlari.**
   - `registerStudent` (`service/ExamService.java:293+`) va `calculateExamPaymentPreview` (`:221`) da `assertExamAccess` yo'q.
   - `getEligibleStudents` tizimdagi **barcha** o'quvchilarni teacher scope'siz qaytaradi (`:272-278`). Bu teacherga boshqa guruhlarning o'quvchi ma'lumotlarini (telefon, balans) ochib beradi. Bundan tashqari `studentRepository.findAll()` va har bir o'quvchi uchun 2 ta so'rov (N+1).
   - `calculate-payment` `@PreAuthorize("isAuthenticated()")` bilan belgilangan (`controller/ExamController.java:48`), lekin URL qoidasi (`SecurityConfig.java:169-170`) uni SA/A/T bilan cheklaydi.
6. **Davomat: TEACHER boshqa guruhga yoza OLMAYDI** (`service/AttendanceService.java:60` → `TeacherAccessService.java:82-92`). Lekin quyidagi bo'shliqlar bor:
   - (a) `attendances[].studentId` guruh a'zosi ekanligi tekshirilmaydi (`AttendanceService.java:94-100`). O'z guruhiga har qanday o'quvchini davomatga yozish mumkin, `studentPaymentLifecycleService.onBillableAttendance` ham chaqiriladi (`:134-137`, ta'siri TAXMIN).
   - (b) Kelajakdagi sanalar bloklanmagan (`:70`).
   - (c) `studentId: null` bo'lsa `findById(null)` → InvalidDataAccessApiUsageException → 500 (TAXMIN).
   - (d) `getStudentAttendance` egalik tekshiruvidan o'tgach o'quvchining boshqa teacherlar guruhlaridagi davomatini ham qaytaradi (`:401-406`).
7. **Unlock ruxsati iste'mol qilinmaydi va muddati tugamaydi.** APPROVED bo'lgan so'rov (`service/AttendanceUnlockRequestService.java:113`) `markAttendance` da doim `exists...APPROVED` sifatida topiladi (`AttendanceService.java:73-75`), ya'ni o'sha o'tgan kun cheksiz ochiq qoladi.
   - `createRequest` sana o'tmishda ekanini tekshirmaydi (bugun yoki kelajak uchun ham so'rov yuborish mumkin).
   - Faqat PENDING dublikatlari bloklanadi, shuning uchun APPROVED bo'lgan kunga yana so'rov yuborish mumkin (`AttendanceUnlockRequestService.java:48-53`).
8. **Notices: chop etilmagan va nofaol e'lonlar hammaga ko'rinadi.**
   - `GET /api/notices` va `/{id}` hech narsani filtrlamaydi (`service/NoticeService.java:38-55`), `publishedTo`/`targetRole` qo'llanmaydi.
   - Admin `createdById` orqali e'lonni boshqa foydalanuvchi nomidan yarata oladi (`:93-96`).
9. **SecurityConfig ∩ @PreAuthorize nomuvofiqliklari.**
   - GET `/api/groups/{id}/suspended-students`: URL qoidasi ACC/T ga ruxsat beradi (`SecurityConfig.java:106-107`), `@PreAuthorize` esa faqat SA/A (`GroupController.java:52`).
   - GET `/api/promotions/**` da `@PreAuthorize` yo'q va faqat URL qoidasi himoya qiladi (`SecurityConfig.java:147-148`, `PromotionController.java:25-40`). URL qoidasi o'zgarsa, hamma uchun ochilib qoladi.
   - `ContractController` URL qoidasiga ega emas va faqat class-level `@PreAuthorize` ga tayanadi (`ContractController.java:25`).
10. **Homework/Exam DELETE.** `SecurityConfig.java:167` (homework) `:196` dan oldin turadi va DELETE'ni TEACHER'ga ham ochadi. Faqat `@PreAuthorize` SA/A (`HomeworkController.java:60`) himoya qiladi.

#### Javob shakllari va nomlash

11. **Envelope'siz javob:** `GET /api/timetable/by-room` `RoomTimetableDto` ni to'g'ridan-to'g'ri qaytaradi (`controller/AcademicController.java:143-144`), qolgan barcha endpointlar `ApiResponse` ichida qaytaradi.
12. **Polimorf javob:** `POST /api/timetable` `daysOfWeek` bo'lsa `List<TimetableResponse>`, bo'lmasa bitta `TimetableResponse` qaytaradi (`AcademicController.java:172-184`, `ResponseEntity<?>`). `PUT` esa `daysOfWeek` ni e'tiborsiz qoldiradi va faqat `dayOfWeek` talab qiladi (`service/AcademicService.java:517`).
13. **Bulk promote barcha xatolarni 500 ga aylantiradi** va `ErrorResponse` o'rniga `ApiResponse.error` qaytaradi (`controller/PromotionController.java:52-59`). 404 (guruh topilmadi) va 400 ham 500 bo'lib ketadi.
14. **Xato shakllari 3 xil:** `ErrorResponse`, `ApiResponse` va `Map` (`exception/GlobalExceptionHandler.java:35-195`). Majburiy query-param yo'q bo'lsa (masalan `GET /api/attendance/missing` da `groupId`, `/timetable/grid` da `dayOfWeek`, `PATCH /groups/{id}/status` da `status`) javob 500 bo'ladi, chunki `MissingServletRequestParameterException` uchun handler yo'q (TAXMIN).
15. **Status parametrlarining berilishi har xil:**
    - `PATCH /api/groups/{id}/status?status=` — query-param (`GroupController.java:82`)
    - `PATCH /api/leaves/{id}/status` — body `Map` (`LeaveController.java:68`)
    - `GET /api/groups?status=` — case-sensitive enum (`GroupController.java:34`), `PATCH` esa case-insensitive satr qabul qiladi (`GroupService.java:653-662`)
    - Contract `status` filtri noto'g'ri qiymatni jimgina e'tiborsiz qoldiradi (`ContractService.java:306-315`), unlock `status` esa 400 qaytaradi (`AttendanceUnlockRequestService.java:186-188`)
16. **"Count" javoblari har xil:** `/api/attendance/unlock-requests/count` → `data: Long` (`AttendanceUnlockRequestController.java:77`), `/api/notices/unread-count` → `data: {count}` (`NoticeController.java:50-52`).
17. **Status maydonlari enum emas, erkin `String`:**
    - `LeaveResponse.status` (`dto/response/LeaveResponse.java:20`)
    - `HomeworkSubmissionResponse.status` (`:19`)
    - `ExamRegistrationResponse.status/paymentStatus` (`:17,21`)
    - `ClassroomRequest.roomType` (`:12`)
    - `NoticeRequest.publishedTo` (`:17`)
    - `ExamRequest.examType` (`:14`)
18. **Nomlash nomuvofiqligi:**
    - Yo'llar: `/api/homework` va `/api/timetable` birlikda, qolganlari (`/api/exams`, `/api/notices` …) ko'plikda.
    - DTO suffikslari aralash: `*Request` va `*Dto` (`AttendanceUnlockCreateDto`, `ContractCreateDto`, `ContractTemplateCreateDto`), `*Response` va `*Dto` (`ContractDto`, `RoomTimetableDto`, `AttendanceUnlockResponseDto`).
    - Xona nomi maydonlari har xil: `ClassroomResponse.roomNumber`, `ClassroomBriefDto.name`, `TimetableGridRoomDto.name`, `TimetableResponse.roomName` + `roomNumber`, `GroupLessonDaysResponse.roomName`, `GroupResponse.room` + `classroomName`.
    - Javob xabarlari aralash tilda: "Group created" va "Xona qo'shildi" / "Shartnoma yaratildi".
19. **Dublikat yoki legacy maydonlar:**
    - `GroupResponse`: `studentGroups` va `students`; `schedules` (`ScheduleResponse` — `DayOfWeek` enum va `LocalTime`) va `scheduleDays` (String) bir xil ma'lumotni turli turda beradi (`dto/response/GroupResponse.java:37-42`, `dto/response/ScheduleResponse.java:8-10`).
    - `StudentSummary`: `fullName` va `studentName`, `joinedAt` va `joinDate`.
    - `GroupRequest`: `schedules` (legacy) va `scheduleDays`.
    - `ExamResultRequest/Response`: `marksObtained` va `score`, `remarks` va `notes`, `isPassed` va `passed` (`dto/response/ExamResultResponse.java:18-35`).
    - `/api/notices/active` va `/latest` bir xil metodni chaqiradi (`NoticeController.java:34-46`).
    - Guruhdan chiqarishning ikki yo'li bor: `DELETE /{g}/students/{s}` va `POST /{g}/remove-student`, ularning qo'shimcha ta'sirlari turlicha (`service/GroupService.java:600-650`).
20. **`ContractTemplateDto.isDefault` — primitiv `boolean`.** Lombok bunday maydon uchun `isDefault()` getter yaratadi, Jackson esa uni `"default"` nomli JSON kaliti sifatida chiqaradi (**TAXMIN**: yuqori ehtimol; `dto/response/ContractTemplateDto.java:21`). Mavjud frontend `tmpl.isDefault` ni o'qiydi (`adizone-crm-front/src/views/pages/settings/contract-templates.vue:57`), ya'ni badge ko'rinmasligi mumkin. Yangi frontendda `default` kalitini tekshirish kerak.
21. **`RoomLessonDto.courseColor` hech qachon to'ldirilmaydi** (`dto/response/RoomLessonDto.java:18`, `service/AcademicService.java:238-266`).
22. **Pagination:** `size` uchun yuqori chegara yo'q. `sort` param qabul qilinmaydi. Default `size` 20 yoki 50 bo'lib turlicha: `/homework/group` va `/leaves/teacher` da 50 (`HomeworkController.java:35`, `LeaveController.java:36`). `pageNumber` so'rovdagi qiymatdan olinadi, faqat Contract'da `Page` dan olinadi (`ContractService.java:89`).
23. **Validatsiya bo'shliqlari:**
    - `@Valid` yo'q: `ClassroomController.update` (`:48`), `GroupController.removeStudentWithReason` (`:116`), `ExamController.updateResult` va `updateResultLegacy` (`:102,111`).
    - DTO'da annotatsiya yo'q: `ContractTemplateCreateDto`, `ContractCreateDto`, `ExamResultRequest`, `RemoveStudentRequest`.
    - `BulkPromoteRequest.sourceYear/targetYear` tekshirilmaydi (`dto/request/BulkPromoteRequest.java:20,26`).
    - `LeaveSubmitRequest` da `from ≤ to` tekshirilmaydi.
    - `AttendanceRequest.attendances` elementlariga `@Valid` qo'yilmagan (`dto/request/AttendanceRequest.java:11`).
24. **Hard va soft delete aralash:**
    - Soft: Group (status CANCELLED), Course, Class, Section, Subject, Homework, Exam (`isActive=false`).
    - Hard: Classroom (`service/ClassroomService.java:79-84`), Timetable, Notice, Leave, Promotion, Contract, ContractTemplate.
    - Frontend DELETE'dan keyin ro'yxatda yozuv qolishi yoki qolmasligini modulga qarab hisobga olishi kerak.

#### TODO / FIXME / @Deprecated

25. Bu 12 controller va ularning servislarida `TODO`/`FIXME` topilmadi.
26. Yagona deprecated: `PUT /api/exams/results/{resultId}` (javadoc `@deprecated`, `controller/ExamController.java:107`). `@Deprecated` annotatsiyasi yo'q.
27. `ExamResultRequest.isPassed` "Deprecated" deb izohlangan (`dto/request/ExamResultRequest.java:29-30`).
28. `PromotionService.java` da izohga olingan kod qolgan (`fromClass`/`toClass`, `service/PromotionService.java:136-137`).

#### Mavjud frontend bilan nomuvofiqliklar (`adizone-crm-front/src/services/*.js`)

29. Frontend backendda **mavjud bo'lmagan** endpointlarni chaqiradi:
    - `POST /api/attendance/mark-bulk` (`attendanceService.js:8`) → 404
    - `GET /api/attendance/teacher/{id}` (`attendanceService.js:17`) → 404
    - `GET` va `POST /api/exams/{id}/registrations` (`examService.js:19,22`) → 404. Backendda buning o'rniga `POST /{id}/register-student?studentId=` bor.
    - `POST /api/promotions/promote` (`promotionService.js:4`) → 405, chunki `/{id}` naqshiga faqat GET/DELETE mos keladi. Backendda to'g'risi `POST /api/promotions`.
    - `GET /api/exams/{id}/calculate-payment` (`examService.js:25`) → 405, backend POST kutadi (`ExamController.java:47`).
30. `groupService.getByStudent` `GET /api/groups?studentId=` yuboradi (`groupService.js:57`), backend esa `studentId` ni e'tiborsiz qoldiradi (`GroupController.java:33-34`) va **barcha guruhlarni** qaytaradi.

#### Ishlatilmayotgandek endpointlar (TAXMIN — mavjud frontend `src/` da literal chaqiruv topilmadi)

- `GET /api/groups/{id}/schedule` (`GroupController.java:38`)
- `GET /api/groups/{id}/suspended-students` (`:51`)
- `PATCH /api/groups/{id}/status` (`:78`)
- `DELETE /api/groups/{groupId}/students/{studentId}` (`:104`)
- `GET /api/timetable/by-room` (`AcademicController.java:141`)
- `GET /api/timetable/teacher/{teacherId}` (`:166`)
- `POST /api/exams/{id}/register-student` (`ExamController.java:31`)
- `GET /api/exams/{id}/eligible-students` (`:41`)
- `PUT /api/exams/results/{resultId}` (`:108`)
- `GET /api/exams/students/{studentId}/results` (`:115`)
- `POST /api/homework/{id}/submissions` (`HomeworkController.java:72`)
- `PUT /api/homework/submissions/{submissionId}` (`:80`)
- `GET /api/notices/latest` (`NoticeController.java:41`)
- `GET /api/leaves/pending` (`LeaveController.java:40`)
- `GET /api/leaves/user/{userId}` (`:51`)
- `DELETE /api/leaves/{id}` (`:73`)
- `GET /api/promotions` (`PromotionController.java:25`)
- `GET /api/promotions/{id}` (`:32`)
- `POST /api/promotions` (`:42`)
- `DELETE /api/promotions/{id}` (`:62`)

### 9.9 CRM / lid / Meta / analitika / admin-repair (inv3)


#### Xavfsizlik / ruxsat

1. **`POST /api/leads/public` spamdan himoyalanmagan.** `permitAll` (`config/SecurityConfig.java:86`) + `@PreAuthorize("permitAll()")` (`controller/LeadController.java:62`). Loyihada rate-limit/captcha/throttle yo'q (butun `com.crm` bo'yicha `rate.?limit|bucket4j|captcha|throttl` qidiruvi bo'sh). Telefon dublikati tekshirilmaydi (`service/LeadService.java:138-140`), `LeadRequest` da `@Size` yo'q (`dto/request/LeadRequest.java:11-25`) — ixtiyoriy uzun matn. `source`/`format` erkin matn (UPPERCASE) — anonim foydalanuvchi istalgan manba yozishi mumkin (`service/LeadService.java:119-124`). Javobda to'liq `LeadResponse` anonimga qaytadi (`controller/LeadController.java:65-67`) — kichik ma'lumot oqishi (id, uuid, statusLabel).
2. **Meta webhook fail-open.** `meta.app-secret` bo'sh bo'lsa yoki `verify-signature=false` bo'lsa imzo tekshirilmaydi va har kim soxta leadgen event yubora oladi (`controller/MetaWebhookController.java:190-199`). Faqat WARN log. Frontend `MetaStatusDto.signatureVerified=false` ni qizil ko'rsatishi kerak (`dto/response/MetaStatusDto.java:31-40`). Verify token `String.equals` bilan taqqoslanadi (`controller/MetaWebhookController.java:80`) — kichik timing risk.
3. **`GET /api/lead-stages` barcha autentifikatsiyadan o'tganlarga ochiq** (`config/SecurityConfig.java:146`), shu jumladan TEACHER, ACCOUNTANT, STUDENT, PARENT (`entity/enums/UserRole.java`). Bu ataylab (`:143-145` izohi), lekin CRM voronkasi ma'lumoti xodim bo'lmaganlarga ham ko'rinadi. Yozish `@PreAuthorize` bilan to'g'ri cheklangan (`controller/LeadStageController.java:36,45,54,62`); `/api/lead-stages/**` qoidasi `DELETE /api/**` (`:196`) dan oldin turgani uchun DELETE ni FAQAT `@PreAuthorize` himoya qiladi — kimdir annotatsiyani olib tashlasa DELETE hammaga ochiladi.
4. **`/api/marketing/sources` hech qanday rol cheklovisiz** — SecurityConfig da qoida yo'q (`anyRequest().authenticated()` `config/SecurityConfig.java:199`), controllerda `@PreAuthorize` yo'q (`controller/MarketingController.java:9-12`). Xuddi shu ma'lumot `/api/analytics/marketing/sources` da faqat SA/ADMIN ga (`config/SecurityConfig.java:120`). TEACHER/ACCOUNTANT/STUDENT/PARENT ham o'quvchilar manba statistikasini ko'ra oladi.
5. **`/api/audit-logs` URL darajasida qoida yo'q** — faqat `@PreAuthorize` ga tayanadi (`controller/AuditLogController.java:31,48,57`). Hozir to'g'ri, lekin mo'rt (yangi metod annotatsiyasiz qo'shilsa hammaga ochiladi).
6. **Operator biriktirishda faollik tekshirilmaydi**: `createLeadByStaff` nofaol userni rad etadi (`service/LeadService.java:199-201`), `assignLead` esa yo'q (`service/LeadService.java:304-313`) — nofaol xodimga lid biriktirish mumkin.

#### Javob shakllari / nomlash nomuvofiqligi

7. **Uch xil "marketing manbalari" javobi**: `/api/marketing/sources` → tekis `Map<String,Long>` faqat mavjud manbalar (`controller/MarketingController.java:17-20`); `/api/analytics/marketing/sources` → `{bySource, total}` BARCHA enum qiymatlari 0 bilan (`service/AnalyticsService.java:278-287`); `/api/analytics/students.byMarketingSource` va `DashboardResponse.studentsBySource` → tekis, faqat mavjudlari (`service/AnalyticsService.java:71-73,298-301`). Bundan tashqari analitikada bitta metod ikki yo'lda (`/marketing/sources` va `/marketing-sources`, `controller/AnalyticsController.java:46`).
8. **Ikki "dashboard"**: `GET /api/dashboard/stats` (`DashboardStatsDto`) va `GET /api/analytics/dashboard` (`DashboardResponse`). "Qarzdorlar" ta'rifi farqli: `balance < 0` (`service/DashboardService.java:50`) vs `studentGroupRepository.findDebtors(now)` (`service/AnalyticsService.java:56`) — ikki ekranda ikki xil son chiqishi mumkin. `leftFromOrder` butun vaqt bo'yicha, qo'shnilari esa oylik (`service/DashboardService.java:45`).
9. **Grafik ma'lumot shakllari bir xil emas**: `/api/analytics/revenue` → `{label, amount}` va bo'sh davrlar 0 bilan to'ldirilgan (`service/AnalyticsService.java:176-211,244-249`); `DashboardResponse.revenueChart` → `{month, year, revenue}`, bo'sh oylar tushib qoladi (`service/AnalyticsService.java:75-83`); `studentGrowthChart` → `{month, year, count}` (`:85-93`). Hammasi `Map<String,Object>` — tiplanmagan.
10. **`period` qiymatlari mos emas**: revenue `daily|monthly|yearly` qabul qiladi (`service/AnalyticsService.java:251-260`), staff endpointlari faqat `daily|monthly`, `yearly` jimgina `monthly` ga aylanadi (`service/TeacherKpiService.java:229-235`). Noto'g'ri qiymat hech qayerda 400 bermaydi.
11. **`DashboardResponse` ichidagi `latestNotices`/`recentPayments` yarim to'ldirilgan** — `NoticeResponse`/`PaymentResponse` ning ko'p maydonlari null (`service/AnalyticsService.java:99-124`); masalan to'lovda `studentName` yo'q — frontend "oxirgi to'lovlar" jadvalida kimligini ko'rsata olmaydi.
12. **Sahifalash shakllari**: lid timeline o'z `{items, page: PageInfo, openTasks}` shaklida (`dto/response/LeadTimelineResponse.java:24-42`), qolganlari `PageResponse`. Default `size` har xil: 20 (leads, tasks, comments), 50 (timeline, meta events, audit), max 100/200. `GET /{leadId}/notes` va `/audit-logs/entity/...` umuman sahifalanmaydi.
13. **Ikki parallel izoh tizimi**: `/{id}/comments` (`lead_comments`, tahrirlanmaydi, sahifalangan) va `/{leadId}/notes` (`lead_notes`, tahrirlanadi, sahifalanmagan) (`controller/LeadController.java:174-239`). Path o'zgaruvchi nomi ham farqli (`{id}` vs `{leadId}`). Timeline ularni `editable` bilan ajratadi (`dto/response/LeadTimelineItemDto.java:65-80`).
14. **Enum/matn aralash kirish**: lid `status`, `format`, `source`, task `type`, meta `defaultStudyFormat` — erkin `String` (servisda parse, noto'g'ri → 400 yoki jimgina qabul); `studyFormat`, `paymentType`, `leadType`, `crmField` — haqiqiy enum (noto'g'ri → Jackson 400 `HttpMessageNotReadable`). Frontend ikkala xil xato shaklini kutishi kerak.
15. **HTTP semantikasi**: `PUT /api/meta/forms/{formId}` aslida qismli (PATCH) (`dto/request/MetaFormSettingsRequest.java:12-15`); `PUT /api/lead-stages/{id}` to'liq; lid izohi `PUT`, qolgan lid tahrirlari `PATCH`. DELETE lar 204 emas, 200 + `ApiResponse<Void>` qaytaradi.
16. **Import nomuvofiqliklari**: o'quvchi shablonida sarlavha 2-qatorda, o'qituvchida 1-qatorda (`service/ImportService.java:123` vs `:653`); o'quvchi shabloni to'g'ri xlsx MIME + qo'shtirnoqli fayl nomi, o'qituvchi `application/octet-stream` + qo'shtirnoqsiz (`controller/ImportController.java:62-67` vs `:99-103`); o'qituvchi shabloni controller ichida quriladi (`:73-97`). Xato elementlari sinfi har xil nomda (`LeadImportResult.RowError` vs `ImportResult.ImportIssue`), lekin JSON bir xil `{row, reason}`. `ImportService.validateStudents` (dry-run) mavjud, lekin endpoint YO'Q (`service/ImportService.java:103-106`) — `ImportResult.validRows` shunga mo'ljallangan. CSV hech qayerda qo'llab-quvvatlanmaydi.
17. **Xato envelope ikki xil**: 401/403 `ApiResponse.error` (`{success:false, message}`, `config/SecurityConfig.java:202-215`), qolgan xatolar `ErrorResponse` (`{timestamp,status,error,message,validationErrors}`); Meta webhook `text/plain`; export/template endpointlari `byte[]` (envelope yo'q).
18. `LeadStatsResponse.newCount` JSON da `"new"` (`dto/response/LeadStatsResponse.java:18-19`) — TS da zaxiralangan so'z emas, lekin `stats.new` sintaksisi ishlaydi; e'tibor uchun.

#### Funksional bo'shliqlar (kanban uchun)

19. Lid ro'yxatida `sort` parametri yo'q (qat'iy `createdAt DESC`, `service/LeadService.java:218`); `source`, `format`, `course`, `taskState`, summa bo'yicha filtr yo'q (`service/LeadService.java:864-910`). Alohida board endpointi yo'q — N ustun = N so'rov. `status` filtri noma'lum kodni jimgina qabul qiladi (bo'sh natija).
20. `GET /api/lead-stages` nofaol bosqichlarni ham qaytaradi (`service/LeadStageService.java:72-73`); `kanban-stats.columns` barcha `orderedCodes()` ni oladi (`service/LeadService.java:730`) — nofaol bosqich ustun sifatida chiqishi mumkin (**TAXMIN**: `cache()` nofaollarni ham o'z ichiga oladi deb).
21. `search` telefonni xom `LIKE` bilan qidiradi (`service/LeadService.java:893-896`), telefonlar esa kanonik shaklda saqlanadi (`config/PhoneDeserializer.java:31`) — "90 123 45 67" kabi bo'shliqli qidiruv topmasligi mumkin (**TAXMIN**).
22. `LeadImportController` `GET /{importBatch}` istalgan matnni qabul qiladi — `GET /api/leads/import/preview` kabi xato so'rov `countBatch("preview")` ga tushadi (`controller/LeadImportController.java:50`). Kutayotgan importlar xotirada — ko'p instansiyali deployda `execute` boshqa instansiyaga tushsa "muddati o'tgan" (`service/LeadImportService.java:115-121`).
23. `@Valid List<MetaQuestionMappingRequest>` (`controller/MetaAdminController.java:75`) — ro'yxat elementlariga `@NotBlank questionKey` kaskadlanmasligi mumkin (`List<@Valid X>` emas) — **TAXMIN** (Spring 6.1 method validation xatti-harakatiga bog'liq).
24. Potensial NPE: `countByMarketingSourceGrouped()` null manbani guruhlasa `row[0].toString()` yiqiladi (`controller/MarketingController.java:19`, `service/AnalyticsService.java:73,300`). Ustun nullable (`entity/Student.java:51-54`, default OTHER) — **TAXMIN**: null qiymatli yozuv bo'lsa 500.

#### TODO/FIXME/@Deprecated

25. Ko'rib chiqilgan controllerlar va ularning servislarida TODO/FIXME/@Deprecated topilmadi (yagona `@Deprecated` — `service/StudentPaymentLifecycleService.java:84`, bu inventarga tegishli emas).
26. Hujjat nomuvofiqligi: `LeadService.migrateLeadStatuses` javadoc'ida yo'l `POST /api/admin/repair/lead-statuses` (`service/LeadService.java:838`), haqiqiysi `/migrate-lead-statuses` (`controller/AdminRepairController.java:63`).

#### Bir martalik / eskirgan endpointlar (AdminRepairController)

27. **TAXMIN** — yangi admin frontendda oddiy UI kerak emas, ko'pi bir martalik migratsiya:
   - `migrate-lead-statuses` (`controller/AdminRepairController.java:63`) — DAY_1_WORKED, ENROLLED_* kabi eski statuslarni ko'chiradi (`service/LeadService.java:846-862`); `lead_stages` jadvaliga o'tilgandan keyin eskirgan.
   - `link-teacher-users` (`:35`) — oxirgi commit "keep teacher profiles in step with their user accounts" (0b0b0a2) avtomatik sinxronni qo'shgan (`service/TeacherService.java` `syncTeacherProfile`), ya'ni qo'lda bog'lash endi kerak bo'lmasligi mumkin.
   - `note-student-attribution` (`:94-101`) — hech narsa qilmaydi, faqat statik matn qaytaradi; o'chirishga nomzod.
   - `recalculate-payment-dates` (`:49`) va `fix-payment-periods` (`:56`) — `dryRun` yo'q, darhol yozadi; UI da ikki bosqichli tasdiq kerak.
   - `verify-balances` (GET, `:70`) va `rebuild-monthly-ledger` (dryRun=true default, `:81-83`) — diagnostika sifatida "Texnik xizmat" sahifasiga (faqat SUPER_ADMIN) chiqarilishi mumkin bo'lgan yagona foydali ikkitasi.

### 9.10 Moliya / oylik / fayl / chat REST (inv4)


#### Xavfsizlik
1. **Ochiq fayllar + saqlangan XSS xavfi.** `GET /api/files/**` JWT siz (`config/SecurityConfig.java:85`). Upload faqat mijoz yuborgan `Content-Type` headerini tekshiradi (`service/FileStorageService.java:40-43`), kengaytma esa cheklovsiz original nomdan olinadi (`controller/FileController.java:35-37`). `evil.html` + `Content-Type: image/png` → `<uuid>.html` saqlanadi va `probeContentType` → `text/html`, `Content-Disposition: inline` bilan API domenida ochiladi (`FileController.java:63-70`). `image/svg+xml` ham qonuniy o'tadi (SVG ichida script). Chat upload ham shunday: `text/plain`/`image/*` headeri bilan `.html`/`.svg` kengaytma (`service/ChatAttachmentService.java:138-149`, `:258-266`). `probeContentType` natijasi OS ga bog'liq — **TAXMIN** (Linux da odatda kengaytma bo'yicha).
2. **Chat biriktirmalari maxfiy emas**: ular ham `/api/files/<uuid>` orqali autentifikatsiyasiz beriladi (`FileStorageService.java:77`, `SecurityConfig.java:85`) — `ChatAccessService` a'zolik tekshiruvi fayl darajasida ishlamaydi. Himoya faqat UUID ni taxmin qilib bo'lmasligi.
3. **ACCOUNTANT kassani o'chira oladi**: `/api/cash-registers/**` qoidasi (`SecurityConfig.java:132-133`) global `DELETE /api/**` → SA/A qoidasidan (`:196-197`) oldin turadi va class `@PreAuthorize` ACC ni qo'shadi (`controller/CashRegisterController.java:31`, `:62-66`). Boshqa modullarda DELETE faqat SA/A — nomuvofiq siyosat.
4. **Bonus/jarima moduli SecurityConfig da yo'q** — himoya faqat `@PreAuthorize` ga tayanadi (`controller/BonusPenaltyController.java:26`; `SecurityConfig.java:199`). Natija: ACC yaratadi/bekor qiladi, lekin DELETE da 403 (`SecurityConfig.java:196`). Frontend ACC ga "o'chirish" tugmasini ko'rsatmasligi kerak.
5. **SALES_MANAGER** URL darajasida `/api/payments/**` ga to'liq ruxsatga ega (`SecurityConfig.java:130-131`), lekin metod darajasida faqat `GET /api/payments/student/{studentId}` ochiq (`controller/PaymentController.java:90-93`, `@PreAuthorize` yo'q). SALES_MANAGER to'lovni yarata/o'chira OLMAYDI; to'lov o'chirish endpointi umuman yo'q. Ziddiyat: URL qoidasi keng, metodlar tor — `@PreAuthorize` yo'qligi tasodifiymi, **TAXMIN** (ehtimol lid/o'quvchi kartasi uchun ataylab).
6. **Salary-rules faqat SUPER_ADMIN** (`SecurityConfig.java:128-129`, `SalaryRuleController.java:19`), vaholanki ADMIN/ACC `/api/payroll/calculate` va `/generate` ni ishga tushira oladi (`PayrollController.java:23-49`) — ular hisob qoidalarini ko'ra olmaydi. Admin UI da qoidalar sahifasini SA ga yashirish kerak.
7. Payroll GET endpointlarida `@PreAuthorize` yo'q (`PayrollController.java:51-67`) — faqat URL qoidasi (`SecurityConfig.java:126-127`) himoya qiladi; SecurityConfig o'zgarsa ochilib qolish xavfi.
8. Upload xatosi ichki exception matnini qaytaradi (`FileController.java:48`).

#### Moliyaviy mantiq / ma'lumot yaxlitligi
9. **Oylikni ikki marta to'lash mumkin**: `markAsPaid` statusni tekshirmaydi, har chaqiruvda kassadan yana chiqim yozadi (`service/PayrollService.java:301-334`).
10. **Oylik o'chirilganda** kassa chiqimi va qo'llangan bonus/jarimalar qaytarilmaydi (`PayrollService.java:292-295`).
11. **`PUT /api/payroll/{id}` upsert**: id topilmasa jimgina yangi yozuv yaratadi (`PayrollService.java:211-216`) — REST semantikasiga zid, 404 kutiladi.
12. `PayrollRequest.deductions` va `netSalary` qabul qilinadi, lekin e'tiborsiz: `deductions=0`, `netSalary=basic+allowances` (`PayrollService.java:390-395`). Payroll `status` erkin `String` (`dto/request/PayrollRequest.java:29`), filtr case-sensitive (`PayrollService.java:54-56`).
13. `receiptNumber = "RCP-" + (count()+1)` (`service/PaymentService.java:88-89`) + `unique=true` (`entity/Payment.java:81`) — parallel to'lovlarda yoki yozuv o'chirilgandan keyin dublikat → `DataIntegrityViolation` → 500.
14. `/api/payments/calculate-debt` eski formula (kun/30 × narx, `double`) ishlatadi (`PaymentService.java:759-771`) — ledger/`/debtors` bilan mos kelmaydi; javob shakli ham ikki xil (`:741-744` vs `:773-784`).
15. Preview bonus/jarimani hisobga olmaydi, create esa `applyBonuses` default `true` bilan qo'llaydi (`PaymentService.java:137-150`, `:692-694`) — preview dagi summalar yakuniy `discountAmount`dan farq qilishi mumkin.
16. `POST /cash-registers/{id}/income` `studentId` bilan `Student.balance` ni oshiradi, lekin `BalanceTransaction` daftariga yozmaydi (`service/CashRegisterService.java:395-400`) — to'lovdagi ledger mantiqi bilan farq (**TAXMIN**: balans tarixi nomutanosib).
17. `paymentMethodForCash` noto'g'ri qiymati: Payment da 400 (`PaymentService.java:678-686`), Expense da jimgina CASH (`service/FinanceService.java:136-138`), Payroll da jimgina e'tiborsiz (`PayrollService.java:372-379`).
18. `IncomeCreateDto.transactionType` aslida tranzaksiya NOMI sifatida saqlanadi (`CashRegisterService.java:389`) — nomi chalg'ituvchi.

#### Javob shakli / nomlash nomuvofiqligi
19. **Uch xil sahifalash**: Spring `Page` (`PaymentController.java:35`, `FinanceController.java:22`, `ExpenseController.java:27`, `CashRegisterController.java:86`), `PageResponse` (`PayrollController.java:52`, `BonusPenaltyController.java:32`), kursorli `List` (`ChatController.java:68-77`). Default `size` ham turlicha: payments **50**, qolganlari 20, chat 50. `size` ga cap yo'q (chatdan tashqari).
20. `GET /api/payments` — `meta` qo'shimcha aggregat faqat shu endpointda (`PaymentController.java:54`), aggregat yiqilsa `meta` jimgina yo'qoladi (`:48-53`).
21. Sana parametrlari: `@DateTimeFormat LocalDate` (Finance/Expense/`/payments/expected` → noto'g'ri → 400) vs `String` + `LocalDate.parse` (Payments list `PaymentService.java:548-550`, CashRegister `CashRegisterController.java:126-131`, BonusPenalty `BonusPenaltyController.java:115-120` → noto'g'ri → `DateTimeParseException` → **500**, handler yo'q).
22. Noto'g'ri enum filtrlari: ko'p joyda jimgina e'tiborsiz (payments `status`, expenses `category`, bonus `kind/targetType/status`, cash `type`), lekin cash-register `status` → 400 (`CashRegisterService.java:733-739`).
23. `uuid` turi: `UUID` (Payment/Expense/Payroll response) vs `String` (`CashRegisterDto.java:12`, `CashTransactionDto.java:15`, `BonusPenaltyDto.java:21`) — JSON da ikkalasi string, lekin TS tipida bir xil qilinsin.
24. Message tili aralash: inglizcha (`"Payment recorded"`, `"Expense recorded"`, `"Payroll created"`, `"Salary rule created"`) vs o'zbekcha (`"Kassa yaratildi"`, `"Yozuv yaratildi"`, `"Guruh yaratildi"`). Create statuslari: ko'pchilik 201, lekin `POST /api/payroll/generate`, `POST /api/files/upload`, `POST /api/chat/upload`, `POST /api/chat/conversations/direct` → 200.
25. `PATCH /cash-registers/{id}/status` bo'sh status uchun controllerning o'zi `ApiResponse.error` (400) qaytaradi (`CashRegisterController.java:72-75`), boshqa validatsiya xatolari esa `ErrorResponse` shaklida.
26. `PaymentMethod` yorlig'i/ikonkasi faqat `PayrollResponse` da (`paymentMethodLabel/Icon`, `dto/response/PayrollResponse.java:33-34`); Payment/CashTransaction da faqat enum nomi. PayrollResponse `paymentMethod` — `String`, boshqalarda enum.
27. Identifikator chalkashligi: `/api/payroll/calculate/{userId}` — **User id**, `/api/payroll/teacher/{teacherId}` — **Teacher id** (`PayrollController.java:31-39`, `:64-67`). `PayrollResponse.createdByName` — username (`PayrollService.java:436`), `BonusPenaltyDto.createdByName` — ism familiya (`service/BonusPenaltyService.java:390-393`).
28. `ExpenseCreateDto` (kassa chiqimi) va `ExpenseRequest` (xarajat moduli) nomlari chalkash; `ExpenseResponse` da `teacherId` yo'q, faqat `teacherName`.
29. `PayrollResponse.calculationDetails` — JSON obyekt emas, JSON **string** (`PayrollService.java:139`), `SalaryCalculationDto.details` esa `Map` — frontend `JSON.parse` qilishi kerak.
30. `MissingServletRequestPartException` xabari "student import uchun 'file' kerak" deydi (`GlobalExceptionHandler.java:104-107`), `MultipartException` xabari "Excel (.xlsx) yuboring" (`:97-102`) — rasm/chat upload uchun chalg'ituvchi.
31. `ChatAttachmentService` izohi (`:92-94`) `FileStorageService` ikkinchi hajm to'sig'i deydi, lekin `save()` hajmni tekshirmaydi (`FileStorageService.java:65-78`).

#### Dublikat / ishlatilmayotgan (TAXMIN — eski `adizone-crm-front/src` bo'yicha grep)
32. `GET/POST /api/finance/expenses` (`FinanceController.java:21-36`) — `GET/POST /api/expenses` ning aynan dublikati; eski front faqat `/api/expenses` ni ishlatadi (`services/financeService.js`) → **ishlatilmaydi (TAXMIN)**.
33. Eski frontendda topilmadi (**TAXMIN** ishlatilmaydi): `GET /api/payments/history` (`PaymentController.java:69`), `GET /api/payments/archived` (`:63`), `GET /api/payments/calculate-debt` (`:110`), `GET /api/cash-registers/{id}/balance` (`CashRegisterController.java:80`), `GET /api/cash-registers/{id}/transactions/export` (`:105`), `DELETE /api/payroll/{id}` (`PayrollController.java:92`), `GET /api/payroll/calculate/{userId}` (`:31`).
34. Eski front `GET /api/salary-rules/{id}` chaqiradi (`salaryRuleService.js:8`), backendda yo'q → 405 (**TAXMIN**).
35. Eski front `POST /api/payroll/generate` ga body yuboradi (`payrollService.js:29`), backend esa `@RequestParam` (query) kutadi (`PayrollController.java:43-46`) → month/year yo'q → 400 (**TAXMIN**, agar axios interceptor o'zgartirmasa). Yangi frontendda query parametr sifatida yuborish kerak.
36. `CashRegisterService.deleteExpense` faqat Payroll ichidan chaqiriladi (`PayrollService.java:266`) — tranzaksiyani o'chirish/bekor qilish REST endpointi YO'Q. Xarajat, to'lov va kassa tranzaksiyalarini tahrirlash/o'chirish API da umuman yo'q.
37. Fayl o'chirish endpointi yo'q — yuborilmagan chat uploadlari va almashtirilgan rasmlar diskda qoladi (yetim fayllar).
38. `FileStorageService.getBaseUrl()` (`:89-91`) va `app.base-url` hech qayerda ishlatilmaydi — URL lar nisbiy qaytadi.

#### Ortiqcha fayl
39. **`controller/PaymentController.class`** — kompilyatsiya qilingan eski bytecode (4637 bayt, 2025-05-04) manba papkasida va **git da kuzatiladi** (`git ls-files` tasdiqladi; oxirgi commit `1491306`). Ichidagi satrlar eski controller versiyasini ko'rsatadi (preview/expected/debtors yo'q). Maven `src/main/java` dagi `.class` ni kompilyatsiya qilmaydi, lekin chalkashlik manbai — o'chirish va `.gitignore` ga `*.class` qo'shish kerak (`.gitignore` da faqat `.classpath` bor, qator 29).

#### TODO/FIXME/@Deprecated
40. Ko'rib chiqilgan 9 controller va tegishli servislar (`PaymentService`, `FinanceService`, `CashRegisterService`, `PayrollService`, `BonusPenaltyService`, `SalaryRuleService`, `FileStorageService`, `ChatAttachmentService`) da `TODO`/`FIXME`/`@Deprecated` topilmadi. `PaymentService.getDebtorsLegacy` (`:629-643`, "Legacy" izohli) faqat `PaymentReminderService.java:27` da ishlatiladi; `PaymentService.getAllPayments(LocalDate, LocalDate)` (`:563-572`) va `FinanceService.getExpenses` (`:41-46`) — chaqiruvchisi topilmadi (o'lik kod, **TAXMIN**).

### 9.11 Domen modeli


**Bir tushuncha — ikki maydon / ikki tur**
1. **Billing holati ikki joyda, ikki turda**: `Student.paymentStatus` — `PaymentStatus` enum (`entity/Student.java:109-112`), `StudentGroup.paymentStatus` — erkin `String` (`entity/StudentGroup.java:111-113`), servisda `"FROZEN"`, `"ARCHIVED"`, `"TRIAL"` literallari (`service/StudentService.java:974`, `service/StudentPaymentLifecycleService.java:98,107`, `service/PaymentScheduleService.java:561-566`). Xato yozilgan literal jim o'tadi.
2. `PaymentStatus` ikki xil tushunchani aralashtiradi: to'lov yozuvi holati (PAID/CANCELLED/PARTIAL) va o'quvchi billing holati (TRIAL/SUSPENDED/ARCHIVED/FROZEN) (`entity/enums/PaymentStatus.java:1-6` javadoc o'zi aytadi). `Payment.status` amalda doim PAID.
3. **Teacher**: `status` + `isActive` (V51 bilan sinxronlashtirildi, lekin ikki maydon qoldi), `monthlySalary` vs `basicSalary` (`entity/Teacher.java:60,110`), `hireDate` vs `joiningDate` (:63,103). **CashRegister**: `status` + `archived` (`entity/CashRegister.java:45,50`). **Contract**: `status=ACCEPTED` + `offerAccepted` (`entity/Contract.java:48,51`). **Attendance**: `status=EXCUSED` + `excused` boolean (`entity/Attendance.java:38,49`) — `ABSENT+excused=false` va `EXCUSED+excused=false` holatlari mumkin.
4. **StudentGroup** sanalari: `leaveDate` vs `exitDate` (ikkalasi ham yoziladi, lekin `removeStudentFromGroup(studentId, groupId)` faqat `leaveDate` yozadi — `service/GroupService.java:604`); `nextPaymentDate` vs `nextPaymentDue` (`entity/StudentGroup.java:41,108`); `Student.nextPaymentDate` ham bor. `Student.balance` vs `StudentGroup.balance` (sync qilinadi — `BalanceTransactionService.syncStudentBalanceFromGroups`).
5. **Jadval/ vaqt**: `GroupSchedule` (DayOfWeek enum + LocalTime) va `GroupScheduleDay` (String + String) — ikki xil model; `Timetable.dayOfWeek` ham String. `Group.room` (String) vs `Group.classroom` (FK) vs `GroupScheduleDay.room` (FK).
6. **Lead**: `format` String vs `StudyFormat` enum; `source` String vs `MarketingSource` enum — default `"WEBSITE"` (`entity/Lead.java:67`, `service/LeadService.java:124,163`) va `"META"` MarketingSource'da yo'q, konvertda jimgina `OTHER` bo'ladi (`service/LeadService.java:957-965`). `course` — String, `Course` entity'ga FK emas. `LeadComment` va `LeadNote` — deyarli bir xil tushuncha, ikki jadval.
7. `Payment.periodStart/periodEnd` ustunlari `period_from/period_to` + `@Transient getPeriodFrom/To` aliaslari (`entity/Payment.java:63-126`) — JSON'da qaysi nom chiqishi DTO'ga bog'liq, chalkash.
8. `Parent.relation` va `StudentParent.relation` — bir ma'lumot ikki joyda.

**Ishlatilmayotgan entity/enum/qiymatlar (grep bilan tekshirildi)**
9. `StudentPaymentPlan` — faqat entity + repository, hech bir servis ishlatmaydi. `GroupSchedule` — faqat `Group.schedules` (`entity/Group.java:69-71`) orqali, repository yo'q; `group_schedules` jadvali o'lik.
10. `Group.currentStudents` (`entity/Group.java:52-54`) — hech qayerda yangilanmaydi (servislar `countByGroupIdAndIsActiveTrue` ishlatadi). `StudentGroup.suspendedAt/suspensionReason` — hech qayerda yozilmaydi.
11. Hech qachon o'rnatilmaydigan enum qiymatlari: `TaskStatus.CANCELLED` (Task o'chirish — hard delete), `CashTransactionStatus.PENDING/CANCELLED`, `PaymentStatus.PARTIAL/CANCELLED` (SUSPENDED faqat saqlanadi), `StudentStatus.FINISHED/ARCHIVED` (faqat sanaladi — `service/DashboardService.java:54`, `StudentService.java:579`), `BalanceTransactionType.PERIOD_REFUND` (javadoc "to'lov bekor qilinsa" deydi, lekin to'lovni bekor qilish yo'li yo'q), `IncomeCategory.OTHER_INCOME`.
12. Maktab modelidan qolgan entity'lar (ClassEntity, Section, Promotion, Subject; Exam/Homework/Timetable ichidagi `classEntity`/`section` FK'lari) — o'quv markazi modeliga (Group/Course) mos emas; hali ham controller'lari bor (masalan `controller/PromotionController.java`).

**Holat mashinalaridagi bo'shliqlar**
13. `StudentStatusHistory.changedBy` **hech qachon o'rnatilmaydi** (grep `setChangedBy` — faqat lead tarixi) → frontend "kim o'zgartirdi" ni ko'rsata olmaydi.
14. Student statusi tarixsiz o'zgaradigan yo'llar: `PUT /api/students/{id}` (istalgan status, noma'lum → jimgina ACTIVE; `service/StudentService.java:636-642`), `DELETE` → LEFT (:534-538, guruhlar ochiq qoladi), cron `archiveInactiveStudents` → FROZEN (`service/StudentPaymentLifecycleService.java:113`) — freeze hisob-kitobi va tarixsiz, `sg.paymentStatus="ARCHIVED"` lekin student FROZEN.
15. Konvert bosqichidan chiqilgandan keyin `Lead.converted=true` va `lead.student` qoladi (`service/LeadService.java:347` faqat kirishni bloklaydi) → lid OPEN bosqichda "konvert qilingan" bo'lib qoladi va qayta konvert qilib bo'lmaydi (:614-618).
16. `GroupStatus` o'tish cheklovisiz; `addStudentToGroup` CANCELLED/COMPLETED guruhga ham qo'shadi (`service/GroupService.java:520-598`), import esa faqat ACTIVE/FORMING (`service/ImportService.java:64-65`). Commit 876897a dan keyin UI default FORMING → ko'p pickerlar (faqat ACTIVE) yangi guruhlarni ko'rmaydi.
17. Leave: status validatsiyasiz erkin matn, `body.get("status")` null bo'lsa NPE/500, `approvedById` klientdan (`service/LeaveService.java:126-146`).
18. Contract: `markSigned` shartsiz (ACCEPTED→SIGNED ham) (`service/ContractService.java:148-152`); `contractNumber = "CTR-" + (count()+1)` (:166-169) — shartnoma o'chirilgach keyingi raqam mavjud raqam bilan to'qnashadi (U constraint → 500). Shablonni o'chirish unga bog'langan shartnomalar bo'lsa FK xatosi (`deleteTemplate` :78-80).
19. Attendance unlock APPROVED muddatsiz (bir marta tasdiq — o'sha kunni cheksiz tahrirlash); kelajak sanaga teacher cheklanmagan (`service/AttendanceService.java:70`).

**Sxema**
20. README "Flyway" deydi, lekin Flyway yo'q; dublikat versiyalar (V31×2, V35×2). Migratsiyalardagi UNIQUE/partial indekslar (V25, V27, V40, V49, V50) toza o'rnatishda faqat ddl-auto bilan **yaratilmaydi** — idempotentlik (Meta leadgen, teacher↔user) shu indekslarga tayanadi.
21. `EnumCheckConstraintCleaner` public sxemadagi `= ANY (ARRAY[...])` ko'rinishidagi **har qanday** CHECK ni o'chiradi (`config/EnumCheckConstraintCleaner.java:36-42`) — kelajakda qo'lda `CHECK (x IN (...))` qo'shilsa, u ham startup'da jimgina o'chadi.

**TODO/FIXME**: `entity/` va `entity/enums/` ichida `TODO|FIXME|XXX|HACK|@Deprecated` topilmadi (grep natijasi bo'sh). Eslatma: `service/StudentPaymentLifecycleService.java:83-87` da `@Deprecated checkOverduePayments` (o'chirilgan cron).

### 9.12 Moliya biznes qoidalari


#### Mantiqiy ziddiyatlar / xatolar
1. **Ko'p guruhli o'quvchida noto'g'ri PERIOD_CHARGE**: `resolvePaymentPeriod` `fee = student.monthlyFee` oladi (`service/PaymentService.java:403-406`), bu esa BARCHA faol guruhlar narxi yig'indisi (`service/PaymentScheduleService.java:192-206`). 2 guruh × 500k da bitta guruh uchun 500k to'lov → `months=0`, PERIOD_CHARGE yozilmaydi, pul balansda qoladi. `BalanceExpectationService` esa `resolveMonthlyFee(sg)` ishlatadi (`service/BalanceExpectationService.java:221`) → verify-balances doimiy "missing period charge" ko'rsatadi.
2. **Oylikdan kam to'lov ham nextPaymentDate ni 1 oyga suradi** (`periodEnd = start + max(months,1)`, `service/PaymentService.java:414`), status PAID bo'ladi, lekin davr debeti yozilmaydi. `amount=0` to'lov ham ruxsat (`@DecimalMin("0.0")`, `dto/request/PaymentRequest.java`) → o'quvchi bepul 1 oy "PAID" bo'ladi.
3. **PER_LESSON da chegirma balansga neytral emas**: kredit = cashAmount (chegirmasiz), debet = har dars uchun to'liq `lessonPrice` (`service/AttendanceService.java:352-358`), `purchased` esa gross dan (`service/PaymentScheduleService.java:336-340`, `repository/PaymentRepository.java:194-203`) → chegirma summasi keyin qarz bo'lib chiqadi.
4. **Student bonus/jarima pulga ta'sir qilmaydi**: `applyPendingForStudent` ledger yozilgandan keyin chaqiriladi va faqat `bonusDiscount/discountAmount/notes` ni o'zgartiradi (`service/PaymentService.java:137-150`). Yozuv APPLIED bo'lib "ishlatilgan" hisoblanadi. `applyBonuses` default true — har bir to'lov jim yeydi.
5. **Enrollmentsiz to'lov ledgerga tushmaydi** (`service/PaymentService.java:210-212`), `studentGroup` va `group` null bo'lgani uchun `findUnlinkedByStudentAndGroup` ham topa olmaydi — pul balansdan "yo'qoladi".
6. **`groupId` noto'g'ri bo'lsa jim fallback** — birinchi faol SG ga yoziladi (`service/PaymentService.java:349-353`).
7. **Holat student darajasidagi "biror to'lov bor"ga asoslangan** (`service/PaymentScheduleService.java:550-557`): yangi (ikkinchi) guruh sanasi kelguncha PAID ko'rinadi; nextPaymentDate esa SG ning o'z to'lovi bo'lmasa boshqa guruhning oxirgi periodEnd dan olinadi (`:501-520`) — `paymentStartDate` e'tiborsiz qoladi.
8. **Bugun to'lashi kerak bo'lganlar hech bir ro'yxatda yo'q**: to'lov tarixi bor o'quvchi `nextPaymentDate == bugun` da PAID (OVERDUE faqat `< bugun`), debtors esa PAID ni chiqaradi (`:762-765`), expected faqat `> bugun` (`:666`).
9. **4+ xil "qarzdor" ta'rifi**: `getDebtorsByDate` (sana+status, `service/PaymentScheduleService.java:732`); Dashboard `student.balance < 0` (`service/DashboardService.java:50`); Analytics `sg.nextPaymentDate < today`, SG soni, TRIAL ham (`service/AnalyticsService.java:56,303`, `repository/StudentGroupRepository.java:76-79`); KPI debtor (`repository/StudentGroupRepository.java:143-146`); `calculate-debt` kun/30 (`service/PaymentService.java:759-771`). Raqamlar ekranlar orasida mos kelmaydi.
10. **Qarz summasi balansni hisobga olmaydi**: `totalDebt = monthlyFee × monthsUnpaid` (`service/PaymentScheduleService.java:781-786`) — balansi musbat o'quvchi ham qarzdor summasi bilan chiqadi.
11. **To'lovni bekor qilish yo'q**, `PERIOD_REFUND`/`CANCELLED` — o'lik kod (`entity/enums/BalanceTransactionType.java`, `service/BalanceExpectationService.java:71-73`).
12. **Kassaga qo'lda kirim `student.balance` ni ledgersiz o'zgartiradi** (`service/CashRegisterService.java:395-400`) — keyingi har qanday `record()` → `syncStudentBalanceFromGroups` uni qayta yozib yo'q qiladi.
13. **Freeze**: bitta guruh muzlatilsa ham o'quvchi to'liq FROZEN, `nextPaymentDate=null` (`service/StudentService.java:999-1001`); MONTHLY to'langan davr qoldig'i qaytarilmaydi; unfreeze boshqa guruhga bo'lsa balans ko'chmaydi (`:1178-1186`), rejim MONTHLY ga majburlanadi (`:1166`).
14. **transfer-group**: balans ko'chmaydi, individual narx va PER_LESSON yo'qoladi (`service/StudentService.java:398-418`).
15. **Avto-arxiv** o'quvchini `FROZEN` qiladi, lekin `StudentStatusHistory`/ledger yozmaydi va SG ni "ARCHIVED" deydi (`service/StudentPaymentLifecycleService.java:106-115`) — freeze bilan farqlab bo'lmaydi; unfreeze `exitReason="FROZEN"` ni qidirgani uchun bu SG dan balans ko'chirmaydi.
16. **`onLessonAttended` idempotent emas**: bir kunning davomatini qayta saqlash `lessonsAttended` ni yana oshiradi (`service/StudentPaymentLifecycleService.java:46-47`).
17. **PER_LESSON narx o'zgarsa** LESSON_REFUND yangi narx bilan qaytaradi (`service/AttendanceService.java:344,360-365`) — eski charge bilan mos kelmaydi.
18. **"1-dars bepul" (isTrial) izohi** (`entity/StudentGroup.java:46`) amalga oshirilmagan — trial PER_LESSON dan ham yechiladi.
19. **Payroll**: `markAsPaid` takroriy chaqiruvda ikkinchi kassa chiqimi (`service/PayrollService.java:301-337`); `generate overwrite=true` avval APPLIED bo'lgan bonus/jarimalarni yo'qotadi (adjustment 0 ga tushadi, yozuvlar APPLIED qolaveradi, `:143-155`); `deletePayroll` bonuslarni PENDING ga qaytarmaydi, kassani tiklamaydi (`:292-295`); `PUT` upsert va ixtiyoriy `status` string ("PAID" qo'yilsa kassa yozilmaydi) (`:208-216`, `:398`); `generate` oyda biror payroll bo'lsa `overwrite=false` bilan yangi xodimlarni ham qo'sha olmaydi (`:87-90`).
20. **Oylik hisobi hozirgi holatga bog'liq**: TEACHER — hozirgi `g.teacher_id` (o'qituvchi almashsa butun oy yangisiga, `repository/PaymentRepository.java:295`); ADMIN KPI — hozirgi ACTIVE soni (`service/SalaryCalculationService.java:232`). O'tgan oyni qayta hisoblash boshqa natija beradi. `cashAmount=0` (balansdan) to'lovlar ham "to'lagan o'quvchi" sanaladi. Yangi o'quvchi: birinchi to'lov `MIN(id)` bo'yicha, sana esa o'sha yozuvdan — orqaga sanalangan to'lovlarda noto'g'ri (`repository/PaymentRepository.java:315-326`).
21. **KPI**: `paymentRate/onTimePaymentRate` from/to dan qat'i nazar HOZIRGI holat (`service/TeacherKpiService.java:154-155`) — trend grafigida o'tgan oylar uchun ma'nosiz; TRANSFERRED/FROZEN chiqishlar "left" sifatida retention ni tushiradi (`repository/StudentGroupRepository.java:163`). Izohlarda hal qilinmagan savol qoldig'i (`service/TeacherKpiService.java:208-214`).
22. **Finance report** payroll va kassa orqali qo'lda chiqim/kirimlarni ko'rmaydi (`service/FinanceService.java:145-151`); Analytics da tushum oy oxirigacha, xarajat bugungacha (`service/AnalyticsService.java:58-64`).
23. **ARCHIVED kassa** operatsiyalarni qabul qilaveradi (`service/CashRegisterService.java:343-371`, `454-485`).
24. **`discountPercentage`** saqlanadi, hech qayerda qo'llanmaydi (`service/GroupService.java:577`, `service/PromotionService.java:137`).
25. **Davomat**: kelajak sanaga cheklov yo'q; unlock muddatsiz; item studentId guruhga tegishliligi tekshirilmaydi (`service/AttendanceService.java:67-110`).
26. **Rollar**: ACCOUNTANT to'lov qabul qiladi, lekin `balance-history` ni ko'ra olmaydi (faqat SUPER_ADMIN/ADMIN, `controller/StudentController.java:102-103`); `/api/payments/**` URL darajasida SALES_MANAGER ga ochiq (`config/SecurityConfig.java:130-131`), lekin `@PreAuthorize` faqat `GET /student/{id}` ni qoldiradi.

#### Poyga holatlari (race)
- `BalanceTransactionService.record` — `sg.balance` read-modify-write, lock/`@Version` yo'q (`service/BalanceTransactionService.java:68-71`); loyihada birorta `@Version`/`@Lock` yo'q. Parallel to'lov + davomat → yo'qolgan yangilanish.
- Kassa balansi xuddi shunday (`service/CashRegisterService.java:661-685`).
- Chek raqami `count()+1` + `unique` (`service/PaymentService.java:88-89`, `entity/Payment.java:81`) → parallel to'lovda DataIntegrityViolation → umumiy handler 500 (`exception/GlobalExceptionHandler.java:185`). Shartnoma raqami ham (`service/ContractService.java:168`, unique emas → dublikat raqam).
- Davomat: find-then-insert (`service/AttendanceService.java:97-110`) — ikki marta yuborilsa dublikat va ikki LESSON_CHARGE (**TAXMIN**: DB da unique constraint bor-yo'qligi tekshirilmadi).
- Idempotentlik kaliti yo'q: to'lov, payroll pay, transfer double-click → ikki marta.
- Ikki parallel to'lov bir xil PENDING student bonusini ikkalasi ham "qo'llashi" mumkin (`service/BonusPenaltyService.java:197-220`).

#### Yaxlitlash (BigDecimal)
- `PeriodChargeFormula.months`: `divide(fee, 0, DOWN)` (`service/PeriodChargeFormula.java:46`); PER_LESSON `purchased`/`bought`: `DOWN` (`service/PaymentScheduleService.java:339`, `service/PaymentService.java:268`); lessonPrice: scale 2 `HALF_UP` (`service/PaymentScheduleService.java:422,460`) → `lessonPrice × darslar ≠ monthlyFee` (tiyinlik qoldiq); `formatUzs` `HALF_UP` scale 0 (`service/PaymentService.java:725`); imtihon `HALF_UP` 2 (`service/ExamPaymentCalculatorService.java`).
- Kirish summalari `setScale` qilinmaydi — 2 dan ko'p kasr DB da jim yaxlitlanadi, hisob (split validatsiya, ledger) esa yaxlitlanmagan qiymat bilan bajariladi.
- `double` arifmetika: `calculateStudentDebt` (`service/PaymentService.java:763-778`), KPI `round1` (`service/TeacherKpiService.java:287-289`), Telegram summasi `doubleValue()` (`service/PaymentReminderService.java:40`).

#### Vaqt zonasi
- Servislarda ~133 ta zonasiz `LocalDate.now()/LocalDateTime.now()`; `application.yml` da `user.timezone`/`hibernate.jdbc.time_zone` yo'q, `TimeZone.setDefault` yo'q; cron'lar JVM zonasi bo'yicha (`CrmApplication.java:11`). Server UTC bo'lsa (**TAXMIN**): 00:05 job Toshkentda 05:05 da ishlaydi; 00:00–05:00 oralig'ida "bugun" = kecha → davomat qulfi (`service/AttendanceService.java:67-70`) kechagi kunni 5 soat ochiq qoldiradi, OVERDUE kech tushadi, to'lov sanasi default (`service/PaymentService.java:86`) kechagi kun bo'ladi.
- `ImportService`/`LeadImportService` `ZoneId.systemDefault()` bilan — bir xil muammo.

#### TODO/FIXME
- `service/` papkasida `TODO|FIXME|XXX|HACK` — **topilmadi**. O'lik/deprecated kod: `StudentPaymentLifecycleService.checkOverduePayments` (`:83-87`), `BalanceTransactionService.recordStudentOnly` (`:102-115`), `PaymentScheduleConfig.SETTING_EXPECTED_PAYMENTS_UNTIL` (`config/PaymentScheduleConfig.java:13`), `StudentPaymentPlan` entity, `PaymentService.getDebtorsLegacy` (`:630-643`).

#### Takrorlangan hisob-kitob
- Davr oylari 3 xil: `PaymentService.resolvePaymentPeriod` (gross, `student.monthlyFee`, `:403-410`) / `PaymentScheduleService.fillPaymentPeriod` (**payable**, sg fee, min 1, `:1010-1023`) / `BalanceExpectationService.compute` (gross, sg fee, `:241-242`).
- Billable statuslar ro'yxati 4 joyda: `service/PaymentScheduleService.java:47`, `service/BalanceExpectationService.java:58`, `service/AttendanceService.java:369`, `service/StudentService.java:1057`.
- PER_LESSON "used" boshlanish sanasi: `joinDate` (`service/PaymentScheduleService.java:343`) vs `paymentStartDate` (`service/BalanceExpectationService.java:310`, `service/StudentService.java:1064`).
- Dars narxi 2 xil maxraj: `resolveLessonPrice` vs `resolveFreezeLessonPrice` (`service/PaymentScheduleService.java:406-461`).
- Oylik narx aniqlash 4+ joyda: `PaymentScheduleService.resolveMonthlyFee` (`:602`), `ContractService.resolveMonthlyFee` (`:241`), `PaymentService.calculateStudentDebt` (`:748-752`), transfer/unfreeze ichida inline (`service/StudentService.java:399-400,1153-1154`).
- Qarzdor ta'riflari — yuqoridagi 9-band.

#### Xavfsizlik (qo'shimcha)
- `src/main/resources/application.yml:14` da izohga olingan sir qatori bor (qiymati bu hujjatga ko'chirilmadi) — repodan olib tashlash tavsiya etiladi.

### 9.13 Lid / vazifa / Meta / WebSocket / fayllar


#### Xavfsizlik
1. **Stored XSS fayl yuklash orqali.** `controller/FileController.java:34-38` kengaytmani asl nomdan filtrsiz oladi, `FileStorageService.validateImage` (`service/FileStorageService.java:40-43`) faqat klient yuborgan `Content-Type` ni tekshiradi. `evil.html` + `Content-Type: image/png` → `UUID.html` saqlanadi, `GET /api/files/UUID.html` (ochiq, `SecurityConfig.java:85`) uni `text/html` va `inline` bilan beradi (`FileController.java:63-69`). Chatda ham xuddi shunday: `service/ChatAttachmentService.java:118,258-266` (kengaytma ≤10 belgi, masalan `.html`, `.svg`) va `image/*` ichida `image/svg+xml` ruxsat (`:143-145`). API domenida JS bajariladi.
2. **STOMP SEND kadrlari tekshirilmaydi.** `config/ChatChannelInterceptor.java:40-57` faqat CONNECT va SUBSCRIBE ni ko'radi. SimpleBroker `/topic` va `/queue` ga klientdan to'g'ridan-to'g'ri kelgan SEND ni ham tarqatadi, ya'ni har qanday ulangan xodim `/topic/conversation.{istalgan id}` ga soxta `MESSAGE`/`DELETED` hodisa (istalgan `senderId` bilan) yoki `/topic/presence` ga soxta holat yubora oladi. **TAXMIN** (Spring standart xulqi bo'yicha): `/user/{boshqa_username}/queue/errors` ga ham yuborish mumkin. Yechim: `/app` dan boshqa prefiksli SEND ni rad etish.
3. **WS sessiyasi token muddatidan uzoq yashaydi.** JWT faqat handshake da tekshiriladi (`security/jwt/JwtHandshakeInterceptor.java:49-78`); nofaol qilingan yoki muddati o'tgan tokenli foydalanuvchi uzilguncha xabar oladi/yuboradi. Token `?token=` query da — proxy/access loglarga tushadi.
4. **`GET /api/files/**` ochiq** (`config/SecurityConfig.java:85`): shaxsiy chat hujjatlari va ovozli xabarlar autentifikatsiyasiz yuklab olinadi (faqat UUID taxmin qilinmasligi himoya).
5. **Chat biriktirmasi begona faylga ishora qila oladi.** `requireOwnUrl` (`service/ChatAttachmentService.java:358-374`) faqat fayl mavjudligini tekshiradi — boshqa foydalanuvchi yuklagan istalgan fayl (masalan o'quvchi rasmi) xabarga biriktiriladi; `contentType`/xabar turi klientdan (`:316-318`) — izoh (`:283-285`) "mijoz aytishi mumkin emas" deydi, kod aksini qiladi.
6. **Rate limit umuman yo'q** (§6.6): login brute-force, `POST /api/leads/public` spam (captcha ham yo'q), webhook flood cheklanmaydi.
7. **Ochiq lid formasi (`/api/leads/public`) cheklanmagan kiritish**: `dto/request/LeadRequest.java` da uzunlik/format validatsiyasi yo'q (phone uchun `@Pattern` ham yo'q); `format` (`entity/Lead.java:51` length 20), `source` (`:65` length 30), `course` (100) dan uzun qiymat → bazada xato (**TAXMIN**: 500 yoki umumiy xato javobi). Javobda to'liq `LeadResponse` anonimga qaytadi (`controller/LeadController.java:63-67`).
8. **Audit IP soxtalashtirilishi mumkin**: `audit/AuditAspect.java:226-246` `X-Forwarded-For` ning BIRINCHI qiymatini oladi — bu klient boshqaradigan qism (nginx `$proxy_add_x_forwarded_for` qo'shsa ham).
9. **Meta webhook `page_id` tekshirilmaydi** (`controller/MetaWebhookController.java:134-167`) va `leadgen_id` raqamligi tekshirilmaydi; u Graph URL yo'liga kodlanmasdan qo'yiladi (`service/MetaGraphClient.java:166`, `:265-274` faqat query param kodlanadi). `verify-signature=false` bo'lsa endpoint butunlay himoyasiz (`:191-199`).
10. **`application.yml` da sirlar ochiq holda repoda**: DB paroli, `jwt.secret`, `meta.system-user-token`, `meta.app-secret`, `meta.verify-token`, `telegram.bot-token` (qiymatlar bu hujjatga ko'chirilmadi). Rotatsiya va env/secret store ga ko'chirish kerak.
11. **Telegram HTML injection**: `service/TelegramService.java:41` `parse_mode=HTML`, `buildAttendanceMessage`/`buildPaymentMessage` (`:69-96`) ism va guruh nomini escape qilmaydi — `<`/`&` bor nom xabarni buzadi (Telegram 400 qaytaradi, xabar yetmaydi).

#### Biznes mantiq / ma'lumot yaxlitligi
12. **Lid telefon dublikati tekshirilmaydi** — `/public` va `POST /api/leads` (`service/LeadService.java:138-140`, ataylab). Importda default `skipDuplicates=false` (`dto/request/LeadImportExecuteRequest.java:30`) — dublikatlar yaratiladi.
13. **Xodim lidni to'g'ridan-to'g'ri konvert bosqichida yarata oladi**: `createLeadByStaff` (`service/LeadService.java:165-167`) `isConverted` ni tekshirmaydi (updateStatus `:347-349` va import `service/LeadImportService.java:654-667` da tekshiriladi). Natija: `student_id` siz "o'quvchi" ustunidagi yetim lid, keyin `convertToStudent` uni rad etadi (`:614-618`).
14. **Konvert bosqichidan chiqarilgan lid** `converted=true` va `student` ni saqlab qoladi (`LeadService.java:340-343` chiqishga ruxsat, flag qaytarilmaydi) → `getStats` dagi "status vs flag" nomuvofiqligi (`:810-813`) va qayta konvertatsiya imkonsiz.
15. **`assignLead` nofaol foydalanuvchiga biriktiradi** (`service/LeadService.java:304-313` — `isActive` tekshiruvi yo'q; `createLeadByStaff` `:199-201` da bor). Lid importi operatorlari ham (`service/LeadImportService.java:626-640`).
16. **Lid importi `execute` qayta chaqirilishi mumkin**: `pending` dan yozuv o'chirilmaydi (`service/LeadImportService.java:190-269`), 1 soat ichida bir xil `importId` bilan ikkinchi chaqiruv (`skipDuplicates=false`) barcha lidlarni ikki marta yaratadi.
17. **`deleteBatch` yarim o'chirishi mumkin**: `@Transactional` yo'q (`service/LeadImportService.java:364-378`); avval har lidning `LeadNote` lari alohida tranzaksiyalarda o'chiriladi, keyin `leadRepository.deleteAll` — partiyadagi biror lidda `LeadComment`/`LeadStatusHistory`/`Task` bo'lsa (FK, `entity/LeadComment.java:21-23`, `entity/LeadStatusHistory.java:35-37`, `entity/Task.java:81-83`) lid o'chirish yiqiladi, lekin izohlar allaqachon yo'qolgan.
18. **Konvertatsiyada telefon dublikati aniq satr bo'yicha** (`service/LeadService.java:622`): talabalar importi telefonni `+998` siz saqlaydi (`service/ImportService.java:995-1004`), lid esa `+998...` — bir odam ikki talaba bo'lib qolishi mumkin. Telefoni `"—"` bo'lgan ikkinchi lid konvert qilinmaydi (birinchisi `"—"` telefonli talaba yaratadi).
19. **Talaba/o'qituvchi importi PhoneUtils ni ishlatmaydi** (`service/ImportService.java:995-1018`) — bazada ikki xil telefon formati; `phoneDigits` 9..13 raqamni qabul qiladi (masalan 11 xonali noto'g'ri raqam ham "yaroqli").
20. **O'qituvchi importi**: butun fayl bitta tranzaksiya (`service/ImportService.java:642`), fayl ichidagi dublikat telefon tekshirilmaydi, `teacherCode = count+n` (`:675-676`) mavjud kodlar bilan to'qnashishi mumkin; audit yo'q.
21. **Status tarixi to'liq emas**: yaratilishda (public/staff/import/Meta) `LeadStatusHistory` yozilmaydi; birinchi o'tishning `daysInPreviousStatus` null (`service/LeadService.java:236-240`).
22. **`MetaLeadForm.active` e'tiborsiz**: faqat saqlanadi (`service/MetaAdminService.java:146-148`), `MetaLeadIngestService.ingest` (`service/MetaLeadIngestService.java:115-209`) uni o'qimaydi — "nofaol" forma ham lid yaratadi. Yangi forma `active=false` bo'lib tushishi (`service/MetaFormSyncService.java:83`) amalda hech narsani to'xtatmaydi.
23. **Meta avtomatik vazifa muddati doim BUGUN 23:59** (`service/MetaLeadIngestService.java:483`) — backfill da eski lidlar uchun ham bugungi (va tezda OVERDUE) vazifalar yaratiladi; `createdBy=null`.
24. **Meta retry backoff yo'q**: har 15 s da qayta urinish, 5 urinish ~1 daqiqada tugaydi (`service/MetaLeadProcessingService.java:44`, `service/MetaEventWorker.java:273-281`) — Graph vaqtincha ishlamasa eventlar tez FAILED bo'ladi. `STATUS_PROCESSING` (`entity/MetaWebhookEvent.java:36`) ishlatilmaydi.
25. **Meta backfill sinxron** (`controller/MetaAdminController.java:89-100`) — yuzlab lid uchun HTTP so'rov uzoq davom etadi (proxy timeout **TAXMIN**).
26. **Vazifalar CANCELLED ga o'tmaydi**: `TaskStatus.CANCELLED` (`entity/enums/TaskStatus.java:69`) hech qayerda o'rnatilmaydi; lid konvert/rad qilinganda ochiq vazifalar ochiq qoladi va `overdue` statistikasini buzadi.
27. **Vazifa ruxsatlaridagi nomuvofiqliklar** (`service/TaskService.java`):
    - Javadoc (`:53-55`) "SALES_MANAGER o'zi yaratgan vazifani ham ko'radi" — `assertCanAccessTask` (`:526-536`) faqat mas'ulni tekshiradi.
    - `delete` (`:304-321`) muallifga mas'ul bo'lmasa ham o'chirishga ruxsat beradi, `assertCanAccessTask` chaqirmaydi.
    - `reassign` (`:264-280`) SALES_MANAGER ga o'z vazifasini istalgan operatorga o'tkazishga ruxsat beradi (`create` dagi "faqat o'zingizga" qoidasi `:542-544` bu yerda yo'q).
    - Talabaga bog'langan vazifa yaratishda doira tekshiruvi yo'q (`:100-103`).
28. **Vazifa bildirishnomalari yo'q** — muddat yaqinlashgani/o'tgani, yangi biriktirilgan vazifa yoki yangi lid haqida hech qanday push/WS/Telegram xabari yo'q.
29. **AuditAspect ichma-ich `@Audited` da kontekstni tozalaydi**: `audit/AuditAspect.java:72` va `:102` — ichki `@Audited` metod (masalan `convertToStudent` → `GroupService.addStudentToGroup`) tashqi metod shu paytgacha yozgan `AuditContext.change/label/skip` ni o'chirib yuboradi. Hozir `convertToStudent` ichkaridan oldin kontekst yozmaydi, lekin naqsh xavfli.
30. **Audit bo'shliqlari**: LeadNote CRUD, Meta sozlamalari/retry/backfill, chat, fayl yuklash, `/api/auth/change-password`, o'qituvchi importi audit qilinmaydi; `AuditAction.EXPORT` (`audit/AuditAction.java:11`) mavjud, lekin `exportLeadsXlsx` (`service/LeadService.java:1089`) audit qilinmaydi. `@Audited` bitta joyda controllerda (`controller/UserController.java:178`) — servis naqshidan chetga chiqish.
31. **`FileStorageService.save` hajmni tekshirmaydi** (`service/FileStorageService.java:65-78`), vaholanki `ChatAttachmentService` izohi (`:88-90`) "FileStorageService dagi tekshiruv ikkinchi to'siq" deydi — chat uchun yagona to'siq multipart 4MB.
32. **`app.base-url` ishlatilmaydi** (`service/FileStorageService.java:31,89-91`) — URL doim nisbiy; frontend/boshqa klientlar host ni o'zi qo'shishi kerak.
33. **Presence hamma xodimga tarqaladi** (`service/ChatPresenceService.java:131`, `/topic/presence` obunasi tekshirilmaydi) — REST `GET /api/chat/presence` esa ataylab faqat suhbatdoshlarni beradi; nomuvofiq.
34. **Yangi suhbat haqida real-time xabar yo'q**: `POST /api/chat/conversations/direct|group` (`controller/ChatController.java:123-138`) WS hodisa yubormaydi — qabul qiluvchi birinchi xabarni sahifani yangilamaguncha/obuna bo'lmaguncha ko'rmaydi (**TAXMIN**: frontend polling qilmasa).
35. **Telegram chaqiruvi tranzaksiya ichida va timeoutsiz**: `AttendanceService.markAttendance` (`@Transactional` `service/AttendanceService.java:52`) ichida har ota-onaga sinxron HTTP (`:138-161`), `RestTemplate` timeout sozlanmagan (`service/TelegramService.java:25`) — Telegram sekin bo'lsa davomat saqlash osilib qoladi.

#### TODO/FIXME (butun `src/main/java`)
`grep -rnE "TODO|FIXME|XXX|HACK"` natijasi — haqiqiy TODO/FIXME **yo'q**. Faqat ikki soxta moslik (telefon formati namunasidagi `XXX`):
- `util/PhoneUtils.java:4` — `* Telefon raqamini {@code +998XXXXXXXXX} kanonik shakliga keltiradi.`
- `util/PhoneUtils.java:66` — `* Dublikat kaliti — kanonik shaklning raqamlari ({@code 998XXXXXXXXX}).`

# Adizone CRM — 5-bosqich: qolgan modullar auditi

> **Maqsad:** yangi admin panel (`adizone-admin`, Vue 3 + TS) 5-bosqichi uchun qolgan 10 modulning backend shartnomasi, xavflari va ish tartibi.
> **Holat:** 2026-10-02, branch `billing-v2` @ `1dce479` + ishchi nusxadagi commit qilinmagan o'zgarishlar.
> **Metodika:** faqat o'qildi — kod o'zgartirilmadi, build/test/dev ishga tushirilmadi.
> - Lokal `adizone` bazasi (PostgreSQL 18.6, TZ `Asia/Tashkent`) faqat o'qish rejimida ishlatildi (`default_transaction_read_only=on`).
> - Kod va bazadagi har bir topilma `fayl:qator` bilan berilgan.
> - Asosiy P0'lar kod bilan qayta tekshirildi: U-01, U-02, E-01, C-01, CH-01.
> **Yo'llar:**
> - Java fayllari `src/main/java/com/crm/` ga nisbatan.
> - Migratsiyalar: `src/main/resources/db/migration/` (qisqacha `db/migration/` yoki `V…`).
> - Eski frontend: `front:` yoki `FE:` = `adizone-crm-front/src/`.
> - Yangi admin: `admin:` yoki `ADM:` = `adizone-admin/` (ba'zan `src/`).
> - `SC` = `config/SecurityConfig.java`.
> **TAXMIN:** koddan yoki bazadan to'g'ridan-to'g'ri tasdiqlanmagan da'vo.
> **Sirlar:** parol, token va kalit qiymatlari hujjatga ko'chirilmagan.
> **Bog'liq hujjatlar:** [api-inventory.md](api-inventory.md), [backend-audit.md](backend-audit.md), [../design/billing-v2.md](../design/billing-v2.md), [../design/payroll-v2.md](../design/payroll-v2.md), [../design/director-dashboard.md](../design/director-dashboard.md).

**Rollar:** SA = SUPER_ADMIN, A = ADMIN, ACC = ACCOUNTANT, SM = SALES_MANAGER, T = TEACHER, ST = STUDENT, P = PARENT, AUTH = har qanday autentifikatsiyalangan.

**Effektiv rol** = SecurityConfig URL qoidasi ∩ `@PreAuthorize` ∩ servisdagi egalik tekshiruvi. URL qoidalarida birinchi mos kelgani ishlaydi.

**Envelope'lar:**
- `ApiResponse<T>` = `{success, message, data, meta?}` (`dto/response/ApiResponse.java:5-14`);
- `PageResponse` = `{content, pageNumber, pageSize, totalElements, totalPages, last}`;
- sahifalash `page` 0 dan boshlanadi.

**Ustuvorlik:**
- **P0** — ishlamaydi yoki xavfli: xavfsizlik teshigi, doimiy 500, ma'lumot buzilishi;
- **P1** — admin panel uchun kerak;
- **P2** — keyin.

## Mundarija
- §0 Qisqacha: P0 ro'yxati, kesishuvchi topilmalar (X-*), lokal baza holati
- §1 O'qituvchilar (T-*)
- §2 Foydalanuvchilar va rollar (U-*)
- §3 Ta'tillar (L-*)
- §4 Imtihonlar (E-*)
- §5 Uy vazifalari (H-*)
- §6 E'lonlar (N-*)
- §7 Shartnomalar (C-*)
- §8 Audit jurnali (A-*)
- §9 Ichki chat (CH-*)
- §10 Sozlamalar va bildirishnomalar (S-*)
- §11 Umumiy xulosa jadvali
- §12 Tavsiya etilgan ish tartibi
- §13 Buyurtmachi bilan aniqlash kerak bo'lgan savollar (+ egasi qarorlari)
- §14 Bajarildi: xavfsizlik bloki (2026-10-02)

Har bir modul bir xil tartibda yozilgan:
- N.1 Endpointlar;
- N.2 Ma'lumot modeli;
- N.3 Xatolar va xavflar;
- N.4 billing-v2 / payroll-v2 / dashboard bilan bog'liqlik;
- N.5 Eski frontend;
- N.6 Yetishmayotgan narsalar;
- N.7 DB tekshiruvi (SQL va lokal natija);
- N.8 Xulosa.

---

## §0. Qisqacha

### 0.0 P0 ro'yxati (8 ta)

| ID | Modul | Muammo | Dalil | Tuzatish hajmi |
|---|---|---|---|---|
| **CH-01** | Chat | `SUBSCRIBE /topic/**` (yoki `/topic/*`) bilan har qanday ulangan foydalanuvchi **barcha suhbatlar**ning xabarlarini real vaqtda oladi. Interceptor faqat `/topic/conversation.` prefiksini tekshiradi, SimpleBroker esa obunada Ant-naqshlarni qabul qiladi | `config/ChatChannelInterceptor.java:90-96`, `config/WebSocketConfig.java:52` | Kichik: SUBSCRIBE uchun oq ro'yxat va wildcard'larni rad etish |
| **CH-02** | Chat | `/api/chat/upload` da `ImageIO.read` rasmni to'liq dekodlaydi. "Decompression bomb" PNG JVM'ni OOM qiladi; har qanday AUTH foydalanuvchi bunga qodir | `service/ChatAttachmentService.java:209-217` | Kichik: o'lchamni dekodlamasdan `ImageReader` orqali o'qish, piksel chegarasi |
| **U-01** | Users / Teachers | `POST /api/users/create-for-teacher/{id}` da username band bo'lsa, o'qituvchi **istalgan mavjud userga** bog'lanadi: rol, egalik va bandlik tekshirilmaydi. Natija — 500 yoki profilni begona hisobga (SA/ACC/ST) ulash | `service/UserService.java:121-126` | Kichik: band bo'lsa 409, bog'lashni alohida va tekshiruv bilan qilish |
| **U-02** | Users | STUDENT/PARENT hisoblarini admin yaratadi, lekin portal yo'q. Ular "authenticated" endpointlarga kiradi: `GET /api/search` barcha o'quvchilarning ism va telefonini beradi, chat, `POST /api/files`, `GET /api/notices` ham ochiq. Lokalda ST/P hisoblari 0 ta | `controller/SearchController.java:33-34`, `SC:246` | Kichik: ST/P uchun `denyAll` (faqat `/api/auth/**`), search'ni xodim rollari bilan cheklash |
| **E-01** | Imtihonlar | `"PAID".equals(sg.getPaymentStatus())` — billing-v2 dan beri bu maydon enum, shart doim `false`. `register-student` doim 400, `eligible-students` doim `[]` | `service/ExamService.java:289`, `entity/StudentGroup.java:118` | 1 qator + "to'lagan" holatlarini billing-v2 bilan kelishish |
| **E-02** | Imtihonlar | TEACHER uchun IDOR: `calculate-payment` (istalgan o'quvchining to'lov ma'lumoti — hozir ishlaydi), `register-student`, `eligible-students` (`findAll()` → barcha o'quvchilarning PII'si, E-01 tuzatilishi bilan ochiladi) | `service/ExamService.java:220-324` | Kichik: `assertExamAccess` + `assertOwnsStudent` |
| **E-03** | Imtihonlar | Eski UI natijani tahrirlaganda `changeReason` yuboradi, backend esa `editNote` talab qiladi, shuning uchun doim 400. Yangi admin `editNote` yuborsa muammo yo'q | `front:…/exam-results.vue:417-422`, `ExamService.java:187-190` | 1 qator `@JsonAlias("changeReason")` |
| **C-01** | Shartnomalar | Raqam `count()+1`. Bitta shartnoma o'chirilgach, har keyingi `generate` UNIQUE buzilishi bilan **doimiy 500** qaytaradi; parallel chaqiruvda ham to'qnashuv bor | `service/ContractService.java:167-170`, `entity/Contract.java:28` | Kichik/o'rta: sequence va raqam formati |

### 0.1 Kesishuvchi topilmalar (X-*)

| ID | P | Topilma | Dalil | Oqibat | Tavsiya |
|---|---|---|---|---|---|
| X-01 | P1 | **Qo'lda migratsiyalar kuzatilmaydi.** Ikki holat bor:<br>- Flyway `pom.xml` da yo'q, README esa "Flyway" deydi. Sxemani `ddl-auto: update` boshqaradi, V25–V57 skriptlari qo'lda bajariladi.<br>- `V31` va `V35` versiyalari ikki fayldan iborat.<br><br>Lokal bazada `flyway_schema_history` 24-versiyada to'xtagan. Qo'lda skriptlar qisman qo'llangan, quyidagilar **yo'q**:<br>- V41 indekslari;<br>- V50 (`ux_teachers_user_id`, `fk_teachers_user`, `idx_teachers_phone`);<br>- V27 `exam_registrations` UNIQUE;<br>- V29 `idx_exams_group_id`;<br>- V52 `uk_payments_idempotency_key`;<br>- V56 `fk_payroll_teacher`.<br><br>V42, V45, V47, V53, V54, V55 va V52 (`uk_billing_periods_sg_start`) esa bor. | `application.yml:32`, `billing/BillingSchemaGuard.java:19`, §0.2 | Qaysi bazada qaysi cheklov borligi noma'lum. Masalan, to'lov idempotentligi UNIQUE'siz faqat kod darajasida qoladi (TAXMIN: prod holati tekshirilmagan) | Prod'da read-only "sxema farqi" tekshiruvi (§0.2 so'rovi). Keyin Flyway `baselineVersion` yoki bitta yig'ma idempotent skript. README'ni tuzatish |
| X-02 | P1 | **`DataIntegrityViolationException` va `MissingServletRequestParameterException` uchun handler yo'q.** Ular umumiy handler'ga tushib 500 qaytaradi. Bu C-01, E-18, H-10, N-07, U-06, U-07, T-12 va boshqalarning ildizi | `exception/GlobalExceptionHandler.java:209-219` (faqat `MissingServletRequestPartException` bor, `:129`) | Har qanday unique/length buzilishi yoki yetishmagan query-param foydalanuvchiga "kutilmagan xato" bo'lib ko'rinadi | Global handler'lar: unique buzilishi → 409 (`error.conflict` + constraint nomi xaritasi), not-null/length → 400, missing param → 400 |
| X-03 | P2 | **Dublikat FK'lar qarama-qarshi `ON DELETE` bilan.** Bir xil ustunda eski skript FK'si (CASCADE/SET NULL) va Hibernate FK'si (NO ACTION) bor, amalda NO ACTION ustun keladi. Bunday ustunlar:<br>- `leave_requests.requester_id` / `approved_by`;<br>- `exam_results.student_id` / `exam_id`;<br>- `notices.created_by`;<br>- `teachers.user_id`;<br>- `payroll.created_by`. | lokal `pg_constraint` (§0.2) | Migratsiyalarda yozilgan CASCADE/SET NULL ishlamaydi, jismoniy o'chirish FK xatosi bilan bloklanadi. Hozir ko'p joyda soft-delete bo'lgani uchun zarar kichik | Bitta skript bilan Hibernate dublikatlarini olib tashlab, kerakli semantikani qoldirish |
| X-04 | P2 | **Sxemada birorta CHECK yo'q (0 ta).** `EnumCheckConstraintCleaner` (`app.schema.drop-enum-checks: true`) butun sxemadagi `= ANY(ARRAY…)` CHECK'larni o'chiradi. Ko'p statuslar esa String:<br>- `leave_requests.status` / `leave_type`;<br>- `exam_*.status`;<br>- `homework_submissions.status`;<br>- `notices.published_to`. | `config/EnumCheckConstraintCleaner.java:36-72`, lokal `pg_constraint` | Status yaxlitligi faqat Java'ga tayanadi, String statuslar umuman tekshirilmaydi (L-01, H-06, N-05) | Status maydonlarini enum'ga o'tkazish. Cleaner'ni faqat Hibernate nomlash naqshi bilan cheklash. Muhim invariantlar uchun nomlangan CHECK |
| X-05 | P2 | **Dublikat UNIQUE va indekslar** (Hibernate `uk_*` va eski skript `*_key`):<br>- `users` (username, email, uuid);<br>- `exams`, `homeworks`, `leave_requests`, `notices`, `teachers` (uuid);<br>- `exam_results` va `homework_submissions` (juftlik);<br>- `refresh_tokens.token`;<br>- `audit_logs` da 3 ta dublikat indeks. | lokal `pg_constraint` / `pg_indexes` | Yozish sekinlashadi, joy ko'proq ketadi | X-03 bilan bitta tozalash skripti |
| X-06 | P2 | **O'lik legacy jadvallar** — entity va kod yo'q:<br>- `settings` (4 qator);<br>- `notifications` (0);<br>- `teacher_salaries` (0);<br>- `student_payment_plans` (0). | lokal baza; Java grep | Chalkashlik. S-03/S-04 loyihasida nom to'qnashuvi | Yangi jadvallarni loyihalashda qaror qilish: qayta ishlatish yoki `DROP` (zaxira bilan) |
| X-07 | — | `GET /api/files/**` permitAll — uy vazifasi, chat va pasport skanlari uchun ham. Hisobga kirmaydi: H-12 (P2) va CH-04 (P1) da sanalgan | `SC:86` | — | CH-04 ga qarang |
| X-08 | P2 | **Eskirgan hujjatlar:**<br>- `README.md:15` "Flyway";<br>- `backend-audit.md` §7 va §9.13 (chat SEND/upload haqidagi da'volar endi noto'g'ri);<br>- `backend-audit.md:351` `paymentStatus` String deydi;<br>- `adizone-admin` `api.ts:6` va `matrix.ts:323,676` `GET /api/teachers — AUTH` deydi (aslida SA,A,ACC). | — | Frontend noto'g'ri shartnomaga tayanadi | Shu audit bilan birga yangilash |

**Kesishuvchi sanoq:** P1 — 2 ta (X-01, X-02), P2 — 5 ta (X-03…X-06, X-08).

### 0.2 Lokal baza holati (read-only, 2026-10-02)

**Umumiy holat:**
- 66 jadval bor. `flyway_schema_history` maksimal versiyasi 24.
- Barcha vaqt ustunlari `timestamp without time zone`. JVM va JDBC `Asia/Tashkent`.

**Modullardagi ma'lumot hajmi:**

| Jadval | Qator |
|---|---|
| `users` | 6 (SA 1, ADMIN 1, TEACHER 4; ST/P 0) |
| `teachers` | 4 (hammasi ACTIVE, userga bog'langan) |
| `audit_logs` | 61 |
| `settings` (legacy) | 4 |
| `refresh_tokens` | 1 |
| `salary_rules` | 3 (umumiy TEACHER qoidalari, 1 tasi faol) |
| `leave_requests`, `exams`, `exam_registrations`, `exam_results`, `homeworks`, `homework_submissions`, `notices`, `notice_reads`, `contracts`, `contract_templates`, `conversations`, `conversation_participants`, `messages`, `message_attachments`, `holidays`, `notifications` | **0** |

**Bundan kelib chiqadigan xulosa:** bu modullar lokalda deyarli **sinalmagan**. Ma'lumot yaxlitligi so'rovlari (har modulning N.7 bo'limi) 0 qaytaradi va **prod nusxasida qayta ishga tushirilishi kerak**. `../adizone_crm_now.dump` fayli bu auditda ishlatilmadi.

**Qo'shimcha kuzatuv:** yagona ACTIVE guruh (`groups.id=1`) o'qituvchisiz (`teacher_id IS NULL`).

Qo'lda migratsiyalar holatini (X-01) prod'da tekshirish uchun so'rov:
```sql
WITH want(mig, name) AS (VALUES
  ('V27','exam_registrations_exam_id_student_id_key'), ('V27','idx_exam_registrations_exam'), ('V29','idx_exams_group_id'),
  ('V41','idx_notice_reads_notice'), ('V41','idx_notice_reads_user'), ('V42','idx_audit_action'),
  ('V45','idx_messages_conversation_created'), ('V47','idx_message_attachments_message'),
  ('V50','ux_teachers_user_id'), ('V50','idx_teachers_phone'), ('V50','fk_teachers_user'),
  ('V52','uk_billing_periods_sg_start'), ('V52','uk_payments_idempotency_key'),
  ('V53','idx_attendance_group_date'), ('V54','uk_payroll_user_month_year_active'),
  ('V55','idx_payroll_salary_rule'), ('V56','uk_payroll_teacher_month_year_active'), ('V56','fk_payroll_teacher'))
SELECT mig, name,
       EXISTS (SELECT 1 FROM pg_class WHERE relname = name)
    OR EXISTS (SELECT 1 FROM pg_constraint WHERE conname = name) AS present
FROM want ORDER BY 1, 2;
-- Lokal natija: V27 (ikkalasi — exam_registrations da faqat PK), V29, V41 (ikkalasi), V50 (uchalasi),
-- V52 uk_payments_idempotency_key, V56 fk_payroll_teacher — YO'Q; qolganlari BOR.
```

---

## 1. O'qituvchilar

### 1.1 Endpointlar

| Metod | Yo'l | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| GET | `/api/teachers` | SA,A,ACC (`SecurityConfig.java:220-221` ∩ `controller/TeacherController.java:44`) | query `activeOnly` (bool, default `true`) | `ApiResponse<List<TeacherResponse>>` | Sahifalash/qidiruv/status filtri **yo'q**; `activeOnly=true` → ACTIVE+ON_LEAVE. N+1 (T-06) |
| GET | `/api/teachers/{id:\d+}` | SA,A,ACC (`:79-83`) | — | `ApiResponse<TeacherResponse>` | ACC pasport, manzil, maoshni ham ko'radi |
| GET | `/api/teachers/search` | SA,A,ACC (`:183-190`) | `q` (majburiy), `page`=0, `size`=20 | `ApiResponse<PageResponse<TeacherResponse>>` {content,pageNumber,pageSize,totalElements,totalPages,last} | Faqat `is_active=true`; ism/familiya/telefon/kod bo'yicha LIKE |
| GET | `/api/teachers/stats` | SA,A (Sec SA,A,ACC ∩ Pre SA,A, `:192-196`) | — | `ApiResponse<Map>` {total, active, byStatus{STATUS:count}} | |
| GET | `/api/teachers/export` | SA,A (`:253-261`) | — | yalang'och `text/csv` (ID,UUID,First Name,Last Name,Phone,Email,Subject,Status,Hire Date,Created At) | CSV-injection (T-11) |
| GET | `/api/teachers/kpi/ranking` | SA,A (Sec `:217-219`, Pre `:66`) | `period` (monthly\|daily, default monthly), `from`,`to` (ISO date; default oy boshi..bugun) | `ApiResponse<TeacherKpiRankingResponse>` {period,from,to,teachers[{rank,teacherId,teacherName,photoUrl,groupCount,studentCount,attendanceRate,paymentRate,onTimePaymentRate,retentionRate,overallScore,insufficientData}]} | Faqat `is_active=true` (`service/TeacherKpiService.java:95`) |
| GET | `/api/teachers/{id}/kpi` | SA,A (`:86-98`) | `period`,`from`,`to` | **yalang'och** `TeacherKpiDto` {teacherId,teacherName,period,balance,bonus,advance(=0),penalty,studentProgress(=0),groups[{groupId,groupName,studentCount,roomName,capacity,freeSeats}],conversion{newStudents,paidStudents,conversionRate},teacherAttendance{plannedLessons,conductedLessons,missedLessons,penaltyAmount(=0)},studentAttendance{presentRate,absentUnexcusedRate,absentExcusedRate},satisfaction{},**current**{attendanceRate,paymentRate,onTimePaymentRate,retentionRate,overallScore,insufficientData},trend[{label,overallScore,insufficientData}]} | ApiResponse'siz (T-05) |
| GET | `/api/teachers/{id}/kpi/trend` | SA,A (`:101-113`) | `months` (1..12, default 12), `period`, `from`,`to` | `ApiResponse<List<TeacherKpiTrendPointDto>>` {label,overallScore,insufficientData} | daily rejimda ≤31 kun |
| GET | `/api/teachers/{id}/kpi/daily` | SA,A (`:132-144`) | `year`+`month` yoki `from`+`to` (≤31 kun) | `ApiResponse<List<TeacherKpiTrendPointDto>>` | |
| GET | `/api/teachers/me/kpi`, `/me/kpi/trend`, `/me/kpi/daily` | T (Sec `:210-211`, Pre `:51,117,148`) | yuqoridagidek | `/me/kpi` — yalang'och `TeacherKpiDto`; qolganlari ApiResponse | Profil yo'q → 404 (`service/TeacherService.java:455-459`) |
| POST | `/api/teachers` | SA,A (`:162-167`) | `TeacherRequest`: `firstName`*,`lastName`*,`phone`* (@NotBlank; `PhoneDeserializer`), `email`, `subjectSpecialization`, `monthlySalary`, `hireDate`, `notes`, `userId`, `gender`, `dateOfBirth`, `fatherName`, `motherName`, `address`, `permanentAddress`, `passportInfo`, `qualification`, `workExperience`, `joiningDate`, `status` (ACTIVE\|INACTIVE\|ON_LEAVE, boshqasi→400), `basicSalary`, `medicalLeaves`, `casualLeaves`, `maternityLeaves`, `sickLeaves`, `photoUrl`, `groupIds[]` | 201 `ApiResponse<TeacherResponse>` | `@Size`/`@Email` yo'q (T-12) |
| PUT | `/api/teachers/{id}` | SA,A (`:169-174`) | `TeacherRequest` — **to'liq almashtirish** | `ApiResponse<TeacherResponse>` | `StaffStatusService.updateTeacher` → status userga uzatiladi (T-01, T-02) |
| DELETE | `/api/teachers/{id}` | SA,A (`:176-181`; Sec `:222-223` DELETE catch-all'dan oldin) | — | `ApiResponse<Void>` | Soft: status=INACTIVE + user bloklanadi (`service/StaffStatusService.java:52-55,66-84`) |
| POST | `/api/teachers/{id:\d+}/photo` | SA,A (`:198-214`) | multipart `file` | `ApiResponse<TeacherResponse>` | |
| POST | `/api/teachers/import` | SA,A (`:216-226`) | multipart `file` | `ApiResponse<ImportResult>` | Kod formati `TCH-%05d` (`service/ImportService.java:676`) |
| POST | `/api/teachers/{userId}/ensure-profile` | SA (`:233-239`) | path = **User** id | `ApiResponse<Map>` {linkedCount,createdCount,skippedCount,items[{type,teacherId,userId,username,action: CREATED\|LINKED\|EXISTS\|SKIPPED,reason}]} | SKIPPED ham 200 (T-07) |
| POST | `/api/teachers/sync-from-users` | SA (`:245-251`) | — | `ApiResponse<Map>` {total,created,linked,updated,errors[]} | Har user o'z tx'ida (`service/TeacherProfileTxRunner.java:27`) |
| POST | `/api/admin/repair/link-teacher-users` | SA (`SecurityConfig.java:171-172`, `controller/AdminRepairController.java:23,30`) | — | `ApiResponse<Map>` {linkedCount,createdCount,skippedCount,remainingUnlinked,usersWithoutProfile,items[]} | `service/TeacherProfileSyncService.java:93-134` |
| GET | `/api/teacher/dashboard` | T (Sec `:212-213`, `controller/TeacherDashboardController.java:23-24`) | — | `ApiResponse<Map>` {teacherId,teacherName,totalGroups,groups[{id,groupName,courseName,studentCount,scheduleDays[{dayOfWeek,startTime,endTime}]}],todayLessons[{groupId,groupName,startTime,endTime,roomNumber,studentCount}],totalStudents,salary?,salaryStatus?} | Faqat ACTIVE guruhlar; N+1 (har guruhga 3 so'rov) |

`TeacherResponse` (`dto/response/TeacherResponse.java:15-60`): id, userId, uuid, firstName, lastName, phone, email, subjectSpecialization, monthlySalary, hireDate, isActive, notes, activeGroupsCount, teacherCode, gender, dateOfBirth, fatherName, motherName, address, permanentAddress, passportInfo, qualification, workExperience, joiningDate, status, basicSalary, medicalLeaves, casualLeaves, maternityLeaves, sickLeaves, photoUrl, groups[{id,groupName,courseName}] (barcha statusdagi guruhlar), createdAt.

### 1.2 Ma'lumot modeli

- **`teachers`** (`entity/Teacher.java`): `id` IDENTITY; `uuid` unique NOT NULL; `user_id` → `users.id` `@OneToOne LAZY` (`:40-42`), FK `fk_teachers_user` + partial unique `ux_teachers_user_id WHERE user_id IS NOT NULL` (`V50__teacher_user_link.sql:14-23,49-51`); `first_name`/`last_name` NOT NULL(100); `phone` NOT NULL(32) — unique **emas**, indeks `idx_teachers_phone` (V50:54); `email`(255); `status` varchar(20) default ACTIVE (`:105-107`) — DB CHECK **yo'q**; `is_active`; `teacher_code`(50) — unique **yo'q** (`:72-73`); `monthly_salary`, `basic_salary` numeric(12,2) (payroll-v2 da ishlatilmaydi); `passport_info`, ta'til kvotalari (int, default 0), `photo_url`. Cascade yo'q.
- **Lokal baza (DB):** V50 qo'llanmagan — `ux_teachers_user_id`, `fk_teachers_user`, `idx_teachers_phone` **yo'q**. Ularning o'rnida Hibernate yaratgan `UNIQUE(user_id)` (`uk_cd1k6…`) va ikkita FK bor: `teachers_user_id_fkey ON DELETE SET NULL` va `fkb8dct…` (NO ACTION). NO ACTION ustun keladi, ya'ni SET NULL amalda ishlamaydi (§0.1 X-03). Legacy ustunlar `casual_leaves`, `sick_leaves`, `medical_leaves`, `maternity_leaves`, `basic_salary`, `monthly_salary` jadvalda bor; lokalda 4 o'qituvchining hammasida maosh ustunlari NULL.
- **Status ↔ is_active invarianti** (yagona joy): `setStatus` (`:143-146`), `setIsActive` (`:152-160`, ON_LEAVE saqlanadi), `@PrePersist/@PreUpdate enforceStatusConsistency` (`:167-179`, zidlikda INACTIVE ustun). Tarixiy zidliklar V51 bilan tuzatilgan (`V51__teacher_status_consistency.sql:16-37`), noma'lum statuslar faqat NOTICE (`:41-52`).
- **`groups`** (`Teacher.groups` `@OneToMany(mappedBy="teacher") LAZY`, `:194-196`); `groups.teacher_id` `@ManyToOne LAZY` (`entity/Group.java:37-39`), `course` ham LAZY (`:33-35`).
- **`payroll.teacher_id`** → teachers: CASCADE FK olib tashlangan, NO ACTION (`V56__legacy_constraints.sql:49-75`) — o'qituvchini jismonan o'chirib bo'lmaydi (soft-delete bilan mos).
- Boshqa bog'lanishlar: `bonus_penalties.teacher_id` (TEACHER target), billing `LESSON_CHARGE.teacher_id`, `billing_periods.teacher_id` (TAXMIN: nomlar `docs/design/payroll-v2.md:115-117` dan).

### 1.3 Topilgan xatolar va xavflar

- **T-01 · P1 · PUT to'liq almashtirish — yuborilmagan maydonlar NULL bo'ladi.** Dalil: `service/TeacherService.java:821-857` (`buildFromRequest` har maydonni shartsiz yozadi), `:103-117`; eski front `front:views/pages/peoples/teachers/edit-teacher.vue:429-453` `monthlySalary`, `joiningDate` yubormaydi. Oqibat: har tahrirda `monthly_salary`, `joining_date` o'chadi (oylik v2 ga ta'sir qilmaydi, lekin ro'yxat/oylik tabida maosh "—"). Tavsiya: PATCH semantikasi (null → tegilmaydi) yoki DTO'ni to'liq yuborish shartnomasi + yangi admin barcha maydonni yuboradi.
- **T-02 · P1 · `TeacherRequest.userId` tekshiruvsiz bog'lanadi.** Dalil: `TeacherService.java:88-92,108-112` — rol TEACHER ekani, user boshqa profilga bog'lanmagani, `assertCanManage` tekshirilmaydi. Oqibat: band userga bog'lash → `ux_teachers_user_id` buziladi → 500; ADMIN o'qituvchini SA/ACC/STUDENT hisobiga bog'lay oladi; eski user profilsiz qoladi → oylik `TEACHER_PROFILE_MISSING` (`service/SalaryCalculationService.java:140-145`). Tavsiya: `userId` ni bu DTO'dan olib tashlash yoki rol=TEACHER + bo'shlik tekshiruvi, 409 qaytarish.
- **T-03 · P1 · Nofaol qilish faol guruhlarga ta'sir qilmaydi.** Dalil: `service/StaffStatusService.java:52-55,66-84` — guruhlar tekshirilmaydi; `GroupService.java:202-205,240-243` INACTIVE o'qituvchini guruhga biriktirishga ruxsat beradi. Oqibat: ACTIVE guruh bloklangan o'qituvchida qoladi — davomat belgilanmaydi, direktor dashboardida "MISSING" darslar unga yoziladi (`dashboard/AttendanceMetricsService.java:68-75`), guruh formasi selektori (`activeOnly=true`) uni ko'rsatmaydi. Tavsiya: faol guruh bo'lsa 409 + `reassignTo` parametri; GroupService'da INACTIVE o'qituvchini rad etish.
- **T-04 · P1 · Oy o'rtasida nofaol qilingan o'qituvchiga oylik generatsiya qilinmaydi.** Dalil: `SalaryCalculationService.java:102` (`findByIsActiveTrue`), `service/PayrollService.java:158-159`; dizaynda qayd etilgan (`docs/design/payroll-v2.md:123`); bitta xodim uchun generate endpointi yo'q (`controller/PayrollController.java:40-137` — faqat `GET /calculate/{userId}` preview). Oqibat: ishlagan kunlar uchun DRAFT yaratilmaydi, qo'lda yo'l yo'q. Tavsiya: generate'ga "shu oyda faol bo'lgan" (status tarixi yoki LESSON_CHARGE.teacher_id bo'yicha) xodimlarni qo'shish yoki `POST /api/payroll/generate/{userId}`.
- **T-05 · P1 · KPI javob shakli nomuvofiq.** Dalil: `TeacherController.java:52,88` yalang'och `TeacherKpiDto` (boshqa KPI endpointlari ApiResponse); skorlar `current` ichida (`TeacherService.java:348`), eski front top-level `overallScore/attendanceRate` o'qiydi (`front:views/pages/peoples/teachers/teacher-details/teacher-kpi.vue:293-299`). Oqibat: admin va o'qituvchi KPI kartalari doim bo'sh/“—”. Tavsiya: yangi admin `data.current.*` o'qisin; backendda ApiResponse ga o'rash (breaking — versiya bilan).
- **T-06 · P1 · Ro'yxat sahifalanmaydi, filtr yo'q, N+1.** Dalil: `TeacherService.java:68-74` (`findAll`/`findByIsActiveTrue`), `toResponse` `t.getGroups()` + `g.getCourse()` LAZY (`:859-869`) → 1 + T + G so'rov. Eski front `page,size,search` yuboradi — e'tiborsiz (`front:views/pages/peoples/teachers/teacher-list.vue:323-326`). Tavsiya: `GET /api/teachers?page&size&q&status&subject` + `@EntityGraph(groups, groups.course)` yoki ro'yxat uchun yengil DTO (groups'siz, `activeGroupsCount` aggregate bilan).
- **T-07 · P1 · `ensure-profile` SKIPPED da ham 200.** Dalil: `service/TeacherProfileSyncService.java:141-154,180-183`; yangi admin javobni tekshirmay "profil yaratildi" deydi (`admin:modules/payroll/components/PayrollGenerateDialog.vue:101-108`), tip ham noto'g'ri (`admin:modules/teachers/api.ts` `Promise<TeacherResponse>`, aslida Map). Tavsiya: SKIPPED → 409 + `reason`; yoki frontend `items[0].action` ni tekshirsin.
- **T-08 · P1 · O'qituvchi yaratish/tahrir/o'chirish audit qilinmaydi.** Dalil: `TeacherService`, `StaffStatusService`, `TeacherController` da `@Audited` yo'q (faqat user tomoni — `service/UserService.java:393-396`). Oqibat: maosh, pasport, status, guruh biriktirish o'zgarishlari izsiz. Tavsiya: `@Audited` + `AuditContext.change` (status, salary, userId, groupIds).
- **T-09 · P2 · KPI semantikasi.** Dalil: to'lov ko'rsatkichlari davrga bog'lanmagan — har doim bugungi snapshot (`service/TeacherKpiService.java:156-158`), oylik trendda barcha oylar uchun bir xil; davomat/ketganlar `g.teacher` (hozirgi) bo'yicha (`repository/AttendanceRepository.java:86-99`, `StudentGroupRepository.java:152-166`) — guruh boshqa o'qituvchiga o'tsa tarix ham o'tadi; daily trend 31×3 ta "barcha o'qituvchilar" aggregate so'rovi (`TeacherKpiService.java:60-71`). `satisfaction`, `studentProgress`, `penaltyAmount`, `advance` — stub (`TeacherService.java:322,344-345,384`). Tavsiya: billing-v2 `LESSON_CHARGE.teacher_id` va `billing_periods` asosida davrli hisob; stub maydonlarni UI'dan yashirish.
- **T-10 · P2 · `teacher_code` poygasi va ikki format.** Dalil: `count()+exists` (`TeacherService.java:768-777`, `TCH-%03d`) vs import `TCH-%05d` (`ImportService.java:676`); DB unique yo'q (`Teacher.java:72-73`). Oqibat: parallel yaratishda dublikat kod. Tavsiya: sequence + unique indeks.
- **T-11 · P2 · CSV formula injection.** Dalil: `TeacherService.java:895-900` faqat qo'shtirnoqni escape qiladi; `=`,`+`,`-`,`@` bilan boshlangan ism Excel'da formula. Tavsiya: bunday qiymatlarga `'` prefiks.
- **T-12 · P2 · Validatsiya yo'q → 500; telefon dublikatlari.** Dalil: `dto/request/TeacherRequest.java:15-55` da `@Size`/`@Email` yo'q (DB 100/32/50 chegarasi → "value too long" → 500); `teachers.phone` unique emas, sync telefonsiz userga `""` yozadi (`TeacherService.java:694-696`); `findByPhone` `Optional` (`TeacherRepository.java:18`) — ikki bir xil telefonda import qatori `IncorrectResultSize` bilan yiqiladi (`ImportService.java:691`). Tavsiya: DTO cheklovlari; `phone` NULLable yoki partial unique; `findFirstByPhone`.
- **T-13 · P2 · `groupIds` yarim ishlaydi.** Dalil: `TeacherService.java:809-819` — noma'lum id jim o'tkaziladi, ro'yxatdan chiqarilgan guruh ajratilmaydi, boshqa o'qituvchining guruhi (status tekshiruvisiz) tortib olinadi, GroupService mantiqi chetlab o'tiladi. Tavsiya: guruh biriktirishni faqat `/api/groups` orqali; bu maydonni deprecated qilish.
- **T-14 · P2 · Rol TEACHER'dan o'zgarsa qoldiqlar.** Dalil: `TeacherService.java:715-730` faqat INACTIVE qiladi; PENDING TEACHER bonuslari (`SalaryCalculationService.java:186-191` faqat TEACHER rolida o'qiydi) va guruhlar yetim qoladi. Tavsiya: rol o'zgarishida faol guruh/PENDING bonus bo'lsa 409.

### 1.4 billing-v2 / payroll-v2 / dashboard bilan bog'liqlik

- **Oylik (payroll-v2):** hisob **user** bo'yicha — `users.is_active=true` + `role ∈ SALARY_ROLES` (`SalaryCalculationService.java:102-113`); TEACHER uchun profil `teachers.user_id = user.id` (`:140-145`), aks holda `TEACHER_PROFILE_MISSING` / `TEACHER_PROFILE_LINKED_TO_OTHER_USER` (`:615-640`) → ta'mir: `/api/admin/repair/link-teacher-users`, `/api/teachers/{userId}/ensure-profile`. `salary_rules` ham user/rol bo'yicha (V40:12-23) — `teachers.monthly_salary/basic_salary` **ishlatilmaydi** (grep: faqat `TeacherService` va DTO), UI ularni maosh sifatida ko'rsatishi chalg'itadi.
- **Status zanjiri:** Teacher INACTIVE ⇄ User bloklangan (`StaffStatusService.java:66-84` ↔ `UserService.java:397-430` → `syncTeacherProfile`); ON_LEAVE userga ta'sir qilmaydi → oylik qoida bo'yicha hisoblanadi (ta'til davri chegirmasi yo'q — TAXMIN, `teacher(...)` hisobi to'liq o'qilmadi). INACTIVE → generate'dan tushadi (T-04).
- **Bonus/jarima:** TEACHER target `teacher_id`, STAFF target `user_id` (`SalaryCalculationService.java:186-199`).
- **billing-v2:** oylik tarixiy `LESSON_CHARGE.teacher_id`/`billing_periods.teacher_id` dan (dizayn `payroll-v2.md:115-117`) — guruh qayta biriktirilsa oylik buzilmaydi; lekin KPI va dashboard `group.teacher` (hozirgi) ishlatadi (T-09).
- **Direktor dashboardi:** davomat intizomi `teacherId` drill-down, faqat ACTIVE guruhlar, `g.teacher` bo'yicha (`dashboard/AttendanceMetricsService.java:68-75,174-175`) — T-03 bilan to'qnashadi.
- **Ruxsatlar:** guruh/davomat/talaba egaligi `TeacherAccessService` (`service/TeacherAccessService.java:55-102`) — Teacher topilmasa 403; ya'ni T-02 dagi noto'g'ri bog'lanish o'qituvchini o'z guruhlaridan mahrum qiladi.

### 1.5 Eski frontend (`adizone-crm-front`)

Servis: `front:services/teacherService.js:4-49` — `getAll→GET /api/teachers`, `getById`, `create`, `update(PUT)`, `remove(DELETE)`, `search(q)` (ishlatilmaydi), `getStats`, `getMyDashboard→/api/teacher/dashboard`, `getMyKpi`, `getKpi`, `getKpiRanking`, `getKpiTrend`, `getKpiDaily`. Login: `front:services/userService.js:40-45` + `front:composables/useTeacherLogin.js`.

Nomuvofiqliklar:
1. `teacher-list.vue:323-326,364` → `{page,size,activeOnly,search}`; backend faqat `activeOnly` → **qidiruv ishlamaydi**, sahifalash yo'q; ON_LEAVE/INACTIVE filtri klientda (`:330-333`).
2. `list-leaves.vue:192` `{page:0,size:200}` — e'tiborsiz (zararsiz).
3. `edit-teacher.vue:429-453` — `monthlySalary`, `joiningDate` yo'q → T-01.
4. `teacher-kpi.vue:293-299` top-level `overallScore` o'qiydi, backend `current.overallScore` → T-05; `useTeacherKpiTrendChart.js:174-175` `/kpi` ga `months` yuboradi (e'tiborsiz) va `data.monthlyTrend` qidiradi (backend `trend`).
5. `useTeacherLogin.js:64` → `GET /api/users/teacher/{teacherId}` — **backendda yo'q** (404, jim yutiladi); `:121-128` username'ni klient o'zi yasaydi → U-01.
6. Ishlatilmaydigan backend endpointlari: `/search`, `/export`, `/import`, `/{id}/photo` (TAXMIN: komponentlarda grep bo'yicha chaqiruv topilmadi), `/kpi/daily` ishlatiladi.

**Yangi admin (`admin:modules/teachers`)** qamrovi: faqat `teachers.detail` sahifasi (`routes.ts`, `pages/TeacherPage.vue` — profil + guruhlar, maosh/pasport ataylab yo'q), selektor uchun `list(activeOnly)`, `ensureProfile` (payroll dialogidan). **Qolgan:** ro'yxat sahifasi (`teachers.list` matritsada bor, sahifa yo'q), KPI/reyting (`teachers.kpi`), yaratish/tahrir formasi, status/deaktivatsiya (guruh qayta biriktirish bilan), rasm, login yaratish/reset, import/eksport, o'qituvchi kabineti (`/teacher`). Eskirgan izohlar: `api.ts:6` va `shared/permissions/matrix.ts:323,676` "GET /api/teachers — AUTH" (aslida SA,A,ACC).

### 1.6 Yetishmayotgan narsalar (edutizim.uz uslubiga nisbatan)

- **P1:** server-side ro'yxat (pagination, status/fan/qidiruv); status tarixi + arxiv sababi/sanasi (`terminated_at`, `reason`); deaktivatsiyada guruhlarni qayta biriktirish ustasi; o'qituvchi audit tarixi; KPI'ni davr bo'yicha to'g'ri hisoblash.
- **P2:** ish jadvali/bandlik (availability) va o'rinbosar (substitution) darslar; hujjat/sertifikat/diplom fayllari; o'quvchi/ota-ona baholari va reyting (satisfaction hozir stub); o'qituvchi davomati/kechikish jarimasi (`penaltyAmount=0`); fanlar ma'lumotnomasi (hozir erkin satr); filial; soatbay stavka tarixi; o'qituvchi o'z profilini tahrirlashi (`/api/auth/profile` Teacher'ga sinxronlanmaydi — U-09).

### 1.7 DB tekshiruvi uchun SQL (read-only)

```sql
-- T-Q1: teacher↔user nomuvofiqligi: roli TEACHER bo'lmagan userga bog'langan profil yoki status/faollik zid
SELECT t.id AS teacher_id, t.status, t.is_active AS t_active,
       u.id AS user_id, u.username, u.role, u.is_active AS u_active
FROM teachers t JOIN users u ON u.id = t.user_id
WHERE u.role <> 'TEACHER'
   OR (t.status = 'INACTIVE') IS DISTINCT FROM (COALESCE(u.is_active, TRUE) = FALSE);
```
```sql
-- T-Q2: bog'lanmaganlar: profilsiz TEACHER userlar (oylik TEACHER_PROFILE_MISSING) va egasiz profillar
SELECT 'USER_NO_PROFILE' AS kind, u.id, u.username::text AS name, u.phone::text AS phone, u.is_active
FROM users u
WHERE u.role = 'TEACHER' AND NOT EXISTS (SELECT 1 FROM teachers t WHERE t.user_id = u.id)
UNION ALL
SELECT 'TEACHER_NO_USER', t.id, t.first_name || ' ' || t.last_name, t.phone::text, t.is_active
FROM teachers t WHERE t.user_id IS NULL
ORDER BY 1, 2;
```
```sql
-- T-Q3: status taqsimoti; INACTIVE+true, ACTIVE/ON_LEAVE+false yoki noma'lum status = invariant buzilgan (V51)
SELECT status, is_active, COUNT(*) FROM teachers GROUP BY status, is_active ORDER BY 1, 2;
```
```sql
-- T-Q4: nofaol o'qituvchida qolgan faol guruhlar (T-03)
SELECT t.id, t.first_name, t.last_name, t.status,
       COUNT(g.id) AS open_groups, string_agg(g.group_name, ', ') AS groups
FROM teachers t JOIN groups g ON g.teacher_id = t.id AND g.status IN ('ACTIVE', 'FORMING')
WHERE t.status = 'INACTIVE' OR t.is_active = FALSE
GROUP BY t.id, t.first_name, t.last_name, t.status
ORDER BY open_groups DESC;
```
```sql
-- T-Q5: dublikatlar: telefon (shu jumladan '' placeholder), teacher_code, email
SELECT 'phone' AS k, phone::text AS v, COUNT(*) FROM teachers GROUP BY phone HAVING COUNT(*) > 1
UNION ALL
SELECT 'teacher_code', teacher_code::text, COUNT(*) FROM teachers WHERE teacher_code IS NOT NULL GROUP BY teacher_code HAVING COUNT(*) > 1
UNION ALL
SELECT 'email_ci', LOWER(email), COUNT(*) FROM teachers WHERE email IS NOT NULL AND email <> '' GROUP BY LOWER(email) HAVING COUNT(*) > 1
ORDER BY 1, 3 DESC;
```
```sql
-- T-Q6: faol shaxsiy oylik qoidasi nofaol yoki rolga mos kelmaydigan userda (payroll-v2)
SELECT sr.id AS rule_id, sr.role AS rule_role, sr.user_id, u.username, u.role AS user_role, u.is_active
FROM salary_rules sr JOIN users u ON u.id = sr.user_id
WHERE sr.is_active = TRUE
  AND (u.role <> sr.role OR COALESCE(u.is_active, TRUE) = FALSE);
```
```sql
-- T-Q7: PENDING TEACHER bonus/jarimalari nofaol yoki noto'g'ri bog'langan profillarda (T-14)
SELECT b.id, b.teacher_id, t.status AS teacher_status, u.id AS user_id, u.role AS user_role
FROM bonus_penalties b
JOIN teachers t ON t.id = b.teacher_id
LEFT JOIN users u ON u.id = t.user_id
WHERE b.status = 'PENDING' AND b.target_type = 'TEACHER'
  AND (t.status = 'INACTIVE' OR u.id IS NULL OR u.role <> 'TEACHER');
```

**Lokal bazadagi natija (2026-10-02, read-only):**
- T-Q1: 0 ta nomuvofiqlik. 4 profilning hammasi ACTIVE, `is_active = true`, TEACHER roli bor userga bog'langan.
- T-Q2: profilsiz TEACHER user ham, usersiz profil ham yo'q (0 / 0).
- T-Q3: faqat `ACTIVE / true` (4).
- T-Q4: 0. Lekin yagona ACTIVE guruh (`groups.id=1`) umuman o'qituvchisiz (`teacher_id IS NULL`). Dashboard va oylik atributsiyasi uchun bu ma'lumot sifati muammosi.
- T-Q5: telefon va `teacher_code` dublikati yo'q. Kodlar `TCH-001…004` formatida (`%03d`).
- T-Q6: 3 ta `salary_rules` bor, hammasi `role=TEACHER`, `user_id IS NULL` (umumiy qoida). Ulardan 2 tasi nofaol.
- T-Q7: `bonus_penalties` bo'sh.

### 1.8 Xulosa

**Holat: Qisman.** Status↔is_active invarianti, teacher↔user sinxroni, ta'mir endpointlari va ruxsatlar (SecurityConfig ∩ @PreAuthorize mos) puxta. Muammolar: PUT ma'lumot o'chirishi, `userId`/guruh biriktirish tekshiruvsiz, deaktivatsiyaning guruh va oylikka ta'siri, KPI shartnomasi va davr semantikasi, ro'yxat N+1/sahifalashsiz, audit yo'q. Yangi admin'da faqat detal sahifa.
**P0: 0** (o'qituvchi↔user bog'lanishidagi P0 — U-01 da) · **P1: 8** (T-01…T-08) · **P2: 6** (T-09…T-14).

---

## 2. Foydalanuvchilar va rollar

### 2.1 Endpointlar

| Metod | Yo'l | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| POST | `/api/users` | SA,A (`SecurityConfig.java:165-166`, `controller/UserController.java:45`); servis: SUPER_ADMIN rolini faqat SA beradi (`service/UserService.java:88,455-459`) | `CreateUserRequest`: `firstName`* (≤100), `lastName`* (≤100), `username` (≤100, ixtiyoriy — bo'sh bo'lsa generatsiya), `password`* (min 6), `phone` (`^$\|^\+998\d{9}$\|^\d{9}$`), `email` (@Email ≤255), `role`* (UserRole), `isActive` (default true) | 201 `ApiResponse<UserResponse>`; **username band bo'lsa 200 `message="ALREADY_EXISTS"` + mavjud user** (`:49-59`) | TEACHER bo'lsa profil yaratiladi (`UserService.java:106`) |
| GET | `/api/users/username-preview` | SA,A (`:66-73`) | `firstName`, `lastName` (query) | `ApiResponse<UsernamePreviewResponse>` {username, available} | Format `N.Familiya[2..]`, kirill→lotin (`UserService.java:212-319`) |
| POST | `/api/users/{id}/reset-password` | SA,A; servis: o'zini emas, SA ni faqat SA (`UserService.java:339-344`) | — | `ApiResponse<PasswordResetResponse>` {username, temporaryPassword (10 belgi, SecureRandom)} | refresh tokenlar bekor (`:350`) |
| PATCH | `/api/users/{id}/status` | SA,A; servis: SA target faqat SA, o'zini va SA ni bloklab bo'lmaydi (`:402-412`) | `UserStatusRequest` {`active`* (@NotNull)} | `ApiResponse<UserResponse>` | bloklashda refresh tokenlar bekor + Teacher sinxron (`:419-424`) |
| PUT | `/api/users/{id}` | SA,A; servis: `assertCanManage` + `assertCanGrantRole` (`:165-167`) | `UpdateUserRequest` (qisman): `firstName`, `lastName` (≤100), `email` (@Email), `phone` (pattern), `role`, `isActive` | `ApiResponse<UserResponse>` | `isActive` himoyalarni chetlab o'tadi (U-04) |
| PUT | `/api/users/{id}/password` | SA,A; servis `assertCanManage` (`:370-379`) | `ChangePasswordRequest` {`newPassword`* (@NotBlank, **min yo'q**), `currentPassword` (e'tiborsiz)} | `ApiResponse<Void>` | refresh tokenlar bekor |
| POST | `/api/users/{id}/photo` | SA,A + `assertManageable` (`:198-219`) | multipart `file` | `ApiResponse<UserResponse>` | |
| POST | `/api/users/create-for-teacher/{teacherId}` | SA,A (`:96-116`) | `CreateUserRequest` **@Valid'siz**; `role` e'tiborsiz (doim TEACHER) | 201 yoki 200 `ALREADY_EXISTS` `ApiResponse<UserResponse>` | U-01 |
| POST | `/api/users/create-for-student/{studentId}` | SA,A (`:118-156`) | `CreateUserRequest` @Valid'siz | 201/200 `ApiResponse<UserResponse>` | `studentId` ishlatilmaydi (U-08) |
| GET | `/api/users` | SA,A (`:158-166`) | — | `ApiResponse<List<UserResponse>>` | barcha userlar, sahifalashsiz |
| GET | `/api/users/{id}` | SA,A (`:168-174`) | — | `ApiResponse<UserResponse>` | |
| DELETE | `/api/users/{id}` | SA (Sec SA,A ∩ Pre SA, `:221-232`) | — | `ApiResponse<Void>` | soft = `setActive(false)` |
| GET | `/api/roles` | SA,A (faqat `SecurityConfig.java:169-170`; `controller/RoleController.java:20` da @PreAuthorize yo'q) | — | `ApiResponse<List<{value,label}>>` — 7 rol, SUPER_ADMIN ham | |
| GET | `/api/auth/me` | har qanday auth (`SecurityConfig.java:246`) | — | `ApiResponse<UserResponse>` | |
| PUT | `/api/auth/profile` | har qanday auth | `UpdateUserRequest` **@Valid'siz**; faqat firstName,lastName,email,phone qo'llanadi (`controller/AuthController.java:62-87`) | `ApiResponse<UserResponse>` | U-09 |
| PUT | `/api/auth/change-password` | har qanday auth (`AuthController.java:111-118`) | `ChangePasswordRequest` {`currentPassword` (servisda majburiy), `newPassword`*} | `ApiResponse<Void>` | refresh tokenlar bekor (`service/AuthService.java:170-185`) |

`UserResponse` (`dto/response/UserResponse.java:7-17`): id, username, email, firstName, lastName, phone, role, isActive, lastLogin, createdAt, photoUrl (`lastSeenAt`, bog'langan teacherId yo'q).

### 2.2 Ma'lumot modeli

- **`users`** (`entity/User.java`): `id` IDENTITY; `uuid` unique; `username` unique NOT NULL(100) — **registrga sezgir** unique, generatsiya esa `IgnoreCase` tekshiradi (`repository/UserRepository.java:19,22`); `email` unique (registrga sezgir); `password` NOT NULL (BCrypt, `config/SecurityConfig.java:303-306`); `first_name`/`last_name` NOT NULL(100); `phone`(20) — unique **emas**; `role` `@Enumerated(STRING)` NOT NULL (`:46-48`) — Hibernate CHECK'i startup'da `EnumCheckConstraintCleaner` o'chiradi (`config/EnumCheckConstraintCleaner.java:36-72`, `application.yml` `app.schema.drop-enum-checks: true`); `is_active` nullable, default true; `last_login`, `last_seen_at`, `photo_url`.
- **`UserRole`** (`entity/enums/UserRole.java:3-11`): SUPER_ADMIN, ADMIN, SALES_MANAGER, TEACHER, ACCOUNTANT, STUDENT, PARENT. STUDENT/PARENT userlari hech qanday entity'ga bog'lanmaydi (`Student`da user_id yo'q — `entity/Student.java:85-88` faqat `createdBy`, `attributed_user_id`).
- **`refresh_tokens`** (`entity/RefreshToken.java:21-33`): `token` unique(500) — **ochiq matn** UUID; `user_id` NOT NULL `@ManyToOne LAZY`; `expires_at`; `is_revoked`. Login userning barcha tokenlarini o'chiradi (`AuthService.java:80`) → bitta sessiya.
- FK'lar users'ga: `teachers.user_id`, `salary_rules.user_id`, `payroll.user_id/approved_by/paid_by/cancelled_by`, `students.created_by/attributed_user_id`, `bonus_penalties.user_id`, `lead_assignments.user_id` (V40, V53:43-45, V54:43-47, V55:26) — jismoniy o'chirish imkonsiz, soft-delete to'g'ri tanlov.
- Autentifikatsiya: har so'rovda user DB'dan yuklanadi (`security/jwt/JwtAuthenticationFilter.java:47-56`, `security/CustomUserDetailsService.java:24-36`) → bloklash va rol o'zgarishi darhol kuchga kiradi; JWT faqat username+muddat tekshiriladi (`security/jwt/JwtUtils.java:63-66`).

### 2.3 Topilgan xatolar va xavflar

- **U-01 · P0 · `create-for-teacher` o'qituvchini istalgan mavjud userga bog'laydi.** Dalil: `UserService.java:121-126` — `username` band bo'lsa rol, egalik (`assertCanManage`), boshqa profilga bog'langanligi tekshirilmaydi, `linkTeacherToUser` (`:503-508`); controller ham `:102-110`. Eski front username'ni klientda yasaydi (`front:composables/useTeacherLogin.js:118-128`) → adash ism-familiyali ikkinchi o'qituvchi birinchisining useriga bog'lanadi: u boshqa profilga bog'langan bo'lsa `ux_teachers_user_id` → **500**, bo'lmasa (masalan STUDENT/ACC/SA hisobi) profil o'g'irlanadi; front esa haqiqiy bo'lmagan yangi parolni ko'rsatadi. Teacher allaqachon userga ega bo'lsa — yangi user yaratib qayta bog'laydi, eski TEACHER user profilsiz faol qoladi (oylik `TEACHER_PROFILE_MISSING`). Tavsiya: band username → 409 (hech qachon bog'lamaslik); bog'lash alohida, rol=TEACHER va "bo'sh" sharti bilan; teacher'da user bo'lsa 409.
- **U-02 · P0 · STUDENT/PARENT hisoblari portal'siz, lekin "authenticated" endpointlarga kiradi — shaxsiy ma'lumot sizishi.** Dalil: STUDENT login admin UI'dan yaratiladi (`front:views/pages/peoples/students/student-details.vue:1305-1314`); `GET /api/search` `isAuthenticated()` (`controller/SearchController.java:33-34`, `SecurityConfig.java:240`) barcha o'quvchilar ism+**telefon**ini qaytaradi (`SearchController.java:46-58`); qolgan `anyRequest().authenticated()` (`SecurityConfig.java:246`) va `GET /api/notices/**`, `/api/chat/**`, `POST /api/files/**`, `/api/lead-stages/**` ham ochiq. Oqibat: istalgan o'quvchi boshqa o'quvchilar telefonlarini sanab chiqadi. Tavsiya: portal tayyor bo'lguncha ST/P ni `SecurityConfig` boshida `denyAll` (faqat `/api/auth/**`), search'ni xodim rollari bilan cheklash; rol ro'yxatidan ST/P ni yashirish.
- **U-03 · P1 · ADMIN boshqa ADMIN'larni to'liq boshqaradi.** Dalil: `assertCanManage` faqat SA'ni himoya qiladi (`UserService.java:448-452`), `assertCanGrantRole` faqat SA rolini (`:455-459`). ADMIN: yangi ADMIN yaratadi, boshqa ADMIN parolini reset qilib ochiq parolni oladi (`:333-359`) → hisobni egallaydi, bloklaydi, rolini tushiradi. Tavsiya: ADMIN/SA nishonini faqat SA boshqarsin (yoki ADMIN→ADMIN uchun SA tasdig'i).
- **U-04 · P1 · `PUT /api/users/{id}` `setActive` himoyalarini chetlab o'tadi.** Dalil: `UserService.java:182-187` `role`/`isActive` ni to'g'ridan-to'g'ri yozadi; o'zini bloklash, SA'ni bloklash taqiqi faqat `setActive` da (`:405-412`); eski front har tahrirda `isActive` va `role` yuboradi (`front:views/pages/user-management/user-list.vue:494-501`). Oqibat: SA boshqa SA'ni bloklay oladi; har kim o'zini bloklaydi/rolini tushiradi; **oxirgi faol SA** qolmasligi mumkin (tekshiruv yo'q). Tavsiya: `isActive` ni PUT'dan olib tashlash yoki `setActive` ga yo'naltirish; "self role change" va "last active SA" tekshiruvlari.
- **U-05 · P1 · Parol siyosati nomuvofiq; reset access tokenni o'ldirmaydi.** Dalil: `ChangePasswordRequest.newPassword` faqat `@NotBlank` (`dto/request/ChangePasswordRequest.java:8-9`) → `PUT /api/users/{id}/password` va `/api/auth/change-password` "1" qabul qiladi; `create-for-teacher/student` `@Valid`'siz (`UserController.java:100,122`) → min 6 ham yo'q, `password` null → BCrypt `IllegalArgumentException` → 400 "rawPassword cannot be null". Reset/parol almashtirish faqat refresh'ni bekor qiladi; access JWT (`application.yml:81`, 8 soat) amal qilaveradi — `JwtUtils.java:63-66` `iat` ni parol o'zgarish vaqti bilan solishtirmaydi. Brute-force cheklovi yo'q (`docs/audit/backend-audit.md` §2.4). Tavsiya: yagona `@ValidPassword` (min 8), `users.password_changed_at`/`token_version` + filterda tekshiruv, login rate-limit/lockout.
- **U-06 · P1 · "ALREADY_EXISTS" 200-muvaffaqiyat sifatida.** Dalil: `UserController.java:49-59,124-134`; front buni yaratildi deb, kiritilgan (haqiqiy bo'lmagan) parolni ko'rsatadi (`user-list.vue:503-515`, `student-details.vue:1305-1314` — 409 kutadi). Username unikalligi registrga sezgir: controller `findByUsername` aniq moslik, servis band-tekshiruvsiz (`UserService.java:196-201`) → `admin`/`Admin` ikkalasi yaratiladi; poygada → 500. Tavsiya: 409 `user.username.taken`; `existsByUsernameIgnoreCase` + `lower(username)` unique indeks.
- **U-07 · P1 · `users.phone` unique emas, lekin `findByPhone` Optional.** Dalil: `UserRepository.java:26,33`; dublikat telefon `create-for-teacher/student` (`UserController.java:141-152`, `UserService.java:138-149`) va `/api/auth/profile` orqali tushadi (tekshiruvsiz). Keyin `validateEmailAndPhone` (`UserService.java:473`) `IncorrectResultSizeDataAccessException` → shu telefon bilan har create/update **500**. Tavsiya: `existsByPhoneAndIdNot`; partial unique indeks yoki ruxsat etilgan dublikat + List.
- **U-08 · P1 · `create-for-student` — o'lik va xavfli.** Dalil: `UserController.java:118-156` — `studentId` hech qayerda ishlatilmaydi (bog'lanish yo'q), UserService chetlab o'tiladi (email/telefon tekshiruvi, audit yo'q), `@Valid` yo'q. Eski front chaqirmaydi (servisda bor: `front:services/userService.js:46-48`). Tavsiya: o'chirish (U-02 bilan birga).
- **U-09 · P1 · `/api/auth/profile` validatsiyasiz.** Dalil: `AuthController.java:62-87` — `@Valid` yo'q, email formati/unikalligi, telefon pattern tekshirilmaydi (band email → 500), Teacher profiliga sinxronlanmaydi (`TeacherService.syncTeacherProfile` chaqirilmaydi). Tavsiya: `UserService.updateOwnProfile` (validatsiya + sync).
- **U-10 · P1 · `GET /api/users` sahifalashsiz va filtrsiz.** Dalil: `UserController.java:158-166` `findAll()` — ST/P ham. Admin panel, kassa, oylik qoidalari selektorlari shu ro'yxatni klientda filtrlaydi. Tavsiya: `?page&size&role&active&q`, selektorlar uchun yengil `/api/users/options?roles=`.
- **U-11 · P2 · Rol o'zgarishi auditda eski/yangi qiymatsiz.** Dalil: `UserController.java:178-180` qat'iy summary; `AuditContext.change` faqat `setActive` da (`UserService.java:403`). Tavsiya: role/isActive/email uchun `change(...)`.
- **U-12 · P2 · `isActive=NULL` NPE.** Dalil: `CustomUserDetailsService.java:27` `!user.getIsActive()`; ustun nullable. Filterda yutiladi → 401; loginda `InternalAuthenticationServiceException` → 401 (TAXMIN). Tavsiya: `Boolean.FALSE.equals`, ustunni NOT NULL.
- **U-13 · P2 · Sessiyalar.** Dalil: login barcha refresh tokenlarni o'chiradi (`AuthService.java:80`) → ikkinchi qurilma birinchisini chiqaradi; tokenlar DB'da ochiq (`RefreshToken.java:21`); `expiresIn` qattiq 86400 (`AuthService.java:99,151`), haqiqiy muddat 8 soat (`application.yml:81`, izohda "24 hours" — TAXMIN: env bilan o'zgarishi mumkin). Tavsiya: ko'p sessiya (qurilma nomi, IP), token hash, `expiresIn` ni konfiguratsiyadan.
- **U-14 · P2 · `/api/roles` ADMIN'ga SUPER_ADMIN, STUDENT, PARENT ni taklif qiladi.** Dalil: `RoleController.java:20-30`. Tavsiya: aktorga beriladigan rollarni qaytarish.

### 2.4 billing-v2 / payroll-v2 / dashboard bilan bog'liqlik

- **Oylik:** hisob birligi — user (`SalaryCalculationService.java:102-150`): faqat faol, `SALARY_ROLES` = TEACHER, ADMIN, SALES_MANAGER (`service/SalaryRuleService.java:37-38`); qoidasiz ADMIN o'tkaziladi (`:110-112`). Bloklash = generate'dan chiqish (T-04); rol o'zgarishi hisob turini almashtiradi (TEACHER ↔ STAFF bonuslari, `:186-199`). `salary_rules` `user_id`/`role` — rol o'zgarganda shaxsiy qoida eski rolda qoladi (T-Q6).
- **Payroll audit maydonlari** `approved_by/paid_by/cancelled_by → users` (V54:43-47) — userni o'chirmaslik shart (soft-delete mos).
- **Dashboard:** `lead_assignments.user_id` (V53:41-50) operator KPI'lari; bloklangan SM'ga biriktirilgan lidlar qayta taqsimlanadimi — TAXMIN (LeadService o'qilmadi). Direktor dashboard ACC'ga ochiq (`SecurityConfig.java:120-121`).
- **Teacher sinxron:** har user create/update/status → `syncTeacherProfile` (`UserService.java:106,191,419`).

### 2.5 Eski frontend

Servis `front:services/userService.js`: `getAll→GET /api/users` (`user-list.vue:402`, `cash-registers.vue:835` — ACC uchun 403, `salary-rules.vue:348`, `audit-logs.vue:228`), `getById`, `update→PUT /{id}`, `changePassword→PUT /{id}/password` (UI'da chaqiruv topilmadi — TAXMIN), `usernamePreview`, `resetPassword`, `updateStatus→PATCH /{id}/status {active}`, `getRoles`, `updateProfile→PUT /api/auth/profile`, `changeOwnPassword→PUT /api/auth/change-password`, `createUser`, `getTeacherLogin`, `createTeacherUser`, `createStudentUser`.

Nomuvofiqliklar:
1. `getTeacherLogin` → `GET /api/users/teacher/{teacherId}` — **backendda yo'q** (404).
2. `user-list.vue:494-501` PUT'da `isActive` va `role` har doim yuboriladi → U-04 himoyalari chetlanadi.
3. `user-list.vue:503-515`, `student-details.vue:1305-1314` — `ALREADY_EXISTS` 200 ni muvaffaqiyat deb oladi (409 kutadi) → U-06; student username formati `ism.familiya` (klient) vs backend `N.Familiya`.
4. `useTeacherLogin.js:121-128` → U-01.
5. `cash-registers.vue:835` ACC uchun `GET /api/users` 403.

**Yangi admin:** `users` marshruti matritsada bor (`admin:shared/permissions/matrix.ts:462-470`, SA,A; `'user.delete': [SA]` `:780`), lekin `modules/users` **yo'q** — butun foydalanuvchilar boshqaruvi (ro'yxat, yaratish, tahrir, reset, status, rol) qilinmagan; `admin:shared/api/normalize.ts:69` `ALREADY_EXISTS` ni ajratib olish yordamchisi tayyor.

### 2.6 Yetishmayotgan narsalar

- **P1:** sahifalangan/filtrlangan userlar ro'yxati; sessiyalar ro'yxati va majburiy chiqarish; login rate-limit/lockout; parol siyosati (min uzunlik, reset'dan keyin majburiy almashtirish `mustChangePassword`); access tokenni bekor qilish (token_version); rol o'zgarishi auditi; "oxirgi SA" himoyasi; ST/P portal yoki ularni bloklash.
- **P2:** 2FA (TOTP yoki Telegram OTP); parol muddati; granular ruxsatlar (enum'dan tashqari RBAC, masalan filial bo'yicha); `lastSeenAt`/onlayn holat javobda; taklif (invite) havolasi; user↔student/parent bog'lanishi; login tarixi (IP/qurilma) UI'da.

### 2.7 DB tekshiruvi uchun SQL (read-only)

```sql
-- U-Q1: rol/faollik taqsimoti, NULL is_active (U-12), hech kirmaganlar, faol SA soni
SELECT role, is_active, COUNT(*) AS cnt,
       COUNT(*) FILTER (WHERE last_login IS NULL) AS never_logged_in,
       MAX(last_login) AS last_login_max
FROM users GROUP BY role, is_active ORDER BY role, is_active;
```
```sql
-- U-Q2: dublikatlar: username (registrsiz), email (registrsiz), telefon (U-06, U-07)
SELECT 'username_ci' AS k, LOWER(username) AS v, COUNT(*), string_agg(id::text, ',') AS ids
FROM users GROUP BY LOWER(username) HAVING COUNT(*) > 1
UNION ALL
SELECT 'email_ci', LOWER(email), COUNT(*), string_agg(id::text, ',')
FROM users WHERE email IS NOT NULL AND email <> '' GROUP BY LOWER(email) HAVING COUNT(*) > 1
UNION ALL
SELECT 'phone', phone::text, COUNT(*), string_agg(id::text, ',')
FROM users WHERE phone IS NOT NULL AND phone <> '' GROUP BY phone HAVING COUNT(*) > 1;
```
```sql
-- U-Q3: users/teachers/refresh_tokens dagi CHECK cheklovlari (eski enum CHECK qolganmi) va indekslar
SELECT 'check' AS kind, conrelid::regclass::text AS tbl, conname AS name, pg_get_constraintdef(oid) AS def
FROM pg_constraint
WHERE contype = 'c' AND conrelid IN ('users'::regclass, 'teachers'::regclass, 'refresh_tokens'::regclass, 'salary_rules'::regclass)
UNION ALL
SELECT 'index', tablename::text, indexname::text, indexdef
FROM pg_indexes WHERE schemaname = 'public' AND tablename IN ('users', 'teachers', 'refresh_tokens')
ORDER BY 1, 2, 3;
```
```sql
-- U-Q4: bloklangan userlarda bekor qilinmagan, muddati o'tmagan refresh tokenlar va tozalanmagan eski tokenlar
SELECT u.id, u.username, u.role, u.is_active,
       COUNT(*) FILTER (WHERE NOT COALESCE(r.is_revoked, FALSE)
                          AND r.expires_at > (now() AT TIME ZONE 'Asia/Tashkent')) AS live_tokens,
       COUNT(*) FILTER (WHERE r.expires_at <= (now() AT TIME ZONE 'Asia/Tashkent')) AS expired_tokens
FROM users u JOIN refresh_tokens r ON r.user_id = u.id
GROUP BY u.id, u.username, u.role, u.is_active
HAVING COALESCE(u.is_active, TRUE) = FALSE
    OR COUNT(*) FILTER (WHERE r.expires_at <= (now() AT TIME ZONE 'Asia/Tashkent')) > 0
ORDER BY live_tokens DESC;
```
```sql
-- U-Q5: STUDENT/PARENT hisoblari (U-02 ta'sir doirasi): faol va so'nggi 30 kunda kirganlar
SELECT role, COUNT(*) AS total,
       COUNT(*) FILTER (WHERE COALESCE(is_active, TRUE)) AS active,
       COUNT(*) FILTER (WHERE last_login > (now() AT TIME ZONE 'Asia/Tashkent') - INTERVAL '30 days') AS login_30d
FROM users WHERE role IN ('STUDENT', 'PARENT') GROUP BY role;
```
```sql
-- U-Q6: ADMIN/SA hisoblari: kim yaratgan/o'zgartirganini audit_log dan (U-03)
SELECT u.id, u.username, u.role, u.is_active, u.created_at,
       (SELECT COUNT(*) FROM audit_logs a WHERE a.entity_type = 'User' AND a.entity_id = u.id) AS audit_events
FROM users u WHERE u.role IN ('SUPER_ADMIN', 'ADMIN') ORDER BY u.role, u.id;
-- audit_logs(entity_type, entity_id) — V42__audit_log.sql:3,11-12 (retention 90 kun, application.yml).
```

**Lokal bazadagi natija:**
- U-Q1: 6 user — SA 1, ADMIN 1, TEACHER 4. Hammasi faol, `is_active` NULL yo'q. 2 ta TEACHER hech kirmagan.
- U-Q2: username (registrsiz), email va telefon dublikatlari yo'q.
- U-Q3: `users` da CHECK yo'q (umuman sxemada 0 ta CHECK — X-04). UNIQUE'lar ikki nusxada: `users_username_key` + `uk_r43af…`, `users_email_key` + `uk_6dotk…`, `users_uuid_key` + `uk_6km2…` (X-05).
- U-Q4: 1 ta jonli refresh token (SA), bloklangan userlarda token yo'q.
- U-Q5: STUDENT/PARENT hisoblari **0**. Ya'ni U-02 lokalda faqat imkoniyat, prod'da tekshirish kerak.
- U-Q6: `User` audit yozuvlari:
  - SA (id 1): 5 ta LOGIN;
  - ADMIN (id 18): 0;
  - TEACHER id 19: 8 ta UPDATE;
  - 3 ta LOGIN_FAILED `entity_id`siz va IP'siz (A-14).
  - Rol o'zgarishi bo'lganmi — diff yo'qligi sababli aniqlab bo'lmaydi (A-02).

### 2.8 Xulosa

**Holat: Muammoli.** Asos to'g'ri (soft-delete, SA himoyasi `setActive`/`reset`da, bloklash darhol kuchga kiradi, refresh bekor qilish, username generatsiyasi). Lekin `create-for-teacher` profilni boshqa hisobga bog'laydi (P0), ST/P hisoblari xodim ma'lumotlariga kiradi (P0), PUT himoyalarni chetlab o'tadi, ADMIN↔ADMIN eskalatsiyasi, parol siyosati va access-token bekor qilinmasligi, `ALREADY_EXISTS`/telefon dublikati xatolari. Yangi admin'da modul yo'q.
**P0: 2** (U-01, U-02) · **P1: 8** (U-03…U-10) · **P2: 4** (U-11…U-14).

---
**Testlar:** `UserService`, `TeacherService`, `StaffStatusService` uchun unit/IT test yo'q; teacher↔user ta'miri `src/test/java/com/crm/payroll/LegacySchemaAndTeacherLinkTest.java` da qisman qoplangan.

---

## 3. Ta'tillar

### 3.1 Endpointlar

SecurityConfig qoidalari (`config/SecurityConfig.java:184-195`, tartib to'g'ri: `/pending`, `/teacher/**` → `/{id}`, `/user/*` dan oldin). DELETE catch-all (`:243-244`) ga yetib bormaydi — `:194` oldinroq ushlaydi. Ikkala qatlam bir xil, nomuvofiqlik yo'q.

| Metod | Yo'l | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| GET | `/api/leaves?page=0&size=20&status=` | SA, A (SC `:194` ∩ `LeaveController.java:23`) | query: `page`, `size` (max yo'q), `status` (erkin matn, katta-kichik harf farqli) | `ApiResponse<PageResponse<LeaveResponse>>` | sort `createdAt DESC` (`LeaveService.java:37`) |
| GET | `/api/leaves/teacher/{teacherId}?page=0&size=50` | SA, A (`:188` ∩ `:32`) | path `teacherId` (Teacher.id) | `ApiResponse<PageResponse<LeaveResponse>>` | `teacher_id` bo'yicha; teacher yo'q → 404 (`LeaveService.java:50-52`) |
| GET | `/api/leaves/pending` | SA, A (`:188` ∩ `:41`) | — | `ApiResponse<List<LeaveResponse>>` | **Pageable'siz** (`LeaveService.java:158-162`) |
| GET | `/api/leaves/{id}` | SA, A, T (`:190` ∩ `:48`); T — faqat o'zi yuborgan yoki o'zi haqidagi (`LeaveService.java:139-151`) | path `id` | `ApiResponse<LeaveResponse>` | aks holda 403 "Bu ariza sizga tegishli emas" |
| GET | `/api/leaves/user/{userId}?page=0&size=20` | SA, A, T; T — faqat `userId == o'z user.id` (`LeaveService.java:61-64`) | path `userId` (**User.id**, requester) | `ApiResponse<PageResponse<LeaveResponse>>` | requester bo'yicha, teacher bo'yicha EMAS |
| POST | `/api/leaves` | SA, A, T (`:192` ∩ `:65`) | `LeaveSubmitRequest`: `leaveType` (@NotBlank), `fromDate` (@NotNull), `toDate` (@NotNull), `reason`, `teacherId` (SA/A uchun majburiy, T uchun e'tiborsiz), `requesterId` (doim e'tiborsiz) (`dto/request/LeaveSubmitRequest.java:10-33`) | 201 `ApiResponse<LeaveResponse>` msg "Leave request submitted" | requester = joriy user; T → o'z Teacher profili (`LeaveService.java:86-97`) |
| PATCH | `/api/leaves/{id}/status` | SA, A (`:194` ∩ `:72`) | yalang'och `Map`: `status` (majburiy, **erkin matn**), `reason` (REJECTED da) | `ApiResponse<LeaveResponse>` msg "Leave request updated" | tasdiqlovchi = joriy user (`LeaveService.java:132-133`) |
| DELETE | `/api/leaves/{id}` | SA, A (`:194` ∩ `:80`) | — | `ApiResponse<Void>` | **hard delete** (`LeaveService.java:153-156`) |

`LeaveResponse` (`dto/response/LeaveResponse.java:10-25`): `id, uuid, requesterId, requesterName (username!), teacherName ("first last"), leaveType, fromDate, toDate, reason, status, approvedById, approvedByName (username), approvedAt, createdAt`. **`teacherId` yo'q**, `days` yo'q.

IDOR tekshiruvi: TEACHER boshqaning arizasini ko'ra olmaydi — `/{id}` (`LeaveService.java:139-151`), `/user/{userId}` (`:61-64`), ro'yxat/`teacher`/`pending` SA/A. POST da boshqa o'qituvchi nomidan yubora olmaydi (`:89-90`). **IDOR topilmadi.**

### 3.2 Ma'lumot modeli

`Leave` (`entity/Leave.java:11-54`) → jadval `leave_requests`, `BaseEntity` (`created_at` NOT NULL, `updated_at`).

| Ustun | Tur / cheklov | Manba |
|---|---|---|
| id | IDENTITY PK | `:16-18` |
| uuid | UUID unique not null | `:20-22` |
| requester_id | FK users, LAZY, **nullable** | `:24-26`, `resources/db/migration/V30__leave_requester_nullable.sql:1-2` |
| teacher_id | FK teachers `ON DELETE SET NULL`, LAZY | `:28-30`, `V31__leave_teacher_id.sql:1-2` |
| leave_type | VARCHAR(30) not null, **enum emas** | `:32-33` |
| from_date / to_date | DATE not null | `:35-39` |
| reason | TEXT (teacher sababi + rad etish izohi aralash) | `:41-42` |
| status | VARCHAR(20), default "PENDING", **String** | `:44-46` |
| approved_by | FK users, LAZY | `:48-50` |
| approved_at | TIMESTAMP | `:52-53` |

- **Lokal baza (DB):**
  - Indekslar `idx_leave_requests_dates(from_date,to_date)`, `idx_leave_requests_requester`, `idx_leave_requests_status` **bor**; `teacher_id` indeksi yo'q.
  - FK'lar ikki nusxada va bir-biriga zid:
    - `requester_id`: `leave_requests_requester_id_fkey ON DELETE CASCADE` + Hibernate'ning `fkqlvxm…` (NO ACTION);
    - `approved_by`: SET NULL + NO ACTION;
    - `teacher_id`: faqat Hibernate FK (NO ACTION), ya'ni V31 dagi `ON DELETE SET NULL` qo'llanmagan.
  - UNIQUE(uuid) ikki nusxada. Jadval bo'sh (0 qator).
- CHECK yo'q: `from_date <= to_date` yo'q; status String bo'lgani uchun Hibernate enum CHECK ham yo'q (EnumCheckConstraintCleaner muammosi bu jadvalga taalluqli emas).
- Indeks: faqat uuid unique. `teacher_id`, `status`, `requester_id` ga indeks e'lon qilinmagan (V31 faqat FK) — TAXMIN: PG FK uchun avtomatik indeks yaratmaydi.
- Flyway yo'q (migratsiyalar qo'lda; `V31` ikki marta: `V31__leave_teacher_id.sql` + `V31__student_exit.sql`). V31 dan oldingi qatorlarda `teacher_id = NULL` bo'lishi mumkin.
- Bog'liq, lekin ishlatilmaydigan: `Teacher.medicalLeaves/casualLeaves/maternityLeaves/sickLeaves` (`entity/Teacher.java:112-126`) — faqat qo'lda tahrir (`service/TeacherService.java:841-851`), `LeaveService` ularga tegmaydi. `Teacher.STATUS_ON_LEAVE` (`entity/Teacher.java:25`) — faqat qo'lda (`service/StaffStatusService.java:66-84`).

### 3.3 Topilgan xatolar va xavflar

**L-01 · P1 · Status o'tishi va qiymati tekshirilmaydi.** Dalil: `LeaveService.java:117-124` — `body.get("status").toString()` to'g'ridan-to'g'ri yoziladi. Oqibat: `"approved"`, `"FOO"`, `"PENDING"` qabul qilinadi; REJECTED → APPROVED, APPROVED → qayta APPROVED (approvedBy/approvedAt ustidan yoziladi); 20 belgidan uzun qiymat → `DataIntegrityViolation` → 500 (`Leave.java:44`, `GlobalExceptionHandler.java:210`). Tavsiya: `LeaveStatus` enum (PENDING/APPROVED/REJECTED/CANCELLED), faqat PENDING → APPROVED|REJECTED; typed DTO `LeaveDecisionRequest{status, reason}` + `@Valid`.

**L-02 · P1 · Sanalar validatsiyasi yo'q.** Dalil: `LeaveSubmitRequest.java:26-30` (faqat @NotNull), `LeaveService.java:104-105`. Oqibat: `fromDate > toDate`, juda uzoq o'tmish/kelajak sanalar saqlanadi; frontend kunlarni manfiy hisoblaydi. Tavsiya: `fromDate <= toDate`, maksimal davomiylik, TEACHER uchun o'tmish sanaga cheklov; DB da `CHECK (from_date <= to_date)`.

**L-03 · P1 · Kesishuvchi ta'tillar tekshirilmaydi.** Dalil: `LeaveService.java:85-110` (hech qanday overlap so'rovi yo'q), `repository/LeaveRepository.java:13-22` (mos metod yo'q). Oqibat: bir o'qituvchiga bir davrga bir nechta PENDING/APPROVED ariza; kvota hisobi (frontend) ikki marta sanaydi. Tavsiya: `existsOverlapping(teacherId, from, to, statuses)` + yuborish va tasdiqlashda tekshirish (tasdiqlashda qulf bilan).

**L-04 · P1 · Tasdiqlangan ta'til tizimga hech qanday ta'sir qilmaydi.** Dalil: `LeaveService.java:117-135` — faqat status/approvedBy yoziladi; `StaffStatusService.changeTeacherStatus` chaqirilmaydi (u faqat `PUT /api/teachers/{id}` dan, `StaffStatusService.java:42-48`); kvota maydonlari kamaytirilmaydi (`TeacherService.java:841-851`); `LessonException` yaratilmaydi (`entity/LessonException.java:22-51` — leave/teacher maydoni yo'q). Oqibat: o'qituvchi ON_LEAVE ga avtomatik o'tmaydi; ta'til kunlaridagi darslar direktor dashboardida **MISSING** sifatida chiqadi (`dashboard/AttendanceMetricsService.java:126-149` — rejadan faqat lesson exception va bayramlar chiqariladi); kvota balansi faqat frontendda hisoblanadi. Tavsiya: §3.4 dagi oqim.

**L-05 · P1 · Oylik (payroll-v2) ta'tilni bilmaydi; o'rinbosarga hech narsa tushmaydi.** Dalil: `service/SalaryCalculationService.java:261-267` — `fixedSalary` to'liq; per-student ulush `billing_periods.teacher_id` (`billing/AccrualService.java:89`) va `LESSON_CHARGE.teacher_id = group.teacher` (`billing/LessonChargeService.java:77-78`) bo'yicha. Oqibat: haq to'lanmaydigan ta'tilda ham fixed to'liq; o'rinbosar dars o'tsa ham ulush guruh egasiga yoziladi. Tavsiya: payroll-v2 ga "UNPAID leave days" chegirma qatori (fixed × kunlar/ish kunlari) va substitute atributsiyasi (LessonException yoki attendance `marked_by` orqali) — dizayn qarori kerak.

**L-06 · P2 · N+1 va chegarasiz ro'yxatlar.** Dalil: `toResponse` har qatorda LAZY `requester`, `approvedBy`, `teacher` ni ochadi (`LeaveService.java:177-195`, `Leave.java:24-50`) → sahifa 20 da ~60 qo'shimcha so'rov; `/pending` Pageable'siz (`:158-162`); `size` ga yuqori chegara yo'q (`LeaveController.java:25-26`). Tavsiya: `@EntityGraph(attributePaths={"requester","approvedBy","teacher"})`, `size ≤ 100`.

**L-07 · P2 · TEACHER o'zi haqidagi (admin kiritgan) arizalarni ro'yxatda ko'ra olmaydi va o'z arizasini bekor qila olmaydi.** Dalil: `/user/{userId}` requester bo'yicha (`LeaveService.java:59-68`), `/teacher/**` SA/A (`SecurityConfig.java:188-189`), DELETE SA/A (`LeaveController.java:79-84`). Tavsiya: `GET /api/leaves/my` (teacher_id OR requester_id), `POST /{id}/cancel` (faqat PENDING, egasi).

**L-08 · P2 · Rad etish sababi o'qituvchi sababiga qo'shib yoziladi; REJECTED da ham `approvedBy` to'ldiriladi.** Dalil: `LeaveService.java:126-133`. Oqibat: asl sabab buziladi, "kim tasdiqladi" semantikasi noto'g'ri. Tavsiya: `decision_note`, `decided_by`, `decided_at` ustunlari.

**L-09 · P2 · Audit yo'q.** Dalil: `approveOrReject`, `deleteLeave` da `@Audited` yo'q (`LeaveService.java:116-156`); hard delete — tasdiqlangan ta'til izsiz yo'qoladi. Tavsiya: `@Audited` + soft-cancel.

**L-10 · P2 · `leaveType` erkin matn.** Dalil: `LeaveSubmitRequest.java:23-24`, `Leave.java:32-33`. Frontend `CASUAL/MEDICAL/MATERNITY/SICK/OTHER` yuboradi (`front:src/views/pages/hrm/leaves/list-leaves.vue:161-169`), backend boshqasini ham qabul qiladi; >30 belgi → 500. Tavsiya: enum + `@Pattern`/`@Size`.

**L-11 · P2 · Javobda `teacherId` va `days` yo'q; `requesterName`/`approvedByName` = username.** Dalil: `LeaveResponse.java:10-25`, `LeaveService.java:181,186`. Oqibat: admin panel ro'yxatdan o'qituvchi kartasiga o'ta olmaydi, filter qilolmaydi. Tavsiya: `teacherId`, `days`, to'liq ism.

**L-12 · P2 · Ta'til faqat o'qituvchilar uchun.** Dalil: SA/A uchun `teacherId` majburiy (`LeaveService.java:91-97`), POST faqat SA/A/T (`SecurityConfig.java:192`). Oqibat: ACCOUNTANT/SALES_MANAGER/ADMIN xodimlar ta'tili hisobga olinmaydi (payroll-v2 ular uchun ham oylik hisoblaydi). Tavsiya: `user_id` asosida (teacher ixtiyoriy).

### 3.4 billing-v2 / payroll-v2 / dashboard bilan bog'liqlik

| Bog'lanish | Holat | Dalil | Oqibat |
|---|---|---|---|
| Ta'til → Teacher.status ON_LEAVE | **YO'Q** | `LeaveService.java:117-135`; ON_LEAVE faqat `StaffStatusService.java:66-84` (qo'lda) | status va ta'til jadvali alohida yashaydi |
| Ta'til → payroll-v2 | **YO'Q** | `SalaryCalculationService.java:64-67` (deterministik manbalar ro'yxatida leave yo'q), `:261-267` | fixed oylik kamaymaydi; o'rinbosarga ulush yo'q |
| Ta'til → billing (o'quvchi to'lovi) | **YO'Q** | `AccrualService.java:80-91` davrlarni ta'tildan qat'i nazar yozadi; `LessonChargeService.java:77-78` | o'qituvchi ta'tilda — o'quvchilar to'liq oy to'laydi (biznes qarori, hujjatlanmagan) |
| Ta'til → davomat / dars kalendari | **YO'Q** | `LessonException.java:22-51`, `AttendanceMetricsService.java:126-149` | darslar rejada qoladi → MISSING; admin qo'lda CANCELLED exception qo'yishi kerak (`LessonCalendarController.java:59-60`) |
| Ta'til → dashboard | faqat `pendingLeaves` soni | `service/AnalyticsService.java:101,146`, `LeaveRepository.java:20-21` | direktor dashboardida (`dashboard/*`) ta'til yo'q |

Tavsiya (oqim): APPROVED → (a) `from..to` ichidagi guruh darslari uchun `LessonException(CANCELLED|SUBSTITUTE)` taklif qilish, (b) `from` kuni ON_LEAVE, `to+1` kuni ACTIVE (kunlik job, `Asia/Tashkent` — `CrmApplication.java:23`), (c) payroll-v2 hisobiga UNPAID kunlar chegirmasi, (d) kvota balansini serverda hisoblash.

### 3.5 Eski frontend nima ishlatadi

`front:src/services/leaveService.js:3-22`:

| Funksiya | Endpoint | Chaqiruvchi | Mosligi |
|---|---|---|---|
| `getAll(params)` | GET `/api/leaves` | `hrm/leaves/list-leaves.vue:175` (`status,page,size=50`), `approve-request.vue:96`, `admin-dashboard.vue:546` (`status=PENDING,size=5`) | mos |
| `getById` | GET `/api/leaves/{id}` | ishlatilmaydi | — |
| `create` | POST `/api/leaves` | `list-leaves.vue:235`, `teacher-leaves.vue:615` | `requesterId: teacherId` yuboriladi (`list-leaves.vue:226`, `teacher-leaves.vue:621`) — **noto'g'ri qiymat, lekin backend e'tiborsiz qoldiradi** (`LeaveService.java:77-83`); `currentUser.teacherId` fallback (`list-leaves.vue:221`) TEACHER uchun ma'nosiz (sahifa SA/A: `front:src/router/index.js` `/leaves` meta) |
| `approve` / `reject` | PATCH `/{id}/status` `{status}` / `{status, reason}` | `list-leaves.vue:202,213`, `approve-request.vue:143,153`, `admin-dashboard.vue:498,507` | mos; `approve-request.vue:153` va dashboard sababni hardcode qiladi ("Rad etildi") |
| `getByTeacher(id)` | GET `/api/leaves/teacher/{id}` | `teacher-leaves.vue:567` | mos (SA/A); `row.days`, `row.appliedOn` backendda yo'q — klient `leaveDaysBetween`/`createdAt` bilan to'ldiradi (`teacher-leaves.vue:458-463`) |
| — | GET `/api/attendance/teacher/{id}` | `teacher-leaves.vue:584` | **backendda yo'q** (o'qituvchi davomati umuman yo'q) |

Yangi admin: faqat menyu (`admin:src/shared/permissions/matrix.ts:447-458`, rollar SA,A — backend bilan mos), `src/modules/` da leaves moduli **yo'q**.

### 3.6 Yetishmayotgan narsalar (edutizim.uz uslubidagi CRM ga nisbatan)

| # | Narsa | Ustuvorlik |
|---|---|---|
| 1 | Status enum + o'tishlar + bekor qilish (CANCELLED) | P1 |
| 2 | Kesishuv va sana validatsiyasi | P1 |
| 3 | Ta'til turlari spravochnigi (haq to'lanadigan/to'lanmaydigan) + yillik kvota/balans serverda (`Teacher.*Leaves` ni haqiqiy kvota sifatida) | P1 |
| 4 | Tasdiqlashda darslarni bekor qilish yoki **o'rinbosar o'qituvchi** tayinlash (LessonException + payroll atributsiyasi) | P1 |
| 5 | Avtomatik ON_LEAVE ↔ ACTIVE (kunlik job) | P1 |
| 6 | Payroll-v2: to'lanmaydigan ta'til kunlari chegirmasi | P1 |
| 7 | O'qituvchining "mening arizalarim" (teacher_id bo'yicha) va bildirishnoma (notice/Telegram) tasdiq/rad haqida | P2 |
| 8 | Ilova (shifokor ma'lumotnomasi) — `/api/files` bilan | P2 |
| 9 | Ta'til kalendari (oy ko'rinishi), filtrlar: teacherId, from/to, type | P2 |
| 10 | O'qituvchi bo'lmagan xodimlar ta'tili (user_id asosida) | P2 |
| 11 | Yarim kun / soatlik ruxsat (javob so'rash) | P2 |

### 3.7 DB tekshiruvi uchun SQL (read-only)

```sql
-- 1) Statuslar taqsimoti (erkin matn: 'approved', 'FOO' kabi g'ayrioddiylarni topish)
SELECT status, COUNT(*) FROM leave_requests GROUP BY status ORDER BY 2 DESC;

-- 2) Noto'g'ri sanalar va turlar taqsimoti
SELECT id, teacher_id, from_date, to_date, leave_type, status
  FROM leave_requests WHERE from_date > to_date OR to_date - from_date > 180;
SELECT leave_type, COUNT(*) FROM leave_requests GROUP BY leave_type ORDER BY 2 DESC;

-- 3) Bir o'qituvchida kesishuvchi PENDING/APPROVED ta'tillar
SELECT a.teacher_id, a.id AS leave_a, b.id AS leave_b, a.from_date, a.to_date, b.from_date, b.to_date
  FROM leave_requests a
  JOIN leave_requests b ON b.teacher_id = a.teacher_id AND b.id > a.id
 WHERE a.status IN ('PENDING','APPROVED') AND b.status IN ('PENDING','APPROVED')
   AND daterange(a.from_date, a.to_date, '[]') && daterange(b.from_date, b.to_date, '[]');

-- 4) Yetim / eski qatorlar: teacher_id yo'q (V31 dan oldin) yoki requester TEACHER bo'lib, ariza boshqa o'qituvchi haqida
--    (eski frontend requesterId = teacherId yuborgan davr izi)
SELECT l.id, l.teacher_id, l.requester_id, u.role AS requester_role, t.user_id AS teacher_user_id
  FROM leave_requests l
  LEFT JOIN users u ON u.id = l.requester_id
  LEFT JOIN teachers t ON t.id = l.teacher_id
 WHERE l.teacher_id IS NULL
    OR (u.role = 'TEACHER' AND t.user_id IS DISTINCT FROM l.requester_id);

-- 5) Teacher.status va bugungi tasdiqlangan ta'til nomuvofiqligi (Toshkent sanasi)
SELECT t.id, t.first_name, t.last_name, t.status,
       EXISTS (SELECT 1 FROM leave_requests l WHERE l.teacher_id = t.id AND l.status = 'APPROVED'
                 AND (now() AT TIME ZONE 'Asia/Tashkent')::date BETWEEN l.from_date AND l.to_date) AS on_approved_leave
  FROM teachers t
 WHERE (t.status = 'ON_LEAVE') <> EXISTS (SELECT 1 FROM leave_requests l WHERE l.teacher_id = t.id AND l.status = 'APPROVED'
                 AND (now() AT TIME ZONE 'Asia/Tashkent')::date BETWEEN l.from_date AND l.to_date);

-- 6) Tasdiq ma'lumoti nomuvofiq: APPROVED/REJECTED lekin approved_by/approved_at yo'q yoki PENDING lekin bor
SELECT id, status, approved_by, approved_at FROM leave_requests
 WHERE (status IN ('APPROVED','REJECTED') AND (approved_by IS NULL OR approved_at IS NULL))
    OR (status = 'PENDING' AND approved_at IS NOT NULL);

-- 7) Jadvaldagi CHECK/UNIQUE/FK cheklovlar va indekslar
SELECT conname, contype, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'leave_requests'::regclass;
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'leave_requests';
```

**Lokal bazadagi natija:**
- `leave_requests` bo'sh, shuning uchun 1–6-so'rovlar 0 qator qaytaradi.
- 7-so'rov natijasi yuqorida, §3.2 "Lokal baza" bandida.
- O'qituvchilarning hammasi ACTIVE. ON_LEAVE ↔ ta'til nomuvofiqligi yo'q.

### 3.8 Xulosa

**Holat: Qisman.** CRUD va egalik (IDOR yo'q) ishlaydi; lekin ta'til faqat "yozuv" — status validatsiyasi, sana/kesishuv tekshiruvi yo'q, oylik, davomat, dars kalendari va o'qituvchi statusi bilan hech qanday bog'lanmagan.
**P0: 0 · P1: 5 (L-01…L-05) · P2: 7 (L-06…L-12).**

---

## 4. Imtihonlar

### 4.1 Endpointlar

SecurityConfig: GET `/api/exams/**` → SA,A,T (`config/SecurityConfig.java:111-112`); POST → SA,A,T (`:206-207`); PUT → SA,A,T (`:208-209`); DELETE → catch-all SA,A (`:243-244`); PATCH yo'q. ACCOUNTANT/SALES_MANAGER/STUDENT/PARENT — kirish yo'q. "Egalik" = `assertExamAccess` (`service/ExamService.java:396-407`: TEACHER uchun `exam.teacher` yoki `exam.group.teacher` = o'zi).

| Metod | Yo'l | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| GET | `/api/exams?page=0&size=20` | SA, A, T; T — faqat o'zinikilar (`ExamService.java:52-54`) | `page`, `size` (filtrlar yo'q) | `ApiResponse<PageResponse<ExamResponse>>` | faqat `isActive=true`; sort `createdAt DESC`; T uchun guruhsiz imtihonlar yo'qolishi mumkin (E-09) |
| GET | `/api/exams/{id}` | SA, A, T (+egalik) | — | `ApiResponse<ExamResponse>` | o'chirilgan (isActive=false) ham qaytadi |
| POST | `/api/exams` | SA, A, T; T — `groupId` o'ziniki (`ExamService.java:74-76`) | `ExamRequest`: `examName` (@NotBlank — yagona validatsiya), `examType`, `classId`, `groupId`, `subjectId`, `examDate`, `startTime`, `endTime`, `totalMarks`, `passMarks`, `academicYear` (`dto/request/ExamRequest.java:11-26`) | 201 `ApiResponse<ExamResponse>` | guruh o'quvchilari avto-ro'yxatga olinadi (`:83-85`, `:426-448`) |
| PUT | `/api/exams/{id}` | SA, A, T (+egalik, yangi `groupId` ham o'ziniki) | `ExamRequest` (@Valid) | `ApiResponse<ExamResponse>` | `groupId=null` guruhni olib tashlamaydi (`:372-379`) |
| DELETE | `/api/exams/{id}` | SA, A | — | `ApiResponse<Void>` | soft: `isActive=false` (`:100-106`) |
| GET | `/api/exams/{id}/results` | SA, A, T (+egalik) | — | `ApiResponse<List<ExamResultResponse>>` | ro'yxatdagi har o'quvchi uchun natija yoki bo'sh qator (`id=null`) (`:108-136`) |
| POST | `/api/exams/{id}/results` | SA, A, T (+egalik) | `ExamResultRequest`: `studentId` (servisda majburiy), `marksObtained`\|`score`, `grade`, `remarks`\|`notes` (`dto/request/ExamResultRequest.java:8-45`; **bean-validatsiya yo'q**) | 201 `ApiResponse<ExamResultResponse>` | dublikat → 409 (`:154-156`); `isPassed` server hisoblaydi |
| PUT | `/api/exams/{examId}/results/{resultId}` | SA, A, T (+egalik) | `ExamResultRequest` + **`editNote` majburiy** (`:187-190`); `@Valid` yo'q (`ExamController.java:102`) | `ApiResponse<ExamResultResponse>` | `@Audited(UPDATE, ExamResult)` (`:174-176`) |
| PUT | `/api/exams/results/{resultId}` | SA, A, T (+egalik) | — " — | — " — | @deprecated; **audit yozilmaydi** (E-07) |
| GET | `/api/exams/students/{studentId}/results` | SA, A, T; T — o'quvchi o'z faol guruhida (`TeacherAccessService.java:94-102`) | — | `ApiResponse<List<ExamResultResponse>>` | boshqa o'qituvchilar imtihonlari ham ko'rinadi |
| POST | `/api/exams/{id}/register-student?studentId=` | SA, A, T — **egalik tekshiruvi YO'Q** | query `studentId` (yo'q bo'lsa → 500) | 201 `ApiResponse<ExamRegistrationResponse>` msg "Ro'yxatdan o'tdi" | hozir **doim 400** (E-01) |
| GET | `/api/exams/{id}/eligible-students` | SA, A, T — **egalik YO'Q** | — | `ApiResponse<List<StudentResponse>>` | hozir **doim []** (E-01); tuzatilsa barcha o'quvchilar PII si |
| POST | `/api/exams/{id}/calculate-payment?studentId=` | SA, A, T (SC `:206`; `@PreAuthorize("isAuthenticated()")` `ExamController.java:48`) — **egalik YO'Q** | query `studentId` | `ApiResponse<Map>`: `examDate, examName, lastPaidUntil, unpaidDays, monthlyPrice, dailyRate, amountDue, message` | read-only amal POST da |

Javob DTO lari: `ExamResponse` (`dto/response/ExamResponse.java:12-31`): `id, uuid, examName, examType, classId, className, groupId, groupName, subjectId, subjectName, examDate, startTime, endTime, totalMarks, passMarks, academicYear, isActive, createdAt` (**teacherId/teacherName yo'q**). `ExamResultResponse` (`dto/response/ExamResultResponse.java:10-40`): `id, examId, examName, studentId, studentName, marksObtained, score (alias), totalMarks, passMarks, grade, remarks, notes (alias), isPassed, passed (alias), editNote, editedAt, editedBy (ism), createdAt`. `ExamRegistrationResponse` (`dto/response/ExamRegistrationResponse.java:12-23`): `id, examId, studentId, studentName, paymentStatus, amountDue, amountPaid, registrationDate, status, notes`.

**Ro'yxatga olinganlarni olish endpointi YO'Q** (faqat `/results` ichida aralash).

### 4.2 Ma'lumot modeli

| Entity | Jadval | Asosiy ustunlar | Bog'lanishlar / cheklovlar |
|---|---|---|---|
| `Exam` (`entity/Exam.java:12-68`), BaseEntity | `exams` | `exam_name` VARCHAR(255) NN, `exam_type` VARCHAR(50) (String), `exam_date` (nullable), `start_time`, `end_time`, `total_marks`/`pass_marks` NUMERIC(6,2), `academic_year`, `is_active` (soft delete) | →`classes` (`class_id`), →`groups` (`group_id`, `ON DELETE SET NULL`, idx `idx_exams_group_id` — `V29__exam_group_teacher.sql:1-7`), →`subjects`, →`teachers` (`teacher_id`, `ON DELETE SET NULL`, indekssiz). Hammasi LAZY. CHECK yo'q (pass ≤ total, start < end) |
| `ExamRegistration` (`entity/ExamRegistration.java:10-63`), BaseEntity EMAS | `exam_registrations` | `payment_status` VARCHAR(20) "PENDING" (String), `amount_due`/`amount_paid` NUMERIC(12,2), `registration_date` (`LocalDate.now()` — JVM TZ Toshkent, `CrmApplication.java:23`), `status` "REGISTERED", `notes`, `created_at` | →`exams` NN, →`students` NN, LAZY. **UNIQUE(exam_id, student_id) faqat `V27__exam_registrations.sql:12` da** (entity'da yo'q, migratsiyalar qo'lda) + idx exam/student (`:15-16`) |
| `ExamResult` (`entity/ExamResult.java:9-49`), BaseEntity | `exam_results` | `marks_obtained` NUMERIC(6,2), `grade` VARCHAR(5), `remarks` TEXT, `is_passed`, `edit_note` TEXT, `edited_at`, `edited_by` | →`exams` NN, →`students` NN, →`users` (`edited_by`, `V39__exam_result_edit_audit.sql:9-10`). **UNIQUE(exam_id, student_id)** (`:10-11`). Cascade yo'q; imtihon soft-delete bo'lganda natijalar qoladi |

**Lokal baza (DB):**
- `exam_registrations` da faqat PK bor. **UNIQUE(exam_id, student_id) yo'q**, `idx_exam_registrations_exam/student` indekslari ham yo'q — V27 qo'llanmagan. Bu E-19 ni tasdiqlaydi.
- `exams`:
  - indekslar `idx_exams_class_id`, `idx_exams_date`, `idx_exams_subject_id` bor;
  - `idx_exams_group_id` (V29) **yo'q**;
  - UNIQUE(uuid) ikki nusxada.
- `exam_results`:
  - UNIQUE(exam_id, student_id) ikki nusxada;
  - `exam_id` va `student_id` FK'lari ikki nusxada (CASCADE + NO ACTION), ya'ni CASCADE amalda ishlamaydi.
- Uchala jadval bo'sh.
- `student_groups.payment_status` qiymatlari: `PENDING` (1). Bu enum nomi, ya'ni E-01 shartidagi `"PAID".equals(enum)` hech qachon bajarilmaydi.

Statuslar String → Hibernate enum CHECK yo'q (EnumCheckConstraintCleaner taalluqli emas). Lekin `StudentGroup.paymentStatus` billing-v2 da **enum**ga aylangan — E-01 sababi.

### 4.3 Topilgan xatolar va xavflar

**E-01 · P0 · Imtihonga ro'yxatdan o'tkazish butunlay ishlamaydi (String vs enum).** Dalil: `ExamService.java:289` — `"PAID".equals(sg.getPaymentStatus())`; `entity/StudentGroup.java:115-118` da `PaymentStatus` enum (billing-v2 commit `74031b7` dan; `docs/audit/backend-audit.md:351` hali "String" deydi). `String.equals(enum)` doim `false` → `isEligibleForExam` doim `false`. Oqibat: `POST /{id}/register-student` doim 400 "O'quvchi imtihon uchun mos emas" (`:302-305`), `GET /{id}/eligible-students` doim `[]` (`:272-278`). Kompilyator ushlamaydi, test yo'q. Tavsiya: `sg.getPaymentStatus() == PaymentStatus.PAID` (va qaysi v2 holatlari "to'lagan" — `BillingSnapshotService.java:70` snapshot qiymatlari bilan kelishish).

**E-02 · P0 · TEACHER uchun ruxsat teshiklari (IDOR) — moliyaviy va shaxsiy ma'lumot.** Dalil: `calculateExamPaymentPreview` (`ExamService.java:220-269`) — na `assertExamAccess`, na `assertOwnsStudent`: istalgan TEACHER istalgan `studentId` uchun `lastPaidUntil`, `monthlyPrice`, `amountDue` ni oladi (endpoint hozir ishlaydi). `registerStudentForExam` (`:292-324`) — egalik yo'q: begona imtihonga istalgan o'quvchini yozadi (hozir E-01 to'sib turibdi). `getEligibleStudents` (`:272-278`) — `studentRepository.findAll()`: **barcha** o'quvchilarning `phone, parentPhone, address, birthDate` (`:326-346`) — E-01 tuzatilishi bilan ochiladi. Tavsiya: uchala metodga `assertExamAccess(exam)` + `teacherAccessService.assertOwnsStudent(studentId)`; eligible — faqat `exam.group` faol o'quvchilari.

**E-03 · P0 · Eski frontendda natijani tahrirlash doim 400.** Dalil: `front:src/views/pages/academic/examination/exam-results.vue:417-422` `changeReason` yuboradi; backend `editNote` talab qiladi (`ExamService.java:187-190`, `ExamResultRequest.java:27`). Oqibat: prod UI da natijani tuzatib bo'lmaydi. Tavsiya: `ExamResultRequest` ga `@JsonAlias("changeReason")` (1 qator) yoki frontend tuzatish.

**E-04 · P1 · Ro'yxatga olinganlar API si yo'q / eski frontend bilan 3 ta nomuvofiqlik.** Dalil: `ExamController.java:23-119` da `GET /{id}/registrations` yo'q; frontend: GET `/{id}/registrations` (`front:src/services/examService.js:18-20`) → 404, POST `/{id}/registrations {studentId}` (`:21-23`) → 404 (backend: `/register-student?studentId=`), GET `/{id}/calculate-payment` (`:24-26`) → 405 (backend POST). Oqibat: `exam-detail.vue` "O'quvchilar" tabi to'liq ishlamaydi. Tavsiya: `GET /api/exams/{id}/registrations` (PageResponse), `DELETE .../registrations/{studentId}`, calculate-payment → GET.

**E-05 · P1 · Natija istalgan o'quvchiga yoziladi.** Dalil: `addResult` (`ExamService.java:146-171`) faqat student mavjudligini tekshiradi — ro'yxatda/guruhda ekanini emas. Oqibat: TEACHER o'z imtihoniga begona o'quvchi natijasini qo'shadi (javobda ism-familiya ham qaytadi); `/results` da "ro'yxatsiz" qatorlar paydo bo'ladi (`:130-134`). Tavsiya: `existsByExamIdAndStudentId` yoki guruh a'zoligi talabi.

**E-06 · P1 · Validatsiya deyarli yo'q.** Dalil: `ExamRequest.java:11-26` (faqat `examName`); `ExamResultRequest.java:8-45` annotatsiyasiz; `ExamController.java:102,111` — `@Valid` yo'q. Oqibat: `examDate=null`, `passMarks > totalMarks`, `endTime < startTime`, manfiy yoki `totalMarks` dan katta ball, `grade` >5 belgi → `DataIntegrityViolation` → 500 (`ExamResult.java:30-31`, `GlobalExceptionHandler.java:210`). Tavsiya: `@NotNull examDate`, `@DecimalMin(0)`, cross-field validator, servisda `0 ≤ score ≤ totalMarks`, `@Size(max=5) grade`.

**E-07 · P1 · Natija tahriri auditi to'liq emas.** Dalil: (a) `editNote` har tahrirda ustidan yoziladi (`ExamService.java:195,205-207`) — faqat oxirgi o'zgarish qoladi; (b) `@Audited` summary statik, eski/yangi ball audit_log ga tushmaydi (`:174-176`, `audit/AuditAspect.java:114-116` — `AuditContext` to'ldirilmaydi); (c) audit 90 kundan keyin o'chadi (`resources/application.yml:55`); (d) deprecated `PUT /results/{id}` → `updateResult(resultId, req)` (`:213-218`) ichki `this.updateResult(...)` chaqiradi — Spring AOP proksi chetlanadi (`AuditAspect.java:70` `@Around`), **audit yozilmaydi**; (e) `addResult` umuman audit qilinmaydi. Tavsiya: `exam_result_history` jadvali (old/new score, note, user, at) yoki `AuditContext` ga diff; deprecated endpointni olib tashlash.

**E-08 · P1 · Imtihon to'lovi billing-v2 dan uzilgan; noto'g'ri summa, ikki marta undirish xavfi.** Dalil: `ExamService.java:230-231` `Payment.periodEnd` ga tayanadi; v2 `PaymentBookingService.java:169-187` to'lovga `periodStart/periodEnd` yozmaydi → `lastPaidUntil` eski (cutover oldidagi) sana → `unpaidDays` va `amountDue` sun'iy katta. Shu kunlar uchun v2 allaqachon davr undiradi (`AccrualService.java:80-91`) → `amountDue` alohida yig'ilsa — **ikki marta to'lov** (TAXMIN: yig'ish qo'lda, tizimda yo'l yo'q). `exam_registrations.amount_due/payment_status` hech qayerda yangilanmaydi (faqat builderlar `:313-320`, `:437-445`), ledger ga yozilmaydi. `monthlyPrice` — birinchi faol SG (tartibsiz), chegirma va PER_LESSON hisobga olinmaydi (`:242-248`). billing-v2 buni qamrovdan chiqargan (`docs/design/billing-v2.md:54`, `:1085`). Tavsiya: P1 qaror — yoki to'lov preview ni olib tashlash (eligibility = `BillingSnapshot` qarzi yo'q), yoki imtihon to'lovini alohida ledger turi (`EXAM_FEE`) qilib v2 ga kiritish.

**E-09 · P1 · TEACHER ro'yxatida guruhsiz imtihonlari ko'rinmaydi (TAXMIN).** Dalil: `repository/ExamRepository.java:17-22` — `e.group.teacher.id` yo'li Hibernate 6 da implicit **INNER** join → `group_id IS NULL` qatorlar `OR e.teacher.id = :teacherId` bo'lsa ham tushib qoladi. TEACHER `groupId`siz imtihon yarata oladi (`ExamService.java:74-80`). Tavsiya: `LEFT JOIN e.group g LEFT JOIN g.teacher gt WHERE e.teacher.id = :t OR gt.id = :t`.

**E-10 · P1 · Ro'yxat filtrlari yo'q.** Dalil: `ExamController.java:23-29`, `ExamService.java:48-63` — `groupId`, `from/to`, `examType`, `teacherId`, `academicYear` yo'q; sort `createdAt` (examDate emas). Oqibat: imtihon jadvali sahifasi `from/to` yuboradi, e'tiborsiz (`front:.../exam-schedule.vue:173-178`). Tavsiya: Specification/JPQL — `(:param IS NULL OR ...)` dan qoching (PG "could not determine data type"), `CAST`/dinamik Criteria ishlating.

**E-11 · P2 · N+1 va chegarasiz so'rovlar.** Dalil: `toExamResponse` LAZY `classEntity/group/subject` (`ExamService.java:409-424`); `getResultsByExam` — har registratsiya/natija uchun `student`, `exam`, `editedBy` (`:113-135`, `:488-511`); `getEligibleStudents` — `findAll()` + har o'quvchiga 2 so'rov (`:274-289`); `size` cheklanmagan. Tavsiya: `@EntityGraph`, fetch join, guruh doirasidagi so'rov.

**E-12 · P2 · O'chirilgan (soft) imtihon bilan ishlash mumkin.** Dalil: `findExamById` `isActive` ni tekshirmaydi (`ExamService.java:391-394`) → GET/PUT/natija qo'shish ishlaydi. Tavsiya: o'chirilganlar uchun 404 yoki faqat o'qish.

**E-13 · P2 · Avto-ro'yxat va qo'lda ro'yxat nomuvofiq.** Dalil: avto (`ExamService.java:426-448`) — eligibility/to'lov tekshiruvisiz, `PENDING` + `amountDue=0`; qo'lda (`:307-320`) — `amountDue=0` bo'lsa `PAID`. Bir xil holat ikki xil status. Guruhga keyin qo'shilgan o'quvchi avto-ro'yxatga tushmaydi (faqat create da, `:83-85`).

**E-14 · P2 · Eligibility qoidalari qattiq kodlangan va noto'g'ri doirada.** Dalil: `MIN_PRESENT_DAYS_FOR_EXAM = 8` (`ExamService.java:32`), hisob — **barcha vaqt, barcha guruhlar**, faqat `PRESENT` (`repository/AttendanceRepository.java:65-66`; LATE sanalmaydi); TRIAL/FROZEN o'quvchi chiqariladi. Tavsiya: sozlama + imtihon guruhi va davri bo'yicha.

**E-15 · P2 · TEACHER o'quvchining begona imtihon natijalarini ko'radi.** Dalil: `getResultsByStudent` (`ExamService.java:138-143`) faqat `assertOwnsStudent` — natijalar imtihon egasi bo'yicha filtrlanmaydi.

**E-16 · P2 · Noma'lum `groupId`/`classId` jimgina e'tiborsiz.** Dalil: `ExamService.java:372-383` (`ifPresent`); `classId == groupId` bo'lsa class tashlanadi (`:381`) — eski frontend xakiga moslash; `groupId=null` bilan guruhni olib tashlab bo'lmaydi. Tavsiya: 404/400.

**E-17 · P2 · `calculate-payment` himoyasi qatlamlarda farqli va metod noto'g'ri.** Dalil: `@PreAuthorize("isAuthenticated()")` (`ExamController.java:48`) — himoya faqat SecurityConfig `:206-207` ga bog'liq; o'qish amali POST da. Tavsiya: `hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')` + GET.

**E-18 · P2 · 400 o'rniga 500.** Dalil: `@RequestParam Long studentId` bo'lmasa `MissingServletRequestParameterException` (`ExamController.java:35,51`); parallel `addResult` → unique buzilishi; ikkalasi `GlobalExceptionHandler.java:210` → 500. Tavsiya: handlerlar qo'shish.

**E-19 · P2 · `exam_registrations` UNIQUE faqat qo'lda migratsiyada.** Dalil: `V27__exam_registrations.sql:12`, entity'da yo'q (`ExamRegistration.java:10-11`); Flyway yo'q. Yangi/ddl-auto bazada `existsBy...` + `save` (`ExamService.java:298-322`, `:434-446`) poygasi dublikat beradi. Tavsiya: `@UniqueConstraint` entity'ga; §4.7 SQL 6 bilan tekshirish.

### 4.4 billing-v2 / payroll-v2 / davomat / dashboard bilan bog'liqlik

| Bog'lanish | Holat | Dalil | Oqibat |
|---|---|---|---|
| Imtihon to'lovi → billing-v2 ledger | **YO'Q** | `ExamService.java:220-269` (faqat preview), `billing-v2.md:54` | E-08: eski `Payment.periodEnd` bo'yicha noto'g'ri summa; undirish yo'li yo'q; qo'lda yig'ilsa davr to'lovi bilan ikki marta |
| Eligibility → billing holati | buzilgan | `ExamService.java:289` vs `StudentGroup.java:118` | E-01 |
| Imtihon → davomat | **YO'Q** (faqat o'qish: eligibility PRESENT soni) | `AttendanceRepository.java:65-66` | imtihon kuni dars/davomat bilan bog'lanmaydi; imtihonda qatnashmaganlik qayd qilinmaydi (registration `status` faqat "REGISTERED") |
| Imtihon → dars kalendari (LessonException) | **YO'Q** | `entity/LessonException.java:24` (CANCELLED/MOVED/EXTRA, exam turi yo'q) | dars o'rniga imtihon bo'lsa — dashboard MISSING (`AttendanceMetricsService.java:146-149`) |
| Imtihon → payroll-v2 / KPI | **YO'Q** | `SalaryCalculationService.java:64-67`; `service/TeacherKpiService.java` exam ishlatmaydi (grep) | o'qituvchi natijadorligi (pass rate) KPI da yo'q |
| Imtihon → dashboard | **YO'Q** | `dashboard/*` va `AnalyticsService` da `ExamRepository` ishlatilmaydi (faqat `countByIsActiveTrue` e'lon qilingan, `ExamRepository.java:15`) | — |

### 4.5 Eski frontend nima ishlatadi

`front:src/services/examService.js:3-27`:

| Funksiya | Endpoint (frontend) | Backend | Chaqiruvchi | Holat |
|---|---|---|---|---|
| `getAll` | GET `/api/exams` `{page,size}` | mos | `exam-list.vue:252,261`, `exam-results.vue:323` | ok |
| `getAll` | GET `/api/exams` `{page,size,from,to}` | `from/to` yo'q | `exam-schedule.vue:173-178` | filtr e'tiborsiz (E-10) |
| `getById` | GET `/{id}` | mos | `exam-detail.vue:233` | ok |
| `create` | POST `/api/exams` | mos; `description` maydoni backendda yo'q (jimgina tashlanadi) | `exam-list.vue:276-290` | ok, tavsif yo'qoladi |
| `update` | PUT `/{id}` | mos | ishlatilmaydi | — |
| `remove` | DELETE `/{id}` | mos (SA/A) | `exam-list.vue:304` | ok |
| `getResults` | GET `/{id}/results` | mos | `exam-results.vue:341` | ok |
| `addResult` | POST `/{id}/results` `{studentId, marksObtained, grade, remarks}` | mos | `exam-results.vue:436-441` | ok |
| `updateResult` | PUT `/{examId}/results/{resultId}` `{marksObtained, grade, remarks, changeReason}` | `editNote` kutadi | `exam-results.vue:417-422` | **doim 400** (E-03) |
| `getRegistrations` | GET `/{id}/registrations` | **yo'q** | `exam-detail.vue:249` | 404 (E-04) |
| `registerStudent` | POST `/{id}/registrations` `{studentId}` | `POST /{id}/register-student?studentId=` | `exam-detail.vue:266` | 404 (E-04) |
| `calculatePayment` | **GET** `/{id}/calculate-payment?studentId=` | **POST** | `exam-detail.vue:286` | 405 (E-04) |

`exam-attendance.vue`, `grade-list.vue` — API chaqiruvi yo'q (statik shablon). Yangi admin: faqat menyu (`admin:src/shared/permissions/matrix.ts:237-250`, rollar SA,A,T — backend bilan mos), `src/modules/` da exams moduli **yo'q**.

### 4.6 Yetishmayotgan narsalar

| # | Narsa | Ustuvorlik |
|---|---|---|
| 1 | `GET /{id}/registrations` (+ o'chirish, status: REGISTERED/ABSENT/COMPLETED) | P1 |
| 2 | Ro'yxat filtrlari va imtihon jadvali (`from/to`, guruh, xona, o'qituvchi; examDate bo'yicha sort) | P1 |
| 3 | Natijalarni ommaviy kiritish (`PUT /{id}/results/bulk`) va Excel import/eksport | P1 |
| 4 | Natija tarixi (har tahrir alohida qator) | P1 |
| 5 | Imtihon to'lovi qarori: olib tashlash yoki v2 ledger turi | P1 |
| 6 | Baholash shkalasi (ball → grade avtomatik), bo'limlar (listening/reading/…) — til markazi uchun | P2 |
| 7 | Natijalarni ota-onaga yuborish (Telegram/SMS) va STUDENT/PARENT uchun o'qish (hozir rol yo'q) | P2 |
| 8 | Sertifikat (PDF, raqamli tekshiruv kodi) | P2 |
| 9 | Daraja imtihoni → keyingi guruhga o'tkazish (transfer-group) bog'lanishi | P2 |
| 10 | Statistika: o'rtacha ball, pass rate guruh/o'qituvchi bo'yicha → KPI va direktor dashboardi | P2 |
| 11 | Imtihon kuni darsni LessonException (EXAM) bilan belgilash, xona bandligi tekshiruvi | P2 |
| 12 | Qayta topshirish (retake), bir o'quvchi uchun bir nechta urinish | P2 |

### 4.7 DB tekshiruvi uchun SQL (read-only)

```sql
-- 1) Imtihonlar holati: faol/o'chirilgan, guruhsiz, sanasiz, ball nomuvofiq
SELECT is_active, (group_id IS NULL) AS no_group, (exam_date IS NULL) AS no_date,
       COUNT(*) FILTER (WHERE pass_marks > total_marks) AS pass_gt_total,
       COUNT(*) FILTER (WHERE end_time < start_time) AS bad_time, COUNT(*)
  FROM exams GROUP BY 1, 2, 3;

-- 2) Teacher atributsiyasi: exams.teacher_id guruh o'qituvchisidan farqli yoki guruhsiz + teacher yo'q (hech kimga ko'rinmaydi TEACHER rolida)
SELECT e.id, e.exam_name, e.teacher_id, g.teacher_id AS group_teacher_id, e.group_id
  FROM exams e LEFT JOIN groups g ON g.id = e.group_id
 WHERE e.is_active AND (e.teacher_id IS DISTINCT FROM g.teacher_id OR (e.group_id IS NULL AND e.teacher_id IS NULL));

-- 3) Natijalar: noto'g'ri ball, is_passed hisobi nomuvofiq, ro'yxatsiz natijalar
SELECT r.id, r.exam_id, r.student_id, r.marks_obtained, e.total_marks, e.pass_marks, r.is_passed,
       (er.id IS NULL) AS not_registered
  FROM exam_results r
  JOIN exams e ON e.id = r.exam_id
  LEFT JOIN exam_registrations er ON er.exam_id = r.exam_id AND er.student_id = r.student_id
 WHERE r.marks_obtained < 0 OR r.marks_obtained > e.total_marks
    OR r.is_passed IS DISTINCT FROM (CASE WHEN r.marks_obtained IS NULL OR e.pass_marks IS NULL THEN NULL
                                          ELSE r.marks_obtained >= e.pass_marks END)
    OR er.id IS NULL;

-- 4) Ro'yxatga olish to'lov holati taqsimoti (amount_due > 0 lekin hech qachon yangilanmagan)
SELECT status, payment_status, COUNT(*), SUM(amount_due) AS due, SUM(amount_paid) AS paid
  FROM exam_registrations GROUP BY 1, 2 ORDER BY 3 DESC;

-- 5) E-01 ta'siri: ro'yxatga olishlar qachon to'xtagan (billing-v2 deploy sanasi bilan solishtiring), qo'lda vs avto
SELECT date_trunc('week', created_at) AS wk,
       COUNT(*) FILTER (WHERE notes = 'Guruhdan avtomatik ro''yxatga olindi') AS auto,
       COUNT(*) FILTER (WHERE notes IS DISTINCT FROM 'Guruhdan avtomatik ro''yxatga olindi') AS manual
  FROM exam_registrations GROUP BY 1 ORDER BY 1 DESC LIMIT 12;
SELECT payment_status, COUNT(*) FROM student_groups WHERE is_active AND leave_date IS NULL GROUP BY 1;

-- 6) Dublikatlar va UNIQUE cheklov mavjudligi (V27 qo'lda qo'llanganmi)
SELECT exam_id, student_id, COUNT(*) FROM exam_registrations GROUP BY 1, 2 HAVING COUNT(*) > 1;
SELECT exam_id, student_id, COUNT(*) FROM exam_results GROUP BY 1, 2 HAVING COUNT(*) > 1;
SELECT conrelid::regclass AS tbl, conname, contype, pg_get_constraintdef(oid)
  FROM pg_constraint
 WHERE conrelid IN ('exams'::regclass, 'exam_results'::regclass, 'exam_registrations'::regclass)
 ORDER BY 1, 3;

-- 7) Yetim yozuvlar: o'chirilgan imtihon natijalari, o'quvchisi yo'q/arxivdagi registratsiyalar
SELECT 'result_on_inactive_exam' AS kind, COUNT(*) FROM exam_results r JOIN exams e ON e.id = r.exam_id WHERE NOT e.is_active
UNION ALL
SELECT 'reg_student_missing', COUNT(*) FROM exam_registrations er LEFT JOIN students s ON s.id = er.student_id WHERE s.id IS NULL
UNION ALL
SELECT 'result_edited_without_note', COUNT(*) FROM exam_results WHERE edited_at IS NOT NULL AND (edit_note IS NULL OR edit_note = '');

-- 8) Natija tahriri izi audit_log da (E-07): deprecated endpoint orqali tahrir audit_log da yo'q bo'ladi
SELECT r.id, r.edited_at, r.edited_by,
       EXISTS (SELECT 1 FROM audit_logs a WHERE a.entity_type = 'ExamResult' AND a.entity_id = r.id) AS has_audit
  FROM exam_results r WHERE r.edited_at IS NOT NULL ORDER BY r.edited_at DESC LIMIT 50;
-- (jadval/ustunlar: resources/db/migration/V42__audit_log.sql:3,11-12; 90 kundan eski loglar o'chirilgan bo'ladi)
```

**Lokal bazadagi natija:**
- `exams`, `exam_registrations`, `exam_results` bo'sh. Shuning uchun 1–5, 7, 8-so'rovlar 0 qator qaytaradi.
- 6-so'rov: `exam_registrations` da UNIQUE yo'q (yuqoriga qarang).
- `audit_logs` da `ExamResult` yozuvi yo'q.

### 4.8 Xulosa

**Holat: Muammoli.** CRUD va natija kiritish (admin) ishlaydi, lekin: ro'yxatga olish billing-v2 dan keyin butunlay buzilgan (E-01), TEACHER uchun moliyaviy/PII IDOR (E-02), eski UI da natijani tahrirlash ishlamaydi (E-03), imtihon to'lovi v2 dan uzilgan va noto'g'ri summa beradi (E-08), davomat/kalendar/payroll/dashboard bilan bog'lanish yo'q.
**P0: 3 (E-01, E-02, E-03) · P1: 7 (E-04…E-10) · P2: 9 (E-11…E-19).**

---

## 5. Uy vazifalari

### 5.1 Endpointlar

URL qoidasi: `/api/homework/**` → SA,A,T (`SC:204-205`; DELETE catch-all `SC:243` dan oldin, shuning uchun DELETE ham shu qoidaga tushadi va `@PreAuthorize` uni SA,A ga toraytiradi). Class-level `@PreAuthorize` yo'q. Envelope: hammasi `ApiResponse<T>` = `{success, message, data, meta?}` (`dto/response/ApiResponse.java:5-14`); `PageResponse` = `{content, pageNumber, pageSize, totalElements, totalPages, last}` (`dto/response/PageResponse.java:5-11`).

| Metod | Yo'l | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| GET | `/api/homework` | SA,A,T (`HomeworkController.java:22-28`). T — faqat `homework.teacher_id = o'zi` (`HomeworkService.java:36-41`) | query `page=0`, `size=20` (yuqori chegara yo'q) | `ApiResponse<PageResponse<HomeworkResponse>>`, `createdAt DESC`, faqat `isActive=true` | Admin yaratgan, `teacher=null` lekin T guruhiga bog'langan vazifa T ro'yxatida chiqmaydi (H-07) |
| GET | `/api/homework/group/{groupId}` | SA,A,T; T uchun `assertOwnsGroup` (`HomeworkService.java:47`, `TeacherAccessService.java:73-92`) | path `groupId`; `page=0`, `size=50` | `ApiResponse<PageResponse<HomeworkResponse>>` | |
| GET | `/api/homework/{id}` | SA,A,T; T — vazifa uniki yoki guruhi uniki (`HomeworkService.java:138-149`) | path `id` | `ApiResponse<HomeworkResponse>` | O'chirilgan (`isActive=false`) vazifani ham qaytaradi (`:54-58`) |
| POST | `/api/homework` | SA,A,T; `groupId` berilsa T uchun egalik (`:62-64`) | `HomeworkRequest`: `title` (@NotBlank), `dueDate` (@NotNull), `description`, `subjectId`, `classId`, `groupId`, `teacherId`, `assignedDate`, `marks` (`dto/request/HomeworkRequest.java:11-23`) | **201** `ApiResponse<HomeworkResponse>` | T bo'lsa `teacher` majburan o'zi (`:66-68`); `assignedDate` bo'sh → bugun (`:70`). `groupId` majburiy emas |
| PUT | `/api/homework/{id}` | SA,A,T + egalik (`:76-80`) | `HomeworkRequest` | `ApiResponse<HomeworkResponse>` | T `teacherId` bilan vazifani boshqa o'qituvchiga o'tkaza oladi (`:166-168`) |
| DELETE | `/api/homework/{id}` | SA,A (`HomeworkController.java:60`) | — | `ApiResponse<Void>` | Soft: `isActive=false` (`HomeworkService.java:86-91`) |
| GET | `/api/homework/{id}/submissions` | SA,A,T + egalik (`:95`) | — | `ApiResponse<List<HomeworkSubmissionResponse>>` (sahifasiz) | |
| POST | `/api/homework/{id}/submissions` | `isAuthenticated()` (`HomeworkController.java:73`) ∩ URL SA,A,T ⇒ **SA,A,T**; **egalik tekshiruvi YO'Q** (`HomeworkService.java:101-121`) | `HomeworkSubmissionRequest`: `studentId` (@NotNull), `submittedAt`, `fileUrl`, `remarks`, `marksObtained`, `status` (`dto/request/HomeworkSubmissionRequest.java:10-18`) | **201** `ApiResponse<HomeworkSubmissionResponse>` | ST roli topshira OLMAYDI (URL qoidasi). Takror → 409 `DuplicateResourceException` (`:104-106`); `status` default `"SUBMITTED"` (`:117`) |
| PUT | `/api/homework/submissions/{submissionId}` | SA,A,T; **egalik tekshiruvi YO'Q** (`HomeworkService.java:124-131`) | `HomeworkSubmissionRequest` (`studentId` @NotNull, lekin ishlatilmaydi) | `ApiResponse<HomeworkSubmissionResponse>` | Faqat `marksObtained`, `remarks`, `status` yangilanadi; `marksObtained` yuborilmasa NULL ga yoziladi (`:127`) |
| POST | `/api/files/upload` | AUTH (`SC:241` + `FileController.java:31`) — ST/P ham | multipart `file` | 200 `ApiResponse<Map>` `{url, filename}` (`FileController.java:36-41`) | **Faqat rasm** (jpg/jpeg/png/webp/gif), 4MB (`service/FileStorageService.java:125-139`, `:22`). PDF/DOCX uy vazifasi fayli bu yerdan yuklanmaydi |
| GET | `/api/files/{filename}` | **permitAll** (`SC:86`) | path, bitta segment | yalang'och `Resource`; `nosniff`; rasm/audio/PDF `inline`, qolgani `attachment` (`FileController.java:59-87`) | Himoya — taxmin qilinmaydigan UUID nom (`FileController.java:50-58`) |

`HomeworkResponse` (`dto/response/HomeworkResponse.java:11-29`): `id, uuid, title, description, subjectId, subjectName, classId, className, groupId, groupName, teacherId, teacherName, assignedDate, dueDate, marks, isActive, createdAt`.
`HomeworkSubmissionResponse` (`dto/response/HomeworkSubmissionResponse.java:9-21`): `id, homeworkId, homeworkTitle, studentId, studentName, submittedAt, fileUrl, remarks, marksObtained, status, createdAt`.

### 5.2 Ma'lumot modeli

- **Homework** → `homeworks` (`entity/Homework.java:11-58`, `BaseEntity` meros: `created_at` NOT NULL, `updated_at` — `entity/BaseEntity.java:18-24`). Ustunlar: `id` IDENTITY, `uuid` UNIQUE NOT NULL (`:20-22`), `title` varchar(255) NOT NULL, `description` TEXT, `assigned_date` date, `due_date` date NOT NULL (`:49-50`), `marks` numeric(6,2), `is_active` (default true). FK: `subject_id`, `class_id`, `group_id`, `teacher_id` — hammasi `@ManyToOne(LAZY)`, nullable, cascade yo'q (`:30-44`).
- **HomeworkSubmission** → `homework_submissions` (`entity/HomeworkSubmission.java:9-42`, `BaseEntity`). `homework_id` NOT NULL, `student_id` NOT NULL (LAZY), **UNIQUE(homework_id, student_id)** (`:10-11`), `submitted_at` timestamp, `file_url` varchar(500), `remarks` TEXT, `marks_obtained` numeric(6,2), `status` varchar(20) **String** (enum emas, default `"PENDING"` `:39-41`; servis esa `"SUBMITTED"` qo'yadi).
- Flyway migratsiyasi YO'Q — jadvallar `ddl-auto: update` bilan yaratilgan (`src/main/resources/application.yml:32`; migratsiyalar V24 dan boshlanadi). Indekslar entity'da e'lon qilinmagan, lekin **lokal bazada bor** (eski migratsiyalardan qolgan): `idx_homeworks_group_id`, `idx_homeworks_teacher_id`, `idx_homeworks_class_id`, `idx_homeworks_due_date`, `idx_hw_submissions_homework`, `idx_hw_submissions_student`. Ikki nusxada bo'lganlar: UNIQUE(uuid) va UNIQUE(homework_id, student_id). Status CHECK yo'q (String).
- Fayl: diskda `app.upload.dir` (`application.yml:57`), nom = `UUID.ext` (`FileController.java:36`), DBda fayl jadvali yo'q, egasi/bog'liqligi saqlanmaydi.

### 5.3 Topilgan xatolar va xavflar

| ID | P | Sarlavha | Dalil | Oqibat | Tavsiya |
|---|---|---|---|---|---|
| H-01 | P1 | Baholashda IDOR: istalgan T istalgan topshiriqni baholaydi | `HomeworkService.java:124-131` — `assertHomeworkAccess` chaqirilmaydi | Boshqa o'qituvchining o'quvchisi bahosini o'zgartirish | `assertHomeworkAccess(sub.getHomework())` qo'shish |
| H-02 | P1 | Topshiriq qo'shishda egalik va guruh a'zoligi tekshirilmaydi | `HomeworkService.java:101-121` | T begona vazifaga yozuv qo'shadi; guruhda bo'lmagan o'quvchiga topshiriq yaratiladi; o'chirilgan (`isActive=false`) vazifaga ham | `assertHomeworkAccess(hw)`, `hw.isActive`, `student ∈ hw.group` (`StudentGroupRepository` aktiv yozilma) tekshiruvlari |
| H-03 | P1 | ST roli uchun topshirish oqimi yo'q | URL `SC:204-205` ST ni bloklaydi; `@PreAuthorize("isAuthenticated()")` (`HomeworkController.java:73`) chalg'ituvchi; `User`↔`Student` bog'i bu oqimda ishlatilmaydi | "O'quvchi uy vazifasini topshiradi" funksiyasi amalda yo'q, faqat xodim qo'lda kiritadi | Hozircha `@PreAuthorize` ni SA,A,T ga to'g'rilash; o'quvchi portali kerak bo'lsa alohida `/api/student/homework/**` + `studentId` ni tokendan olish |
| H-04 | P1 | Uy vazifasi fayllari (PDF/DOCX) yuklanmaydi | `/api/files/upload` faqat `saveImage` (`FileController.java:36`, `FileStorageService.java:125-139`); `Homework` da attachment maydoni yo'q (`Homework.java:24-57`) | O'qituvchi topshiriq faylini biriktira olmaydi; `fileUrl` faqat erkin matn | `FileStorageService.isAllowed` (`:95-98`) asosida uy vazifasi upload endpointi + `homework_attachments` jadvali |
| H-05 | P2 | `fileUrl` validatsiyasiz saqlanadi | `HomeworkService.java:114`; DTO `HomeworkSubmissionRequest.java:14` | `javascript:`/tashqi havola saqlanadi — frontend `<a :href>` qilsa stored-XSS/phishing (TAXMIN: hozirgi FE topshiriqlarni ko'rsatmaydi) | `URL_PREFIX` (`FileStorageService.java:28`) bilan boshlanishini talab qilish (chat'dagi kabi) |
| H-06 | P2 | Ball/holat validatsiyasi yo'q | `HomeworkSubmissionRequest.java:16-17`; `HomeworkService.java:116-117,127-129` | Manfiy yoki `homework.marks` dan katta ball; `status` istalgan satr (≤20, oshsa 500) | `@DecimalMin(0)`, `≤ hw.marks`; `status` enum (`PENDING/SUBMITTED/GRADED/LATE/MISSING`); update'da `null` → o'zgartirmaslik |
| H-07 | P2 | T ro'yxati va ruxsat qoidasi mos emas | Ro'yxat faqat `teacher_id` (`HomeworkService.java:38`), ruxsat esa `teacher_id` YOKI `group.teacher_id` (`:143-146`) | Admin T guruhiga bergan vazifani T ro'yxatda ko'rmaydi, lekin id bilan ochadi | Repo so'rovi: `h.teacher.id = :t OR h.group.teacher.id = :t` |
| H-08 | P2 | T vazifani boshqa o'qituvchiga o'tkazadi / `groupId=null` bilan "egasiz" vazifa | `HomeworkService.java:62-68,166-168` | Ma'lumot chalkashligi | T uchun `teacherId` ni e'tiborsiz qoldirish; `groupId` ni majburiy qilish |
| H-09 | P2 | N+1 | `toResponse` LAZY `subject/classEntity/group/teacher` nomlarini o'qiydi (`HomeworkService.java:182-191`); `toSubmissionResponse` `homework/student` (`:199-201`) | Sahifa 50 da ~100+ so'rov | `@EntityGraph` yoki DTO projection |
| H-10 | P2 | Parallel takroriy topshiriq → 500 | check-then-insert (`HomeworkService.java:104-106`) + UNIQUE (`HomeworkSubmission.java:10-11`); `DataIntegrityViolationException` handleri yo'q (`exception/GlobalExceptionHandler.java:210-219` umumiy 500) | Ikki marta bosilganda 500 | Global `DataIntegrityViolationException` → 409 |
| H-11 | P2 | `size` chegarasiz | `HomeworkController.java:26,35`; `PageRequest.of(page,size)` (`HomeworkService.java:34,48`) | `size=100000` → og'ir so'rov | `Math.min(size, 200)` |
| H-12 | P2 | `GET /api/files/**` ochiq | `SC:86`; `FileController.java:50-58` | Rasm/chat fayllari (PDF/DOCX/ovoz — `FileStorageService.java:49-66`) havolani bilgan har kimga ochiq, muddatsiz; bekor qilib bo'lmaydi. Uy vazifasi fayllari kelajakda shu yerga tushsa — ular ham ochiq | Rasm uchun ochiq qoldirish mumkin; hujjatlar uchun imzolangan (HMAC + expiry) URL yoki autentifikatsiyali yuklab olish |
| H-13 | P2 | Fayl tekshiruvi faqat kengaytma + mijoz MIME | `FileStorageService.java:125-139` (magic-bytes yo'q) | Soxta rasm saqlanadi (beriluvchi Content-Type kengaytmadan + `nosniff` — xavf past) | Magic-bytes tekshiruvi (ImageIO o'qish) |
| H-14 | P2 | Upload xatosida ichki xabar chiqadi | `FileController.java:46` `"...: " + e.getMessage()` | IOException matnida server yo'li ko'rinishi mumkin | Umumiy xabar, tafsilot logga |
| H-15 | P2 | Upload kvotasi/egasi yo'q, yetim fayllar | `FileController.java:30-48` (ST/P ham yuklay oladi), o'chirish endpointi yo'q | Disk to'lishi | Rate-limit, egasi bilan metadata jadvali, tozalash jobi |

Path traversal: xavf topilmadi — `{filename}` bitta segment, `normalize()` + `startsWith(uploadPath)` (`FileStorageService.java:160-182`).

### 5.4 billing-v2 / payroll-v2 / dashboard bilan bog'liqlik

Bog'liqlik YO'Q: `HomeworkRepository`/`HomeworkSubmissionRepository` faqat `HomeworkService` da ishlatiladi (grep: boshqa iste'molchi yo'q). `countByIsActiveTrue` (`HomeworkRepository.java:15`) hech qayerda chaqirilmaydi. Payroll KPI/teacher KPI uy vazifasini hisobga olmaydi.

### 5.5 Eski frontend nima ishlatadi

`adizone-crm-front/src/services/homeworkService.js`:
- `getAll` → `GET /api/homework` (`:4`), `class-home-work.vue:211,220` `{page:0,size:50}` — pagination UI yo'q, 50 dan keyingilar ko'rinmaydi.
- `getByGroup` → `GET /api/homework/group/{id}` (`:9`, `class-home-work.vue:246`) — parametrsiz (backend default 50).
- `create` → `POST` (`:6`, `class-home-work.vue:260`) `{title, description, groupId, dueDate, marks}` — mos.
- `remove` → `DELETE` (`:8`, `class-home-work.vue:274`) — o'chirish tugmasi rolga qarab yashirilmagan (`class-home-work.vue:95-102`), T bosganda **403**. NOMUVOFIQ.
- `update`, `getById`, `getSubmissions` (`:5,7,10`) — servisda bor, lekin hech bir sahifa chaqirmaydi. `POST/PUT submissions` uchun FE funksiyasi umuman yo'q → baholash UI yo'q.
- Yangi admin: `src/shared/permissions/matrix.ts:229-236` (`/homework`, SA,A,T) — modul hali yo'q (`src/modules/` da `homework` papkasi yo'q).

### 5.6 Yetishmayotgan narsalar (edutizim.uz uslubi)

- P1: Guruh o'quvchilari × vazifa matritsasi (kim topshirdi/topshirmadi, kechikdi) — endpoint yo'q.
- P1: Vazifaga fayl biriktirish (PDF/DOCX/rasm) va o'quvchi topshirig'iga fayl (H-04).
- P1: Baholash oqimi: `GRADED` holati, `gradedBy/gradedAt`, ball shkalasi validatsiyasi (H-06).
- P2: Muddat eslatmasi (Telegram/SMS ota-onaga, `@Scheduled` — infratuzilma bor: `service/PaymentReminderService.java:42`), muddati o'tganlarni `LATE/MISSING` ga avtomatik o'tkazish.
- P2: O'quvchi/ota-ona portali (ST/P rollari mavjud — `entity/enums/UserRole.java`, `controller/UserController.java:118-146`).
- P2: Filtrlar (`dueDate` oralig'i, `subjectId`, holat), dars (lesson/timetable) bilan bog'lash, statistik hisobot (o'qituvchi KPI ga).

### 5.7 DB tekshiruvi uchun SQL (read-only)

```sql
-- 5.7.1 Hajm va yetim vazifalar (guruhsiz VA o'qituvchisiz — hech bir T ko'rmaydi)
SELECT count(*) total,
       count(*) FILTER (WHERE is_active) active,
       count(*) FILTER (WHERE group_id IS NULL) no_group,
       count(*) FILTER (WHERE teacher_id IS NULL) no_teacher,
       count(*) FILTER (WHERE group_id IS NULL AND teacher_id IS NULL) orphan
FROM homeworks;

-- 5.7.2 Vazifa o'qituvchisi guruh o'qituvchisidan farq qiladi (H-07/H-08)
SELECT h.id, h.title, h.teacher_id, g.teacher_id AS group_teacher_id
FROM homeworks h JOIN groups g ON g.id = h.group_id
WHERE h.teacher_id IS DISTINCT FROM g.teacher_id;

-- 5.7.3 Guruhda (hech qachon) bo'lmagan o'quvchining topshirig'i (H-02)
SELECT s.id, s.homework_id, s.student_id, h.group_id
FROM homework_submissions s JOIN homeworks h ON h.id = s.homework_id
WHERE h.group_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM student_groups sg
                  WHERE sg.student_id = s.student_id AND sg.group_id = h.group_id);

-- 5.7.4 Ball anomaliyalari, holatlar taqsimoti, tashqi fileUrl (H-05/H-06)
SELECT s.status, count(*),
       count(*) FILTER (WHERE s.marks_obtained < 0 OR s.marks_obtained > h.marks) bad_marks,
       count(*) FILTER (WHERE s.file_url IS NOT NULL AND s.file_url NOT LIKE '/api/files/%') ext_url,
       count(*) FILTER (WHERE NOT h.is_active) on_deleted_hw
FROM homework_submissions s JOIN homeworks h ON h.id = s.homework_id
GROUP BY s.status;

-- 5.7.5 Cheklovlar va indekslar (UNIQUE(homework_id,student_id) haqiqatda bormi, FK indekslari)
SELECT conrelid::regclass, conname, pg_get_constraintdef(oid)
FROM pg_constraint WHERE conrelid IN ('homeworks'::regclass, 'homework_submissions'::regclass);
SELECT tablename, indexname, indexdef FROM pg_indexes
WHERE tablename IN ('homeworks', 'homework_submissions');
```

**Lokal bazadagi natija:** `homeworks` va `homework_submissions` bo'sh. 5.7.5 natijasi yuqorida, §5.2 da.

### 5.8 Xulosa

Holat: **Qisman** — admin/o'qituvchi uchun CRUD ishlaydi, lekin baholash/topshiriq oqimida egalik teshiklari bor, fayl biriktirish va o'quvchi oqimi yo'q. P0: 0, P1: 4 (H-01..H-04), P2: 11 (H-05..H-15).

---

## 6. E'lonlar

### 6.1 Endpointlar

URL qoidalari: `GET /api/notices/**` → AUTH (`SC:113`); `POST /api/notices/read-all`, `POST /api/notices/*/read` → AUTH (`SC:230-231`); boshqa `POST/PUT /api/notices/**` → SA,A (`SC:232-235`); DELETE → `SC:243` SA,A. AUTH ichiga ST va P ham kiradi (`UserController.java:118-146` ST foydalanuvchi yaratadi).

| Metod | Yo'l | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| GET | `/api/notices` | AUTH (`NoticeController.java:25-31`) | `page=0`, `size=20` (chegarasiz) | `ApiResponse<PageResponse<NoticeResponse>>`, `createdAt DESC` | **Barcha** e'lonlar: qoralama, nofaol, muddati o'tgan; auditoriya filtri yo'q (`NoticeService.java:40-51`) |
| GET | `/api/notices/active` | AUTH (`:34-39`) | `limit=50` (1..50 ga siqiladi `NoticeService.java:62`) | `ApiResponse<List<NoticeResponse>>` | `isActive && isPublished && (expiresAt IS NULL OR expiresAt >= bugun 00:00)` (`NoticeRepository.java:21-28`) |
| GET | `/api/notices/latest` | AUTH (`:41-46`) | `limit=5` | `ApiResponse<List<NoticeResponse>>` | `/active` bilan bir xil metod |
| GET | `/api/notices/unread-count` | AUTH (`:48-53`) | — | `ApiResponse<Map>` → `data: {"count": N}` | Bitta COUNT + NOT EXISTS (`NoticeRepository.java:39-49`) — N+1 yo'q |
| POST | `/api/notices/read-all` | AUTH (`:55-60`) | — | `ApiResponse<Void>` | Faqat faol e'lonlar (`NoticeService.java:163-177`) |
| POST | `/api/notices/{id:\d+}/read` | AUTH (`:62-67`) | — | `ApiResponse<Void>` | Idempotent (check-then-insert `:150-160`); qoralamani ham "o'qiladi" |
| GET | `/api/notices/{id:\d+}` | AUTH (`:69-73`) | — | `ApiResponse<NoticeResponse>` | Har qanday e'lon, jumladan qoralama (`NoticeService.java:54-57`) |
| POST | `/api/notices` | SA,A (`:75-80`) | `NoticeRequest`: `title` @NotBlank, `content` @NotBlank, `noticeDate`, `publishedTo`, `noticeType`, `targetRole`, `isActive`, `isPublished`, `publishedAt`, `expiresAt`, `expiryDate`, `createdById` (`dto/request/NoticeRequest.java:10-28`) | **201** `ApiResponse<NoticeResponse>` | Defaultlar: `publishedTo="ALL"`, `noticeType="GENERAL"`, `isPublished = published && active`, `publishedAt=now` (`NoticeService.java:72-90`); `expiresAt` ustun, aks holda `expiryDate 23:59:59` (`:191-199`) |
| PUT | `/api/notices/{id:\d+}` | SA,A (`:82-87`) | `NoticeRequest` | `ApiResponse<NoticeResponse>` | `targetRole` va `expiresAt` yuborilmasa **NULL ga yoziladi** (`NoticeService.java:119,129`) |
| DELETE | `/api/notices/{id:\d+}` | SA,A (`:89-95` + `SC:243`) | — | `ApiResponse<Void>` | Hard delete, avval `notice_reads` bulk DELETE (`NoticeService.java:141-147`), `@Audited` |

`NoticeResponse` (`dto/response/NoticeResponse.java:13-32`): `id, uuid, title, content, noticeDate, publishedTo, noticeType, targetRole, isActive, isPublished, publishedAt, expiresAt, expiryDate (expiresAt.toLocalDate()), isExpired, isRead, createdByName (username!), createdAt`.

### 6.2 Ma'lumot modeli

- **Notice** → `notices` (`entity/Notice.java:11-66`, `BaseEntity`). `uuid` UNIQUE, `title` varchar(255) NOT NULL, `content` TEXT NOT NULL, `notice_date` date, `published_to` varchar(30) default `"ALL"` (izoh: `ALL, TEACHERS, STUDENTS, PARENTS` `:37`), `notice_type` varchar(30) `"GENERAL"`, `target_role` varchar(30), `is_active`, `is_published`, `published_at` timestamp, `expires_at` timestamp, `created_by` → users (LAZY, `:63-65`). Jadval `ddl-auto` bilan yaratilgan (migratsiya yo'q). **Lokal bazada** bitta ustunli indekslar bor: `idx_notices_expires_at`, `idx_notices_is_published`, `idx_notices_published_at`, `idx_notices_target_role`. Kompozit indeks yo'q. Yana ikkita dublikat:
- UNIQUE(uuid) ikki nusxada;
- `created_by` FK ikki nusxada (SET NULL + NO ACTION).
- **NoticeRead** → `notice_reads` (`entity/NoticeRead.java:9-39`; V41 `src/main/resources/db/migration/V41__notice_reads.sql:3-12`): `notice_id` NOT NULL FK → notices (ON DELETE yo'q), `user_id` NOT NULL FK → users, `read_at` NOT NULL default NOW(), `UNIQUE(notice_id,user_id)` `uk_notice_reads_notice_user`, indekslar `idx_notice_reads_user`, `idx_notice_reads_notice`. `BaseEntity` emas. **Lokal bazada** V41 qisman qo'llangan: `uk_notice_reads_notice_user` va ikkala FK (Hibernate, NO ACTION) bor, lekin `idx_notice_reads_user` / `idx_notice_reads_notice` **yo'q**.
- Foydalanuvchi o'chirish soft (`UserController.java:221-231`), shuning uchun `notice_reads.user_id` FK muammo bermaydi.
- Timezone: JVM default `Asia/Tashkent` (`CrmApplication.java:18,23`), `hibernate.jdbc.time_zone` ham (`application.yml:40`) — `LocalDate.now()` to'g'ri kunni beradi.

### 6.3 Topilgan xatolar va xavflar

| ID | P | Sarlavha | Dalil | Oqibat | Tavsiya |
|---|---|---|---|---|---|
| N-01 | P1 | Auditoriya (rol) filtri umuman ishlamaydi | `publishedTo`/`targetRole` faqat saqlanadi va qaytariladi (`NoticeService.java:83-85,217-218`); hech bir so'rovda shart yo'q (`NoticeRepository.java:21-49`) | "Faqat o'qituvchilarga" e'lon SM/ACC/ST/P ga ham ko'rinadi, `unread-count` ularga ham sanaydi | `publishedTo` ni rol to'plamiga (`notice_target_roles` yoki `text[]`) aylantirish; `findActiveNotices`/`countUnreadForUser`/`markAllRead` ga `:role` sharti |
| N-02 | P1 | Qoralama/nofaol e'lonlar barcha rollarga ochiq | `GET /api/notices` → `findAll` (`NoticeService.java:42`), `GET /{id}` (`:54-57`); URL AUTH (`SC:113`) | Chop etilmagan matn (ST/P ham) o'qiladi | Admin ro'yxatini SA,A ga cheklash (`@PreAuthorize`) va filtrlar (`status=active/draft/expired`, `q`); oddiy foydalanuvchiga faqat `/active` |
| N-03 | P2 | Rejalashtirilgan (`publishedAt` kelajakda) e'lon darhol chiqadi | `NoticeRepository.java:21-27` `publishedAt <= now` sharti yo'q | Rejalashtirish ishlamaydi | `AND (n.publishedAt IS NULL OR n.publishedAt <= :now)` |
| N-04 | P2 | Muddat semantikasi: `expiresAt` vaqti e'tiborsiz | So'rov `expiresAt >= bugun 00:00` (`NoticeRepository.java:25`), `isExpired` faqat sana (`NoticeService.java:229-234`) | `expiresAt=10:00` bo'lsa e'lon kun oxirigacha ko'rinadi | Yoki faqat `expiryDate` (LocalDate) qoldirish, yoki `expiresAt >= now` |
| N-05 | P2 | `publishedTo`/`targetRole`/`noticeType` validatsiyasiz erkin satr, ikki xil auditoriya maydoni | `NoticeRequest.java:17-19`; `Notice.java:37-47` (`TEACHERS` ≠ `UserRole.TEACHER`) | Noto'g'ri qiymatlar; 30 belgidan uzun → 500 | Enum + `@Pattern`; bitta maydon |
| N-06 | P2 | PUT qisman yangilashda `targetRole`/`expiresAt` o'chib ketadi | `NoticeService.java:119,129` | Eski FE `targetRole`/`expiresAt` yubormaydi → har tahrirda tozalanadi | Faqat `!= null` bo'lsa yozish yoki PATCH semantikasi |
| N-07 | P2 | `read`/`read-all` poygasi → 500 | check-then-insert (`NoticeService.java:153-158,165-176`), UNIQUE (V41:8), `DataIntegrityViolationException` handleri yo'q (`GlobalExceptionHandler.java:210-219`) | Ikki tab bir vaqtda → 500 | `INSERT ... ON CONFLICT DO NOTHING` (native) |
| N-08 | P2 | `isRead` uchun foydalanuvchining BARCHA o'qigan id'lari yuklanadi; `createdBy` LAZY | `NoticeService.java:201-208` (chegarasiz ro'yxat), `:225` (har noyob muallif uchun 1 so'rov) | Vaqt o'tishi bilan sekinlashadi (klassik N+1 emas, lekin o'sib boradi) | `findReadNoticeIds(userId, :noticeIds)`; `JOIN FETCH n.createdBy` |
| N-09 | P2 | Muallifni soxtalashtirish | `createdById` (`NoticeRequest.java:27`, `NoticeService.java:92-94`) | Admin boshqa user nomidan e'lon qiladi | `createdById` ni olib tashlash |
| N-10 | P2 | `markAllRead` qatorma-qator INSERT | `NoticeService.java:167-176` (IDENTITY → batch yo'q) | Ko'p e'londa sekin | Bitta `INSERT ... SELECT ... ON CONFLICT DO NOTHING` |
| N-11 | P2 | Admin ro'yxatida `size` chegarasiz; yaratish/tahrir audit qilinmaydi | `NoticeController.java:29`; `@Audited` faqat delete (`NoticeService.java:142`) | | `min(size,100)`; create/update ga `@Audited` |
| N-12 | P2 | Dashboard e'lonlari alohida nusxa-mapping | `service/AnalyticsService.java:105-118` (`isRead`, `targetRole` yo'q, auditoriya filtri yo'q) | Ikki joyda mantiq ajraladi | `NoticeService.getLatestNotices` ni qayta ishlatish |

### 6.4 billing-v2 / payroll-v2 / dashboard bilan bog'liqlik

- Billing-v2 / payroll-v2: bog'liqlik YO'Q.
- Dashboard: eski analytics dashboardi `noticeRepository.findActiveNotices(..., PageRequest.of(0,5))` ni chaqiradi (`service/AnalyticsService.java:36,105-118`) — `latestNotices` maydoni. Direktor dashboardi (`dashboard/*`) e'lonlardan foydalanmaydi (grep). Direktor Telegram digest infratuzilmasi bor (`dashboard/DirectorDigestService.java:46`) — e'lonlarni Telegram'ga yuborishda qayta ishlatish mumkin (TAXMIN).

### 6.5 Eski frontend nima ishlatadi

`adizone-crm-front/src/services/noticeService.js`:
- `getAll` → `GET /api/notices` (`:4-6`); `notice-list.vue:194` **parametrsiz** → faqat birinchi 20 ta, sahifalash UI yo'q. NOMUVOFIQ.
- `getLatest(params={page:0,size:10})` / `getActive` → `GET /api/notices/active` (`:8-13`); `layout-header.vue:462`, `admin-dashboard.vue:570`, `admin-banner.vue:75` — **`page/size` yuboriladi, backend `limit` kutadi** → har doim 50 ta qaytadi. NOMUVOFIQ.
- `getUnreadCount` → `/unread-count` (`:32-34`); `layout-header.vue:440` `raw.count ?? raw.unreadCount ?? ...` — `count` mos.
- `markRead`/`markAllRead` (`:26-31`; `layout-header.vue:471,540`) — mos.
- `create/update` (`notice-list.vue:265-276`) payload: `{title, content, noticeDate, expiryDate, publishedTo, isActive}` — `isPublished`, `targetRole`, `noticeType`, `expiresAt` yuborilmaydi → update'da `targetRole`/`expiresAt` NULL (N-06; `expiryDate` yuborilgani uchun `expiresAt` qayta tiklanadi). `publishedTo` tanlovi `ALL/TEACHERS/STUDENTS` (`notice-list.vue:137-141`) — backend uni ishlatmaydi (N-01), `PARENTS` yo'q.
- `/notices` sahifasi faqat SA,A (`router/index.js:211-213`), qo'ng'iroqcha barcha rollarda.
- Yangi admin: `matrix.ts:155-162` (`/notices`, SA,A), modul hali yo'q.

### 6.6 Yetishmayotgan narsalar

- P1: Rol/guruh/filial bo'yicha auditoriya (masalan "1-guruh ota-onalariga") — N-01.
- P1: Admin ro'yxati uchun filtrlar va server-side pagination (holat, qidiruv, sana).
- P2: Telegram/SMS kanaliga yuborish (yuborish holati jurnali bilan), push/WebSocket orqali real-time bell (STOMP infratuzilmasi bor — `SC:99`).
- P2: Rejalashtirilgan nashr (N-03), muhim e'lonni "pin" qilish, ilova (rasm/PDF).
- P2: O'qilganlik statistikasi (kim o'qidi/o'qimadi — `notice_reads` asosida, admin uchun).

### 6.7 DB tekshiruvi uchun SQL

```sql
-- 6.7.1 Auditoriya/tur qiymatlari — qanday erkin satrlar to'plangan (N-05)
SELECT published_to, target_role, notice_type, count(*)
FROM notices GROUP BY 1,2,3 ORDER BY 4 DESC;

-- 6.7.2 Holatlar: qoralama, nofaol, muddati o'tgan, kelajakdagi nashr, muallifsiz (N-02/N-03)
SELECT count(*) total,
       count(*) FILTER (WHERE NOT is_published) draft,
       count(*) FILTER (WHERE NOT is_active) inactive,
       count(*) FILTER (WHERE expires_at < date_trunc('day', now())) expired,
       count(*) FILTER (WHERE published_at > now()) scheduled,
       count(*) FILTER (WHERE created_by IS NULL) no_author
FROM notices;

-- 6.7.3 O'qilganlik: rol bo'yicha o'qishlar (kim "begona" e'lonni o'qigan — N-01 izi)
SELECT n.published_to, u.role, count(*)
FROM notice_reads r JOIN notices n ON n.id = r.notice_id JOIN users u ON u.id = r.user_id
GROUP BY 1,2 ORDER BY 1,2;

-- 6.7.4 Nofaol foydalanuvchilarning o'qish yozuvlari va eng ko'p yozuvli userlar (N-08 hajmi)
SELECT u.id, u.username, u.is_active, count(*) reads
FROM notice_reads r JOIN users u ON u.id = r.user_id
GROUP BY u.id, u.username, u.is_active ORDER BY reads DESC LIMIT 20;

-- 6.7.5 Cheklovlar/indekslar va V41 qo'llanganmi
SELECT conrelid::regclass, conname, pg_get_constraintdef(oid)
FROM pg_constraint WHERE conrelid IN ('notices'::regclass, 'notice_reads'::regclass);
SELECT tablename, indexname, indexdef FROM pg_indexes WHERE tablename IN ('notices','notice_reads');
SELECT version, description, success FROM flyway_schema_history WHERE version = '41';
```

**Lokal bazadagi natija:**
- `notices` va `notice_reads` bo'sh.
- `flyway_schema_history` 24-versiyada to'xtagan. V41 u yerda yo'q, chunki V25+ qo'lda bajariladi (X-01).
- Cheklov va indekslar holati §6.2 da.

### 6.8 Xulosa

Holat: **Qisman** — bell (active/unread/read) ishlaydi, lekin auditoriya filtri yo'q va qoralamalar hammaga ochiq. P0: 0, P1: 2 (N-01, N-02), P2: 10 (N-03..N-12).

---

## 7. Shartnomalar

### 7.1 Endpointlar

URL: `/api/contracts`, `/api/contracts/**`, `/api/contract-templates`, `/api/contract-templates/**` → SA,A (`SC:173-175`); class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")` (`ContractController.java:25`). Base path `/api` (`:23`). Egalik tekshiruvi kerak emas (faqat admin).

| Metod | Yo'l | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| GET | `/api/contract-templates` | SA,A (`:30-33`) | — | `ApiResponse<List<ContractTemplateDto>>` (sahifasiz) | |
| GET | `/api/contract-templates/{id}` | SA,A (`:35-38`) | — | `ApiResponse<ContractTemplateDto>` | |
| POST | `/api/contract-templates` | SA,A (`:40-45`) | `ContractTemplateCreateDto`: `title`, `type` (`OFFLINE/OFFER`), `content`, `isDefault` — **validatsiya YO'Q** (`dto/request/ContractTemplateCreateDto.java:6-12`) | **201** `ApiResponse<ContractTemplateDto>` | `isDefault=true` → boshqasini tozalaydi (`ContractService.java:58-65`) |
| PUT | `/api/contract-templates/{id}` | SA,A (`:47-53`) | shu DTO, `null` maydonlar o'zgarmaydi (`ContractService.java:262-275`) | `ApiResponse<ContractTemplateDto>` | |
| DELETE | `/api/contract-templates/{id}` | SA,A (`:55-59`) | — | `ApiResponse<Void>` | Hard delete (`ContractService.java:78-80`) |
| GET | `/api/contracts` | SA,A (`:61-71`) | query `studentId?`, `status?` (noto'g'ri qiymat jim e'tiborsiz `ContractService.java:305-314`), `page=0`, `size=20`; sort `contractDate DESC, createdAt DESC` | `ApiResponse<PageResponse<ContractDto>>` (`pageNumber` `Page` dan) | Specification — null-param JPQL muammosi yo'q (`:293-303`) |
| GET | `/api/contracts/student/{studentId}` | SA,A (`:73-76`) | — | `ApiResponse<List<ContractDto>>` (sahifasiz) | |
| GET | `/api/contracts/{id}` | SA,A (`:78-81`) | — | `ApiResponse<ContractDto>` | |
| POST | `/api/contracts/generate` | SA,A (`:83-88`) | `ContractCreateDto`: `studentId` (qo'lda tekshiriladi → 400), `templateId?` (null → default shablon, yo'q bo'lsa 400) (`dto/request/ContractCreateDto.java:6-10`, `ContractService.java:111-133`) | **201** `ApiResponse<ContractDto>` | Raqam `CTR-%04d` = `count()+1` (`:167-170`), status DRAFT |
| PATCH | `/api/contracts/{id}/accept-offer` | SA,A (`:90-94`) | — | `ApiResponse<ContractDto>` | Faqat `type=OFFER`, aks holda 400 (`ContractService.java:136-145`) |
| PATCH | `/api/contracts/{id}/sign` | SA,A (`:96-100`) | — | `ApiResponse<ContractDto>` | Shartsiz `SIGNED` (`:147-152`) |
| DELETE | `/api/contracts/{id}` | SA,A (`:102-106`) | — | `ApiResponse<Void>` | Hard delete, imzolangani ham (`:154-157`) |

`ContractDto` (`dto/response/ContractDto.java:17-32`): `id, uuid (String), contractNumber, studentId, studentName, templateId, templateTitle, type, renderedContent, status, offerAccepted, acceptedAt, contractDate, createdAt`.
`ContractTemplateDto` (`dto/response/ContractTemplateDto.java:15-23`): `id, uuid, title, type, content, isDefault, createdAt`.

### 7.2 Ma'lumot modeli

- **Contract** → `contracts` (`entity/Contract.java:14-84`, `BaseEntity` EMAS — `@PrePersist/@PreUpdate` `:65-83`). `uuid` varchar(36) UNIQUE, `contract_number` varchar(32) **UNIQUE** NOT NULL (`:28-29`), `student_id` NOT NULL FK (LAZY, `:31-33`), `template_id` NOT NULL FK (LAZY, `:35-37`), `type` varchar(20) enum STRING, `rendered_content` TEXT, `status` varchar(20) enum default DRAFT, `offer_accepted` bool, `accepted_at`, `contract_date` date NOT NULL, `created_at`, `updated_at`. **Guruh/yozilma (student_group), summa, davr, imzolovchi, imzo sanasi YO'Q.**
- **ContractTemplate** → `contract_templates` (`entity/ContractTemplate.java:12-61`): `uuid`, `title` NOT NULL (`:26-27`), `type` default OFFLINE, `content` TEXT, `is_default` bool — "bitta default" faqat kodda, DB da partial unique index yo'q.
- Enumlar: `ContractStatus {DRAFT, SIGNED, ACCEPTED}` (`entity/enums/ContractStatus.java`), `ContractType {OFFLINE, OFFER}` (`entity/enums/ContractType.java`). Hibernate yaratgan enum CHECK'lar startupda o'chiriladi (`config/EnumCheckConstraintCleaner.java:36-42`, `application.yml:66`).
- Migratsiya yo'q (`ddl-auto`). Cascade yo'q.
- PDF: `pom.xml` da PDF kutubxonasi YO'Q (iText/OpenHTMLtoPDF/Flying Saucer yo'q); faqat `poi`/`poi-ooxml` (`pom.xml:120,130`) — DOCX generatsiyasi texnik jihatdan mumkin, lekin ishlatilmaydi. Chop etish faqat brauzerda (`adizone-crm-front/src/views/pages/settings/contracts.vue:307-376`).

### 7.3 Topilgan xatolar va xavflar

| ID | P | Sarlavha | Dalil | Oqibat | Tavsiya |
|---|---|---|---|---|---|
| C-01 | **P0** | Raqamlash `count()+1` — o'chirishdan keyin generatsiya doimiy 500 | `ContractService.java:167-170`; UNIQUE `Contract.java:28`; hard delete `ContractService.java:154-157`; `DataIntegrityViolationException` handleri yo'q (`GlobalExceptionHandler.java:210-219`) | Masalan CTR-0001..0003 dan 0002 o'chirilsa: `count=2` → `CTR-0003` (bor) → unique violation → **har keyingi `generate` 500**, toki yangi yozuv qo'shilmaguncha (qo'shilmaydi). Parallel generatsiyada ham to'qnashuv | PostgreSQL `SEQUENCE` (`nextval`) yoki `max(seq)+1` + qulf; raqamni yil prefiksi bilan (`CTR-2026-00001`); imzolangan shartnomani o'chirmaslik |
| C-02 | P1 | Ishlatilgan shablonni o'chirish → 500 | `ContractService.java:78-80`; `contracts.template_id NOT NULL` FK (`Contract.java:35-37`) | Admin "o'chirish" bossa 500 | Soft-archive (`isActive`) yoki oldindan tekshirib 409 |
| C-03 | P1 | Shablon DTO validatsiyasiz | `ContractTemplateCreateDto.java:6-12` (@NotBlank yo'q), `title NOT NULL` (`ContractTemplate.java:26`) | `title` siz POST → DB xatosi → 500 | `@NotBlank title`, `@NotNull type`, `@NotBlank content`, `@Size` |
| C-04 | P1 | Shartnomadagi narx billing-v2 dan farq qiladi | `resolveMonthlyFee` (`ContractService.java:241-249`) vs `EnrollmentPricing.monthlyFee/effectiveMonthlyFee` (`billing/EnrollmentPricing.java:25-34,72-74`): override `0` bo'lsa shartnoma "0" ko'rsatadi (billing kurs narxiga tushadi), **chegirma qo'llanmaydi**, `PER_LESSON` (`lessonPrice`) e'tiborsiz | Shartnomadagi `{{monthlyFee}}` haqiqiy hisoblanadigan summadan farq qiladi — huquqiy/moliyaviy nizo | `EnrollmentPricing.effectiveMonthlyFee(sg)` / `effectiveLessonPrice` ni ishlatish; `{{discount}}`, `{{paymentType}}`, `{{lessonPrice}}` placeholderlari |
| C-05 | P1 | Ko'p guruhli o'quvchida guruh tasodifiy tanlanadi; shartnoma yozilmaga bog'lanmaydi | `findActiveByStudentId(...).findFirst()` ORDER BY'siz (`ContractService.java:222-230`, `repository/StudentGroupRepository.java:26-28`); `Contract` da `student_group_id`/`group_id` yo'q (`Contract.java:31-37`) | Noto'g'ri guruh/narx; billing shartnomani yozilma bilan bog'lay olmaydi | `ContractCreateDto` ga `studentGroupId` (yoki `groupId`), `contracts` ga FK + narx snapshot ustunlari |
| C-06 | P1 | Holat mashinasi yo'q | `markSigned` shartsiz (`ContractService.java:147-152`), `acceptOffer` takrorlanadi va SIGNED ni ACCEPTED ga qaytaradi (`:136-145`); `signedAt/signedBy` yo'q | Imzolangan shartnoma holati qayta yoziladi, audit izi yo'q | DRAFT→SIGNED/ACCEPTED, faqat DRAFT dan; `signedAt`, `signedBy`, `CANCELLED` holati; imzolanganini o'chirish taqiqi |
| C-07 | P2 | Placeholder qiymatlari HTML-escape qilinmaydi | `renderContent` oddiy `String.replace` (`ContractService.java:172-179`) | Eski FE matn sifatida chiqaradi (`contracts.vue:189` `{{ }}`, print'da escape `contracts.vue:310-314`) — XSS yo'q. Yangi admin `v-html` ishlatsa, o'quvchi ismi (lead → student, `POST /api/leads/public` ochiq `SC:87`) orqali stored-XSS (TAXMIN) | Kontent turini aniqlash (plain text) yoki serverda escape + sanitizer; FE da `v-html` taqiqi |
| C-08 | P2 | Placeholderlar ro'yxati qattiq kodlangan, `{{studentPassport}}` doim bo'sh | `ContractService.java:181-220`, `:185`; FE ro'yxati alohida (`contract-templates.vue:216-228`, `studentPassport` yo'q); noma'lum `{{x}}` jim qoladi | Shablon xatolari sezilmaydi | `GET /api/contract-templates/placeholders`; noma'lum placeholder → ogohlantirish; pasport maydoni Student'ga |
| C-09 | P2 | Bir nechta default shablon → 500 | `findByIsDefaultTrue` `Optional` (`ContractTemplateRepository.java:14`), DB da partial unique yo'q; tozalash poygasi (`ContractService.java:277-291`) | `IncorrectResultSizeDataAccessException` → `generate` 500 | `CREATE UNIQUE INDEX ... ON contract_templates(is_default) WHERE is_default` |
| C-10 | P2 | Noto'g'ri `status` filtri jim e'tiborsiz | `ContractService.java:305-314` | FE `PENDING/OFFER_*` yuboradi → hammasi qaytadi (7.5) | 400 qaytarish |
| C-11 | P2 | N+1 ro'yxatda | `toContractDto` LAZY `student`, `template` (`ContractService.java:352-359`) | 20 qatorga ~21-40 so'rov | `@EntityGraph(attributePaths={"student","template"})` |
| C-12 | P2 | Audit/muallif yo'q, markaz nomi qattiq kodlangan | `Contract`/`ContractTemplate` `BaseEntity`/`createdBy`siz; `@Audited` yo'q; `CENTER_NAME="Adizone"` (`ContractService.java:35`) | Kim yaratdi/imzoladi/o'chirdi — noma'lum | `@Audited`, `createdBy`; markaz rekvizitlari settings'dan |
| C-13 | P2 | `getByStudent`, shablonlar ro'yxati sahifasiz | `ContractController.java:30-33,73-76` | Hajm kichik — past xavf | — |

### 7.4 billing-v2 / payroll-v2 / dashboard bilan bog'liqlik

- billing-v2: **to'g'ridan-to'g'ri bog'liqlik yo'q** — billing (`billing/*`) `ContractRepository` ni ishlatmaydi (grep), shartnoma narxi hisob-kitobga ta'sir qilmaydi. Shartnoma narxni o'zi hisoblaydi (C-04): billing-v2 `EnrollmentPricing` (override>0 → kurs narxi, chegirma, PER_LESSON), shartnoma esa override (0 ham) → kurs narxi, chegirmasiz. `BillingMigrationService.java:260` migratsiyada `monthlyPriceOverride=null` qiladi — migratsiyadan keyin yaratilgan shartnomalar kurs narxini ko'rsatadi; oldingi shartnomalar `renderedContent` da eski narx bilan qoladi (snapshot matnda, ustunda emas).
- payroll-v2 / director dashboard: bog'liqlik YO'Q.

### 7.5 Eski frontend nima ishlatadi

`adizone-crm-front/src/services/contractService.js` — barcha 12 endpoint (`:4-39`), URL'lar mos:
- `getAll(params)` (`contracts.vue:385`) — faqat `studentId`, `status`; **page/size yo'q** → faqat 20 ta, sahifalash yo'q. NOMUVOFIQ.
- Status ro'yxati `['DRAFT','PENDING','SIGNED','OFFER_PENDING','OFFER_ACCEPTED']` (`contracts.vue:244`) — backendda `PENDING/OFFER_PENDING/OFFER_ACCEPTED` yo'q, `ACCEPTED` esa ro'yxatda yo'q → filtr jim ishlamaydi (C-10), `ACCEPTED` badge default `cn-st-draft` (`contracts.vue:271-280`). NOMUVOFIQ. `isOfferAccepted` `offerAccepted` bilan qutqariladi (`:300`).
- `generate({studentId, templateId})` (`contracts.vue:422-425`; `student-details.vue:1370` `templateId:null`) — mos. `student-details` SM/ACC ga ham ochiq bo'lsa, shartnoma tabi 403 oladi (TAXMIN — sahifa rollari `frontend-audit.md:1346,1363`).
- `sign` faqat `OFFLINE && status!=='SIGNED'` (`contracts.vue:99`) — backend cheklamaydi (C-06).
- Shablon: `createTemplate/updateTemplate(form)` `{title, type, isDefault, content}` (`contract-templates.vue:328-331`) — mos; placeholder ro'yxati FE da qattiq (`:216-228`).
- PDF: server yo'q, `window.open` + `print()` (`contracts.vue:307-376`).
- Yangi admin: `matrix.ts:490-505` (`/settings/contracts`, `/settings/contract-templates`, SA,A), modul hali yo'q.

### 7.6 Yetishmayotgan narsalar

- P1: Ishonchli avtomatik raqamlash (sequence, yil prefiksi) — C-01.
- P1: Shartnomani yozilma (student_group) va narx snapshoti (summa, chegirma, to'lov turi, boshlanish/tugash sanasi) bilan bog'lash — C-04/C-05.
- P1: Server tomonda PDF (OpenHTMLtoPDF yoki DOCX→PDF; `poi-ooxml` allaqachon bor) va saqlangan nusxa.
- P2: Elektron imzo/oferta qabuli (SMS-kod yoki Telegram tasdiq, `acceptedIp`, `acceptedBy`), imzolangan nusxa fayli.
- P2: Shablon versiyalash, placeholderlar API (C-08), ota-ona/pasport rekvizitlari, markaz rekvizitlari settings'da.
- P2: Shartnoma muddati/yangilash, bekor qilish (`CANCELLED`), o'quvchi chiqib ketganda (`student_exit`) avtomatik yopish.

### 7.7 DB tekshiruvi uchun SQL

```sql
-- 7.7.1 C-01: keyingi /generate to'qnashadimi? (true → hozir 500 beradi)
WITH c AS (SELECT count(*) + 1 AS n FROM contracts)
SELECT c.n,
       'CTR-' || CASE WHEN c.n < 10000 THEN lpad(c.n::text, 4, '0') ELSE c.n::text END AS next_number,
       EXISTS (SELECT 1 FROM contracts x
               WHERE x.contract_number = 'CTR-' || CASE WHEN c.n < 10000 THEN lpad(c.n::text, 4, '0') ELSE c.n::text END)
         AS next_generate_will_fail,
       (SELECT max(substring(contract_number FROM 'CTR-(\d+)')::int) FROM contracts) AS max_seq
FROM c;

-- 7.7.2 C-09: bir nechta default shablon; C-02: o'chirib bo'lmaydigan shablonlar
SELECT t.id, t.title, t.type, t.is_default, count(c.id) AS contracts
FROM contract_templates t LEFT JOIN contracts c ON c.template_id = t.id
GROUP BY t.id ORDER BY t.is_default DESC, contracts DESC;

-- 7.7.3 C-06: holat/tur nomuvofiqliklari
SELECT type, status, offer_accepted, count(*),
       count(*) FILTER (WHERE status = 'ACCEPTED' AND type <> 'OFFER') accepted_not_offer,
       count(*) FILTER (WHERE offer_accepted AND status <> 'ACCEPTED') accepted_flag_mismatch
FROM contracts GROUP BY 1,2,3;

-- 7.7.4 C-05: shartnomasi bor, lekin aktiv yozilmasi yo'q yoki bir nechta aktiv guruhli o'quvchilar
SELECT c.student_id, s.status AS student_status, count(DISTINCT c.id) contracts,
       count(DISTINCT sg.id) FILTER (WHERE sg.is_active AND sg.leave_date IS NULL) active_enrollments
FROM contracts c JOIN students s ON s.id = c.student_id
LEFT JOIN student_groups sg ON sg.student_id = c.student_id
GROUP BY c.student_id, s.status
HAVING count(DISTINCT sg.id) FILTER (WHERE sg.is_active AND sg.leave_date IS NULL) <> 1;

-- 7.7.5 Eski enum CHECK'lar qolganmi (cleaner ishlaganini tekshirish) va FK/UNIQUE'lar
SELECT conrelid::regclass, conname, contype, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid IN ('contracts'::regclass, 'contract_templates'::regclass)
ORDER BY contype;
```

**Lokal bazadagi natija:**
- `contracts` va `contract_templates` bo'sh. 7.7.1 bo'yicha keyingi raqam `CTR-0001` bo'ladi, to'qnashuv hozircha yo'q.
- CHECK yo'q. UNIQUE'lar: `contract_number`, `uuid` (ikkala jadvalda).
- `uuid` turi bu ikki jadvalda `varchar(36)`, boshqa jadvallarda esa `uuid`. Bu nomuvofiqlik, P2.

### 7.8 Xulosa

Holat: **Muammoli** — CRUD va generatsiya ishlaydi, lekin raqamlash o'chirishdan keyin doimiy buziladi (P0), narx billing-v2 ga mos emas, holat mashinasi va server PDF yo'q. P0: 1 (C-01), P1: 5 (C-02..C-06), P2: 7 (C-07..C-13).

---

## 8. Audit jurnali

### 8.1 Endpointlar

| Metod | Yo'l | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| GET | `/api/audit-logs` | **SA** (SecurityConfig `config/SecurityConfig.java:181-182` SA ∩ `@PreAuthorize` `controller/AuditLogController.java:31` SA) | query: `from`, `to` (ISO `LocalDate`), `userId` (Long), `action`, `entityType` (String), `entityId` (Long), `q` (String), `page`=0, `size`=50 (1..200 ga qisiladi, `service/AuditLogService.java:43`) | `ApiResponse<PageResponse<AuditLogResponse>>`; `AuditLogResponse{id, createdAt, userId, username, userRole, action, entityType, entityId, entityLabel, summary, details (parse qilingan JSON `{"changes":[{field,old,new}]}` yoki null), ipAddress}` (`dto/response/AuditLogResponse.java:11-25`) | Saralash `createdAt DESC` (`AuditLogService.java:44-45`). Filtr — `Specification`, null qiymatlar shartga qo'shilmaydi (`AuditLogService.java:88-114`) → PostgreSQL "could not determine data type" muammosi YO'Q. `to` → `23:59:59.999999999` (`:83`). `q` → `lower(coalesce(summary/entityLabel/username,''))` LIKE (`:107-113`). `userAgent` javobda yo'q |
| GET | `/api/audit-logs/entity/{entityType}/{entityId}` | **SA, A** (`SecurityConfig.java:179-180` ∩ `AuditLogController.java:48`) | path: `entityType` (String, registrga sezgir), `entityId` (Long) | `ApiResponse<List<AuditLogResponse>>` | Pageable YO'Q, butun tarix (`AuditLogService.java:61-67`; repo `repository/AuditLogRepository.java:18`). `entityType` oq ro'yxatsiz |
| GET | `/api/audit-logs/filters` | **SA** (`SecurityConfig.java:181-182` ∩ `AuditLogController.java:57`) | — | `ApiResponse<Map>`: `{actions: String[], entityTypes: String[]}` — bazadagi DISTINCT qiymatlar (`AuditLogService.java:71-76`, `AuditLogRepository.java:28-32`) | |

Yozish endpointi yo'q — yozuv faqat AOP orqali.

**Yozish mexanizmi (tasdiqlangan):**
- `@Audited(action, entity, summary/entityId/label = SpEL)` (`audit/Audited.java:22-41`) → `AuditAspect.around` (`audit/AuditAspect.java:70-106`). Bean `app.audit.enabled=true` bo'lsa yuklanadi (`:55`, `application.yml:51-55`).
- Metod exception tashlasa — yozuv YO'Q (`AuditAspect.java:76-80`). Tranzaksiya ichida bo'lsa — `afterCommit` sinxronizatsiyasi (`:87-93`), ya'ni **rollback bo'lsa yozilmaydi**; tranzaksiyadan tashqarida — darhol (`:94-97`). Aspect va `@Transactional` tartibi qanday bo'lmasin natija bir xil (tashqi tranzaksiya bo'lsa uning commit'i kutiladi).
- Aktyor/IP/UA so'rov oqimida sinxron olinadi (`:132-160`); IP: `X-Forwarded-For` birinchi elementi → `X-Real-IP` → `remoteAddr` (`:226-236`); UA 255 belgigacha (`:159`).
- Saqlash: `AuditRecorder.record` `@Async` + `REQUIRES_NEW`, xato yutiladi (`audit/AuditRecorder.java:31-45`); `@EnableAsync` (`CrmApplication.java:14`), maxsus executor YO'Q → Spring Boot default `applicationTaskExecutor` (TAXMIN: core 8, cheksiz navbat, shutdown'da kutmaydi).
- `LOGIN_FAILED` — `@Audited` emas, `AuthService.recordFailedLogin` to'g'ridan-to'g'ri `auditRecorder.record` (`service/AuthService.java:59-62, 104-118`).
- Retention: har kuni 03:30 Asia/Tashkent, `retention-days`=90 (`audit/AuditRetentionJob.java:22-34`, `audit/AuditProperties.java:18`, `application.yml:55`).
- Sirlar: `AuditContext.change` maydon nomida `password|token|secret` va ro'yxatdagi nomlarni `***` qiladi (`audit/AuditDiff.java:21-23, 94-103`; `audit/AuditContext.java:50-59`). `AuditDiff.compare` HECH QAYERDA ishlatilmaydi (grep). `detailsJson` faqat qo'lda `AuditContext.change` chaqirilgan joylarda to'ladi: LeadService, LeadStageService, TaskService, UserService (`isActive`) — maydonlar: status, dueAt, amount, type, title, result, requiresAmount, nextTask, nameUz, isActive, funnelStep, color, assignedUser, assignedTo. **Parol hash/token audit'ga tushmaydi** (tasdiqlangan: hech bir `change()` sir maydon bermaydi; `resetPassword`/`setPassword` faqat statik summary — `service/UserService.java:330-333, 367-370`).

**@Audited qamrovi — 62 ta metod** (grep `@Audited`, `audit/` paketidan tashqari):

| Domen | Metodlar (fayl:qator) | Amal |
|---|---|---|
| Auth/User | `AuthService.login` `service/AuthService.java:50`; `UserController.updateUser` `controller/UserController.java:178` (controller darajasida!); `UserService` createUser `:84`, createForTeacher `:113`, resetPassword `:330`, setPassword `:367`, setActive `:394` | LOGIN, CREATE, UPDATE |
| Student | `StudentService` `:137, :185, :360, :420, :512, :947 (freeze), :961 (unfreeze)`; `LeadService.convertToStudent` `:623`; `ImportService` `:97` | CREATE/UPDATE/DELETE/IMPORT |
| Group | `GroupService` `:183, :221, :476, :486, :494, :567, :576, :585` | CREATE/UPDATE/DELETE (Group, StudentGroup) |
| Billing v2 | `PaymentService.createPayment` `:83` (PAYMENT), `cancelPayment` `:138` (PAYMENT_CANCEL); `RefundPayoutService.payout` `billing/RefundPayoutService.java:51` (REFUND, entity=Student); `BalanceTransactionService` `:112, :150` (UPDATE, entity=Balance) | |
| Kassa | `CashRegisterService` `:453` addIncome, `:583` addExpense, `:611` DELETE CashTransaction, `:628` transfer | PAYMENT/DELETE |
| Payroll v2 | `PayrollService` generate `:155`, recalculate `:239`, delete `:255`, approve `:274` (STATUS_CHANGE), markAsPaid `:331`, cancel `:400` (PAYMENT_CANCEL) | payroll-v2.md §5.3 talabi bajarilgan |
| Lid/CRM | `LeadService` `:117, :152, :301 (ASSIGN), :335 (STATUS_CHANGE), :419, :476 (COMMENT)`; `LeadImportService` `:188, :367`; `LeadStageService` `:79, :113, :149, :178`; `TaskService` `:81, :127, :179 (TASK_DONE), :271 (ASSIGN), :289, :311` | |
| Boshqa | `AttendanceService.markAttendance` `:53` (entityId = **groupId**); `ExamService` `:174`; `NoticeService.deleteNotice` `:142` | |

**Audit qilinmaydigan yozish amallari (tasdiqlangan, `@Audited` yo'q):** `BillingAdminController` accrue/refresh-snapshots (`controller/BillingAdminController.java:40, 56`); `BillingMigrationController` dry-run/approve/apply/revert-sg/apply-sg (`controller/BillingMigrationController.java:43-116`; faqat `billing_migration_runs.approved_by` saqlanadi — `billing/BillingMigrationService.java:116-118`); `AdminRepairController` 5 ta POST (`controller/AdminRepairController.java:30-70`) — `AuditAction.REPAIR` e'lon qilingan (`audit/AuditAction.java:18`), lekin hech qayerda ishlatilmaydi; `SalaryRuleController` POST/PUT/DELETE (`controller/SalaryRuleController.java:34-47`); `BonusPenaltyController` POST/PUT/cancel/apply/DELETE (`controller/BonusPenaltyController.java:95-135`); Holidays POST/DELETE (`controller/LessonCalendarController.java:38-49`); `ExpenseController` POST (`controller/ExpenseController.java:37`); Teacher CRUD (oylik maydonlari bilan); Notice create/update (`service/NoticeService.java:71-132`); Meta sozlamalari (`controller/MetaAdminController.java:46-128`); `PUT /api/auth/change-password`; `AuditAction.EXPORT` ishlatilmaydi.

### 8.2 Ma'lumot modeli

- Entity `AuditLog` → jadval `audit_logs` (`entity/AuditLog.java:15-79`; `V42__audit_log.sql:3-18`).
- Ustunlar: `id BIGSERIAL PK`, `created_at TIMESTAMP NOT NULL DEFAULT NOW()` (zonasiz; Java `LocalDateTime.now()` — JVM zonasi `Asia/Tashkent`, `CrmApplication.java:23`; JDBC `time_zone: Asia/Tashkent`, `application.yml:40`), `user_id BIGINT` (FK EMAS — ataylab), `username VARCHAR(100)`, `user_role VARCHAR(30)`, `action VARCHAR(30) NOT NULL`, `entity_type VARCHAR(50)`, `entity_id BIGINT`, `entity_label VARCHAR(255)`, `summary VARCHAR(500)`, `details_json TEXT` (jsonb EMAS), `ip_address VARCHAR(45)`, `user_agent VARCHAR(255)`.
- Indekslar (V42 `:20-23` = entity `:16-21`): `idx_audit_created(created_at DESC)`, `idx_audit_user(user_id)`, `idx_audit_entity(entity_type, entity_id)`, `idx_audit_action(action)`.
- **Lokal baza:** V42 indekslari bor. Bulardan tashqari Hibernate/eski skript bilan **dublikatlar** yaratilgan:
  - `idx_audit_logs_entity` (= `idx_audit_entity`);
  - `idx_audit_logs_user_id` (= `idx_audit_user`);
  - `idx_audit_logs_created_at` (≈ `idx_audit_created DESC`).

  Har bir INSERT 7 ta indeksni yangilaydi. `user_id` FK — `audit_logs_user_id_fkey ON DELETE SET NULL`. Bu "FK emas — ataylab" degan izohga zid (TAXMIN: eski migratsiyadan qolgan).
- CHECK/enum cheklov yo'q (action — erkin VARCHAR, `audit/AuditAction.java:3-4`).
- Bog'liq iste'molchilar (audit jadvali biznes ma'lumot manbai sifatida!): lid lentasidagi mas'ul almashuvi — `service/LeadTimelineService.java:104-106` (`findByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc("Lead", id, "ASSIGN")`, `repository/AuditLogRepository.java:25-26`); direktor dashboardidagi operator `lastSeenAt` — `dashboard/OperatorMetricsService.java:135-140` (`MAX(createdAt) WHERE action='LOGIN' GROUP BY userId`).
- Indeks bo'shliqlari: `q` LIKE uchun indeks yo'q (pg_trgm yo'q); `(action, user_id, created_at)` kompozit yo'q — `lastSeenAt` so'rovi `idx_audit_action` + heap bilan (90 kunlik hajmda yetarli, TAXMIN).

### 8.3 Topilgan xatolar va xavflar

| ID | P | Sarlavha | Dalil | Oqibat | Tavsiya |
|---|---|---|---|---|---|
| A-01 | P1 | Billing/payroll boshqaruv va sozlama amallari audit'siz | §8.1 "audit qilinmaydigan" ro'yxati: `BillingMigrationController.java:43-116`, `BillingAdminController.java:40-56`, `AdminRepairController.java:30-70`, `SalaryRuleController.java:34-47`, `BonusPenaltyController.java:95-135`, `LessonCalendarController.java:38-49`, `ExpenseController.java:37` | Migratsiya apply/revert, oylik qoidasi, bonus/jarima, chiqim, ta'mirlash — "kim, qachon" izi yo'q; `REPAIR` amali o'lik | `@Audited` qo'shish: SalaryRule (CREATE/UPDATE/DELETE + diff), BonusPenalty (CREATE/UPDATE/STATUS_CHANGE/DELETE), Expense, Holiday, Billing migration (`REPAIR`/yangi `MIGRATION`), AdminRepair (`REPAIR`, dryRun'da `AuditContext.skip()`), Notice CREATE/UPDATE, Teacher UPDATE |
| A-02 | P1 | Diff (`details`) faqat 4 domenda; foydalanuvchi roli o'zgarishi izsiz | `UserController.java:176-187` (controller'da statik summary), `UserService.java:162` `updateUser` — `AuditContext.change` yo'q; Student/Group/Payroll/SalaryRule update'larda ham yo'q; `AuditDiff.compare` ishlatilmaydi | Rol/maosh/narx qanday o'zgargani ko'rinmaydi — audit maqsadi (kim nimani o'zgartirdi) bajarilmaydi | `UserService.updateUser` ichida `role`, `isActive`, `phone`, `email` uchun `AuditContext.change`; Student/Group/SalaryRule/Teacher update'larida `AuditDiff.compare(before, after)` (oldin detached nusxa) |
| A-03 | P1 | 90 kunlik retention biznes ma'lumotni o'chiradi | `AuditRetentionJob.java:30-31` + `LeadTimelineService.java:104-106` + `OperatorMetricsService.java:135-140` | 90 kundan eski lidlarda mas'ul almashuvi lentadan yo'qoladi; 90 kun kirmagan xodimda `lastSeenAt=null`; `app.audit.enabled=false` bo'lsa ikkala funksiya jimgina buziladi | Lid tayinlash tarixini `lead_assignments` jadvalidan olish (entity `LeadAssignment` mavjud); `users.last_login` dan lastSeen; yoki retention'dan `ASSIGN`/`LOGIN` ni chiqarish (`deleteOlderThan` ga `action NOT IN`) |
| A-04 | P2 | Entity tarixi sahifalanmaydi | `AuditLogService.java:61-67`; FE `views/pages/peoples/students/student-details.vue:1062` `{page:0,size:100}` yuboradi — e'tiborsiz | Faol lid/o'quvchida yuzlab yozuv bir so'rovda | `Pageable` (`size` max 200) yoki `limit` |
| A-05 | P2 | ADMIN istalgan obyekt tarixini ko'radi, jumladan SA ning LOGIN/IP va parol tiklash yozuvlari | `SecurityConfig.java:179-180`, `AuditLogController.java:47-53` — `entityType` oq ro'yxatsiz | `/api/audit-logs/entity/User/{saId}` → SA ning kirish vaqti va IP manzili | `entityType` oq ro'yxati (Student, Group, Lead, Task, Payment…), `User` tarixi — faqat SA; yoki LOGIN/LOGIN_FAILED ni A dan yashirish |
| A-06 | P2 | Ichma-ich `@Audited` ThreadLocal'ni tozalaydi | `AuditAspect.java:72` (`clear()` boshida) va `:102` (finally); misol: `LeadService.convertToStudent` (`:623`) → `groupService.addStudentToGroup` (`:690` → `GroupService.java:494`) | Tashqi metod ichki chaqiruvdan OLDIN yozgan `change/skip/actor/label` yo'qoladi. Hozir zarar kichik (convert oldin hech narsa yozmaydi) — kelajakdagi tuzoq | Holder'ni stek qilish (push/pop) yoki faqat eng tashqi aspect clear qilsin |
| A-07 | P2 | Commit bo'lgan amal audit'siz qolishi mumkin | `AuditRecorder.java:31-45` (`@Async`, xato faqat `log.error`); maxsus executor yo'q (`CrmApplication.java:14`) | Restart/deploy paytida navbatdagi yozuvlar yo'qoladi; DB xatosida jim yo'qotish; lid lentasi ASSIGN darhol ko'rinmasligi mumkin | `ThreadPoolTaskExecutor` (`setWaitForTasksToCompleteOnShutdown(true)`, `awaitTermination`), yoki moliyaviy amallar uchun sinxron yozuv |
| A-08 | P2 | IP soxtalashtirilishi mumkin | `AuditAspect.java:226-246` — XFF ning BIRINCHI elementi olinadi | nginx `$proxy_add_x_forwarded_for` ishlatsa, mijoz `X-Forwarded-For: 1.2.3.4` yuborib audit IP'ni soxtalashtiradi (TAXMIN: nginx konfiguratsiyasi ko'rilmadi) | nginx'da `proxy_set_header X-Forwarded-For $remote_addr` yoki `X-Real-IP` ni birinchi o'qish; Spring `server.forward-headers-strategy` + trusted proxies |
| A-09 | P2 | `LOGIN_FAILED` — ommaviy endpoint orqali cheksiz yozuv, kiritilgan "login" ochiq saqlanadi | `AuthService.java:104-118` (username summary/label'ga), rate limit yo'q (`docs/audit/backend-audit.md` §2.4) | Brute-force jadvalni to'ldiradi; xodim parolni login maydoniga yozsa — parol SA ga ochiq ko'rinadi | Rate limit; login uzunligini kesish/niqoblash (masalan mavjud bo'lmagan username → `***`) |
| A-10 | P2 | `userAgent` saqlanadi, lekin API qaytarmaydi; eksport yo'q | `AuditLogService.java:117-132`, `AuditLogResponse.java:11-25`; `AuditAction.EXPORT` ishlatilmaydi | Admin UI'da qurilma ko'rinmaydi; CSV/XLSX eksport yo'q | `userAgent` ni javobga qo'shish; `GET /api/audit-logs/export` (SA, CSV, o'zi `EXPORT` sifatida audit qilinsin) |
| A-11 | P2 | `q` qidiruvda `%`/`_` escape yo'q, indekssiz | `AuditLogService.java:107-113` | `q=%` hammasini qaytaradi (zararsiz); katta hajmda seq scan | `cb.like(..., escape '\\')`; kerak bo'lsa `pg_trgm` GIN |
| A-12 | P2 | `entityType/entityId` semantikasi nomuvofiq | Payment → paymentId (`PaymentService.java:83-87`), Balance → studentId (`BalanceTransactionService.java:112-114`), REFUND → entity `Student` (`RefundPayoutService.java:51-54`), Attendance → **groupId** (`AttendanceService.java:53-55`), CashRegister → kassa id | O'quvchi kartasidagi "Loglar" tabi (`entity/Student/{id}`) to'lov/bekor/balans tuzatishlarni ko'rsatmaydi | Ixtiyoriy `subject_type/subject_id` (yoki `student_id`) ustuni + indeks; yoki tarix endpointi bir nechta entity'ni birlashtirsin |
| A-14 | P2 | `LOGIN_FAILED` IP va user-agentsiz yoziladi | `AuthService.recordFailedLogin` (`service/AuthService.java:104-118`) `ipAddress`/`userAgent` ni to'ldirmaydi, aspect esa bu yo'lda ishlamaydi. Lokal bazada 3 ta `LOGIN_FAILED` yozuvining hammasida `ip_address` bo'sh | Brute-force tahlili (IP bo'yicha) imkonsiz. A-09 dagi tavsiya bu holatda ishlamaydi | `AuditAspect` dagi IP/UA aniqlovchi metodni umumiy util'ga chiqarib, shu yerda ham chaqirish |
| A-13 | P2 | Eski yozuvlar vaqt zonasi | `TimeZone.setDefault` keyinroq qo'shilgan (git: `afd4a36` audit commit `4815772` dan keyin) | Server UTC bo'lgan davrdagi `created_at` 5 soat siljigan bo'lishi mumkin (TAXMIN) — §8.7 SQL bilan tekshiring | Kerak bo'lsa bir martalik tuzatish |

P0 topilmadi: filtrlar `Specification` bilan (null-parametr muammosi yo'q), `lower()` faqat VARCHAR ustunlarda (bytea yo'q), sirlar diff'ga tushmaydi, rollback'da yozuv yo'q.

### 8.4 billing-v2 / payroll-v2 / dashboard bilan bog'liqlik

- **Billing v2:** to'lov (`PAYMENT`), bekor qilish (`PAYMENT_CANCEL`), pul qaytarish (`REFUND`), balansni qo'lda tuzatish (`UPDATE Balance`), muzlatish/chiqarish (`UPDATE Student`) — qamralgan. Idempotent takror (`Idempotency-Key`) audit'ni qayta yozmaydi: `AuditContext.skip()` (`billing/PaymentBookingService.java:247`). Qamralmagan: accrual job (tizim amali — mantiqan to'g'ri), admin `accrue`/`refresh-snapshots`, migratsiya approve/apply/revert (A-01). Kassa yozuvi `recordIncome/recordReversal/recordExpense` audit'siz, lekin ular audit qilingan ota-metod ichida chaqiriladi.
- **Payroll v2:** `docs/design/payroll-v2.md` §5.3 dagi barcha amallar (`CREATE/UPDATE/STATUS_CHANGE/PAYMENT/PAYMENT_CANCEL/DELETE`) — `PayrollService.java:155-400`. `generatePayroll` tranzaksiyasiz (har xodim alohida) → yozuv darhol. Oylik **qoidasi** (SalaryRule) va bonus/jarima — audit'siz (A-01), garchi oylik natijasiga bevosita ta'sir qilsa ham.
- **Dashboard:** `OperatorMetricsService.java:135-140` LOGIN'dan `lastSeenAt`; `DashboardBackfillService.java:407` ASSIGN tarixi audit'dan to'ldirilmasligini qayd etadi; direktor digest (`dashboard/DirectorDigestService.java:46-80`) audit'ga yozilmaydi, o'z `director_digest_log` jadvaliga yozadi.
- **Parametrlar qayerda:** `app.audit.enabled/retention-days` — faqat `application.yml:51-55` (`AuditProperties`), DB'da emas.

### 8.5 Eski frontend nima ishlatadi

| Servis/funksiya | Endpoint | Mos emas |
|---|---|---|
| `FE:services/auditService.js:17-19` `getAll` ← `FE:views/pages/settings/audit-logs.vue:238-246` | `GET /api/audit-logs` | Mos (`page,size,from,to,userId,action,entityType,q`). `entityId` UI'da yo'q |
| `auditService.getByEntity` (`:21-25`) ← `student-details.vue:1062` | `GET /api/audit-logs/entity/Student/{id}` | `page/size` yuboriladi — backend e'tiborsiz (A-04); to'lov/balans yozuvlari `Student` tarixida yo'q (A-12) |
| `auditService.getFilters` (`:27-29`) ← `audit-logs.vue:213-224` | `GET /api/audit-logs/filters` | Mos (`actions`, `entityTypes`) |
| `audit-logs.vue:226-233` `userService.getAll()` | `GET /api/users` | Foydalanuvchi filtri uchun; SA uchun ishlaydi |
| `FE:utils/auditLabels.js:33-43` `AUDIT_ACTION` | — | `PAYMENT_CANCEL`, `REFUND`, `STATUS_CHANGE`, `ASSIGN`, `COMMENT`, `TASK_DONE` yo'q → xom matn, kulrang badge. `AUDIT_ENTITY` (`:57-76`) da `LeadStage`, `Task`, `Import` yo'q |
| Route `FE:router/index.js:65-68` | — | `roles: ['SUPER_ADMIN']` — backend bilan mos |

Yangi admin: `ADM:src/shared/permissions/matrix.ts:527-534` `auditLogs` (SA) e'lon qilingan, lekin `src/modules/` da audit moduli YO'Q (sahifa hali qurilmagan).

### 8.6 Yetishmayotgan narsalar

| P | Narsa |
|---|---|
| P1 | A-01 qamrovi: SalaryRule, BonusPenalty, Expense, Holiday, Billing migration/admin, AdminRepair (`REPAIR`), Notice create/update, Teacher update, Meta sozlamalari |
| P1 | Rol/maosh/narx o'zgarishlari uchun diff (A-02) |
| P1 | Yangi admin'da audit sahifasi (ro'yxat + filtrlar + detal modal) va obyekt tarixi komponenti |
| P2 | Eksport (CSV/XLSX, `EXPORT` amali bilan), `userAgent` javobda, entity tarixi pagination |
| P2 | O'quvchi bo'yicha yig'ma tarix (`student_id` bog'lami), sozlanadigan retention (DB), retention'dan tashqari "muhim" amallar |
| P2 | Login brute-force himoyasi va `LOGIN_FAILED` hisobot (IP bo'yicha) |

### 8.7 DB tekshiruvi uchun SQL (read-only)

```sql
-- 1) Hajm, eng eski/yangi yozuv va retention ishlayaptimi (eng eski ~90 kun bo'lishi kerak)
SELECT count(*) AS total, min(created_at) AS oldest, max(created_at) AS newest,
       pg_size_pretty(pg_total_relation_size('audit_logs')) AS size
FROM audit_logs;

-- 2) Amal × obyekt taqsimoti (qaysi domenlar haqiqatan yozilyapti; REPAIR/EXPORT 0 bo'lishi kutiladi)
SELECT action, entity_type, count(*) AS n, max(created_at) AS last_at
FROM audit_logs GROUP BY action, entity_type ORDER BY n DESC;

-- 3) Indekslar (V42 dagi 4 ta + Hibernate ddl-auto qo'shgan dublikatlar bormi)
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'audit_logs' ORDER BY indexname;

-- 4) Diff (details_json) qaysi amallarda to'lgan va sir kalitlari tushib qolmaganmi
SELECT entity_type, action, count(*) FILTER (WHERE details_json IS NOT NULL) AS with_diff, count(*) AS total
FROM audit_logs GROUP BY 1,2 ORDER BY 1,2;
SELECT id, entity_type, action FROM audit_logs
WHERE details_json ~* '"field"\s*:\s*"[^"]*(password|token|secret|otp)[^"]*"' AND details_json !~ '"\*\*\*"' LIMIT 20;

-- 5) Vaqt zonasi siljishi (A-13): kun soati taqsimoti — 04:00-09:00 cho'qqi bo'lsa yozuvlar UTC'da
SELECT extract(hour FROM created_at) AS h, count(*) FROM audit_logs GROUP BY 1 ORDER BY 1;

-- 6) LOGIN_FAILED bosimi va IP (A-08/A-09); oxirgi 7 kun
SELECT date_trunc('day', created_at) d, ip_address, count(*) FROM audit_logs
WHERE action = 'LOGIN_FAILED' AND created_at > now() - interval '7 days'
GROUP BY 1,2 ORDER BY 3 DESC LIMIT 20;
```

**Lokal bazadagi natija (2026-10-02):**
1. Jadvalda 61 yozuv bor, hajmi 184 kB. Eng eskisi `2026-09-22 23:29`, eng yangisi `2026-10-02 16:34`. Retention hali ishga tushmagan: 90 kundan eski yozuv yo'q.
2. Amal va obyekt bo'yicha taqsimot:
   - Lead CREATE — 20 ta;
   - User UPDATE — 8 ta;
   - Payroll — CREATE 7, UPDATE 3, STATUS_CHANGE 2, PAYMENT_CANCEL 2, PAYMENT 1;
   - Lead STATUS_CHANGE — 5 ta;
   - User LOGIN 5 ta, LOGIN_FAILED 3 ta;
   - Payment PAYMENT 1, PAYMENT_CANCEL 1;
   - Student, StudentGroup va Attendance — 1 tadan.

   REPAIR/EXPORT yozuvlari yo'q, bu kutilgan holat (A-01).
3. V42 indekslari va 3 ta dublikat indeks bor (§8.2).
4. Diff (`details_json`) faqat ikki joyda to'lgan: Lead STATUS_CHANGE (5/5) va User UPDATE (4/8). Sir kalitlari (password/token/secret/otp) uchun 0 ta moslik.
5. Soatlar taqsimoti 0, 9, 11, 12, 15, 16, 23. Bu ish vaqtiga mos keladi, UTC siljishi belgisi yo'q (A-13 lokalda tasdiqlanmadi).
6. LOGIN_FAILED: 3 ta yozuv, `ip_address` bo'sh (A-14). LOGIN: 5 ta, IP `127.0.0.1`.

### 8.8 Xulosa

**Holat: Qisman.** Mexanizm to'g'ri: yozuv faqat commit'dan keyin tushadi, sirlar niqoblanadi, filtrlar null-xavfsiz, retention va IP/UA bor. Lekin uchta muammo bor:
- qamrov va diff tor;
- audit jadvali biznes ma'lumot manbai sifatida ishlatiladi va retention uni o'chiradi;
- muvaffaqiyatsiz login IP'siz yoziladi.

**P0: 0, P1: 3 (A-01, A-02, A-03), P2: 11 (A-04 … A-14).**


---

## 9. Ichki chat

> Qamrov: `controller/ChatController.java`, `controller/ChatSocketController.java`, `service/Chat{,Access,Attachment,Presence}Service.java`, `service/FileStorageService.java`, `controller/FileController.java`, `config/{WebSocketConfig,ChatChannelInterceptor,EnumCheckConstraintCleaner,SecurityConfig}.java`, `security/jwt/{JwtHandshakeInterceptor,PrincipalHandshakeHandler}.java`, `entity/{Conversation,ConversationParticipant,Message,MessageAttachment}.java`, enum/converter'lar, `repository/{Conversation,ConversationParticipant,Message,MessageAttachment}Repository.java`, barcha chat DTO'lar, `db/migration/V45–V48`. Eski frontend: `adizone-crm-front/src/{services/chatService.js,services/chatSocket.js,stores/chat.js,composables/useChatAttachments.js,composables/useVoiceRecorder.js,utils/chatAudio.js,views/pages/application/chat/*}`. Yangi admin (`adizone-admin`) da chat moduli YO'Q — faqat menyu kaliti (`src/shared/permissions/matrix.ts:146-149`).
> Barcha backend yo'llari `src/main/java/com/crm/` ga nisbatan. Spring Boot 3.2.0 (`pom.xml:10`).
> **Eskirgan hujjat:** `docs/audit/backend-audit.md` §7 "SEND kadrlari tekshirilmaydi" va §8/§9.13 "SVG/HTML/zip/wav ruxsat, `probeContentType`" — endi NOTO'G'RI: SEND faqat `/app/**` + a'zolik (`config/ChatChannelInterceptor.java:121-141`), upload qat'iy oq ro'yxat ext+MIME (`service/FileStorageService.java:49-66`, `:95-98`), qaytarishda `contentTypeFor` + `nosniff` (`controller/FileController.java:70-82`).

### 9.1 Endpointlar

Envelope (REST): `ApiResponse {success: boolean, message: String?, data: T, meta?: Object(NON_NULL)}` (`dto/response/ApiResponse.java:5-14`). Xato: a'zo emas → **403** `chat.notParticipant` (`service/ChatAccessService.java:66-70`), validatsiya/biznes → 400, topilmadi → 404.
Effektiv rollar: `SecurityConfig.java:239` `/api/chat/**` → `authenticated()` (rol cheklovi yo'q) ∩ `@PreAuthorize` faqat upload'da (`isAuthenticated()`, `ChatController.java:174`) ∩ `ChatAccessService` (faol a'zo, `left_at IS NULL`). Ya'ni **barcha 7 rol, jumladan STUDENT/PARENT** (`entity/enums/UserRole.java:4-10`; STUDENT hisobi `controller/UserController.java:141-150` da yaratiladi).

#### (a) REST — `/api/chat` (`ChatController.java:42`)

| Metod | Yo'l | Effektiv rollar | Request | Response (`data`) | Izoh |
|---|---|---|---|---|---|
| GET | `/conversations` (`:52-56`) | har qanday auth; faqat o'z a'zoliklari | — | `List<ConversationResponse>` | Saralash: pinned DESC, `last_message_at` NULLS LAST DESC, id DESC (`ConversationParticipantRepository.java:27-36`). Sahifalash YO'Q. 4 ta SQL (`ChatService.java:336-345`) |
| GET | `/conversations/{id:\d+}/messages` (`:68-77`) | a'zo (`ChatService.java:364`) | query `before: Long?`, `around: Long?`, `size: Integer?` (def 50, max 100, ≤0→50; `ChatService.java:79-80`, `:408-413`) | `List<ChatMessageResponse>` **yangi→eski** | Keyset kursor `id < before` (`MessageRepository.java:37-46`). `before`+`around` → 400 (`ChatService.java:361-363`). `around` boshqa suhbatniki → 400 (`:389-391`). O'chirilganlar ham keladi, `text`/`attachments` = null (`:853`, `:856`). "hasMore" yo'q |
| GET | `/conversations/{id:\d+}/messages/search` (`:86-93`) | a'zo | query `q` (trim, min 2; aks holda `[]`) | `List<ChatMessageResponse>` (max 20) | `LOWER(text) LIKE %q% ESCAPE '!'`, o'chirilganlar chiqmaydi (`MessageRepository.java:154-164`) |
| GET | `/search` (`:101-106`) | har qanday auth | query `q` (min 2) | `ChatSearchResponse {conversations: List<ConversationResponse>, messages: List<ChatMessageResponse>}` (har biri max 20) | Xabarlarda `conversationTitle` to'ldiriladi (`ChatService.java:460-461`). GROUP — nom, DIRECT — suhbatdosh ismi bo'yicha (`ConversationParticipantRepository.java:93-115`) |
| GET | `/presence` (`:116-120`) | har qanday auth | — | `List<PresenceResponse {type:"PRESENCE", userId, online, lastSeenAt}>` | Faqat suhbatdoshlar (`ConversationParticipantRepository.java:71-81`); onlaynlik xotiradan |
| POST | `/conversations/direct` (`:123-129`) | har qanday auth | `DirectConversationRequest {userId: Long @NotNull}` | `ConversationResponse`, **200** (yangi bo'lsa ham) | O'zi bilan → 400; nofaol → 400 (`ChatService.java:526-528`, `:555-557`); yo'q user → 404. `direct_key` UNIQUE + parallel-safe (`:530-544`). Rol bo'yicha cheklov YO'Q |
| POST | `/conversations/group` (`:132-138`) | har qanday auth | `GroupConversationRequest {title: @NotBlank @Size≤255, userIds: List<Long> @NotEmpty}` | `ConversationResponse`, **201**, message `"Guruh yaratildi"` | Yaratuvchi avtomatik a'zo; dublikatlar olib tashlanadi; o'zidan boshqa a'zo yo'q → 400; topilmagan id → 404 (`ChatService.java:575-606`). Nofaol/STUDENT a'zo tekshirilmaydi, a'zolar soniga chegara yo'q |
| PATCH | `/conversations/{id:\d+}/pin` (`:141-148`) | a'zo | `ConversationPinRequest {pinned: Boolean @NotNull}` | `ConversationResponse` | Faqat joriy userning qatori (`ChatService.java:616-622`) |
| GET | `/users` (`:151-155`) | har qanday auth | — | `List<ChatUserResponse {id, fullName, username, role, photoUrl, online, lastSeenAt}>` | BARCHA faol userlar, o'zidan tashqari (`ChatService.java:625-634`, `UserRepository.java:27`); sahifalash/filtr yo'q |
| POST | `/upload` (`:173-182`) | `isAuthenticated()` | multipart: `file` (req), `durationMs: Integer?`, `waveform: String?` | `ChatUploadResponse {fileUrl, fileName, fileSize, contentType, width?, height?, durationMs?, waveform?}`, **200**, `"Fayl yuklandi"` | Xabar yaratilmaydi. Ext+MIME oq ro'yxat: jpg/jpeg/png/webp/gif/pdf/doc/docx/xls/xlsx/webm/ogg/mp3/m4a (`FileStorageService.java:49-66`). Audio: `durationMs` 1..300000 majburiy, `waveform` ≤50 butun son 0..100 (`ChatAttachmentService.java:136-187`). Hajm faqat multipart 4MB (`application.yml:10-11`) |
| GET | `/unread-count` (`:185-190`) | har qanday auth | — | `UnreadCountResponse {count: long}` | 1 ta SQL (`MessageRepository.java:92-105`); o'z xabarlari va o'chirilganlar sanalmaydi; `is_muted` hisobga olinmaydi |

Yo'q endpointlar: suhbatni id bo'yicha olish, guruhga a'zo qo'shish/chiqarish, guruhdan chiqish, nomini o'zgartirish, guruhni o'chirish, mute, "hammasini o'qildi", xabar REST orqali yuborish/tahrir/o'chirish.

#### (b) STOMP — endpoint `/ws` (sof WebSocket, SockJS yo'q; `WebSocketConfig.java:43-47`), app prefix `/app`, broker `SimpleBroker("/topic","/queue")`, user prefix `/user` (`:52-54`)

| Destination | Yo'nalish | Payload DTO | Broadcast | Ruxsat tekshiruvi |
|---|---|---|---|---|
| `/ws?token=<accessJWT>` (yoki `Authorization: Bearer`) | HANDSHAKE | — | — | `JwtHandshakeInterceptor.java:50-78`: token → `loadUserByUsername` (nofaol → rad, `security/CustomUserDetailsService.java:28`) → `isTokenValid`; xato → 403. Origin: `SecurityConfig.ALLOWED_ORIGIN_PATTERNS` (`SecurityConfig.java:45-56`, `https://*.vercel.app` ham). FAQAT shu paytda |
| CONNECT | → server | — | `SessionConnectedEvent` → `/topic/presence` (0→1) | `Principal` bo'lishi shart (`ChatChannelInterceptor.java:74-77`) |
| `/app/chat.send` (`ChatSocketController.java:60-65`) | SEND | `ChatSendRequest {conversationId: Long @NotNull, text: String @Size≤4000, clientId: String @Size≤64, replyToId: Long?, attachments: List<ChatAttachmentRequest> @Valid @Size≤10}`; `ChatAttachmentRequest {fileUrl @NotBlank ≤500, fileName @NotBlank ≤255, fileSize, contentType ≤100, width, height, durationMs, waveform ≤500}` (`dto/request/ChatSendRequest.java:28-44`, `ChatAttachmentRequest.java:18-39`) | `/topic/conversation.{id}` ← `ChatMessageResponse {type:"MESSAGE", id, uuid, conversationId, conversationTitle(NON_NULL), senderId, senderName, senderPhotoUrl, text, messageType, replyToId, clientId, attachments[], replyTo{id,senderName,text,type}, createdAt, editedAt, deletedAt}` | Interceptor: `/app/**` + tanadagi `conversationId` a'zoligi (`ChatChannelInterceptor.java:121-141`); servis: a'zolik, matn yoki biriktirma majburiy, `replyToId` SHU suhbatdan (`ChatService.java:126-147`), `fileUrl` diskda mavjud (`ChatAttachmentService.java:326-343`). Yuboruvchi = `Principal` |
| `/app/chat.read` (`:68-75`) | SEND | `ChatReadRequest {conversationId @NotNull, messageId @NotNull}` | kursor oldinga surilsa `ChatReadReceiptResponse {type:"READ", conversationId, userId, messageId}` | a'zo + xabar shu suhbatniki (`ChatService.java:271-290`) |
| `/app/chat.typing` (`:88-93`) | SEND | `ChatTypingRequest {conversationId @NotNull, typing: Boolean @NotNull}` | `ChatTypingResponse {type:"TYPING", conversationId, userId, userName, typing}` (yuboruvchiga ham qaytadi) | a'zo (`ChatService.java:314-323`); bazaga yozilmaydi, server throttle yo'q |
| `/app/chat.edit` (`:102-107`) | SEND | `ChatEditRequest {messageId @NotNull, text @NotBlank @Size≤4000}` | `ChatMessageEditedResponse {type:"EDITED", conversationId, messageId, text, editedAt}` | a'zo + FAQAT muallif + o'chirilmagan + faqat `TEXT` + 15 daqiqa (`ChatService.java:96`, `:186-220`) |
| `/app/chat.delete` (`:115-122`) | SEND | `ChatDeleteRequest {messageId @NotNull}` | `ChatMessageDeletedResponse {type:"DELETED", conversationId, messageId, deletedBy}` | a'zo + (muallif YOKI SUPER_ADMIN/ADMIN) (`ChatService.java:99-100`, `:232-253`); muddat cheklovi yo'q; soft delete, fayllar qoladi |
| `/topic/conversation.{id}` | SUBSCRIBE | — | yuqoridagi 5 hodisa | faqat SHU prefiks bilan boshlansa a'zolik (`ChatChannelInterceptor.java:90-110`); boshqa har qanday manzil TEKSHIRILMAYDI (`:92-96`) — qarang CH-01 |
| `/topic/presence` | SUBSCRIBE | — | `PresenceResponse` — barcha ulanganlarga (`ChatPresenceService.java:130-136`) | yo'q |
| `/user/queue/errors` | SUBSCRIBE | — | `Map {type:"ERROR", message}` (`ChatSocketController.java:132-144`; interceptor rad etganda `ChatChannelInterceptor.java:143-147`) | Spring user-destination |

Heartbeat/transport: `setHeartbeatValue`/`TaskScheduler`/`configureWebSocketTransport` YO'Q (`WebSocketConfig.java:50-60`) → Spring standartlari (xabar ≤64KB, send buffer 512KB, 10s) va server heartbeat 0.

### 9.2 Ma'lumot modeli

Sxema manbai: **Flyway pom'da YO'Q** (`pom.xml` — `flyway` 0 ta; README "Flyway" deydi), `spring.jpa.hibernate.ddl-auto: update` (`application.yml:32`). `V45–V48` — idempotent qo'lda skriptlar; jadvallarni amalda Hibernate yaratgan bo'lishi mumkin (TAXMIN) → 9.7 SQL bilan tekshirish.

| Entity → jadval | Ustunlar | Bog'lanish / cheklov / indeks |
|---|---|---|
| `Conversation` → `conversations` (`entity/Conversation.java:25-75`, `extends BaseEntity`) | `id` BIGSERIAL, `uuid` UUID NOT NULL UNIQUE, `type` VARCHAR(20) NOT NULL (`ConversationTypeConverter`, DIRECT/GROUP), `title` VARCHAR(255) (GROUP), `direct_key` VARCHAR(64) UNIQUE, updatable=false (`"minId:maxId"`, `:54-55`, `:66-70`), `created_by` → users, `last_message_at`, `created_at` NOT NULL, `updated_at` | `idx_conversations_last_message(last_message_at DESC)` (`:26-28`; `V45__chat.sql:25-26`). CHECK yo'q |
| `ConversationParticipant` → `conversation_participants` (`ConversationParticipant.java:94-150`) | `conversation_id` NOT NULL FK, `user_id` NOT NULL FK, `last_read_message_id` BIGINT (FK EMAS, `:47-48`), `is_pinned` BOOL NOT NULL, `is_muted` BOOL NOT NULL (ishlatilmaydi, `:58-60`), `joined_at` NOT NULL, `left_at` (null = faol; qo'yuvchi kod YO'Q) | `uk_conv_participant(conversation_id,user_id)` (`:21-23`), `idx_conv_participants_user(user_id)` (`:25`). Guruhda rol (owner/admin) ustuni yo'q |
| `Message` → `messages` (`Message.java:173-235`) | `uuid` UNIQUE, `conversation_id` NOT NULL FK, `sender_id` NOT NULL FK, `text` TEXT (≤4000 kodda, `:40`), `type` VARCHAR(20) NOT NULL (`MessageTypeConverter`: TEXT/IMAGE/FILE/VOICE/SYSTEM, `MessageType.java:23-27`), `reply_to_id` (FK EMAS), `edited_at`, `deleted_at` (soft), `created_at` NOT NULL | `idx_messages_conversation_created(conversation_id, created_at DESC)`, `idx_messages_conversation_id_desc(conversation_id, id DESC)` (`:24-31`). `text` uchun trigram indeks yo'q (`V46__chat_presence.sql:13-25` — qo'lda). CHECK yo'q |
| `MessageAttachment` → `message_attachments` (`MessageAttachment.java:261-343`) | `uuid` UNIQUE, `message_id` NOT NULL FK, `file_url` VARCHAR(500) NOT NULL, `file_name` VARCHAR(255) NOT NULL, `file_size`, `content_type` VARCHAR(100), `width`, `height`, `duration_ms`, `waveform` VARCHAR(500), `sort_order` INT NOT NULL, `created_at` | `idx_message_attachments_message(message_id, sort_order)` (`:262-264`). `Message` da `@OneToMany` ataylab yo'q (IN so'rov) |
| `User.last_seen_at` (`entity/User.java:66`; `V46__chat_presence.sql:7`) | TIMESTAMP | Faqat oxirgi WS sessiya yopilganda (`ChatPresenceService.java:107-118`, `UserRepository.java:46`) |

Yuklanmagan/xabarga bog'lanmagan fayllar uchun jadval YO'Q (upload → disk, keyin klient URL'ni qaytarib yuboradi). Hech qanday `ON DELETE` yo'q; user/suhbat/xabar jismoniy o'chirilmaydi (user — soft, `UserController.java:221-231`).

### 9.3 Topilgan xatolar va xavflar

| ID | P | Sarlavha | Dalil | Oqibat | Tavsiya |
|---|---|---|---|---|---|
| CH-01 | **P0** | **Wildcard SUBSCRIBE bilan begona suhbatlarni o'qish** | Interceptor faqat `/topic/conversation.` bilan boshlanadigan manzilni tekshiradi, qolganini o'tkazadi (`config/ChatChannelInterceptor.java:92-96`). `SimpleBroker` (`WebSocketConfig.java:52`) obunada `AntPathMatcher` naqshlarini qo'llab-quvvatlaydi (Spring `DefaultSubscriptionRegistry`) | Har qanday ulangan foydalanuvchi (STUDENT ham) `SUBSCRIBE /topic/*` yoki `/topic/**`, `/topic/conv*` yuborib BARCHA suhbatlarning MESSAGE/EDITED/DELETED/READ/TYPING hodisalarini (matn, fayl URL'lari) real vaqtda oladi; `/queue/**` bilan boshqalarning xato navbatini ham. Runtime'da tasdiqlanmagan — Spring standart xulqi bo'yicha (yuqori ishonch) | SUBSCRIBE uchun oq ro'yxat: aniq `/topic/presence`, `/user/queue/errors`, `^/topic/conversation\.\d+$`; `*`, `?`, `{` bo'lsa rad. Qo'shimcha: brokerga naqshsiz `PathMatcher`. Integratsion test |
| CH-02 | **P0** | **Rasm o'lchamini aniqlashda "decompression bomb" → OOM** | `ImageIO.read(in)` butun rasmni xotiraga dekodlaydi (`service/ChatAttachmentService.java:209-217`, `:211`); `catch (IOException \| RuntimeException)` `OutOfMemoryError` ni ushlamaydi (`:213`). Yuklash har qanday auth uchun ochiq (`ChatController.java:173-174`) | 4MB ichiga siqilgan ~20000×20000 PNG bitta so'rovda GB'lab heap talab qiladi → JVM OOM / butun CRM to'xtaydi. Aniq chegara `-Xmx` ga bog'liq — TAXMIN | `ImageIO.getImageReaders` + `reader.getWidth(0)/getHeight(0)` (dekodlamasdan) yoki o'lchamni frontend'dan olib faqat chegaralash; piksel chegarasi (masalan ≤ 40 MP) |
| CH-03 | P1 | WS sessiyasi bloklash va token muddatidan keyin ham ishlaydi | Token faqat handshake'da (`JwtHandshakeInterceptor.java:50-78`); CONNECT'da faqat `Principal` borligi (`ChatChannelInterceptor.java:74-77`); `ChatAccessService.userOf` `isActive` ni tekshirmaydi (`service/ChatAccessService.java:48-58`); `UserService.setActive(false)` faqat refresh tokenlarni bekor qiladi (`service/UserService.java:421-424`), WS sessiyalarni yopmaydi. Access token 8 soat (`application.yml:81`, izoh "24 hours" — noto'g'ri) | Ishdan bo'shatilgan xodim ochiq tabda yozishmalarni o'qish/yozishda davom etadi | `userOf` da `isActive` tekshiruvi; deaktivatsiyada `SimpUserRegistry` orqali sessiyalarni yopish (yoki event); token `exp` ni sessiya atributida saqlab, SEND/SUBSCRIBE da tekshirish |
| CH-04 | P1 | Chat fayllari autentifikatsiyasiz ochiq | `GET /api/files/**` permitAll (`SecurityConfig.java:86`); chat fayli ham shu yerda (`FileStorageService.java:28`, `:160-173`); soft-delete'dan keyin ham fayl va URL qoladi (`ChatService.java:246`, `V47__chat_attachments.sql:39-41`) | Ovozli xabar, pasport/shartnoma skanlari URL tarqalsa (log, forward, brauzer tarixi) har kimga ochiq; o'chirilgan xabar fayli ham | Chat fayllari uchun alohida `GET /api/chat/files/{uuid}` + a'zolik tekshiruvi, yoki qisqa muddatli imzolangan URL (img/audio tegi Bearer yubora olmaydi). O'chirilganda URL'ni bekor qilish |
| CH-05 | P1 | Yangi suhbat haqida real-time hodisa yo'q + guruh boshqaruvi yo'q | REST `direct`/`group` hech narsa tarqatmaydi (`ChatController.java:123-138`); `convertAndSend` faqat `ChatSocketController` va `ChatPresenceService` da. Frontend faqat ro'yxatdagi suhbatlarga obuna (`adizone-crm-front/src/stores/chat.js:570`). A'zo qo'shish/chiqarish/chiqish/rename/mute/delete endpointlari va `GET /conversations/{id}` yo'q; `left_at` ni hech kim qo'ymaydi | Qabul qiluvchi yangi suhbatdagi xabarlarni sahifani yangilamaguncha ko'rmaydi; guruhni yaratgandan keyin o'zgartirib bo'lmaydi | `/user/queue/events` ga `CONVERSATION_CREATED/UPDATED/MEMBER_*` hodisasi; `GET/PATCH /conversations/{id}`, `POST/DELETE /conversations/{id}/members`, `POST .../leave`, `PATCH .../mute`; guruh egasi/admin roli; a'zo chiqarilganda uning obunasini uzish (hozir SUBSCRIBE faqat bir marta tekshiriladi) |
| CH-06 | P1 | Kim kim bilan yozisha olishi cheklanmagan; username/rol hammaga | `/api/chat/**` rolsiz (`SecurityConfig.java:239`); `listChatUsers` barcha faol userlarni `username` va `role` bilan beradi (`ChatService.java:625-634`, `dto/response/ChatUserResponse.java:22-32`); `createDirect`/`createGroup` rolni tekshirmaydi (`ChatService.java:552-606`). STUDENT hisoblari mavjud (`UserController.java:146`) | O'quvchi istalgan xodim/o'quvchiga yozadi va barcha login nomlarini ko'radi; xodim ro'yxatida o'quvchilar aralashadi | Rol siyosati (masalan STUDENT/PARENT faqat o'z o'qituvchisi/administratori bilan), `/users` da `username` ni olib tashlash, rol/qidiruv/sahifalash |
| CH-07 | P1 | Server heartbeat yo'q | `enableSimpleBroker` `setHeartbeatValue`/`setTaskScheduler` siz (`WebSocketConfig.java:52`); klient 10s/10s so'raydi (`adizone-crm-front/src/services/chatSocket.js:97-98`) → CONNECTED `heart-beat:0,0`, amalda heartbeat o'chadi | Proksi idle-timeout (nginx odatda 60s — TAXMIN, konfiguratsiya repoda yo'q) ulanishni uzadi → qayta ulanish, presence ONLINE/OFFLINE "miltillashi"; yarim ochiq TCP'da user "onlayn" qoladi | `.setHeartbeatValue(new long[]{10000,10000}).setTaskScheduler(...)`; proksida `proxy_read_timeout` > 2×heartbeat |
| CH-08 | P1 | JWT URL query'da | `?token=` (`JwtHandshakeInterceptor.java:90-93`; frontend `chatSocket.js:39-45`). Ilova o'zi URL'ni loglamaydi (`application.yml:84-86`, Tomcat accesslog yo'q; reject log faqat sabab, `:103`) | Reverse-proxy/CDN access loglarida 8 soatlik access token qoladi (TAXMIN — proksi konfiguratsiyasi repoda yo'q) | STOMP CONNECT `Authorization` native header'da autentifikatsiya (`ChannelInterceptor` CONNECT'da) yoki bir martalik qisqa "WS ticket" endpointi; proksida query'ni loglamaslik |
| CH-09 | P1 | Rad etilgan xabar klientga bog'lab qaytarilmaydi | Xato `{type,message}` — `clientId` siz (`ChatSocketController.java:132-144`, izohda ham tan olingan); frontend `/user/queue/errors` ga umuman obuna bo'lmaydi (`chatSocket.js` — bunday subscribe yo'q), "failed" faqat ulanish yo'qligida (`stores/chat.js:819-829`) | 403/400 bo'lgan xabar abadiy "yuborilmoqda" holatida qoladi | Xatoda `clientId` (va `destination`) qaytarish; yoki STOMP `receipt`; admin frontend `/user/queue/errors` ga obuna bo'lsin |
| CH-10 | P1 | Upload: hajm/kvota/tozalash yo'q | `FileStorageService.save` hajmni tekshirmaydi (`FileStorageService.java:160-173`) — izoh "ikkinchi to'siq" noto'g'ri (`ChatAttachmentService.java:66-68`); per-user kvota/rate-limit yo'q; xabarga bog'lanmagan uploadlar hech qachon o'chirilmaydi | Har qanday auth (STUDENT ham) 4MB'lik fayllar bilan diskni to'ldira oladi; yetim fayllar to'planadi | `chat_uploads` jadvali (uploader_id, stored_name, real ext/MIME/size, created_at, attached_at) + 24 soatlik tozalovchi; kvota/rate limit; `save()` da `MAX_BYTES` |
| CH-11 | P1 | Chat uchun birorta test yo'q | `src/test` da `*chat*` fayl va `ChatChannelInterceptor` testi yo'q | CH-01 kabi regressiyalar ushlanmaydi | Interceptor (SUBSCRIBE/SEND), `ChatService` (reply IDOR, edit/delete qoidalari), upload validatsiyasi uchun testlar |
| CH-12 | P2 | Biriktirma metama'lumotiga klient ishonchi; begona faylni biriktirish | `requireOwnUrl` faqat fayl mavjudligini tekshiradi, kim yuklaganini emas (`ChatAttachmentService.java:326-343`); `fileName/fileSize/contentType/width/height/durationMs` klientdan (`:293-306`); xabar turi (IMAGE/FILE/VOICE) klient `contentType` idan (`:273-312`) — izoh (`:249-251`) aksini da'vo qiladi | `.pdf` ni `audio/webm` deb VOICE qilib ko'rsatish, soxta nom/hajm; boshqa suhbat/modul faylini (URL ma'lum bo'lsa) qayta tarqatish | CH-10 dagi upload jadvali orqali `uploadId` bilan biriktirish; tur/hajm serverdagi yozuvdan |
| CH-13 | P2 | STOMP xato xabarida ichki ma'lumot | `@MessageExceptionHandler(Exception.class)` `exception.getMessage()` ni to'g'ridan-to'g'ri qaytaradi (`ChatSocketController.java:132-144`) | Validatsiya xatosida klass/metod nomlari va rad etilgan qiymat, kutilmagan xatoda SQL matni klientga ketadi | Faqat `CodedException`/`BadRequest`/`Forbidden` xabarini, qolganiga umumiy matn; validatsiya uchun `{field, message}` |
| CH-14 | P2 | Presence: hammaga tarqaladi, xotirada | `/topic/presence` barcha ulanganlarga (`ChatPresenceService.java:130-136`), REST esa faqat suhbatdoshlar (`:149-162`); holat `ConcurrentHashMap` (`:51`) | Har kim har kimning onlayn holatini ko'radi; 2+ instansda noto'g'ri; restartda `last_seen_at` yozilmaydi | `/user/queue/presence` ga faqat suhbatdoshlarga; ko'p instans uchun Redis/STOMP relay (RabbitMQ) |
| CH-15 | P2 | Har kadrda ortiqcha SQL | SEND: interceptor `userOf`+`isParticipant` (`ChatChannelInterceptor.java:133-135`), controller `userOf`, servis `requireParticipant` — ≥4 so'rov; typing'da ham (`ChatService.java:314-323`); SUBSCRIBE har suhbatga 2 so'rov, frontend har suhbatga obuna (`stores/chat.js:570`) | Ko'p suhbatli userda ulanishda yuzlab so'rov; typing bosimi | `userId` ni sessiya atributida keshlash; a'zolikni qisqa TTL kesh; typing server throttle |
| CH-16 | P2 | Guruh yaratish validatsiyasi va SYSTEM xabar | `userIds` ichida `null` → `findAllById` istisno → 500; nofaol/STUDENT a'zo tekshirilmaydi; a'zolar soni chegarasiz (`ChatService.java:582-591`); `MessageType.SYSTEM` hech qayerda yozilmaydi | Iflos guruhlar, tarixda "guruh yaratildi/qo'shildi" yo'q | `@NotNull` element, `isActive` + rol filtri, max a'zo; SYSTEM xabarlar |
| CH-17 | P2 | Qidiruv to'liq skan | `LOWER(m.text) LIKE '%q%'` (`MessageRepository.java:137-164`), trigram indeks qo'lda (`V46__chat_presence.sql:19-25`) | Xabarlar ko'payganda sekinlashadi. **Null-parametr JPQL / `lower(bytea)` xavfi YO'Q**: naqsh null bo'lsa so'rov umuman ketmaydi (`ChatService.java:429-435`, `:485-488`, `:503-513`), `before/around` alohida so'rovlar (`:372-374`) | `pg_trgm` GIN indeks; yoki PostgreSQL FTS |
| CH-18 | P2 | Enum fallback va global CHECK tozalovchi | Noma'lum `messages.type` → TEXT, `conversations.type` → DIRECT jimgina (`MessageType.java:42-45`, `ConversationType.java:30-33`) → kelajakdagi tur TEXT sifatida tahrirlanadi (`ChatService.java:198`). Chat ustunlari `@Convert` — Hibernate CHECK yaratmaydi, V45 da ham CHECK yo'q, demak yangi `MessageType` qiymati DB o'zgarishsiz ishlaydi. `EnumCheckConstraintCleaner` esa butun sxemadagi `= ANY (ARRAY[...])` CHECK'larni o'chiradi (`config/EnumCheckConstraintCleaner.java:36-42`, `:64`) — ataylab yozilgan CHECK'lar ham ketadi (V52–V57 da hozircha CHECK yo'q) | Ma'lumot buzilsa sezilmaydi | Noma'lum qiymatda log/xato; cleaner'ni faqat Hibernate nomlash naqshidagi (`*_check`) va ma'lum ustunlar bilan cheklash |
| CH-19 | P2 | Migratsiyalar avtomatik bajarilmaydi | Flyway yo'q (`pom.xml`), `ddl-auto: update` (`application.yml:32`); `V45` izohi o'zi indekslar kafolatlanmasligini aytadi (`V45__chat.sql:3-6`) | Prod'da indekslar/`direct_key` UNIQUE yo'q bo'lishi mumkin (TAXMIN) | 9.7 #1–#2 SQL bilan tekshirish; Flyway'ni qo'shish yoki README'ni tuzatish |
| CH-20 | P2 | Guruh UX/ma'lumot kamchiliklari | GROUP uchun "kim o'qidi" yo'q (`peerLastReadMessageId` faqat DIRECT, `ChatService.java:758`); `is_muted` saqlanadi, lekin endpoint va hisobga ta'siri yo'q; har ro'yxatda barcha guruh a'zolari to'liq qaytadi (`ConversationParticipantRepository.java:42-49`, `ChatService.java:744-746`); `fullName` `lastName` null bo'lsa "Ism null" (`ChatService.java:880-882`) | Katta guruhlarda og'ir javob; noto'g'ri ism | Guruhlarda `participantCount` + birinchi N avatar; null-safe ism |

Tekshirildi — muammo YO'Q: reply orqali cross-conversation IDOR yopiq (`ChatService.java:140-147`); read kursori xabar shu suhbatdaligini tekshiradi (`:276-279`); boshqa nomidan yozish mumkin emas (yuboruvchi `Principal`, `ChatSocketController.java:60-65`); SEND faqat `/app/**` (`ChatChannelInterceptor.java:125-129`); broadcast servis tranzaksiyasi commit bo'lgandan KEYIN (`ChatSocketController.java:63-64`, `ChatService.java:68-71`; yagona istisno `ChatPresenceService.onDisconnected` — `@Transactional` ichida, zararsiz, `:107-118`); ro'yxat/unread N+1 yo'q (4 va 1 so'rov, `ChatService.java:336-345`, `:639-642`); yuklash oq ro'yxati SVG/HTML'ni chiqarib tashlaydi, qaytarishda `nosniff` (`FileController.java:82`); path traversal himoyalangan (`FileStorageService.java:165-168`, `:175-182`); xabar hajmi ≤4000 belgi STOMP 64KB standart chegarasiga sig'adi.

### 9.4 billing-v2 / payroll-v2 / dashboard bilan bog'liqlik

**Bog'liqlik YO'Q.** `SimpMessagingTemplate`/`convertAndSend`/`ChatService` faqat chat fayllarida (`ChatSocketController`, `ChatPresenceService`, `ChatChannelInterceptor`); `billing/**`, payroll, `dashboard/**` chatga yozmaydi, tizim xabarlari/bildirishnomalar chat orqali YUBORILMAYDI (`MessageType.SYSTEM` umuman ishlatilmaydi). Real-time faqat chatda — to'lov, qarz, oylik, dashboard uchun WS hodisa yo'q. Bildirishnomalar alohida: Telegram integratsiyasi va e'lonlar (`/api/notices`). Umumiy nuqtalar faqat: (1) `EnumCheckConstraintCleaner` global (CH-18), (2) `FileStorageService`/`/api/files/**` umumiy (CH-04, CH-10), (3) user deaktivatsiyasi WS'ni uzmaydi (CH-03).

### 9.5 Eski frontend nima ishlatadi

| Frontend funksiya | Endpoint / destination | Mos kelish |
|---|---|---|
| `chatService.getConversations` (`services/chatService.js:17-19`) | GET `/api/chat/conversations` | Mos |
| `chatService.getMessages(id,{before,around,size=50})` (`:28-33`) | GET `.../messages?before\|around&size` | Mos (ikkovini birga yubormaydi) |
| `createDirect(userId)` / `createGroup(title,userIds)` / `setPinned(id,pinned)` (`:36-46`) | POST direct / POST group / PATCH pin | Mos |
| `getUsers`, `getUnreadCount`, `getPresence`, `search(q)`, `searchMessages(id,q)` (`:49-80`) | GET users / unread-count / presence / search / messages/search | Mos (`count` → `raw.count ?? raw.unreadCount ?? raw.total`, `stores/chat.js:32`) |
| `upload(file,{durationMs,waveform})` (`:100-117`) | POST `/api/chat/upload` | Mos; javob butunicha `attachments[]` ga qo'yiladi |
| `chatSocket.connect` (`services/chatSocket.js:39-45`, `:93-114`) | `/ws?token=` | Mos; heartbeat 10s/10s — server bermaydi (CH-07) |
| `send/editMessage/deleteMessage/markRead/sendTyping` (`chatSocket.js:240-297`) | `/app/chat.send\|edit\|delete\|read\|typing` | Maydon nomlari mos |
| `subscribe(id)` / `subscribePresence` (`:189-230`) | `/topic/conversation.{id}`, `/topic/presence` | Mos |

**MOS EMAS / bo'shliqlar:**
1. `/user/queue/errors` ga obuna YO'Q — backend rad etgan xabar "sending" holatida qotadi (`stores/chat.js:819-829`; backend `ChatSocketController.java:132-144`). Backend ham `clientId` qaytarmaydi (CH-09).
2. `replyTo` shakli: frontend `{id, senderId, senderName, text, deletedAt}` kutadi (`stores/chat.js:74`), backend `ChatReplyPreviewResponse {id, senderName, text, type}` (`dto/response/ChatReplyPreviewResponse.java:22-25`) — `senderId` va `deletedAt` yo'q. Sahifa yangilangach o'chirilgan iqtibos "matnsiz biriktirma" bo'lib ko'rinadi (`deletedAt` faqat jonli DELETED'da qo'yiladi, `stores/chat.js:288-291`).
3. `ConversationResponse` da `lastMessageDeleted`, `lastMessageAttachmentCount`, `lastMessageAttachments` YO'Q, frontend ularni o'qiydi (`views/pages/application/chat/conversation-row.vue:34`, `:100`; `stores/chat.js:274`, `:299-300`, `:841-843`). Bundan tashqari backend oxirgi xabarda o'chirilganlarni o'tkazib yuboradi (`MessageRepository.java:58`) — "Xabar o'chirildi" faqat jonli hodisada ko'rinadi.
4. Ovoz: `extensionForMime` `wav` beradi (`utils/chatAudio.js:55`), backend oq ro'yxatida `wav` yo'q (`FileStorageService.java:49-66`) → 400. Safari AAC 5 daqiqalik yozuv (`chatAudio.js:13`) 4MB dan oshishi mumkin (`useChatAttachments.js:7`) — TAXMIN.
5. Yangi suhbatlar: store faqat `GET /conversations` dagi suhbatlarga obuna (`stores/chat.js:570`), backend yangi suhbat hodisasini yubormaydi (CH-05).
6. Fayl havolalari (`<img>`, `<a download>`) JWT'siz `GET /api/files/...` ga tayanadi — CH-04 yopilsa frontend ham o'zgarishi kerak (imzolangan URL/blob).

Yangi admin (`adizone-admin`): chat moduli yo'q; spetsifikatsiya `docs/spec/frontend-audit.md:4604-` (§3.7) va `docs/spec/frontend-api-usage.md:378-402` (§7) eski frontend kontraktini to'g'ri aks ettiradi, lekin yuqoridagi 1–3 farqlarni qayd etmaydi.

### 9.6 Yetishmayotgan narsalar (edutizim.uz uslubidagi o'quv markazi CRM'ga nisbatan)

| P | Narsa | Hozir |
|---|---|---|
| P1 | Rolga asoslangan chat siyosati: o'qituvchi ↔ o'z guruhidagi o'quvchi/ota-ona, administrator ↔ hamma; STUDENT/PARENT xodimlar ro'yxatini ko'rmasin | Hamma bilan hamma (CH-06) |
| P1 | Guruh boshqaruvi: a'zo qo'shish/chiqarish, chiqish, rename, egasi/admin, o'quv guruhidan avtomatik chat (StudyGroup → Conversation) | Faqat yaratish (CH-05) |
| P1 | Offline bildirishnoma: Web Push/FCM yoki mavjud Telegram bot orqali "yangi xabar"; mute ni hisobga olish | Yo'q; `is_muted` ishlatilmaydi |
| P1 | Yangi suhbat/a'zolik o'zgarishi real-time hodisalari, `GET /conversations/{id}` | Yo'q |
| P1 | Admin uchun moderatsiya/audit (shikoyat, xabarni o'chirish logi), saqlash muddati siyosati | Chat audit qilinmaydi |
| P2 | Suhbatda xabarni qadash (pin message) | Faqat suhbatni qadash |
| P2 | Reaksiyalar, forward, @mention (+ mention bo'yicha unread), "kim o'qidi" guruhda, delivered/read ajratish | Yo'q |
| P2 | E'lon kanali (faqat admin yozadi, ko'p o'quvchiga), so'rovnoma (poll), rejalashtirilgan xabar | Yo'q (e'lonlar alohida modulda) |
| P2 | Suhbat media/fayl galereyasi, arxivlash, havola preview, SYSTEM xabarlar | Yo'q |

### 9.7 DB tekshiruvi uchun SQL (read-only)

```sql
-- 1) Chat jadvallaridagi barcha cheklovlar (UNIQUE direct_key / uk_conv_participant bormi,
--    eski Hibernate CHECK'lar — masalan messages.type uchun "= ANY (ARRAY[...])" — qolganmi)
SELECT conrelid::regclass AS tbl, conname, contype, pg_get_constraintdef(oid) AS def
FROM pg_constraint
WHERE conrelid IN ('conversations'::regclass, 'conversation_participants'::regclass,
                   'messages'::regclass, 'message_attachments'::regclass)
ORDER BY 1, contype, conname;

-- 2) Indekslar: V45/V47 dagi 5 ta indeks haqiqatan yaratilganmi (ddl-auto ularni kafolatlamaydi)
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE tablename IN ('conversations','conversation_participants','messages','message_attachments')
ORDER BY tablename, indexname;

-- 3) A'zosiz yoki noto'g'ri a'zoli suhbatlar (DIRECT != 2 faol, GROUP < 2 faol)
SELECT c.id, c.type, c.direct_key,
       COUNT(p.id) AS total,
       COUNT(p.id) FILTER (WHERE p.left_at IS NULL) AS active
FROM conversations c
LEFT JOIN conversation_participants p ON p.conversation_id = c.id
GROUP BY c.id, c.type, c.direct_key
HAVING COUNT(p.id) = 0
    OR (c.type = 'DIRECT' AND COUNT(p.id) FILTER (WHERE p.left_at IS NULL) <> 2)
    OR (c.type = 'GROUP'  AND COUNT(p.id) FILTER (WHERE p.left_at IS NULL) < 2)
ORDER BY c.id;

-- 4) DIRECT dublikatlari va direct_key nomuvofiqligi (bir juftlikka >1 suhbat yoki kalit a'zolarga mos emas)
WITH d AS (
  SELECT c.id, c.direct_key,
         string_agg(p.user_id::text, ':' ORDER BY p.user_id) AS pair
  FROM conversations c
  JOIN conversation_participants p ON p.conversation_id = c.id
  WHERE c.type = 'DIRECT'
  GROUP BY c.id, c.direct_key
)
SELECT pair, COUNT(*) AS cnt, array_agg(id ORDER BY id) AS conv_ids,
       array_agg(direct_key ORDER BY id) AS keys
FROM d
GROUP BY pair
HAVING COUNT(*) > 1 OR bool_or(direct_key IS DISTINCT FROM pair);

-- 5) Yaxlitlik: boshqa suhbatdagi xabarga reply, a'zo bo'lmagan yuboruvchi
SELECT 'reply_cross_conv' AS issue, m.id, m.conversation_id, r.conversation_id AS other
FROM messages m JOIN messages r ON r.id = m.reply_to_id
WHERE r.conversation_id <> m.conversation_id
UNION ALL
SELECT 'reply_missing', m.id, m.conversation_id, NULL
FROM messages m
WHERE m.reply_to_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM messages r WHERE r.id = m.reply_to_id)
UNION ALL
SELECT 'sender_not_participant', m.id, m.conversation_id, m.sender_id
FROM messages m
WHERE NOT EXISTS (SELECT 1 FROM conversation_participants p
                  WHERE p.conversation_id = m.conversation_id AND p.user_id = m.sender_id)
ORDER BY 1, 2
LIMIT 200;

-- 6) Xabar turi va biriktirmalar mosligi + noma'lum tur qiymatlari
SELECT m.type,
       COUNT(*) AS total,
       COUNT(*) FILTER (WHERE a.cnt IS NULL)               AS no_attachment,
       COUNT(*) FILTER (WHERE a.cnt > 1)                   AS multi_attachment,
       COUNT(*) FILTER (WHERE m.deleted_at IS NOT NULL)    AS deleted,
       (m.type NOT IN ('TEXT','IMAGE','FILE','VOICE','SYSTEM')) AS unknown_type
FROM messages m
LEFT JOIN (SELECT message_id, COUNT(*) AS cnt FROM message_attachments GROUP BY message_id) a
       ON a.message_id = m.id
GROUP BY m.type
ORDER BY m.type;
-- Kutiladi: TEXT da no_attachment = total; IMAGE/FILE/VOICE da no_attachment = 0; VOICE da multi = 0.

-- 7) Bir fayl bir nechta xabarda / bir nechta yuboruvchida (begona faylni biriktirish izi, CH-12)
--    va o'chirilgan xabarlarning hali ochiq fayllari (CH-04)
SELECT a.file_url,
       COUNT(*) AS uses,
       COUNT(DISTINCT m.sender_id) AS senders,
       array_agg(DISTINCT m.conversation_id) AS conversations,
       bool_or(m.deleted_at IS NOT NULL) AS any_deleted
FROM message_attachments a
JOIN messages m ON m.id = a.message_id
GROUP BY a.file_url
HAVING COUNT(*) > 1 OR COUNT(DISTINCT m.sender_id) > 1 OR bool_or(m.deleted_at IS NOT NULL)
ORDER BY uses DESC
LIMIT 100;

-- 8) Faol a'zoliklardagi nofaol / STUDENT / PARENT foydalanuvchilar (CH-03, CH-06)
--    va noto'g'ri o'qilganlik kursori (boshqa suhbat xabariga yoki max id dan katta)
SELECT p.conversation_id, p.user_id, u.role, u.is_active, p.last_read_message_id, mm.max_id,
       (p.last_read_message_id IS NOT NULL AND NOT EXISTS (
          SELECT 1 FROM messages x
          WHERE x.id = p.last_read_message_id AND x.conversation_id = p.conversation_id)) AS bad_cursor
FROM conversation_participants p
JOIN users u ON u.id = p.user_id
LEFT JOIN (SELECT conversation_id, MAX(id) AS max_id FROM messages GROUP BY conversation_id) mm
       ON mm.conversation_id = p.conversation_id
WHERE p.left_at IS NULL
  AND (u.is_active = false
       OR u.role IN ('STUDENT','PARENT')
       OR (p.last_read_message_id IS NOT NULL
           AND (mm.max_id IS NULL OR p.last_read_message_id > mm.max_id
                OR NOT EXISTS (SELECT 1 FROM messages x
                               WHERE x.id = p.last_read_message_id
                                 AND x.conversation_id = p.conversation_id))))
ORDER BY p.conversation_id, p.user_id;
```

**Lokal bazadagi natija:**
1. Cheklovlar to'liq: `uk_conv_participant(conversation_id,user_id)`, `conversations.direct_key` UNIQUE va `uuid` UNIQUE (har jadvalda). CHECK yo'q, `messages.type` uchun eski enum CHECK ham yo'q. FK'lar Hibernate nomlarida, hammasi NO ACTION.
2. V45 va V47 dagi 5 ta indeksning hammasi **bor**. V46 dagi ixtiyoriy `idx_messages_text_trgm` yo'q, bu kutilgan holat (CH-17). `users.last_seen_at` ustuni bor.
3. Chat jadvallari (`conversations`, `conversation_participants`, `messages`, `message_attachments`) bo'sh, shuning uchun 3–8-so'rovlar 0 qator qaytaradi. Chat lokalda sinalmagan.

### 9.8 Xulosa

**Holat: Muammoli.** Funksional jihatdan chat to'liq ishlaydi (DIRECT/GROUP, matn/rasm/fayl/ovoz, reply, 15 daqiqalik tahrir, soft delete, read receipts, typing, presence, qidiruv, kursorli sahifalash; N+1 yo'q, broadcast commit'dan keyin, reply IDOR va soxta SEND yopilgan). Lekin ikkita P0 bor: wildcard SUBSCRIBE orqali barcha suhbatlarni o'qish (CH-01) va upload orqali JVM'ni OOM qilish (CH-02). Admin panel uchun guruh boshqaruvi, yangi suhbat hodisalari, rol siyosati, fayl maxfiyligi va sessiyani bekor qilish kerak.

| P0 | P1 | P2 |
|---|---|---|
| 2 (CH-01, CH-02) | 9 (CH-03 – CH-11) | 9 (CH-12 – CH-20) |

---

## 10. Sozlamalar va bildirishnomalar

### 10.1 Endpointlar

**Sozlamalar / enum'lar**

| Metod | Yo'l | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| GET | `/api/settings/academic-year` | **PUBLIC** (`SecurityConfig.java:91`; `@PreAuthorize` yo'q) | — | `ApiResponse<String>` masalan `"2026 / 2027"` | Har so'rovda `LocalDate.now()` dan hisoblanadi: oy ≥ 9 → `y / y+1` (`controller/SettingsController.java:15-26`). DB/yml'da saqlanmaydi |
| * | `/api/settings/**` (boshqa) | GET AUTH, qolgani SA,A (`SecurityConfig.java:92-93`) | — | — | Endpoint YO'Q — faqat qoida zaxirada |
| GET | `/api/enums/payment-methods` | AUTH (`anyRequest().authenticated()`, `SecurityConfig.java:246`) | — | `ApiResponse<List<EnumOptionDto{value,label,icon}>>` | `controller/EnumController.java:27-37` |
| GET | `/api/enums/task-types` | AUTH | — | shu | `:39-49` |
| GET | `/api/enums/task-statuses` | AUTH | — | `{value,label}` (icon null) | `:51-60` |
| GET/POST/DELETE | `/api/holidays`, `/api/holidays/{date}` | SA, A (`controller/LessonCalendarController.java:30-49`) | from/to; body Holiday | `List<Holiday>` (entity to'g'ridan-to'g'ri) | Sozlama sifatida yagona DB'dagi kalendar ma'lumotnomasi; audit'siz |

Lead bosqichlari (`/api/lead-stages/**`, AUTH o'qish, yozish `@PreAuthorize`) — 5-modulda; bu yerda faqat havola (`SecurityConfig.java:160`).

**Bildirishnomaga aloqador manbalar (backendda alohida Notification yo'q)**

| Metod | Yo'l | Effektiv rollar | Request | Response | Izoh |
|---|---|---|---|---|---|
| GET | `/api/notices` | AUTH (`SecurityConfig.java:113` ∩ `controller/NoticeController.java:26`) | `page`=0, `size`=20 (cheklanmagan) | `ApiResponse<PageResponse<NoticeResponse>>` | **Barcha** e'lonlar: qoralama, nofaol, muddati o'tgan — har kimga (`service/NoticeService.java:39-51`) |
| GET | `/api/notices/active` | AUTH (`:34-39`) | `limit`=50 (1..50) | `ApiResponse<List<NoticeResponse>>` | Bell lentasi: `isActive && isPublished && (expiresAt null \|\| ≥ bugun 00:00)` (`repository/NoticeRepository.java:21-28`) |
| GET | `/api/notices/latest` | AUTH (`:41-46`) | `limit`=5 | shu | `/active` bilan bir xil servis |
| GET | `/api/notices/unread-count` | AUTH (`:48-53`) | — | `ApiResponse<{count: long}>` | `NoticeRepository.java:39-49` |
| POST | `/api/notices/read-all` | AUTH (`SecurityConfig.java:230-231`, `:55-60`) | — | `ApiResponse<Void>` | |
| POST | `/api/notices/{id}/read` | AUTH (`:62-67`) | path `id` | `ApiResponse<Void>` | |
| GET | `/api/notices/{id}` | AUTH (`:69-73`) | — | `ApiResponse<NoticeResponse>` | Qoralama ham qaytadi |
| POST | `/api/notices` | SA, A (`SecurityConfig.java:232-233` ∩ `:76`) | `NoticeRequest{title*, content*, noticeDate, publishedTo, noticeType, targetRole, isActive, isPublished, publishedAt, expiresAt, expiryDate, createdById}` (`dto/request/NoticeRequest.java:11-27`) | 201 `ApiResponse<NoticeResponse>` | |
| PUT | `/api/notices/{id}` | SA, A (`:234-235` ∩ `:83`) | shu | `ApiResponse<NoticeResponse>` | `targetRole` har doim yoziladi (null bo'lsa tozalanadi, `NoticeService.java:119`) |
| DELETE | `/api/notices/{id}` | SA, A (`:243-244` ∩ `:91`) | — | `ApiResponse<Void>` | Jismoniy o'chirish + `notice_reads` bulk delete (`NoticeService.java:141-147`), `@Audited DELETE` |
| GET | `/api/chat/unread-count` | AUTH (`SecurityConfig.java:239`) | — | `ApiResponse<UnreadCountResponse{count}>` | `controller/ChatController.java:185-190` |
| GET | `/api/attendance/unlock-requests/count` | SA, A (`controller/AttendanceUnlockRequestController.java:75-79`) | — | `ApiResponse<Long>` | PENDING soni |
| GET | `/api/leaves/pending` | SA, A (`SecurityConfig.java:188-189` ∩ `controller/LeaveController.java:40-44`) | — | `ApiResponse<List<LeaveResponse>>` | Faqat ro'yxat, **count endpoint yo'q** |
| GET | `/api/tasks/stats` | SA, A, SM (`SecurityConfig.java:150-151` ∩ `controller/TaskController.java:30, 66-69`) | — | `TaskStatsResponse{overdue, today, upcoming, noTask, byUser[]}` (`dto/response/TaskStatsResponse.java:24-30`) | Vazifa badge manbai |
| WS | `/ws` (STOMP) | handshake permitAll, token `?token=` (`SecurityConfig.java:99`, `config/WebSocketConfig.java:41-48`) | — | `/topic/conversation.{id}`, `/topic/presence`, `/user/queue/errors` (`config/WebSocketConfig.java:51-55`; `controller/ChatSocketController.java:64-141`; `service/ChatPresenceService.java:48, 131`) | **Faqat chat**. Notice/vazifa/so'rov uchun push YO'Q |

**NoticeResponse** (`dto/response/NoticeResponse.java:14-31`): `id, uuid, title, content, noticeDate, publishedTo, noticeType, targetRole, isActive, isPublished, publishedAt, expiresAt, expiryDate, isExpired, isRead, createdByName (username), createdAt`.

**Telegram:** `service/TelegramService.java` — `sendMessage(chatId, html)` (`:27`), shablonlar kodda (`buildAttendanceMessage :69-82`, `buildPaymentMessage :84-98`, "Adizone o'quv markazi" literal), `sendPaymentReminder` faqat log yozadi (`:60-67`, stub). Iste'molchilar: davomat (kelmadi) ota-onaga (`service/AttendanceService.java:156-166`), to'lov eslatmasi `@Scheduled` 10:00 (`service/PaymentReminderService.java:42-76`, `parent.telegramChatId`), direktor digest 20:00 (`dashboard/DirectorDigestService.java:46-80`, default o'chiq, `application.yml:71-77`). Xodimlarga ichki Telegram bildirishnoma yo'q.

### 10.2 Ma'lumot modeli

- **Kodda sozlamalar jadvali YO'Q** (migratsiyalarda `settings`/`organization`/`branch` yo'q, grep bilan tekshirildi).
  - **Lokal bazada esa legacy `settings` jadvali bor**: `id`, `setting_key` UNIQUE, `setting_value`, `description`, `updated_by`, `updated_at`. Unda 4 qator: `school_name=Adizone`, `currency=UZS`, `payment_cycle_days=30`, `max_debt_days=7`.
  - Shu kabi legacy **`notifications`** jadvali ham bor: `user_id`, `title`, `message`, `is_read`, `notification_type`, `reference_id`, `reference_type`, `created_at`. Qatorlar soni 0.
  - Ikkala jadval uchun entity yoki kod yo'q (Java'da grep 0). Ular eski sxemadan qolgan "o'lik" jadvallar (X-06). S-03/S-04 ni loyihalashda ularni qayta ishlatish yoki o'chirish kerak.

  Barcha parametrlar `application.yml` + `@ConfigurationProperties` — o'zgartirish = redeploy:
  - `app.audit.*` (`audit/AuditProperties.java:15-18`), `app.payroll.cutover-date`, `count-discount-covered` (`config/PayrollProperties.java:23, 29`; `application.yml:59-61`), `app.billing.*` — enabled, graceDays=3, accrualCron, maxCatchUp=24, reminderCron, startupCatchUp, cancelMaxAgeDays=31, migrationGoLive, migrationRollbackHours=72 (`billing/BillingProperties.java:22-46`; yml'da yo'q → default), `app.dashboard.*` — trialDecisionDays, trialStayDays, churnGraceDays, workStart 09:00, workEnd 20:00, workDays Mon–Sat, noResponseWorkHours, digest.* (`dashboard/DashboardProperties.java:21-62`), `app.attendance.unlock-valid-hours=48` (`application.yml:67-69`), `telegram.enabled/bot-token` (`TelegramService.java:19-22`).
  - Ish vaqti (`workStart/workEnd/workDays`) faqat dashboard SLA uchun; markaz ish vaqti sozlamasi yo'q.
- **`holidays`** (`V53__director_dashboard.sql:72`; `entity/Holiday.java`): `holiday_date DATE PK`, `name VARCHAR(200) NOT NULL`, `created_by BIGINT`, `created_at`. Billing'da ishlatilmaydi (faqat `GroupScheduleService`, `LessonCalendarService`, `AttendanceMetricsService`).
- **`notices`** (`entity/Notice.java:11-66`, `BaseEntity` → `created_at`, `updated_at`; jadval Hibernate `ddl-auto` bilan, migratsiyasiz — TAXMIN): `id`, `uuid UNIQUE NOT NULL`, `title VARCHAR(255) NOT NULL`, `content TEXT NOT NULL`, `notice_date DATE`, `published_to VARCHAR(30)` ("ALL"), `notice_type VARCHAR(30)` ("GENERAL"), `target_role VARCHAR(30)`, `is_active`, `is_published`, `published_at`, `expires_at TIMESTAMP`, `created_by → users`. Lokal bazada 4 ta bitta ustunli indeks bor (§6.2).
- **`notice_reads`** (`V41__notice_reads.sql:3-12`; `entity/NoticeRead.java`): `notice_id NOT NULL FK notices` (ON DELETE CASCADE YO'Q), `user_id NOT NULL FK users`, `read_at`, `UNIQUE(notice_id,user_id)`, indekslar `idx_notice_reads_user`, `idx_notice_reads_notice`.
- **Notification / UserNotification entity YO'Q** (grep `Notification` — faqat SecurityConfig/AnalyticsService matnlari).

### 10.3 Topilgan xatolar va xavflar

| ID | P | Sarlavha | Dalil | Oqibat | Tavsiya |
|---|---|---|---|---|---|
| S-01 | ↪ N-01 (P1, 6-modulda sanalgan) | E'lon nishonlash (`publishedTo`/`targetRole`) e'tiborga olinmaydi | Ustunlar saqlanadi (`Notice.java:37-47`), lekin `findActiveNotices`/`countUnreadForUser`/`findAll` rol bo'yicha filtrlamaydi (`NoticeRepository.java:21-49`, `NoticeService.java:39-69, 179-183`) | "Faqat adminlar" e'loni o'qituvchi/SM qo'ng'iroqchasida ko'rinadi va unread'ga qo'shiladi | `publishedTo IN ('ALL', :audience)` + `targetRole IS NULL OR targetRole = :role` shartlari; `publishedTo` qiymatlarini enum qilish |
| S-02 | ↪ N-02 (P1, 6-modulda sanalgan) | Qoralama/nofaol/muddati o'tgan e'lonlar har kimga ochiq | `GET /api/notices` va `/{id}` — AUTH (`NoticeController.java:25-31, 69-73`; `SecurityConfig.java:113`), servis filtrsiz (`NoticeService.java:39-57`) | T/SM/ACC chop etilmagan e'lonni o'qiydi | Ro'yxat/detal: SA,A dan boshqalarga faqat faol+chop etilgan; boshqaruv ro'yxati alohida (`/api/notices/manage`) |
| S-03 | P1 | Sozlamalar ombori yo'q — markaz parametrlari faqat yml/kodda | `SettingsController.java:15-26` (yagona endpoint, hisoblanadi); §10.2 ro'yxati; o'quv yili boshlanishi (sentyabr) kodda | Billing grace/cancel muddatlari, payroll cutover, eslatma vaqti, ish vaqti, Telegram yoqish — admin panelidan o'zgartirib bo'lmaydi | `app_settings(key PK, value jsonb, updated_by, updated_at)` + `SettingsService` (kesh, yml default), `GET/PUT /api/settings/{group}` (SA), `@Audited` |
| S-04 | P1 | Bildirishnomalar markazi yo'q, push faqat chat uchun | Notification entity yo'q; WS faqat chat topiklari (`WebSocketConfig.java:51-55`, `ChatSocketController.java:64`); ADM `src/layouts/components/NotificationsBell.vue:8` — stub ("hozircha bo'sh"); ADM `src/layouts/composables/useSidebarBadges.ts:11-23` — faqat unlock count | Lid tayinlandi, vazifa muddati, unlock/ta'til arizasi, to'lov bekor qilindi — xodimga xabar yetmaydi; frontend 4–5 alohida so'rov bilan polling qiladi | §10.6 dagi minimal dizayn |
| S-05 | ↪ N-03 (P2) | Rejalashtirilgan e'lon ishlamaydi | `findActiveNotices` da `publishedAt <= now` sharti yo'q (`NoticeRepository.java:21-27`) | Kelajak sanali e'lon darhol chiqadi | Shart qo'shish |
| S-06 | ↪ N-07 (P2) | `markRead` poygasi → 500 | `NoticeService.java:149-160` (exists → insert, UNIQUE `uk_notice_reads_notice_user`) | Ikki marta tez bosish → DataIntegrityViolation | `INSERT … ON CONFLICT DO NOTHING` (native) yoki ushlab yutish; `markAllRead` (`:162-177`) — `saveAll` |
| S-07 | ↪ N-09, N-11 (P2) | E'lon create/update audit'siz va muallifni soxtalashtirish | Faqat delete `@Audited` (`NoticeService.java:142`); `createdById` istalgan user (`NoticeRequest.java:27`, `NoticeService.java:92-94`) | Kim e'lon yozgani noaniq; A boshqa xodim nomidan e'lon chiqaradi | `createdById` ni olib tashlash (doim joriy user); CREATE/UPDATE audit |
| S-08 | ↪ N-11 (P2) | `GET /api/notices` `size` cheklanmagan | `NoticeService.java:41` | `size=100000` | `min(size,100)` |
| S-09 | P2 | Ta'til arizalari uchun count yo'q | `LeaveController.java:40-44` faqat ro'yxat | Badge uchun butun ro'yxat yuklanadi | `GET /api/leaves/pending/count` yoki yig'ma badge endpoint |
| S-10 | P2 | Telegram shablonlari kodda, eslatma stub | `TelegramService.java:60-98` | Matnni o'zgartirish = redeploy; `sendPaymentReminder` hech narsa yubormaydi | Shablonlar jadvali (`message_templates`) + o'zgaruvchilar; stub'ni o'chirish |
| S-11 | P2 | `/api/settings/academic-year` frontendda ishlatilmaydi | FE `views/layouts/layout-header.vue:400-405` o'zi hisoblaydi | Ikki joyda mantiq | O'quv yilini sozlamaga ko'chirish (S-03) va front shu endpointni o'qisin |

P0 topilmadi (ruxsat qoidalari tartibi to'g'ri: `/api/settings/academic-year` permitAll faqat GET bitta yo'l; notice POST read-status qoidasi admin POST'dan oldin — `SecurityConfig.java:230-235`).

### 10.4 billing-v2 / payroll-v2 / dashboard bilan bog'liqlik

- Billing (`BillingProperties`), payroll (`PayrollProperties`), dashboard (`DashboardProperties`) parametrlari **faqat yml/default** — DB yo'q (§10.2). Masalan `payroll.cutover-date` bo'sh bo'lsa APPLIED billing migratsiyasidan olinadi (`PayrollProperties.java:19-23`); `billing.migration-go-live = 2026-09-18` kodda default (`BillingProperties.java:43`).
- To'lov eslatmasi (`PaymentReminderService.java:42`) billing v2 qarzidan (`buildPaymentMessage` BigDecimal) — Telegram ota-onaga; cron `app.billing.reminder-cron`.
- Direktor digest — yagona "bildirishnoma" ko'rinishidagi xodim xabari, chat id'lar env'da (`application.yml:74-77`), default o'chiq.
- Billing/payroll hodisalari (to'lov bekor qilindi, oylik tasdiqlandi/to'landi) uchun ichki bildirishnoma yo'q — faqat audit yozuvi.

### 10.5 Eski frontend nima ishlatadi

| Servis/funksiya | Endpoint | Mos emas |
|---|---|---|
| `FE:services/noticeService.js:8-10` `getLatest({page:0,size:10})` ← `layout-header.vue:462` | `GET /api/notices/active` | **Backend `limit` kutadi** (`NoticeController.java:37`) — `page/size` e'tiborsiz, 50 ta qaytadi |
| `noticeService.getUnreadCount` (`:32-34`) ← `layout-header.vue:453` | `GET /api/notices/unread-count` | Mos (`count`). Faqat `onMounted` da (`layout-header.vue:572-576`) — polling/push yo'q, yangi e'lon sahifa yangilanmaguncha ko'rinmaydi |
| `noticeService.markAllRead` ← `layout-header.vue:471`; `markRead` ← `:540` | `POST /api/notices/read-all`, `/{id}/read` | Mos |
| Header `notif-item-text`: `n.message \|\| n.content \|\| n.description` (`layout-header.vue:206`) | — | Backend faqat `content` beradi — ishlaydi |
| `noticeService.getAll()` ← `views/pages/notices/notice-list.vue:194` | `GET /api/notices` | Parametrsiz → 20 ta; pagination UI yo'q (TAXMIN) |
| Chat badge `stores/chat.js:32` + `services/chatSocket.js:37, 48` | `GET /api/chat/unread-count` + STOMP `/topic/conversation.{id}`, `/topic/presence` | Mos; WS'da shaxsiy "unread" hodisasi yo'q — front suhbat topiklaridan o'zi hisoblaydi |
| `attendanceService.getUnlockRequestsCount` ← `views/layouts/vertical-sidebar.vue:239` | `GET /api/attendance/unlock-requests/count` | Mos (`data` = son) |
| O'quv yili `layout-header.vue:400-405` | — (endpoint chaqirilmaydi) | S-11 |
| "Sozlamalar" sahifalari (`router/index.js:150-154`, `notifications-settings`, `school-settings`, `religion`…) | — | PreSkool shablonidan DEMO, backend yo'q (`ADM:docs/spec/frontend-audit.md:184, 190`) |

Yangi admin: `NotificationsBell.vue` bo'sh holat (`:8`), `useSidebarBadges.ts` faqat `attendance.unlock` (`:13-17`, 60 s polling — `modules/attendance/queries`), `matrix.ts:155-161` `notices` (SA,A) bo'limi e'lon qilingan, `src/modules/` da notices moduli yo'q.

### 10.6 Yetishmayotgan narsalar (edutizim.uz uslubidagi CRM'ga nisbatan)

| P | Narsa | Izoh / minimal dizayn |
|---|---|---|
| P1 | **Bildirishnomalar markazi** | `notifications(id, user_id FK, type VARCHAR(50), title, body, entity_type, entity_id, link, created_at, read_at NULL)`, indeks `(user_id, read_at, created_at DESC)`; `NotificationService.notify(userIds, …)` — `afterCommit` da; manbalar: lid tayinlandi (`LeadService:301`), vazifa tayinlandi/muddati o'tdi (`TaskService:271` + scheduler), unlock/ta'til arizasi yaratildi (SA,A ga), ariza tasdiqlandi/rad (arizachiga), to'lov bekor qilindi, oylik tasdiqlandi/to'landi (xodimga), yangi e'lon (auditoriyaga). Endpointlar: `GET /api/notifications?unreadOnly&page&size`, `GET /api/notifications/unread-count`, `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all`. Push: `convertAndSendToUser(username, "/queue/notifications", dto)` (mavjud `/user` prefiksi, `WebSocketConfig.java:54`); retention (masalan 90 kun) |
| P1 | **Yig'ma badge endpoint** | `GET /api/me/badges` → `{notices, chat, notifications, unlockPending (SA/A), leavePending (SA/A), tasksOverdue, tasksToday}` — 5 ta alohida so'rov o'rniga |
| P1 | **Markaz/filial sozlamalari** | nom, logotip (fayl), manzil, telefon, Telegram/Instagram, rekvizitlar (chek va shartnoma uchun), vaqt zonasi, valyuta; hozir "Adizone o'quv markazi" kodda (`TelegramService.java:79, 95`). Filial (branch) tushunchasi butunlay yo'q (P2 — ko'p filial kerak bo'lsa katta o'zgarish) |
| P1 | **To'lov sozlamalari** (DB) | grace kunlari, bekor qilish muddati, eslatma vaqti/yoqish, to'lov usullari yoqish/o'chirish (hozir enum `PaymentMethod`), chek prefiksi |
| P1 | E'lon nishonlash (S-01) va qoralama yashirish (S-02) | |
| P2 | SMS/Telegram shablonlari | `message_templates(code, channel, lang, body, is_active)`, o'zgaruvchilar `{studentName}`, `{debt}`…; SMS provayder integratsiyasi yo'q (Eskiz/Playmobile) |
| P2 | Ish vaqti / ish kunlari markaz sozlamasi | hozir faqat `DashboardProperties.workStart/workEnd/workDays` (yml) |
| P2 | Bayramlar | `holidays` CRUD bor (SA,A), lekin billing'ga ta'sir qilmaydi (siyosat hujjatlashtirilishi kerak), audit'siz, takrorlanuvchi (har yil) bayram yo'q |
| P2 | O'quv yili sozlamasi | boshlanish oyi/sanasi DB'da |
| P2 | Xodim bildirishnoma afzalliklari | qaysi tur — in-app/Telegram (`users` da Telegram chat id yo'q — faqat `parents.telegram_chat_id`, `entity/Parent.java:34-35`) |
| P2 | Audit eksport | 8.6 ga qarang |

### 10.7 DB tekshiruvi uchun SQL (read-only)

```sql
-- 1) Sozlamalar/bildirishnoma jadvallari bormi (kutilgan: faqat notices, notice_reads, holidays)
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public'
  AND (table_name ILIKE '%setting%' OR table_name ILIKE '%notif%' OR table_name ILIKE '%notice%'
       OR table_name ILIKE '%holiday%' OR table_name ILIKE '%template%' OR table_name ILIKE '%branch%');

-- 2) notices ustunlari va cheklovlari (Hibernate eski enum CHECK qoldirganmi; FK'lar)
SELECT conname, contype, pg_get_constraintdef(oid) FROM pg_constraint
WHERE conrelid IN ('notices'::regclass, 'notice_reads'::regclass) ORDER BY conrelid::regclass::text, conname;
SELECT indexname, indexdef FROM pg_indexes WHERE tablename IN ('notices','notice_reads');

-- 3) Nishonlash ishlatilyaptimi (S-01) va qoralamalar soni (S-02)
SELECT published_to, target_role, is_active, is_published,
       (expires_at IS NOT NULL AND expires_at < date_trunc('day', now())) AS expired,
       (published_at > now()) AS scheduled, count(*)
FROM notices GROUP BY 1,2,3,4,5,6 ORDER BY 7 DESC;

-- 4) notice_reads yaxlitligi: dublikat (bo'lmasligi kerak) va faol foydalanuvchilar bo'yicha o'qilmaganlar
SELECT notice_id, user_id, count(*) FROM notice_reads GROUP BY 1,2 HAVING count(*) > 1;
SELECT u.role, count(*) AS users,
       avg((SELECT count(*) FROM notices n WHERE n.is_active AND n.is_published
            AND (n.expires_at IS NULL OR n.expires_at >= date_trunc('day', now()))
            AND NOT EXISTS (SELECT 1 FROM notice_reads r WHERE r.notice_id = n.id AND r.user_id = u.id))) AS avg_unread
FROM users u WHERE u.is_active GROUP BY u.role;

-- 5) Bayramlar va badge manbalari hajmi (yig'ma endpoint uchun asos)
SELECT count(*) AS holidays, min(holiday_date), max(holiday_date) FROM holidays;
SELECT (SELECT count(*) FROM attendance_unlock_requests WHERE status = 'PENDING') AS unlock_pending,
       (SELECT count(*) FROM leave_requests WHERE status = 'PENDING') AS leave_pending;
-- jadval nomlari: entity/AttendanceUnlockRequest.java:10, entity/Leave.java:12 (status default 'PENDING', :46)
```

**Lokal bazadagi natija:**
1. Topilgan jadvallar: `settings` (legacy, 4 qator, kodda ishlatilmaydi), `notifications` (legacy, 0 qator, entity yo'q), `notices`, `notice_reads`, `holidays`, `contract_templates`. `branch` yo'q.
2. Cheklov va indekslar holati §6.2 da: V41 indekslari yo'q, ikkita `created_by` FK bor.
3. `notices` bo'sh.
4. `notice_reads` bo'sh.
5. `holidays` bo'sh; `unlock_pending` = 0, `leave_pending` = 0.

### 10.8 Xulosa

**Holat:**
- **Sozlamalar — Yo'q.** Bitta hisoblanadigan endpoint bor, qolgan hamma narsa yml/kodda. Legacy `settings` jadvali o'lik.
- **Bildirishnomalar — Qisman.** E'lonlar, o'qilganlik, chat unread va WS bor. Bildirishnomalar markazi, push va nishonlash yo'q. Yangi admin'dagi qo'ng'iroqcha hozircha bo'sh (stub).

**P0: 0 · P1: 2 · P2: 3.**
- P1: S-03, S-04.
- P2: S-09, S-10, S-11.
- Hisobga kirmagan havolalar: S-01, S-02, S-05…S-08 — ular N-01, N-02, N-03, N-07, N-09, N-11 bilan bir xil va 6-modulda sanalgan.

---

## §11. Umumiy xulosa jadvali

| # | Modul | Holat | P0 | P1 | P2 | Yangi admin'da hozir | Frontenddan oldin backend tuzatish kerakmi |
|---|---|---|---|---|---|---|---|
| 1 | O'qituvchilar | Qisman | 0 | 8 | 6 | `modules/teachers`: faqat detal sahifa, selektor ro'yxati, `ensureProfile` | **Ha:**<br>- T-01 (PUT maydonlarni NULL qiladi);<br>- T-02 (`userId` tekshiruvi);<br>- T-06 (sahifalash);<br>- T-07 (`ensure-profile` SKIPPED → 409).<br>KPI'ni `data.current.*` dan o'qish mumkin |
| 2 | Foydalanuvchilar va rollar | **Muammoli** | 2 | 8 | 4 | Yo'q (`matrix.ts` da bo'lim bor) | **Ha:** U-01, U-02 (P0), U-03, U-04, U-05, U-06 (409) |
| 3 | Ta'tillar | Qisman | 0 | 5 | 7 | Yo'q (faqat menyu) | **Ha:** L-01, L-02, L-03 (status va sana validatsiyasi). L-04 va L-05 — biznes qarori (§13) |
| 4 | Imtihonlar | **Muammoli** | 3 | 7 | 9 | Yo'q (faqat menyu) | **Ha:** E-01, E-02, E-03 (P0), E-04 (`GET /{id}/registrations`), E-06, E-10 |
| 5 | Uy vazifalari | Qisman | 0 | 4 | 11 | Yo'q (faqat menyu) | **Ha:** H-01, H-02 (egalik). H-03 va H-04 — qaror (§13) |
| 6 | E'lonlar | Qisman | 0 | 2 | 10 | Yo'q (faqat menyu) | **Ha:** N-01, N-02 (auditoriya, qoralamalar), N-06 |
| 7 | Shartnomalar | **Muammoli** | 1 | 5 | 7 | Yo'q (faqat menyu) | **Ha:** C-01 (P0), C-02, C-03, C-06. C-04 va C-05 — narx qarori |
| 8 | Audit jurnali | Qisman | 0 | 3 | 11 | Yo'q (faqat menyu) | **Yo'q** — o'qish API tayyor. A-01 va A-02 parallel bajariladi |
| 9 | Ichki chat | **Muammoli** | 2 | 9 | 9 | Yo'q (faqat menyu kaliti) | **Ha:** CH-01, CH-02 (P0), CH-03, CH-05, CH-09 |
| 10 | Sozlamalar va bildirishnomalar | Sozlamalar **Yo'q** / bildirishnomalar Qisman | 0 | 2 | 3 | Bell — stub; badge — faqat unlock | **Ha:** S-03 (sozlamalar ombori), S-04 (`notifications` + `/api/me/badges`) — yangi backend ishi |
| X | Kesishuvchi | — | 0 | 2 | 5 | — | **Ha:** X-02 (409/400 handler'lari), X-01 (prod sxema tekshiruvi) |
| | **Jami** | | **8** | **55** | **82** | | |

*Izoh:*
- 10-moduldagi S-01, S-02 va S-05…S-08 6-modul bilan bir xil, shuning uchun bir marta (6-modulda) sanalgan.
- X-07 CH-04 da sanalgan.

---

## §12. Tavsiya etilgan ish tartibi

### 12.1 Bosqich 0 — backend "xavfsizlik paketi" (frontenddan OLDIN, ~2–3 kun, bitta PR)

Hammasi kichik va lokal o'zgarishlar, test bilan:

1. **CH-01** — SUBSCRIBE oq ro'yxati. Wildcard'lar (`*`, `?`, `{`) rad etiladi. Interceptor uchun test.
2. **CH-02** — rasm o'lchamini `ImageReader.getWidth/Height` bilan o'qish va piksel chegarasi.
3. **U-02** — ST/P rollari uchun `denyAll` (faqat `/api/auth/**`). `GET /api/search` faqat xodimlarga.
4. **U-01** — `create-for-teacher`: band username → 409. Bog'lash faqat rol=TEACHER va bo'sh user bo'lsa.
5. **E-01 + E-02** — enum taqqoslash; `assertExamAccess`/`assertOwnsStudent`; eligible — faqat imtihon guruhi.
6. **C-01** — `contract_number` uchun sequence (format §13 dagi qarorga ko'ra).
7. **E-03** — `@JsonAlias("changeReason")` (eski UI prod'da ishlab turgan ekan).
8. **X-02** — `DataIntegrityViolationException` → 409, `MissingServletRequestParameterException` → 400.
9. **U-03, U-04, U-05** — ADMIN↔ADMIN, PUT orqali bloklash, parol minimal uzunligi va `token_version`. P1, lekin xavfsizlik bo'lgani uchun shu paketda.
10. **X-01** — prod'da §0.2 so'rovini read-only ishga tushirish; yetishmagan indeks va UNIQUE'larni bitta idempotent skript bilan qo'shish (alohida, kelishilgan oynada).

### 12.2 Frontend modullari tartibi (har biri oldidan o'z backend P1 tuzatishlari bilan)

| Navbat | Modul | Nega shu tartibda | Oldidan backend | Frontend hajmi (TAXMIN) |
|---|---|---|---|---|
| 1 | **Foydalanuvchilar va rollar** | O'qituvchi logini, oylik qoidalari va kassa selektorlari shunga tayanadi. Xavfsizlik tuzatishlarini darhol UI'da ishlatish mumkin | U-06 (409), U-10 (`?page&size&role&active&q` yoki `/api/users/options`), U-14 | 2–3 kun |
| 2 | **O'qituvchilar** (ro'yxat, forma, status, login, KPI) | Modul allaqachon qisman bor. Payroll-v2 va dashboard bilan bog'liq | T-01 (PATCH semantikasi), T-02, T-03 (faol guruh bo'lsa 409 + `reassignTo`), T-06 (sahifalash + yengil DTO), T-07, T-08 (audit) | 3–4 kun |
| 3 | **Audit jurnali** | Backend tayyor, tez natija. Keyingi modullardagi o'zgarishlarni kuzatishga yordam beradi | A-01 va A-02 parallel; A-04 (entity tarixi sahifalash) — ixtiyoriy | 1–2 kun |
| 4 | **E'lonlar + qo'ng'iroqcha (bell)** | Kichik modul. Bell topbarda har kuni ko'rinadi | N-01, N-02, N-06; `GET /api/me/badges` (S-04 ning birinchi qadami) | 2 kun |
| 5 | **Ta'tillar** | Biznes qarorlari kerak (§13 Q3–Q6). Qarorgacha "yozuv + tasdiqlash" bosqichini chiqarish mumkin | L-01, L-02, L-03, L-11 (`teacherId`, `days`); L-04 va L-05 qarordan keyin | 2 kun (+ bog'lanishlar 3–5 kun backend) |
| 6 | **Imtihonlar** | P0'lar 12.1 da yopilgan bo'ladi | E-04 (registrations API), E-05, E-06, E-09, E-10 (filtrlar), E-07 (natija tarixi) | 3–4 kun |
| 7 | **Uy vazifalari** | O'quvchi portali qarori kerak (Q11) | H-01, H-02, H-04 (fayl yuklash), H-06, H-07 | 2–3 kun |
| 8 | **Shartnomalar** | Narx va PDF qarori kerak (Q8, Q9) | C-02, C-03, C-04, C-05 (`studentGroupId` + narx snapshot), C-06 (holat mashinasi), C-07 (escape); PDF (P1) | 3–5 kun |
| 9 | **Ichki chat** | Eng katta modul. Eski frontend ishlayveradi | CH-03, CH-04 (fayllar auth bilan), CH-05 (guruh boshqaruvi + hodisalar), CH-06 (rol siyosati), CH-07 (heartbeat), CH-08, CH-09 (xatoda `clientId`) | 6–8 kun |
| 10 | **Sozlamalar + bildirishnomalar markazi** | Yangi backend ishi (jadval, servis, WS). Avval qaysi parametrlar kerakligini kelishish kerak (Q15, Q16) | S-03 (`app_settings` + `SettingsService`), S-04 (`notifications` + `/user/queue/notifications`) | backend 4–6 kun + frontend 3 kun |

**Asosiy qoida:** har modulda sahifa ochilishidan oldin backenddagi P0 va o'sha modul P1'lari yopilgan bo'lishi kerak (adizone-admin CLAUDE.md: "Sahifa ochilib API 403 bersa — bu xato"). Ruxsatlar matritsasi (`matrix.ts`) har modulning N.1 bo'limidagi **effektiv rollar** bilan solishtirilsin.

---

## §13. Buyurtmachi bilan aniqlash kerak bo'lgan savollar (taklif bilan)

| # | Savol | Taklif | Ta'sir qiladigan topilmalar | Qaror (2026-10-02) |
|---|---|---|---|---|
| Q1 | O'quvchi/ota-ona uchun shaxsiy kabinet (portal) yaqin rejadami? | **Hozircha yo'q.** ST/P loginlarini bloklash va UI'dan "login yaratish"ni olib tashlash. Portal alohida loyiha | U-02, U-08, H-03, CH-06, N-01 | **Qaror:** STUDENT/PARENT logini bloklanadi. Bajarildi: login → 403 `auth.roleNotAllowed`, URL qoidalari faqat xodimlarga (§14). |
| Q2 | ADMIN boshqa ADMIN'ni (parol reset, bloklash, rol) boshqara oladimi? | **Yo'q** — ADMIN/SA hisoblarini faqat SUPER_ADMIN boshqaradi. Oxirgi faol SA'ni bloklash taqiqlanadi | U-03, U-04 | **Qaror:** ADMIN → ADMIN yo'q, ADMIN/SA hisoblarini faqat SUPER_ADMIN boshqaradi. Bajarildi (U-03). |
| Q3 | Ta'til turlari qaysilar va qaysilari haq to'lanmaydi? | CASUAL, SICK, MEDICAL, MATERNITY, UNPAID, OTHER. **UNPAID va OTHER** uchun fixed maoshdan `fixed × ta'til ish kunlari / oy ish kunlari` chegirma | L-05, L-10, 3.6 #3 | Buyurtmachidan kutilmoqda |
| Q4 | Ta'til tasdiqlanganda darslar nima bo'ladi: bekor qilinadimi yoki o'rinbosar o'qituvchi tayinlanadimi? O'rinbosarga haq to'lanadimi? | Tasdiqlash oynasida admin har guruh uchun tanlaydi: "bekor" (LessonException CANCELLED) yoki "o'rinbosar". O'rinbosar o'tgan darsning ulushi o'rinbosarga yoziladi | L-04, L-05 | Buyurtmachidan kutilmoqda |
| Q5 | Darslar o'qituvchi ta'tili sababli bekor qilinsa, o'quvchilardan to'lov olinadimi? | Billing-v2 qoidasiga ko'ra hal qilinadi (PER_LESSON — olinmaydi; oylik — biznes qarori). Hozir hech qayerda hujjatlanmagan | L-04, 3.4 | Buyurtmachidan kutilmoqda |
| Q6 | Ta'til faqat o'qituvchilar uchunmi yoki barcha xodimlar uchunmi? Kvota (yillik kunlar) yuritiladimi? | **Barcha xodimlar** (`user_id` asosida). Yillik kvota serverda hisoblanadi; `teachers.*_leaves` legacy ustunlari kvota sifatida qayta ishlatiladi yoki olib tashlanadi | L-12, 3.6 #3 | Buyurtmachidan kutilmoqda |
| Q7 | Imtihon alohida pullikmi? "Imtihonga kirish" shartlari (≥8 marta qatnashgan + to'lagan) saqlanadimi? | Imtihon to'lovi preview **olib tashlansin**. Kirish sharti: billing-v2 snapshot bo'yicha qarz yo'q + sozlanadigan minimal davomat (faqat imtihon guruhi va davri bo'yicha) | E-01, E-08, E-14 | Buyurtmachidan kutilmoqda |
| Q8 | Shartnoma raqami formati va imzolangan shartnomani o'chirish mumkinmi? | `CTR-YYYY-NNNNN` (PG sequence). Imzolangan/qabul qilingan shartnoma o'chirilmaydi — faqat `CANCELLED` | C-01, C-06 | **Qaror:** `CTR-YYYY-NNNNN`, sequence. Bajarildi (C-01, V58). Hisoblagich yil almashganda nolga qaytmaydi. |
| Q9 | Shartnomadagi narx qaysi bo'lsin: kurs narxi yoki chegirmali effektiv narx? PDF serverda kerakmi? Oferta qanday qabul qilinadi? | **Effektiv narx** (billing-v2 `EnrollmentPricing`) yozilmaga bog'lab snapshot qilinadi. Server PDF — P1 (OpenHTMLtoPDF). Oferta qabuli: SMS/Telegram kod — P2 | C-04, C-05, 7.6 | Buyurtmachidan kutilmoqda |
| Q10 | Shartnoma va cheklarda markaz rekvizitlari (nom, manzil, STIR, bank) qayerdan olinsin? | Sozlamalar → "Markaz ma'lumotlari" (S-03). Hozirgi "Adizone" literali olib tashlanadi | C-12, S-03 | Buyurtmachidan kutilmoqda |
| Q11 | Uy vazifasini o'quvchi o'zi topshiradimi yoki o'qituvchi belgilaydimi? Qaysi fayl turlari va qancha hajm? | Portalsiz bosqichda **o'qituvchi belgilaydi** (topshirdi/baho). Vazifaga PDF/DOCX/rasm biriktirish ≤10 MB, fayl auth bilan beriladi | H-03, H-04, H-12 | **Qaror:** topshiriqni o'qituvchi belgilaydi (portal yo'q). |
| Q12 | E'lon kimlarga yuboriladi: rollar, guruhlar, ota-onalar? Telegram'ga ham ketadimi? | Bosqich 1: rollar to'plami (`ALL` yoki rollar ro'yxati). Bosqich 2: guruh va ota-onalarga Telegram (mavjud bot orqali) | N-01, N-05, 6.6 | **Qaror:** e'lon auditoriyasi — rollar. |
| Q13 | Ichki chatda kim kim bilan yozisha oladi? O'quvchi va ota-ona chatda bo'ladimi? Xabarlar va fayllar qancha saqlanadi? | **Faqat xodimlar** (ST/P yo'q). Fayllar faqat a'zolarga (auth). Saqlash muddati — cheksiz, admin moderatsiya logi bilan | CH-04, CH-06, 9.6 | **Qaror:** chat faqat xodimlar. Bajarildi: `/api/chat/**` va WS handshake faqat xodim rollari. |
| Q14 | Audit jurnali qancha saqlansin? | Moliyaviy amallar (PAYMENT*, REFUND, payroll, salary rule) — **≥ 1 yil**, qolganlari 90 kun. Lid tayinlash tarixi va `lastSeen` audit'dan emas, o'z jadvallaridan olinadi | A-03, A-01 | **Qaror:** audit — moliya 1 yil, qolgani 180 kun. |
| Q15 | Admin paneldan qaysi sozlamalar o'zgartirilishi kerak? Filiallar bormi yoki rejalashtirilganmi? | Markaz rekvizitlari va logotip; to'lov sozlamalari (grace kunlari, bekor qilish muddati, eslatma vaqti); ish vaqti va ish kunlari; o'quv yili boshlanishi; Telegram yoqish/o'chirish. **Filial — hozircha yo'q** | S-03, X-06 | **Qaror:** filial yo'q. |
| Q16 | Xodimlarga qaysi hodisalar haqida bildirishnoma kerak va qaysi kanal orqali? | In-app + WebSocket. Hodisalar: lid tayinlandi, vazifa tayinlandi yoki muddati o'tdi, unlock/ta'til arizasi (adminlarga), ariza natijasi (arizachiga), oylik tasdiqlandi yoki to'landi. Xodim uchun Telegram — P2 | S-04, 10.6 | Buyurtmachidan kutilmoqda |
| Q17 | Eski frontend (`adizone-crm-front`) prod'da qachongacha ishlaydi? | Yangi admin to'liq chiqquncha. Shu davrda faqat backend tomondagi moslik tuzatishlari (E-03 `@JsonAlias`, `limit`/`size` aliasi) qilinadi, eski frontend kodiga tegilmaydi | E-03, N-05/6.5, 8.5 | — |
| Q18 | Prod bazasini read-only tekshirishga ruxsat bormi (yoki yangi dump)? | Ha, §0.2 va har modulning N.7 so'rovlari prod nusxasida bajarilsin. Lokal bazada bu modullarning ma'lumoti deyarli yo'q | X-01, barcha N.7 | — |
| Q19 | `teachers.monthly_salary` / `basic_salary` va ta'til kvota ustunlari UI'da ko'rsatilsinmi? | **Yo'q** — oylikning yagona manbai payroll-v2 `salary_rules`. Bu ustunlar yashiriladi va keyin olib tashlanadi | T-01, 1.4 | **Qaror:** legacy maosh ustunlari javoblardan chiqarilmaydi, UI ko'rsatmaydi. |
| Q20 | Parol siyosati va 2FA talabi bormi? | Parol min 8 belgi; reset'dan keyin majburiy almashtirish; login rate-limit. 2FA (Telegram OTP) — P2 | U-05, 2.6 | **Qaror:** parol ≥ 8 belgi, 2FA yo'q. Bajarildi (U-05, `PasswordPolicy`, max 72 — BCrypt chegarasi). |

---

## §14. Bajarildi: xavfsizlik bloki (2026-10-02)

§12.1 dagi blok bajarildi, commit qilinmagan. Testlar: H2 da `mvn test` → 246 ta, 0 xato (5 skip — faqat PostgreSQL'da ishlaydigan testlar); `mvn test -Dspring.profiles.active=pgtest` → 246 ta, 0 xato, 0 skip. Yangi testlar: `src/test/java/com/crm/security/*`.

### 14.1 P0 lar qanday yopildi

| ID | Yechim | Asosiy fayllar | Regression test |
|---|---|---|---|
| CH-01 | SUBSCRIBE oq ro'yxati:<br>- `/topic/presence` va `/user/queue/errors` — ruxsat;<br>- `^/topic/conversation\.\d+$` — faqat faol a'zoga;<br>- naqsh belgilari (`* ? { }`) va boshqa har qanday manzil (`/queue/errors-user…` ham) — rad. | `config/ChatChannelInterceptor.java` | `ChatSubscriptionGuardTest` |
| CH-02 | Rasm o'lchami faqat sarlavhadan o'qiladi (`ImageReader.getWidth/Height`), piksellar dekodlanmaydi, va diskka yozishdan OLDIN tekshiriladi:<br>- tomon ≤ 12000, jami ≤ 50 MP;<br>- fayl ≤ 4MB;<br>- aks holda 400 `chat.upload.imageDimensions` / `chat.upload.tooLarge`.<br>`ImageIO.read` olib tashlandi. Profil rasmi (`validateImage`) ham shu tekshiruvdan o'tadi. | `service/ChatAttachmentService.java`, `service/FileStorageService.java` | `ChatUploadLimitsTest` |
| U-01 | `create-for-teacher` endi faqat yaratadi:<br>- band login (registrsiz) → 409 `user.username.taken`;<br>- profilda login bor → 409 `teacher.user.alreadyLinked`;<br>- parol < 8 → 400 `user.password.size`.<br>Mavjud userga bog'lash olib tashlandi; "200 ALREADY_EXISTS" javobi ham yo'q. | `service/UserService.java`, `controller/UserController.java` | `UserManagementSecurityTest` |
| U-02 | STUDENT/PARENT:<br>- login → 403 `auth.roleNotAllowed` (parol to'g'ri bo'lsa ham, `LOGIN_FAILED` audit bilan);<br>- refresh → 403 va token bekor;<br>- WS handshake → 403;<br>- barcha "authenticated" URL qoidalari → `STAFF_ROLES` (14.2);<br>- ST/P hisobini yaratish → 403 `user.role.notAllowed`. | `config/SecurityConfig.java`, `service/AuthService.java`, `security/jwt/JwtHandshakeInterceptor.java` | `StaffOnlyAccessTest` |
| E-01 | Imtihonga kirish sharti:<br>- ≥ 8 PRESENT (o'zgarmagan);<br>- hisob yuritiladigan faol yozilma bor (trial yoki muzlatilgan emas), guruhli imtihonda aynan shu guruhda;<br>- shu yozilmalarning hech biri billing-v2 bo'yicha OVERDUE emas — `BillingStatusService.isOverdue` (enum, bugungi sana bilan).<br>Imtihon to'lovi mantig'iga tegilmadi (Q7). | `service/ExamService.java` | `ExamAccessTest` |
| E-02 | `register-student`, `calculate-payment`, `eligible-students`, `POST /results`: imtihon egaligi (`assertExamAccess`) + o'quvchi egaligi (`assertOwnsStudent`).<br>`eligible-students` nomzodlari: imtihon guruhi; guruhsiz bo'lsa — TEACHER uchun o'z guruhlari.<br>TEACHER javobida faqat `id, uuid, firstName, lastName, status, photoUrl`.<br>`calculate-payment` `@PreAuthorize` → SA, A, T. | `service/ExamService.java`, `controller/ExamController.java` | `ExamAccessTest` |
| E-03 | `ExamResultRequest.editNote` — `@JsonAlias("changeReason")` (deprecated alias). | `dto/request/ExamResultRequest.java` | `ExamAccessTest` |
| C-01 | Shartnoma raqami `CTR-YYYY-NNNNN`:<br>- `contract_number_seq` (V58) dan olinadi;<br>- YYYY — shartnoma sanasining yili;<br>- hisoblagich yil almashganda nolga qaytmaydi;<br>- eski `CTR-0001` raqamlari o'zgarmaydi. | `service/ContractService.java`, V58 | `ContractNumberAndV58Test` |

**P1 lar (shu blokda):**
- **U-03.** ADMIN/SUPER_ADMIN hisoblarini (tahrir, parol, faollik, rasm) va ADMIN/SA rollarini berishni faqat SA bajaradi — 403 `user.manage.adminProtected` / `user.role.adminGrant`.
- **U-04.** `PUT /api/users/{id}` dagi `isActive` va `role` endi PATCH /status bilan bir xil himoyadan o'tadi. Qiymat o'zgarmasa (eski frontend har tahrirda qayta yuboradi) tekshiruv ishlamaydi. O'zini bloklash, SA ni bloklash va o'z rolini o'zgartirish (`user.role.self`) taqiqlangan; rol o'zgarishi audit diff'iga tushadi.
- **U-05.** Parol 8–72 belgi (`PasswordPolicy`). Reset, admin almashtirishi va o'z parolini almashtirish `users.token_version` ni oshiradi. JWT dagi `tv` claim'i mos kelmasa token rad etiladi (401); claim'siz eski tokenlar 0-versiya hisoblanadi.
- **X-02.** Baza cheklovi xatolari SQLSTATE bo'yicha javob beradi:
  - `23505` → 409 `error.conflict.duplicate`;
  - `23503` → 409 `error.conflict.reference`;
  - `23502` / `22001` / `23514` → 400 `error.data.invalid`.

  Yetishmagan query parametri → 400 `error.param.missing`. Testlar: `ErrorMappingTest`, `ExamAccessTest`.
- **Qo'shimcha tuzatish (test ushladi).** `AuthService.refreshToken` endi `noRollbackFor` bilan ishlaydi. Avval rad etilgan refresh tokenning `isRevoked = true` belgisi istisno bilan birga rollback bo'lardi — muddati o'tgan token yo'lida ham.

**Bajarilmadi (P1, keyingi bosqich):**
- login uchun rate-limit/lockout (U-05 ning bir qismi);
- U-06 — `POST /api/users` dagi "200 ALREADY_EXISTS";
- U-07 — telefon dublikati;
- T-02 — `TeacherRequest.userId` tekshiruvsiz bog'lanadi;
- CH-03…CH-11;
- Q14 retention (180 kun / moliya 1 yil) kodda hali sozlanmagan — `app.audit.retention-days` hozir 90.

### 14.2 Rol cheklovi: avval "authenticated" bo'lgan qoidalar

`SecurityConfig.STAFF_ROLES` = SUPER_ADMIN, ADMIN, SALES_MANAGER, ACCOUNTANT, TEACHER.

| Qoida (SecurityConfig) | Avval | Endi |
|---|---|---|
| `GET /api/settings/**` (academic-year dan tashqari) | authenticated | STAFF |
| `GET /api/notices/**` | authenticated | STAFF |
| `/api/lead-stages/**` | authenticated | STAFF |
| `POST /api/notices/read-all`, `/api/notices/*/read` | authenticated | STAFF |
| `/api/chat/**` | authenticated | STAFF (Q13) |
| `/api/search/**` | authenticated | STAFF |
| `POST /api/files/**` | authenticated | STAFF |
| `anyRequest()` — boshqa qoidaga tushmagan barcha yo'llar (masalan `/api/auth/me`, `/api/auth/profile`, `/api/auth/change-password`, `/api/enums/**`) | authenticated | STAFF |
| `/ws` handshake (`JwtHandshakeInterceptor`) | yaroqli token | yaroqli token + STAFF rol |

**O'zgarmadi (permitAll):**
- `/api/auth/login`, `/refresh`, `/logout` — rol tekshiruvi servisda;
- `GET /api/settings/academic-year`;
- `GET /api/files/**` (CH-04, P1 ochiq qoldi);
- `POST /api/leads/public`, `/api/meta/webhook`, `/actuator/health`.

**Controller darajasida:**
- `isAuthenticated()` qolgan joylar (`ChatController.upload`, `FileController.upload`, `HomeworkController` submissions, `NoticeController`, `SearchController`) endi URL qoidasi bilan STAFF ga cheklangan;
- `ExamController.calculatePayment` → `hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')`.

### 14.3 V58 (`db/migration/V58__phase5_security.sql`, idempotent, qo'lda)

1. `users.token_version INTEGER NOT NULL DEFAULT 0`. Ustun yo'q bo'lsa qo'shiladi, NULL lar 0 ga o'tadi.
2. `contract_number_seq` + `setval(max(CTR-YYYY-NNNNN) + 1)`.
3. `idx_exam_registrations_exam`, `idx_exam_registrations_student` va `ux_exam_registrations_exam_student` UNIQUE(exam_id, student_id).
   - UNIQUE allaqachon bo'lsa (V27) — o'tkazib yuboriladi.
   - Dublikatlar bo'lsa — NOTICE, indeks yaratilmaydi.

**Qachon bajarish:** deploydan oldin tavsiya etiladi. Keyin bajarilsa ham to'g'ri: ddl-auto ustunni nullable qo'shadi, V58 uni NOT NULL qiladi. Test: pgtest profilida startda + `ContractNumberAndV58Test` ikki marta qayta bajaradi.

**Tekshirish:** `docs/ops/prod-schema-check.sql` (faqat o'qiydi, `BEGIN READ ONLY … ROLLBACK`). U quyidagilarni ko'rsatadi:
- V25–V58 ning yo'q bo'laklari;
- ma'noviy invariantlar;
- dublikat FK lar;
- CHECK lar;
- dublikat indekslar.

Lokal bazadagi natija (2026-10-02):
- 13 ta migratsiya qisman: V25, V26, V27, V29, V35, V36, V40, V41, V49, V50, V52, V56 va hali bajarilmagan V58;
- 53 ta dublikat FK juftligi;
- 26 ta dublikat UNIQUE/indeks;
- 0 ta CHECK.

### 14.4 Frontend shartnomasidagi o'zgarishlar (adizone-admin va eski front uchun)

| Endpoint | O'zgarish |
|---|---|
| `POST /api/auth/login`, `/refresh` | STUDENT/PARENT → 403 `{code: "auth.roleNotAllowed"}` |
| Parol almashtirilgach / tiklangach | Shu userning barcha access tokenlari darhol 401 qaytaradi. O'z parolini almashtirgan foydalanuvchi qayta kiradi |
| `POST /api/users`, `PUT /api/users/{id}`, `PUT /{id}/password`, `PUT /api/auth/change-password` | Parol ≥ 8 (avval 6, ba'zi joyda cheksiz) |
| `POST /api/users/create-for-teacher/{id}` | 201 yoki 409 (`user.username.taken`, `teacher.user.alreadyLinked`); "200 ALREADY_EXISTS" yo'q |
| `POST /api/users/create-for-student/{id}` | Doim 403 `user.role.notAllowed` |
| `/api/users/**` (ADMIN aktor) | ADMIN/SA nishon va ADMIN/SA rol berish → 403 |
| `PUT /api/users/{id}` | O'zini bloklash → 400; o'z rolini o'zgartirish → 400 `user.role.self`; SA ni bloklash → 403 |
| `GET /api/exams/{id}/eligible-students` | TEACHER: qisqa obyekt (telefon/manzilsiz); boshqa o'qituvchi imtihoni → 403 |
| `POST /api/exams/{id}/register-student`, `calculate-payment` | TEACHER — faqat o'z imtihoni va o'z guruhlari o'quvchilari |
| `PUT /api/exams/{examId}/results/{id}` | `editNote` yoki `changeReason` |
| `POST /api/contracts/generate` | `contractNumber` = `CTR-2026-00001` ko'rinishida |
| Har qanday endpoint | Cheklov buzilishi: 500 o'rniga 409/400 + `code` |

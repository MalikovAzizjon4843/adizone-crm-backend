# Ta'tillar, o'rinbosarlar, imtihon to'lovi, shartnoma (dizayn hujjati)

> **Holat:** amalga oshirildi (2026-10-02, `billing-v2` branch) — 1–6-bosqichlar, V61–V63. Yakuniy buyurtmachi qarorlari — §0.1 (hujjatning qolgan qismidan ustun). Bosqichlar bo'yicha fayllar va testlar — §11. Frontend uchun API — [leaves-exams-contracts-api.md](leaves-exams-contracts-api.md).
> **Asos:** [phase5-audit.md](../audit/phase5-audit.md) §3 (L-01…L-12), §4 (E-04…E-19), §7 (C-02…C-13), §10 (S-03), buyurtmachi javoblari (pastda §0).
> **Bog'liq:** [payroll-v2.md](payroll-v2.md) (§2 formulalar, §6 `calculationDetails`), [billing-v2.md](billing-v2.md) (§3.4 narx, §6.4 kassa teskari yozuvi).
> **Yo'llar:** Java — `src/main/java/com/crm/` ga nisbatan. **TAXMIN** — kod yozilganda tasdiqlanadigan da'vo.

## Mundarija
- §0 Buyurtmachi qarorlari
- §1 Ta'tillar: model, holatlar, API
- §2 O'rinbosar o'qituvchi: model, davomat ruxsati, API
- §3 Payroll-v2 ga ta'sir: `LEAVE_DEDUCTION`, `SUBSTITUTE_LESSONS`
- §4 Imtihon to'lovi va kassa
- §5 Sozlamalar: markaz rekvizitlari
- §6 Shartnoma: narx snapshot'i, PDF, chop etish
- §7 Migratsiyalar (V61–V63)
- §8 Testlar
- §9 Ochiq savollar (taklif bilan)
- §10 Ish tartibi va baho

---

## §0. Buyurtmachi qarorlari

| # | Qaror | Bu hujjatdagi aksi |
|---|---|---|
| D1 | Ta'til — **barcha xodimlar** uchun | §1: `leave_requests.user_id` asosiy, `teacher_id` ixtiyoriy |
| D2 | Tasdiqlashda **"haqli / haqsiz"** tanlanadi | §1: `paid` maydoni faqat tasdiqlashda majburiy |
| D3 | Haqsiz ta'til — belgilangan oylikdan **ish kunlari ulushi** ayiriladi, ish kunlari **Du–Sha** | §3.1: `LEAVE_DEDUCTION` |
| D4 | O'qituvchi ta'tilida darslar **davom etadi**, o'rinbosar tayinlanadi | §2: `lesson_substitutions` |
| D5 | O'rinbosar **o'tgan har darsi** uchun haq oladi | §3.2: `SUBSTITUTE_LESSONS` (stavka — §9 Q-A) |
| D6 | O'quvchilardan to'lov **o'zgarmaydi** | billing-v2 ga tegilmaydi: davrlar, `LESSON_CHARGE` va ledger o'zgarmaydi |
| D7 | Imtihon **pullik yoki bepul** (`fee = 0`) | §4: `exams.fee` |
| D8 | Qarzdor (**OVERDUE**) imtihonga yozilolmaydi | §4: E-01 dagi shart saqlanadi (`BillingStatusService.isOverdue`) |
| D9 | Imtihon to'lovi yozilishda **kassaga tushadi**, yozilish bekor qilinsa **qaytariladi** | §4.3: kassa INCOME → REVERSAL |
| D10 | Shartnomada **kurs narxi + chegirma + yakuniy summa** | §6.1: narx snapshot'i (`EnrollmentPricing`) |
| D11 | **Server PDF + chop etish** | §6.3: HTML → PDF |
| D12 | Rekvizitlar **Sozlamalardan**, faqat SA tahrirlaydi. Ma'lum qiymatlar: "ADIZONE LC" MChJ, STIR 311626069, Novza MFY Ye mavzesi 10-uy, +998 90 335 13 45. Bank va direktor — kutilmoqda | §5 |

### 0.1 Yakuniy qarorlar (2026-10-02) — hujjatning qolgan qismidan USTUN

| # | Qaror | Kodda |
|---|---|---|
| F1 | **Ta'til — hamma xodimlar** (SA, A, SALES, ACC, TEACHER). Tasdiqlashda **haqli/haqsiz** (`paid` majburiy). Haqsiz → `fixedSalary × haqsiz Du–Sha ish kunlari / oydagi Du–Sha ish kunlari`, **bayramlar ish kuni EMAS** (Q-E teskari yopildi: bayram surat ham, maxrajdan ham chiqadi) | `WorkdayCalendar`, `SalaryCalculationService.leaveDeduction` |
| F2 | **O'rinbosar MAJBURIY EMAS** — o'qituvchilar o'zaro kelishadi. SA/A muayyan **guruh + sana** uchun "darsni X o'tdi" deb belgilaydi (`lesson_substitutions`; dars oldidan ham, keyin ham). Shu dars davomatini o'rinbosar belgilaydi (asosiy o'qituvchi shu kuni — 403). Ta'tilga bog'lash ixtiyoriy (`leaveRequestId`) | `LessonSubstitutionService`, `AttendanceAccessService` |
| F3 | Haq: `SalaryRule.substituteLessonRate` (TEACHER, **qat'iy summa**, shaxsiy → rol qoidasi) × **o'tilgan** (davomati belgilangan, CONDUCTED) darslar. **Asosiy o'qituvchidan ayirilmaydi** (Q-A, Q-B yopildi). Stavka yo'q va o'tilgan dars bor — `calculable=false`, `SUBSTITUTE_RATE_MISSING` | `SUBSTITUTE_LESSONS` |
| F4 | Dars o'tilmasa — mavjud `LessonException CANCELLED` (MOVED ham): o'tilmagan (PLANNED) belgi avtomatik bekor | `LessonCalendarService.addException` |
| F5 | Payroll qatorlari `LEAVE_DEDUCTION`, `SUBSTITUTE_LESSONS` — `calculationDetails` formatida, `items.leaves[]`, `items.substitutions[]` bilan. **`VERSION` = 2 qoldi**: qatorlar va items qo'shimcha (ixtiyoriy) — eski v2 snapshot'lar o'qiladi, mavjud DRAFT lar qayta hisoblashsiz tasdiqlanadi, payroll-v2 testlari (version = 2) buzilmaydi (§3 dagi "VERSION = 3" o'rniga) | `PayrollCalculationDetails` |
| F6 | Imtihon: `fee` (0 = bepul); OVERDUE yozilolmaydi (bor edi); pullik — yozilishda to'lov **kassaga** (SA/A/ACC, usul, `Idempotency-Key`), bekor qilinsa kassaga **REVERSAL** | `ExamService.register/cancelRegistration` |
| F7 | Shartnoma: kurs narxi + chegirma + yakuniy summa (snapshot); **server PDF** (OpenHTMLtoPDF 1.1.87 + Noto) va **chop etish ko'rinishi** (`/print`, PDF bilan bir xil XHTML) | `ContractService`, `ContractPdfService` |
| F8 | Rekvizitlar (SA), boshlang'ich qiymatlar (V63): nomi `"ADIZONE LC" MChJ` / `ООО «ADIZONE LC»`; STIR `311626069`; manzil `Toshkent sh., Chilonzor t., Novza MFY, Ye mavzesi, 10-uy`; bank `ОПЕРУ АКБ «Капитал Банк»`, MFO `00974`, h/r `20208000007147330001`; direktor `Adizov Oqilbek Oybek o'g'li`; tel `+998 90 045 55 17`; litsenziya `Xabarnoma tasdiqnomasi №1180460 (reestr X-1743276)`; yordam telefoni (Mini App) `+998 77 337 32 33`. Q-C, Q-D yopildi (`contractCity` = "Toshkent shahri") | `CenterSettingsService`, V63 |

**Amalga oshirishda qabul qilingan kichik qarorlar (TAXMIN lar yopildi):**
- `POST /api/exams/{id}/calculate-payment` 410 emas — bir bosqich **imtihon narxini** qaytaradi (eski front va testlar buzilmasin; ruxsat tekshiruvi o'sha). `ExamPaymentCalculatorService` olib tashlandi.
- Guruhli **bepul** imtihon yaratilganda avtomatik yozish qoladi (FREE), OVERDUE o'quvchi yozilmaydi; **pullik** imtihonda avtomatik yozish yo'q.
- Imtihonni o'chirish faqat **to'langan** REGISTERED yozilish bo'lsa 409 (`exam.hasRegistrations`); bepul yozilishlar to'smaydi. `fee` esa har qanday REGISTERED bo'lsa o'zgarmaydi (`exam.feeLocked`).
- V62: eski `PENDING` (kassasiz) va kassasiz `PAID` (eski kod summa 0 da PAID yozardi) → `FREE`.
- Faol yozilmasi yo'q o'quvchiga shartnoma **narxsiz** tuziladi (snapshot NULL) — avvalgi xulq saqlandi; bir nechta faol yozilma — `studentGroupId` majburiy (400).
- PDF ga narx jadvali (kurs narxi, chegirma, yakuniy summa, boshlanish) shablondan qat'i nazar qo'shiladi (D10). Shablon tozalash jsoup emas — o'z oq ro'yxati (`ContractHtml`, atributsiz teglar), idempotent.
- O'tilgan (CONDUCTED) belgini ham SA/A bekor qila oladi (xato belgi), o'rinbosar oyligi APPROVED/PAID bo'lsa — 409 `substitution.payrollLocked`. Belgilash ham shu oyda yopiq oylikda 409.
- Haqsiz ta'tilni **tasdiqlash** ham (nafaqat bekor qilish) yopiq (APPROVED/PAID) oylik oyiga tushsa — 409 `leave.payrollLocked`.
- Ta'til bitta arizada ≤ 60 kalendar kun (`leave.tooLong`). Ta'til tasdiqlanganda/bekor qilinganda o'qituvchi statusi darhol yangilanadi (job kutilmaydi).
- Moliya hisobotida `examFees` — alohida qator, `netProfit` ga qo'shiladi.

---

## §1. Ta'tillar

### 1.1 Model — `leave_requests` (mavjud jadval kengaytiriladi)

| Ustun | Tur | Izoh |
|---|---|---|
| `id`, `uuid`, `created_at`, `updated_at` | mavjud | — |
| **`user_id`** | BIGINT FK users, NOT NULL (backfilldan keyin) | **Kim ta'tilda** (D1). Backfill: `teacher_id → teachers.user_id`, bo'lmasa `requester_id` |
| `teacher_id` | mavjud, ixtiyoriy | O'qituvchi bo'lsa avtomatik to'ldiriladi (o'rinbosar va dashboard uchun) |
| `requester_id` | mavjud | Kim ariza berdi (o'zi yoki admin) |
| `leave_type` | VARCHAR(30) → **enum** `LeaveType` | `ANNUAL, SICK, FAMILY, STUDY, OTHER` (TAXMIN: ro'yxat UI bilan kelishiladi). Haqli/haqsizni **tur emas, `paid` belgilaydi** (D2) |
| `from_date`, `to_date` | mavjud | `CHECK (from_date <= to_date)` |
| `reason` | mavjud | Faqat ariza beruvchining sababi |
| `status` | VARCHAR(20) → **enum** `LeaveStatus` | `PENDING → APPROVED / REJECTED`, `PENDING/APPROVED → CANCELLED` |
| **`paid`** | BOOLEAN NULL | `PENDING` da NULL; `APPROVED` da majburiy (D2); `REJECTED/CANCELLED` da NULL |
| **`decided_by`**, **`decided_at`**, **`decision_note`** | BIGINT FK, TIMESTAMP, TEXT | Eski `approved_by/approved_at` → `decided_*` ga ko'chiriladi. Rad etish sababi `reason` ga qo'shilmaydi (L-08) |
| **`cancelled_by`**, **`cancelled_at`** | — | Bekor qilish izi |

**Cheklovlar va indekslar:**
- `CHECK (from_date <= to_date)`;
- `CHECK (status <> 'APPROVED' OR paid IS NOT NULL)`;
- `idx_leave_requests_user (user_id, from_date)`, `idx_leave_requests_teacher (teacher_id)`;
- **kesishuv taqiqi:** bir xodimda PENDING/APPROVED ta'tillar ustma-ust tushmaydi. Asosiy himoya servisda, qulf ostida. PostgreSQL'da qo'shimcha `EXCLUDE USING gist (user_id WITH =, daterange(from_date, to_date, '[]') WITH &&) WHERE (status IN ('PENDING','APPROVED'))` — `btree_gist` kengaytmasi kerak (superuser). Bo'lmasa faqat servis tekshiruvi (TAXMIN: prod'da kengaytma bormi — tekshiriladi).

### 1.2 Holatlar va qoidalar

```
          submit                approve(paid)
 (yo'q) ──────► PENDING ─────────────────────► APPROVED
                   │  reject(note)                │ cancel (SA/A; payroll APPROVED/PAID bo'lsa — 409)
                   ▼                              ▼
                REJECTED         cancel ──►   CANCELLED
```

| Amal | Kim | Shart | Ta'sir |
|---|---|---|---|
| submit | xodim o'zi uchun; SA/A — istalgan xodim uchun | `from ≤ to`, kesishuv yo'q, `to − from ≤ 60` kun (TAXMIN: chegara sozlamaga) | PENDING; SA/A ga bildirishnoma (S-04 tayyor bo'lsa) |
| approve | SA, A (ADMIN o'z ta'tilini tasdiqlay olmaydi — SA) | PENDING, `paid` berilgan, kesishuv qayta tekshiriladi | APPROVED. O'qituvchi bo'lsa — §2 dagi darslar ro'yxati qaytadi |
| reject | SA, A | PENDING | REJECTED + `decision_note` |
| cancel | xodim — o'z PENDING arizasi; SA/A — PENDING/APPROVED | Davr oylari uchun APPROVED/PAID payroll bo'lsa **409** `leave.payrollLocked` | CANCELLED; PLANNED o'rinbosarlar bekor qilinadi |
| DELETE | olib tashlanadi (hard delete yo'q, L-09) | — | — |

- **O'qituvchi statusi:** kunlik job (Asia/Tashkent, 00:10) bugun APPROVED ta'tilda bo'lgan o'qituvchini `ON_LEAVE` ga, ta'til tugagach `ACTIVE` ga o'tkazadi. Faqat `ACTIVE ↔ ON_LEAVE`; `INACTIVE` ga tegilmaydi. `StaffStatusService` orqali.
- **Audit:** submit, approve (paid bilan), reject, cancel — `@Audited` + `AuditContext.change("status"/"paid")`.
- **Kvota (yillik kunlar):** bu bosqichda yo'q. Hisobot `GET /api/leaves/summary?userId&year` haqli/haqsiz kunlarni ko'rsatadi; kvota keyin (§9 Q-F).

### 1.3 API

| Metod | Yo'l | Rollar | Request / Response |
|---|---|---|---|
| GET | `/api/leaves` | SA, A | `?status&userId&teacherId&from&to&paid&page&size` (Specification, null-xavfsiz) → `PageResponse<LeaveResponse>` |
| GET | `/api/leaves/my` | STAFF | o'zi haqidagi va o'zi bergan arizalar |
| GET | `/api/leaves/{id}` | SA, A, egasi | `LeaveResponse` |
| POST | `/api/leaves` | STAFF (o'zi), SA/A (`userId` bilan) | `{userId?, leaveType, fromDate, toDate, reason}` → 201 |
| POST | `/api/leaves/{id}/approve` | SA, A | `{paid: boolean (majburiy), note?}` → `LeaveResponse` + `affectedLessons[]` |
| POST | `/api/leaves/{id}/reject` | SA, A | `{note}` (majburiy, 3–500) |
| POST | `/api/leaves/{id}/cancel` | egasi (PENDING) / SA, A | `{note?}` |
| GET | `/api/leaves/{id}/affected-lessons` | SA, A | o'qituvchining shu davrdagi rejadagi darslari: `[{groupId, groupName, lessonDate, startTime, endTime, substitution?}]` |
| GET | `/api/leaves/summary` | SA, A, egasi | `?userId&year` → `{paidDays, unpaidDays, byType}` |
| GET | `/api/leaves/pending/count` | SA, A | `{count}` (S-09) |

**`LeaveResponse` maydonlari:** `id, uuid, userId, userName, userRole, teacherId, requesterId, requesterName, leaveType, fromDate, toDate, days, workdays (Du–Sha), reason, status, paid, decidedById, decidedByName, decidedAt, decisionNote, createdAt`.

Eski `PATCH /{id}/status {status, reason}` bir bosqich deprecated alias sifatida qoladi: `APPROVED` + `paid` yo'q → 400 `leave.paidRequired`. `/teacher/{id}`, `/user/{id}`, `/pending` → `GET /api/leaves?…` ga yo'naltiriladi.

**Xato kodlari:**
- `leave.overlap` (409);
- `leave.dates.invalid` (400);
- `leave.paidRequired` (400);
- `leave.notPending` (409);
- `leave.payrollLocked` (409);
- `leave.selfApprove` (403).

---

## §2. O'rinbosar o'qituvchi

### 2.1 Model — yangi `lesson_substitutions`

| Ustun | Tur | Izoh |
|---|---|---|
| `id` | BIGSERIAL | — |
| `group_id` | FK groups NOT NULL | — |
| `lesson_date` | DATE NOT NULL | Guruhning rejadagi dars kuni (`GroupScheduleService.hasLessonOn` + `lesson_exceptions` MOVED/EXTRA) |
| `original_teacher_id` | FK teachers NOT NULL | Tayinlash paytidagi `group.teacher` (snapshot) |
| `substitute_teacher_id` | FK teachers NOT NULL | `≠ original`, faol (`ACTIVE`) o'qituvchi |
| `leave_request_id` | FK leave_requests NULL | Ta'tildan kelib chiqqan bo'lsa; ta'tilsiz bir martalik almashtirish ham mumkin |
| `status` | VARCHAR(20) | `PLANNED → CONDUCTED`, `PLANNED → CANCELLED` |
| `conducted_at`, `conducted_by` | — | Davomat belgilanganda (§2.3) |
| `created_by`, `created_at`, `cancelled_by`, `cancelled_at` | — | Audit |

**Cheklovlar:**
- `UNIQUE (group_id, lesson_date) WHERE status <> 'CANCELLED'` — bir darsga bitta faol o'rinbosar;
- `idx (substitute_teacher_id, lesson_date)`, `idx (original_teacher_id, lesson_date)`.

**Tekshiruvlar (servis):**
- o'rinbosarning shu kuni o'z darsi bilan vaqt to'qnashuvi → 409 `substitution.timeConflict`. Faqat ogohlantirish emas — rad etiladi (TAXMIN: buyurtmachi "ogohlantirish yetarli" desa yumshatiladi);
- dars sanasi rejada yo'q → 400 `substitution.noLesson`.

### 2.2 Holatlar va API

| Metod | Yo'l | Rollar | Izoh |
|---|---|---|---|
| POST | `/api/substitutions` | SA, A | `{groupId, lessonDate, substituteTeacherId, leaveRequestId?}` → 201. Ko'plikda: `POST /api/substitutions/bulk {items[]}` (ta'til oynasidan) |
| GET | `/api/substitutions` | SA, A, ACC | `?teacherId&substituteTeacherId&groupId&from&to&status` |
| GET | `/api/substitutions/my` | TEACHER | o'rinbosar sifatidagi darslarim (bugun va kelgusi) |
| POST | `/api/substitutions/{id}/cancel` | SA, A | faqat PLANNED; CONDUCTED ni bekor qilish — faqat davomat o'chirilganda (§2.3) |

**O'qituvchi kabineti (`/api/teacher/dashboard`):** `todayLessons` ga o'rinbosar darslari qo'shiladi (`substitute: true, originalTeacherName`). Asosiy o'qituvchining ta'tildagi darslari ko'rsatilmaydi.

### 2.3 Davomat ruxsati (D4)

Hozir `AttendanceService.markAttendance` → `teacherAccessService.assertOwnsGroup(group)`. Yangi qoida — `assertCanMarkAttendance(group, date)`:

| Kim | Ruxsat |
|---|---|
| SA, A | har doim (o'zgarmagan) |
| Guruh o'qituvchisi | o'z guruhi, **shu sanada faol o'rinbosar bo'lmasa** |
| O'rinbosar | faqat `lesson_substitutions(group, date, substitute = men, status ≠ CANCELLED)` bo'lgan kun |

- O'tgan sana uchun ochish (unlock) qoidasi o'rinbosarga ham amal qiladi. `attendance_unlock_requests` `teacher_id` = so'ragan o'qituvchi.
- O'rinbosar `GET /api/attendance/group/{id}?date=` ni faqat shu sana uchun o'qiy oladi (`assertCanReadAttendance`).
- **CONDUCTED:** o'rinbosar shu guruh va sana uchun davomatni birinchi marta saqlaganda `status = CONDUCTED`, `conducted_at/by` yoziladi (shu tranzaksiyada). Davomatni admin belgilasa ham CONDUCTED bo'ladi (`conducted_by = admin`), chunki dars o'tgan.
- Davomat keyin o'chirilsa yoki guruh darsi CANCELLED qilinsa (`lesson_exceptions`), substitution PLANNED ga qaytadi yoki CANCELLED bo'ladi. Payroll APPROVED/PAID bo'lsa — 409 `substitution.payrollLocked`.
- `attendance.marked_by` allaqachon bor — kim belgilagani ko'rinadi.
- **Billing:** `LESSON_CHARGE.teacher_id` o'zgarmaydi — guruh o'qituvchisi. D6: o'quvchi to'lovi va asosiy o'qituvchining "to'lagan o'quvchi" hisobi o'zgarmaydi (§9 Q-B).
- **Direktor dashboardi:** o'rinbosar belgilagan dars MISSING emas. Davomat intizomi (`AttendanceMetricsService`) shu kun uchun o'rinbosarga yoziladi (`teacherId = substitute`).

---

## §3. Payroll-v2 ga ta'sir

`calculationDetails` formati o'zgarmaydi ([payroll-v2.md §6](payroll-v2.md)): `lines[] {code, label, base, count, amount, status?}` + `items`. ~~`VERSION = 3`~~ — **2 qoldi** (§0.1 F5: yangi qatorlar va items qo'shimcha). v2 yozuvlari o'qishda o'zgarmaydi. APPROVED/PAID — muzlatilgan snapshot (qayta hisoblanmaydi).

### 3.1 `LEAVE_DEDUCTION` (D3) — barcha `SALARY_ROLES` (TEACHER, ADMIN, SALES_MANAGER)

```
workdays(month)   = oydagi Du–Sha kunlari soni, bayramlar (holidays) ish kuni EMAS — §0.1 F1
unpaidDays(month) = Σ APPROVED, paid = false ta'tillarning shu oyga tushgan Du–Sha, bayram bo'lmagan kunlari
dailyRate         = fixedSalary / workdays(month)                      (rule.fixedSalary, payroll-v2 §7)
LEAVE_DEDUCTION   = − Money.uzs(fixedSalary × unpaidDays / workdays)   (bitta yaxlitlash, Money da)
```

- Faqat **belgilangan qism** (`FIXED`) dan ayiriladi; o'zgaruvchan qism (`PER_PAYING_STUDENT`, `PER_NEW_STUDENT`, `KPI`) tegilmaydi (D3: "belgilangan oylikdan").
- `|LEAVE_DEDUCTION| ≤ FIXED` — butun oy ta'tilda bo'lsa ham FIXED dan oshmaydi.
- Oylar orasidagi ta'til (masalan 28.09–05.10) kunlari har oyga alohida tushadi.
- **ACCOUNTANT** `SALARY_ROLES` da yo'q — ta'tili yoziladi, ayirma hisoblanmaydi (§9 Q-G).
- **Determinizm:** manba — APPROVED ta'til yozuvi (o'zgarmas; bekor qilish payroll APPROVED/PAID da 409).

`calculationDetails`:

```json
{ "code": "LEAVE_DEDUCTION", "label": "Haqsiz ta'til", "base": 115385, "count": 3, "amount": -346154 }
```
`base` = `dailyRate` (ko'rsatish uchun yaxlitlangan); `count` = haqsiz ish kunlari; `amount` = yuqoridagi formula (bitta yaxlitlash, `base × count` emas).

```json
"items": { "leaves": [ { "leaveId": 12, "fromDate": "2026-09-14", "toDate": "2026-09-16",
                          "leaveType": "FAMILY", "paid": false, "workdaysInMonth": 3 } ] }
```
Haqli ta'tillar ham `items.leaves` da ko'rinadi (`paid: true`), lekin qator yaratilmaydi.

### 3.2 `SUBSTITUTE_LESSONS` (D5) — o'rinbosar TEACHER

```
lessons(month)     = lesson_substitutions: substitute = shu o'qituvchi, status = CONDUCTED, lesson_date ∈ [from, to]
SUBSTITUTE_LESSONS = + Money.uzs(rate × lessons)        rate — §9 Q-A
```

```json
{ "code": "SUBSTITUTE_LESSONS", "label": "O'rinbosar darslari", "base": 60000, "count": 4, "amount": 240000 }
```
```json
"items": { "substitutions": [ { "substitutionId": 31, "groupId": 4, "groupName": "IELTS 2", "lessonDate": "2026-09-15",
                                 "originalTeacherId": 2, "originalTeacherName": "...", "conductedAt": "2026-09-15T18:40:00" } ] }
```

- Sanaladigan dars — faqat **CONDUCTED** (davomat belgilangan). PLANNED pullik emas.
- Asosiy o'qituvchidan ayirish — §9 Q-B. Taklif: ayirilmaydi. Agar buyurtmachi ayirishni tanlasa, asosiy o'qituvchiga `SUBSTITUTED_LESSONS` (manfiy) qatori qo'shiladi, `items.substitutedLessons[]` bilan.
- `Σ lines.amount = net` invarianti saqlanadi; `gross` endi FIXED + o'zgaruvchan + `LEAVE_DEDUCTION` + `SUBSTITUTE_LESSONS`.
- **Payroll ustunlari:** `deductions = |LEAVE_DEDUCTION|` (hozir doim 0 — payroll-v2 §6), `allowances` ga `SUBSTITUTE_LESSONS` qo'shiladi.
- **Generate doirasi:** o'rinbosar shu oyda faol bo'lsa hisoblanadi. Oy o'rtasida nofaol bo'lgan o'rinbosar — T-04 muammosi (alohida).

### 3.3 Qoida (SalaryRule) o'zgarishi

Q-A taklifi qabul qilinsa: `salary_rules.substitute_lesson_rate NUMERIC(12,2) NULL`. Faqat TEACHER uchun, aks holda 400 `salaryRule.field.notApplicable`.
- Shaxsiy qoida → rol qoidasi; ikkalasida NULL bo'lsa → oylik `calculable=false`, sabab `SUBSTITUTE_RATE_MISSING`. Faqat o'rinbosar darsi bo'lgan oyda; jimgina 0 emas.
- API nomi: `substituteLessonRate`.
- `RuleSnapshot` ga qo'shiladi.

---

## §4. Imtihon to'lovi va kassa

### 4.1 Model

| Jadval / ustun | Izoh |
|---|---|
| `exams.fee` NUMERIC(12,2) NOT NULL DEFAULT 0 | D7. 0 — bepul. Ro'yxatga olingan (REGISTERED) o'quvchi bo'lsa `fee` o'zgarmaydi → 409 `exam.feeLocked` |
| `exam_registrations.status` → enum `REGISTERED, CANCELLED, ATTENDED, ABSENT` | — |
| `exam_registrations.amount_due` | yozilish paytidagi `exams.fee` (snapshot) |
| `exam_registrations.amount_paid` | kassaga tushgan summa |
| `exam_registrations.payment_status` → enum `FREE, PAID, REFUNDED` | PENDING yo'q — pullik imtihonga to'lovsiz yozilib bo'lmaydi (D9) |
| **`exam_registrations.cash_transaction_id`** FK cash_transactions | INCOME yozuvi |
| **`exam_registrations.refund_cash_transaction_id`** | REVERSAL yozuvi |
| **`exam_registrations.cancelled_at/by`, `cancel_reason`** | — |
| **`cash_transactions.exam_registration_id`** | kassa tarixidan yozilishga havola (`payment_id`, `payroll_id` kabi) |
| UNIQUE (exam_id, student_id) | V58 (bor bo'lsa — o'tkaziladi). CANCELLED dan keyin qayta yozilish: qisman `UNIQUE … WHERE status <> 'CANCELLED'` ga almashtiriladi (V62) |

Eski preview `POST /calculate-payment` va `ExamPaymentCalculatorService` **olib tashlanadi** (E-08). Summa endi `exams.fee`, billing davrlaridan hisoblanmaydi.

### 4.2 Yozilish (`POST /api/exams/{id}/registrations`)

```json
{ "studentId": 5, "cashRegisterId": 2, "paymentMethod": "CASH", "cashPart": null, "cardPart": null }
```

Tekshiruvlar, shu tartibda:
1. Imtihon faol, sanasi o'tmagan → aks holda 409 `exam.closed`.
2. TEACHER — imtihon va o'quvchi egaligi (E-02).
3. Kirish sharti — E-01: davomat, faol yozilma, **OVERDUE emas** (D8) → 400 `exam.notEligible` + `data.reason ∈ {ATTENDANCE, NO_ENROLLMENT, OVERDUE}`.
4. Dublikat → 409 `exam.alreadyRegistered`.
5. `fee > 0` bo'lsa `cashRegisterId` va `paymentMethod` majburiy → 400 `exam.paymentRequired`.

Yozuv bitta tranzaksiyada. Qulf tartibi billing-v2 §7.2 bo'yicha: Student → … → CashRegister.
- `fee > 0` → `CashRegisterService.recordIncome(cashRegisterId, fee, method, student, "Imtihon to'lovi: <nomi>", …)` + `cash_transactions.exam_registration_id`. Registratsiya `PAID`, `amount_paid = fee`.
- `fee = 0` → `FREE`, kassa yozuvi yo'q.
- `Idempotency-Key` sarlavhasi (ixtiyoriy, payroll `pay` dagidek) — ikki marta bosilsa ikki marta kirim bo'lmaydi.
- **Kim:** SA, A, ACC (kassa). TEACHER faqat bepul imtihonga yoza oladi; pullik imtihonda 403 `exam.paymentRole` — pulni o'qituvchi qabul qilmaydi (TAXMIN; §9 Q-H).
- **O'quvchi balansi (billing-v2 ledger) ishlatilmaydi:** imtihon to'lovi o'qish uchun to'lov emas va qarzni yopmasligi kerak. Kassa — yagona pul izi. Moliya hisobotida (`/api/finance/report`) alohida qator `examFees`.
- **Chek:** `ReceiptNumberService` dan raqam (`RCP-…`) — chek bitta ketma-ketlikda (TAXMIN; alohida prefiks kerakmi — UI bilan).

### 4.3 Bekor qilish (`POST /api/exams/{id}/registrations/{regId}/cancel`)

`{reason}` majburiy, 3–500 belgi.
- **Kim:** SA, A, ACC.
- **Shart:** `status = REGISTERED`; natija (`exam_results`) yo'q → aks holda 409 `exam.registration.hasResult`.
- PAID → `CashRegisterService.recordReversal(original, "Imtihon yozilishi bekor qilindi")` — pul kassadan qaytadi, asl yozuv o'chirilmaydi. Natija: `REFUNDED`, `refund_cash_transaction_id`.
- Kassa chelagi manfiyga tushsa — ogohlantirish (billing-v2 §13 #22 dagidek), rad etilmaydi.
- Imtihon o'chirilsa (soft) — REGISTERED yozilishlar avtomatik bekor qilinmaydi: 409 `exam.hasRegistrations`, avval yozilishlar bekor qilinadi (pul qaytishi aniq bo'lsin).
- **Audit:** `@Audited(PAYMENT / PAYMENT_CANCEL, entity = "ExamRegistration")`.

### 4.4 API (imtihon qismi, yangi va o'zgargan)

| Metod | Yo'l | Rollar |
|---|---|---|
| GET | `/api/exams/{id}/registrations` | SA, A, ACC, T (egasi) — `PageResponse<ExamRegistrationResponse>` (E-04) |
| POST | `/api/exams/{id}/registrations` | §4.2 |
| POST | `/api/exams/{id}/registrations/{regId}/cancel` | §4.3 |
| POST | `/api/exams/{id}/register-student?studentId=` | deprecated: faqat `fee = 0` da ishlaydi, aks holda 400 `exam.paymentRequired` |
| POST | `/api/exams/{id}/calculate-payment` | **olib tashlanadi** (410 `exam.feeInsteadOfPreview` bir bosqich) |
| POST/PUT | `/api/exams` | `ExamRequest` + `fee` (`@DecimalMin(0)`) |

**`ExamRegistrationResponse`:** `id, examId, studentId, studentName, status, paymentStatus, amountDue, amountPaid, cashTransactionId, refundCashTransactionId, receiptNumber, registrationDate, cancelledAt, cancelReason`.

---

## §5. Sozlamalar: markaz rekvizitlari (D12, S-03)

### 5.1 Model

Mavjud legacy `settings` jadvali qayta ishlatiladi (lokal bazada bor: `setting_key UNIQUE, setting_value TEXT, description, updated_by, updated_at`; prod'da bo'lmasa V63 yaratadi).

Kalitlar — `center.*` guruhi:

| Kalit | Qiymat (boshlang'ich) | Holat |
|---|---|---|
| `center.legalName` | `"ADIZONE LC" MChJ` | ma'lum |
| `center.shortName` | `Adizone` | ma'lum (hozir `ContractService.CENTER_NAME` va `TelegramService` da qattiq yozilgan) |
| `center.inn` (STIR) | `311626069` | ma'lum |
| `center.address` | `Novza MFY, Ye mavzesi, 10-uy` | ma'lum. Shahar/tuman — §9 Q-D |
| `center.phone` | `+998 90 335 13 45` | ma'lum |
| `center.bankName`, `center.bankAccount` (h/r), `center.bankMfo` | — | **kutilmoqda** (§9 Q-C) |
| `center.directorName` | — | **kutilmoqda** |
| `center.contractCity` | — | §9 Q-D |

Eski `school_name`, `currency`, `payment_cycle_days`, `max_debt_days` kalitlariga tegilmaydi. Kod ularni o'qimaydi (X-06); keyin tozalanadi.

### 5.2 API

| Metod | Yo'l | Rollar | Izoh |
|---|---|---|---|
| GET | `/api/settings/center` | STAFF | `{legalName, shortName, inn, address, phone, bankName, bankAccount, bankMfo, directorName, contractCity, missing: [...]}` |
| PUT | `/api/settings/center` | **SA** (D12) | qisman yangilash; `inn` — 9 raqam, `bankAccount` — 20 raqam, `bankMfo` — 5 raqam; `@Audited(UPDATE, "Settings")` + diff |

SecurityConfig: hozirgi `/api/settings/**` (POST/PUT → SA, A) dan oldin `PUT /api/settings/center` → SA. `SettingsService` keshlaydi (yozishda bekor qilinadi).

---

## §6. Shartnoma: narx, PDF, chop etish

### 6.1 Narx snapshot'i (D10, C-04, C-05)

`ContractCreateDto` ga **`studentGroupId`** qo'shiladi (majburiy; o'quvchida bitta faol yozilma bo'lsa — avtomatik). Generatsiya paytida `contracts` ga muzlatiladi:

| Ustun | Manba | Shablon belgisi |
|---|---|---|
| `student_group_id` FK | so'rov | — |
| `payment_type` | `sg.paymentType` | `{{paymentType}}` ("oylik" / "darsbay") |
| `list_price` | MONTHLY: `EnrollmentPricing.monthlyFee(sg)` (override > 0 → kurs narxi); PER_LESSON: `lessonPrice(sg)` | `{{coursePrice}}` |
| `discount_percent` | `EnrollmentPricing.discount(sg)` | `{{discountPercent}}` |
| `discount_amount` | `list_price − final_amount` | `{{discountAmount}}` |
| `final_amount` | MONTHLY: `effectiveMonthlyFee(sg)`; PER_LESSON: `effectiveLessonPrice(sg)` | `{{finalAmount}}`, `{{finalAmountWords}}` (so'z bilan — TAXMIN: kerakmi) |
| `start_date` | `sg.paymentStartDate` | `{{startDate}}` |

- Narx keyin o'zgarsa, shartnoma o'zgarmaydi (snapshot). Hisob-kitob baribir billing-v2 da (`EnrollmentPricing`) — shartnoma faqat hujjat.
- Eski `{{monthlyFee}}` = `{{finalAmount}}` (alias). Override = 0 bo'lgan holat endi kurs narxiga tushadi (C-04 xatosi tuzaladi).
- **Rekvizit belgilari (§5):** `{{center.legalName}}`, `{{center.inn}}`, `{{center.address}}`, `{{center.phone}}`, `{{center.bankName}}`, `{{center.bankAccount}}`, `{{center.bankMfo}}`, `{{center.directorName}}`, `{{center.contractCity}}`.
- `GET /api/contract-templates/placeholders` — barcha belgilar ro'yxati va tavsifi (C-08).
- Noma'lum `{{x}}` → shablon saqlanayotganda 400 `contract.template.unknownPlaceholder`.

### 6.2 Holat mashinasi (C-06) — qisqa

`DRAFT → SIGNED` (OFFLINE) yoki `DRAFT → ACCEPTED` (OFFER); `DRAFT/SIGNED/ACCEPTED → CANCELLED` (SA, A, sabab bilan).
- `signed_at`, `signed_by` qo'shiladi.
- SIGNED/ACCEPTED o'chirilmaydi → 409 `contract.signed`; DRAFT o'chiriladi (raqam qaytmaydi — §0 C-01 qarori).
- SIGNED paytida PDF muzlatiladi (§6.3).

### 6.3 PDF

**Kutubxona tanlovi:**

| Variant | Litsenziya | Plyus | Minus | Qaror |
|---|---|---|---|---|
| **OpenHTMLtoPDF** (`openhtmltopdf-pdfbox`, PDFBox ustida) | LGPL-2.1 | HTML/CSS (CSS 2.1 + paged media: `@page`, sahifa raqami) → PDF, TTF shrift joylash, sof Java, ~5 MB | XHTML talab qiladi; CSS3 (flex/grid) yo'q; asl loyiha arxivlangan — community fork (`io.github.openhtmltopdf`) davom etadi (TAXMIN: versiya va Spring Boot 3.2 / PDFBox mosligi implementatsiyada tekshiriladi) | **Tanlandi** |
| Flying Saucer + OpenPDF | LGPL / LGPL-MPL | Xuddi shu dvigatel, barqaror | Eskiroq, paged media zaifroq | Zaxira varianti |
| iText 7 / pdfHTML | **AGPL** yoki tijoriy | Kuchli | Yopiq kodli SaaS uchun litsenziya xavfi | Rad |
| Headless Chromium (Playwright / Gotenberg) | Apache-2.0 | To'liq CSS3, brauzerdagi ko'rinish bilan bir xil | +300 MB, alohida jarayon/servis, sovuq start | Rad (hozircha) |
| JasperReports | LGPL | Hisobotlar uchun kuchli | Alohida dizayner, og'ir | Rad |

**Shriftlar (kirill + lotin):** **Noto Sans** va **Noto Serif** (SIL OFL 1.1), Regular + Bold TTF. `src/main/resources/fonts/` ga, `builder.useFont(...)` bilan joylanadi (subset). Shart — quyidagi glyph'lar:
- o'zbek lotinidagi `ʻ` (U+02BB) va `ʼ` (U+02BC);
- o'zbek kirilli `ў қ ғ ҳ Ў Қ Ғ Ҳ`;
- rus kirilli;
- `№ « » — –`.

Noto ularning hammasini qamraydi. PT Serif/PT Sans (OFL) — zaxira. Brauzer/OS shrifti ishlatilmaydi, ya'ni server qayerda ishlashidan qat'i nazar natija bir xil. Test glyph'larni tekshiradi (§8).

**Oqim:**
1. **Shablon** (`contract_templates.content`) — matn yoki cheklangan HTML (`p, b, i, u, br, h1–h3, table, tr, td, ul, ol, li`). Saqlashda jsoup Safelist bilan tozalanadi (C-07, stored-XSS).
2. **Belgilar** — qiymatlar **HTML-escape** bilan qo'yiladi. Rekvizit yo'q bo'lsa `________` (qo'lda to'ldirish uchun chiziq), PDF bloklanmaydi. Javobda `missingRequisites: ["bankAccount", …]`.
3. **Qobiq:** `templates/contract-layout.html` — A4, chegaralar 20/15/20/25 mm, sarlavha (markaz nomi, raqam, sana), pastda ikki tomon rekvizitlari va imzo joylari, sahifa raqami (`@page { @bottom-center { content: counter(page) } }`). jsoup → W3C DOM → OpenHTMLtoPDF.
4. **Saqlash:**
   - DRAFT — har so'rovda qayta yaratiladi;
   - SIGNED/ACCEPTED — birinchi so'rovda yaratilib `uploads/contracts/{uuid}.pdf` ga yoziladi, `contracts.pdf_file`, `pdf_sha256` saqlanadi. Keyin shu fayl beriladi (rekvizit o'zgarsa ham imzolangan nusxa o'zgarmaydi).
5. **Fayl yo'li ochiq emas:** `GET /api/files/**` permitAll orqali emas (CH-04), faqat endpoint orqali.

### 6.4 API (shartnoma qismi)

| Metod | Yo'l | Rollar | Izoh |
|---|---|---|---|
| POST | `/api/contracts/generate` | SA, A | `{studentId, studentGroupId?, templateId?}` → `ContractDto` + snapshot maydonlari + `missingRequisites[]` |
| GET | `/api/contracts/{id}/pdf` | SA, A | `application/pdf`, `Content-Disposition: inline; filename="CTR-2026-00001.pdf"` |
| GET | `/api/contracts/{id}/pdf?download=true` | SA, A | `attachment` |
| POST | `/api/contracts/{id}/sign` | SA, A | DRAFT → SIGNED (OFFLINE), PDF muzlatiladi |
| POST | `/api/contracts/{id}/cancel` | SA, A | `{reason}` |
| GET | `/api/contract-templates/placeholders` | SA, A | `[{key, description, example}]` |

**Chop etish:** frontend PDF ni yangi oynada (blob URL) ochadi va brauzerning chop etish oynasini chaqiradi. Eski `window.print()` + HTML usuli (front `contracts.vue:307-376`) olib tashlanadi — chop etilgan nusxa server PDF bilan bir xil bo'ladi.

**`ContractDto` qo'shimchalari:** `studentGroupId, groupName, paymentType, listPrice, discountPercent, discountAmount, finalAmount, startDate, signedAt, signedByName, cancelledAt, cancelReason, hasPdf`.

---

## §7. Migratsiyalar (qo'lda, idempotent)

V58 (xavfsizlik), V59 (shartnoma raqami har yil noldan) va V60 (e'lon auditoriyasi) allaqachon bor. Har skript `prod-schema-check.sql` ro'yxatiga qo'shiladi.

| Skript | Mazmuni |
|---|---|
| **V61__leaves_substitutions.sql** | `leave_requests`: `user_id` + backfill (`teacher → teachers.user_id`, aks holda `requester_id`), `paid`, `decided_*` (`approved_*` dan ko'chiriladi), `decision_note`, `cancelled_*`. `status`/`leave_type` normalizatsiyasi: `UPPER`, noma'lum → NOTICE. CHECK'lar, indekslar, (ixtiyoriy) EXCLUDE. Mavjud APPROVED lar `paid = TRUE` — eski ta'tillar oylikka ta'sir qilmagan, retroaktiv ayirma bo'lmasin. `lesson_substitutions` + indekslar + qisman UNIQUE. `salary_rules.substitute_lesson_rate` (Q-A qarori bilan) |
| **V62__exam_fee.sql** | `exams.fee DEFAULT 0 NOT NULL`. `exam_registrations`: `cash_transaction_id`, `refund_cash_transaction_id`, `cancelled_*`, `cancel_reason`; `payment_status`: eski `PENDING` (summasi 0) → `FREE`, `PAID` qoladi. UNIQUE → qisman (`WHERE status <> 'CANCELLED'`). `cash_transactions.exam_registration_id` + indeks |
| **V63__settings_contract_snapshot.sql** | `settings` (IF NOT EXISTS) + `center.*` boshlang'ich qiymatlari (`INSERT … ON CONFLICT (setting_key) DO NOTHING` — qo'lda o'zgartirilgan qiymat ustidan yozilmaydi). `contracts`: `student_group_id`, `payment_type`, `list_price`, `discount_percent`, `discount_amount`, `final_amount`, `start_date`, `signed_at/by`, `cancelled_at/by`, `cancel_reason`, `pdf_file`, `pdf_sha256`. Eski shartnomalar snapshot'siz qoladi (NULL) — UI "—" ko'rsatadi; `rendered_content` da eski matn bor |

- **Tartib:** V58 → V59 → V61 → V62 → V63 → yangi jar.
- **pgtest:** `application-pgtest.yml` `schema-locations` ga qo'shiladi.
- **H2:** test sxemasida kerakli obyektlar (`billing-test-schema.sql`).

---

## §8. Testlar

Uslub — mavjud: `AbstractBillingIT`, ikkala baza (`mvn test` va `-Dspring.profiles.active=pgtest`). V61–V63 testi — faqat PostgreSQL, ikki marta bajarib idempotentlik tekshiriladi.

| Soha | Test |
|---|---|
| Ta'til | submit/approve/reject/cancel o'tishlari va xato kodlari; kesishuv (parallel ikki approve — biri 409); `paid` majburiyligi; ADMIN o'zini tasdiqlay olmaydi; TEACHER boshqaning arizasini ko'rmaydi; ON_LEAVE job (bugun ichida/tashqarisida, INACTIVE ga tegmaydi) |
| `LEAVE_DEDUCTION` | 3 haqsiz kun / sentyabr 2026 (26 Du–Sha kun) → `−Money.uzs(fixed × 3 / 26)`; yakshanba sanalmaydi; oylar orasidagi ta'til ikki oyga bo'linadi; haqli ta'til — qator yo'q, `items.leaves` da bor; butun oy → `−FIXED`; ACCOUNTANT — hisoblanmaydi; APPROVED payroll dan keyin ta'tilni bekor qilish → 409 |
| O'rinbosar | tayinlash: o'rinbosar = asosiy → 400; vaqt to'qnashuvi → 409; rejada dars yo'q → 400; bir darsga ikkinchi → 409 |
| Davomat ruxsati | o'rinbosar faqat shu kun belgilaydi (boshqa kun → 403); asosiy o'qituvchi o'rinbosar kunida → 403; admin → ok; birinchi belgilash → CONDUCTED; o'chirish → PLANNED; o'rinbosar uchun unlock oqimi |
| `SUBSTITUTE_LESSONS` | 4 CONDUCTED + 1 PLANNED → count 4; stavka yo'q → `calculable=false` `SUBSTITUTE_RATE_MISSING`; asosiy o'qituvchi "to'lagan o'quvchi" qismi o'zgarmaydi (D6); `Σ lines = net` |
| Billing o'zgarmasligi (D6) | o'rinbosar darsidagi davomat → `LESSON_CHARGE` summasi va `teacher_id` (asosiy) avvalgidek; davr summasi o'zgarmaydi |
| Imtihon to'lovi | fee > 0: kassa INCOME, `exam_registration_id`, `PAID`; fee = 0: kassa yo'q, `FREE`; OVERDUE → 400 `exam.notEligible{reason: OVERDUE}`; pullik imtihonda kassasiz → 400; TEACHER pullikka → 403; Idempotency-Key takrori — bitta kirim; bekor qilish → REVERSAL, chelak qaytadi, `REFUNDED`; natijasi bor → 409; qayta yozilish (CANCELLED dan keyin) — ok; `fee` ni REGISTERED bor imtihonda o'zgartirish → 409 |
| Sozlamalar | GET — STAFF; PUT — faqat SA (ADMIN → 403); validatsiya (INN 9 raqam); audit diff |
| Shartnoma | snapshot: chegirma 10% → `discount_amount`, `final_amount = effectiveMonthlyFee`; override = 0 → kurs narxi; PER_LESSON; ko'p guruhli o'quvchi `studentGroupId` siz → 400; belgilar HTML-escape (`<script>` matn bo'lib chiqadi); noma'lum belgi → 400; rekvizit yo'q → `________` + `missingRequisites` |
| PDF | `%PDF-` boshlanadi, sahifa soni ≥ 1; PDFBox `PDFTextStripper` bilan matnda `ʻ`, `ʼ`, `ў`, `қ`, `Ж`, `№` bor (shrift joylangan, "tofu" yo'q); SIGNED dan keyin sozlama o'zgarsa PDF hash o'zgarmaydi; `/api/files/**` orqali PDF fayliga kirib bo'lmaydi |

---

## §9. Ochiq savollar (buyurtmachidan kutilmoqda) — taklif bilan

| # | Savol | Taklif | Ta'sir |
|---|---|---|---|
| **Q-A** (yopildi: qat'iy summa, F3) | O'rinbosar haqi formulasi: qat'iy summa yoki stavka ulushi? | **Qat'iy summa bir darsga** — `salary_rules.substitute_lesson_rate`; rol qoidasida umumiy, shaxsiy qoidada alohida. Sabab: o'qituvchi oyligi darsbay emas ("to'lagan o'quvchi" × stavka, payroll-v2 §2.1), ya'ni "asosiy o'qituvchining bir dars narxi" tabiiy mavjud emas. Uni `perPayingStudent × o'quvchilar / oydagi darslar` bilan hisoblash har oy o'zgaradi va tushuntirish qiyin. Muqobil (agar ulush kerak bo'lsa): `rate = round(guruhning shu oydagi o'qituvchi daromadi / oydagi rejadagi darslar)` — determinizm uchun `billing_periods.teacher_id` dan hisoblanadi | §3.2, §3.3 |
| **Q-B** (yopildi: ayirilmaydi, F3) | O'rinbosar haqi asosiy o'qituvchidan ayiriladimi? | **Yo'q** (markaz xarajati). Asosiy o'qituvchi ta'tili haqsiz bo'lsa — `LEAVE_DEDUCTION` FIXED qismidan ayiradi. "To'lagan o'quvchi" qismi ayirilmaydi, chunki o'quvchilar shu oy uchun to'lagan va guruh uniki. Buyurtmachi ayirishni tanlasa: asosiy o'qituvchiga `SUBSTITUTED_LESSONS = −(o'rinbosarga to'langan summa)`, lekin FIXED + o'zgaruvchan qismdan oshmasin | §3.2 |
| **Q-C** (yopildi, F8) | Bank rekvizitlari (bank nomi, h/r, MFO) va direktor F.I.Sh. | Kelguncha PDF da `________` chiziq, javobda `missingRequisites`. SA keyin Sozlamalardan kiritadi, kod o'zgarmaydi | §5, §6.3 |
| **Q-D** (yopildi, F8) | Shartnoma manzili: shartnomada qaysi manzil va qaysi shahar ko'rsatiladi (yuridik manzil yoki o'quv binosi; "Toshkent sh." / tuman)? | `center.address` = yuridik manzil (Novza MFY, Ye mavzesi, 10-uy). Shartnoma tuzilgan joy — alohida `center.contractCity` (taklif: **"Toshkent shahri"**). Tuman — buyurtmachi tasdiqlasin (Novza MFY qaysi tumanda ekanini ular aniq aytadi) | §5.1 |
| Q-E (yopildi: bayram ish kuni EMAS, F1) | Haqsiz ta'til ulushida bayramlar (`holidays`) ish kuni hisoblanadimi? | Buyurtmachi "Du–Sha" dedi — taklif: **bayramlar ayirilmaydi** (oddiy, oldindan ma'lum maxraj). Kerak bo'lsa keyin `workdays − holidays` | §3.1 |
| Q-F | Yillik ta'til kvotasi (haqli kunlar) yuritiladimi? | Hozircha yo'q — faqat hisobot (`/api/leaves/summary`). `teachers.*_leaves` legacy ustunlari ishlatilmaydi (Q19) | §1.2 |
| Q-G | ACCOUNTANT oyligi tizimda hisoblanmaydi (`SALARY_ROLES` da yo'q) — haqsiz ta'tili qanday? | Ta'til yoziladi, ayirma — qo'lda (bonus/jarima PENALTY). Buxgalter oyligi tizimga qo'shilsa — avtomatik | §3.1 |
| Q-H (yopildi: yo'q, F6) | Pullik imtihon to'lovini o'qituvchi qabul qila oladimi? | **Yo'q** — pul faqat SA/A/ACC kassasiga. O'qituvchi bepul imtihonga yozadi | §4.2 |

---

## §10. Ish tartibi va baho (TAXMIN)

| Bosqich | Mazmun | Backend |
|---|---|---|
| 1 | Sozlamalar (§5) + shartnoma snapshot va holatlari (§6.1–6.2) + V63 | 2–3 kun |
| 2 | PDF (§6.3): kutubxona, shriftlar, layout, muzlatish, testlar | 2–3 kun |
| 3 | Imtihon to'lovi (§4) + V62 | 2–3 kun |
| 4 | Ta'tillar (§1) + V61 (ta'til qismi) + ON_LEAVE job | 2–3 kun |
| 5 | O'rinbosar (§2) + davomat ruxsati + dashboard | 3–4 kun |
| 6 | Payroll: `LEAVE_DEDUCTION`, `SUBSTITUTE_LESSONS`, `VERSION 3` (§3) — **Q-A va Q-B qaroridan keyin** | 2–3 kun |

1–3-bosqichlar ochiq savollarga bog'liq emas (Q-C, Q-D faqat ma'lumot). 6-bosqich Q-A va Q-B ni kutadi; 4–5 ularsiz boshlanishi mumkin.

---

## §11. Amalga oshirish (2026-10-02) — bosqichlar bo'yicha fayllar

Yo'llar `src/main/java/com/crm/` ga nisbatan (resurslar — `src/main/resources/`). Testlar — `src/test/java/com/crm/`.

| Bosqich | Asosiy fayllar | Testlar |
|---|---|---|
| 1. Sozlamalar + shartnoma snapshot/holatlar + V63 | `entity/Setting`, `repository/SettingRepository`, `service/CenterSettingsService`, `controller/SettingsController`, `dto/{request/CenterSettingsRequest, response/CenterSettingsDto}`; `entity/Contract` (+snapshot, holat izi, PDF), `entity/enums/ContractStatus` (+CANCELLED), `service/ContractService`, `service/ContractPlaceholders`, `util/ContractHtml` (tozalash), `controller/ContractController`, `dto/{request/ContractCreateDto, ContractCancelRequest; response/ContractDto}`; `config/SecurityConfig`; `db/migration/V63__settings_contract_snapshot.sql` | `lec/SettingsAndContractTest` (8) |
| 2. PDF | `pom.xml` (`io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.87`, PDFBox 3.0.7, LGPL-2.1+, Java 8 bytecode), `fonts/Noto{Sans,Serif}-*.ttf` + `fonts/OFL.txt` (SIL OFL 1.1), `templates/contract-layout.html`, `service/ContractPdfService`, `service/FileStorageService` (pastki katalog `/api/files` da yopiq); test `application.yml`: `app.contracts.pdf-dir` | `lec/ContractPdfTest` (3) |
| 3. Imtihon to'lovi + V62 | `entity/{Exam (+fee), ExamRegistration, CashTransaction (+examRegistrationId)}`, `entity/enums/{ExamRegistrationStatus, ExamPaymentStatus}` + konverterlar, `repository/{ExamRegistrationRepository, CashTransactionRepository}`, `service/{ExamService, CashRegisterService, FinanceService}`, `controller/ExamController`, `dto/{request/ExamRegistrationRequest, ExamRegistrationCancelRequest, ExamRequest; response/ExamRegistrationResponse, ExamResponse, CashTransactionDto, FinanceReportResponse}`; `ExamPaymentCalculatorService` o'chirildi; `db/migration/V62__exam_fee.sql`, `V58` (qisman UNIQUE ni ham "bor" deb biladi) | `lec/ExamFeeTest` (6) |
| 4. Ta'tillar + V61 (ta'til qismi) + ON_LEAVE job | `entity/Leave`, `entity/enums/{LeaveType, LeaveStatus}` + konverterlar, `repository/LeaveRepository`, `service/{LeaveService, LeaveStatusJob, WorkdayCalendar, PayrollPeriodGuard}`, `controller/LeaveController`, `dto/{request/LeaveSubmitRequest, LeaveDecisionRequest; response/LeaveResponse, LeaveSummaryDto, AffectedLessonDto}`; `db/migration/V61__leaves_substitutions.sql` | `lec/LeaveTest` (7) |
| 5. "Darsni X o'tdi" + davomat ruxsati + dashboard | `entity/LessonSubstitution`, `entity/enums/SubstitutionStatus`, `repository/LessonSubstitutionRepository`, `service/{LessonSubstitutionService, AttendanceAccessService, AttendanceService, AttendanceUnlockRequestService, GroupScheduleService (slotOn), TeacherService (kabinet)}`, `dashboard/{AttendanceMetricsService, LessonCalendarService}`, `controller/SubstitutionController`, `dto/{request/SubstitutionRequest, SubstitutionBulkRequest; response/SubstitutionResponse}`; V61 (jadval qismi) | `lec/SubstitutionTest` (6) |
| 6. Payroll | `entity/SalaryRule` (+substituteLessonRate), `dto/{request/SalaryRuleRequest, response/SalaryRuleResponse, response/SalaryCalculationDto, response/PayrollCalculationDetails}`, `service/{SalaryCalculationService, SalaryRuleService, PayrollService}`; V61 (`salary_rules.substitute_lesson_rate`) | `payroll/PayrollLeaveSubstituteTest` (8) |
| Migratsiyalar | V61–V63 idempotent; `docs/ops/prod-schema-check.sql` ga qo'shildi; pgtest `schema-locations` ga ulandi; H2 da Hibernate yaratadi | `lec/MigrationV61ToV63Test` (faqat PostgreSQL) |

Xabarlar — `messages{,_en,_ru}.properties` (uz/en/ru). Umumiy test yordamchisi — `lec/LecItBase`; `BillingFixtures.wipe` ga
`lesson_substitutions`, `leave_requests`, `attendance_unlock_requests` qo'shildi.

**Prod tartibi:** V61 → V62 → V63 (qo'lda, `psql -v ON_ERROR_STOP=1`), so'ng yangi jar; tekshiruv — `prod-schema-check.sql`.
V61 `btree_gist` ni o'rnatishga urinadi (huquq bo'lmasa — NOTICE, kesishuv faqat ilovada tekshiriladi).

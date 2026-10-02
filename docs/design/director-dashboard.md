# Direktor dashboardi — dizayn hujjati

> **Holat:** §7 qarorlari olindi (02.10.2026); amalga oshirish — branch `billing-v2`. Frontend shartnomasi: `docs/design/director-dashboard-api.md`.
>
> **Bog'liqlik:** `docs/design/billing-v2.md` — qarz, holat, `billing_periods`, FIFO.
> Bu hujjat billing-v2 ta'riflarini **qayta aniqlamaydi**, faqat ishlatadi.
>
> **Buyurtmachi talabi.** Direktor har kuni kechqurun shunday satrlarni ko'rishi kerak:
> - "bugun 34 lid → 17 tashrif → 9 to'lov";
> - "28 ta muddati kelgan to'lov → 21 tasi undirildi";
> - "7 qarzdor";
> - "42 darsdan 40 tasida davomat qilindi".
>
> Qo'shimcha savollar:
> - to'lovlar o'z vaqtida bo'lganmi;
> - sinovga kelganlarning qanchasi 1-kunda, qanchasi keyin to'lagan, qanchasi to'lamay qolgan;
> - administratorlar ishlayaptimi.

---

## 0. Umumiy qoidalar

| Mavzu | Qoida |
|---|---|
| Vaqt zonasi | Hamma sana chegaralari **Asia/Tashkent** bo'yicha. `TIMESTAMP` ustunlar bazada Toshkent mahalliy vaqtida saqlanadi (`application.yml: hibernate.jdbc.time_zone`, `docs/ops/timezone.md`). "D kuni" `[D 00:00, D+1 00:00)` oralig'i. `DATE` ustunlar to'g'ridan-to'g'ri solishtiriladi. "Bugun" `billingClock` dan olinadi, `LocalDate.now()` emas. |
| Davr | `period=DAY \| WEEK \| MONTH \| CUSTOM`. Hafta ISO bo'yicha (dushanba–yakshanba), oy kalendar oyi. CUSTOM `from..to`, ko'pi bilan 366 kun. Davr `date` ni o'z ichiga oladi; default `period=DAY`, `date=bugun`. |
| Qiymat holati | Har ko'rsatkichda `asOf` bor — qaysi paytgacha bo'lgan ma'lumot. O'tgan kun raqamlari keyinroq kiritilgan yozuvlar sabab o'zgarishi mumkin (§6 — qayta hisoblash oynasi). |
| Pul | UZS, butun so'm; billing-v2 `Money` qoidasi. |
| Bekor qilingan | `payments.status = CANCELLED` hech qaysi ko'rsatkichda sanalmaydi. Bekor qilingan to'lov yopgan davr qayta "yopilmagan" bo'ladi (§1.3). |
| Test / o'chirilgan | `students.status` dan qat'i nazar o'quvchi sanaladi. Lidlarda import qilinganlar (`leads.import_batch IS NOT NULL`) default chiqariladi (`includeImported=false`). |

Ko'rsatkich identifikatorlari (API va Telegramda ham shu nomlar ishlatiladi):

| Kod | Bo'lim | Kim ko'radi |
|---|---|---|
| `funnel` | Lid voronkasi | SA, A |
| `collections` | Muddati kelgan to'lovlar | SA, ACC |
| `debtors` | Qarzdorlar | SA, ACC |
| `attendance` | Davomat intizomi | SA, A |
| `trials` | Sinov → to'lov | SA, A |
| `retention` | Qolish | SA |
| `operators` | Operator faoliyati | SA, A |

---

### 0.1 Amalga oshirishdagi aniqliklar (02.10.2026)
Kod shu qoidalar bo'yicha yozilgan. Hujjatning qolgan qismi bilan farq qilsa, shu bo'lim ustun turadi.

| Mavzu | Qoida |
|---|---|
| `asOf` | Hisob payti = **hozir**. Kogorta turidagi ko'rsatkichlar shu paytgacha o'lchanadi: voronka kogortasi, undirilgan/kechikkan/to'lanmagan davrlar, sinov natijasi, qolish, operator konversiyasi. O'tgan kunning "o'sha kechki" ko'rinishi — `director_daily_stats` snapshot'i: DAY + o'tgan kun + yakuniy snapshot bo'lsa, xulosa undan o'qiladi. Faollik sanoqlari davr chegarasi bilan sanaladi. |
| Tashrif (§7 #1) | Konvert bosqichi `contacted_at` va `converted_at` ni to'ldiradi, lekin `visited_at` ni **to'ldirmaydi**. Tashrif ikki manbadan keladi: `VISITED_TRIAL` / *_PAID bosqichi, yoki konvert qilingan o'quvchining birinchi PRESENT/LATE davomati. Sabab: bu CRM da lid sinovga yozilganda konvert qilinadi, aks holda davomat qoidasi hech qachon ishlamasdi. Shu sababli kogortada CONVERTED soni VISITED dan katta bo'lishi mumkin. |
| Snapshot sxemasi | `director_daily_stats.payload` — TEXT (JSON matn), JSONB emas. Bayroq ustuni `is_final` (`final` emas). |
| Chiqish sababi | `freeze` so'rovida `reasonCode` yo'q — kod avtomatik: FROZEN yoki AUTO_ARCHIVE. `remove-student` da `reasonCode` ixtiyoriy; berilmasa eski `reason` matnidan olinadi (`GRADUATED` → GRADUATED, noma'lum → OTHER, §7 #12). |
| `funnel_step` o'zgarishi | Bosqich qadami keyin o'zgartirilsa, mavjud lidlarning yozilgan sanalari qayta hisoblanmaydi (write-once). Backfill faqat bo'sh maydonlarni to'ldiradi. `/recompute-funnel` endpointi yo'q. |
| Retention | Xulosaga (`GET /director`) kirmaydi — faqat `/retention` va `/retention/exits` (SA). |
| Taxminiy belgisi | `collections.estimated` — migratsiya davrlari (`coverage_source = MIGRATION_REPLAY`). `trials.estimated` — `trial_source = BACKFILL`. `operators.estimated` — `lead_assignments.source = BACKFILL`. `debtors.source = NONE` — o'sha kun uchun ma'lumot yo'q. |
| Frontend shartnomasi | `docs/design/director-dashboard-api.md` |

## 1. Ko'rsatkichlar ta'rifi

### 1.1 Lid voronkasi — `funnel`

**Voronka qadamlari** (monoton, yuqoriga faqat oldinga):

| Rank | Qadam | Lid bu qadamga yetdi deb hisoblanadi, agar… |
|---|---|---|
| 0 | `CREATED` | `leads.created_at` |
| 1 | `CONTACTED` | `funnel_step ≥ CONTACTED` bo'lgan bosqichga **birinchi marta** o'tdi |
| 2 | `VISITED` | `funnel_step ≥ VISITED` bo'lgan bosqichga birinchi marta o'tdi, **yoki** lid konvert qilinib o'quvchisining birinchi PRESENT/LATE davomati bor (§7 #1) |
| 3 | `CONVERTED` | `kind = CONVERTED` bo'lgan bosqichga birinchi marta o'tdi (`LeadService.convertToStudent`, `LeadService.java:616`) |
| 4 | `FIRST_PAYMENT` | Lid konvert qilingan o'quvchining (`students.converted_from_lead_id = lead.id`) birinchi PAID to'lovi (§1.1.3) |

**Qoidalar:**
- Yuqori qadam pastkilarini ham qamraydi. Masalan NEW → CONVERTED_OFFLINE ga to'g'ridan o'tgan lid CONTACTED va VISITED ga ham yetgan hisoblanadi. Qadam sanasi esa shu yuqori qadam sanasi bo'ladi.
- `REJECTED` qadam emas. Rad etilgan lid erishgan eng yuqori qadamida qoladi va `rejected` deb alohida sanaladi.
- Qadamga yetish sanasi — o'sha qadamga **birinchi** kirish payti. Orqaga qaytish va qayta kirish uni o'zgartirmaydi.

#### 1.1.1 Kunlik faollik rejimi (`mode=ACTIVITY`) — "bugun nima bo'ldi"
Har raqam **mustaqil hodisalar soni**; ular bir xil odamlar emas. UI buni aniq yozadi: "bugun: 34 yangi lid · 17 tashrif · 9 birinchi to'lov".

| Maydon | Formula (davr P) |
|---|---|
| `leadsCreated` | `count(leads) WHERE created_at ∈ P` (importlar chiqarilgan) |
| `contacted` | P ichida **birinchi marta** rank ≥ 1 ga yetgan lidlar |
| `visited` | P ichida birinchi marta rank ≥ 2 ga yetgan lidlar |
| `converted` | P ichida birinchi marta rank ≥ 3 ga yetgan lidlar |
| `firstPayments` | Birinchi PAID to'lovi (`payment_date`) P ga tushgan o'quvchilar. Ikki bo'lak: `fromLeads` (`converted_from_lead_id IS NOT NULL`) va `walkIn` (lidsiz) |
| `rejected` | P ichida REJECTED bosqichiga o'tgan lidlar |

#### 1.1.2 Kogorta rejimi (`mode=COHORT`) — "P da kelgan lidlar qayergacha yetdi"
- Kogorta: `created_at ∈ P` bo'lgan lidlar (importlarsiz).
- Har qadam uchun `reached = kogortadan asOf gacha shu qadamga yetganlar`, `rate = reached / cohortSize`.
- Qo'shimcha: `medianDaysToStep` — qadamgacha kunlar medianasi.
- Qadam bo'yicha pastga qarab konversiya: `CONTACTED / CREATED`, `VISITED / CONTACTED`, `CONVERTED / VISITED`, `FIRST_PAYMENT / CONVERTED`.
- Default: `period=DAY` → ACTIVITY; `WEEK`/`MONTH` → ikkala rejim (§7 #2).

#### 1.1.3 "Birinchi to'lov" ta'rifi
```
firstPayment(student) = min(payment_date, id) bo'yicha birinchi to'lov,
                        WHERE student_id = s AND status = 'PAID'
```
- O'quvchi darajasida (guruhdan qat'i nazar).
- Birinchi to'lov keyin bekor qilinsa, keyingi PAID to'lov "birinchi" bo'ladi. Raqam qayta hisoblanadi (§6).
- Faqat `cash_amount > 0` bo'lgan to'lovlar (to'liq chegirmali "to'lov" sanalmaydi).

### 1.2 Muddati kelgan to'lovlar — `collections`
Manba — `billing_periods` (faqat MONTHLY; PER_LESSON da muddat yo'q, §7 #6).

**Hisobga olinadigan davr:**
- `status IN ('CHARGED','PARTIALLY_REFUNDED')`;
- `amount − refunded_amount > 0`;
- SG `billing_hold IS NOT TRUE`.

Hisobga olinmaydi:
- `MIGRATED` va `REFUNDED` davrlar;
- `d = 100` (`amount = 0`) davrlar.

**Asosiy tushunchalar:**

| Tushuncha | Ta'rif |
|---|---|
| `due_date` | `period_start` — billing kuni |
| `grace_until` | `due_date + app.billing.grace-days` (3) |
| `paid_on` | Davr FIFO bo'yicha **to'liq yopilgan** kun (yangi ustun, §3.2) |

**Bir davrning toifasi** (`asOf` kuni bo'yicha):

| Toifa | Shart |
|---|---|
| `ON_TIME` | `paid_on ≤ grace_until` (oldindan to'langan ham, `paid_on < due_date`) |
| `LATE` | `paid_on > grace_until` |
| `PENDING` | `paid_on IS NULL` va `asOf ≤ grace_until` — hali muddat ichida |
| `UNPAID` | `paid_on IS NULL` va `asOf > grace_until` — billing-v2 dagi OVERDUE ga mos |

**Davr P uchun:**

| Maydon | Formula |
|---|---|
| `due.count`, `due.amount` | `due_date ∈ P` bo'lgan davrlar soni / Σ(`amount − refunded_amount`) |
| `collected.count`, `.amount` | Shulardan `paid_on ≤ asOf` |
| `onTime`, `late`, `pending`, `unpaid` | Yuqoridagi toifalar bo'yicha soni va summasi (`due` ichida) |
| `collectionRate` | `collected.count / due.count` |
| `avgDelayDays` | `avg(max(0, paid_on − due_date))` — `due` ichidagi yopilgan davrlar bo'yicha |
| `avgLateDelayDays` | Faqat `LATE` lar bo'yicha `avg(paid_on − due_date)` |
| `collectedOnDay` | **Boshqa savol:** P ichida yopilgan davrlar (`paid_on ∈ P`), `due_date` dan qat'i nazar. "Bugun eski qarzlar qancha undirildi" |
| `cashIn` | P dagi PAID to'lovlarning Σ `cash_amount` — moliya bilan solishtirish uchun |

"28 ta muddati kelgan → 21 undirildi" satri = `due.count` → `collected.count`. Kunlik ko'rinishda `collectedOnDay` ham alohida beriladi, chunki direktor uchun "bugun kassaga eski qarzdan qancha tushdi" ham muhim.

### 1.3 Qarzdorlar — `debtors`
billing-v2 §4.5 dagi **yagona ta'rif** (`DebtorService`). Bu yerda yangi formula yo'q.

| Maydon | Ta'rif |
|---|---|
| `count` | Kamida bitta OVERDUE yozilmasi bor o'quvchilar (`scope=ACTIVE` — faol va muzlatilgan SG lar) |
| `amount` | Shu o'quvchilarning OVERDUE SG lari bo'yicha Σ `debt` |
| `overdue7Plus` | `daysOverdue ≥ 7` bo'lganlar |
| `newToday` | D kuni OVERDUE ga o'tganlar: `debt_since + grace + 1 = D` |
| `clearedToday` | D−1 da qarzdor bo'lgan, D da qarzdor emas (snapshotdan, §3.6) |
| `closedDebt` | `scope=ALL` dagi yopilgan SG lar qarzi (ma'lumot uchun) |

**Tarixiy kun:** `count`/`amount` faqat `director_daily_stats` dan olinadi (§3.6). Ledgerdan "D kunidagi holat"ni qayta hisoblash mumkin, lekin qimmat. Snapshot bo'lmagan kunlar uchun `null` va `source=NONE` qaytadi.

### 1.4 Davomat intizomi — `attendance`

| Tushuncha | Ta'rif |
|---|---|
| Rejadagi dars | D kuni uchun `(group, D)` jufti, agar quyidagi **hammasi** bajarilsa: |
| | `groups.status = 'ACTIVE'` (§7 #7) |
| | `start_date ≤ D ≤ coalesce(end_date, ∞)` |
| | `D.dayOfWeek ∈ GroupScheduleService.lessonWeekdays(g)` (`group_schedule_days` + `timetable`, `GroupScheduleService.java:80`) |
| | D kuni guruhda kamida bitta faol yozilma bor: `join_date ≤ D` va (`leave_date IS NULL` yoki `D ≤ leave_date`), muzlatilmagan (`frozen_from IS NULL` yoki `D < frozen_from`) |
| | D dam olish kuni emas va guruh uchun bekor qilinmagan (**yangi** jadvallar, §3.5) |
| Bir kunda bir nechta slot | Bitta dars sanaladi (`(group, date)` bo'yicha distinct). Hozirgi `missing-attendance` mantiqi ham shunday (`AttendanceService.java:220-270`) |
| Davomat olingan | `EXISTS attendance WHERE group_id = g AND attendance_date = D` |
| O'z vaqtida olingan | `min(attendance.created_at) < D+1 00:00` (o'sha kuni, §7 #8) |

| Maydon | Formula (davr P) |
|---|---|
| `planned` | Σ rejadagi darslar |
| `taken` | Ulardan davomat olinganlari |
| `takenOnTime` | Ulardan o'z vaqtida olinganlari |
| `missing` | `planned − taken` (o'qituvchi bo'yicha drill-down) |
| `unplanned` | Davomat bor, lekin rejada yo'q `(group, D)` — jadval eskirganini bildiradi |
| `rate` | `taken / planned` |

"42 darsdan 40 tasida davomat qilindi" = `planned` → `taken`.

Bugungi kun uchun hali boshlanmagan darslar `planned` ga kiradi, lekin `missing` faqat soati o'tganlar uchun sanaladi. Shartlar:
- `D < bugun`, **yoki**
- `D = bugun` va slot `end_time` o'tgan.

Hozirgi `missing-attendance` bugungi kunni ham to'liq "o'tkazib yuborilgan" deb hisoblaydi — yangi hisob bu farqni hisobga oladi.

### 1.5 Sinov → to'lov — `trials`
**Kogorta:** sinovga **haqiqatan kelganlar** — `trial_started_at ∈ P` bo'lgan yozilmalar.

`trial_started_at` = SG sinovda (`is_trial = true`) bo'lgan paytdagi birinchi PRESENT/LATE davomat sanasi (yangi ustun, §3.3). Kelmagan sinov yozilmalari alohida sanaladi: `noShow` — sinovga yozilgan, `trial_started_at IS NULL`.

**Natija:** `k = convertedDay − trial_started_at` (kunlar), bu yerda `convertedDay` = SG ning sinovdan keyingi birinchi PAID to'lovi sanasi.

| Bucket | Shart |
|---|---|
| `D0` | `k = 0` — sinov kuni to'lagan |
| `D1` | `k = 1` |
| `D2_7` | `2 ≤ k ≤ 7` |
| `D8_PLUS` | `k ≥ 8` |
| `IN_TRIAL` | Hali to'lamagan, SG hali sinovda va `asOf − trial_started_at ≤ 14` (§7 #10) |
| `NOT_PAID` | To'lamagan va (SG yopilgan **yoki** 14 kundan oshgan) |

| Maydon | Formula |
|---|---|
| `cohort` | Kogorta hajmi |
| `buckets{}` | Har bucket soni va % |
| `conversionRate` | `(D0 + D1 + D2_7 + D8_PLUS) / (cohort − IN_TRIAL)` — hali qaror qilmaganlar maxrajga kirmaydi |
| `stayed30` | To'laganlardan `convertedDay + 30` da hali ochiq (faol yoki muzlatilgan) yozilmasi borlar % |

"1-kunda" iborasi bu hujjatda `D0` (sinov kunining o'zi) deb talqin qilindi. `D1` esa ertasi kun (§7 #9).

### 1.6 Qolish — `retention`
- **Kogorta:** o'quvchining **birinchi pullik oyi** — `firstPayment(student).payment_date` oyi.
- **Faol deb hisoblanadi** (m oyi oxirida): kamida bitta yozilmasi `is_active = true` va muzlatilmagan, **yoki** o'sha oyda yopilgan davri bor.
- **Matritsa:** `retention[cohortMonth][k] = kogortadan (cohortMonth + k) oyi oxirida faol bo'lganlar / kogorta hajmi`, `k = 0..12`.
- **Chiqib ketish (churn):** o'quvchining oxirgi ochiq yozilmasi yopilgan sana, agar 30 kun ichida yangi yozilma ochilmasa.

  | Holat | Churn hisoblanadimi |
  |---|---|
  | Transfer (`exit_reason = TRANSFERRED`) | Yo'q |
  | Muzlatish | Yo'q, "pauza" deb alohida ko'rsatiladi |
  | Bitirish (`GRADUATED`) | Churn emas, `graduated` deb alohida |

- **Sabablar:** `exit_reason_code` (yangi, §3.4) bo'yicha guruhlanadi. Davr P da chiqib ketganlar soni va ulushi.

### 1.7 Operator faoliyati — `operators`
**Operatorlar:** roli `ADMIN` yoki `SALES_MANAGER`, `is_active = true`. SA lid tayinlangan bo'lsa qo'shiladi (§7 #14).

| Maydon | Ta'rif (davr P, operator u) |
|---|---|
| `assigned` | P ichida u ga tayinlangan lidlar — `lead_assignments.assigned_at ∈ P` (yangi jadval, §3.1). Qayta tayinlash ham sanaladi |
| `firstResponse.median`, `.p90` | Har tayinlangan lid uchun `t0 = max(leads.created_at, assigned_at)` va `t1` = t0 dan keyin u ning birinchi harakati. Harakatlar: (a) bosqich o'zgarishi (`lead_status_history.changed_by = u`), (b) shu lid bo'yicha `DONE` vazifa (`tasks.completed_by = u`), (c) izoh (`lead_comments.author = u`). `responseMinutes = ish vaqti daqiqalari(t0, t1)` (§7 #13) |
| `firstResponse.within15m`, `.within1h` | Ulush (%) |
| `noResponse` | `t1` yo'q va t0 dan 24 ish soati o'tgan |
| `tasks.done` | `tasks.completed_at ∈ P AND completed_by = u` |
| `tasks.doneLate` | Ulardan `completed_at > due_at` |
| `tasks.overdueOpen` | asOf paytida `assigned_to = u AND status = 'OPEN' AND due_at < asOf` |
| `converted` | `assigned` kogortasidan asOf gacha rank ≥ 3 ga yetganlar va `rate` |
| `firstPayments` | `students.attributed_user_id = u` va birinchi to'lovi P da |
| `paymentsReceived` | `payments.received_by = u`, PAID, `payment_date ∈ P` — soni va Σ `cash_amount` |
| `activity.statusChanges`, `.comments`, `.lastSeenAt` | Tarix va izohlar soni; oxirgi `LOGIN` (`audit_logs`) |

Mavjud `StaffAnalyticsService.getStaffAnalytics` (`StaffAnalyticsService.java:67`) `assigned/converted/payments` ni hozirgi `assigned_user_id` bo'yicha hisoblaydi. U qayta tayinlashni ko'rmaydi. Yangi bo'lim `lead_assignments` ga o'tadi, eski endpoint o'zgarmaydi.

---

## 2. Ma'lumot manbalari va yetishmayotgan ma'lumotlar

### 2.1 Mavjud manbalar

| Ko'rsatkich | Jadval.ustun | Izoh |
|---|---|---|
| Lid yaratilgan | `leads.created_at`, `leads.import_batch`, `leads.source` | Indeks yo'q `created_at` da (§6) |
| Bosqich o'zgarishi | `lead_status_history(lead_id, from_status, to_status, changed_by, changed_at)` | Indeks `(lead_id, changed_at)` bor. Lid **yaratilganda** yozuv yo'q (`LeadService.java:379, 699` faqat o'zgarishda). Import qilingan lidlar boshlang'ich bosqichi tarixsiz |
| Bosqich turi | `lead_stages(code, kind OPEN/CONVERTED/REJECTED, sort_order)` | Default bosqichlar `LeadStageSeeder.java:67-94`: NEW, CONTACTED, ONLINE/OFFLINE_ENROLLED, ONLINE/OFFLINE_PAID, CONVERTED_ONLINE/OFFLINE, REJECTED |
| Konvertatsiya | `leads.converted`, `students.converted_from_lead_id` (indeksli), `students.attributed_user_id` | Konvertatsiya vaqti faqat tarixdan (`to_status ∈ CONVERTED kind`) |
| Tayinlash | `leads.assigned_user_id`, `leads.assigned_at` | Faqat **oxirgi** tayinlash |
| Vazifalar | `tasks(assigned_to, status, due_at, completed_at, completed_by, lead_id)` | Indekslar `(assigned_to, status, due_at)`, `(lead_id, status, due_at)` |
| Izohlar | `lead_comments(lead_id, author, created_at)` | — |
| To'lovlar | `payments(student_id, student_group_id, status, payment_date, cash_amount, received_by, created_at)` | Lid bilan bog'lanish `students.converted_from_lead_id` orqali |
| Davrlar | `billing_periods(student_group_id, period_start, period_end, amount, refunded_amount, status, charge_tx_id, migration_run_id)` | Yopilgan payt yo'q |
| Qarz | `student_groups(debt_since, payment_status, balance)`, `DebtorService` | Faqat hozirgi holat |
| Jadval | `group_schedule_days(group_id, day_of_week, start_time, end_time)`, `timetable` | Tarix yo'q: jadval o'zgarsa o'tgan "rejadagi darslar" ham o'zgaradi |
| Davomat | `attendance(student_id, group_id, attendance_date, status, created_at, marked_by)` | Unique `(student_id, group_id, attendance_date)` |
| Yozilma | `student_groups(join_date, leave_date, is_trial, payment_start_date, exit_reason, exit_date, frozen_from, first_lesson_date)` | `exit_reason` — erkin matn |
| Audit | `audit_logs(action, entity_type, entity_id, user_id, created_at)` | `LOGIN`, `ASSIGN` amallari bor |

### 2.2 Yetishmayotgan ma'lumotlar

| # | Nima yetishmaydi | Nega kerak | Taklif (§3) | Tarixni to'ldirish |
|---|---|---|---|---|
| G1 | **"Tashrif" belgisi** — qaysi bosqich "keldi" ekanini bildiruvchi atribut yo'q. `kind` faqat OPEN/CONVERTED/REJECTED | `funnel.visited` | `lead_stages.funnel_step` = `NONE \| CONTACTED \| VISITED`, sozlamalar sahifasidan tanlanadi. Qaror (§7 #1): CONTACTED → `CONTACTED`; yangi `VISITED_TRIAL` va *_PAID → `VISITED`; *_ENROLLED → `NONE`. CONVERTED kind avtomatik VISITED dan yuqori | `lead_status_history` + yangi `funnel_step` dan hisoblanadi (o'tmish ham, chunki bosqich kodi tarixda bor) |
| G2 | **Lid qadam sanalari** denormalizatsiya qilinmagan — har so'rovda tarix bo'yicha `min(changed_at)` qidiriladi | Tezlik, kogorta | `leads.contacted_at`, `visited_at`, `converted_at`, `rejected_at` — birinchi kirish (write-once). `LeadService.recordStatusChange` yozadi | Bir martalik SQL: tarixdan `min(changed_at)`. Import qilingan va tarixsiz lidlar uchun `NULL` |
| G3 | **Tayinlash tarixi** yo'q — `assigned_user_id` ustiga yoziladi | `operators.assigned`, birinchi javob vaqti | `lead_assignments(lead_id, user_id, assigned_at, assigned_by, unassigned_at)` | `leads.assigned_user_id/assigned_at` dan bitta qator. `audit_logs(action='ASSIGN', entity_type='Lead')` dan qo'shimcha (taxminiy) |
| G4 | **Birinchi javob** saqlanmaydi | `firstResponse` | `lead_assignments.first_response_at`, `first_response_kind` (STATUS/TASK/COMMENT) — birinchi harakatda yoziladi | Tarix, vazifa, izohdan bir martalik hisob |
| G5 | **Davr qachon yopilgani** (`paid_on`) yo'q. FIFO faqat "hozirgi qarz boshi"ni beradi | `collections` (o'z vaqtida / kechikib / o'rtacha kechikish) | `billing_periods.paid_on DATE`, `paid_at TIMESTAMP`, `paid_tx_id`, `coverage_source`; `due_date`, `grace_until` (§3.2). `BillingSnapshotService.refresh` FIFO ni xronologik qayta o'ynab yangilaydi | Ledger replay. Migratsiya davrlari uchun §3.2.3 |
| G6 | **Sinov boshlangan / tugagan sana** va natija yo'q. `is_trial` konvertatsiyada `false` bo'ladi va tarix yo'qoladi | `trials` | `student_groups.trial_started_at`, `trial_converted_at`, `trial_outcome` (`IN_TRIAL / CONVERTED / LEFT / NO_SHOW`) | Hozir sinovdagilar — aniq. Konvert bo'lganlar — taxminiy: `payment_start_date` dan oldingi birinchi PRESENT/LATE davomat. `trial_source='BACKFILL'` belgisi |
| G7 | **To'lov ↔ lid** to'g'ridan-to'g'ri bog'lanmagan; "birinchi to'lov" har safar hisoblanadi | `funnel.firstPayments` | Yangi ustun **shart emas**: `students.converted_from_lead_id` + `payments(student_id, status, payment_date)` indeksi. Ixtiyoriy: `students.first_payment_id`, `first_payment_date` (to'lov / bekor qilishda yangilanadi) | Bir martalik SQL |
| G8 | **Dam olish kunlari va bekor qilingan darslar** yo'q | `attendance.planned` (aks holda bayramda "40/42 emas, 0/42") | `holidays(date, name, scope)` va `lesson_exceptions(group_id, date, kind CANCELLED/MOVED/EXTRA, moved_to, reason, created_by)` | Yo'q — boshlangan kundan |
| G9 | **Jadval tarixi** yo'q | O'tgan davr `planned` barqarorligi | `director_daily_stats` da kun yakunidagi `planned` muzlatiladi (§3.6). To'liq jadval tarixi — v2 da emas (§7 #7) | — |
| G10 | **Chiqish sababi** erkin matn (`FROZEN`, `TRANSFERRED`, `LEFT`, `GRADUATED`, `AUTO_ARCHIVE`, qo'lda yozilgan…) | `retention` sabablari | `student_groups.exit_reason_code` (enum, §3.4) + mavjud `exit_notes`. remove-student va freeze'da majburiy | Ma'lum qiymatlar xaritasi, qolgani `OTHER` |
| G11 | **Kunlik tarixiy snapshot** yo'q — qarzdorlar soni, rejadagi darslar "o'sha kuni qancha edi" | `debtors` tarixi, Telegram, tezlik | `director_daily_stats(stat_date, section, payload jsonb, computed_at, final)` | Faqat ishga tushgan kundan |
| G12 | **Operatorning ishda bo'lgani** (smena, ish vaqti) yo'q | "ishlayaptimi" | v1 da `audit_logs` LOGIN + harakatlar soni. Ish jadvali — §7 #13 | — |

---

## 3. Sxema o'zgarishlari — `V53__director_dashboard.sql`

**Nega alohida V53:**
- V52 billing cutover uchun egasi bilan kelishilgan va dry-run hash'iga ta'sir qiladigan fayl. Unga qo'shimcha kiritilmaydi.
- V53 faqat **qo'shimcha** (additive) va idempotent (`IF NOT EXISTS`).
- V52 dan **keyin** bajariladi, chunki `billing_periods` ga tayanadi.

### 3.1 Lidlar
```sql
ALTER TABLE lead_stages ADD COLUMN IF NOT EXISTS funnel_step VARCHAR(20) NOT NULL DEFAULT 'NONE';
-- CHECK (funnel_step IN ('NONE','CONTACTED','VISITED')) — EnumCheckConstraintCleaner bilan mos nomlanadi
UPDATE lead_stages SET funnel_step = 'CONTACTED' WHERE code = 'CONTACTED' AND funnel_step = 'NONE';
UPDATE lead_stages SET funnel_step = 'VISITED'
 WHERE code IN ('ONLINE_PAID','OFFLINE_PAID') AND funnel_step = 'NONE';           -- §7 #1 qarori
-- *_ENROLLED → NONE (o'zgarmaydi); yangi bosqich VISITED_TRIAL ("Sinovga keldi") CONTACTED dan keyin qo'shiladi

ALTER TABLE leads ADD COLUMN IF NOT EXISTS contacted_at TIMESTAMP;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS visited_at   TIMESTAMP;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS converted_at TIMESTAMP;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS rejected_at  TIMESTAMP;

CREATE TABLE IF NOT EXISTS lead_assignments (
    id                  BIGSERIAL PRIMARY KEY,
    lead_id             BIGINT    NOT NULL REFERENCES leads(id),
    user_id             BIGINT    NOT NULL REFERENCES users(id),
    assigned_at         TIMESTAMP NOT NULL,
    assigned_by         BIGINT    REFERENCES users(id),
    unassigned_at       TIMESTAMP,
    first_response_at   TIMESTAMP,
    first_response_kind VARCHAR(20),          -- STATUS | TASK | COMMENT
    source              VARCHAR(20) NOT NULL DEFAULT 'LIVE'   -- LIVE | BACKFILL
);
```

- **Yozuvchilar:**
  - `LeadService.recordStatusChange` — `*_at` ustunlarni birinchi marta to'ldiradi;
  - `assign` — `lead_assignments` ga qator, oldingisiga `unassigned_at`;
  - status o'zgarishi, vazifa `DONE`, izoh — `first_response_at IS NULL` bo'lsa to'ldiradi.
- **`funnel_step` o'zgarsa** (sozlamalar), `visited_at` qayta hisoblanadi: SA uchun `POST /api/admin/dashboard/recompute-funnel`.

### 3.2 `billing_periods` — muddat va yopilish

#### 3.2.1 Ustunlar
```sql
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS due_date        DATE;        -- = period_start
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS grace_until     DATE;        -- = due_date + grace (yozilgan paytdagi)
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS paid_on         DATE;        -- yopgan kreditning effective_date si
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS paid_at         TIMESTAMP;   -- yopgan kredit yozilgan payt (created_at)
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS paid_tx_id      BIGINT;      -- yopgan kredit (balance_transactions.id)
ALTER TABLE billing_periods ADD COLUMN IF NOT EXISTS coverage_source VARCHAR(20); -- FIFO | MIGRATION_REPLAY
UPDATE billing_periods SET due_date = period_start WHERE due_date IS NULL;
UPDATE billing_periods SET grace_until = period_start + 3 WHERE grace_until IS NULL;
```
"O'z vaqtida" `paid_on` bo'yicha baholanadi, chunki orqaga sanalangan to'lovda pul haqiqatan `payment_date` da kelgan. `paid_at` esa kiritish kechikishini ko'rsatadi (buxgalter intizomi).

#### 3.2.2 Hisob — xronologik FIFO ("coverage replay")
billing-v2 `FifoDebt` "hozir qaysi majburiyat ochiq"ni beradi. Bu yerda esa **har majburiyat qachon yopilgani** kerak.

1. SG ledgerini `(effective_date, id)` bo'yicha o'qiladi. REVERSAL zanjirlari asl yozuvga qo'shib netlanadi (`FifoDebt.rootOf` bilan bir xil).
2. **Neytral yozuvlar chiqariladi** (billing-v2 §9.2 bo'yicha ularning yig'indisi 0):
   - `PERIOD_CHARGE` va `billing_period_id IS NULL` (v1 debetlari);
   - `MANUAL_ADJUST '[ledger-repair]%'`;
   - `MIGRATION`.
3. Majburiyatlar (manfiy net) FIFO tartibida navbatga qo'yiladi. Kreditlar kelgan sari ketma-ket yopiladi. Majburiyat `paid_on` = uni **to'liq** yopgan kreditning `effective_date` si, `paid_tx_id` = o'sha kredit.
4. Kredit majburiyatdan oldin kelgan bo'lsa (oldindan to'lov): majburiyat paydo bo'lishi bilan yopiladi va `paid_on = kredit sanasi` (≤ `due_date`) → ON_TIME.
5. Natija `billing_periods` ga yoziladi; o'zgarmagan qatorlar yozilmaydi.
6. **Qachon ishlaydi:** `BillingSnapshotService.refresh(sg)` ichida, shu tranzaksiyada. Har ledger amalidan keyin (to'lov, bekor qilish, muzlatish, accrual).
   - Bekor qilish `REVERSAL` qo'shadi → replay davrni qayta ochadi (`paid_on = NULL`) yoki kechroq kredit bilan yopadi.

#### 3.2.3 Migratsiya bilan moslik
`MigrationPlanner` / `BillingMigrationService.applyOne` o'zgarmaydi — u davrlar va MIGRATION yozuvini yozadi, oxirida `snapshotService.refresh(sg)` ni chaqiradi. Replay shu yerda ishlaydi:

- **R dan T gacha `CHARGED` davrlar:**
  - v1 dagi haqiqiy `PAYMENT` kreditlari (effective = to'lov sanasi) ular bilan FIFO bo'yicha juftlanadi;
  - v1 `PERIOD_CHARGE` va `MIGRATION` replay'dan chiqariladi (2-qadam), shuning uchun davr **o'sha to'lov sanasida** yopiladi, cutover sanasida emas;
  - `coverage_source = 'MIGRATION_REPLAY'`.
- **Misol** (billing-v2 §9.3, 1-qator): 20.09 dagi 700 000 to'lov 20.09 davrini yopadi → `paid_on = 20.09`, ON_TIME.
  - Neytral yozuvlar chiqarilmaganda MIGRATION krediti (T = 05.10) tufayli "LATE, 15 kun" chiqardi.
- **`MIGRATED` (R dan oldingi) davrlar:** `paid_on` hisoblanmaydi, `collections` ga kirmaydi.
- **Ma'lumot sifati:** v1 to'lovlarida `effective_date` V52 da `created_at` dan to'ldirilgan bo'lishi mumkin. Shuning uchun cutover'dan oldingi `due_date` lar "taxminiy" deb belgilanadi. UI cutover sanasidan oldingi davrlar uchun "taxminiy" belgisini ko'rsatadi.
- **Dry-run hisoboti** (`MigrationPlanner.SgPlan`) ga ixtiyoriy ustun: `estimatedOnTime/late/unpaid`. Egasi migratsiyadan keyingi "o'z vaqtida" ko'rsatkichini oldindan ko'radi. Bu MigrationPlanner ga **o'qish-only** qo'shimcha, hash'ga kirmaydi.
- **Mavjud davrlarni to'ldirish:** V53 dan keyin bir marta `POST /api/admin/billing/periods/recompute-coverage` (SA) — har SG uchun replay. Faqat `billing_periods` ga yozadi, ledgerga tegmaydi.

### 3.3 `student_groups` — sinov
```sql
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS trial_started_at   DATE;
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS trial_converted_at DATE;
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS trial_outcome      VARCHAR(20);  -- IN_TRIAL | CONVERTED | LEFT | NO_SHOW
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS trial_source       VARCHAR(20);  -- LIVE | BACKFILL
```

| Hodisa | Yozuvchi | Natija |
|---|---|---|
| Sinovli SG yaratildi | `GroupService.addStudentToGroup`, lid konvertatsiyasi | `trial_outcome = IN_TRIAL` |
| Sinovda birinchi PRESENT/LATE | `AttendanceService` (`LessonChargeService.sync` yonida) | `trial_started_at = attendance_date` (agar NULL) |
| Sinovdan to'lovliga | `PaymentBookingService` (trial konvertatsiyasi), `EnrollmentLifecycleService.reanchor(isTrial=false)` | `trial_converted_at = bugun`, `CONVERTED` |
| Sinovda guruhdan chiqdi | `EnrollmentLifecycleService.leave` | `LEFT` (`trial_started_at` NULL bo'lsa `NO_SHOW`) |

### 3.4 Chiqish sababi
```sql
ALTER TABLE student_groups ADD COLUMN IF NOT EXISTS exit_reason_code VARCHAR(30);
-- TRANSFERRED | FROZEN | AUTO_ARCHIVE | GRADUATED | PRICE | SCHEDULE | MOVED_AWAY | QUALITY | HEALTH | NO_TIME | OTHER
UPDATE student_groups SET exit_reason_code = CASE
  WHEN exit_reason IN ('TRANSFERRED','FROZEN','AUTO_ARCHIVE','GRADUATED') THEN exit_reason
  WHEN exit_reason IS NULL THEN NULL ELSE 'OTHER' END
 WHERE exit_reason_code IS NULL AND (is_active = FALSE OR frozen_from IS NOT NULL);
```
`remove-student` va `freeze` so'rovlariga `reasonCode` (majburiy) qo'shiladi — bu API o'zgarishi, §7 #12.

### 3.5 Kalendar
```sql
CREATE TABLE IF NOT EXISTS holidays (
    holiday_date DATE PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    created_by   BIGINT REFERENCES users(id),
    created_at   TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS lesson_exceptions (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT NOT NULL REFERENCES groups(id),
    lesson_date DATE   NOT NULL,
    kind        VARCHAR(20) NOT NULL,       -- CANCELLED | MOVED | EXTRA
    moved_to    DATE,
    reason      VARCHAR(500),
    created_by  BIGINT REFERENCES users(id),
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT uk_lesson_exceptions UNIQUE (group_id, lesson_date, kind)
);
```
- `GroupScheduleService.hasLessonOn` bu jadvallarni hisobga oladi.
- Ta'sir doirasi kengroq: PER_LESSON `nthUpcomingLesson`, `missing-attendance` ham to'g'rilanadi. Bu ijobiy yon ta'sir.

### 3.6 Kunlik snapshot
```sql
CREATE TABLE IF NOT EXISTS director_daily_stats (
    stat_date   DATE        NOT NULL,
    section     VARCHAR(30) NOT NULL,      -- funnel | collections | debtors | attendance | trials | operators
    payload     TEXT        NOT NULL,      -- §4 dagi section DTO (period=DAY), JSON matn
    computed_at TIMESTAMP   NOT NULL,
    is_final    BOOLEAN     NOT NULL DEFAULT FALSE,
    version     INTEGER     NOT NULL DEFAULT 1,
    PRIMARY KEY (stat_date, section)
);
```
- **20:00** — Telegram uchun hisoblanadi (`final = false`).
- **23:55** — kun yakuni (`final = true`).
- **Har kecha** — oxirgi 7 kun qayta hisoblanadi: kechikib kiritilgan to'lov va davomat. `debtors` bundan mustasno — u faqat o'z kunida yoziladi, chunki "o'sha kungi holat" qayta tiklanmaydi.

### 3.7 Indekslar — §6.

---

## 4. API

**Asos:** `/api/dashboard/director`. Hammasi `ApiResponse` ichida.

**Rol bo'yicha bo'limlar:**
- `SUPER_ADMIN` — hammasi;
- `ADMIN` — `funnel, attendance, trials, operators`;
- `ACCOUNTANT` — `collections, debtors`.

Ruxsatsiz bo'lim javobda `null` va `meta.hiddenSections[]` da qaytadi (403 emas). Drill-down esa ruxsatsiz bo'lsa 403.

**Umumiy parametrlar:**
- `date` (default bugun);
- `period=DAY|WEEK|MONTH|CUSTOM`, `from`, `to`;
- `operatorId?` (`funnel`/`operators` uchun filtr), `groupId?`, `teacherId?`;
- `includeImported=false`.

### 4.1 `GET /api/dashboard/director` — xulosa
```jsonc
DirectorSummaryDto {
  meta: { date, period, from, to, asOf, timezone: "Asia/Tashkent",
          source: "LIVE" | "SNAPSHOT" | "MIXED", hiddenSections: [] },
  funnel: {
    activity: { leadsCreated, contacted, visited, converted, rejected,
                firstPayments: { total, fromLeads, walkIn } },
    cohort:   { size, steps: [ { step, reached, rate, medianDaysToStep } ] }   // period != DAY
  },
  collections: {
    due:       { count, amount },
    collected: { count, amount },
    onTime:    { count, amount }, late: { count, amount },
    pending:   { count, amount }, unpaid: { count, amount },
    collectionRate, avgDelayDays, avgLateDelayDays,
    collectedOnDay: { count, amount }, cashIn,
    estimated: boolean          // cutover'dan oldingi davrlar bor
  },
  debtors: { count, amount, overdue7Plus, newToday, clearedToday, closedDebt, source },
  attendance: { planned, taken, takenOnTime, missing, unplanned, rate, upcomingToday },
  trials: { cohort, noShow,
            buckets: { D0, D1, D2_7, D8_PLUS, IN_TRIAL, NOT_PAID },   // har biri {count, percent}
            conversionRate, stayed30 },
  operators: { activeOperators, assigned, medianFirstResponseMin, noResponse,
               tasksDone, tasksOverdueOpen, top: [ OperatorRowDto ] /* 5 tasi */ },
  headline: [ "34 lid → 17 tashrif → 9 to'lov", "28 muddati kelgan → 21 undirildi", … ]  // Telegram bilan bir xil matn
}
```

### 4.2 Drill-down (har raqam bosilganda)
Hammasi sahifalangan (`page`, `size ≤ 200`). Har qatorda kartaga o'tish uchun `studentId` / `leadId` / `groupId` bor.

| Endpoint | Parametr | Qator (DTO) | Rol |
|---|---|---|---|
| `GET …/funnel/leads` | `step=CREATED\|CONTACTED\|VISITED\|CONVERTED\|FIRST_PAYMENT\|REJECTED`, `mode=ACTIVITY\|COHORT` | `{leadId, fullName, phone, source, createdAt, stepAt, currentStage, assignedUser, studentId?, firstPaymentDate?, firstPaymentAmount?}` | SA, A |
| `GET …/collections/periods` | `bucket=DUE\|COLLECTED\|ON_TIME\|LATE\|PENDING\|UNPAID\|COLLECTED_ON_DAY` | `{studentGroupId, studentId, studentName, phone, groupName, periodStart, periodEnd, dueDate, graceUntil, amountDue, paidOn, paidAt, delayDays, receiptNumber?, coverageSource}` | SA, ACC |
| `GET …/debtors` | `scope`, `minDays` | billing-v2 `/api/payments/debtors` qatori (aynan o'sha servis) | SA, ACC |
| `GET …/attendance/lessons` | `status=PLANNED\|TAKEN\|MISSING\|LATE_MARKED\|UNPLANNED` | `{groupId, groupName, teacherId, teacherName, date, startTime, endTime, activeStudents, markedCount, firstMarkedAt, status}` | SA, A |
| `GET …/trials/enrollments` | `bucket=D0\|D1\|D2_7\|D8_PLUS\|IN_TRIAL\|NOT_PAID\|NO_SHOW` | `{studentGroupId, studentId, studentName, phone, groupName, trialStartedAt, convertedDay?, days?, outcome, leadId?, operator?}` | SA, A |
| `GET …/retention` | `cohortFrom`, `cohortTo` (oylar) | `{cohorts: [{month, size, retained: [k0..k12 %]}]}` | SA |
| `GET …/retention/exits` | `reasonCode?` | `{studentId, studentName, groupName, exitDate, reasonCode, notes, monthsStudied}` | SA |
| `GET …/operators` | — | `OperatorRowDto {userId, fullName, role, assigned, firstResponse {median, p90, within15m, within1h}, noResponse, tasks {done, doneLate, overdueOpen}, converted, conversionRate, firstPayments, paymentsReceived {count, amount}, activity {statusChanges, comments, lastSeenAt}}` | SA, A |
| `GET …/operators/{userId}/leads` | `metric=ASSIGNED\|NO_RESPONSE\|SLOW_RESPONSE\|CONVERTED` | funnel/leads qatori + `assignedAt, firstResponseAt, responseMinutes, firstResponseKind` | SA, A |
| `GET …/operators/{userId}/tasks` | `state=DONE\|DONE_LATE\|OVERDUE_OPEN` | `{taskId, title, type, leadId?, studentId?, dueAt, completedAt?, result?}` | SA, A |
| `GET …/trend` | `metric=<section.field>`, `period=DAY`, `from`, `to` (≤ 92 kun) | `[{date, value, final}]` — `director_daily_stats` dan | Bo'lim roli |

**Sozlamalar** (mavjud lead-stage API ga qo'shimcha):
- `PUT /api/lead-stages/{id}` → `funnelStep` maydoni (SA, A);
- `GET/POST/DELETE /api/holidays` (SA, A);
- `POST /api/groups/{id}/lesson-exceptions` (SA, A, guruh o'qituvchisi).

### 4.3 Mavjud endpointlar bilan munosabat
- `/api/dashboard/stats`, `/api/analytics/*`, `/api/analytics/staff*` o'zgarmaydi.
- Qarzdorlar raqami billing-v2 yagona ta'rifidan olinadi, shuning uchun hamma joyda bir xil chiqadi.
- `operators` bo'limi `StaffAnalyticsService` ni almashtirmaydi. Uning o'rni — tayinlash tarixi va javob vaqti.

---

## 5. Kunlik Telegram xulosasi (ixtiyoriy)

| Sozlama | Default |
|---|---|
| `app.director-digest.enabled` | `false` |
| `app.director-digest.cron` | `0 0 20 * * *` (Asia/Tashkent) |
| `app.director-digest.chat-ids` | env `DIRECTOR_DIGEST_CHAT_IDS` (vergul bilan) — sirlar env'da |
| `app.director-digest.dashboard-url` | Admin panel havolasi |

**Tartib:**
1. 20:00 da `director_daily_stats` hisoblanadi (`final = false`).
2. Shundan matn tuziladi va mavjud `TelegramService.sendMessage(chatId, text)` bilan yuboriladi.
3. Takroriy yuborishga qarshi `director_digest_log(stat_date, chat_id, sent_at, ok, error)` jadvali ishlatiladi. Ilova qayta ishga tushsa ham bir kunga bitta xabar ketadi.

**Matn** (raqamlar §4.1 `headline` bilan aynan bir xil):
```
📊 Adizone — 02.10.2026 (20:00 holati)
Lidlar: 34 → tashrif 17 → birinchi to'lov 9
To'lovlar: 28 muddati kelgan → 21 undirildi (o'z vaqtida 18, kechikib 3), 5 kutilmoqda
Qarzdorlar: 7 ta, 4 900 000 so'm (bugun +2 / −1)
Davomat: 42 darsdan 40 tasida (2 ta yo'q: Matematika-3, Ingliz-1)
Sinov: 6 kelgan → 2 shu kuni to'ladi
Operatorlar: javob medianasi 12 daq, javobsiz 3 lid, muddati o'tgan vazifa 5
👉 <dashboard havolasi>
```

**Qoidalar:**
- Shaxsiy ma'lumot yo'q — ism, telefon yuborilmaydi. Faqat soni va guruh nomi.
- 20:00 dagi raqamlar kunning to'liq natijasi emas; matnda "20:00 holati" deb yoziladi.
- Xato bo'lsa xabar log'ga yoziladi, ilova to'xtamaydi. SA dashboardida "oxirgi xulosa yuborilmadi" ogohlantirishi chiqadi.

---

## 6. Ishlash (performance)

### 6.1 Hajm taxmini
| Ma'lumot | Hozir (taxmin) | 2 yildan keyin |
|---|---|---|
| Lidlar | ~10 ming | ~60 ming |
| `lead_status_history` | ~40 ming | ~250 ming |
| O'quvchi / SG | ~1–2 ming | ~6 ming |
| `billing_periods` | ~2 ming/oy ko'payadi | ~50 ming |
| `attendance` | ~20 ming/oy | ~500 ming |

Hammasi "kichik": to'g'ri indeks bilan bitta bo'lim so'rovi < 50 ms. Asosiy xavf indekssiz `created_at`/`changed_at` skanlari va N+1.

### 6.2 Indekslar (V53)
```sql
CREATE INDEX IF NOT EXISTS idx_leads_created_at            ON leads (created_at);
CREATE INDEX IF NOT EXISTS idx_leads_converted_at          ON leads (converted_at);
CREATE INDEX IF NOT EXISTS idx_leads_visited_at            ON leads (visited_at);
CREATE INDEX IF NOT EXISTS idx_lead_status_history_changed ON lead_status_history (changed_at, to_status);
CREATE INDEX IF NOT EXISTS idx_lead_assignments_user       ON lead_assignments (user_id, assigned_at);
CREATE INDEX IF NOT EXISTS idx_lead_assignments_lead       ON lead_assignments (lead_id, assigned_at);
CREATE INDEX IF NOT EXISTS idx_tasks_completed             ON tasks (completed_by, completed_at);
CREATE INDEX IF NOT EXISTS idx_lead_comments_author        ON lead_comments (author_id, created_at);
CREATE INDEX IF NOT EXISTS idx_payments_student_paid       ON payments (student_id, status, payment_date);
CREATE INDEX IF NOT EXISTS idx_payments_received           ON payments (received_by, payment_date);
CREATE INDEX IF NOT EXISTS idx_billing_periods_due         ON billing_periods (due_date) WHERE status IN ('CHARGED','PARTIALLY_REFUNDED');
CREATE INDEX IF NOT EXISTS idx_billing_periods_paid_on     ON billing_periods (paid_on);
CREATE INDEX IF NOT EXISTS idx_attendance_group_date       ON attendance (group_id, attendance_date);
CREATE INDEX IF NOT EXISTS idx_student_groups_trial        ON student_groups (trial_started_at) WHERE trial_started_at IS NOT NULL;
```
Ustun nomlari entity'lar bilan tekshirilgan: `lead_comments.author_id`, `tasks.completed_by`, `payments.received_by`.

### 6.3 Hisoblash strategiyasi
- **O'tgan kunlar** (`date < bugun` va `final = true`): `director_daily_stats` dan o'qiladi, so'rov ~ 1 ms. `WEEK`/`MONTH` — kunlik snapshotlar yig'indisi emas, chunki kogorta va ulushlar qo'shilmaydi. Shuning uchun quyidagicha:
  - hodisa sanoqlari (`leadsCreated`, `due`, `planned`…) yig'iladi;
  - kogortalar, medianalar va ulushlar jonli hisoblanadi (indeksli, < 300 ms).
- **Bugun:** jonli hisob. Natija ilova ichidagi keshda **60 soniya** saqlanadi. Kesh kaliti `(section, date, period, filters, role)`.
  - Spring cache hozir sozlanmagan. Taklif: Caffeine (`spring-boot-starter-cache` + `caffeine`) yoki oddiy TTL map.
  - Ledger yoki davomat yozilishi keshni bekor qilmaydi — 60 soniyalik eskirish qabul qilinadi.
- **Rejadagi darslar:** guruhlar jadvali bitta so'rov bilan xotiraga olinadi; `(group, date)` Java'da hisoblanadi. 300 guruh × 31 kun ≈ 9 300 iteratsiya — ahamiyatsiz.
  - Davomatlar `SELECT DISTINCT group_id, attendance_date … WHERE attendance_date BETWEEN` bitta so'rov bilan olinadi.
  - Hozirgi `buildMissingForGroup` har guruh uchun alohida so'rov qiladi — dashboardda ishlatilmaydi.
- **Coverage replay** (§3.2.2): har ledger amalida bitta SG uchun O(ledger qatorlari), odatda < 100 qator. `refresh` ichida 1–3 ms qo'shiladi. To'liq qayta hisoblash (`recompute-coverage`) har SG uchun alohida tranzaksiyada, ~6 ming SG ≈ 1–2 daqiqa, faqat qo'lda.
- **Birinchi javob vaqti:** `lead_assignments.first_response_at` oldindan yozilgani uchun so'rov oddiy agregat. Ish vaqti daqiqalari Java'da hisoblanadi.

### 6.4 Maqsad
| So'rov | p95 |
|---|---|
| `GET /director` (o'tgan kun, snapshot) | < 50 ms |
| `GET /director` (bugun, keshsiz) | < 800 ms |
| `GET /director` (bugun, keshdan) | < 20 ms |
| Drill-down sahifasi | < 300 ms |
| 20:00 snapshot + Telegram | < 30 s |

---

## 7. Ochiq savollar (har biriga taklif bilan)

1. **"Tashrif" nima?**
   - Hozirgi bosqichlarda "keldi" yo'q. `*_ENROLLED` ("yozildi") — yozilgan, lekin hali kelmagan bo'lishi mumkin.
   - **Taklif:** bosqichlar ro'yxatiga yangi `VISITED` ("Sinovga keldi") bosqichi qo'shiladi, `funnel_step = VISITED`. `*_ENROLLED` esa `NONE` qoladi.
   - Bundan tashqari, lid konvert qilingan o'quvchining birinchi PRESENT/LATE davomati ham tashrif sanaladi. Shunda operator bosqichni qo'lda o'zgartirishni unutsa ham raqam to'g'ri bo'ladi.
   - Muqobil (sozlamasiz, tezroq): `*_ENROLLED` va `*_PAID` → VISITED, V53 default'i shunday.
   - **Egasi tanlaydi.**
   **Qaror:** yangi lid bosqichi **"Sinovga keldi"** (`code = VISITED_TRIAL`, `funnel_step = VISITED`) seed qilinadi (CONTACTED dan keyin). `*_ENROLLED` → `NONE`, `*_PAID` → `VISITED`. Qo'shimcha: konvert qilingan o'quvchining birinchi PRESENT/LATE davomati ham tashrif — `leads.visited_at` bo'sh bo'lsa yoziladi.
2. **Kunlik ekranda qaysi rejim?**
   - **Taklif:** `DAY` → faqat ACTIVITY ("bugun nima bo'ldi"); `WEEK`/`MONTH` → ikkala rejim, kogorta asosiy.
   - Sabab: bir kunlik kogortada konversiya hali 0, ma'nosiz.
   **Qaror:** taklif qabul qilindi.
3. **Voronkadagi "9 to'lov" — faqat lidlardan kelganlarmi?**
   - **Taklif:** asosiy raqam — lidlardan (`fromLeads`), yonida "+ lidsiz N". Jami alohida ko'rinadi.
   **Qaror:** taklif qabul qilindi.
4. **"Birinchi to'lov"** — o'quvchi bo'yicha (har qanday guruh) yoki har yangi guruh bo'yicha?
   - **Taklif:** o'quvchi bo'yicha. Bekor qilingan to'lov sanalmaydi, keyingisi birinchi bo'ladi.
   - Qayta qaytgan eski o'quvchi (`FORMER_STUDENT`) "birinchi to'lov" sanalmaydi.
   **Qaror:** taklif qabul qilindi.
5. **"O'z vaqtida" chegarasi** — billing grace (3 kun) bilanmi yoki qat'iy `due_date` bilan?
   - **Taklif:** grace bilan (billing-v2 holati bilan bir xil: PAID/PENDING/OVERDUE).
   - Qo'shimcha "billing kunida to'langan" ulushi `paid_on ≤ due_date` ham ko'rsatiladi.
   **Qaror:** taklif qabul qilindi.
6. **PER_LESSON o'quvchilar `collections` da qanday?**
   - Muddati yo'q (oldindan to'lov, dars bo'yicha yechiladi).
   - **Taklif:** `collections` ga kirmaydi. `debtors` ga billing-v2 ta'rifi bo'yicha kiradi.
   - Kerak bo'lsa keyin "dars bo'yicha qarz" alohida ko'rsatkich bo'ladi.
   **Qaror:** taklif qabul qilindi.
7. **Davomat: qaysi guruhlar va jadval tarixi?**
   - **Taklif:**
     - faqat `ACTIVE` guruhlar (FORMING da dars bo'lsa ham rejada emas);
     - bir kunda bitta dars;
     - bayram va bekor qilingan darslar yangi jadvallardan olinadi.
   - Jadval tarixi saqlanmaydi; o'tgan kunlar raqami 23:55 dagi snapshotda muzlatiladi.
   - To'liq jadval tarixi (`effective_from/to`) v2 da emas.
   **Qaror:** taklif qabul qilindi.
8. **Davomat "o'z vaqtida" chegarasi?**
   - **Taklif:** o'sha kun 23:59 gacha. Qattiqroq variant — dars tugagandan keyin 2 soat; slot `end_time` bo'lgani uchun texnik jihatdan mumkin.
   - **Egasi tanlaydi.**
   **Qaror:** davomat "o'z vaqtida" = o'sha kun 23:59 gacha (`min(created_at) < D+1 00:00`).
9. **Sinovda "1-kun" nimani bildiradi?**
   - **Taklif:** `D0` = sinov darsi kuni (birinchi kelgan kun), `D1` = ertasi kun.
   - Asos — sinovga **kelgan** kun, yozilgan kun emas. Kelmaganlar `noShow` da alohida.
   - Agar direktor "1-kun" deganda sinov kunini nazarda tutsa, UI'da `D0` "1-kunda" deb yoziladi.
   **Qaror:** taklif qabul qilindi.
10. **"Necha foizi qolgan" va "to'lamadi" qachon qat'iy bo'ladi?**
    - **Taklif:**
      - "qolgan" = sinovdan keyin to'lamaganlar (`NOT_PAID`);
      - sinov **14 kun**dan keyin yoki SG yopilganda "to'lamadi" deb yopiladi;
      - qo'shimcha `stayed30` — to'laganlarning 30 kundan keyin ham o'qiyotgani.
    - 14 kun sozlamada (`app.dashboard.trial-decision-days`).
    **Qaror:** taklif qabul qilindi.
11. **Retention: kim "chiqib ketdi"?**
    - **Taklif:**
      - o'quvchi darajasida;
      - oxirgi ochiq yozilma yopilib, 30 kun ichida yangisi ochilmasa;
      - muzlatish churn emas ("pauza");
      - transfer churn emas;
      - bitirish alohida.
    - Kogorta = birinchi pullik oy.
    **Qaror:** taklif qabul qilindi.
12. **Chiqish sabablari ro'yxati.**
    - **Taklif:** `PRICE, SCHEDULE, MOVED_AWAY, QUALITY, HEALTH, NO_TIME, GRADUATED, TRANSFERRED, AUTO_ARCHIVE, OTHER` (izoh bilan).
    - remove-student va freeze'da `reasonCode` majburiy bo'ladi. Bu API o'zgarishi; eski front `reason` yuboradi, u `OTHER` + izohga xaritalanadi.
    - Ro'yxatni egasi tasdiqlaydi.
    **Qaror:** taklif qabul qilindi.
13. **Birinchi javob vaqti qanday o'lchanadi?**
    - **Taklif:**
      - ish vaqti — dushanba–shanba 09:00–20:00 (sozlamada);
      - tun va yakshanba hisoblanmaydi;
      - harakat — bosqich o'zgarishi, bajarilgan vazifa yoki izoh;
      - qo'ng'iroq yozuvi tizimda yo'q — izoh/vazifa orqali qayd etiladi;
      - Meta'dan avtomatik kelgan va hech kimga tayinlanmagan lidlar operator ko'rsatkichiga kirmaydi; ular "tayinlanmagan lidlar" deb alohida sanaladi.
    - Muqobil: kalendar daqiqalari (oddiyroq, lekin tunda kelgan lidlar natijani buzadi).
    **Qaror:** taklif qabul qilindi.
14. **Operator kim?**
    - **Taklif:** `ADMIN` va `SALES_MANAGER`. SA va boshqa rollar faqat lid tayinlangan bo'lsa ro'yxatga chiqadi.
    - O'qituvchilar bu bo'limda emas; ular uchun davomat intizomi `teacherId` drill-down orqali.
    **Qaror:** taklif qabul qilindi.
15. **ADMIN qaysi bo'limlarni ko'radi?**
    - Talabda "A — operator bo'limi" deyilgan.
    - **Taklif:** ADMIN → `funnel, trials, attendance, operators`; moliya (`collections, debtors`) faqat SA va ACC.
    - Muqobil: ADMIN faqat `operators`. Bunda o'z ishini ko'radigan kishi o'z ishining natijasini (voronka) ko'rmaydi.
    - **Egasi tanlaydi.**
    **Qaror:** ADMIN → `funnel, trials, attendance, operators`; `collections`, `debtors` → faqat SA va ACC.
16. **V53 dan oldingi tarix.**
    - Avtomatik to'ldiriladi:
      - voronka — tarixdan;
      - tayinlash — oxirgi tayinlash va audit'dan;
      - sinov — davomatdan;
      - `paid_on` — replay bilan.
    - Taxminiy, va tarixi yo'q:
      - qarzdorlar tarixi — faqat snapshot ishga tushgan kundan;
      - rejadagi darslar — bayramlar kiritilmagan.
    - **Taklif:** UI'da "taxminiy" belgisi; direktorga boshlanish nuqtasi sifatida V53 deploy kuni beriladi.
    **Qaror:** taklif qabul qilindi.
17. **Telegram: kimga, nima va qachon?**
    - **Taklif:**
      - faqat direktor chat'i (env'da);
      - faqat raqamlar va guruh nomlari, ism/telefon yo'q;
      - 20:00 da.
    - Ixtiyoriy ikkinchi xabar: ertalab 08:00 da kechagi **yakuniy** (`final`) raqamlar, agar ular 20:00 dagidan farq qilsa.
    **Qaror:** taklif qabul qilindi.
18. **Import qilingan lidlar voronkada.**
    - **Taklif:** default chiqariladi (`includeImported=false`). Ular tarixsiz va boshqa bosqichdan boshlanadi, konversiyani buzadi.
    - Filtr bilan ko'rish mumkin.
    **Qaror:** taklif qabul qilindi.
19. **Kechikib kiritilgan ma'lumot o'tgan kunlarni qanchalik o'zgartiradi?**
    - **Taklif:** har kecha oxirgi 7 kun qayta hisoblanadi (`version++`); undan eski kunlar muzlatiladi.
    - `debtors` tarixi qayta hisoblanmaydi.
    - UI'da o'zgargan raqam yonida "yangilandi" belgisi.
    **Qaror:** taklif qabul qilindi.
20. **Kesh texnologiyasi.**
    - **Taklif:** Caffeine (yangi qaram kutubxona, ~1 MB) bilan 60 soniya.
    - Muqobil: Redis — ortiqcha, bitta instansiya ishlaydi.
    **Qaror:** taklif qabul qilindi.

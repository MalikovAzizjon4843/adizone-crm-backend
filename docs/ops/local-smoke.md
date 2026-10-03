# Lokal smoke — backend (IntelliJ) + `adizone_rehearsal` + frontend (`npm run dev`)

> Maqsad: prod nusxasida (V52…V64 qo'llangan) yangi backendni ishga tushirib, billing migratsiyasini va
> asosiy oqimlarni qo'lda tekshirish. **Har qadam — bitta amal.** Sirlar o'rniga `<...>`; ularni faylga yozmang.
> Bog'liq: [deploy-v2.md](deploy-v2.md), [billing-v2-migration.md](billing-v2-migration.md), [env.md](env.md),
> [rehearsal/REPORT.md](rehearsal/REPORT.md) (repoda yo'q).
>
> ⚠️ `adizone_rehearsal` — **prod nusxasi, haqiqiy shaxsiy ma'lumot**. Telegram, Meta va direktor digest
> o'chiq bo'lishi shart (1-bo'lim) — aks holda ota-onalarga xabar ketishi yoki Meta'ga so'rov yuborilishi mumkin.

---

## 0. Tayyorgarlik

0.1. Smoke'dan oldingi holatni saqlang (6-bo'limda shu nusxadan tiklanadi). Bazaga hech kim ulanmagan bo'lishi kerak:
```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d postgres -X -c "CREATE DATABASE adizone_rehearsal_pre_smoke TEMPLATE adizone_rehearsal"
```

0.2. Sxemaning boshlang'ich nusxasini oling (2.3 da ddl-auto o'zgarishlarini solishtirish uchun):
```powershell
& "C:\Program Files\PostgreSQL\18\bin\pg_dump.exe" -h localhost -U postgres -d adizone_rehearsal --schema-only --no-owner -f "$env:TEMP\smoke-schema-before.sql"
```

0.3. Yuklash katalogini yarating (prod yo'li `/opt/crm/uploads` Windows'da yo'q):
```powershell
New-Item -ItemType Directory -Force "$env:TEMP\crm-smoke-uploads"
```

0.4. JWT kalitini yarating (natijani faqat Run Configuration'ga qo'ying, hech qayerga saqlamang):
```powershell
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

---

## 1. IntelliJ Run Configuration

1.1. *Run → Edit Configurations → Spring Boot* → main class `com.crm.CrmApplication`, JDK 17, module `crm-system`.

1.2. **Active profiles — bo'sh qoldiring.** `pgtest` profilini HECH QACHON qo'ymang: u `ddl-auto: create-drop` — bazani o'chiradi.

1.3. *Environment variables* (`KALIT=qiymat;KALIT=qiymat`):

| O'zgaruvchi | Qiymat | Nega |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/adizone_rehearsal` | yml dagi `adizone` o'rniga |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | rehearsal jadvallari `postgres` ga tegishli (`--no-owner`) |
| `DB_PASSWORD` | `<lokal postgres paroli>` | majburiy, default yo'q |
| `JWT_SECRET` | `<0.4 dagi Base64>` | majburiy, ≥ 32 bayt |
| `META_SYSTEM_USER_TOKEN` | `<soxta, masalan smoke-fake>` | ilova ishga tushishi uchun; haqiqiy token qo'ymang |
| `META_APP_SECRET` | `<soxta>` | — |
| `META_VERIFY_TOKEN` | `<soxta>` | — |
| `META_ENABLED` | `false` | Meta scheduler va Graph so'rovlari o'chadi |
| `TELEGRAM_BOT_TOKEN` | *(bo'sh)* | e'lon qilinadi, qiymatsiz |
| `TELEGRAM_ENABLED` | `false` | to'lov eslatmasi / davomat xabarlari yuborilmaydi |
| `DIRECTOR_DIGEST_ENABLED` | `false` | (default ham false — aniq bo'lsin) |
| `APP_BILLING_ENABLED` | `false` | **birinchi start uchun majburiy** (3-bo'lim). Billing yoqilgan bo'lsa startdagi accrual migratsiyasiz SG larga davr yozib yuboradi va dry-run hash'i o'zgaradi |
| `APP_UPLOAD_DIR` | `<%TEMP%\crm-smoke-uploads to'liq yo'li>` | fayl/PDF yuklash |
| `LOGGING_LEVEL_COM_CRM` | `INFO` | ixtiyoriy; yml da `DEBUG` — log juda ko'p |

`APP_PAYROLL_CUTOVER_DATE` bermang — cutover qo'llangan migratsiyadan olinadi (3-bo'lim).

1.4. *Run* bosing.

---

## 2. Startda logni tekshirish

2.1. Kutilgan qatorlar (tartib taxminan shu):

| Qator | Ma'nosi | Boshqacha bo'lsa |
|---|---|---|
| `Could not resolve placeholder '...'` **bo'lmasin** | env to'liq | 1.3 dagi o'zgaruvchi yo'q yoki nomi xato |
| `Billing v2 sxemasi tayyor (sequence, billing_periods UNIQUE, idempotency_key UNIQUE)` | V52 to'liq | `Billing v2 sxemasi to'liq emas: [...]` (ERROR) — V52 ni rehearsal'ga qayta qo'llang, to'xtating |
| `Enum CHECK constraint topilmadi — tozalash shart emas` **yoki** `Enum CHECK o'chirildi: … ` + `Enum CHECK tozalash yakunlandi: N ta o'chirildi` | Hibernate enum CHECK lari tozalandi | `Enum CHECK o'chirib bo'lmadi` (ERROR) — egalik muammosi |
| `lead_stages allaqachon to'ldirilgan (N ta) — seed o'tkazib yuborildi` | bosqichlar o'zgarmadi | `lead_stages: N ta bosqich yozildi` — prod nusxasida bo'lmasligi kerak, sababini aniqlang |
| `Billing ACCRUAL … (STARTUP)` **YO'Q** | `APP_BILLING_ENABLED=false` — startup accrual o'tkazib yuborildi | bor bo'lsa — to'xtating, 6.1 bo'yicha bazani tiklang, env ni tuzating |
| `Telegram o'chiq: TELEGRAM_BOT_TOKEN berilmagan` **yo'q** | `TELEGRAM_ENABLED=false` | bor bo'lsa — `TELEGRAM_ENABLED` berilmagan |
| `Started CrmApplication in … seconds` | tayyor | — |

2.2. Brauzerda yoki PowerShell'da: `Invoke-RestMethod http://localhost:8080/actuator/health` → `status = UP`.

2.3. ddl-auto o'zgarishlarini oling va 0.2 dagi nusxa bilan solishtiring:
```powershell
& "C:\Program Files\PostgreSQL\18\bin\pg_dump.exe" -h localhost -U postgres -d adizone_rehearsal --schema-only --no-owner -f "$env:TEMP\smoke-schema-after.sql"
Compare-Object (Get-Content "$env:TEMP\smoke-schema-before.sql") (Get-Content "$env:TEMP\smoke-schema-after.sql")
```

**`ddl-auto: update` prod sxemasida nimani o'zgartirishi mumkin** (faqat qo'shadi — hech narsani o'chirmaydi, ustun turini o'zgartirmaydi):
- entity'da bor, bazada yo'q **ustun / jadval** — yaratiladi (V52…V64 dan keyin kutilmaydi; chiqsa — migratsiyaga yetishmagan ustun, ro'yxatga yozing);
- `@Table(indexes = …)` dagi **indekslar** (masalan `lesson_substitutions` dagilar) — nomi bo'yicha yo'q bo'lsa;
- `@Column(unique = true)` / `@OneToOne` uchun **UNIQUE** (`uk…` nomli) — shu ustunlarda nomi boshqa UNIQUE bo'lsa ham qo'shilishi mumkin (REPORT O2 dagi dublikat indekslar shunday paydo bo'lgan);
- **FK** — shu ustun → jadval FK si umuman yo'q bo'lsa (`fk…` nomli; bor bo'lsa nomidan qat'i nazar qo'shilmaydi);
- yangi enum ustunida **CHECK** — darhol `EnumCheckConstraintCleaner` o'chiradi (2.1).

Farq chiqsa — prod deployda ham xuddi shu bo'ladi: `deploy-v2.md` ga qayd eting.

---

## 3. Billing migratsiyasi (dry-run → ko'rish → approve → apply → verify)

Hammasi SUPER_ADMIN bilan, ilova `APP_BILLING_ENABLED=false` holatida. Buyruqlar bitta PowerShell oynasida
(o'zgaruvchilar saqlanadi).

3.1. Manzil:
```powershell
$api = 'http://localhost:8080'
```

3.2. SA login (parol oynada so'raladi — buyruq tarixiga tushmaydi). Rehearsal'dagi SA: `superadmin` (paroli — prod'niki):
```powershell
$cred = Get-Credential -UserName 'superadmin' -Message 'adizone_rehearsal SUPER_ADMIN'
```

3.3. Token:
```powershell
$login = Invoke-RestMethod -Method Post -Uri "$api/api/auth/login" -ContentType 'application/json' -Body (@{ username = $cred.UserName; password = $cred.GetNetworkCredential().Password } | ConvertTo-Json)
$H = @{ Authorization = "Bearer $($login.data.accessToken)" }
$login.data.role   # SUPER_ADMIN
```
Token 8 soat amal qiladi; 401 olsangiz 3.3 ni takrorlang.

3.4. Cutover sanasi (prod uchun rejalashtirilgan sana bo'lsa — o'sha; bo'lmasa bugun):
```powershell
$T = '<YYYY-MM-DD>'
```

3.5. Dry-run (hech narsa yozmaydi):
```powershell
$dry = Invoke-RestMethod -Method Post -Uri "$api/api/admin/billing/migration/dry-run?cutover=$T" -Headers $H
```

3.6. Xulosani ko'ring (`sgTotal` — repetitsiyada 132 faol yozilma + yopiqlar; `blocking` — bloklovchi anomaliyalar):
```powershell
$dry.data.summary | ConvertTo-Json -Depth 5
```

3.7. Anomaliyali qatorlar:
```powershell
$dry.data.rows | Where-Object { $_.anomalies.Count -gt 0 } | Select-Object studentGroupId, studentName, groupName, category, @{n='anomalies';e={$_.anomalies -join ','}}, blocking, oldDebt, newDebt | Format-Table -AutoSize
```

3.8. (Ixtiyoriy) Egasi uchun xlsx — **shaxsiy ma'lumot**, 6-bo'limda o'chiriladi:
```powershell
Invoke-WebRequest -Uri "$api/api/admin/billing/migration/dry-run.xlsx?cutover=$T" -Headers $H -OutFile "$env:TEMP\smoke-dry-run.xlsx"
```

3.9. Tasdiq (`reportHash` — 3.5 dagi; oradan ma'lumot o'zgarsa 409 `migration.reportChanged` → 3.5 dan qayta):
```powershell
$run = Invoke-RestMethod -Method Post -Uri "$api/api/admin/billing/migration/approve" -Headers $H -ContentType 'application/json' -Body (@{ cutover = $T; a14UsePayable = $false; reportHash = $dry.data.reportHash; approvedByOwner = '<egasi F.I.Sh — smoke>'; note = 'local smoke' } | ConvertTo-Json)
$run.data.id; $run.data.status   # APPROVED
```

3.10. Apply (`confirm=APPLY-<runId>`; billing yoqilgan bo'lsa 409 `migration.billingEnabled`):
```powershell
$apply = Invoke-RestMethod -Method Post -Uri "$api/api/admin/billing/migration/apply?runId=$($run.data.id)&confirm=APPLY-$($run.data.id)" -Headers $H
$apply.data.run.status; $apply.data.migrated.Count; $apply.data.held.Count; $apply.data.errors
```
Kutilgan: `APPLIED`, `errors` bo'sh; `held` — bloklovchi anomaliyali SG lar (3.7 bilan solishtiring).
Kerak bo'lsa egasi qarori bilan: `&exclude=<sgId>,<sgId>` va/yoki `&clearOverrides=true` (3.10 dan oldin, yangi approve shart emas).

3.11. Tekshiruv:
```powershell
(Invoke-RestMethod -Uri "$api/api/admin/billing/verify" -Headers $H).data | ConvertTo-Json -Depth 4
```
Kutilgan: `ok = true`; `sgBalanceMismatch`, `studentBalanceMismatch`, `chargedPeriodsWithoutLedger`, `paymentsWithoutCash` bo'sh;
`heldEnrollments` = 3.10 dagi `held`.

3.12. IntelliJ'da ilovani to'xtating, `APP_BILLING_ENABLED=true` qiling (yoki o'zgaruvchini olib tashlang), qayta *Run*.

3.13. Logda: `Billing ACCRUAL <bugun> (STARTUP): nomzod N, davr M, xato 0`. `xato` > 0 bo'lsa —
`SELECT errors FROM billing_job_runs ORDER BY id DESC LIMIT 1` (psql).

3.14. 3.3 ni takrorlang (yangi token), so'ng 3.11 ni yana bajaring → `ok = true`.

> Payroll: cutover oyi `T` dan oldingi oylar uchun `calculate/generate` → 400 `payroll.beforeCutover` — bu to'g'ri.
> Cutover oyida natija `estimated = true`.

---

## 4. Test foydalanuvchilar, o'qituvchi bog'lash, oylik qoidasi

3.3 dagi `$H` (SA) bilan. Parollar ≥ 8 belgi; parolni oynada so'raydi:

4.1. Umumiy smoke paroli:
```powershell
$pw = (Get-Credential -UserName 'smoke' -Message 'smoke foydalanuvchilar paroli (>= 8)').GetNetworkCredential().Password
```

4.2. Har rol uchun bittadan (ADMIN, SALES_HEAD, SALES_MANAGER, ACCOUNTANT; STUDENT/PARENT login qila olmaydi):
```powershell
foreach ($r in 'ADMIN','SALES_HEAD','SALES_MANAGER','ACCOUNTANT') { $u = Invoke-RestMethod -Method Post -Uri "$api/api/users" -Headers $H -ContentType 'application/json' -Body (@{ firstName = 'Smoke'; lastName = $r; username = "smoke_$($r.ToLower())"; password = $pw; role = $r } | ConvertTo-Json); "$($u.data.id) $($u.data.username) $($u.data.role)" }
```

4.3. O'qituvchi profili (loginsiz) — bog'lashni sinash uchun:
```powershell
$t = Invoke-RestMethod -Method Post -Uri "$api/api/teachers" -Headers $H -ContentType 'application/json' -Body (@{ firstName = 'Smoke'; lastName = 'Teacher'; phone = '+998900000001' } | ConvertTo-Json)
$t.data.id; $t.data.userId   # userId bo'sh
```

4.4. Shu profilga login yaratib bog'lash (profilda login bo'lsa 409 `teacher.user.alreadyLinked`):
```powershell
$tu = Invoke-RestMethod -Method Post -Uri "$api/api/users/create-for-teacher/$($t.data.id)" -Headers $H -ContentType 'application/json' -Body (@{ firstName = 'Smoke'; lastName = 'Teacher'; username = 'smoke_teacher'; password = $pw; role = 'TEACHER' } | ConvertTo-Json)
$tu.data.id; $tu.data.role   # TEACHER
```

4.5. Bog'langanini tekshirish (`userId` = 4.4 dagi id):
```powershell
(Invoke-RestMethod -Uri "$api/api/teachers/$($t.data.id)" -Headers $H).data.userId
```

4.6. Shu o'qituvchiga oylik qoidasi (faqat SA; prod'da `salary_rules` bo'sh — REPORT §3):
```powershell
Invoke-RestMethod -Method Post -Uri "$api/api/salary-rules" -Headers $H -ContentType 'application/json' -Body (@{ role = 'TEACHER'; userId = $tu.data.id; fixedSalary = 3000000; perPayingStudent = 50000; substituteLessonRate = 60000; effectiveFrom = $T } | ConvertTo-Json)
```

4.7. Oylik hisobini ko'rish (cutover oyi; o'quvchisi yo'q — faqat `fixedSalary`):
```powershell
$ym = [datetime]::Parse($T); (Invoke-RestMethod -Uri "$api/api/payroll/calculate/$($tu.data.id)?month=$($ym.Month)&year=$($ym.Year)" -Headers $H).data | Select-Object fullName, role, calculable, baseSalary, totalAmount
```

UI orqali ham bo'ladi: *Foydalanuvchilar → Qo'shish* (4.2), *O'qituvchilar → Qo'shish* + *Login yaratish* (4.3–4.4), *Oylik qoidalari* (4.6).

---

## 5. Frontend (`adizone-admin`, `npm run dev`)

5.1. `adizone-admin/.env.local` (fayl gitignore'da):
```ini
VITE_API_BASE_URL=http://localhost:8080
VITE_WS_URL=ws://localhost:8080/ws
```

5.2. `npm run dev` → Vite odatda `http://localhost:5173` (band bo'lsa 5174).

5.3. Brauzerda **`http://localhost:5173`** ni oching (`127.0.0.1:5174` emas — u ruxsat ro'yxatida yo'q).

**CORS (tasdiqlangan, `SecurityConfig.ALLOWED_ORIGIN_PATTERNS`; WebSocket ham shu ro'yxat):**
`http://localhost:3000`, `http://localhost:5173`, `http://localhost:5174`, `http://127.0.0.1:5173`, `http://127.0.0.1:3000`
(+ prod domenlar, `https://*.vercel.app`). Eski `adizone-crm-front` — `localhost:3000` (vite `server.port`), u ham ruxsatda.
Boshqa port (5175, …) — CORS xatosi; Vite'ni `--port 5173` bilan ishga tushiring.

5.4. Har bir smoke foydalanuvchi bilan kiring (4.2/4.4), rolga xos sahifalar ochilishini va 403 lar kutilganini tekshiring
(SH ruxsatlari — [api-inventory.md §0.2a](../audit/api-inventory.md)).

---

## 6. Smoke tugagach

6.1. IntelliJ'da ilovani to'xtating, so'ng rehearsal bazani smoke'dan oldingi holatga qaytaring (0.1 nusxasidan):
```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d postgres -X -c "DROP DATABASE adizone_rehearsal" -c "CREATE DATABASE adizone_rehearsal TEMPLATE adizone_rehearsal_pre_smoke"
```
(Smoke natijasini keyin ham ko'rish kerak bo'lsa — avval `ALTER DATABASE adizone_rehearsal RENAME TO adizone_smoke_<sana>` qiling.)
Tiklangach nusxani o'chiring: `DROP DATABASE adizone_rehearsal_pre_smoke`.

6.2. Shaxsiy ma'lumotli fayllar: `$env:TEMP\smoke-dry-run.xlsx`, `$env:TEMP\smoke-schema-*.sql` (sxema — xavfsiz, lekin keraksiz),
`$env:TEMP\crm-smoke-uploads\` (yuklangan fayllar, shartnoma PDF lari).

6.3. IntelliJ Run Configuration'dagi `DB_PASSWORD` va `JWT_SECRET` ni o'chiring (yoki konfiguratsiyani o'chiring) —
`.idea/workspace.xml` da ochiq matnda saqlanadi; konfiguratsiya "Store as project file" bo'lsa — repoga tushmasligini tekshiring.

6.4. `adizone-admin/.env.local` ni oldingi qiymatiga qaytaring (yoki o'chiring).

6.5. PowerShell oynasini yoping (`$cred`, `$pw`, `$H` xotirada).

6.6. Topilgan farqlar (2.3 ddl-auto, 3.7 anomaliyalar, 3.13 xatolar) — `deploy-v2.md` / REPORT ga yozing.

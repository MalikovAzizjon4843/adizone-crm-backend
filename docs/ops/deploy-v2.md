# Deploy v2 — runbook (billing v2, dashboard, payroll v2, phase 5, ta'til/imtihon/shartnoma, SALES_HEAD)

> Kod emas — prod'ga chiqarish tartibi. Branch `billing-v2`, migratsiyalar `V52…V64`.
> Bog'liq: [env.md](env.md) (sirlar), [billing-v2-migration.md](billing-v2-migration.md) (billing migratsiyasi
> va rollback), [prod-schema-check.sql](prod-schema-check.sql) (sxema tekshiruvi), [timezone.md](timezone.md).
> **TAXMIN** — serverda tekshirilishi kerak bo'lgan da'vo.

## 0. Eng katta xavflar (qisqa)

| # | Xavf | Oqibat | Chora (bo'lim) |
|---|---|---|---|
| R1 | Prod `-Dspring.config.location=/opt/crm/application.yml` bilan ishlaydi — classpath `application.yml` **umuman o'qilmaydi** | Yangi kalitlar prod'ga yetmaydi: **Meta integratsiyasi o'chadi** (`meta.enabled` kod default'i `false`), **Telegram o'chadi** (`telegram.enabled` default `false`), JSON sanalar UTC da, upload limiti 1 MB, `jwt.*`/`meta.*` yo'q bo'lsa start yiqiladi | §1 — `spring.config.additional-location` ga o'tish |
| R2 | Billing migratsiyasi (apply) | 72 soatdan keyin to'liq rollback yo'q; noto'g'ri `exclude`/A14/A15 — noto'g'ri qarzlar | §3.6, [billing-v2-migration.md](billing-v2-migration.md) |
| R3 | Eski frontend yangi backend bilan to'liq mos emas | Ta'til tasdiqlash (`paid` majburiy) 400, ta'tilni o'chirish 405, pullik imtihonga eski "ro'yxatga olish" 400, shartnoma raqami/holati yangi | §5 — pilot davomida bu amallar faqat yangi panelda (crm.adizone.uz) |
| R4 | Vaqt zonasi | Server avval UTC bo'lgan bo'lsa eski yozuvlar 5 soat orqada | [timezone.md](timezone.md) — deploydan oldin tekshiruv |
| R5 | Enum CHECK cheklovlari (`users.role`, `contracts.status`, ...) | `SALES_HEAD` user yaratish / shartnomani `CANCELLED` qilish 500 | `EnumCheckConstraintCleaner` startda o'chiradi (`app.schema.drop-enum-checks` — prod yml da `false` bo'lmasin), §3.5 tekshiruv |
| R6 | Oshkor bo'lgan sirlar (Meta, Telegram, JWT) | Begona webhook/bot | [env.md](env.md) "Rotatsiya" — shu deploy bilan |
| R7 | Qo'lda migratsiyalar qisman qo'llangan bo'lishi | Startda `BillingSchemaGuard` billingni o'chiradi; 500 lar | §2.1 `prod-schema-check.sql` |

---

## 1. Konfiguratsiya: `spring.config.location` → `additional-location`

### 1.1 Muammo
`spring.config.location` Spring Boot'ning **standart joylarini almashtiradi** — `classpath:/application.yml` yuklanmaydi.
Demak prod faqat `/opt/crm/application.yml` dagi kalitlar bilan ishlaydi; v2 da qo'shilgan yoki classpath'da
o'zgargan har bir kalit (§1.4) prod'da **kod default'iga** tushadi yoki umuman yo'q bo'ladi.

Serverda tekshiring (deploydan OLDIN):
```bash
systemctl cat crm | grep -E 'ExecStart|Environment'
sudo cat /opt/crm/application.yml          # qaysi kalitlar bor (sirlarni ekranga chiqarmang)
```

### 1.2 Tavsiya
1. Classpath `application.yml` — **asos** (repo bilan birga yangilanadi, sirsiz).
2. Sirlar — faqat `/opt/crm/crm.env` (`EnvironmentFile`, [env.md](env.md)): `DB_PASSWORD`, `JWT_SECRET`, `META_*`,
   `TELEGRAM_BOT_TOKEN`, ixtiyoriy `DIRECTOR_DIGEST_*`, `APP_PAYROLL_CUTOVER_DATE`.
3. Serverdagi yml — **faqat prod farqlari** (`/opt/crm/application-prod.yml`, eski faylning nusxasidan qisqartirib):
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://localhost:5432/adizone   # prod farq qilsa
       username: crm_app                              # postgres superuser emas (env.md tavsiyasi)
   app:
     upload:
       dir: /opt/crm/uploads
     billing:
       enabled: false        # FAQAT billing migratsiyasi oynasida (§3.6), keyin olib tashlanadi
   logging:
     level:
       com.crm: INFO         # prod da DEBUG emas
   ```
   Eski fayldagi `jwt.secret`, `meta.*-token`, `telegram.bot-token`, `spring.datasource.password` — **olib tashlanadi**
   (endi env'dan). Eski faylda classpath bilan bir xil qiymatlar ham olib tashlanadi — ular keyingi yangilanishlarni to'sadi.
4. Unit:
   ```ini
   [Service]
   User=crm
   EnvironmentFile=/opt/crm/crm.env
   ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/crm/crm-system.jar \
     --spring.config.additional-location=optional:file:/opt/crm/application-prod.yml
   ```
   `additional-location` dagi qiymatlar classpath'dagini **ustidan yozadi**, qolganlari classpath'dan keladi.
   `optional:` — fayl bo'lmasa ham ishga tushadi.
5. Tekshiruv: start logida `Billing v2 sxemasi tayyor`, enum CHECK tozalovchi xabari, Telegram WARN yo'qligi
   (token berilgan bo'lsa); `GET /actuator/health` → `UP`; `GET /api/meta/status` (SA) — Meta yoqilgan va imzo tekshiruvi on.

> Muqobil (kamroq tavsiya): `spring.config.location=classpath:/,file:/opt/crm/application.yml` — xuddi shu natija,
> lekin unutib qo'yish oson. `-D...` emas, dastur argumenti yoki `SPRING_CONFIG_ADDITIONAL_LOCATION` env ham bo'ladi.

### 1.3 Prod yml da bo'lmasligi kerak (yoki ataylab)
| Kalit | Nega |
|---|---|
| `app.schema.drop-enum-checks: false` | Yangi enum qiymatlari (`SALES_HEAD`, `CANCELLED`, ...) bazada rad etiladi (R5) |
| `app.billing.startup-catch-up: false` | Server to'xtab turgan kunlar uchun accrual yozilmay qoladi (faqat testlarda `false`) |
| `meta.verify-signature: false` | Webhook imzosiz qabul qilinadi |
| `spring.jpa.hibernate.ddl-auto` ning `update` dan boshqa qiymati | V-skriptlar ddl-auto `update` bilan birga ishlashga mo'ljallangan (ustunlarni nullable qo'shadi, skript NOT NULL qiladi) |

### 1.4 Kalitlar ro'yxati (classpath `application.yml` + kod default'lari)

Prod'da **config.location bilan hozir YO'Q bo'lishi mumkin** bo'lganlar — ★ (TAXMIN: serverdagi faylga qarab).

| Kalit | Default / classpath qiymati | Izoh |
|---|---|---|
| ★ `spring.jackson.time-zone` | `Asia/Tashkent` | JSON dagi `Date`/`Instant` |
| ★ `spring.jpa.properties.hibernate.jdbc.time_zone` | `Asia/Tashkent` | JVM default ham Toshkent (`CrmApplication.main`) |
| ★ `spring.jpa.properties.hibernate.jdbc.batch_size`, `order_inserts`, `order_updates` | `50`, `true`, `true` | Unumdorlik |
| ★ `spring.servlet.multipart.max-file-size` / `max-request-size` | `4MB` / `8MB` | Yo'q bo'lsa Spring default 1 MB — chat fayllari yiqiladi |
| ★ `spring.jpa.hibernate.ddl-auto` | `update` | §1.3 |
| `spring.datasource.*` | url `localhost:5432/adizone`, user `postgres`, parol `${DB_PASSWORD}`, hikari 20/5 | Prod user — §1.2 |
| `jwt.secret` / `jwt.expiration` / `jwt.refresh-expiration` | `${JWT_SECRET}` / `28800000` / `604800000` | Sir — env |
| ★ `app.audit.enabled`, `retention-days`, `financial-retention-days` | `true`, `180`, `365` | Q14 |
| `app.upload.dir` | `/opt/crm/uploads` | Servis useri yoza olsin |
| ★ `app.contracts.pdf-dir` | `${app.upload.dir}/contracts` | **Yangi.** Imzolangan shartnoma PDF lari (`/api/files` dan ochilmaydi) |
| ★ `app.payroll.cutover-date` | yo'q (env `APP_PAYROLL_CUTOVER_DATE`) | Bo'lmasa — qo'llangan billing migratsiyasining cutover sanasi |
| ★ `app.payroll.count-discount-covered` | `false` | §11 #7 |
| ★ `app.base-url` | `https://api.adizone.uz` | |
| ★ `app.schema.drop-enum-checks` | `true` (yo'q bo'lsa ham `true`) | R5 |
| ★ `app.attendance.unlock-valid-hours` | `48` | |
| ★ `app.billing.enabled` | `true` | Migratsiya oynasida `false` (§3.6) |
| ★ `app.billing.grace-days` | `3` | PENDING → OVERDUE |
| ★ `app.billing.accrual-cron` | `0 10 0 * * *` | Asia/Tashkent |
| ★ `app.billing.reminder-cron` | `0 0 10 * * *` | Telegram qarz eslatmasi |
| ★ `app.billing.max-catch-up` | `24` | |
| ★ `app.billing.startup-catch-up` | `true` | §1.3 |
| ★ `app.billing.cancel-max-age-days` | `31` | |
| ★ `app.billing.migration-go-live` | `2026-09-18` | Migratsiyadagi G (R ni aniqlash) — **haqiqiy go-live sanasiga to'g'rilang** |
| ★ `app.billing.migration-rollback-hours` | `72` | |
| ★ `app.dashboard.trial-decision-days`, `trial-stay-days`, `churn-grace-days` | `14`, `30`, `30` | |
| ★ `app.dashboard.work-start` / `work-end` / `work-days` | `09:00` / `20:00` / Du–Sha | Operator javob vaqti |
| ★ `app.dashboard.no-response-work-hours`, `cache-seconds` | `24`, `60` | |
| ★ `app.dashboard.digest.enabled` / `cron` / `chat-ids` / `dashboard-url` | `${DIRECTOR_DIGEST_ENABLED:false}` / `0 0 20 * * *` / `${DIRECTOR_DIGEST_CHAT_IDS:}` / `${DIRECTOR_DASHBOARD_URL:}` | `dashboard-url` → yangi front (§5) |
| ★ `app.dashboard.snapshot.enabled` / `final-cron` / `recompute-days` | `true` / `0 55 23 * * *` / `7` | |
| ★ `app.leave.status-cron` | `0 10 0 * * *` | **Yangi.** O'qituvchi ACTIVE ↔ ON_LEAVE |
| ★ `meta.enabled` | classpath `true`, **kod default `false`** | R1 — yo'q bo'lsa Meta o'chadi |
| `meta.api-version`, `page-id`, `graph-base-url`, `verify-signature`, `task-assignee-user-id`, `page-token-ttl-hours`, `duplicate-window-days`, `request-timeout-seconds` | `v21.0`, `972371302634487`, Graph URL, `true`, bo'sh, `6`, `30`, `20` | |
| `meta.system-user-token` / `app-secret` / `verify-token` | `${META_*}` | Sir — env, **default yo'q** (start yiqiladi) |
| ★ `telegram.enabled` | classpath `${TELEGRAM_ENABLED:true}`, **kod default `false`** | R1 |
| `telegram.bot-token` | `${TELEGRAM_BOT_TOKEN:}` | Sir |
| `management.endpoints.web.exposure.include` | `health,info,metrics` | |
| `logging.level.com.crm` | `DEBUG` | Prod da `INFO` tavsiya (§1.2) |

---

## 2. Tayyorgarlik (deploy kunidan oldin)

### 2.1 Sxema holati (faqat o'qiydi)
```bash
PGOPTIONS='-c default_transaction_read_only=on' \
psql -h localhost -U <user> -d adizone -X -v ON_ERROR_STOP=1 -f docs/ops/prod-schema-check.sql > schema-before.txt
```
Qaysi `V52…V63` "QISMAN"/yo'qligini yozib oling. Skriptlar idempotent — to'liq qo'llanganini qayta bajarish xavfsiz.

### 2.2 Staging repetitsiya (T − 3 kun)
1. `pg_dump -Fc` prod → staging, uploads nusxasi.
2. Stagingda §3 ni to'liq bajaring (jar, V52…V63, billing dry-run → egasiga `dry-run.xlsx`).
3. `mvn test` va `mvn test -Dspring.profiles.active=pgtest` yashil (CI yoki lokal).
4. Yangi frontend stagingga ulangan holda smoke (§4).

### 2.3 Kelishiladi
- Texnik oyna (tavsiya: yakshanba kechqurun, to'lov qabul qilinmaydigan vaqt) va egasi (billing apply tasdig'i).
- `crm.env` ga yangi sirlar (rotatsiya — [env.md](env.md)).
- `APP_PAYROLL_CUTOVER_DATE` = billing cutover sanasi (T) — kerak bo'lsa.

---

## 3. Deploy tartibi (texnik oyna)

### 3.1 Backup
```bash
sudo -u postgres pg_dump -Fc adizone > /backup/pre-v2-$(date +%F-%H%M).dump
sudo tar czf /backup/uploads-$(date +%F).tgz /opt/crm/uploads
sudo cp /opt/crm/crm-system.jar /backup/crm-system-prev.jar
sudo cp /opt/crm/application.yml /backup/application-prev.yml
sudo cp /etc/systemd/system/crm.service /backup/crm.service.prev
```
Dump'ni boshqa serverga ham ko'chiring. `pg_restore --list` bilan o'qilishini tekshiring.

### 3.2 Servisni to'xtatish
```bash
sudo systemctl stop crm
```
Frontendga "texnik ishlar" sahifasi (ixtiyoriy). Meta webhook eventlari Meta tomonda qayta yuboriladi.

### 3.2a Egalik va huquqlar (`sudo -u postgres`, §3.3 dan OLDIN)

**Prod natijasi (2026-10-02, aniq):** 0-so'rov prod'da bajarildi — `crm_user` ga tegishli bo'lmagan **yagona**
jadval `ops_tz_shift_log` edi. U 2026-10-02 da qo'lda `crm_user` ga o'tkazildi. Shuning uchun deploy kuni 0-so'rov natijasi **"0 qator"** bo'lishi kutiladi; 1-blok
himoya sifatida baribir bajariladi (`egasi o'zgartirildi: 0 obyekt` — normal). 0-so'rov qator qaytarsa — bu
02.10 dan keyin kimdir `postgres` bilan obyekt yaratgan degani: ro'yxat `owners-before.txt` da saqlanadi, 1-blok
ularni o'tkazadi, sababi alohida aniqlanadi.

**Nega:** prod'da ilova `crm_user` bilan ishlaydi; `crm_user` ga tegishli bo'lmagan obyekt (qo'lda yoki `postgres`
bilan yaratilgan) migratsiyalarni to'xtatadi. Ro'yxat 0-so'rov bilan olinadi — repetitsiya nusxasi
`pg_restore --no-owner` bilan tiklangani uchun egalarni ko'rsatmaydi.
1. `ALTER TABLE` (V52…V63 ning ko'pi) faqat jadval **egasi** bajara oladi → `crm_user` bilan bajarilsa
   `must be owner of table …` va skript to'xtaydi.
2. Skriptlarni `postgres` bilan bajarish ham yechim emas: yangi jadval va sequence'lar (`billing_periods`,
   `payment_receipt_seq`, `lesson_substitutions`, ...) `postgres` ga tegishli bo'lib qoladi → ilova `permission denied`
   oladi, `ddl-auto: update` keyin ularga ustun qo'sha olmaydi.
3. `ddl-auto: update` startda entity'lardagi yangi ustunlarni qo'shadi — bu ham egalikni talab qiladi.

Shuning uchun: hamma narsa `crm_user` ga o'tkaziladi, `btree_gist` superuser bilan bir marta o'rnatiladi,
migratsiyalar (§3.3) **`crm_user` bilan** bajariladi.

```bash
# 0) Kimga tegishli emas — ro'yxatni logga saqlang
sudo -u postgres psql -d adizone -X -v ON_ERROR_STOP=1 -c "
SELECT c.relkind, c.relname, pg_get_userbyid(c.relowner) AS owner
  FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
 WHERE ns.nspname = 'public' AND c.relkind IN ('r','p','v','m','S')
   AND pg_get_userbyid(c.relowner) <> 'crm_user' ORDER BY 1, 2;" | tee owners-before.txt

# 1) Egalikni o'tkazish (jadval bilan birga uning SERIAL/IDENTITY sequence'lari ham o'tadi)
sudo -u postgres psql -d adizone -X -v ON_ERROR_STOP=1 <<'SQL'
DO $$
DECLARE r record; n int := 0;
BEGIN
  FOR r IN
    SELECT c.relkind, c.relname
      FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
     WHERE ns.nspname = 'public' AND c.relkind IN ('r','p','v','m','S')
       AND pg_get_userbyid(c.relowner) <> 'crm_user'
       AND NOT (c.relkind = 'S' AND EXISTS (SELECT 1 FROM pg_depend d
                 WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype IN ('a','i')))
     ORDER BY c.relkind, c.relname
  LOOP
    EXECUTE format(CASE r.relkind WHEN 'S' THEN 'ALTER SEQUENCE %I OWNER TO crm_user'
                                  WHEN 'v' THEN 'ALTER VIEW %I OWNER TO crm_user'
                                  WHEN 'm' THEN 'ALTER MATERIALIZED VIEW %I OWNER TO crm_user'
                                  ELSE 'ALTER TABLE %I OWNER TO crm_user' END, r.relname);
    n := n + 1;
  END LOOP;
  RAISE NOTICE 'egasi o''zgartirildi: % obyekt', n;
END $$;
-- PostgreSQL 15+: public sxemada CREATE huquqi PUBLIC dan olingan — yangi jadvallar uchun kerak
GRANT USAGE, CREATE ON SCHEMA public TO crm_user;
-- V61 EXCLUDE uchun (crm_user o'zi o'rnata olmasligi mumkin)
CREATE EXTENSION IF NOT EXISTS btree_gist;
SQL
```
Tekshiruv: 0-so'rov yana bajarilganda bo'sh. Repetitsiyada (2026-10-02) shu blok 70 jadval + 2 mustaqil sequence
ni o'tkazdi (tranzaksiyada sinab, qaytarildi) — nusxa `--no-owner` bo'lgani (hammasi `postgres` ga tegishli) uchun;
prod'da kutilgan natija — 0 obyekt (yuqoridagi "Prod natijasi").

### 3.3 Migratsiyalar (qo'lda, tartib bilan, `crm_user` bilan)
```bash
for v in V52__billing_v2 V53__director_dashboard V54__payroll_v2 V55__payroll_v2_decisions \
         V56__legacy_constraints V57__salary_rule_overlaps V58__phase5_security \
         V59__contract_number_per_year V60__notice_target_roles \
         V61__leaves_substitutions V62__exam_fee V63__settings_contract_snapshot \
         V64__teacher_user_fk_dedupe; do
  psql -h localhost -U crm_user -d adizone -X -v ON_ERROR_STOP=1 -f src/main/resources/db/migration/$v.sql \
    2>&1 | tee -a migrate-$(date +%F).log
done
```
Repetitsiya natijasi (prod nusxasi, ikki marta): [rehearsal/REPORT.md](rehearsal/REPORT.md) — mahalliy, repoga kirmaydi.
- Har skript o'z `BEGIN/COMMIT` i bilan; xato bo'lsa to'xtaydi — sababni tuzatib, **shu skriptdan** davom eting.
- `NOTICE` larni o'qing: dublikatlar (V58/V62 UNIQUE), V61 `user_id` aniqlanmagan ta'tillar, noma'lum statuslar,
  `btree_gist` o'rnatilmagani (huquq bo'lmasa — kesishuv faqat ilovada tekshiriladi, xavfli emas).
- V58 ni V62 dan keyin qayta bajarish xavfsiz (to'liq UNIQUE qaytmaydi).
- **SALES_HEAD uchun alohida migratsiya yo'q**: `users.role` VARCHAR; Hibernate yaratgan enum CHECK ni
  `EnumCheckConstraintCleaner` startda o'chiradi (§3.5 tekshiruvi).
- V63 rekvizitlarni (`settings.center.*`) faqat yo'q bo'lsa qo'shadi.
- V64 `teachers.user_id` dagi ikkinchi FK ni (`teachers_user_id_fkey`, ON DELETE SET NULL) olib tashlaydi, V50 ning
  `fk_teachers_user` (NO ACTION) qoladi — xulq o'zgarmaydi (avval ham NO ACTION ishlagan). Repetitsiya nusxasida:
  1-o'tish `NOTICE: teachers: FK teachers_user_id_fkey [n] olib tashlandi`, 2-o'tish — NOTICE siz.
- Keyin `prod-schema-check.sql` ni qayta bajaring → `schema-after.txt`: V52…V63 "TO'LIQ", invariantlar `true` (V64 qatori ham)
  (`V61 ... EXCLUDE` ixtiyoriy).
- **V52 dan oldingi QISMAN bo'laklar** (V25–V49: 18 ta indeks yo'q — `idx_schedule_group`, `idx_leads_*`,
  `uk_leads_meta_leadgen_id`, `uk_meta_*` va h.k.) bu deployga kirmaydi; repetitsiyada ham QISMAN qoldi. Ular eski
  skriptlarni qayta bajarishni talab qiladi (V31 va V35 da takror versiyalar bor) — alohida, kelishilgan oynada.
- Repetitsiyada `INSERT 0 13` (V63 rekvizitlar) va V52/V53/V58 `UPDATE`lari (225, 98, 1, 2, 8 qator) kutilgan;
  ikkinchi marta bajarilganda **hech bir qator o'zgarmadi**.

### 3.4 Konfiguratsiya va jar
1. `/opt/crm/application-prod.yml` (§1.2) — **`app.billing.enabled: false`** bilan (billing migratsiyasi hali qo'llanmagan bo'lsa).
2. `crm.env` — yangi sirlar.
3. Unit — `--spring.config.additional-location=...` (§1.2), `sudo systemctl daemon-reload`.
4. Jar: `sudo install -m 644 -o crm crm-system.jar /opt/crm/crm-system.jar`.
5. Kataloglar: `sudo install -d -o crm /opt/crm/uploads/contracts`.
6. `sudo systemctl start crm && sudo journalctl -u crm -f`

### 3.5 Start tekshiruvlari
| Tekshiruv | Kutilgan |
|---|---|
| Log | `Could not resolve placeholder` yo'q; `Billing v2 sxemasi tayyor`; Telegram WARN yo'q (token berilgan bo'lsa) |
| `GET /api/meta/status` (SA) | Meta yoqilgan, imzo tekshiruvi on (R1) |
| `GET /actuator/health` | `UP` |
| Enum CHECK lar | `SELECT conrelid::regclass, conname FROM pg_constraint WHERE contype='c' AND pg_get_constraintdef(oid) LIKE '%= ANY %ARRAY[%';` → bo'sh |
| Login (SA) | token, `GET /api/auth/me` |
| `GET /api/settings/center` | rekvizitlar, `missing: []` |

### 3.6 O'qituvchi bog'lanishi va billing migratsiyasi
1. **O'qituvchi ↔ user:** `POST /api/admin/repair/link-teacher-users` (SA). Javobdagi bog'lanmaganlar ro'yxatini qo'lda hal qiling —
   aks holda payroll'da `TEACHER_PROFILE_MISSING`, o'qituvchi kabineti 403, ta'tillarda o'qituvchi aniqlanmaydi.
2. **Billing migratsiyasi** — to'liq tartib, himoyalar va rollback: [billing-v2-migration.md](billing-v2-migration.md):
   1. `POST /api/admin/billing/migration/dry-run?cutover=T` → `GET …/dry-run.xlsx?cutover=T` → egasi ko'radi
      (A-ro'yxatlar: `exclude`, `a14UsePayable`, `clearOverrides`).
   2. `POST …/migration/approve` (`reportHash` = shu dry-run hash'i, `approvedByOwner`).
   3. `POST …/migration/apply?runId=N&confirm=APPLY-N[&exclude=…][&clearOverrides=true]` — `app.billing.enabled=false` holatida.
   4. `GET /api/admin/billing/verify` → `ok = true`.
   5. `application-prod.yml` dan `app.billing.enabled: false` ni olib tashlang → `systemctl restart crm`.
   6. Rollback oynasi (72 soat) davomida har kuni `GET /api/admin/billing/payments-since?from=T` eksporti.
3. **Payroll cutover:** `APP_PAYROLL_CUTOVER_DATE=T` (yoki migratsiyadan avtomatik). Cutover oyidan oldingi oylar uchun oylik
   hisoblanmaydi (400 `payroll.beforeCutover`).
4. **Oylik qoidalari (repetitsiya: prod'da `salary_rules` BO'SH).** Birinchi `generate` dan oldin SA `/api/salary-rules` da
   qoidalarni kiritadi: har TEACHER (repetitsiyada 5 ta) — `fixedSalary`, `perPayingStudent`, kerak bo'lsa
   `substituteLessonRate`; oylik oladigan ADMIN/SALES_* — `fixedSalary`, `perNewStudent` (ADMIN — KPI). Qoidasiz TEACHER
   `RULE_NOT_FOUND` bilan `skipped` ga tushadi; qoidasiz ADMIN ro'yxatga umuman kirmaydi.

### 3.7 Funksional smoke (SA va har roldan bittadan)
- To'lov qabul qilish (Idempotency-Key bilan), kassa balansi, chek raqami.
- Direktor dashboardi: SA — hamma bo'lim; ACC — collections/debtors; SALES_HEAD — funnel/operators.
- Lidlar: SALES_HEAD hamma lidni ko'radi, tayinlaydi; SALES_MANAGER — faqat o'zinikini.
- Oylik: `GET /api/payroll/calculate?month&year` — `calculable`, `LEAVE_DEDUCTION`/`SUBSTITUTE_LESSONS` (agar bo'lsa).
- Ta'til: ariza → tasdiqlash (`paid`) → o'qituvchi `ON_LEAVE`.
- Imtihon: pullik imtihonga yozilish → kassada INCOME; bekor qilish → REVERSAL.
- Shartnoma: generate → `GET /api/contracts/{id}/pdf` (o'zbek/kirill harflari to'g'ri) → sign → PDF o'zgarmaydi.
- Telegram: sinov guruhida davomat (ABSENT) → ota-onaga xabar. Meta: test lid (`GET /api/meta/status`).
- Audit: `GET /api/audit-logs` da yuqoridagi amallar (oylik xulosalari bilan).

---

## 4. Rollback

| Qachon | Amal |
|---|---|
| Migratsiyalar (§3.3) yiqildi, jar hali eski | Skriptlar additive: sababni tuzatib davom eting. To'xtatish kerak bo'lsa — eski jar + `pre-v2` dump'dan tiklash |
| Yangi jar ishga tushmadi | Logdagi xatoni tuzating (ko'pincha sir yoki config). Bo'lmasa: eski jar + eski unit/yml (`/backup/*prev*`). **Eski jar V52…V63 dan keyingi sxemada ishlaydi** (ustunlar qo'shilgan, o'chirilmagan) — TAXMIN, stagingda tekshiriladi; eski kod yangi enum qiymatlarini (`CANCELLED` shartnoma, `SALES_HEAD`) o'qiy olmasligi mumkin |
| Billing apply'dan keyin, to'lov qabul qilinmagan | `pg_restore` (`pre-v2` dump) + eski jar — ma'lumot yo'qolmaydi |
| Billing apply'dan keyin, to'lovlar bor (≤ 72 soat) | [billing-v2-migration.md](billing-v2-migration.md) "Rollback": `payments-since` eksportidan to'lovlarni qayta kiritish yoki `revert-sg` (SG bo'yicha) |
| 72 soatdan keyin | Faqat oldinga tuzatish (forward fix) |

Rollback qarori va vaqtini yozib boring (kim, qachon, nima uchun).

---

## 5. Yangi frontend: crm.adizone.uz (pilot) → admin.adizone.uz

**Hozirgi holat:** `admin.adizone.uz` — **eski Vue frontend** (Vercel, `adizone-crm-front` repo). Yangi panel —
`adizone-admin`, **alohida Vercel loyihasi**, pilot domeni **`crm.adizone.uz`**. Ikkalasi bitta backendga
(`https://api.adizone.uz`) ulanadi. CORS: `SecurityConfig.ALLOWED_ORIGIN_PATTERNS` da `https://admin.adizone.uz`,
`https://crm.adizone.uz` va `https://*.vercel.app` (preview va eski loyihaning zaxira manzili) bor.

1. **Tayyorgarlik.** `adizone-admin` prod build'i `https://api.adizone.uz` bilan; Vercel'da `crm.adizone.uz` domeni
   (DNS: `CNAME crm → cname.vercel-dns.com`, SSL Vercel'da). Staging/preview da §3.7 smoke.
   API hujjatlari: `docs/design/*-api.md` (billing-v2, payroll-v2, director-dashboard, leaves-exams-contracts).
2. **Backend deploy (§3) birinchi.** Shundan keyin eski front (`admin.adizone.uz`) ishlashda davom etadi, lekin quyidagilarda
   buziladi — pilot davomida bu amallar **faqat yangi panelda** qilinadi: ta'til tasdiqlash (`paid` majburiy → 400),
   ta'tilni o'chirish (405), pullik imtihonga eski `register-student` (400), imtihon to'lovi preview'i (endi faqat narx),
   shartnoma chop etish (endi server PDF), billing ekranlari (v2 javoblari). Xodimlarga oldindan xabar beriladi.
3. **Pilot (1–2 hafta) — `crm.adizone.uz`.** Avval SA/A va buxgalter, keyin boshqa rollar. Eski `admin.adizone.uz`
   o'zgarmaydi. Xatolar kanali: brauzer konsoli + `journalctl -u crm`. Pilot mezoni: §3.7 dagi barcha oqimlar yangi
   panelda kamida bir marta real ishlatilgan, ochiq P0/P1 yo'q.
4. **Domen ko'chirish (pilotdan keyin).** Vercel'da `admin.adizone.uz` ni eski loyihadan (`adizone-crm-front`) olib,
   yangi loyihaga (`adizone-admin`) qo'shish. Ikkalasi Vercel'da — DNS yozuvi o'zgarmaydi, faqat loyihaga biriktirish
   (sertifikat bir necha daqiqada qayta chiqariladi; kam yuklamali vaqtda qiling). `crm.adizone.uz` — yangi loyihada qoladi
   yoki `301 → https://admin.adizone.uz`. Foydalanuvchilar qayta login qiladi (token brauzerda domen bo'yicha saqlanadi).
5. **Eski loyiha 2 hafta saqlanadi.** `adizone-crm-front` o'chirilmaydi, o'z `*.vercel.app` manzilida ishlab turadi
   (CORS ruxsat beradi) — zarur bo'lsa ma'lumot ko'rish uchun. 2 hafta muammosiz o'tgach arxivlanadi.
6. **Bog'liq sozlamalar.** `DIRECTOR_DASHBOARD_URL` (Telegram digest havolasi) — pilotda `https://crm.adizone.uz/...`,
   ko'chirishdan keyin `https://admin.adizone.uz/...`. Yangi `SALES_HEAD` foydalanuvchilarini SA/A yaratadi; markaz
   rekvizitlarini SA tekshiradi (`/settings/center`).
7. **Front rollback.** Domenni Vercel'da eski loyihaga qaytarish (4-band teskarisi) — backend o'zgarmaydi, lekin 2-banddagi
   mos kelmasliklar qoladi (ular uchun backend rollback'i, §4).
8. **Tozalash (eski loyiha arxivlangach).** Deprecated endpointlar (`PATCH /api/leaves/{id}/status`,
   `/api/exams/*/register-student`, `/calculate-payment`, `PATCH /api/contracts/{id}/sign`) — yangi panel ularni
   ishlatmasligi tasdiqlangach olib tashlanadi; kerak bo'lmasa `crm.adizone.uz` CORS dan ham.

---

## 6. Deploydan keyin (birinchi hafta)
- Har kuni: `journalctl -u crm --since today | grep -E 'ERROR|WARN'`, accrual job (00:10), ta'til statuslari job (00:10),
  dashboard snapshot (23:55), audit tozalash (03:30).
- Billing: `GET /api/admin/billing/verify` (har kuni 72 soat), qarzdorlar soni kutilganmi.
- Birinchi oylik: DRAFT → qo'lda tekshiruv (`calculationDetails`) → approve.
- `prod-schema-check.sql` ni bir hafta o'tgach yana bir bor.

# Deploy kuni — 2026-10-04 (yakshanba, kechqurun) — `vmi3146377`

> [deploy-v2.md](deploy-v2.md) ning **shu server uchun** aniq varianti. Umumiy sabablar, xavflar va to'liq rollback nazariyasi —
> deploy-v2.md; billing tafsilotlari — [billing-v2-migration.md](billing-v2-migration.md); frontend — `adizone-admin/docs/ops/vercel-pilot.md` §5.
>
> **Qarorlar (2026-10-03):** pilot (`crm.adizone.uz`) **yo'q** — yangi front to'g'ridan-to'g'ri `admin.adizone.uz` ga; prod'da
> ma'lumot kam, kichik xatolar deploydan keyin tuzatiladi; oylik qoidalarini deploydan keyin SA kiritadi.
>
> **Belgilar:** **[PuTTY]** — serverda root shell · **[WinSCP]** — fayl ko'chirish · **[Brauzer]** · **[PS]** — lokal PowerShell.
> Sirlar hech qayerda yozilmaydi — `<...>`. Har qadam: buyruq → **Kutilgan** → **Xato bo'lsa**.

### Server faktlari (2026-10-03 holati)

| Narsa | Qiymat |
|---|---|
| Unit | `/etc/systemd/system/crm.service`, `User=crm`, `-Dspring.config.location=/opt/crm/application.yml` (classpath yml **o'qilmaydi** — R1) |
| Loglar | stdout → `/var/log/crm/app.log`, stderr → `/var/log/crm/error.log` (**`journalctl` da ilova logi yo'q**). Prod yml da `logging.file.name` **yo'q** — log faqat systemd append orqali |
| Jar | `/opt/crm/crm-system.jar` (02.10, root:root 644); eski: `crm-system1.jar`, `crm-system_old_leads_voronka.jar` |
| Config | `/opt/crm/application.yml` — **root:root 644, sirlar ichida (JWT, DB, Meta, Telegram) — hamma o'qiy oladi** |
| Uploads | `/opt/crm/uploads` (crm:crm) |
| Java / PostgreSQL | OpenJDK 17.0.20.1 / **PostgreSQL 16.15** (repetitsiya 18.6 da edi — skriptlar PG 16 bilan mos) |
| nginx | `client_max_body_size 10m` — **2026-10-03 da qo'llandi** (§0.7) |
| Baza | `adizone_crm`, ilova foydalanuvchisi `crm_user` |
| Migratsiyalar | V52…**V65** (V65 — uy vazifalari va guruhga ko'chirish, [phase6-api.md](../design/phase6-api.md) §7) |

### Vaqt rejasi (taxminan)

| Bo'lim | Vaqt |
|---|---|
| 1–2. To'xtatish + backup | 10 daq |
| 3. Migratsiyalar | 5 daq |
| 4. Config + jar + start | 10 daq |
| 5. link-teacher-users + billing | 15–20 daq |
| 6. Vercel domen | 10 daq |
| 7. Smoke | 15 daq |
| **Jami** | **~1 soat 15 daq** (+ zaxira 45 daq) |

Billing apply **Toshkent vaqti bilan 00:00 dan oldin** tugashi ma'qul (cutover sanasi `T` — §5.4).

---

## 0. Bir kun oldin (bugun, 03.10)

### 0.1 [PuTTY] Hozirgi config'dan sir bo'lmagan qiymatlarni yozib oling
```bash
grep -nE '^\s*(url|username|maximum-pool-size|ddl-auto|port|expiration|refresh-expiration|name|enabled|api-version|page-id|verify-signature|graph-base-url|page-token-ttl-hours|duplicate-window-days|request-timeout-seconds|com\.crm):' /opt/crm/application.yml
```
**Kutilgan:** `url: jdbc:postgresql://localhost:5432/adizone_crm`, `username: crm_user`, `ddl-auto: update`, `port: <8080?>`,
`telegram.enabled`, `meta.enabled` qiymatlari. Ularni 0.4 uchun yozib qo'ying. (`password`, `secret`, `token` qatorlari chiqmaydi.)
**Xato bo'lsa:** `ddl-auto` `update` emas → deploy-v2 §1.3; to'xtab hal qiling.

### 0.2 [PuTTY] Server holati
```bash
timedatectl | grep "Time zone"
```
**Kutilgan:** `Asia/Tashkent`. **UTC bo'lsa** — [timezone.md](timezone.md) dagi tekshiruv (eski yozuvlar 5 soat orqada bo'lishi mumkin); deployni to'xtatmaydi, lekin qayd eting.
```bash
df -h / /var/lib/postgresql
```
**Kutilgan:** bo'sh joy ≥ 2 × (baza + uploads) hajmi.
```bash
grep -nE "listen|server_name|proxy_pass|client_max_body_size|Upgrade|Connection" /etc/nginx/sites-available/crm-api
```
**Kutilgan:** `proxy_pass http://127.0.0.1:<port>` (0.1 dagi `server.port` bilan bir xil), `/ws` uchun `proxy_set_header Upgrade` va `Connection "upgrade"`.
```bash
python3 --version
```
**Kutilgan:** `Python 3.12.x` (5-bo'limda JSON uchun ishlatiladi).

### 0.3 [WinSCP] Deploy katalogi va fayllar
1. [PuTTY] `install -d -m 700 -o root -g root /opt/crm/deploy /opt/crm/deploy/migration`
2. [WinSCP] lokal `adizone-crm-backend\src\main\resources\db\migration\V52__…sql … V65__…sql` (14 fayl) → `/opt/crm/deploy/migration/`
3. [WinSCP] `adizone-crm-backend\docs\ops\prod-schema-check.sql` → `/opt/crm/deploy/`
4. [PuTTY] `ls /opt/crm/deploy/migration | wc -l` → **Kutilgan:** `14`.

### 0.4 [PuTTY] `application-prod.yml` — faqat prod farqlari (sirsiz)
```bash
install -m 640 -o root -g crm /dev/null /opt/crm/application-prod.yml && nano /opt/crm/application-prod.yml
```
Tarkib (`<...>` — 0.1 dagi **eski** qiymatlar; qiymat classpath'dagi bilan bir xil bo'lsa — qatorni yozmang):
```yaml
spring:
  datasource:
    url: <0.1 dagi url>
    username: crm_user
    hikari:
      maximum-pool-size: <0.1 dagi qiymat>
server:
  port: <0.1 dagi port — 8080 bo'lsa bu blokni yozmang>
jwt:
  expiration: <0.1 — 28800000 bo'lsa yozmang>
  refresh-expiration: <0.1 — 604800000 bo'lsa yozmang>
logging:
  level:
    com.crm: INFO
telegram:
  enabled: <0.1 dagi qiymat>
meta:
  enabled: <0.1 dagi qiymat>
app:
  dashboard:
    digest:
      dashboard-url: https://admin.adizone.uz/dashboard
```
**Olib tashlangan (classpath'da bor yoki keraksiz):** `password`, `jwt.secret`, `*-token`, `app-secret` (→ `crm.env`), `driver-class-name`, `ddl-auto`,
`show-sql`, `dialect`, `format_sql`, `batch_size`, `order_*`, `spring.flyway.*` (Flyway pom'da yo'q), `meta.api-version/page-id/graph-base-url/...`
(0.1 dagi qiymat classpath'dagidan farq qilsa — qoldiring).
`app.billing.enabled` bu yerda **yo'q** — u `crm.env` da (`APP_BILLING_ENABLED`), env yml'dan ustun.

Classpath (yangi jar ichidagi) yml bilan solishtirish (0.6 dan keyin):
```bash
python3 -c "import zipfile,sys; print(zipfile.ZipFile(sys.argv[1]).read('BOOT-INF/classes/application.yml').decode())" /opt/crm/deploy/crm-system-1.0.0.jar | less
```

### 0.5 [PuTTY] `crm.env` — sirlar (faqat crm o'qiydi)
```bash
install -m 600 -o crm -g crm /dev/null /opt/crm/crm.env && nano /opt/crm/crm.env
```
```ini
DB_PASSWORD=<crm_user paroli — eski yml dan (o'zgarmaydi)>
JWT_SECRET=<YANGI: openssl rand -base64 48>
META_SYSTEM_USER_TOKEN=<YANGI — 2.4 da>
META_APP_SECRET=<YANGI — 2.4 da>
META_VERIFY_TOKEN=<YANGI: openssl rand -hex 24>
TELEGRAM_BOT_TOKEN=<YANGI — 2.4 da>
APP_BILLING_ENABLED=false
```
**Qaror (03.10): JWT, Meta va Telegram sirlari — hammasi yangilanadi** (git tarixida oshkor, env.md "Rotatsiya").
Bugun `JWT_SECRET` va `META_VERIFY_TOKEN` ni yangisini yozing (ishlab turgan eski backendga ta'sir qilmaydi). Meta token/app secret va
Telegram token esa **eski backend to'xtagandan keyin** (2.4) qayta chiqariladi — aks holda kechasi webhook va xabarlar ishlamay qoladi;
bugun ularning o'rniga vaqtincha `CHANGE_ME` yozib qo'ying.
`export` yo'q, bo'shliq yo'q; `$ " \` bo'lsa qiymatni `'...'` ga oling. `APP_PAYROLL_CUTOVER_DATE` **qo'shmang** (§9.4).
Tekshiruv (qiymatlarsiz):
```bash
sed -E 's/=.+/=<bor>/' /opt/crm/crm.env; ls -l /opt/crm/crm.env
```
**Kutilgan:** 7 kalit, hammasi `=<bor>`; `-rw------- 1 crm crm`.
**Xato bo'lsa:** `=` dan keyin bo'sh qolgan kalit startda `Could not resolve placeholder` yoki bo'sh sir beradi (Telegram bo'sh bo'lishi mumkin).
> JWT almashtirilgani uchun barcha access tokenlar yaroqsiz — refresh bilan avtomatik tiklanadi (env.md); domen ham o'zgarayotgani uchun
> foydalanuvchilar baribir qayta login qiladi.

### 0.6 [PS] Jar build (o'zingiz)
```powershell
mvn clean package -DskipTests
```
```powershell
Get-FileHash .\target\crm-system-1.0.0.jar -Algorithm SHA256
```
[WinSCP] `target\crm-system-1.0.0.jar` → `/opt/crm/deploy/`. [PuTTY]:
```bash
sha256sum /opt/crm/deploy/crm-system-1.0.0.jar
```
**Kutilgan:** hash lokal bilan bir xil. **Xato bo'lsa:** WinSCP'da "Binary" rejimda qayta yuklang.

### 0.7 Upload limiti — nginx (✅ 2026-10-03 da qo'llandi)

| Qatlam | Deploydan keyin |
|---|---|
| nginx `client_max_body_size` | **10m** (03.10 da qo'shildi) |
| Spring `multipart.max-file-size` / `max-request-size` | classpath `4MB` / `8MB` (additional-location tufayli; hozirgi prod — Spring default 1 MB) |
| Kod (`FileStorageService.MAX_BYTES`, chat, uy vazifasi fayli) | 4 MB |

Faqat tekshiruv [PuTTY]:
```bash
grep -n client_max_body_size /etc/nginx/sites-available/crm-api
```
**Kutilgan:** `client_max_body_size 10m;`. Yo'q bo'lsa — `server { … }` blokiga qo'shing, `nginx -t && systemctl reload nginx`.

### 0.8 Yangi unit (4.2 da qo'llanadi) — [PuTTY] faylni tayyorlab qo'ying
```bash
cp /etc/systemd/system/crm.service /opt/crm/deploy/crm.service.new && nano /opt/crm/deploy/crm.service.new
```
`[Service]` bo'limi:
```ini
[Service]
User=crm
WorkingDirectory=/opt/crm
EnvironmentFile=/opt/crm/crm.env
ExecStart=/usr/bin/java \
  -Xms256m -Xmx512m \
  -jar /opt/crm/crm-system.jar \
  --spring.config.additional-location=optional:file:/opt/crm/application-prod.yml
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
StandardOutput=append:/var/log/crm/app.log
StandardError=append:/var/log/crm/error.log
```
`-Dspring.config.location=...` qatori **olib tashlanadi**. `[Unit]` va `[Install]` o'zgarmaydi.
```bash
systemd-analyze verify /opt/crm/deploy/crm.service.new
```
**Kutilgan:** chiqish bo'sh (yoki faqat boshqa unitlar haqida ogohlantirish).

### 0.9 [PuTTY] Prod sxema holati (faqat o'qiydi)
```bash
read -rs PGPASSWORD && export PGPASSWORD
PGOPTIONS='-c default_transaction_read_only=on' psql -h localhost -U crm_user -d adizone_crm -X -v ON_ERROR_STOP=1 -f /opt/crm/deploy/prod-schema-check.sql > /opt/crm/deploy/schema-before.txt; unset PGPASSWORD
```
**Kutilgan:** fayl yaratildi; V52…V65 — "QISMAN"/yo'q (hali qo'llanmagan), V25–V49 — repetitsiyadagidek (18 ta indeks yo'q — deployga kirmaydi).

### 0.10 Frontend tayyorgarligi — [Brauzer]
1. `adizone-admin` Vercel loyihasi eski loyiha (`adizone-crm-front`) bilan **bitta team**da, production deployment yashil,
   `https://adizone-admin-<...>.vercel.app` da login ishlaydi (vercel-pilot §1–2).
2. **⚠️ Eski PWA service worker** — qaror (03.10): **frontend `public/sw.js` (o'zini o'chiruvchi) qo'shadi**; 6.4 tekshiruvi qoladi. Eski front `vite-plugin-pwa` (`registerType: 'autoUpdate'`) bilan
   `admin.adizone.uz` da `sw.js` o'rnatgan va butun ilovani keshlagan. Domen yangi loyihaga o'tgach `/sw.js` so'rovi yangi
   loyihada `index.html` ga rewrite bo'ladi (200, `text/html`) → SW yangilanishi yiqiladi → **qaytgan foydalanuvchilar eski
   ilovani keshdan ko'rishda davom etadi** (eski front + v2 backend = 400/405 lar). Yechim — domen ko'chirishdan **oldin**
   `adizone-admin/public/sw.js` (o'zini o'chiradigan "kill-switch") deploy qilinadi:
   ```js
   self.addEventListener('install', () => self.skipWaiting())
   self.addEventListener('activate', (event) => {
     event.waitUntil((async () => {
       for (const key of await caches.keys()) await caches.delete(key)
       await self.registration.unregister()
       for (const client of await self.clients.matchAll({ type: 'window' })) client.navigate(client.url)
     })())
   })
   ```
   Vercel statik faylni rewrite'dan oldin beradi; `Cache-Control: no-cache` — `vercel.json` dagi umumiy qoida bilan.
   Tekshiruv 6.4 da.
3. Eski loyihada **Settings → Domains → `admin.adizone.uz`** sozlamasini yozib oling; eski production deployment URL'ini
   (`https://<eski>-<hash>.vercel.app`) yozib oling (rollback, 8.6).
4. DNS'da `admin` yozuvi TTL → **300**.

### 0.11 Xabar
Xodimlarga: 04.10 kechqurun `<soat>` dan ~1–2 soat tizim ishlamaydi; keyin `admin.adizone.uz` — **yangi panel**, qayta login.
To'lov qabul qilinmaydigan vaqt bo'lsin.

---

## 1. Backup (texnik oyna shu yerda boshlanadi)

> Baza dump'i **to'xtatilgan** servisdan olinadi (dump va to'xtash orasida yozilgan to'lov rollbackda yo'qolmasin).

1.1. [PuTTY] Servisni to'xtatish:
```bash
systemctl stop crm
```
**Kutilgan:** `systemctl is-active crm` → `inactive`. **Xato bo'lsa:** `systemctl kill crm`, keyin yana `stop`.

1.2. [PuTTY] Backup katalogi:
```bash
install -d -m 700 /var/backups/crm-2026-10-04 && cd /var/backups/crm-2026-10-04
```

1.3. [PuTTY] Baza (crm_user bilan; parol so'raladi — buyruq tarixiga tushmaydi):
```bash
read -rs PGPASSWORD && export PGPASSWORD
pg_dump -h localhost -U crm_user -d adizone_crm -Fc -f adizone-pre-v2.dump && pg_restore --list adizone-pre-v2.dump | wc -l
```
**Kutilgan:** xatosiz; `pg_restore --list` — yuzlab qator. **Xato bo'lsa:** `permission denied for ...` — o'sha obyekt crm_user'ga tegishli emas
(3.1 dagi 0-so'rov bilan aniqlang) → `sudo -u postgres pg_dump -Fc adizone_crm > adizone-pre-v2.dump`.

1.4. [PuTTY] Fayllar:
```bash
tar czf uploads.tgz -C /opt/crm uploads
cp -p /opt/crm/crm-system.jar crm-system-prev.jar
cp -p /opt/crm/application.yml application-prev.yml
cp -p /etc/systemd/system/crm.service crm.service.prev
ls -lh
```
**Kutilgan:** 5 fayl, nol bo'lmagan hajm.

1.5. [WinSCP] `/var/backups/crm-2026-10-04/` ni lokal xavfsiz joyga yuklab oling (dump — shaxsiy ma'lumot; shifrlangan diskda saqlang).

---

## 2. Servis to'xtaganini tasdiqlash

2.1. [PuTTY] `ss -ltnp | grep -E ':<port>\b'` → **Kutilgan:** bo'sh (port bo'sh).
2.2. [PS] `curl.exe -s -o NUL -w "%{http_code}" https://api.adizone.uz/actuator/health` → **Kutilgan:** `502` (nginx, backend yo'q).
2.3. Meta webhook'lari shu vaqtda 502 oladi — Meta ularni keyin qayta yuboradi (harakat shart emas).

2.4. **Sirlarni qayta chiqarish** (qaror: hammasi yangilanadi; eski backend to'xtagan — hech narsa buzilmaydi). Har yangi qiymat
[PuTTY] `nano /opt/crm/crm.env` da `CHANGE_ME` o'rniga yoziladi (ekranga chiqarmang):

| # | Qayerda [Brauzer / Telegram] | Amal | `crm.env` |
|---|---|---|---|
| a | developers.facebook.com → App → Settings → Basic → **App Secret → Reset** | yangi secret | `META_APP_SECRET` |
| b | Meta Business Suite → Business Settings → System users → tegishli user → **Generate new token** (lead/page ruxsatlari), eskisini **Revoke** | yangi token | `META_SYSTEM_USER_TOKEN` |
| c | Telegram `@BotFather` → `/revoke` → bot | yangi token (eski darhol o'ladi) | `TELEGRAM_BOT_TOKEN` |
| d | — (0.5 da yozilgan) | `JWT_SECRET`, `META_VERIFY_TOKEN` yangi | — |

Tekshiruv: `grep -c CHANGE_ME /opt/crm/crm.env` → **Kutilgan:** `0`.
Webhook obunasini yangi `META_VERIFY_TOKEN` bilan qayta tasdiqlash — **4.7 dan keyin** (backend ishlab turganda):
App → Webhooks → Page → **Edit subscription** → Callback `https://api.adizone.uz/api/meta/webhook`, Verify token = yangi qiymat → **Verify and save**.
**Xato bo'lsa:** Meta "callback could not be validated" — backend ishlamayapti yoki token mos emas (`crm.env` va restart).

---

## 3. Migratsiyalar V52…V65

3.1. [PuTTY] Egalik — 0-so'rov (02.10 da yagona `ops_tz_shift_log` tuzatilgan; **"0 qator" kutiladi**, deploy-v2 §3.2a):
```bash
sudo -u postgres psql -d adizone_crm -X -At -c "SELECT c.relkind, c.relname, pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace ns ON ns.oid=c.relnamespace WHERE ns.nspname='public' AND c.relkind IN ('r','p','v','m','S') AND pg_get_userbyid(c.relowner) <> 'crm_user'" | tee /var/backups/crm-2026-10-04/owners-before.txt
```
**Kutilgan:** bo'sh. **Xato bo'lsa (qator bor):** deploy-v2 §3.2a 1-blokini (egalikni o'tkazish) bajaring, keyin davom eting.

3.2. [PuTTY] Huquq va kengaytma (postgres bilan, bir marta):
```bash
sudo -u postgres psql -d adizone_crm -X -v ON_ERROR_STOP=1 -c "GRANT USAGE, CREATE ON SCHEMA public TO crm_user" -c "CREATE EXTENSION IF NOT EXISTS btree_gist"
```
**Kutilgan:** `GRANT`, `CREATE EXTENSION` (yoki `already exists, skipping`).
**Xato bo'lsa:** `could not open extension control file` → `apt install postgresql-contrib`; bo'lmasa davom eting — V61 EXCLUDE'siz qo'yiladi
(NOTICE), kesishuv faqat ilovada tekshiriladi.

3.3. [PuTTY] Migratsiyalar (1.3 dagi `PGPASSWORD` hali eksportda):
```bash
cd /opt/crm/deploy && set -o pipefail
for v in V52__billing_v2 V53__director_dashboard V54__payroll_v2 V55__payroll_v2_decisions V56__legacy_constraints V57__salary_rule_overlaps V58__phase5_security V59__contract_number_per_year V60__notice_target_roles V61__leaves_substitutions V62__exam_fee V63__settings_contract_snapshot V64__teacher_user_fk_dedupe V65__homework_group_transfer; do
  echo "== $v"; psql -h localhost -U crm_user -d adizone_crm -X -v ON_ERROR_STOP=1 -f migration/$v.sql 2>&1 | tee -a migrate-2026-10-04.log || { echo "!!! XATO: $v"; break; }
done
```
**Kutilgan:** 13 ta `== V..` va har birida `COMMIT`; `!!! XATO` yo'q. ~5–10 soniya.
**Xato bo'lsa:** sikl to'xtaydi — xatoni o'qing (`must be owner` → 3.1; `duplicate key` / "dublikat" → V58/V62 NOTICE'ga qarang),
tuzating va **shu skriptdan** qayta boshlang (skriptlar idempotent). Tuzatib bo'lmasa — 8.1.

3.4. [PuTTY] NOTICE'larni ko'rish:
```bash
grep -E "NOTICE|ERROR|UPDATE [1-9]|INSERT 0 [1-9]" migrate-2026-10-04.log | grep -v "already exists, skipping"
```
Kutilgan (repetitsiya, 02.10 nusxa — sonlar biroz farq qilishi mumkin):

| Skript | Kutilgan |
|---|---|
| V52 | `UPDATE ~225` (`balance_transactions.effective_date`), `UPDATE ~98` (`discount_percentage` NULL → 0) |
| V53 | lid bosqichlari `funnel_step` — `UPDATE 1`, `UPDATE 2` |
| V54 | `index "uk_payroll_user_month_year" does not exist, skipping` |
| V56 | `payroll: payroll_teacher_id_month_year_key olib tashlandi`, `payroll: uk5duwev… olib tashlandi`, `payroll: CASCADE FK payroll_teacher_id_fkey olib tashlandi`, `student_groups: student_groups_student_id_group_id_join_date_key olib tashlandi` |
| V58 | `UPDATE 8` (`users.token_version`) |
| V61 | ta'tillar 0 → NOTICE yo'q; `btree_gist` yo'q bo'lsa — EXCLUDE qo'yilmadi NOTICE (3.2) |
| V63 | `relation "settings" already exists, skipping` (+ ustunlar), `INSERT 0 13` (`center.*`) |
| V64 | `teachers: FK teachers_user_id_fkey [n] olib tashlandi` |
| V65 | `ALTER TABLE` ×3, `UPDATE <n>` (eski uy vazifasi holatlari → `NOT_SUBMITTED`; yozuv yo'q bo'lsa `UPDATE 0`), `CREATE TABLE`, `CREATE INDEX` ×2. `homework_submissions: dublikat … UNIQUE qo'yilmadi` NOTICE chiqsa — yozib oling, deployni to'xtatmaydi |

Boshqa NOTICE (dublikat yozilish, aniqlanmagan ta'til, noma'lum status) — yozib oling; deployni to'xtatmaydi (prod'da ma'lumot kam).

3.5. [PuTTY] Sxema tekshiruvi:
```bash
PGOPTIONS='-c default_transaction_read_only=on' psql -h localhost -U crm_user -d adizone_crm -X -v ON_ERROR_STOP=1 -f prod-schema-check.sql > schema-after.txt; unset PGPASSWORD
grep -E "V6[0-4]|V5[2-9]" schema-after.txt | head -40
```
**Kutilgan:** V52…V65 — to'liq; 2-bo'lim invariantlari `t` (`V61 ... EXCLUDE` — ixtiyoriy); 3-bo'lim (dublikat FK) da `teachers.user_id` yo'q.

---

## 4. Config, yangi jar, start (`APP_BILLING_ENABLED=false`)

4.1. [PuTTY] Eski config'ni ishlatishdan olib tashlash (nusxasi 1.4 da; sirlari bor fayl endi ochiq turmasin):
```bash
mv /opt/crm/application.yml /var/backups/crm-2026-10-04/application-live-moved.yml
```

4.2. [PuTTY] Unit:
```bash
cp /opt/crm/deploy/crm.service.new /etc/systemd/system/crm.service && systemctl daemon-reload && systemctl cat crm | grep -E "EnvironmentFile|ExecStart|additional-location|config.location"
```
**Kutilgan:** `EnvironmentFile=/opt/crm/crm.env`, `--spring.config.additional-location=optional:file:/opt/crm/application-prod.yml`; `config.location` **yo'q**.

4.3. [PuTTY] Jar va katalog:
```bash
install -m 644 -o root -g root /opt/crm/deploy/crm-system-1.0.0.jar /opt/crm/crm-system.jar
install -d -o crm -g crm /opt/crm/uploads/contracts
sha256sum /opt/crm/crm-system.jar
```
**Kutilgan:** hash — 0.6 dagi.

4.4. [PuTTY] `grep -c APP_BILLING_ENABLED=false /opt/crm/crm.env` → **Kutilgan:** `1`.

4.5. [PuTTY] Start va log (ikkinchi PuTTY oynasida `tail` qoldiring):
```bash
systemctl start crm; tail -n 0 -F /var/log/crm/app.log /var/log/crm/error.log
```
Kutilgan qatorlar (~30–60 s):

| Qator | Ma'nosi | Bo'lmasa / boshqacha bo'lsa |
|---|---|---|
| `Could not resolve placeholder` **yo'q** | `crm.env` o'qildi | kalit nomi xato yoki `EnvironmentFile` yo'q → 0.5 / 4.2, `systemctl restart crm` |
| `Billing v2 sxemasi tayyor (sequence, billing_periods UNIQUE, idempotency_key UNIQUE)` | V52 to'liq | `Billing v2 sxemasi to'liq emas` → 3.3 da V52 qayta |
| `Enum CHECK constraint topilmadi` **yoki** `Enum CHECK tozalash yakunlandi: N ta o'chirildi` | R5 yopildi | `Enum CHECK o'chirib bo'lmadi` → egalik (3.1) |
| `lead_stages allaqachon to'ldirilgan (N ta)` | bosqichlar o'zgarmadi | `lead_stages: N ta bosqich yozildi` — qayd eting |
| `Billing ACCRUAL ... (STARTUP)` **yo'q** | billing o'chiq | bor bo'lsa → **darhol** `systemctl stop crm`, 4.4, 8.3 |
| `Telegram o'chiq: TELEGRAM_BOT_TOKEN berilmagan` **yo'q** | token bor | token bo'sh bo'lsa — kutilgan (Telegram o'chiq) |
| `Started CrmApplication in ... seconds` | tayyor | `error.log` ni o'qing; 3 urinishda bo'lmasa — 8.2 |

> `ddl-auto: update` startda yetishmagan **ustun/indeks/UNIQUE/FK** larni qo'shishi mumkin (hech narsani o'chirmaydi) — local-smoke.md 2.3.
> Hibernate SQL lari `com.crm: INFO` da ko'rinmaydi; kerak bo'lsa keyin `pg_dump --schema-only` bilan solishtiriladi.

4.6. [PuTTY] `curl -s http://127.0.0.1:<port>/actuator/health` → **Kutilgan:** `{"status":"UP"...}`.

4.7. [PS] Tashqaridan va CORS:
```powershell
curl.exe -s https://api.adizone.uz/actuator/health
```
```powershell
curl.exe -si -X OPTIONS https://api.adizone.uz/api/auth/login -H "Origin: https://admin.adizone.uz" -H "Access-Control-Request-Method: POST" | Select-String -Pattern "HTTP/|access-control-allow-origin"
```
**Kutilgan:** `UP`; `200` va `access-control-allow-origin: https://admin.adizone.uz`.

4.8. [PuTTY] Enum CHECK qoldig'i:
```bash
sudo -u postgres psql -d adizone_crm -X -At -c "SELECT conrelid::regclass, conname FROM pg_constraint WHERE contype='c' AND pg_get_constraintdef(oid) LIKE '%= ANY %ARRAY[%'"
```
**Kutilgan:** bo'sh.

---

## 5. link-teacher-users, billing migratsiyasi, billing yoqish

Hammasi **[PuTTY]** da, bitta shell sessiyada (o'zgaruvchilar saqlanadi), SUPER_ADMIN (`superadmin`) bilan.

5.1. Yordamchilar:
```bash
cd /var/backups/crm-2026-10-04; export API=http://127.0.0.1:<port>
jget() { python3 -c 'import json,sys; d=json.load(sys.stdin); d=d.get("data",d); print(eval(sys.argv[1]))' "$1"; }
```

5.2. SA token (parol so'raladi):
```bash
read -rs SA_PW && export SA_PW
TOKEN=$(python3 -c 'import json,os,urllib.request as u; r=u.Request(os.environ["API"]+"/api/auth/login", data=json.dumps({"username":"superadmin","password":os.environ["SA_PW"]}).encode(), headers={"Content-Type":"application/json"}); print(json.load(u.urlopen(r))["data"]["accessToken"])'); unset SA_PW
```
```bash
curl -s $API/api/auth/me -H "Authorization: Bearer $TOKEN" | jget 'd["username"], d["role"]'
```
**Kutilgan:** `('superadmin', 'SUPER_ADMIN')`. **Xato bo'lsa:** `401` — parol xato; `403 auth.roleNotAllowed` — rol noto'g'ri. Token 8 soat amal qiladi.

5.3. O'qituvchi ↔ user:
```bash
curl -s -X POST $API/api/admin/repair/link-teacher-users -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```
**Kutilgan:** bog'lanmaganlar ro'yxati bo'sh (repetitsiyada 5/5 bog'langan). **Xato bo'lsa:** ro'yxatdagilarni deploydan keyin SA qo'lda hal qiladi (deploy-v2 §3.6.1) — billingni to'xtatmaydi.

5.4. Cutover sanasi — qaror: **`T` = billing apply bajariladigan sana** (rejada `2026-10-04`; apply Toshkent vaqti 00:00 dan keyin bo'lsa — `2026-10-05`):
```bash
T=2026-10-04
```

5.5. Dry-run (hech narsa yozmaydi) — natija faylga (shaxsiy ma'lumot, katalog 700):
```bash
curl -s -X POST "$API/api/admin/billing/migration/dry-run?cutover=$T" -H "Authorization: Bearer $TOKEN" > dry-run.json
jget 'json.dumps(d["summary"], indent=1, ensure_ascii=False)' < dry-run.json
jget 'd["reportHash"]' < dry-run.json
```
**Kutilgan:** `sgTotal` ≈ repetitsiya (132 faol + yopiqlar), `blocking` — kam/0, `debtorsNew` va `debtNew` mantiqiy.
Anomaliyalar:
```bash
python3 -c 'import json,sys
for r in json.load(sys.stdin)["data"]["rows"]:
    if r["anomalies"]: print(r["studentGroupId"], r["studentName"], "|", r["groupName"], "|", r["category"], "|", ",".join(r["anomalies"]), "| blocking =", r["blocking"])' < dry-run.json
```
(Ixtiyoriy) egasiga xlsx: `curl -s -o dry-run.xlsx "$API/api/admin/billing/migration/dry-run.xlsx?cutover=$T" -H "Authorization: Bearer $TOKEN"` → [WinSCP] yuklab oling.
**Xato bo'lsa:** `503 billing.maintenance` emas, `5xx` — `error.log`; `migration.cutoverRequired` — `T` bo'sh.

5.6. Qaror: `approvedByOwner = "Adizov Oqilbek"`. `exclude`, `a14UsePayable`, `clearOverrides` — **5.5 dagi anomaliyalarga qarab shu yerda hal qilinadi**; standart — `exclude` yo'q, `a14UsePayable=false`, `clearOverrides=false` (bloklovchi anomaliyali SG lar baribir hold'ga tushadi). `a14UsePayable` o'zgarsa — 5.5 dry-run shu qiymat bilan qayta olinadi (hash unga bog'liq). Tasdiq:
```bash
HASH=$(jget 'd["reportHash"]' < dry-run.json)
RUN=$(curl -s -X POST $API/api/admin/billing/migration/approve -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"cutover\":\"$T\",\"a14UsePayable\":false,\"reportHash\":\"$HASH\",\"approvedByOwner\":\"Adizov Oqilbek\",\"note\":\"deploy 2026-10-04\"}" | jget 'd["id"]'); echo "runId=$RUN"
```
**Kutilgan:** `runId=<son>`. **Xato bo'lsa:** `409 migration.reportChanged` — dry-run'dan keyin ma'lumot o'zgargan (servis to'xtagan — bo'lmasligi kerak) → 5.5 dan qayta;
`400 migration.ownerRequired` — `approvedByOwner` bo'sh.

5.7. Apply (`APP_BILLING_ENABLED=false` holatida; egasi qaroriga ko'ra `&exclude=1,2` va/yoki `&clearOverrides=true` qo'shiladi):
```bash
curl -s -X POST "$API/api/admin/billing/migration/apply?runId=$RUN&confirm=APPLY-$RUN" -H "Authorization: Bearer $TOKEN" > apply.json
jget 'd["run"]["status"], len(d["migrated"]), d["held"], d["errors"]' < apply.json
```
**Kutilgan:** `('APPLIED', <son>, [<bloklovchi SG lar>], [])`. ~bir necha soniya.
**Xato bo'lsa:** `409 migration.billingEnabled` → 4.4; `APPLIED_WITH_ERRORS` — `errors` dagi SG lar hold'da qoladi, keyin `revert-sg/apply-sg` (billing-v2-migration.md) — deployni to'xtatmaydi;
butunlay noto'g'ri → 8.4.

5.8. Tekshiruv:
```bash
curl -s $API/api/admin/billing/verify -H "Authorization: Bearer $TOKEN" | jget 'json.dumps(d, indent=1)'
```
**Kutilgan:** `"ok": true`; `sgBalanceMismatch`, `studentBalanceMismatch`, `chargedPeriodsWithoutLedger`, `paymentsWithoutCash` — `[]`;
`heldEnrollments` = 5.7 dagi `held`. **Xato bo'lsa:** `ok=false` — ro'yxatdagi id larni yozing; ko'p bo'lsa 8.4, bitta-ikkita — keyin forward fix.

5.9. Billingni yoqish:
```bash
sed -i 's/^APP_BILLING_ENABLED=false$/APP_BILLING_ENABLED=true/' /opt/crm/crm.env && grep APP_BILLING_ENABLED /opt/crm/crm.env
systemctl restart crm
```
**Kutilgan (app.log):** `Billing v2 sxemasi tayyor`, `Billing ACCRUAL 2026-10-0x (STARTUP): nomzod N, davr M, xato 0`, `Started CrmApplication`.
**Xato bo'lsa:** `xato > 0` → `sudo -u postgres psql -d adizone_crm -Atc "SELECT errors FROM billing_job_runs ORDER BY id DESC LIMIT 1"`; bitta SG xatosi deployni to'xtatmaydi.

5.10. Qayta token (5.2) va 5.8 → `ok = true`.

5.11. Rollback eksporti (72 soat davomida har kuni, 9.6): `curl -s -o payments-since-$(date +%F).xlsx "$API/api/admin/billing/payments-since?from=${T}T00:00:00" -H "Authorization: Bearer $TOKEN"`.

---

## 6. Vercel: `admin.adizone.uz` → yangi loyiha

> vercel-pilot §5.2 ning pilotsiz varianti. Shart: 0.10 (bitta team, yangi loyiha yashil, **`sw.js` kill-switch deploy qilingan**).

6.1. [Brauzer] Eski loyiha (`adizone-crm-front`) → **Settings → Domains → `admin.adizone.uz` → Remove**.
6.2. [Brauzer] Darhol yangi loyiha (`adizone-admin`) → **Settings → Domains → Add → `admin.adizone.uz`** → Production.
**Kutilgan:** bir necha daqiqada **Valid Configuration** va SSL. **Xato bo'lsa:** "already in use" — 6.1 tugamagan yoki boshqa team (`TXT _vercel` yozuvini qo'shing).
6.3. [PS]
```powershell
curl.exe -sI https://admin.adizone.uz/ | Select-String -Pattern "HTTP/|cache-control|x-robots"
```
```powershell
curl.exe -sI https://admin.adizone.uz/students/list | Select-String -Pattern "HTTP/|content-type"
```
**Kutilgan:** `200` + `no-cache` + `x-robots-tag: noindex`; ikkinchisi `200 text/html` (SPA).
6.4. [PS] Eski SW'ni o'chiruvchi fayl:
```powershell
curl.exe -sI https://admin.adizone.uz/sw.js | Select-String -Pattern "HTTP/|content-type"
```
**Kutilgan:** `200`, `content-type: application/javascript`. **`text/html` bo'lsa** — kill-switch yo'q: eski PWA foydalanuvchilarida eski ilova qoladi
(ularga brauzerda sayt ma'lumotlarini tozalash kerak bo'ladi) — "Qarorlar" #1.
6.5. [Brauzer] Eski loyiha → **Settings → Git → Disconnect** (avtodeploy to'xtaydi; o'chirmang, pause qilmang — 2 hafta zaxira).
6.6. [Brauzer] Ilgari eski panel ochilgan brauzerda `https://admin.adizone.uz` → yangi panel login sahifasi (1–2 marta F5 kerak bo'lishi mumkin).

---

## 7. Qisqa smoke (`admin.adizone.uz`, SA)

[Brauzer] SA bilan login, har sahifada DevTools → Network da qizil (4xx/5xx) yo'qligini ko'ring; [PuTTY] parallel `tail -F /var/log/crm/app.log | grep -E "ERROR|WARN"`.

| # | Sahifa | Kutilgan |
|---|---|---|
| 1 | Dashboard (direktor) | barcha bo'limlar, xato yo'q |
| 2 | O'quvchilar ro'yxati → bitta o'quvchi | balans/qarz migratsiyadan keyingi qiymatlar (5.5 dagi `newDebt` bilan mos) |
| 3 | Guruhlar → bitta guruh | o'quvchilar, jadval |
| 4 | To'lovlar / kassa | ro'yxat ochiladi (**to'lov qabul qilmang** — keyingi kun birinchi real to'lovni kuzating) |
| 5 | Lidlar (kanban) | bosqichlar va lidlar |
| 6 | Sozlamalar → Markaz rekvizitlari | 13 maydon, `missing: []` |
| 7 | Oylik → hisob (oktyabr) | `estimated` belgisi; qoidasizlar `RULE_NOT_FOUND` — kutilgan (9.1) |

Qolgani (har rol) — `adizone-admin/docs/ops/smoke-checklist.md` (deploydan keyingi kunlarda).

---

## 8. Rollback

| Qaerda to'xtadi | Amal |
|---|---|
| **8.1** 3-bo'lim (migratsiya) yiqildi, tuzatib bo'lmaydi | `systemctl stop crm` (to'xtagan) → bazani 1.3 dump'dan tiklash (8.5) → eski jar/config hali joyida (4 bajarilmagan) → `systemctl start crm` |
| **8.2** 4-bo'lim: yangi jar ishga tushmayapti | Logdagi sababni tuzating (ko'pincha `crm.env` / yml). 30 daqiqada bo'lmasa: 8.5 (baza) + 8.6 (fayllar). V52…V65 additive, lekin eski jar yangi sxemada sinalmagan — **baza ham tiklanadi** |
| **8.3** Billing yoqilgan holda STARTUP accrual migratsiyadan **oldin** ishlab ketdi | `systemctl stop crm` → 8.5 (baza) → 4.4 → 4-bo'limdan qayta |
| **8.4** 5.7/5.8 dan keyin, to'lov qabul qilinmagan | `systemctl stop crm` → 8.5 → 4-bo'limdan qayta (yoki 8.6 bilan eski holatga) — ma'lumot yo'qolmaydi |
| 5.9 dan keyin, to'lovlar bor, ≤ 72 soat | billing-v2-migration.md "Rollback": `payments-since` xlsx → tiklash → to'lovlarni eski UI'da qayta kiritish; yoki SG bo'yicha `revert-sg` |
| 72 soatdan keyin | Faqat forward fix |
| **8.7** 6-bo'lim: yangi front ishlamayapti | Yangi loyiha xatosi — **Deployments → oldingi yaxshi → Promote to Production**. Domenni qaytarish (oxirgi chora) — 8.7 pastda |

**8.5 Bazani tiklash** [PuTTY] (servis to'xtagan):
```bash
sudo -u postgres psql -X -c "ALTER DATABASE adizone_crm RENAME TO adizone_crm_failed_v2"
sudo -u postgres createdb -O crm_user adizone_crm
read -rs PGPASSWORD && export PGPASSWORD
pg_restore -h localhost -U crm_user -d adizone_crm --no-owner --exit-on-error /var/backups/crm-2026-10-04/adizone-pre-v2.dump; unset PGPASSWORD
```
**Xato bo'lsa:** `permission denied to create extension` → `sudo -u postgres pg_restore -d adizone_crm --no-owner --role=crm_user /var/backups/...dump`
(fayl `postgres` o'qiy olishi uchun vaqtincha `/tmp` ga nusxalang). `_failed_v2` baza tahlil uchun qoladi — keyin `DROP DATABASE`.

**8.6 Fayllarni qaytarish** [PuTTY]:
```bash
cd /var/backups/crm-2026-10-04
cp -p crm.service.prev /etc/systemd/system/crm.service && systemctl daemon-reload
cp -p application-prev.yml /opt/crm/application.yml
cp -p crm-system-prev.jar /opt/crm/crm-system.jar
systemctl start crm && tail -n 50 -F /var/log/crm/app.log
```
`crm.env` va `application-prod.yml` qolaveradi (eski unit ularni o'qimaydi).

**8.7 Frontend domenini qaytarish** [Brauzer]: yangi loyiha → Domains → `admin.adizone.uz` → Remove; eski loyiha → Domains → Add → Production; eski loyihada Git'ni qayta ulash shart emas.
⚠️ Eski front v2 backend bilan to'liq mos emas (deploy-v2 §5.2) — backend ham qaytarilmasa, ta'til tasdiqlash/o'chirish, pullik imtihon,
shartnoma, billing ekranlari ishlamaydi.

Har rollback: kim, qachon, nima uchun — yozib qo'ying.

---

## 9. Deploydan keyin

9.1. **Oylik qoidalari** (SA, UI → *Oylik qoidalari*; yoki `POST /api/salary-rules`): prod'da `salary_rules` **bo'sh**. Har o'qituvchiga
**shaxsiy** qoida (`role: TEACHER`, `userId`, `fixedSalary`, `perPayingStudent`, kerak bo'lsa `substituteLessonRate`, `effectiveFrom: 2026-10-01`);
oylik oladigan ADMIN/SALES_* — `fixedSalary`, `perNewStudent` (ADMIN — KPI). Birinchi `generate` (oktyabr oyligi) dan **oldin**.
Qoidasiz TEACHER — `RULE_NOT_FOUND` (`skipped`), qoidasiz ADMIN ro'yxatda umuman yo'q.

9.2. **Markaz rekvizitlari** (SA, *Sozlamalar → Markaz*): V63 13 ta qiymat qo'ygan — INN, hisob raqam, MFO, direktor F.I.Sh ni egasi bilan tekshiring
(shartnoma PDF larida shular chiqadi). `GET /api/settings/center` → `missing: []`.

9.3. **Test foydalanuvchilar**: smoke uchun yaratilganlarni (masalan `smoke_*`) **o'chirmang, faolsizlantiring** — *Foydalanuvchilar → Holat → Faol emas*
(`PATCH /api/users/{id}/status`); jismoniy o'chirish FK lar tufayli yiqilishi mumkin. Test lid/vazifa/to'lov qilingan bo'lsa — bekor qiling (to'lov — 31 kun ichida).

9.4. **`APP_PAYROLL_CUTOVER_DATE`** — **qo'shmang**: berilmasa payroll cutover'i qo'llangan billing migratsiyasining cutover sanasidan olinadi
(`T` = 2026-10-04 → cutover oyi **oktyabr 2026**). Qo'lda berilsa — aynan `T` (`APP_PAYROLL_CUTOVER_DATE=2026-10-04` `crm.env` da + restart).
Natija: **sentyabr va undan oldingi oylar uchun** `calculate`, `generate`, `recalculate` → **400 `payroll.beforeCutover`** — bu ataylab: v2 hisobi
billing v2 davrlariga tayanadi, cutover'dan oldingi oylarda ular to'liq emas. Oktyabr oyligida `estimated = true` ("taxminiy" belgisi).
Qaror: sentyabr oyligi tizimda hisoblanmagan (`salary_rules` bo'sh edi) — sentyabr uchun 400 muammo emas.

9.5. **Eski config faylida sirlar**: `/var/backups/crm-2026-10-04/` (700, root) da qoladi; 2 hafta rollback oynasidan keyin
`application-prev.yml`, `application-live-moved.yml` ni o'chiring (shred). `/opt/crm/deploy` — 72 soatdan keyin o'chiring
(`dry-run.json`, `apply.json`, xlsx — shaxsiy ma'lumot).

9.6. **Birinchi 72 soat**: har kuni `payments-since` eksporti (5.11), `GET /api/admin/billing/verify`, `grep -E "ERROR|WARN" /var/log/crm/app.log`,
00:10 accrual va ta'til job'lari (`Billing ACCRUAL ... (SCHEDULED)`), 23:55 dashboard snapshot.

9.7. **Eski Vercel loyiha**: 14 kundan keyin (≈ 18.10) o'chiriladi — shu orada rollback bo'lmagan bo'lsa.

9.8. Bir hafta o'tgach `prod-schema-check.sql` (0.9) ni qayta bajaring.
9.9. **Eski jarlar** (rollback oynasi — 2 hafta, ≈ 18.10 dan keyin) [PuTTY]:
```bash
ls -l /opt/crm/*.jar
rm /opt/crm/crm-system1.jar /opt/crm/crm-system_old_leads_voronka.jar
```
`crm-system.jar` (yangi) qoladi; oldingisi `/var/backups/crm-2026-10-04/crm-system-prev.jar` da (u ham 2 haftadan keyin o'chiriladi).

---

## Qarorlar (2026-10-03)

| # | Masala | Qaror | Hujjatda |
|---|---|---|---|
| 1 | Eski PWA service worker | Frontend `public/sw.js` (o'zini o'chiruvchi) qo'shadi; tekshiruv qoladi | 0.10.2, 6.4 |
| 2 | Sirlar | **JWT, Meta (app secret, system user token, verify token), Telegram — hammasi yangilanadi**; DB paroli o'zgarmaydi | 0.5, 2.4 |
| 3 | Billing cutover `T` | Billing apply bajarilgan sana (rejada **2026-10-04**) | 5.4 |
| 4 | Sentyabr oyligi | Tizimda hisoblanmagan (`salary_rules` bo'sh edi) — sentyabr uchun 400 `payroll.beforeCutover` muammo emas | 9.4 |
| 5 | Billing tasdig'i | `approvedByOwner = "Adizov Oqilbek"`; `exclude` / `a14UsePayable` / `clearOverrides` — dry-run anomaliyalariga qarab deploy paytida, standart yo'q/`false` | 5.6 |
| 6 | `app.billing.migration-go-live` | `2026-09-18` to'g'ri (prod o'sha kuni tozalangan) — default, `application-prod.yml` ga yozilmaydi | — |
| 7 | Baza | `adizone_crm`, foydalanuvchi `crm_user` | butun hujjat |
| 8 | Loglar | Prod yml da `logging.file.name` yo'q — log faqat systemd append (`/var/log/crm/app.log`) | 0.4 |
| 9 | nginx | `client_max_body_size 10m` — 03.10.2026 da qo'llandi | 0.7 |
| 10–12 | Vercel team, SA paroli, to'lov qabul qilinmasligi | Foydalanuvchi o'zi tekshiradi | 0.10.1, 5.2, 0.11 |
| — | JVM | `-Xms256m -Xmx512m` saqlanadi | 0.8 |
| — | Eski jarlar | `crm-system1.jar`, `crm-system_old_leads_voronka.jar` — rollback oynasidan keyin o'chiriladi | 9.9 |
| — | V65 (03.10 da qo'shildi) | Uy vazifalari va guruhga ko'chirish — migratsiya sikliga kiritildi | 3.3, 3.4 |

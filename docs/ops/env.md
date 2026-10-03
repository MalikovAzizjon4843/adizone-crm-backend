# Muhit o'zgaruvchilari (sirlar)

`src/main/resources/application.yml` endi hech qanday sir saqlamaydi. Quyidagi
o'zgaruvchilar **default qiymatsiz** — bittasi berilmasa ilova startda
`Could not resolve placeholder '...'` xatosi bilan to'xtaydi. Bu ataylab:
sirsiz yoki eski sir bilan jimgina ishga tushib qolmasin.

| O'zgaruvchi | yml kaliti | Nima uchun | Bo'sh qoldirish mumkinmi |
|---|---|---|---|
| `DB_PASSWORD` | `spring.datasource.password` | PostgreSQL paroli | Yo'q |
| `JWT_SECRET` | `jwt.secret` | Access tokenlarni imzolash (HS256). **Base64** matn, kamida 32 bayt (256 bit) | Yo'q |
| `META_SYSTEM_USER_TOKEN` | `meta.system-user-token` | Graph API (lid ma'lumotini olish, formalar) | Faqat `meta.enabled: false` bo'lsa |
| `META_APP_SECRET` | `meta.app-secret` | Webhook imzosi (`X-Hub-Signature-256`) | **Yo'q** — bo'sh bo'lsa webhook imzosiz qabul qilinadi |
| `META_VERIFY_TOKEN` | `meta.verify-token` | Webhook obunasini tasdiqlash (GET qo'l berish) | Faqat `meta.enabled: false` bo'lsa |
| `TELEGRAM_BOT_TOKEN` | `telegram.bot-token` | Ota-onalarga davomat/to'lov xabarlari, direktor digest | **Ha, default bo'sh** — berilmasa (yoki e'lon qilinmasa) Telegram o'chiq ishlaydi: xabar yuborilmaydi, startda bitta WARN |
| `TELEGRAM_ENABLED` | `telegram.enabled` | Telegramni butunlay o'chirish | Ha, default `true` (token bo'lmasa baribir o'chiq) |
| `TELEGRAM_WEBHOOK_SECRET` | `telegram.webhook-secret` | Bot webhook himoyasi: Telegram har so'rovda `X-Telegram-Bot-Api-Secret-Token` sarlavhasida qaytaradi. **≥ 32 belgi, faqat `A-Z a-z 0-9 _ -`** (`openssl rand -hex 32`) | Ha, default bo'sh — lekin unda `POST /api/telegram/webhook` **har doim 401** (bot javob bermaydi) |
| `TELEGRAM_WEBHOOK_URL` | `telegram.webhook-url` | `POST /api/admin/telegram/set-webhook` Telegram'ga beradigan manzil (https) | Ha, default `https://api.adizone.uz/api/telegram/webhook` |
| `TELEGRAM_WEBAPP_URL` | `telegram.webapp-url` | Mini App manzili — bot /start dagi "Ilovani ochish" tugmasi | Ha, default `https://webapp.adizone.uz` |
| `TELEGRAM_APP_JWT_SECRET` | `telegram.app.jwt-secret` | O'quvchi/ota-ona Mini App tokenlarini imzolash (HS256). **`JWT_SECRET` dan boshqa qiymat**, kamida 32 bayt (`openssl rand -base64 48`) | Ha, default bo'sh — lekin unda `POST /api/app/auth` → 503 `app.auth.notConfigured` |

**Telegram Mini App** (docs/design/miniapp-api.md) uchun `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET`, `TELEGRAM_APP_JWT_SECRET` uchalasi kerak: bot tokeni webhook javoblari va `initData` HMAC tekshiruvi uchun ham ishlatiladi. Qo'yilgandan va V66 bajarilgandan keyin SUPER_ADMIN bir marta `POST /api/admin/telegram/set-webhook` chaqiradi (tanasiz); BotFather'da Mini App domeni `webapp.adizone.uz`.

"Bo'sh" deganda o'zgaruvchi e'lon qilinadi, lekin qiymatsiz: `META_VERIFY_TOKEN=`.
Umuman e'lon qilinmasa ilova ishga tushmaydi (`TELEGRAM_*` dan tashqari — ularda default bor).

> **2026-10-02:** `application.yml` da ochiq turgan Meta (`system-user-token`, `app-secret`, `verify-token`) va Telegram (`bot-token`) qiymatlari placeholder'ga o'tkazildi. Ular `8139927` va `d7603fb` commitlari bilan GitHub'ga chiqqan — **oshkor deb hisoblanadi, almashtirilishi shart** (quyidagi "Sirlarni almashtirish" jadvali). Prod `crm.env` da `META_*` va `TELEGRAM_BOT_TOKEN` bo'lmasa, deploydan oldin qo'shing: `META_*` siz ilova ishga tushmaydi.

Sir **bo'lmagan** sozlamalar (DB URL va foydalanuvchi, `meta.page-id`,
portlar, limitlar) `application.yml` da qoldi.

Testlar (`src/test/resources/application.yml`) bu o'zgaruvchilarni talab
qilmaydi: u H2 va o'zining test JWT kalitidan foydalanadi.

Xuddi shu testlarni lokal PostgreSQL'da yurgizish (H2 sezmaydigan xatolar uchun —
masalan `(:p IS NULL OR ...)` so'rovlari):

```bash
DB_PASSWORD=... mvn test -Dspring.profiles.active=pgtest
```

`application-pgtest.yml`: baza `adizone_test` (`PGTEST_DB_URL` bilan o'zgartiriladi),
foydalanuvchi `crm_user` (`PGTEST_DB_USER`), parol faqat `DB_PASSWORD`. Sxema
`create-drop` — har ishga tushishda jadvallar qayta yaratiladi, so'ng
`db/migration/V52…V66` (billing v2, dashboard, payroll v2, qarorlar, eski cheklovlar, qoida ustma-ustligi, phase5 xavfsizlik, shartnoma raqami, e'lon auditoriyasi, ta'til/o'rinbosar, imtihon to'lovi, rekvizitlar/shartnoma, FK dublikati, uy vazifasi/ko'chirish, Telegram Mini App) aynan o'zi bajariladi. Prod deploy tartibi — [deploy-v2.md](deploy-v2.md).
**Ishchi bazaga ulamang.**

## systemd bilan o'rnatish

1. Sir faylini yarating (faqat servis foydalanuvchisi o'qiy olsin):

   ```bash
   sudo install -m 600 -o crm -g crm /dev/null /opt/crm/crm.env
   sudo nano /opt/crm/crm.env
   ```

   Servis `root` ostida ishlasa `-o root -g root` qiling. Tekshirish:
   `ls -l /opt/crm/crm.env` → `-rw------- 1 crm crm ...`

2. Fayl tarkibi (`KALIT=qiymat`, har biri alohida qatorda, `export` va
   bo'shliqlarsiz):

   ```ini
   DB_PASSWORD=
   JWT_SECRET=
   META_SYSTEM_USER_TOKEN=
   META_APP_SECRET=
   META_VERIFY_TOKEN=
   TELEGRAM_BOT_TOKEN=
   TELEGRAM_WEBHOOK_SECRET=
   TELEGRAM_APP_JWT_SECRET=
   ```

   Qiymatda `$`, `"`, `\` yoki bo'shliq bo'lsa, butun qiymatni bitta
   qo'shtirnoqqa oling: `DB_PASSWORD='...'`.

3. Unit faylga ulang (`/etc/systemd/system/crm.service`, mavjud unitga
   faqat `EnvironmentFile` qatori qo'shiladi):

   ```ini
   [Service]
   User=crm
   EnvironmentFile=/opt/crm/crm.env
   ExecStart=/usr/bin/java -jar /opt/crm/crm-system.jar
   ```

4. Qo'llash:

   ```bash
   sudo systemctl daemon-reload
   sudo systemctl restart crm
   sudo journalctl -u crm -n 100 --no-pager
   ```

   `Could not resolve placeholder` ko'rinsa — shu nomdagi o'zgaruvchi
   faylda yo'q yoki nomi xato yozilgan.

`crm.env` ni hech qachon repoga qo'shmang (`.gitignore` da `*.env` bor).

## Rotatsiya (qayta chiqarish) — MAJBURIY

Eski qiymatlar git tarixida qoladi (`application.yml` ning oldingi
versiyalari; `jwt.secret` esa test konfiguratsiyasida ham turgan). Faylni
tozalash ularni tarixdan o'chirmaydi, shuning uchun **har bir sir yangisiga
almashtirilishi kerak**. Almashtirilgandan keyin tarixni tozalash
(`git filter-repo`) ixtiyoriy, lekin rotatsiyaning o'rnini bosmaydi.

| O'zgaruvchi | Qayerda qayta chiqariladi | Almashtirish oqibati |
|---|---|---|
| `DB_PASSWORD` | PostgreSQL: `ALTER USER <db_user> WITH PASSWORD '<yangi>';` (`psql` orqali, superuser bilan) | `crm.env` yangilanib servis qayta ishga tushiriladi. Parolni o'zgartirish va restart orasida ilova DB ga ulana olmaydi — bir buyruqda bajaring. Tavsiya: `postgres` superuser o'rniga ilova uchun alohida cheklangan foydalanuvchi |
| `JWT_SECRET` | O'zingiz yaratasiz: `openssl rand -base64 48` | Barcha amaldagi access tokenlar yaroqsiz bo'ladi. Refresh tokenlar bazada saqlanadi va JWT kalitiga bog'liq emas, shuning uchun frontend 401 da bir marta refresh qilib ishlashda davom etadi — foydalanuvchilar qayta login qilmaydi |
| `META_SYSTEM_USER_TOKEN` | Meta Business Suite → Business Settings → Users → System users → tegishli system user → **Generate new token** (lead/page ruxsatlari bilan); eskisini **Revoke** | Yangi token berilguncha lid ma'lumotlarini olish ishlamaydi; webhook eventlari navbatda qoladi va qayta uriniladi |
| `META_APP_SECRET` | developers.facebook.com → App → Settings → Basic → App Secret → **Reset** | Reset bilan `crm.env` yangilanishi orasidagi webhook eventlari imzo xatosi bilan rad etiladi — Meta ularni qayta yuboradi |
| `META_VERIFY_TOKEN` | O'zingiz yaratasiz: `openssl rand -hex 24`. So'ng App → Webhooks → Page → Edit subscription da yangi verify token bilan qayta tasdiqlang | Faqat obunani tasdiqlashda ishlatiladi; ishlab turgan webhookga ta'sir qilmaydi |
| `TELEGRAM_BOT_TOKEN` | Telegram `@BotFather` → `/revoke` → botni tanlang (yangi token beriladi) | Eski token darhol o'ladi; `crm.env` yangilanguncha xabarlar yuborilmaydi. Webhook yangi token bilan qayta o'rnatiladi (`set-webhook`). Mini App: ochiq sessiyalarning initData si yaroqsiz bo'ladi — ilovani qayta ochish kerak |
| `TELEGRAM_WEBHOOK_SECRET` | O'zingiz yaratasiz: `openssl rand -hex 32`, so'ng restart va SA `POST /api/admin/telegram/set-webhook` | Restart va set-webhook orasidagi update'lar 401 oladi — Telegram ularni qayta yuboradi |
| `TELEGRAM_APP_JWT_SECRET` | O'zingiz yaratasiz: `openssl rand -base64 48` | Barcha Mini App tokenlari yaroqsiz — ilova keyingi so'rovda o'zi qayta auth qiladi (foydalanuvchi sezmaydi) |

Rotatsiyadan keyin tekshirish:
- `GET /actuator/health` → `UP`
- login → token olinadi, `GET /api/auth/me` ishlaydi
- Meta admin sahifasidagi holat (`GET /api/meta/status`) — imzo tekshiruvi yoqilgan
- Telegram: sinov guruhida davomat belgilab, xabar kelishini ko'ring

# Meta (Facebook / Instagram) Lead Ads integratsiyasi

Instagram va Facebook reklamasidagi "Lead Ads" formalarini to'ldirgan odam
CRM ga avtomatik lid bo'lib tushadi. Operator hech narsa ko'chirmaydi.

---

## 1. Oqim

```
   Odam formani to'ldiradi
            │
            ▼
   ┌─────────────────┐
   │  Meta (Graph)   │
   └────────┬────────┘
            │  POST /api/meta/webhook           ← 20 soniya ichida javob kutadi
            │  X-Hub-Signature-256: sha256=...
            ▼
   ┌──────────────────────────────────────────┐
   │  MetaWebhookController                   │
   │   1. HMAC-SHA256 imzo tekshiruvi         │  mos kelmasa → 403
   │   2. entry[].changes[] → leadgen_id      │
   │   3. meta_webhook_events ga PENDING      │  leadgen_id UNIQUE → takror o'tadi
   │   4. 200 OK                              │  ← Graph API bu yerda CHAQIRILMAYDI
   └────────────────┬─────────────────────────┘
                    │
                    │  har 15 soniyada, 20 tadan
                    ▼
   ┌──────────────────────────────────────────┐
   │  MetaLeadProcessingService @Scheduled     │
   │   beginAttempt()  ─ REQUIRES_NEW          │  attempts++
   │   GET /{leadgen_id}  ← tranzaksiyadan     │
   │                        TASHQARIDA         │
   │   persist()       ─ REQUIRES_NEW          │
   └────────────────┬─────────────────────────┘
                    ▼
   ┌──────────────────────────────────────────┐
   │  MetaLeadIngestService                    │
   │   • forma sozlamalari                     │
   │   • field_data → mapping → maydonlar      │
   │   • variant KALIT → LABEL tarjimasi       │
   │   • dublikat: leadgen_id, keyin telefon   │
   │   • lid + izoh + (ixtiyoriy) vazifa       │
   └────────────────┬─────────────────────────┘
                    ▼
              leads (kanban: "Biriktirilmagan")
```

Backfill (`POST /api/meta/forms/{formId}/backfill`) **shu bilan bir xil**
`MetaLeadIngestService` ni chaqiradi — faqat lidlarni webhook dan emas,
`GET /{formId}/leads` dan kursor bilan oladi.

---

## 2. Sozlamalar (`application.yml`)

```yaml
meta:
  enabled: true                       # false — scheduler o'chadi, webhook baribir 200 qaytaradi
  api-version: v21.0
  page-id: "972371302634487"
  system-user-token: ""               # Business Manager → System User tokeni
  app-secret: ""                      # Meta App → Settings → Basic → App Secret
  verify-token: ""                    # O'zimiz o'ylab topamiz, Meta ga ham shuni yozamiz
  verify-signature: true              # false — imzo tekshirilmaydi, FAQAT test uchun
  graph-base-url: https://graph.facebook.com
  task-assignee-user-id:              # avtomatik vazifa kimga biriktiriladi
  page-token-ttl-hours: 6
  duplicate-window-days: 30
  request-timeout-seconds: 20
```

**Uchta sir uchta har xil narsa** — ularni aralashtirish eng ko'p
uchraydigan xato:

| Sozlama | Nima uchun | Qayerdan olinadi |
|---|---|---|
| `system-user-token` | Graph API so'rovlari | Business Manager → System Users → Generate Token |
| `app-secret` | Webhook imzosini tekshirish | App → Settings → Basic → App Secret |
| `verify-token` | Webhook ni ro'yxatdan o'tkazishdagi qo'l berish | O'zimiz o'ylaymiz |

### `verify-signature` — vaqtincha yo'l

`app-secret` hali olinmagan bo'lsa, `meta.verify-signature: false` qo'ying:
webhook imzoni tekshirmaydi va `POST /api/meta/webhook` ni qabul qilaveradi.
Bu sync va backfill ni sinab ko'rish uchun kerak.

**Bu holatda endpoint himoyasiz qoladi** — manzilini bilgan har qanday
odam bizga soxta lid yuborishi mumkin. Shuning uchun:

- imzo tekshirilmagan **har bir so'rovda** log ga warning tushadi:
  `Meta webhook imzo tekshiruvi O'CHIRILGAN — faqat test uchun`;
- `meta.app-secret` bo'sh bo'lsa ham aynan shu warning yoziladi va so'rov
  qabul qilinadi (avval bunday so'rov 403 bilan rad etilardi);
- `GET /api/meta/status` javobidagi `signatureVerified` maydoni `false`
  bo'lib turadi — sozlash sahifasi buni ko'rsatishi kerak.

`verify-signature: true` bo'lib, sir ham bor, imzo esa mos kelmasa —
403, avvalgidek.

`app-secret` olingan zahoti `verify-signature: true` ga qaytaring.

**`task-assignee-user-id` nega kerak.** Meta lidi hech kimga biriktirilmagan
holda tug'iladi (kanbanning "Biriktirilmagan" ustuni), `tasks.assigned_to`
ustuni esa `NOT NULL` — ya'ni mas'ulsiz vazifa yaratib bo'lmaydi. Bu sozlama
bo'sh qolsa `autoCreateTask` shunchaki ishlamaydi va log ga ogohlantirish
tushadi; lid esa baribir yaratiladi.

### Migratsiya

`db/migration/V49__meta_lead_ads.sql` ni **qo'lda** bajaring.

> Faylda V36 emas, V49: V36…V48 bu loyihada allaqachon band. Flyway yo'q,
> sxemani `ddl-auto: update` quradi — lekin u **UNIQUE indekslarni
> kafolatlamaydi**, butun idempotentlik esa aynan ularga tayanadi.

---

## 3. Meta tomonida nima qilish kerak

1. **App yarating** (developers.facebook.com) va unga *Webhooks* hamda
   *Facebook Login for Business* mahsulotlarini qo'shing.

2. **Ruxsatlar:** `leads_retrieval`, `pages_show_list`,
   `pages_read_engagement`, `pages_manage_metadata`,
   `business_management`. App Review dan o'tkazing.

3. **System User** yarating (Business Settings → Users → System Users),
   unga sahifani *Full control* bilan biriktiring va token generatsiya
   qiling. Tokenni `meta.system-user-token` ga yozing.

4. **Webhook ni ulang:** App → Webhooks → Page →
   `https://api.adizone.uz/api/meta/webhook`, Verify Token — sizning
   `meta.verify-token` ingiz. Meta darhol `GET` yuboradi va
   `hub.challenge` ni toza matn ko'rinishida qaytarishimizni kutadi.

5. **`leadgen` maydoniga obuna bo'ling.** Meta UI dan yoki bizdan:

   ```
   POST /api/meta/subscribe        # POST /{pageId}/subscribed_apps
   GET  /api/meta/subscribe        # joriy obuna holati
   ```

6. **Formalarni tortib oling va sozlang:**

   ```
   POST /api/meta/forms/sync
   GET  /api/meta/forms
   GET  /api/meta/forms/{formId}
   PUT  /api/meta/forms/{formId}           # leadType, bosqich, manba, vazifa
   PUT  /api/meta/forms/{formId}/mapping   # [{questionKey, crmField}]
   ```

   Yangi forma har doim `leadType = UNMAPPED`, `active = false` bo'lib
   tushadi — uni odam ko'rib chiqishi kerak. **Sinxronizatsiya operator
   qo'ygan sozlamalarni HECH QACHON ustiga yozmaydi**, faqat Meta ning
   `name`, `status`, `locale`, `leadsCount` maydonlarini yangilaydi.

7. **Eski lidlarni tortib oling** — avval sinov bilan:

   ```
   POST /api/meta/forms/{formId}/backfill?dryRun=true
   POST /api/meta/forms/{formId}/backfill?since=2026-01-01&dryRun=false
   ```

   > 776 lidli forma bor. `dryRun=true` siz birinchi chaqiruv kanbanga
   > yuzlab karta to'kadi va ularni qaytarib olish qo'lda o'chirishdan
   > iborat bo'ladi.

---

## 4. Savollar va mapping

Meta savol kalitini foydalanuvchi yozgan matndan yasaydi, shuning uchun
kalit ichida apostrof, `?`, `/` va oxirida `_` bo'ladi:

```
o'qishni_qachondan_boshlamoqchisiz?
ha_qulay_borib_o'qiy_olaman_
```

**Bu kalitlar hech qayerda normalizatsiya qilinmaydi.** `field_data` ham
AYNAN shu kalitni qaytaradi; bir tomonda tozalab, ikkinchi tomonda
tozalamasak, javob hech qachon savoliga tushmaydi. `PUT .../mapping` ga
kalitni `GET /api/meta/forms/{formId}` qaytargan holda, **xom ko'chirib**
yuboring.

### Avtomatik taxmin (faqat YANGI savol uchun)

| `questionKey` | `crmField` |
|---|---|
| `full_name` | `FULL_NAME` |
| `first_name`, `ismingiz` | `FIRST_NAME` |
| `last_name`, `familyangiz` | `LAST_NAME` |
| `phone_number` | `PHONE` |
| `telefon_raqamingiz` | `PHONE_ALT` |
| boshqa hammasi | `NOTE` |

`phone_number` **birlamchi**: u Meta ning standart savoli va raqam doim
`+998…` shaklida keladi. `telefon_raqamingiz` — qo'lda yozilgan savol,
odam unga kodsiz yoki umuman matn yozishi mumkin, shuning uchun u
**zaxira**.

Mapping bir marta qo'yilgandan keyin sinxronizatsiya unga tegmaydi.

### Variant javoblari

`field_data` variantlarda **kalitni** qaytaradi, foydalanuvchi ko'rgan
matnni emas. `meta_lead_form_questions.options_json` dagi `{kalit: label}`
lug'ati orqali tarjima qilinadi. Lug'atda topilmasa xom kalit yoziladi —
tushunarsiz bo'lsa ham, javobsiz qolgandan yaxshiroq.

---

## 5. Lid yo'qolmaydi

Bu integratsiyaning asosiy qoidasi. Hech qanday sozlama xatosi lidni
yo'qotmaydi:

| Holat | Nima bo'ladi |
|---|---|
| Forma bazada yo'q | Avtomatik sync chaqiriladi; baribir topilmasa — lid `NEW` da yaratiladi, izohga "noma'lum forma" |
| `leadType = UNMAPPED` | Lid yaratiladi, izohga "forma turi sozlanmagan" |
| `leadType = IGNORE` | Lid yaratilmaydi, event `SKIPPED` |
| `leadType = HR` | Lid yaratilmaydi, event `SKIPPED` — xom JSON `meta_webhook_events.raw_payload` da qoladi |
| `defaultStageCode` yaroqsiz | Lid `NEW` da yaratiladi, log ga warning |
| Ism kelmadi | `Meta lid 1234` (telefon oxirgi 4 raqami) |
| Telefon buzuq | Lid yaratiladi, xom qiymat izohda, `leads.phone` ga xom matn |
| Telefon umuman yo'q | Lid yaratiladi, `leads.phone = "—"` |
| Ikkita har xil telefon | Birlamchisi lidga, ikkinchisi izohga: "Qo'shimcha raqam: +998…" |

### Dublikatlar

1. **`leadgen_id` bo'yicha** — `leads.meta_leadgen_id` UNIQUE. Bor bo'lsa
   event `SKIPPED`, yangi lid yaratilmaydi.
2. **Telefon bo'yicha** — oxirgi 30 kun ichida (`duplicate-window-days`)
   shu raqam bilan **ochiq** lid bo'lsa, yangisi yaratilmaydi: mavjud lid
   lentasiga "Meta'dan takroriy murojaat" izohi qo'shiladi, event esa
   `PROCESSED` bo'lib o'sha lidga ishora qiladi.

   Yopilgan (CONVERTED / REJECTED) lid hisobga olinmaydi: yarim yil oldin
   rad etilgan odam qaytib murojaat qilsa, bu yangi ish.

---

## 6. Xatolarni tekshirish

### Qayerga qarash kerak

```
GET /api/meta/status
```

bir qarashda hamma narsani ko'rsatadi: sirlar sozlanganmi, sahifa tokeni
keshdami, oxirgi sync qachon, `PENDING` / `FAILED` eventlar soni.

```
GET  /api/meta/events?status=FAILED
GET  /api/meta/events?status=PENDING
POST /api/meta/events/{id}/retry        # attempts=0, status=PENDING
```

`error_message` ustunida Meta ning aniq xato matni turadi.

### Tez-tez uchraydigan xatolar

| Belgi | Sabab | Yechim |
|---|---|---|
| Webhook 403 qaytaradi | `app-secret` noto'g'ri | App → Settings → Basic dan qayta ko'chiring |
| Log da `imzo tekshiruvi O'CHIRILGAN` | `verify-signature: false` yoki `app-secret` bo'sh | Sirni sozlang va `verify-signature: true` ga qaytaring |
| Verifikatsiya o'tmaydi | `verify-token` mos emas | Meta UI dagi va `application.yml` dagisi bir xil bo'lsin |
| `Meta xatosi 190` | Token muddati tugagan / huquq olib tashlangan | System User ga yangi token. Kesh avtomatik tozalanadi, keyingi sikl o'zi tiklanadi. |
| `Sahifa … /me/accounts ro'yxatida yo'q` | System User ga sahifa biriktirilmagan | Business Settings → System Users → Assign Assets → Page |
| Eventlar `PENDING` da qotib qolgan | `meta.enabled: false` | Yoqing va qayta ishga tushiring |
| Lidlar kelyapti, lekin bo'sh | Mapping sozlanmagan | `GET /api/meta/forms/{formId}` da `unmappedQuestionsCount` ga qarang |
| Vazifalar yaratilmayapti | `task-assignee-user-id` bo'sh | Sozlang; log da `meta.task-assignee-user-id sozlanmagan` warning i bo'ladi |

### Loglar

```
Meta: sahifa tokeni olindi (page=…, nom='…')
Meta: Graph dan N ta forma keldi
Meta: lid #123 yaratildi (leadgen=…, forma=…, bosqich=NEW)
Meta: event #45 (leadgen=…) 3-urinish muvaffaqiyatsiz: …
Meta backfill: forma … — 3 sahifa, 287 lid o'qildi (12 yangi, 275 dublikat, …)
```

**URL lar logga to'liq yozilmaydi.** `paging.next` access_token ni query
parametrda olib keladi; `MetaGraphClient.maskUrl` uni `***` bilan
almashtiradi. So'rovlarimizda token esa umuman URL da emas —
`Authorization: Bearer` sarlavhasida.

---

## 7. Nima qayerda

| Fayl | Vazifasi |
|---|---|
| `config/MetaProperties` | `meta.*` sozlamalari |
| `service/MetaGraphClient` | Graph API ga boradigan yagona eshik, token keshi, sahifalash |
| `service/MetaFormSyncService` | Formalar va savollar sinxronizatsiyasi, avtomatik taxmin |
| `controller/MetaWebhookController` | Imzo tekshiruvi, event yozish, 200 |
| `service/MetaLeadProcessingService` | `@Scheduled` sikl |
| `service/MetaEventWorker` | Event holati, `REQUIRES_NEW` tranzaksiyalar |
| `service/MetaLeadIngestService` | **Lid yaratishning yagona joyi** — webhook ham, backfill ham shu yerga keladi |
| `service/MetaBackfillService` | Kursor bilan sahifalash va statistika |
| `service/MetaAdminService` | Sozlash endpointlari ortidagi mantiq va validatsiya |

### Nega `REQUIRES_NEW`

PostgreSQL da tranzaksiya ichidagi bitta xato seansni `25P02` ("current
transaction is aborted") holatiga tushiradi va undan keyingi **har qanday**
so'rov rad etiladi. Ya'ni sikl ichidagi `try/catch` ishlamaydi: bitta
buzuq lid butun partiyani yiqitadi. Shuning uchun har lid o'z
tranzaksiyasida, xatoni yozish esa **yana bir** yangi tranzaksiyada
bo'ladi.

`REQUIRES_NEW` faqat Spring proxysi orqali chaqirilganda ishlaydi —
shuning uchun `MetaEventWorker` va `MetaBackfillWorker` alohida bean.

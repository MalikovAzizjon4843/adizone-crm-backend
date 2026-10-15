# Kassa va ruxsatlar (1-bosqich, V86) — deploy va cut-over

Dizayn: [../design/cash-and-books.md](../design/cash-and-books.md) §0.1, §1. API: [../audit/api-inventory.md](../audit/api-inventory.md)
("Yangilanishlar (2026-10-18 …)"). Yangi muhit o'zgaruvchisi yo'q ([env.md](env.md)).

## Nima o'zgaradi (foydalanuvchilar uchun)

- **Admin va buxgalter (ACCOUNTANT)** faqat o'zi a'zo bo'lgan kassani ko'radi va unga yozadi: o'quvchi to'lovi, imtihon
  to'lovi, "boshqa" kirim, kundalik xarajat (dostavka, kanselyariya, xo'jalik mollari, boshqa mayda). Boshqa kassa va
  Direktor kassasi ko'rinmaydi (404). Kassaga biriktirilmagan admin to'lov qabul qila olmaydi —
  400 `cash.noRegisterAssigned` ("Direktordan kassa biriktirishni so'rang").
- **Faqat Direktor (SUPER_ADMIN):** kassa yaratish/tahrirlash/arxiv/o'chirish, a'zolar va razmen; kassalar orasida
  o'tkazma; yirik (STRATEGIC) xarajatlar — ijara, reklama, kommunal, jihozlar (Direktor kassasidan yoki kassasiz);
  oylik to'lash va o'quvchiga pul qaytarish (faqat Direktor kassasidan); moliya hisoboti; analitikadagi moliya bloki;
  Direktor dashboardidagi `collections` va `debtors`.
- **Kunlik topshirish:** admin kechqurun "Kassani topshirish" (naqd summa) → Direktor qabul qiladi (sanalgan naqd
  Direktor kassasiga o'tadi; farq — kamomad / ortiqcha yozuvi, jarima yo'q) yoki rad etadi. Karta, terminal, Click,
  Payme, Uzum — faqat solishtiriladi. Razmen (kassa bo'yicha, Direktor belgilaydi) kassada qoladi.
- **Moliya hisoboti** endi "boshqa" kirim, kassadan to'g'ridan chiqim va qaytarishlarni ham hisobga oladi —
  **o'tgan oylar raqamlari o'zgaradi** (buyurtmachi qabul qilgan).

## Tartib (deploy kuni, kechqurun — kassalar topshirilgandan keyin)

1. **Zaxira nusxa** (`pg_dump`) — har doimgidek.
2. **V86 — yangi jar ishga tushishidan OLDIN** (aks holda Hibernate `ddl-auto: update` NOT NULL `kind` ustunini
   to'ldirilgan jadvalga qo'sha olmaydi):
   ```bash
   psql -h <host> -U crm_user -d adizone -X -v ON_ERROR_STOP=1 \
     -f src/main/resources/db/migration/V86__cash_registers_access_categories_handover.sql
   ```
   Idempotent. Tekshirish: `docs/ops/prod-schema-check.sql` — V86 bo'lagi "TO'LIQ".
3. **Jar deploy** (deploy-v2.md dagidek). Startda `CashCategorySeeder` yetishmagan kategoriyalarni qo'shadi (V86 dan keyin —
   0 ta), `EnumCheckConstraintCleaner` — o'zgarishsiz.
4. **Cut-over** — `docs/ops/cash-cutover.sql`:
   - avval ko'rib chiqish (`PGOPTIONS='-c default_transaction_read_only=on' …`): 1b (a'zo qilinmaydigan mas'ullar),
     1c (bir nechta kassaning mas'uli), 1d (kassasiz qoladigan adminlar) — SA bilan hal qiling;
   - keyin qo'llash: moderator → a'zo, "Direktor kassasi" (bo'lmasa), eski yozuvlar → legacy topshirish.
   - **Bir marta** bajaring (qayta bajarilsa yangi topshirilmagan yozuvlar uchun yana legacy yaratadi).
5. **Kategoriya backfill** — `docs/ops/cash-category-backfill.sql`: ko'rib chiqish (1b, 1c — kassadan to'g'ridan chiqimlar
   STRATEGIC/OTHER ga tushadi, SA ko'rsin), keyin qo'llash. Hisobot raqamlari backfill'ga bog'liq emas (kod eski
   yozuvlarga xuddi shu qoidani o'zi qo'llaydi) — backfill filtr va ro'yxatlar uchun.
6. **SA sozlaydi (UI / API):** har admin kassasining a'zolari (`PUT /api/cash-registers/{id}/members`), razmen
   (`PUT /api/cash-registers/{id}` `floatAmount`), kerak bo'lsa kategoriyalar nomi (`/api/cash-categories`).
7. **Birinchi topshirish:** shu kech har admin kassani topshiradi; kutilgan naqd = `cash_balance − razmen` — eski
   qoldiq ham kiradi. Direktor naqdni sanab qabul qiladi; farq kamomad/ortiqcha sifatida qayd etiladi.

## Orqaga qaytarish

V86 faqat qo'shadi (ustun, jadval, FK); eski jar bilan ham ishlaydi (yangi ustunlar e'tiborsiz, `kind` standart
`ADMIN`). Kodni qaytarish yetarli; cut-over va backfill ma'lumotlari zararsiz qoladi. `expenses.category` FK `NOT VALID`
— eski qiymatlar tekshirilmaydi.

## Frontend (adizone-admin) — kerakli o'zgarishlar

`matrix.ts`: `financeReport` → [SA]; `cash.write` / `cash.delete` / `cash.transfer` / `cash.members` → [SA];
ACC — admin bilan bir xil. To'lov / imtihon / xarajat dialoglaridagi kassa tanlash — `GET /api/cash-registers/my`
(bitta bo'lsa avtomatik). Kassadan chiqimda kategoriya (`GET /api/cash-categories?direction=OUT`). Kassa sahifasida
"Kassani topshirish" (preview + submit), SA uchun "Topshirishlar" (qabul / rad etish). Analitikada
`finance == null` — blok yashiriladi. Batafsil: cash-and-books.md §1.6.

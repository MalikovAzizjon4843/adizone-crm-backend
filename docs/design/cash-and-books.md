# Kassa, ruxsatlar va kitoblar ombori — dizayn

Holat: **1-bosqich** (V86, 2026-10-18) va **2-bosqich** (V87, 2026-10-24) amalga oshirildi; deploy — docs/ops/cash-and-books-deploy.md. Manba: buyurtmachi talablari (2026-10-17) va F-javoblari (2026-10-18).
Ikki bosqich: **1 — kassa va ruxsatlar** (kitoblarsiz ham kerak), **2 — kitoblar** (1-bosqichga tayanadi).

Rollar: SA = SUPER_ADMIN (Direktor), A = ADMIN, ACC = ACCOUNTANT, SH = SALES_HEAD, T = TEACHER.
Fayl yo'llari: backend — `src/main/java/com/crm/...`, frontend — `adizone-admin/src/...`.

---

## 0. Buyurtmachi qarorlari (qisqa)

| # | Qaror |
|---|---|
| 1 | Alohida **Direktor kassasi** — faqat SA ko'radi. Adminlar har kech o'z kassasini topshiradi; topshirishda bugungi tushum usullar bo'yicha (naqd / karta / terminal / Click / Payme / Uzum …) aniq ko'rinadi |
| 2 | Kitob kirimi: admin faqat **sonini** kiritadi, tannarxni Direktor to'ldiradi |
| 3 | Daraja tilga bog'liq ("Turk A1", "Turk A2" …), ro'yxat **sozlanadigan** (enum emas). Har darajaga 1 ta bepul kitob |
| 4 | "Sinov kuni" = o'quvchi markazga **birinchi kelgan kun** |
| 5 | Shu kuni oylik to'liq to'lansa — chegirmali va individual o'quvchi ham bepul kitob oladi |
| 6 | Bitta sotuvda o'quv + badiiy kitob bo'lishi mumkin (bitta chek, savat) |
| 7 | Narx faqat belgilangan (sotuvda o'zgarmaydi) |
| 8 | Yetkazib beruvchiga to'lov kitob kelgandan keyin, qisman ham mumkin |
| 9 | Kam qolgan chegarasi: darslik 30, badiiy 3 (standart, har kitobda o'zgaradi) |
| 10 | "Boshqa" kirim va kassadan to'g'ridan chiqim moliya hisobotiga qo'shiladi. To'liq kirim-chiqim va moliya hisoboti — **faqat SA**. Admin faqat **o'z kassasini** ko'radi va faqat **kundalik mayda** xarajat kiritadi; yirik xarajatlar — faqat SA |
| 11 | Boshlang'ich qoldiq — soni va tannarxi bilan, bir marta |

## 0.1 Ochiq savollarga javoblar (2026-10-18)

**Buyurtmachi** javob bergan savollar:

| Savol | Javob |
|---|---|
| 1.9-#1 ACCOUNTANT | Buxgalter hozircha yo'q → ACCOUNTANT = admin kabi (faqat o'z kassasi) |
| 1.9-#2 Kassa egasi | Har bir adminga **bitta** kassa + Direktor kassasi |
| 1.9-#3 Razmen | Topshirishdan keyin kassada razmen qoladi; summani Direktor har kassa uchun belgilaydi (`float_amount`) |
| 1.9-#4 Kamomad | Faqat qayd etiladi, jarima yo'q; qarorni Direktor qiladi |
| 1.9-#6 Oylik, qaytarish | Faqat SA, faqat **Direktor kassasidan** |
| 2.6-#1 Daraja | Guruhga belgilanadi |
| 1.8 Hisobot raqamlari | O'tgan oylar raqamlari yangilanishi qabul qilindi |

**Standart** — buyurtmachi javob bermagan savollarga biz qo'ygan javoblar:

| Savol | Standart javob |
|---|---|
| 1.9-#5 Eslatma | Avtomatik eslatma hozircha **yo'q**. SA "Topshirishlar" ro'yxatida SUBMITTED larni ko'radi. Kechki eslatma — keyingi qadam |
| 1.9-#7 Limit | Kundalik xarajatga summa chegarasi **yo'q** |
| 1.9-#8 Kommunal | Kommunal (UTILITIES) — **STRATEGIC** |
| 1.9-#9 Dashboard | Direktor dashboardi: `collections` va `debtors` — **faqat SA**. ACC endi ADMIN bilan bir xil bo'limlarni ko'radi. Oyliklar ro'yxati (`/api/payroll` GET) va o'quvchi to'lovlari ro'yxati o'zgarmaydi (operatsion). Analytics → "manbalar" (`revenue`) o'zgarmaydi (marketing) |
| 1.9-#10 Tarix | Admin o'z kassasining **butun tarixini** ko'radi |
| 2.6-#2 Davomat | 2.5 dagi taklif qabul (davomat yo'q + bugun dars bor → bugun birinchi kun, `first_visit_assumed`) |
| 2.6-#3 PER_LESSON | "To'liq to'lagan" = to'lovdan keyin shu yozilmaning birinchi davrida qarz 0 (oylik bilan bir xil qoida). PER_LESSON da to'lov ≥ guruhning oylik narxi (chegirma bilan) |
| 2.6-#4 Narx | Kitob narxi va `min_stock` — faqat SA |
| 2.6-#5 Yetkazib beruvchi | Kirimda yetkazib beruvchini admin tanlaydi (faqat nomlar ro'yxati) |
| 2.6-#6 Sovg'a | Kitob omborda bo'lmasa keyin berish **yo'q** |

**Amalga oshirishda aniqlashtirilganlar** (standart):
- Kassa turi qiymatlari: `ADMIN` | `DIRECTOR` (hujjatdagi `ADMIN_DESK` o'rniga).
- "Bir admin — bitta kassa" a'zolik endpointida tekshiriladi: biriktirish adminni boshqa kassadan ko'chiradi. Bazada UNIQUE yo'q.
- Topshirishda **naqdsiz pul ko'chirilmaydi** — faqat solishtiriladi (1.2.3 dagi hisob-kitob transferi bekor). Admin kassasining naqdsiz balansi yig'ilib boraveradi; Direktor buni moliya hisobotida ko'radi.
- Kutilgan naqd = topshirish paytidagi `cash_balance − float_amount` (tarixdagi barcha naqd, cut-over qoldig'i ham).
- Kassadan qo'lda chiqimda kategoriya yuborilmasa → `OTHER_DAILY` (eski UI buzilmasin). Qo'lda kirim → `OTHER_INCOME`.
- STRATEGIC xarajat kassasiz (bank orqali) — faqat SA. Kassa ko'rsatilsa — faqat DIRECTOR kassa.
- Bir nechta DIRECTOR kassa bo'lishi mumkin. Qabulda maqsad ko'rsatilmasa — yagona faol DIRECTOR kassa; 0 ta yoki >1 ta bo'lsa → 400.
- Kassa yaratish, tahrirlash, status, o'chirish, a'zolar, razmen — faqat SA.
- `CASH_SHORTAGE` xarajatlar yig'indisiga kiradi (kategoriya sifatida ko'rinadi), `CASH_SURPLUS` — "boshqa kirim" ga.
- Kategoriyalar seed'i ilova ishga tushganda ham tekshiriladi (`CashCategorySeeder` — faqat yetishmaganini qo'shadi, o'zgartirmaydi).

---

# BOSQICH 1 — Kassa va ruxsatlar

## 1.1 Hozirgi holat (diagnoz)

**Kassa egasi — faqat yorliq, kirish nazorati yo'q**
- `CashRegister.moderator` — bitta `User` ([CashRegister.java:30-32](../../src/main/java/com/crm/entity/CashRegister.java)). U faqat yaratish/tahrirlashda yoziladi (`CashRegisterService.java:138-139, 158-159`) va DTO'da ko'rsatiladi (`:1029-1032`).
- `getAll` hamma kassani qaytaradi, filtr yo'q (`CashRegisterService.java:78-91`). `getById`, `/balance`, `/by-method`, `/transactions`, `/export` ham har qanday kassa uchun ochiq.
- Controller sinf darajasida: SA, A, ACC (`CashRegisterController.java:32`). Kassa yaratish/tahrirlash/status backend'da **ACC ga ham ochiq**, UI'da esa faqat SA, A (`matrix.ts` `cash.write`). O'chirish — SA, A (`:65`).
- Kassaga yozadigan barcha oqimlar `cashRegisterId` ni so'rovdan oladi va **ixtiyoriy kassani** qabul qiladi:
  - o'quvchi to'lovi — `PaymentController.java:93` (SA, A, ACC);
  - imtihon — `ExamController.java:60` (SA, A, ACC; T to'lov ololmaydi);
  - oylik — `PayrollController.java:117` (SA, A, ACC);
  - qaytarish — `StudentController.java:142-143` (SA, A);
  - xarajat — `ExpenseController.java:21`, `FinanceController.java:60` (SA, A, ACC);
  - kassadan kirim/chiqim — `CashRegisterController.java:149, 157`;
  - o'tkazma — `:165` (SA, A, ACC).
- "Kassani topshirish" tushunchasi **yo'q**. Faqat erkin o'tkazma (`CashRegisterService.java:735`) bor. Uning yo'nalishi nom orqali aniqlanadi: `'%(kirim)%'` yoki `related_tx_id` (`CashChannelService.java:99-101`).
- "Direktor kassasi" yoki yashirin kassa tushunchasi yo'q.

**Kategoriyalar**
- `cash_transactions` da kategoriya **yo'q** — faqat `type` va erkin `transaction_name` (`CashTransaction.java:34, 50`).
- Qo'lda kirim: frontend'dagi erkin matn `transaction_name` bo'lib saqlanadi (`CashRegisterService.java:555-576`).
- Qo'lda chiqim: kategoriyasiz, nomi `null` (`:686-710`).
- `expenses` jadvali `ExpenseCategory` enum'i bilan (SALARY, RENT, MARKETING, UTILITIES, EQUIPMENT, OTHER). Kassaga `"Xarajat: <CAT>"` nomi bilan yoziladi (`FinanceService.java:112-131`).

**Moliya hisoboti va dashboard** — hozir SA, A, ACC ko'radi
- `/api/finance/report` (`FinanceController.java:60`):
  - `totalIncome` faqat `payments` dan;
  - `totalExpenses` faqat `expenses` dan;
  - "Boshqa" kirim, kassadan to'g'ridan chiqim va o'quvchiga qaytarilgan pul **hisobotda yo'q** (`FinanceService.java:147-208`).
- `/api/analytics/overview` — SA, A (`AnalyticsController.java:29`). Moliya bloki: daromad, xarajat, oylik, imtihon, sof foyda, usullar bo'yicha.
- Direktor dashboardi bo'limlarni rolga qarab filtrlaydi (`DirectorDashboardService.java:106-116`): `collections` (summa, `cashIn`) va `debtors` — SA va ACC.
- `/api/dashboard/stats` — faqat sonlar, pul yo'q.
- Frontend: `matrix.ts:418-475` — kassalar, xarajatlar, moliya hisoboti SA, A, ACC uchun ochiq.

## 1.2 Model

### 1.2.1 Kassa turi va a'zolari

```
cash_registers
  + kind         VARCHAR(20) NOT NULL DEFAULT 'ADMIN_DESK'   -- ADMIN_DESK | DIRECTOR
  + float_amount NUMERIC(15,2) NOT NULL DEFAULT 0            -- razmen pul (ochiq savol 1.9-#3)
  moderator_id — saqlanadi ("mas'ul"), lekin kirish huquqini u emas, a'zolik belgilaydi

cash_register_members                       -- qaysi admin qaysi kassada (smena: bir kassada bir nechta admin)
  cash_register_id BIGINT FK, user_id BIGINT FK, PRIMARY KEY (cash_register_id, user_id)
  backfill: moderator_id IS NOT NULL bo'lgan har kassa uchun (id, moderator_id)
```

**Kirish qoidasi** — yagona `CashAccessService`:

| | SA | A / ACC (1.9-#1 gacha A kabi) |
|---|---|---|
| ADMIN_DESK kassa | hammasi | faqat a'zo bo'lganlari |
| DIRECTOR kassa | hammasi | yo'q: ro'yxatda ko'rinmaydi, `/{id}` → **404** (bunday kassa borligi ham sezilmasin) |

Tekshiruv nuqtalari:
1. O'qish: `getAll` (filtrlangan), `getById`, `getBalance`, `getChannelReport`, `getTransactions`, `exportTransactions`.
2. Yozish — **yagona tor nuqta**: `CashRegisterService.recordIncome / recordExpense / transfer` boshida `assertCanOperate(registerId)`. Shu bilan to'lov, imtihon, oylik, qaytarish, xarajat va keyin kitob sotuvi ham avtomatik qamraladi. Job'lar kassaga yozmaydi; tekshirildi — barcha chaqiruvlar foydalanuvchi so'rovidan keladi.

### 1.2.2 Kategoriyalar (sozlanadigan jadval, enum emas)

```
cash_categories
  code        VARCHAR(40) PRIMARY KEY
  direction   VARCHAR(3)  NOT NULL    -- IN | OUT
  name        VARCHAR(100) NOT NULL   -- UI nomi (uz)
  scope       VARCHAR(20) NOT NULL    -- SYSTEM | DAILY | STRATEGIC
  is_active   BOOLEAN NOT NULL DEFAULT TRUE
  sort_order  INT NOT NULL DEFAULT 0
```

- **SYSTEM** — faqat o'z oqimi yozadi, qo'lda tanlanmaydi.
- **DAILY** — admin o'z kassasidan.
- **STRATEGIC** — faqat SA.
- SA yangi DAILY/STRATEGIC kategoriya qo'sha oladi; SYSTEM kategoriyani o'zgartirib bo'lmaydi.

Boshlang'ich ro'yxat (V86 seed):

| code | dir | scope | nomi |
|---|---|---|---|
| STUDENT_PAYMENT | IN | SYSTEM | O'quvchi to'lovi |
| EXAM | IN | SYSTEM | Imtihon |
| BOOK_SALE | IN | SYSTEM | Kitob sotuvi (2-bosqich; O'quv/Badiiy bo'linishi — sotuv qatorlaridan) |
| OTHER_INCOME | IN | DAILY | Boshqa kirim |
| CASH_SURPLUS | IN | SYSTEM | Topshirishda ortiqcha |
| SALARY | OUT | SYSTEM | Oylik |
| REFUND | OUT | SYSTEM | O'quvchiga qaytarildi |
| BOOK_PURCHASE | OUT | SYSTEM | Kitob xaridi (2-bosqich; faqat Direktor kassasidan) |
| CASH_SHORTAGE | OUT | SYSTEM | Topshirishda kamomad |
| DELIVERY | OUT | DAILY | Dostavka |
| STATIONERY | OUT | DAILY | Kanselyariya, qog'oz |
| HOUSEHOLD | OUT | DAILY | Xo'jalik mollari |
| OTHER_DAILY | OUT | DAILY | Boshqa mayda xarajat |
| RENT | OUT | STRATEGIC | Ijara |
| MARKETING | OUT | STRATEGIC | Reklama (target) |
| UTILITIES | OUT | STRATEGIC | Kommunal (1.9-#8) |
| EQUIPMENT | OUT | STRATEGIC | Jihozlar |
| OTHER | OUT | STRATEGIC | Boshqa yirik xarajat |

- TRANSFER yozuvlari: `category = NULL`.
- REVERSAL: asl yozuvning kategoriyasi.
- `expenses.category` (hozir enum STRING): qiymatlari (SALARY … OTHER) seed'da bor, shuning uchun FK `→ cash_categories(code)` qo'shiladi. Java'da `ExpenseCategory` enum o'rniga `String code` + validatsiya.

```
cash_transactions
  + category    VARCHAR(40) NULL FK → cash_categories(code)
  + handover_id BIGINT NULL FK → cash_handovers(id)
  idx (category, transaction_date); idx (cash_register_id) WHERE handover_id IS NULL
```

**Backfill** — `category` faqat NULL bo'lsa yoziladi, birinchi mos qoida qo'llanadi:

| Shart | category |
|---|---|
| type = TRANSFER | NULL |
| type = REVERSAL | asl yozuvniki (`related_tx_id`) — oxirida, alohida UPDATE |
| `payment_id` NOT NULL | STUDENT_PAYMENT |
| `exam_registration_id` NOT NULL | EXAM |
| `payroll_id` NOT NULL | SALARY |
| EXPENSE va name = 'O''quvchiga pul qaytarildi' | REFUND |
| EXPENSE va name LIKE 'Xarajat: %' | `substring(name, 10)`, agar seed'da bo'lsa, aks holda OTHER |
| INCOME, qolgani | OTHER_INCOME |
| EXPENSE, qolgani (kassadan to'g'ridan, `name IS NULL`) | OTHER (strategik — tarix SA ga tegishli) |

Migratsiyadan oldin **dry-run SELECT** (har qoida bo'yicha soni va summasi) — `docs/ops/` ga.

### 1.2.3 Kunlik topshirish (handover)

```
cash_handovers
  id BIGSERIAL PK, uuid
  cash_register_id        BIGINT NOT NULL FK   -- ADMIN_DESK
  target_cash_register_id BIGINT NOT NULL FK   -- DIRECTOR
  business_date  DATE NOT NULL                 -- topshirilgan kun (Toshkent)
  status         VARCHAR(20) NOT NULL          -- SUBMITTED | ACCEPTED | REJECTED
  expected_cash  NUMERIC(15,2) NOT NULL        -- tizim hisobi: naqd chelak (float hisobga olingan)
  declared_cash  NUMERIC(15,2) NOT NULL        -- admin sanagani
  counted_cash   NUMERIC(15,2) NULL            -- Direktor sanagani (qabulda)
  cash_diff      NUMERIC(15,2) NULL            -- counted − expected
  submitted_by, submitted_at, accepted_by, accepted_at, reject_reason, note, diff_note
  idempotency_key VARCHAR UNIQUE
  UNIQUE (cash_register_id) WHERE status = 'SUBMITTED'   -- bir vaqtda bitta ochiq topshirish

cash_handover_lines                    -- usul bo'yicha solishtirish (aniq PaymentMethod, kanal emas)
  handover_id FK, payment_method VARCHAR(20)   -- CASH, CARD, TERMINAL, CLICK, PAYME, UZUM, OTHER
  expected_in, expected_out, expected_net NUMERIC(15,2) NOT NULL
  actual NUMERIC(15,2) NULL, diff NUMERIC(15,2) NULL     -- non-cash: Direktor terminal Z-hisobot / Click kabinetidan
  PRIMARY KEY (handover_id, payment_method)
```

CASH_AND_CARD qatorlari CASH va CARD ga bo'linadi (`cash_part` / `card_part`).

**Oqim**

1. **Ko'rish** — `GET /cash-registers/{id}/handover/preview`. Hali topshirilmagan (`handover_id IS NULL`, COMPLETED) yozuvlar bo'yicha:
   - usullar kesimida kirim / chiqim / sof;
   - kategoriyalar kesimida;
   - kun bo'yicha ro'yxat.
2. **Topshirish** (A, o'z kassasi) — `POST /cash-registers/{id}/handovers {declaredCash, note, previewHash}`, Idempotency-Key bilan.
   - Kassa qatori qulflanadi (`billingLocks.lockCashRegister`).
   - `previewHash` mos kelmasa → 409 (orada yangi to'lov tushgan).
   - Hamma topshirilmagan yozuvlarga `handover_id` yoziladi va qatorlar saqlanadi → **SUBMITTED**.
   - Shundan keyingi yangi yozuvlar keyingi topshirishga tushadi. Kassa yopilmaydi, to'lov qabul qilish to'xtamaydi.
3. **Qabul** (SA) — `POST /cash-handovers/{id}/accept {countedCash, actuals{method:sum}, diffNote}`. Bitta tranzaksiyada:
   - **Naqd:** TRANSFER `counted_cash` (CASH): admin kassasi → Direktor kassasi. Kirim qatorida `related_tx_id = chiqim qatori` (`CashChannelService.java:99-101` yo'nalishni shundan aniqlaydi). Ikkala qatorda `handover_id`.
   - **Farq:** `cash_diff ≠ 0` bo'lsa, admin kassasida SYSTEM yozuv — CASH_SHORTAGE (EXPENSE) yoki CASH_SURPLUS (INCOME). Shu bilan naqd chelak `float_amount` ga qaytadi; farq hisobotda alohida ko'rinadi.
   - **Naqdsiz:** pul jismonan yurmaydi va **ko'chirilmaydi** (0.1 standarti) — faqat solishtiriladi. `actual − expected` faqat `cash_handover_lines.diff` da qayd etiladi (odatda bu usul noto'g'ri tanlangan degani).
   - Status **ACCEPTED**.
4. **Rad etish** (SA) — `POST /cash-handovers/{id}/reject {reason}`. `handover_id` lar bo'shatiladi → admin qayta topshiradi.
5. **Kuni topshirilmagan kassa** — kechki job (20:30, Toshkent) SA ga qo'ng'iroqcha yuboradi (mavjud `UserNotification`), ixtiyoriy Telegram (1.9-#5).

**Ishga tushirish (cut-over)** — bir martalik amal:
- har ADMIN_DESK kassa uchun `legacy=true` ACCEPTED handover yaratiladi;
- shu paytgacha bo'lgan barcha yozuvlar unga bog'lanadi, aks holda birinchi topshirish butun tarixni ko'rsatardi;
- **eski qoldiq** (tizim boshidan yig'ilgan; pul real hayotda allaqachon Direktorga topshirilgan — prod preview
  2026-10-18) Direktor kassasiga legacy TRANSFER juftliklari bilan usul bo'yicha ko'chiriladi: admin kassasi naqd =
  razmen, plastik = 0. Moliya hisobotiga ta'sir yo'q. Kassa bo'yicha bir marta (kalit `cutover-v86-<id>`).
  Skript: docs/ops/cash-cutover.sql.

**Eski to'lov kirimi (standart, 2026-10-18 prod preview):** `payment_id` siz, nomi "O'quvchi to'lovi" bo'lgan INCOME —
STUDENT_PAYMENT (billing v2 dan oldingi to'lov, `payments` da bor — hisobotda `totalIncome` orqali, "boshqa kirim" emas).
Qoida bir joyda: `CashCategoryTotals` (hisobot, kassa ko'rinishi, topshirish preview'i) va backfill skripti — aynan bir xil.

`cash_handovers` ga `legacy BOOLEAN NOT NULL DEFAULT FALSE` ustuni qo'shiladi.

## 1.3 Moliya hisoboti (faqat SA) — yangi formula

Manba qoidasi: kassaga bog'liq oqimlar `cash_transactions.category` dan olinadi; kassasiz xarajatlar `expenses` dan (`cash_register_id IS NULL`). Ikki marta sanalmaydi.

| Qator | Manba |
|---|---|
| studentIncome | `payments` (hozirgidek) |
| examFees | cash EXAM (hozirgidek, `sumExamFees`) |
| otherIncome **(yangi)** | cash OTHER_INCOME + CASH_SURPLUS |
| bookSales {STUDY, FICTION} (2-bosqich) | `book_sale_lines` |
| expensesByCategory **(kengaytirildi)** | cash OUT, bundan mustasno: SALARY, REFUND, BOOK_PURCHASE, CASH_SHORTAGE (ular alohida qator) + `expenses` (`cash_register_id IS NULL`). DAILY va STRATEGIC guruhlari bilan |
| payroll | `payroll` (hozirgidek) |
| refunds **(yangi)** | cash REFUND − REVERSAL |
| cashShortage **(yangi)** | cash CASH_SHORTAGE |
| bookPurchases (2-bosqich) | `book_supplier_payments` |
| netProfit | daromadlar − xarajatlar − oylik − qaytarishlar − kamomad − kitob xaridi |

**Diqqat:** "Boshqa" kirim, kassadan chiqim va qaytarishlar endi hisobotga kiradi. **O'tgan oylar uchun raqamlar o'zgaradi** — buyurtmachi ogohlantiriladi.

## 1.4 Endpointlar

| Endpoint | Rol | O'zgarish |
|---|---|---|
| `GET /api/cash-registers` | SA hammasi; A — a'zo ADMIN_DESK | filtr |
| `GET /api/cash-registers/my` | A, SA | **yangi** — o'z kassalari (to'lov dialoglari uchun) |
| `GET /{id}`, `/balance`, `/by-method`, `/transactions`, `/export` | SA; A — o'z kassasi | `assertCanRead`; DIRECTOR → 404 |
| `GET /{id}/by-category?from&to` | SA; A — o'z kassasi | **yangi** |
| `POST`, `PUT /api/cash-registers`, `PATCH /{id}/status`, `DELETE /{id}` | **SA** | oldin SA, A, ACC / SA, A |
| `PUT /api/cash-registers/{id}/members` | SA | **yangi** |
| `POST /{id}/income` | SA; A — o'z kassasi | `category` majburiy (A: faqat OTHER_INCOME) |
| `POST /{id}/expense` | SA; A — o'z kassasi, faqat DAILY | `category` majburiy |
| `POST /transfer` | **SA** | oldin SA, A, ACC; admin uchun o'rniga topshirish |
| `GET /{id}/handover/preview`, `POST /{id}/handovers` | A (o'z), SA | **yangi** |
| `GET /api/cash-handovers?status&registerId&from&to`, `GET /{id}` | SA; A — o'zinikilar | **yangi** |
| `POST /api/cash-handovers/{id}/accept`, `/reject` | SA | **yangi** |
| `GET /api/cash-handovers/report?from&to` | SA | **yangi** — admin/kassa bo'yicha farqlar |
| `GET /api/cash-categories?direction&scope` | hamma moliya rollari (A — faqat DAILY + OTHER_INCOME) | **yangi** |
| `POST`, `PUT /api/cash-categories` | SA | **yangi** |
| `POST /api/expenses`, `/api/finance/expenses` | SA har qanday kategoriya; A — DAILY + o'z kassasi majburiy | |
| `GET /api/expenses`, `/api/finance/expenses` | SA hammasi; A — o'z kassalaridagi DAILY | |
| `GET /api/finance/report` | **SA** | oldin SA, A, ACC; 1.3 formulasi |
| `GET /api/analytics/overview` | SA, A | A uchun `finance = null` (`meta.hiddenSections` qoidasi bilan) |
| to'lov, imtihon, qaytarish | o'zgarmaydi | kassa = o'z kassasi (tor nuqtada tekshiriladi) |
| `POST /api/payroll/{id}/pay` | **SA** (taklif, 1.9-#6) | yirik xarajat |

## 1.5 Ruxsatlar: oldin → keyin

| Amal | Oldin | Keyin |
|---|---|---|
| Kassalar ro'yxati / detal / balans / tranzaksiyalar | SA, A, ACC — **hamma kassa** | SA hammasi; A — faqat a'zo bo'lgan ADMIN_DESK |
| Direktor kassasi | — | faqat SA |
| Kassa yaratish / tahrirlash / status | SA, A, ACC (UI: SA, A) | SA |
| Kassa o'chirish | SA, A | SA |
| A'zolarni biriktirish | — (moderator yorlig'i) | SA |
| Qo'lda kirim | SA, A, ACC, erkin nom | SA; A — o'z kassasi, OTHER_INCOME |
| Qo'lda chiqim | SA, A, ACC, kategoriyasiz | SA har qanday; A — o'z kassasi, faqat DAILY |
| Xarajat (`/api/expenses`) | SA, A, ACC, har qanday kategoriya | SA har qanday; A — DAILY + o'z kassasi |
| O'tkazma | SA, A, ACC | SA |
| Kassani topshirish | — | A (o'z kassasi) |
| Topshirishni qabul / rad etish | — | SA |
| O'quvchi to'lovi / imtihon to'lovi | ixtiyoriy kassa | o'z kassasi (SA — har qanday) |
| Oylik to'lash | SA, A, ACC | SA (1.9-#6) |
| O'quvchiga pul qaytarish | SA, A | SA har qanday; A — o'z kassasi (1.9-#6) |
| Moliya hisoboti | SA, A, ACC | SA |
| Analytics → moliya bloki | SA, A | SA |
| Direktor dashboard → collections / debtors | SA, ACC | o'zgarmaydi (operatsion, 1.9-#9) |
| Kassa kategoriyalarini sozlash | — | SA |

## 1.6 UI (adizone-admin)

**`matrix.ts`**
- Bo'limlar:
  - `cashRegisters` → [SA, A];
  - `expenses` → [SA, A] (A — filtrlangan);
  - `financeReport` → [SA];
  - `analytics.overview` moliya bloki → `can('finance.view')` = [SA].
- Yangi bo'limlar:
  - `cashHandovers` (`/finance/handovers`; SA — "Qabul qilish" ro'yxati, A — "Mening topshirishlarim");
  - `cashCategories` (`/settings/cash-categories`, SA).
- Amallar:
  - `cash.write` / `cash.delete` / `cash.transfer` / `cash.members` / `expense.strategic` / `cash.handover.accept` → [SA];
  - `cash.handover.submit` → [A, SA].
- Sidebar soni: SUBMITTED topshirishlar (SA).

**Ekranlar**
- **`CashRegistersPage.vue`** — A faqat o'z kassalarini ko'radi. SA uchun kassa turi belgisi ("Direktor kassasi") va a'zolar muharriri.
- **`CashRegisterPage.vue`**
  - "Topshirilmagan tushum" paneli: usullar bo'yicha jadval + kategoriyalar.
  - **"Kassani topshirish"** tugmasi (A).
  - Tranzaksiyalarda kategoriya ustuni va filtri.
  - "O'tkazma" tugmasi faqat SA ga.
- **`HandoverDialog.vue`** (A, yangi) — usul / kutilgan / sof jadvali, "Sanagan naqd" `MoneyInput`, izoh. Farq bo'lsa ogohlantiradi, lekin topshirishga ruxsat beradi.
- **`HandoversPage.vue`** + **`HandoverAcceptDialog.vue`** (SA, yangi)
  - har usul: kutilgan / haqiqiy / farq (qizil);
  - sanalgan naqd;
  - farq izohi;
  - rad etish.
- **`CashOperationDialog.vue`** — chiqimda kategoriya tanlash majburiy (A — DAILY ro'yxati, `GET /cash-categories`); kirimda OTHER_INCOME; A uchun o'tkazma rejimi yo'q.
- **`ExpenseFormDialog.vue:59`** — `EXPENSE_CATEGORIES` konstantasi o'rniga API ro'yxati; A uchun kassa majburiy.
- **`CashRegisterSelect.vue` / `useActiveCashRegisters`** (`payments/queries.ts`) — `GET /cash-registers/my`; bitta bo'lsa avtomatik tanlanadi. Bu `PaymentDialog`, `ExamPaymentDialog`, qaytarish va oylik dialoglarida bir xil ishlaydi.
- **`FinanceReportPage.vue`** — faqat SA. Yangi qatorlar: otherIncome, refunds, cashShortage; xarajatlar DAILY/STRATEGIC guruhida.
- **`AnalyticsOverviewPage.vue`** — `finance == null` bo'lsa blok ko'rinmaydi.
- **Sozlamalar → Kassa kategoriyalari** (SA, yangi).

## 1.7 Migratsiya rejasi — V86 (qo'lda, idempotent, V85 uslubida)

1. `cash_registers`: `kind`, `float_amount` qo'shiladi; `cash_register_members` yaratiladi va `moderator_id` dan to'ldiriladi.
2. `INSERT` "Direktor kassasi" (kind = DIRECTOR), agar DIRECTOR kassa yo'q bo'lsa.
3. `cash_categories` + seed (`ON CONFLICT DO NOTHING`).
4. `cash_transactions`: `category`, `handover_id` + indekslar; backfill (1.2.2), keyin REVERSAL uchun alohida UPDATE.
5. `expenses.category` → FK `cash_categories(code)` (`DO $$ IF NOT EXISTS`).
6. `cash_handovers`, `cash_handover_lines` + partial unique indeks.
7. Cut-over: `legacy` handover'lar + eski yozuvlarni bog'lash — **alohida skript** (`docs/ops/`), deploy kuni kechqurun.
8. `docs/ops/prod-schema-check.sql` ga V86 bo'lagi.

`ddl-auto: update` va `drop-enum-checks: true` (`application.yml:32, 69`) — yangi ustunlar uchun xavf yo'q; FK va seed esa faqat V86 da.

## 1.8 Xavflar

- **To'lov oqimlari sinishi:** admin endi boshqa kassani tanlay olmaydi. Hamma dialoglarda `/my` va avtomatik tanlash bir vaqtda chiqishi shart. A'zosiz admin → aniq xato `cash.noRegisterAssigned`.
- **Hisobot raqamlari o'zgaradi** (1.3) — o'tgan oylar ham.
- **Backfill noaniqligi:** eski kassadan chiqimlar (`name IS NULL`) STRATEGIC/OTHER ga tushadi. Dry-run'ni SA ko'rib chiqadi.
- **Topshirish bilan parallel to'lov:** qulf + `previewHash` 409 bilan hal qilinadi. Topshirishdan keyin tushgan to'lov keyingi topshirishga o'tadi.
- **Naqdsiz "hisob-kitob transferi"** Direktor kassasi kanal hisobotida ONLINE / TERMINAL kirim sifatida ko'rinadi — bu to'g'ri, lekin SA ga tushuntirish kerak.
- **ACC roli** aniqlanmaguncha A kabi cheklanadi — buxgalter hisobotni yo'qotadi (1.9-#1).
- **SA o'tgan sanaga yozuv kiritishi** (`PaymentDatePolicy`) allaqachon topshirilgan kunga tushishi mumkin. U `handover_id IS NULL` bo'lib keyingi topshirishga qo'shiladi; preview'da "o'tgan sana" belgisi bilan ko'rsatiladi.

## 1.9 Ochiq savollar (1-bosqich)

1. ACCOUNTANT roli: Direktor kabi hammasini ko'radimi yoki admin kabi faqat o'z kassasini?
2. Bir kassada bir nechta admin (smena) bo'ladimi? Bir admin bir nechta kassada ishlaydimi? (Model ikkalasini ham qo'llaydi.)
3. Kassada razmen pul (float) qoladimi, qancha?
4. Kamomad avtomatik admin jarimasiga (BonusPenalty PENALTY) aylanadimi yoki faqat qayd etiladimi? Ortiqcha pul-chi?
5. Topshirilmagan kun haqida eslatma: qo'ng'iroqcha yetadimi yoki Telegram ham kerakmi, soat nechada?
6. Oylik to'lash va o'quvchiga pul qaytarish — faqat Direktormi?
7. Admin kundalik xarajatiga summa chegarasi kerakmi (bitta yozuv yoki kunlik)?
8. Kategoriyalar ro'yxatini tasdiqlash. Kommunal — kundalikmi yoki strategikmi?
9. Direktor dashboardidagi to'lov yig'imi va qarzdorlar summalari ACC (va A) ga qoladimi? Oyliklar ro'yxati (`/payroll`, hozir SA, A, ACC) yashirilsinmi?
10. Admin o'z kassasining o'tgan kunlar tarixini ko'radimi yoki faqat topshirilmaganlarni?

---

# BOSQICH 2 — Kitoblar

## 2.1 Model

```
study_levels                                   -- sozlanadigan daraja (enum emas)
  id BIGSERIAL PK
  language   VARCHAR(50)  NOT NULL             -- "Turk"
  code       VARCHAR(20)  NOT NULL             -- "A1"
  name       VARCHAR(80)  NOT NULL             -- "Turk A1"
  sort_order INT NOT NULL DEFAULT 0
  is_active  BOOLEAN NOT NULL DEFAULT TRUE
  gift_book_item_id BIGINT NULL FK → book_items(id)   -- har darajaga 1 ta bepul kitob
  UNIQUE (lower(language), lower(code))

groups  + level_id BIGINT NULL FK → study_levels      -- haqiqiy daraja (2.6-#1)
courses + level_id BIGINT NULL FK → study_levels      -- yangi guruh uchun standart

book_items
  id, uuid
  kind        VARCHAR(20) NOT NULL       -- STUDY | FICTION
  title       VARCHAR(200) NOT NULL, author VARCHAR(150) NULL
  level_id    BIGINT NULL FK → study_levels
              CHECK ((kind = 'STUDY') = (level_id IS NOT NULL))
  sale_price  NUMERIC(12,2) NOT NULL CHECK (sale_price >= 0)
  stock_qty   INT NOT NULL DEFAULT 0 CHECK (stock_qty >= 0)
  avg_cost    NUMERIC(12,2) NULL         -- faqat SA (o'rtacha tortilgan)
  min_stock   INT NOT NULL               -- standart: STUDY 30, FICTION 3 (settings: books.lowStock.study / .fiction)
  is_active, created_at, updated_at
  UNIQUE (lower(title), kind, COALESCE(level_id, 0))

book_suppliers  (id, name UNIQUE, phone, note, is_active, created_at)

book_receipts                               -- kirim hujjati
  id, uuid, supplier_id FK NOT NULL, receipt_date DATE NOT NULL
  status       VARCHAR(20) NOT NULL         -- COST_PENDING | UNPAID | PARTIAL | PAID
  total_cost   NUMERIC(14,2) NULL           -- SA to'ldirgach
  paid_amount  NUMERIC(14,2) NOT NULL DEFAULT 0
  CHECK (total_cost IS NULL OR paid_amount <= total_cost)
  created_by, cost_filled_by, cost_filled_at, note
book_receipt_lines (id, receipt_id FK, book_item_id FK, qty INT CHECK (qty > 0), unit_cost NUMERIC(12,2) NULL CHECK (unit_cost >= 0))

book_supplier_payments
  id, uuid, receipt_id FK NOT NULL, supplier_id FK NOT NULL
  amount NUMERIC(14,2) NOT NULL CHECK (amount > 0)
  cash_register_id FK NOT NULL                -- faqat kind = DIRECTOR (servis tekshiradi)
  payment_method, cash_part, card_part, cash_transaction_id, paid_at DATE
  status VARCHAR(20) NOT NULL                 -- PAID | CANCELLED (SA, REVERSAL bilan)
  idempotency_key UNIQUE, created_by

book_sales                                    -- bitta chek, aralash savat
  id, uuid, receipt_number (ReceiptNumberService)
  student_id NULL FK, buyer_name VARCHAR(150) NULL, buyer_phone VARCHAR(30) NULL
  CHECK (student_id IS NOT NULL OR buyer_name IS NOT NULL)
  total_amount NUMERIC(14,2) NOT NULL
  cash_register_id FK NOT NULL, payment_method, cash_part, card_part, cash_transaction_id
  sale_date DATE NOT NULL, idempotency_key UNIQUE, created_by, created_at
book_sale_lines
  sale_id FK, book_item_id FK, kind VARCHAR(20) NOT NULL   -- nusxa (hisobot uchun)
  qty INT CHECK (qty > 0), unit_price NUMERIC(12,2) NOT NULL   -- book_items.sale_price nusxasi
  unit_cost NUMERIC(12,2) NULL                                 -- avg_cost nusxasi, faqat SA
  line_total NUMERIC(14,2) NOT NULL

book_gifts
  id, student_id BIGINT NOT NULL UNIQUE        -- bir o'quvchiga bitta
  student_group_id FK, payment_id FK, level_id FK, book_item_id FK
  first_visit_date DATE NOT NULL, unit_cost NUMERIC(12,2) NULL, gift_date DATE NOT NULL, created_by

book_stock_movements                           -- ombor daftari (append-only)
  id, book_item_id FK
  type VARCHAR(20) NOT NULL                    -- OPENING | RECEIPT | SALE | GIFT | ADJUSTMENT
  qty_delta INT NOT NULL CHECK (qty_delta <> 0), qty_after INT NOT NULL CHECK (qty_after >= 0)
  unit_cost NUMERIC(12,2) NULL, ref_id BIGINT NULL, reason TEXT NULL
  created_by, created_at
  idx (book_item_id, created_at)

cash_transactions + book_sale_id BIGINT NULL, + book_supplier_payment_id BIGINT NULL
```

**Parallel ishlash**
- `BillingLocks.Plan.bookItems(ids)` qo'shiladi.
- Qulf tartibi: Student → BookItem (id o'sish tartibida) → CashRegister.
- Qoldiq qulf ostida tekshiriladi. Bazadagi `CHECK stock_qty >= 0` — oxirgi himoya.

## 2.2 Oqimlar va Direktor kassasiga bog'lanish

**Kirim** (A yoki SA)
- Admin yetkazib beruvchi + qatorlar (kitob, soni) kiritadi → `COST_PENDING`.
- Ombor **darhol** ko'payadi (RECEIPT, `unit_cost = NULL`).
- Admin javobida narx yoki tannarx maydonlari umuman yo'q.

**Tannarx to'ldirish** (SA)
- `PUT /receipts/{id}/costs` → qator `unit_cost` lari, `total_cost`, `avg_cost` qayta hisoblanadi, status `UNPAID`.
- Faqat shundan keyin yetkazib beruvchi qarzi hisoblanadi.

**Yetkazib beruvchiga to'lov** (SA)
- Faqat **DIRECTOR** turdagi kassadan.
- `amount ≤ total_cost − paid_amount`; qisman to'lov mumkin.
- Kassa EXPENSE, kategoriya BOOK_PURCHASE, `book_supplier_payment_id` bilan. Status PARTIAL yoki PAID.
- Admin bu yozuvni hech qayerda ko'rmaydi: Direktor kassasi unga yopiq, hisobot faqat SA ga.

**Sotuv** (A — o'z kassasi, SA)
- So'rovda faqat `{bookItemId, qty}[]` + xaridor + kassa + usul. **Narx so'rovda yo'q** — server `sale_price` ni oladi.
- `expectedTotal` UI'dagi jami bilan solishtiriladi; narx o'zgargan bo'lsa → 409.
- Bitta kassa INCOME, kategoriya BOOK_SALE, `book_sale_id` bilan. Kechqurun topshirishga qo'shiladi.
- O'quv / Badiiy bo'linishi `book_sale_lines.kind` dan: kassaning `by-category` hisobotida BOOK_SALE → "O'quv kitob" / "Badiiy kitob" bo'lib ko'rsatiladi. Topshirish usul bo'yicha bo'lgani uchun bo'linish unga xalaqit bermaydi.
- O'quvchi ledgeri yoki balansiga tegmaydi. Qaytarish yo'q.

**Bepul kitob** (A, SA) — `GET /books/gifts/eligibility?studentId`. Shart (`today` = Toshkent sanasi):
1. `firstVisitDate(student) = MIN(attendance.date)`, bunda PRESENT yoki LATE, barcha guruhlar bo'yicha. Shart: `firstVisitDate == today`. Davomat hali belgilanmagan bo'lsa → 2.5.
2. O'quvchining bugun PAID `Payment` i bor. To'lov qilingan yozilmaning (SG) birinchi davri bo'yicha qarz 0 (`BillingSnapshot`).
   - Chegirma bilan to'langan bo'lsa ham, qolgan qarz 0 bo'lsa → "to'liq".
   - Guruh va individual dars farq qilmaydi.
   - PER_LESSON to'lov turi uchun → 2.6-#3.
3. `book_gifts` da bu o'quvchi uchun yozuv yo'q.
4. Shu SG guruhining `level_id` bor; darajada `gift_book_item_id` bor; omborda `stock_qty > 0`.

`eligible=false` bo'lsa aniq sabab qaytadi: `NOT_FIRST_VISIT_DAY`, `NOT_FULLY_PAID`, `ALREADY_GIVEN`, `NO_LEVEL`, `NO_GIFT_BOOK`, `OUT_OF_STOCK`.
`POST /books/gifts {studentId, studentGroupId}` shu shartni qulf ostida **qayta tekshiradi**. Ombor kamayadi (GIFT), kassaga yozuv yo'q. Ertasi kuni 1-shart bajarilmaydi → tugma o'chiq.

**Boshlang'ich qoldiq** (SA, bir marta) — `POST /books/opening-balance [{bookItemId, qty, unitCost}]`. Faqat harakati yo'q kitobga ruxsat; OPENING harakati yoziladi, `avg_cost = unitCost`.

**Tuzatish** (SA) — `POST /books/adjustments {bookItemId, qtyDelta, reason}`; sabab majburiy.

**Hisobot (faqat SA)** — moliya hisobotiga qo'shiladi:
- `bookSales {STUDY, FICTION}`;
- `bookPurchases` (to'lovlar);
- `supplierDebt`;
- `bookCogs` = Σ(sotuv va sovg'a × `unit_cost`);
- `bookMargin`.

## 2.3 Endpointlar

| Endpoint | Rol |
|---|---|
| `GET /api/study-levels`; `POST`, `PUT` | GET — xodimlar; yozish — SA |
| `GET /api/books?kind&levelId&lowStock&active` | SA, A (`avgCost` faqat SA ga, `@JsonInclude(NON_NULL)` + servis tekshiruvi) |
| `POST`, `PUT /api/books` (narx, `min_stock`) | SA |
| `GET /api/books/{id}/movements` | SA, A (`unit_cost` faqat SA) |
| `GET /api/books/suppliers` (faqat nomlar) | SA, A; CRUD — SA |
| `POST /api/books/receipts` (soni) | SA, A |
| `GET /api/books/receipts` | SA hammasi; A — o'zi yaratganlari, tannarxsiz |
| `PUT /api/books/receipts/{id}/costs` | SA |
| `POST /api/books/receipts/{id}/payments` (Idempotency-Key) | SA, faqat DIRECTOR kassa |
| `GET /api/books/suppliers/{id}/balance` | SA |
| `POST /api/books/sales` (Idempotency-Key, `expectedTotal`) | SA, A (o'z kassasi) |
| `GET /api/books/sales?from&to&studentId`, `GET /{id}` (chek) | SA hammasi; A — o'z kassasidagilar |
| `GET /api/books/gifts/eligibility?studentId`, `POST /api/books/gifts`, `GET /api/books/gifts` | SA, A |
| `POST /api/books/opening-balance`, `POST /api/books/adjustments` | SA |
| `GET /api/books/reports/stock` | SA, A (soni); qiymati — SA |
| `GET /api/books/reports/low-stock` | SA, A |
| `GET /api/books/reports/sales?from&to&groupBy=item\|kind\|day` | SA |
| `GET /api/books/reports/margin`, `/supplier-debts` | SA |

`SecurityConfig` URL qoidalari faqat SA uchun: `/api/books/receipts/*/costs`, `/api/books/receipts/*/payments`, `/api/books/opening-balance`, `/api/books/adjustments`, `/api/books/reports/{sales,margin,supplier-debts}`.

## 2.4 UI

- **Modul `src/modules/books/`**, `matrix.ts` bo'limi "Ombor":
  - `/books` — katalog + qoldiq, kam qolganlar belgisi va filtri;
  - `/books/receipts` — A: kirim formasi (kitob → soni); SA: tannarx to'ldirish va yetkazib beruvchiga to'lash (`CashRegisterSelect`, faqat DIRECTOR);
  - `/books/suppliers` — SA;
  - `/books/sales` — sotuvlar;
  - `/books/reports` — qoldiq, kam qolgan; SA: davr sotuvi, marja, qarzlar.
- **`BookSaleDialog`**
  - xaridor: o'quvchi qidiruvi yoki tashqi (ism + telefon);
  - **savat**: kitob → soni, narx faqat o'qiladi, qoldiqdan ortiq son kiritib bo'lmaydi;
  - kassa (o'z kassasi, avtomatik) va usul (CASH_AND_CARD bo'linishi);
  - natijada chek.
- **"Kitob berish"**
  - qayerda: `TrialStudentsPage` qator menyusi, `StudentHeader` dropdown'i, `PaymentDialog` muvaffaqiyatidan keyin taklif sifatida;
  - holati eligibility natijasiga qarab: faol yoki o'chiq, sababi tooltip'da.
- **Mavjud formalarga qo'shimcha:**
  - `GroupFormDialog` va `CourseFormDialog` — daraja tanlash;
  - Sozlamalar → Darajalar (SA): til, kod, nom, bepul kitob.
- **Boshqa joylar:**
  - kassa `by-category` panelida "O'quv kitob" va "Badiiy kitob" alohida qator;
  - `FinanceReportPage` (SA) — kitob kartalari.
- **Kam qolgan kitob ogohlantirishi:** sidebar'da son, ertalabki qo'ng'iroqcha SA ga (`UserNotification`, kuniga bitta).

## 2.5 Migratsiya — V87 va xavflar

V87 tarkibi:
- `study_levels`, `groups.level_id`, `courses.level_id`;
- barcha `book_*` jadvallari;
- `cash_transactions` ga `book_sale_id` va `book_supplier_payment_id`;
- `settings` standartlari `books.lowStock.study = 30` va `books.lowStock.fiction = 3`.

`study_levels.gift_book_item_id` FK `book_items` dan keyin qo'shiladi. Darajalar va guruhlarga daraja biriktirish — SA qo'lda, UI orqali.

**Xavflar**
- **Birinchi kelgan kunning davomati kechikishi.** O'qituvchi davomatni kechqurun yoki ertasi kuni belgilashi mumkin. Shunda admin to'lov paytida kitob bera olmaydi, ertasi kuni esa shart buziladi.
  - Taklif: davomat belgilanmagan bo'lsa, `firstVisitDate := today` deb olinadi. Shart: o'quvchining umuman PRESENT yoki LATE yozuvi yo'q va guruhida bugun dars bor (`LessonCalendarService`).
  - Sovg'a `first_visit_assumed = true` belgisi bilan yoziladi.
  - Keyin davomat boshqa sanani ko'rsatsa — SA hisobotida ogohlantirish (2.6-#2).
- **Tannarxi kutilayotgan kitob** (`COST_PENDING`) sotilsa, sotuv qatorida `unit_cost = NULL` bo'ladi. Shu oraliqdagi marja taxminiy (`avg_cost` qayta hisoblanadi). Hisobotda "tannarxi to'liq emas" belgisi chiqadi.
- **Bir kunda ikki guruhga to'lov** — sovg'a bitta (UNIQUE). Daraja admin tanlagan SG dan olinadi.
- **Guruhda daraja yo'q** → sovg'a yo'q, aniq sabab qaytadi. Joriy etishda guruhlarga darajalarni biriktirish ishi talab qilinadi.
- **Narx o'zgarishi** dialog ochiq turganda → 409, narxlar qayta yuklanadi.

## 2.6 Ochiq savollar (2-bosqich)

1. Daraja guruhga bog'lanadimi (taklif) yoki kursga? Bitta "Turk tili" kursida turli darajadagi guruhlar bormi?
2. Birinchi kun davomati belgilanmagan holat uchun 2.5 dagi taklif ma'qulmi?
3. Individual darslarda dars bo'yicha to'lov (PER_LESSON) bo'lsa, "oylik to'liq" qanday hisoblanadi — `courses.monthly_price` ga teng summami?
4. Kitob narxi va kam qolgan chegarasini faqat Direktor o'zgartiradimi?
5. Kirimda yetkazib beruvchini admin tanlaydimi yoki Direktor tannarx bilan birga?
6. Sovg'a kitobi omborda tugagan bo'lsa — keyin berish yo'q ("ertasidan bosilmaydi") deb faraz qilindi. To'g'rimi?

## 2.7 Amalga oshirildi (V87, 2026-10-24)

**Kod:** `com.crm.books` — `BookStockService` (ombor qoldig'ining yagona o'zgartirish nuqtasi), `BookCatalogService`
(darajalar, katalog, tuzatish), `BookReceiptService` (yetkazib beruvchi, kirim, tannarx, to'lov), `BookSaleService`,
`BookGiftService`, `BookReportService`; controller'lar `StudyLevelController` (`/api/study-levels`), `BookController`,
`BookReceiptController`, `BookSaleController` (`/api/books/**`). API — docs/audit/api-inventory.md "2026-10-24".

**Model (2.1 dan farqi):**
- FK lar oddiy `Long` id ustunlar (entity'da `@ManyToOne` yo'q); bazada FK — V87 da.
- `book_sales.cash_transaction_id` ga FK yo'q (aylanma bog'lanish bo'lmasin); bog'lanish `cash_transactions.book_sale_id` (FK).
- `book_supplier_payments.status` yo'q — to'lovni bekor qilish ushbu bosqichda yo'q.
- Harakat turi `OPENING` yo'q — boshlang'ich qoldiq `ADJUSTMENT` (sabab majburiy, buyurtmachi talabi).

**Qoidalar (standart, amalga oshirishda aniqlangan):**
- "Kam qolgan" = `stock_qty ≤ min_stock` (chegarada — buyurtma vaqti). Bildirishnoma faqat kamayishda (sotuv, sovg'a,
  manfiy tuzatish), SA va ADMIN qo'ng'iroqchasiga, kalit `lowstock:{bookId}:{kun}` — kuniga bitta.
- O'rtacha tannarx — tortilgan o'rtacha. Tannarx kirimdan keyin to'ldirilsa yoki o'zgartirilsa (to'lovgacha mumkin) —
  joriy qoldiq bo'yicha taxminiy tuzatish; sotuv/sovg'a qatoridagi `unit_cost` — o'sha paytdagi o'rtacha (tannarx hali
  to'ldirilmagan bo'lsa null; hisobotda `costIncomplete`).
- Kirimni admin faqat o'zi kiritganlarini ko'radi (tannarxsiz); kirim sanasi — `PaymentDatePolicy` (kelajak — 400,
  o'tgan — faqat SA).
- Sotuvda savatdagi takror qatorlar birlashtiriladi; sotuv summasi > 0 bo'lishi shart.
- Bepul kitob, birinchi kelgan kun: eng erta PRESENT/LATE davomat = bugun; davomat yo'q bo'lsa — guruhida bugun dars bor.
  Qo'shimcha himoya: bugundan oldingi to'langan to'lovi bo'lgan o'quvchi — "yangi emas" (`NOT_FIRST_VISIT_DAY`).
  "Oylik to'liq": yozilma bo'yicha bugungi to'lovlar brutto (chegirma bilan) ≥ `EnrollmentPricing.effectiveMonthlyFee`
  (PER_LESSON — guruhning oylik narxi) va yozilma ledger balansi ≥ 0. Bir nechta mos yozilma — so'ralgani, keyin darajasi
  borligi. Qulf: o'quvchi → kitob; `book_gifts.student_id` UNIQUE — oxirgi himoya.
- Qulf tartibi: o'quvchi → kitoblar (id o'sish tartibida) → kirim → kassa. Kitob qatori qulfi — `BillingLocks.lock`
  (5 s, 409 `concurrency.busy`).
- Moliya hisoboti: kitob sotuvi (`bookSales`, tur bo'yicha) va xaridi (`bookPurchases`) alohida qatorlar,
  `netProfit = … + bookSales − bookPurchases`. Analitikada sotuv — `otherIncome` da, xarid — `expenses` da (net bir xil).
- Kassa ko'rinishi va topshirish preview'i: `BOOK_SALE` → "O'quv kitob" (`BOOK_SALE_STUDY`) va "Badiiy kitob"
  (`BOOK_SALE_FICTION`) — sotuv qatorlaridan (`CashCategoryTotals.view` — yagona qurilish).

**Testlar:** `BookInventoryTest` (H2 va pgtest): minus himoyasi va oxirgi nusxaning parallel sotuvi, admin kirimi
(faqat son) va SA tannarxi, boshlang'ich qoldiq, tannarx API da yashirilishi, Direktor kassasidan qisman to'lov va
idempotentlik, savat + o'z kassasi + hisobot + moliya, kam qolgan bildirishnomasi (kuniga bitta), sovg'a: birinchi kun /
ertasi / takror / to'liq to'lanmagan / daraja yo'q / bepul kitob yo'q / omborda yo'q / davomatsiz birinchi kun.

**Qolgani:** frontend (adizone-admin) — `/books` sahifalari, "Kitob berish" tugmasi, kassa ko'rinishida kitob qatorlari.
Deploy: V87 — yangi jar'dan OLDIN, qo'lda (V86 kabi); keyin SA darajalar, katalog, bepul kitob va boshlang'ich qoldiqni
(`POST /api/books/adjustments`) kiritadi.

# O'qituvchi KPI: `billing_periods.paid_on` va dashboard backfill G5

> O'qituvchi KPI ning "to'lov" va "o'z vaqtida to'lov" ko'rsatkichlari `billing_periods.due_date / grace_until / paid_on`
> ga tayanadi (`TeacherKpiService`, direktor dashboardidagi "Muddati kelgan to'lovlar" bilan bir xil ta'rif).
> Prod'da billing v2 migratsiyasi **03.10.2026** da qo'llangan. Savol: G5 (davrlar qachon yopilgani) ni alohida
> to'ldirish kerakmi?

## Xulosa: G5 hozirgi `billing_periods` uchun KERAK EMAS (kod bo'yicha) — faqat tekshiruv

Koddan:

1. `paid_on`, `due_date`, `grace_until` ni `PeriodCoverageService.refresh(sg)` yozadi; uni
   `BillingSnapshotService.writeEnrollment` **har SG yangilanishida, o'sha tranzaksiyada** chaqiradi
   (commit `1dce479`, 2026-10-02).
2. Migratsiya (`BillingMigrationService.applyOne`) har SG uchun davrlar va ledger yozuvlarini yaratib, oxirida
   `snapshotService.refresh(sg)` chaqiradi → migratsiya davrlari yaratilgan zahoti qoplanadi
   (`coverage_source = MIGRATION_REPLAY`). Hold'ga tushgan SG da davr yo'q; keyingi `apply-sg` ham `applyOne` orqali.
3. Migratsiyadan keyingi har bir davr (accrual), to'lov, bekor qilish, muzlatish, ko'chirish — hammasi
   `snapshotService.refresh` bilan tugaydi → `paid_on` jonli yangilanadi.
4. `billing_periods` jadvali (V52) va `due_date…paid_on` ustunlari (V53) shu deploy bilan paydo bo'lgan —
   coverage kodidan oldin yaratilgan "eski" davr prod'da yo'q.

G5 faqat coverage kodi bo'lmagan jar bilan yaratilgan davrlar uchun kerak edi. Migratsiyani bajargan jar
03.10 da 02.10 dan keyingi koddan yig'ilgan (deploy-day-2026-10-04.md §0.6) — demak bunday davr kutilmaydi.
Bu **taxmin prod ma'lumotida tasdiqlanishi kerak**: quyidagi ikkala tekshiruv ham faqat o'qiydi.

## 1. Tekshiruv (hech narsa yozmaydi)

### 1a. SQL (psql, read-only)

```bash
PGOPTIONS='-c default_transaction_read_only=on' psql -h localhost -U crm_user -d adizone_crm -X -v ON_ERROR_STOP=1 -c "
SELECT COUNT(*)                                                   AS muddatli_davrlar,
       COUNT(*) FILTER (WHERE due_date IS NULL OR grace_until IS NULL) AS muddatsiz,
       COUNT(*) FILTER (WHERE paid_on IS NOT NULL)                AS yopilgan,
       COUNT(*) FILTER (WHERE coverage_source = 'MIGRATION_REPLAY') AS migratsiya_replay,
       COUNT(*) FILTER (WHERE coverage_source = 'FIFO')           AS fifo,
       MIN(period_start)                                          AS eng_eski_davr
  FROM billing_periods
 WHERE status IN ('CHARGED', 'PARTIALLY_REFUNDED') AND charge_tx_id IS NOT NULL;"
```

**O'qish:** `muddatsiz` **0** bo'lishi kerak — coverage refresh har davrga `due_date`/`grace_until` ni albatta yozadi;
0 dan katta bo'lsa, o'sha davrlar SG si refresh ko'rmagan → backfill kerak (2-bo'lim). `yopilgan` < `muddatli_davrlar`
normal (to'lanmagan davrlar).

### 1b. Backfill DRY-RUN (endpoint, hech narsa yozmaydi)

Server ichida, SA token bilan (parol buyruq qatoriga yozilmaydi; token 8 soat):

```bash
export API=http://127.0.0.1:8080
jget() { python3 -c 'import json,sys; d=json.load(sys.stdin); d=d.get("data",d); print(eval(sys.argv[1]))' "$1"; }
read -rs SA_PW && export SA_PW
TOKEN=$(python3 -c 'import json,os,urllib.request as u; r=u.Request(os.environ["API"]+"/api/auth/login", data=json.dumps({"username":"superadmin","password":os.environ["SA_PW"]}).encode(), headers={"Content-Type":"application/json"}); print(json.load(u.urlopen(r))["data"]["accessToken"])'); unset SA_PW
```

```bash
curl -s -X POST "$API/api/admin/dashboard/backfill?dryRun=true" -H "Authorization: Bearer $TOKEN" > backfill-dry-run.json
jget '"\n".join(i["code"] + ": candidates=" + str(i["candidates"]) + " changes=" + str(i["changes"]) for i in d["items"])' < backfill-dry-run.json
```

**O'qish:** `G5: … changes=0` — hech bir davr o'zgarmaydi, **backfill kerak emas, 2-bo'limni o'tkazib yuboring**.
`changes` — o'zgaradigan davr qatorlari soni (`candidates` — tekshirilgan SG lar). Boshqa qatorlar (G2, G3/G4, G6,
G10) KPI ga aloqasi yo'q — direktor dashboardi tarixi.

**Xato bo'lsa:** `401` — token eskirgan/yo'q (qayta login); `403` — SA emas.

## 2. Qo'llash — FAQAT 1a `muddatsiz > 0` yoki 1b `G5 changes > 0` bo'lsa

> ⚠️ Endpoint G5 ni alohida qo'llamaydi: **G2, G3/G4, G6, G10 ham** birga yoziladi (faqat bo'sh maydonlar —
> mavjud qiymat ustidan yozilmaydi). Ularning `changes` sonini 1b natijasida ko'rib, egasi bilan kelishing.
> Ledger, balans va to'lovlarga tegmaydi.

```bash
curl -s -X POST "$API/api/admin/dashboard/backfill?dryRun=false&confirm=BACKFILL-APPLY" -H "Authorization: Bearer $TOKEN" > backfill-apply.json
jget '"\n".join(i["code"] + ": changes=" + str(i["changes"]) for i in d["items"])' < backfill-apply.json
```

**Kutilgan:** `G5 changes` = 1b dagi son. Keyin 1b ni qayta bajaring → `G5 changes=0`; 1a → `muddatsiz=0`.
**Xato bo'lsa:** `400 migration.confirmRequired` — `confirm=BACKFILL-APPLY` yo'q.

## 3. Deploydan keyin: o'tgan oy KPI snapshot'i

Oy yakuni snapshot'i (`teacher_kpi_monthly`, V72) har oyning 1-kuni 01:00 da faqat **o'tgan oy** uchun yoziladi —
deploy oyidan oldingi oylar uchun avtomatik yozilmaydi (o'qilganda jonli hisoblanadi, `source: LIVE`). Sentabrni
muzlatib qo'yish uchun (V72 qo'llangan, yangi jar ishlayotgan bo'lsa):

```bash
curl -s -X POST "$API/api/teachers/kpi/snapshots?month=2026-09" -H "Authorization: Bearer $TOKEN" | jget 'd'
```

**Kutilgan:** `{'month': '2026-09', 'rows': <o'qituvchilar soni>}`. Eslatma: migratsiya davrlari
(`teacher_source = ESTIMATED`) o'qituvchisi — migratsiya paytidagi guruh o'qituvchisi; ularning `paid_on` i
`MIGRATION_REPLAY` (eski to'lovlar FIFO bo'yicha qayta o'ynalgan) — sentabr KPI si shu sababli taxminiy.

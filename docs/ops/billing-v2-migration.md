# Billing v2 — migratsiya va rollback runbook

> Manba: `docs/design/billing-v2.md` §9.4–§9.7, §13 #18, #20. Kod: `billing/MigrationPlanner.java`,
> `billing/BillingMigrationService.java`, `controller/BillingMigrationController.java`.
>
> **Holat:** kod tayyor, faqat H2 integratsiya testlarida (`MigrationTest`) sinalgan.
> Migratsiya **hech qaysi haqiqiy bazada bajarilmagan**. Ilova uni o'zi hech qachon ishga
> tushirmaydi — har bir yozuvchi qadam SUPER_ADMIN so'rovi va aniq `confirm` parametrini talab qiladi.

## Atamalar
- **G** — go-live, `app.billing.migration-go-live` (default `2026-09-18`).
- **T** — cutover sanasi, har so'rovda `cutover=YYYY-MM-DD`.
- **Run** — `billing_migration_runs` qatori. U faqat **tasdiqlangan** hisobot uchun yaratiladi (dry-run qator yaratmaydi).

## Endpointlar (hammasi `SUPER_ADMIN`)

| Qadam | So'rov | Yozadimi |
|---|---|---|
| Dry-run (JSON) | `POST /api/admin/billing/migration/dry-run?cutover=T[&a14UsePayable=true]` | **Yo'q** |
| Dry-run (xlsx, egasi uchun) | `GET /api/admin/billing/migration/dry-run.xlsx?cutover=T` (`X-Report-Hash` sarlavhasi) | **Yo'q** |
| Tasdiq | `POST /api/admin/billing/migration/approve` `{cutover, a14UsePayable, reportHash, approvedByOwner, note}` | faqat `billing_migration_runs` (APPROVED) |
| Apply | `POST /api/admin/billing/migration/apply?runId=N&confirm=APPLY-N[&exclude=1,2][&clearOverrides=true]` | ha |
| Tekshiruv | `GET /api/admin/billing/verify` | yo'q |
| Qisman qaytarish | `POST /api/admin/billing/migration/revert-sg?runId=N&sgId=S&confirm=REVERT-N-S` | ha (REVERSAL) |
| SG ni qayta qo'llash | `POST /api/admin/billing/migration/apply-sg?runId=N&sgId=S&confirm=APPLY-N-S` | ha |
| Rollback eksporti | `GET /api/admin/billing/payments-since?from=2026-10-05T00:00:00` (xlsx) | yo'q |
| Runlar | `GET /api/admin/billing/migration/runs`, `…/runs/{id}` | yo'q |

## Apply himoyalari
1. Run holati `APPROVED` bo'lishi kerak. Aks holda 409 `migration.notApproved` qaytadi, shuning uchun bir run ikki marta qo'llanmaydi.
2. `confirm=APPLY-<runId>` berilmasa 400 `migration.confirmRequired`.
3. `app.billing.enabled=false` bo'lishi shart. Aks holda 409 `migration.billingEnabled`.
4. Hisobot qayta hisoblanadi. Hash tasdiqlangan hash'dan farq qilsa 409 `migration.reportChanged`. Hash ichiga `max(balance_transactions.id)` va `max(payments.id)` ham kiradi.
5. `exclude` ro'yxatidagi va bloklovchi anomaliyali (A4, A16, NO_ANCHOR) M-SG lar yozilmaydi. Ular `billing_hold = true` (MIGRATION_PENDING) holatiga o'tadi va accrual ularni o'tkazib yuboradi.
6. Har SG alohida tranzaksiyada yoziladi. Yiqilgan SG ham hold'ga o'tadi va `errors` ga yoziladi; run holati `APPLIED_WITH_ERRORS` bo'ladi.
7. Yozuvlar `migration_run_id` bilan belgilanadi: `billing_periods` (MIGRATED/CHARGED), `PERIOD_CHARGE`, `MIGRATION`.
8. Yon amallar:
   - eski muzlatilgan SG larda `frozen_from` to'ldiriladi;
   - `clearOverrides=true` bo'lsa, egasi tasdiqlagan A15 ro'yxati bo'yicha `monthlyPriceOverride = NULL` qilinadi (§9.5).

## Cutover tartibi (§9.6)
1. **T − 3 kun.** Prod nusxasi stagingga ko'chiriladi (`pg_dump`). U yerda `dry-run.xlsx` olinib, **egasiga** beriladi.
   - Egasi A-ro'yxatlar bo'yicha qaror qabul qiladi: `exclude`, A14 (`a14UsePayable`), A15 (`clearOverrides`).
2. **T, texnik oyna.**
   1. `pg_dump` qilinadi → `pre-billing-v2-<sana>.dump`.
   2. `V52__billing_v2.sql` qo'lda bajariladi (additive).
   3. Jar `app.billing.enabled=false` bilan deploy qilinadi.
3. Prodda dry-run (`cutover=T`) o'tkaziladi. Egasi hisobotni yana bir bor ko'rib chiqadi.
   - Keyin `approve`: `reportHash` = shu dry-run hash'i, `approvedByOwner` = egasining F.I.Sh.
4. `apply?runId=…&confirm=APPLY-…` bajariladi.
5. `verify` chaqiriladi: `ok = true` va `heldEnrollments` kutilgan ro'yxat bo'lishi kerak.
6. `app.billing.enabled=true` qilinadi (qayta ishga tushirish), keyin job va to'lovlar ochiladi.
7. **Darhol** `payments-since?from=T` bo'yicha davriy eksportni boshlang. Rollback oynasi davomida har kuni olinadi.

## Rollback (§9.7, §13 #20 — oyna 72 soat)
`applied_at + 72 soat` qiymati run'ning `rollbackDeadline` maydonida turadi.

| Holat | Amal |
|---|---|
| 6-qadamdan oldin (to'lov qabul qilinmagan) | `pg_dump` dan tiklanadi va oldingi jar (`main`) qaytariladi. Ma'lumot yo'qolmaydi. |
| 6-qadamdan keyin, `rollbackDeadline` gacha | 1) `payments-since?from=T` xlsx yuklab olinadi. 2) `pg_dump` dan tiklanadi va oldingi jar qaytariladi. 3) Ro'yxatdagi to'lovlar, bekor qilishlar va muzlatishlar eski UI orqali qayta kiritiladi. |
| `rollbackDeadline` dan keyin | To'liq rollback **yo'q**. Faqat oldinga tuzatish qilinadi: `revert-sg` → qo'lda tuzatish → `apply-sg`, yoki `MANUAL_ADJUST` / `REVERSAL`. |

**Bitta SG noto'g'ri migratsiya qilingan bo'lsa** (istalgan vaqtda):
1. `revert-sg?runId&sgId&confirm=REVERT-…` chaqiriladi. U quyidagilarni qiladi:
   - run'ning shu SG dagi yozuvlariga `REVERSAL` yozadi;
   - run'ning `billing_periods` qatorlarini o'chiradi (yagona DELETE istisnosi);
   - SG ni `billing_hold` holatiga o'tkazadi.
2. Ma'lumot qo'lda tuzatiladi (masalan `nextPaymentDate`, narx, to'lov bog'lanishi).
3. `apply-sg?runId&sgId&confirm=APPLY-…` chaqiriladi. U shu run parametrlari bilan qayta hisoblaydi, yozadi, hold'ni oladi va billing yoqilgan bo'lsa bugungacha accrual qiladi.

Agar `revert-sg` javobida `migration.revert.laterPeriods` ogohlantirishi bo'lsa, migratsiyadan keyin accrual yozgan davrlar qolgan. Ular qo'lda ko'riladi.

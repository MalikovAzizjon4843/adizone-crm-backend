# Vaqt zonasi: Asia/Tashkent

## Nima o'zgardi

| Joy | Qiymat |
|---|---|
| `CrmApplication.main` | `TimeZone.setDefault(Asia/Tashkent)` — `SpringApplication.run` dan **oldin** |
| `spring.jackson.time-zone` | `Asia/Tashkent` (JSON'dagi `Date`/`Instant` matni) |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `Asia/Tashkent` |
| `@Scheduled(cron = …)` | barchasiga `zone = "Asia/Tashkent"` (audit tozalash 03:30, token tozalash 03:00, to'lov holatlari 00:05, eslatma 10:00, lifecycle 08:00) |

Kodda `LocalDate.now()` — **109 ta** (34 fayl), `LocalDateTime.now()` — **99 ta** (48 fayl),
`ZoneId.systemDefault()` — 7 ta, `Instant.now()` — 9 ta. Ularning hammasi JVM default zonasidan
foydalanadi, endi bu doim Toshkent — server (Docker, VPS) qaysi zonada ishlashidan qat'i nazar.
`Clock` injeksiyasi kiritilmadi.

`LocalDate` / `LocalDateTime` — zonasiz: bazaga (`date` / `timestamp without time zone`) "devor vaqti"
qanday bo'lsa shunday yoziladi. Shuning uchun asosiy xavf — **mavjud ma'lumotlar**.

## Mavjud ma'lumotlar — eslatma

Agar server **avval UTC da ishlagan bo'lsa**, `LocalDateTime.now()` / `@CreatedDate` bilan yozilgan
qiymatlar UTC devor vaqtida saqlangan (Toshkentdan **5 soat orqada**). Deploydan keyingi yangi
yozuvlar Toshkent vaqtida bo'ladi — eski va yangi yozuvlar aralashadi (tartib, "bugun" filtrlari,
hisobotlar).

Avval tekshiring (ishlab turgan serverda, deploydan OLDIN):

```bash
# JVM/OS zonasi
timedatectl            # yoki: docker exec <container> date
```

```sql
-- Oxirgi yozilgan vaqt bilan bazaning hozirgi vaqti (UTC'da yozilgan bo'lsa ~5 soat farq)
SELECT max(created_at) AS last_audit, now() AT TIME ZONE 'Asia/Tashkent' AS tashkent_now FROM audit_logs;
```

Server allaqachon Asia/Tashkent da ishlagan bo'lsa — hech narsa qilish shart emas.

### Ta'sirlanadigan ustunlar (server `now()` bilan yozadi → siljitiladi)

| Jadval | Ustunlar |
|---|---|
| BaseEntity (`@CreatedDate`/`@LastModifiedDate`): `classes`, `classrooms`, `conversations`, `courses`, `exams`, `exam_results`, `groups`, `homeworks`, `homework_submissions`, `lead_notes`, `lead_stages`, `leave_requests`, `notices`, `parents`, `payroll`, `promotions`, `sections`, `students`, `subjects`, `tasks`, `teachers`, `timetable`, `users` | `created_at`, `updated_at` |
| `attendance` | `created_at`, `updated_at` |
| `attendance_unlock_requests` | `reviewed_at`, `created_at` |
| `audit_logs` | `created_at` |
| `balance_transactions` | `created_at` |
| `bonus_penalties`, `cash_registers`, `cash_transactions`, `contract_templates`, `expenses`, `income`, `payments`, `salary_rules`, `meta_lead_forms`, `meta_lead_form_questions` | `created_at`, `updated_at` |
| `contracts` | `accepted_at`, `created_at`, `updated_at` |
| `conversations` | `last_message_at` |
| `conversation_participants` | `joined_at`, `left_at` |
| `exam_registrations` | `created_at` |
| `exam_results` | `edited_at` |
| `group_schedule_days` | `created_at` |
| `homework_submissions` | `submitted_at` |
| `leads` | `assigned_at`, `created_at`, `updated_at` |
| `lead_comments` | `created_at` |
| `lead_status_history`, `student_status_history` | `changed_at` |
| `leave_requests` | `approved_at` |
| `messages` | `created_at`, `edited_at`, `deleted_at` |
| `message_attachments` | `created_at` |
| `notice_reads` | `read_at` |
| `refresh_tokens` | `created_at`, `expires_at` (siljitish shart emas — tokenlar baribir muddati bilan o'chadi) |
| `student_groups` | `suspended_at`, `created_at`, `updated_at` |
| `student_payment_plans` | `created_at` |
| `tasks` | `completed_at` |
| `users` | `last_login`, `last_seen_at` |

### SILJITILMAYDI

- `tasks.due_at` — foydalanuvchi kiritgan Toshkent devor vaqti (Meta lid avtomatik vazifasi bundan
  mustasno — `LocalDate.now().atTime(...)`, faqat 00:00–05:00 oralig'ida bir kun farq qilishi mumkin).
- `notices.published_at`, `notices.expires_at` — odatda admin kiritadi (bo'sh bo'lsa `now()`); qo'lda,
  yozuvma-yozuv tekshiring.
- `Instant` ustunlari (`meta_lead_forms.synced_at`, `meta_webhook_events.received_at`/`processed_at`) —
  absolyut vaqt (`timestamp with time zone`, TAXMIN: ddl-auto yaratgan) — zonaga bog'liq emas.
- `LocalDate` ustunlari (sana) — siljitilmaydi. Lekin UTC serverda 00:00–05:00 (Toshkent) oralig'ida
  `LocalDate.now()` bilan yozilgan sanalar (masalan to'lov sanasi default, `admission_date`) bir kun
  oldingi bo'lishi mumkin — avtomatik tuzatib bo'lmaydi.

### Tuzatish SQL — NAMUNA (BAJARILMAGAN)

`<deploy vaqti>` — yangi versiya ishga tushgan payt, **Toshkent devor vaqtida** (masalan
`2026-10-05 02:00:00`). Shundan oldingi yozuvlar UTC devor vaqtida deb hisoblanadi. Avval zaxira
nusxa (`pg_dump`), bitta tranzaksiyada, avval `SELECT count(*)` bilan tekshirib:

```sql
BEGIN;

-- Namuna: bitta jadval
UPDATE audit_logs
   SET created_at = created_at + interval '5 hours'
 WHERE created_at < '<deploy vaqti>';

UPDATE leads
   SET created_at  = created_at  + interval '5 hours' WHERE created_at  < '<deploy vaqti>';
UPDATE leads
   SET updated_at  = updated_at  + interval '5 hours' WHERE updated_at  < '<deploy vaqti>';
UPDATE leads
   SET assigned_at = assigned_at + interval '5 hours' WHERE assigned_at < '<deploy vaqti>';

-- … yuqoridagi jadvaldagi har bir (jadval, ustun) uchun xuddi shunday.
-- DIQQAT: updated_at deploydan keyin yangilangan qatorlar shartga tushmaydi — to'g'ri.
-- Bitta qatorni ikki marta siljitmaslik uchun skriptni FAQAT BIR MARTA bajaring.

COMMIT;
```

Muhim: tuzatish deploydan keyin imkon qadar tez (yangi yozuvlar bilan aralashib ketmasdan) va
faqat server avval UTC da ishlagani tasdiqlangandan keyin bajariladi.

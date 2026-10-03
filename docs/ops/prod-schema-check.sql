-- ═══════════════════════════════════════════════════════════════════════════════════
-- Prod sxema tekshiruvi — FAQAT O'QIYDI (docs/audit/phase5-audit.md X-01, §12.1 #10).
--
-- Nima uchun: Flyway yo'q, V25–V64 qo'lda bajariladi va qaysi bazada qaysi bo'lak
-- qo'llangani noma'lum. Bu skript hech narsani o'zgartirmaydi: butun ish READ ONLY
-- tranzaksiyada va oxirida ROLLBACK. Natijani ko'rib, yetishmaganini tegishli
-- V__*.sql faylidan (ular idempotent) alohida, kelishilgan oynada qo'llang.
--
-- Ishga tushirish (parolni buyruq qatoriga yozmang — ~/.pgpass yoki PGPASSWORD):
--   PGOPTIONS='-c default_transaction_read_only=on' \
--   psql -h <host> -U <user> -d adizone -X -v ON_ERROR_STOP=1 -f docs/ops/prod-schema-check.sql
--
-- Bo'limlar:
--   1. V25–V63 bo'laklari: jadval/ustun/indeks/cheklov/sequence — faqat YO'QLARI + xulosa
--      (V64 faqat olib tashlaydi — uning tekshiruvi 2-bo'limda)
--   2. Ma'noviy invariantlar (nomidan qat'i nazar): UNIQUE juftliklar, NOT NULL, sequence
--   3. Dublikat FK lar (bir ustunda bir nechta FK, ON DELETE har xil)
--   4. CHECK cheklovlari (enum CHECK lar EnumCheckConstraintCleaner tomonidan o'chiriladi)
--   5. Dublikat UNIQUE / oddiy indekslar (bir xil ustunlar to'plami)
--   6. flyway_schema_history (qolgan bo'lsa)
-- ═══════════════════════════════════════════════════════════════════════════════════

BEGIN TRANSACTION READ ONLY;

-- ── 1. V25–V63 bo'laklari ────────────────────────────────────────────────────────────
-- kind: table | column | index | constraint | sequence. Ro'yxat migratsiya fayllaridan olingan.
-- V40 dagi uk_payroll_user_month_year ro'yxatda yo'q — V54 uni *_active bilan almashtiradi.
-- V58 dagi ux_exam_registrations_exam_student ham yo'q — V62 uni qisman ux_exam_registrations_active bilan almashtiradi.
-- Nomi bo'yicha tekshiriladi; boshqa nom bilan yaratilgan ekvivalent (masalan Hibernate uk_*)
-- 2-bo'limda ustunlar bo'yicha tekshiriladi.
-- Natija: har migratsiya bo'yicha bor / jami va YO'Q bo'laklar ro'yxati
-- (READ ONLY tranzaksiyada vaqtinchalik jadval/view ham yaratib bo'lmaydi — shuning uchun CTE).
WITH want(mig, kind, tbl, obj) AS (VALUES
    ('V25', 'index', 'group_schedule_days', 'idx_schedule_group'),
    ('V25', 'table', 'group_schedule_days', NULL),
    ('V26', 'column', 'student_groups', 'first_lesson_date'),
    ('V26', 'column', 'student_groups', 'last_payment_date'),
    ('V26', 'column', 'student_groups', 'lessons_attended'),
    ('V26', 'column', 'student_groups', 'next_payment_due'),
    ('V26', 'column', 'student_groups', 'payment_status'),
    ('V26', 'column', 'student_groups', 'suspended_at'),
    ('V26', 'column', 'student_groups', 'suspension_reason'),
    ('V26', 'index', 'student_payment_plans', 'idx_payment_plans_group'),
    ('V26', 'index', 'student_payment_plans', 'idx_payment_plans_student'),
    ('V26', 'table', 'student_payment_plans', NULL),
    ('V27', 'index', 'exam_registrations', 'idx_exam_registrations_exam'),
    ('V27', 'index', 'exam_registrations', 'idx_exam_registrations_student'),
    ('V27', 'table', 'exam_registrations', NULL),
    ('V28', 'column', 'timetable', 'subject_name'),
    ('V29', 'column', 'exams', 'group_id'),
    ('V29', 'column', 'exams', 'teacher_id'),
    ('V29', 'index', 'exams', 'idx_exams_group_id'),
    ('V31', 'column', 'leave_requests', 'teacher_id'),
    ('V31', 'column', 'student_groups', 'exit_date'),
    ('V31', 'column', 'student_groups', 'exit_notes'),
    ('V31', 'column', 'student_groups', 'exit_reason'),
    ('V31', 'table', 'student_status_history', NULL),
    ('V33', 'column', 'attendance', 'excuse_reason'),
    ('V33', 'column', 'attendance', 'excused'),
    ('V34', 'column', 'promotions', 'source_month'),
    ('V34', 'column', 'promotions', 'source_year'),
    ('V34', 'column', 'promotions', 'target_month'),
    ('V34', 'column', 'promotions', 'target_year'),
    ('V35', 'column', 'leads', 'assigned_at'),
    ('V35', 'column', 'leads', 'assigned_user_id'),
    ('V35', 'index', 'lead_comments', 'idx_lead_comments_lead_id'),
    ('V35', 'index', 'leads', 'idx_leads_assigned_user_id'),
    ('V35', 'index', 'leads', 'idx_leads_status'),
    ('V35', 'table', 'lead_comments', NULL),
    ('V36', 'column', 'leads', 'parent_phone'),
    ('V36', 'column', 'students', 'converted_from_lead_id'),
    ('V36', 'index', 'students', 'idx_students_converted_from_lead_id'),
    ('V37', 'column', 'courses', 'lesson_price'),
    ('V37', 'column', 'payments', 'balance_used'),
    ('V37', 'column', 'student_groups', 'lesson_price'),
    ('V37', 'column', 'student_groups', 'lessons_purchased'),
    ('V37', 'column', 'student_groups', 'lessons_used'),
    ('V37', 'column', 'student_groups', 'payment_type'),
    ('V37', 'column', 'students', 'balance'),
    ('V38', 'column', 'student_groups', 'balance'),
    ('V38', 'index', 'balance_transactions', 'idx_balance_tx_created'),
    ('V38', 'index', 'balance_transactions', 'idx_balance_tx_sg'),
    ('V38', 'index', 'balance_transactions', 'idx_balance_tx_student'),
    ('V38', 'table', 'balance_transactions', NULL),
    ('V39', 'column', 'exam_results', 'edit_note'),
    ('V39', 'column', 'exam_results', 'edited_at'),
    ('V39', 'column', 'exam_results', 'edited_by'),
    ('V40', 'column', 'payroll', 'calculation_details'),
    ('V40', 'column', 'payroll', 'kpi_amount'),
    ('V40', 'column', 'payroll', 'kpi_applied'),
    ('V40', 'column', 'payroll', 'new_student_count'),
    ('V40', 'column', 'payroll', 'paid_student_count'),
    ('V40', 'column', 'payroll', 'user_id'),
    ('V40', 'column', 'students', 'attributed_user_id'),
    ('V40', 'column', 'students', 'created_by'),
    ('V40', 'index', 'salary_rules', 'idx_salary_rules_role'),
    ('V40', 'index', 'salary_rules', 'idx_salary_rules_user'),
    ('V40', 'index', 'students', 'idx_students_attributed_user'),
    ('V40', 'index', 'students', 'idx_students_created_by'),
    ('V40', 'table', 'salary_rules', NULL),
    ('V41', 'constraint', NULL, 'uk_notice_reads_notice_user'),
    ('V41', 'index', 'notice_reads', 'idx_notice_reads_notice'),
    ('V41', 'index', 'notice_reads', 'idx_notice_reads_user'),
    ('V41', 'table', 'notice_reads', NULL),
    ('V42', 'index', 'audit_logs', 'idx_audit_action'),
    ('V42', 'index', 'audit_logs', 'idx_audit_created'),
    ('V42', 'index', 'audit_logs', 'idx_audit_entity'),
    ('V42', 'index', 'audit_logs', 'idx_audit_user'),
    ('V42', 'table', 'audit_logs', NULL),
    ('V43', 'column', 'student_status_history', 'balance_snapshot'),
    ('V43', 'column', 'student_status_history', 'meta_json'),
    ('V45', 'constraint', NULL, 'uk_conv_participant'),
    ('V45', 'index', 'conversation_participants', 'idx_conv_participants_user'),
    ('V45', 'index', 'conversations', 'idx_conversations_last_message'),
    ('V45', 'index', 'messages', 'idx_messages_conversation_created'),
    ('V45', 'index', 'messages', 'idx_messages_conversation_id_desc'),
    ('V45', 'table', 'conversation_participants', NULL),
    ('V45', 'table', 'conversations', NULL),
    ('V45', 'table', 'messages', NULL),
    ('V46', 'column', 'users', 'last_seen_at'),
    ('V47', 'index', 'message_attachments', 'idx_message_attachments_message'),
    ('V47', 'table', 'message_attachments', NULL),
    ('V48', 'column', 'message_attachments', 'duration_ms'),
    ('V48', 'column', 'message_attachments', 'waveform'),
    ('V49', 'column', 'leads', 'meta_form_id'),
    ('V49', 'column', 'leads', 'meta_leadgen_id'),
    ('V49', 'column', 'leads', 'meta_raw_json'),
    ('V49', 'index', 'leads', 'idx_leads_meta_form'),
    ('V49', 'index', 'leads', 'idx_leads_phone_created'),
    ('V49', 'index', 'leads', 'uk_leads_meta_leadgen_id'),
    ('V49', 'index', 'meta_lead_form_questions', 'idx_meta_form_questions_form'),
    ('V49', 'index', 'meta_lead_form_questions', 'uk_meta_form_question'),
    ('V49', 'index', 'meta_lead_forms', 'idx_meta_lead_forms_page'),
    ('V49', 'index', 'meta_lead_forms', 'idx_meta_lead_forms_type'),
    ('V49', 'index', 'meta_lead_forms', 'uk_meta_lead_forms_form_id'),
    ('V49', 'index', 'meta_webhook_events', 'idx_meta_webhook_events_form'),
    ('V49', 'index', 'meta_webhook_events', 'idx_meta_webhook_events_received'),
    ('V49', 'index', 'meta_webhook_events', 'idx_meta_webhook_events_status'),
    ('V49', 'index', 'meta_webhook_events', 'uk_meta_webhook_events_leadgen'),
    ('V49', 'table', 'meta_lead_form_questions', NULL),
    ('V49', 'table', 'meta_lead_forms', NULL),
    ('V49', 'table', 'meta_webhook_events', NULL),
    ('V50', 'column', 'teachers', 'user_id'),
    ('V50', 'constraint', 'teachers', 'fk_teachers_user'),
    ('V50', 'index', 'teachers', 'idx_teachers_phone'),
    ('V50', 'index', 'teachers', 'ux_teachers_user_id'),
    ('V52', 'column', 'balance_transactions', 'billing_period_id'),
    ('V52', 'column', 'balance_transactions', 'effective_date'),
    ('V52', 'column', 'balance_transactions', 'migration_run_id'),
    ('V52', 'column', 'balance_transactions', 'related_tx_id'),
    ('V52', 'column', 'bonus_penalties', 'cancel_reason'),
    ('V52', 'column', 'bonus_penalties', 'ledger_tx_id'),
    ('V52', 'column', 'bonus_penalties', 'student_group_id'),
    ('V52', 'column', 'cash_transactions', 'payment_id'),
    ('V52', 'column', 'cash_transactions', 'related_tx_id'),
    ('V52', 'column', 'payments', 'cancel_reason'),
    ('V52', 'column', 'payments', 'cancelled_at'),
    ('V52', 'column', 'payments', 'cancelled_by_id'),
    ('V52', 'column', 'payments', 'cash_transaction_id'),
    ('V52', 'column', 'payments', 'idempotency_hash'),
    ('V52', 'column', 'payments', 'idempotency_key'),
    ('V52', 'column', 'student_groups', 'billing_hold'),
    ('V52', 'column', 'student_groups', 'debt_since'),
    ('V52', 'column', 'student_groups', 'frozen_from'),
    ('V52', 'column', 'student_groups', 'next_payment_amount'),
    ('V52', 'column', 'students', 'debt'),
    ('V52', 'column', 'students', 'next_payment_amount'),
    ('V52', 'constraint', 'billing_periods', 'uk_billing_periods_sg_start'),
    ('V52', 'constraint', 'payments', 'uk_payments_idempotency_key'),
    ('V52', 'index', 'balance_transactions', 'idx_balance_tx_migration'),
    ('V52', 'index', 'balance_transactions', 'idx_balance_tx_related'),
    ('V52', 'index', 'balance_transactions', 'idx_balance_tx_sg_effective'),
    ('V52', 'index', 'billing_job_runs', 'idx_billing_job_runs_started'),
    ('V52', 'index', 'billing_periods', 'idx_billing_periods_migration'),
    ('V52', 'index', 'cash_transactions', 'idx_cash_transactions_payment'),
    ('V52', 'sequence', NULL, 'payment_receipt_seq'),
    ('V52', 'table', 'billing_job_runs', NULL),
    ('V52', 'table', 'billing_migration_runs', NULL),
    ('V52', 'table', 'billing_periods', NULL),
    ('V53', 'column', 'billing_periods', 'coverage_source'),
    ('V53', 'column', 'billing_periods', 'due_date'),
    ('V53', 'column', 'billing_periods', 'grace_until'),
    ('V53', 'column', 'billing_periods', 'paid_at'),
    ('V53', 'column', 'billing_periods', 'paid_on'),
    ('V53', 'column', 'billing_periods', 'paid_tx_id'),
    ('V53', 'column', 'lead_stages', 'funnel_step'),
    ('V53', 'column', 'leads', 'contacted_at'),
    ('V53', 'column', 'leads', 'converted_at'),
    ('V53', 'column', 'leads', 'rejected_at'),
    ('V53', 'column', 'leads', 'visited_at'),
    ('V53', 'column', 'student_groups', 'exit_reason_code'),
    ('V53', 'column', 'student_groups', 'trial_converted_at'),
    ('V53', 'column', 'student_groups', 'trial_outcome'),
    ('V53', 'column', 'student_groups', 'trial_source'),
    ('V53', 'column', 'student_groups', 'trial_started_at'),
    ('V53', 'constraint', NULL, 'uk_director_digest_log'),
    ('V53', 'constraint', NULL, 'uk_lesson_exceptions'),
    ('V53', 'index', 'attendance', 'idx_attendance_group_date'),
    ('V53', 'index', 'billing_periods', 'idx_billing_periods_due'),
    ('V53', 'index', 'billing_periods', 'idx_billing_periods_paid_on'),
    ('V53', 'index', 'lead_assignments', 'idx_lead_assignments_lead'),
    ('V53', 'index', 'lead_assignments', 'idx_lead_assignments_user'),
    ('V53', 'index', 'lead_comments', 'idx_lead_comments_author'),
    ('V53', 'index', 'lead_status_history', 'idx_lead_status_history_changed'),
    ('V53', 'index', 'leads', 'idx_leads_contacted_at'),
    ('V53', 'index', 'leads', 'idx_leads_converted_at'),
    ('V53', 'index', 'leads', 'idx_leads_created_at'),
    ('V53', 'index', 'leads', 'idx_leads_visited_at'),
    ('V53', 'index', 'lesson_exceptions', 'idx_lesson_exceptions_date'),
    ('V53', 'index', 'payments', 'idx_payments_received'),
    ('V53', 'index', 'payments', 'idx_payments_student_paid'),
    ('V53', 'index', 'student_groups', 'idx_student_groups_trial'),
    ('V53', 'index', 'tasks', 'idx_tasks_completed'),
    ('V53', 'table', 'director_daily_stats', NULL),
    ('V53', 'table', 'director_digest_log', NULL),
    ('V53', 'table', 'holidays', NULL),
    ('V53', 'table', 'lead_assignments', NULL),
    ('V53', 'table', 'lesson_exceptions', NULL),
    ('V54', 'column', 'balance_transactions', 'teacher_id'),
    ('V54', 'column', 'balance_transactions', 'teacher_source'),
    ('V54', 'column', 'billing_periods', 'teacher_id'),
    ('V54', 'column', 'billing_periods', 'teacher_source'),
    ('V54', 'column', 'cash_transactions', 'payroll_id'),
    ('V54', 'column', 'payroll', 'approved_at'),
    ('V54', 'column', 'payroll', 'approved_by'),
    ('V54', 'column', 'payroll', 'calc_version'),
    ('V54', 'column', 'payroll', 'cancel_reason'),
    ('V54', 'column', 'payroll', 'cancelled_at'),
    ('V54', 'column', 'payroll', 'cancelled_by'),
    ('V54', 'column', 'payroll', 'cash_transaction_id'),
    ('V54', 'column', 'payroll', 'paid_at'),
    ('V54', 'column', 'payroll', 'paid_by'),
    ('V54', 'column', 'payroll', 'pay_idempotency_key'),
    ('V54', 'index', 'balance_transactions', 'idx_balance_tx_lesson_teacher'),
    ('V54', 'index', 'billing_periods', 'idx_billing_periods_teacher_paid'),
    ('V54', 'index', 'cash_transactions', 'idx_cash_transactions_payroll'),
    ('V54', 'index', 'payroll', 'uk_payroll_user_month_year_active'),
    ('V55', 'column', 'bonus_penalties', 'user_id'),
    ('V55', 'column', 'payroll', 'paid_student_units'),
    ('V55', 'column', 'payroll', 'salary_rule_id'),
    ('V55', 'column', 'salary_rules', 'effective_to'),
    ('V55', 'index', 'bonus_penalties', 'idx_bonus_penalties_user_status'),
    ('V55', 'index', 'payroll', 'idx_payroll_paid_at'),
    ('V55', 'index', 'payroll', 'idx_payroll_salary_rule'),
    ('V56', 'constraint', 'payroll', 'fk_payroll_teacher'),
    ('V56', 'index', 'payroll', 'uk_payroll_teacher_month_year_active'),
    ('V58', 'column', 'users', 'token_version'),
    ('V58', 'index', 'exam_registrations', 'idx_exam_registrations_exam'),
    ('V58', 'index', 'exam_registrations', 'idx_exam_registrations_student'),
    ('V58', 'sequence', NULL, 'contract_number_seq'),
    ('V59', 'table', 'contract_number_counters', NULL),
    ('V60', 'table', 'notice_target_roles', NULL),
    ('V60', 'index', 'notice_target_roles', 'idx_notice_target_roles_role'),
    ('V61', 'column', 'leave_requests', 'user_id'),
    ('V61', 'column', 'leave_requests', 'paid'),
    ('V61', 'column', 'leave_requests', 'decided_by'),
    ('V61', 'column', 'leave_requests', 'decided_at'),
    ('V61', 'column', 'leave_requests', 'decision_note'),
    ('V61', 'column', 'leave_requests', 'cancelled_by'),
    ('V61', 'column', 'leave_requests', 'cancelled_at'),
    ('V61', 'column', 'salary_rules', 'substitute_lesson_rate'),
    ('V61', 'constraint', 'leave_requests', 'ck_leave_requests_dates'),
    ('V61', 'constraint', 'leave_requests', 'ck_leave_requests_paid'),
    ('V61', 'constraint', 'lesson_substitutions', 'ck_lesson_substitutions_teachers'),
    ('V61', 'constraint', 'salary_rules', 'ck_salary_rules_substitute_rate'),
    ('V61', 'index', 'leave_requests', 'idx_leave_requests_user'),
    ('V61', 'index', 'leave_requests', 'idx_leave_requests_teacher'),
    ('V61', 'index', 'lesson_substitutions', 'ux_lesson_substitutions_active'),
    ('V61', 'index', 'lesson_substitutions', 'idx_lesson_substitutions_substitute'),
    ('V61', 'index', 'lesson_substitutions', 'idx_lesson_substitutions_original'),
    ('V61', 'index', 'lesson_substitutions', 'idx_lesson_substitutions_leave'),
    ('V61', 'table', 'lesson_substitutions', NULL),
    ('V62', 'column', 'exams', 'fee'),
    ('V62', 'column', 'exam_registrations', 'cash_transaction_id'),
    ('V62', 'column', 'exam_registrations', 'refund_cash_transaction_id'),
    ('V62', 'column', 'exam_registrations', 'receipt_number'),
    ('V62', 'column', 'exam_registrations', 'idempotency_key'),
    ('V62', 'column', 'exam_registrations', 'cancelled_at'),
    ('V62', 'column', 'exam_registrations', 'cancelled_by'),
    ('V62', 'column', 'exam_registrations', 'cancel_reason'),
    ('V62', 'column', 'exam_registrations', 'created_by'),
    ('V62', 'column', 'cash_transactions', 'exam_registration_id'),
    ('V62', 'constraint', 'exams', 'ck_exams_fee_nonnegative'),
    ('V62', 'index', 'exam_registrations', 'ux_exam_registrations_active'),
    ('V62', 'index', 'cash_transactions', 'idx_cash_transactions_exam_registration'),
    ('V63', 'table', 'settings', NULL),
    ('V63', 'column', 'contracts', 'student_group_id'),
    ('V63', 'column', 'contracts', 'payment_type'),
    ('V63', 'column', 'contracts', 'list_price'),
    ('V63', 'column', 'contracts', 'discount_percent'),
    ('V63', 'column', 'contracts', 'discount_amount'),
    ('V63', 'column', 'contracts', 'final_amount'),
    ('V63', 'column', 'contracts', 'start_date'),
    ('V63', 'column', 'contracts', 'signed_at'),
    ('V63', 'column', 'contracts', 'signed_by'),
    ('V63', 'column', 'contracts', 'cancelled_at'),
    ('V63', 'column', 'contracts', 'cancelled_by'),
    ('V63', 'column', 'contracts', 'cancel_reason'),
    ('V63', 'column', 'contracts', 'pdf_file'),
    ('V63', 'column', 'contracts', 'pdf_sha256'),
    ('V63', 'index', 'contracts', 'idx_contracts_student_group')
), checked AS (
    SELECT w.*,
           CASE w.kind
             WHEN 'table'      THEN to_regclass('public.' || w.tbl) IS NOT NULL
             WHEN 'column'     THEN EXISTS (SELECT 1 FROM information_schema.columns c
                                             WHERE c.table_schema = 'public'
                                               AND c.table_name = w.tbl AND c.column_name = w.obj)
             WHEN 'index'      THEN EXISTS (SELECT 1 FROM pg_indexes i
                                             WHERE i.schemaname = 'public' AND i.indexname = w.obj)
             WHEN 'constraint' THEN EXISTS (SELECT 1 FROM pg_constraint k
                                             WHERE k.connamespace = 'public'::regnamespace
                                               AND k.conname = w.obj)
             WHEN 'sequence'   THEN EXISTS (SELECT 1 FROM pg_class s
                                             WHERE s.relkind = 'S' AND s.relname = w.obj
                                               AND s.relnamespace = 'public'::regnamespace)
           END AS present
      FROM want w
)
SELECT mig AS migratsiya,
       COUNT(*) FILTER (WHERE present) AS bor,
       COUNT(*) AS jami,
       CASE WHEN bool_and(present) THEN 'TO''LIQ' ELSE 'QISMAN' END AS holat,
       string_agg(CASE WHEN NOT present
                       THEN kind || ' ' || COALESCE(tbl || '.', '') || COALESCE(obj, '') END,
                  ', ' ORDER BY kind, tbl, obj) AS yoq_bolaklar
  FROM checked
 GROUP BY mig
 ORDER BY mig;

-- ── 2. Ma'noviy invariantlar (nomidan qat'i nazar) ───────────────────────────────────
WITH phase5_uniq AS (
    SELECT i.indrelid::regclass::text AS tbl,
           (SELECT array_agg(a.attname::text ORDER BY a.attname)
              FROM pg_attribute a
             WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)) AS cols,
           i.indpred IS NOT NULL AS partial
      FROM pg_index i
      JOIN pg_class t ON t.oid = i.indrelid AND t.relnamespace = 'public'::regnamespace
     WHERE i.indisunique AND NOT i.indisprimary
)
SELECT 'V62 exam_registrations UNIQUE(exam_id, student_id) qisman (CANCELLED dan tashqari)' AS invariant,
       EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'exam_registrations'
                 AND cols = ARRAY['exam_id','student_id'] AND partial) AS bajarilgan
UNION ALL
SELECT 'V62 exam_registrations: eski TO''LIQ UNIQUE(exam_id, student_id) qolmagan (V27/V58)',
       NOT EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'exam_registrations'
                     AND cols = ARRAY['exam_id','student_id'] AND NOT partial)
UNION ALL
SELECT 'V62 exam_registrations UNIQUE(idempotency_key)',
       EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'exam_registrations' AND cols = ARRAY['idempotency_key'])
UNION ALL
SELECT 'V41 notice_reads UNIQUE(notice_id, user_id)',
       EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'notice_reads'
                 AND cols = ARRAY['notice_id','user_id'])
UNION ALL
SELECT 'V45 conversation_participants UNIQUE(conversation_id, user_id)',
       EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'conversation_participants'
                 AND cols = ARRAY['conversation_id','user_id'])
UNION ALL
SELECT 'V50 teachers UNIQUE(user_id)',
       EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'teachers' AND cols = ARRAY['user_id'])
UNION ALL
SELECT 'V52 payments UNIQUE(idempotency_key)',
       EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'payments' AND cols = ARRAY['idempotency_key'])
UNION ALL
SELECT 'V52 billing_periods UNIQUE(student_group_id, period_start)',
       EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'billing_periods'
                 AND cols = ARRAY['period_start','student_group_id'])
UNION ALL
SELECT 'V54 payroll UNIQUE(user_id, month, year) qisman (CANCELLED dan tashqari)',
       EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'payroll'
                 AND cols = ARRAY['month','user_id','year'] AND partial)
UNION ALL
SELECT 'V56 payroll: eski TO''LIQ UNIQUE(teacher/user, month, year) qolmagan',
       NOT EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'payroll'
                     AND cols IN (ARRAY['month','teacher_id','year'], ARRAY['month','user_id','year'])
                     AND NOT partial)
UNION ALL
SELECT 'V56 payroll.teacher_id FK CASCADE emas',
       NOT EXISTS (SELECT 1 FROM pg_constraint c
                     JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                    WHERE c.conrelid = to_regclass('public.payroll') AND c.contype = 'f'
                      AND a.attname = 'teacher_id' AND c.confdeltype = 'c')
UNION ALL
SELECT 'V58 users.token_version NOT NULL DEFAULT 0',
       EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users'
                  AND column_name = 'token_version' AND is_nullable = 'NO'
                  AND column_default = '0')
UNION ALL
SELECT 'V58 contract_number_seq mavjud',
       to_regclass('public.contract_number_seq') IS NOT NULL
UNION ALL
SELECT 'V61 leave_requests.user_id NOT NULL (backfill tugagan)',
       EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'leave_requests'
                  AND column_name = 'user_id' AND is_nullable = 'NO')
UNION ALL
SELECT 'V61 lesson_substitutions UNIQUE(group_id, lesson_date) qisman',
       EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'lesson_substitutions'
                 AND cols = ARRAY['group_id','lesson_date'] AND partial)
UNION ALL
SELECT 'V61 ta''til kesishuvi EXCLUDE (ixtiyoriy, btree_gist)',
       EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ex_leave_requests_overlap')
UNION ALL
SELECT 'V62 exams.fee NOT NULL DEFAULT 0',
       EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'exams'
                  AND column_name = 'fee' AND is_nullable = 'NO' AND column_default = '0')
UNION ALL
SELECT 'V63 settings UNIQUE(setting_key)',
       EXISTS (SELECT 1 FROM phase5_uniq WHERE tbl = 'settings' AND cols = ARRAY['setting_key'])
UNION ALL
-- settings jadvali V63 dan oldin bo'lmasligi mumkin — so'rov dinamik (yo'q jadvalga murojaat skriptni to'xtatmasin)
SELECT 'V63 center.* rekvizitlari (13 kalit)',
       CASE WHEN to_regclass('public.settings') IS NULL THEN FALSE
            ELSE (xpath('/row/n/text()', query_to_xml(
                     'SELECT COUNT(*) AS n FROM settings WHERE setting_key LIKE ''center.%''', false, true, '')))[1]::text::int = 13
       END
UNION ALL
-- V64: teachers.user_id → users da aynan bitta FK — fk_teachers_user, NO ACTION (SET NULL dublikati yo'q)
SELECT 'V64 teachers.user_id: yagona FK fk_teachers_user [NO ACTION]',
       (SELECT COUNT(*) = 1 AND bool_and(c.conname = 'fk_teachers_user' AND c.confdeltype = 'a')
          FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = to_regclass('public.teachers') AND c.contype = 'f'
           AND a.attname = 'user_id');

-- V58: sequence mavjud CTR-YYYY-NNNNN raqamlaridan oldindami (sequence bo'lsa)
SELECT 'contract_number_seq' AS sequence,
       COALESCE((SELECT MAX(SUBSTRING(contract_number FROM 10)::BIGINT)
                   FROM contracts WHERE contract_number ~ '^CTR-[0-9]{4}-[0-9]+$'), 0) AS max_raqam,
       (SELECT s.last_value FROM pg_sequences s
         WHERE s.schemaname = 'public' AND s.sequencename = 'contract_number_seq') AS sequence_oxirgi;

-- ── 3. Dublikat FK lar ───────────────────────────────────────────────────────────────
-- Bir ustunda bir nechta FK: eski skript (CASCADE / SET NULL) + Hibernate (NO ACTION).
-- Amalda eng qattiqi (NO ACTION) ishlaydi — migratsiyada yozilgan ON DELETE bajarilmaydi.
-- confdeltype: a = NO ACTION, r = RESTRICT, c = CASCADE, n = SET NULL, d = SET DEFAULT.
SELECT c.conrelid::regclass AS jadval,
       (SELECT string_agg(a.attname, ',' ORDER BY a.attnum)
          FROM pg_attribute a WHERE a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)) AS ustun,
       c.confrelid::regclass AS havola,
       COUNT(*) AS fk_soni,
       string_agg(c.conname || ' [' || c.confdeltype::text || ']', ', ' ORDER BY c.conname) AS fk_lar
  FROM pg_constraint c
 WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
 GROUP BY c.conrelid, c.conkey, c.confrelid
HAVING COUNT(*) > 1
 ORDER BY 1, 2;

-- ── 4. CHECK cheklovlari ─────────────────────────────────────────────────────────────
-- "= ANY (ARRAY[...])" — Hibernate enum CHECK i. app.schema.drop-enum-checks=true bo'lsa
-- ilova ularni startda o'chiradi; bu yerda qolgani — yangi enum qiymatini rad etadi.
SELECT c.conrelid::regclass AS jadval, c.conname AS cheklov,
       pg_get_constraintdef(c.oid) AS tarif,
       pg_get_constraintdef(c.oid) LIKE '%= ANY (ARRAY[%' AS enum_check
  FROM pg_constraint c
 WHERE c.contype = 'c' AND c.connamespace = 'public'::regnamespace
 ORDER BY 1, 2;

-- ── 5. Dublikat UNIQUE va oddiy indekslar ───────────────────────────────────────────
-- Bir jadvalda bir xil ustunlar (va bir xil qisman shart) bo'yicha bir nechta indeks —
-- har INSERT/UPDATE da ortiqcha ish. PK hisobga olinmaydi.
WITH idx AS (
    SELECT i.indrelid,
           i.indexrelid::regclass::text AS name,
           i.indisunique,
           (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
              FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
              JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum) AS cols,
           COALESCE(pg_get_expr(i.indpred, i.indrelid), '') AS pred,
           COALESCE(pg_get_expr(i.indexprs, i.indrelid), '') AS exprs
      FROM pg_index i
      JOIN pg_class t ON t.oid = i.indrelid AND t.relnamespace = 'public'::regnamespace
     WHERE NOT i.indisprimary
)
SELECT indrelid::regclass AS jadval, cols AS ustunlar, indisunique AS is_unique,
       COUNT(*) AS soni, string_agg(name, ', ' ORDER BY name) AS indekslar
  FROM idx
 GROUP BY indrelid, cols, indisunique, pred, exprs
HAVING COUNT(*) > 1
 ORDER BY 1, 2;

-- ── 6. flyway_schema_history ─────────────────────────────────────────────────────────
-- Flyway hozir ishlatilmaydi; jadval qolgan bo'lsa — qaysi versiyagacha avtomatik
-- bajarilgani ko'rinadi (undan keyingilari qo'lda).
SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL
            THEN 'flyway_schema_history yo''q'
            ELSE 'flyway_schema_history bor — oxirgi versiya: '
                 || (xpath('/row/v/text()', query_to_xml(
                        'SELECT max(version::int) AS v FROM flyway_schema_history WHERE success',
                        false, true, '')))[1]::text
       END AS flyway;

ROLLBACK;

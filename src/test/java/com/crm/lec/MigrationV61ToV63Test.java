package com.crm.lec;

import com.crm.entity.Exam;
import com.crm.repository.ExamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V61–V63 (§7, §8): faqat PostgreSQL ({@code -Dspring.profiles.active=pgtest}). Har skript ikki marta
 * bajariladi — idempotentlik; eski ko'rinishdagi ma'lumot normallashadi; qo'lda o'zgartirilgan
 * rekvizit ustidan yozilmaydi.
 */
class MigrationV61ToV63Test extends LecItBase {

    private static final String[] SCRIPTS = {
        "db/migration/V61__leaves_substitutions.sql",
        "db/migration/V62__exam_fee.sql",
        "db/migration/V63__settings_contract_snapshot.sql"};

    @Autowired ExamRepository examRepository;

    @AfterEach
    void restoreSettings() {
        if (isPostgres()) {
            seedCenter();
        }
    }

    private void runScript(String path) {
        jdbc.execute((ConnectionCallback<Void>) c -> {
            try {
                ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource(path)),
                    false, false, ScriptUtils.DEFAULT_COMMENT_PREFIX, ScriptUtils.EOF_STATEMENT_SEPARATOR,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER, ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
            } catch (RuntimeException e) {
                // Skript o'z BEGIN i ichida yiqildi — ulanish "aborted" holatda pulga qaytmasin
                try (var st = c.createStatement()) {
                    st.execute("ROLLBACK");
                }
                throw e;
            }
            return null;
        });
    }

    private int uniqueIndexes(String table, String columns) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM pg_indexes
            WHERE tablename = ? AND indexdef LIKE 'CREATE UNIQUE INDEX%(' || ? || ')%'
            """, Integer.class, table, columns);
    }

    @Test
    void v61_v62_v63_areIdempotent_andNormalizeLegacyData() {
        Assumptions.assumeTrue(isPostgres(), "V61–V63 — PostgreSQL skriptlari (pgtest)");

        // ── eski ko'rinishdagi ta'tillar ──
        TeacherUser t = newTeacher();
        Long requester = newUser(com.crm.entity.enums.UserRole.ADMIN).getId();
        jdbc.execute("ALTER TABLE leave_requests ALTER COLUMN user_id DROP NOT NULL");
        jdbc.update("""
            INSERT INTO leave_requests (uuid, created_at, teacher_id, requester_id, leave_type, from_date, to_date, status, reason)
            VALUES (gen_random_uuid(), CURRENT_TIMESTAMP, ?, ?, 'vacation', DATE '2026-08-03', DATE '2026-08-07', 'approved', 'Dam olish')
            """, t.teacherId(), requester);
        jdbc.update("""
            INSERT INTO leave_requests (uuid, created_at, requester_id, leave_type, from_date, to_date, status, reason)
            VALUES (gen_random_uuid(), CURRENT_TIMESTAMP, ?, 'Kasallik', DATE '2026-08-10', DATE '2026-08-11', 'REJECTED',
                    'Isitma' || chr(10) || '[Rad etish] Hujjat yo''q')
            """, requester);

        // ── eski imtihon yozilishlari ──
        Long examId = inTx(() -> examRepository.save(Exam.builder().examName("Eski").examDate(LocalDate.of(2026, 9, 20))
            .fee(BigDecimal.ZERO).isActive(true).build()).getId());
        Long s1 = fixtures.student();
        Long s2 = fixtures.student();
        jdbc.update("""
            INSERT INTO exam_registrations (exam_id, student_id, payment_status, amount_due, amount_paid, status)
            VALUES (?, ?, 'PENDING', 50000, 0, 'registered')
            """, examId, s1);
        jdbc.update("""
            INSERT INTO exam_registrations (exam_id, student_id, payment_status, amount_due, amount_paid, status)
            VALUES (?, ?, 'PAID', 0, 0, 'REGISTERED')
            """, examId, s2);

        // ── rekvizitlar: biri o'chirilgan, biri qo'lda o'zgartirilgan ──
        jdbc.update("DELETE FROM settings WHERE setting_key = 'center.inn'");
        jdbc.update("UPDATE settings SET setting_value = '+998 71 000 00 00' WHERE setting_key = 'center.phone'");

        for (int i = 0; i < 2; i++) {
            for (String script : SCRIPTS) {
                runScript(script);
            }
        }

        // V61: user_id backfill (o'qituvchi → uning useri, aks holda requester), normallashtirish, paid
        Map<String, Object> approved = jdbc.queryForMap(
            "SELECT user_id, status, leave_type, paid FROM leave_requests WHERE teacher_id = ?", t.teacherId());
        assertThat(((Number) approved.get("user_id")).longValue()).isEqualTo(t.user().getId());
        assertThat(approved.get("status")).isEqualTo("APPROVED");
        assertThat(approved.get("leave_type")).isEqualTo("ANNUAL");
        assertThat(approved.get("paid")).isEqualTo(true);
        Map<String, Object> rejected = jdbc.queryForMap(
            "SELECT user_id, reason, decision_note, paid FROM leave_requests WHERE teacher_id IS NULL");
        assertThat(((Number) rejected.get("user_id")).longValue()).isEqualTo(requester);
        assertThat(rejected.get("reason")).isEqualTo("Isitma");
        assertThat(rejected.get("decision_note")).isEqualTo("Hujjat yo'q");
        assertThat(rejected.get("paid")).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT is_nullable FROM information_schema.columns
            WHERE table_name = 'leave_requests' AND column_name = 'user_id'
            """, String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_constraint WHERE conname IN "
            + "('ck_leave_requests_dates', 'ck_leave_requests_paid', 'ck_lesson_substitutions_teachers')", Integer.class))
            .isEqualTo(3);
        assertThat(uniqueIndexes("lesson_substitutions", "group_id, lesson_date")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_name = 'salary_rules' AND column_name = 'substitute_lesson_rate'
            """, Integer.class)).isEqualTo(1);

        // V62: fee NOT NULL DEFAULT 0, holatlar, qisman UNIQUE (bitta), kassa havolasi
        assertThat(jdbc.queryForObject("""
            SELECT is_nullable || ':' || column_default FROM information_schema.columns
            WHERE table_name = 'exams' AND column_name = 'fee'
            """, String.class)).isEqualTo("NO:0");
        assertThat(jdbc.queryForList("SELECT status || '/' || payment_status FROM exam_registrations WHERE exam_id = ?"
            + " ORDER BY id", String.class, examId)).containsExactly("REGISTERED/FREE", "REGISTERED/FREE");
        assertThat(uniqueIndexes("exam_registrations", "exam_id, student_id")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_exam_registrations_active'",
            String.class)).contains("CANCELLED");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_name = 'cash_transactions' AND column_name = 'exam_registration_id'
            """, Integer.class)).isEqualTo(1);
        // V58 qayta bajarilsa ham to'liq UNIQUE qaytmaydi (qayta yozilish ishlashi uchun)
        runScript("db/migration/V58__phase5_security.sql");
        assertThat(uniqueIndexes("exam_registrations", "exam_id, student_id")).isEqualTo(1);

        // V63: o'chirilgan kalit qayta qo'shildi, qo'lda o'zgartirilgani saqlandi, dublikat yo'q
        assertThat(jdbc.queryForObject("SELECT setting_value FROM settings WHERE setting_key = 'center.inn'", String.class))
            .isEqualTo("311626069");
        assertThat(jdbc.queryForObject("SELECT setting_value FROM settings WHERE setting_key = 'center.phone'", String.class))
            .isEqualTo("+998 71 000 00 00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settings WHERE setting_key LIKE 'center.%'", Integer.class))
            .isEqualTo(13);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_name = 'contracts' AND column_name IN ('student_group_id', 'final_amount', 'pdf_sha256', 'signed_at')
            """, Integer.class)).isEqualTo(4);
    }
}

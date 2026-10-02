package com.crm.payroll;

import com.crm.dto.response.PayrollGenerateResult;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.entity.Teacher;
import com.crm.entity.enums.PayrollStatus;
import com.crm.entity.enums.UserRole;
import com.crm.service.TeacherProfileSyncService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * payroll-v2 §12: eski sxema cheklovlari (BUG 1, 2) va o'qituvchi profili bog'lanishi (BUG 3).
 *
 * <p>pgtest sxemasi entity'dan quriladi — eski qo'lda migratsiyalardagi cheklovlar unda yo'q. Shuning
 * uchun ular test ichida QO'LDA yaratiladi (lokal {@code adizone} bazasidagi nomlar bilan) va
 * {@code finally} da olib tashlanadi.
 */
class LegacySchemaAndTeacherLinkTest extends PayrollItBase {

    private static final String LEGACY_PAYROLL_KEY = "payroll_teacher_id_month_year_key";
    private static final String LEGACY_PAYROLL_KEY_HIBERNATE = "uk5duwev7ya2dq0c0q71wfbloyu";
    private static final String LEGACY_SG_KEY = "student_groups_student_id_group_id_join_date_key";

    @Autowired TeacherProfileSyncService profileSync;

    private Staff paidTeacher() {
        Staff t = teacherStaff();
        teacherRule(t, 3_000_000, 100_000);
        pay(monthly(t.teacherId(), 700_000, "15.09.2026"), 700_000, "15.09.2026");
        return t;
    }

    /** Sentyabr oyligi to'lanib, keyin bekor qilingan o'qituvchi. */
    private Staff teacherWithCancelledPayroll() {
        Staff t = paidTeacher();
        Long id = draftFor(t.userId(), SEP).getId();
        payroll.approve(id, null);
        payroll.markAsPaid(id, null, null);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payroll.cancel(id, "Qayta hisoblash");
        return t;
    }

    // ── BUG 1: bitta xodimdagi baza xatosi butun generate ni 500 qilmasin ──

    @Test
    void generate_legacyUniqueBlocksOnlyThatStaff_otherStaffSaved_no500() throws Exception {
        Staff cancelled = teacherWithCancelledPayroll();
        Staff fresh = paidTeacher();
        jdbc.execute("ALTER TABLE payroll ADD CONSTRAINT " + LEGACY_PAYROLL_KEY + " UNIQUE (teacher_id, month, year)");
        try {
            fixtures.loginAs(UserRole.SUPER_ADMIN);
            mvc.perform(post("/api/payroll/generate").param("month", "9").param("year", "2026")
                    .with(user("test-super_admin").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(1));

            assertThat(payrollOf(fresh.userId(), SEP).getStatus()).isEqualTo(PayrollStatus.DRAFT);
            assertThat(inTx(() -> payrollRepo.findActive(cancelled.userId(), SEP, YEAR))).isEmpty();
            PayrollGenerateResult again = payroll.generatePayroll(SEP, YEAR, false);
            assertThat(again.skipped()).filteredOn(s -> cancelled.userId().equals(s.userId()))
                .singleElement().satisfies(s -> {
                    assertThat(s.reason()).isEqualTo("ERROR");
                    assertThat(s.code()).isEqualTo("DB_CONSTRAINT");
                    assertThat(s.message()).isNotBlank();
                });
        } finally {
            jdbc.execute("ALTER TABLE payroll DROP CONSTRAINT IF EXISTS " + LEGACY_PAYROLL_KEY);
        }
        // Cheklovsiz (V56 dan keyingi holat) — bekor qilingan oy uchun yangi DRAFT
        assertThat(payroll.generatePayroll(SEP, YEAR, false).created()).isEqualTo(1);
        assertThat(payrollOf(cancelled.userId(), SEP).getStatus()).isEqualTo(PayrollStatus.DRAFT);
    }

    // ── BUG 1 + 2: V56 eski cheklovlarni olib tashlaydi (PostgreSQL) ────

    @Test
    void v56_dropsLegacyConstraints_cancelThenGenerateGivesNewDraft_isIdempotent() {
        Assumptions.assumeTrue(isPostgres(), "V56 — PostgreSQL skripti (pgtest)");
        Staff t = teacherWithCancelledPayroll();
        try {
            // Lokal adizone bazasidagi holat: bir xil cheklov ikki nom bilan + CASCADE FK + eski SG kaliti
            jdbc.execute("ALTER TABLE payroll ADD CONSTRAINT " + LEGACY_PAYROLL_KEY + " UNIQUE (teacher_id, month, year)");
            jdbc.execute("ALTER TABLE payroll ADD CONSTRAINT " + LEGACY_PAYROLL_KEY_HIBERNATE
                + " UNIQUE (teacher_id, month, year)");
            jdbc.execute("ALTER TABLE payroll ADD CONSTRAINT payroll_teacher_id_fkey FOREIGN KEY (teacher_id)"
                + " REFERENCES teachers(id) ON DELETE CASCADE");
            jdbc.execute("ALTER TABLE student_groups ADD CONSTRAINT " + LEGACY_SG_KEY
                + " UNIQUE (student_id, group_id, join_date)");
            fixtures.loginAs(UserRole.SUPER_ADMIN);
            assertThat(payroll.generatePayroll(SEP, YEAR, false).skipped())
                .anyMatch(s -> t.userId().equals(s.userId()) && "ERROR".equals(s.reason()));

            runScript("db/migration/V56__legacy_constraints.sql");
            runScript("db/migration/V56__legacy_constraints.sql");

            assertThat(constraintNames()).doesNotContain(LEGACY_PAYROLL_KEY, LEGACY_PAYROLL_KEY_HIBERNATE,
                "payroll_teacher_id_fkey", LEGACY_SG_KEY);
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE conrelid = 'payroll'::regclass AND contype = 'f' AND confdeltype = 'c'
                """, Integer.class)).isZero();
            assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename IN ('payroll','student_groups')",
                String.class)).contains("uk_payroll_teacher_month_year_active");

            assertThat(payroll.generatePayroll(SEP, YEAR, false).created()).isEqualTo(1);
            assertThat(payrollOf(t.userId(), SEP).getStatus()).isEqualTo(PayrollStatus.DRAFT);
        } finally {
            jdbc.execute("ALTER TABLE payroll DROP CONSTRAINT IF EXISTS " + LEGACY_PAYROLL_KEY);
            jdbc.execute("ALTER TABLE payroll DROP CONSTRAINT IF EXISTS " + LEGACY_PAYROLL_KEY_HIBERNATE);
            jdbc.execute("ALTER TABLE payroll DROP CONSTRAINT IF EXISTS payroll_teacher_id_fkey");
            jdbc.execute("ALTER TABLE student_groups DROP CONSTRAINT IF EXISTS " + LEGACY_SG_KEY);
        }
    }

    @Test
    void v56_activePartialIndex_stillForbidsTwoActivePayrollsForSameTeacherMonth() {
        Assumptions.assumeTrue(isPostgres(), "qisman indeks — PostgreSQL (pgtest)");
        Staff t = paidTeacher();
        Long id = draftFor(t.userId(), SEP).getId();
        Integer dup = null;
        try {
            jdbc.update("""
                INSERT INTO payroll (uuid, teacher_id, month, year, status, created_at)
                VALUES (gen_random_uuid(), ?, 9, 2026, 'DRAFT', now())
                """, t.teacherId());
            dup = 1;
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            // uk_payroll_teacher_month_year_active
        }
        assertThat(dup).as("faol ikkinchi oylik rad etilishi kerak").isNull();
        // CANCELLED bo'lsa — ruxsat (tarix)
        jdbc.update("""
            INSERT INTO payroll (uuid, teacher_id, month, year, status, created_at)
            VALUES (gen_random_uuid(), ?, 9, 2026, 'CANCELLED', now())
            """, t.teacherId());
        assertThat(payroll(id).getStatus()).isEqualTo(PayrollStatus.DRAFT);
    }

    // ── BUG 3: profili yo'q TEACHER userlar ───────────────────────────────

    @Test
    void repair_createsMissingProfiles_linksOrphans_reportsReasons() {
        Long noProfile = staffUser(UserRole.TEACHER);
        rule(noProfile, UserRole.TEACHER, 2_000_000, 0, 0, null, 0);
        assertThat(calculator.calculateForUser(noProfile, SEP, YEAR).getMessageCode())
            .isEqualTo("TEACHER_PROFILE_MISSING");

        Long matchedUser = staffUser(UserRole.TEACHER);
        Long orphanMatched = fixtures.teacher();
        jdbc.update("UPDATE users SET phone = '+998911234567' WHERE id = ?", matchedUser);
        jdbc.update("UPDATE teachers SET phone = '+998911234567' WHERE id = ?", orphanMatched);
        Long orphanNoMatch = fixtures.teacher();

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        Map<String, Object> result = profileSync.repairTeacherLinks();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

        assertThat(items).filteredOn(i -> noProfile.equals(i.get("userId")))
            .singleElement().satisfies(i -> {
                assertThat(i.get("action")).isEqualTo("CREATED");
                assertThat(i.get("teacherId")).isNotNull();
            });
        assertThat(items).filteredOn(i -> orphanMatched.equals(i.get("teacherId")))
            .singleElement().satisfies(i -> {
                assertThat(i.get("action")).isEqualTo("LINKED");
                assertThat(i.get("userId")).isEqualTo(matchedUser);
            });
        assertThat(items).filteredOn(i -> orphanNoMatch.equals(i.get("teacherId")))
            .singleElement().satisfies(i -> {
                assertThat(i.get("action")).isEqualTo("SKIPPED");
                assertThat((String) i.get("reason")).contains("topilmadi");
            });
        assertThat((Integer) result.get("createdCount")).isGreaterThanOrEqualTo(1);
        assertThat(result.get("usersWithoutProfile")).isEqualTo(0L);
        assertThat(result.get("remainingUnlinked")).isEqualTo(1L);

        // Endi oylik hisoblanadi
        SalaryCalculationDto calc = calculator.calculateForUser(noProfile, SEP, YEAR);
        assertThat(calc.getCalculable()).isTrue();
        assertThat(calc.getTotalAmount()).isEqualByComparingTo("2000000");
        assertThat(inTx(() -> teacherRepo.findByUser_Id(matchedUser)).map(Teacher::getId)).contains(orphanMatched);
    }

    @Test
    void notCalculable_distinguishesMissingProfile_fromProfileOfAnotherUser() {
        Long u1 = staffUser(UserRole.TEACHER);
        rule(u1, UserRole.TEACHER, 1_000_000, 0, 0, null, 0);
        jdbc.update("UPDATE users SET phone = '+998977777777' WHERE id = ?", u1);

        SalaryCalculationDto missing = calculator.calculateForUser(u1, SEP, YEAR);
        assertThat(missing.getMessageCode()).isEqualTo("TEACHER_PROFILE_MISSING");
        assertThat(missing.getMessage()).startsWith("O'qituvchi profili yo'q");

        Staff other = teacherStaff();
        jdbc.update("UPDATE teachers SET phone = '+998977777777' WHERE id = ?", other.teacherId());
        SalaryCalculationDto taken = calculator.calculateForUser(u1, SEP, YEAR);
        assertThat(taken.getMessageCode()).isEqualTo("TEACHER_PROFILE_LINKED_TO_OTHER_USER");
        assertThat(taken.getMessage()).contains("boshqa userga bog'langan")
            .contains("#" + other.teacherId()).contains("#" + other.userId());

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertThat(payroll.generatePayroll(SEP, YEAR, false).skipped())
            .filteredOn(s -> u1.equals(s.userId())).singleElement().satisfies(s -> {
                assertThat(s.reason()).isEqualTo("NOT_CALCULABLE");
                assertThat(s.code()).isEqualTo("TEACHER_PROFILE_LINKED_TO_OTHER_USER");
            });
    }

    private List<String> constraintNames() {
        return jdbc.queryForList("""
            SELECT conname FROM pg_constraint
            WHERE conrelid IN ('payroll'::regclass, 'student_groups'::regclass)
            """, String.class);
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    private void runScript(String path) {
        jdbc.execute((ConnectionCallback<Void>) c -> {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource(path)),
                false, false, ScriptUtils.DEFAULT_COMMENT_PREFIX, ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER, ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
            return null;
        });
    }
}

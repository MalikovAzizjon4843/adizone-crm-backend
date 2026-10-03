package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.FreezeStudentRequest;
import com.crm.dto.request.StudentGroupRequest;
import com.crm.dto.request.TransferGroupRequest;
import com.crm.dto.request.UnfreezeStudentRequest;
import com.crm.entity.StudentGroup;
import com.crm.entity.StudentStatusHistory;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.StudentStatusHistoryRepository;
import com.crm.service.GroupService;
import com.crm.service.StudentService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** O'quvchi holati yozilmalardan (StudentStatusService) — barcha yo'llar va V73 tuzatish skripti. */
class StudentStatusSyncTest extends AbstractBillingIT {

    @Autowired GroupService groups;
    @Autowired StudentService students;
    @Autowired StudentRepository studentRepo;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired StudentStatusHistoryRepository historyRepo;
    @Autowired JdbcTemplate jdbc;

    private Long newGroup() {
        return fixtures.group(fixtures.course(700_000));
    }

    private Long enroll(Long student, Long group) {
        return fixtures.enrollment(student, group).start(d("01.09.2026")).save();
    }

    private StudentStatus status(Long student) {
        return inTx(() -> studentRepo.findById(student).orElseThrow().getStatus());
    }

    /** Eng oxirgi tarix qatori (id bo'yicha — test soati qotgan, changed_at teng bo'lishi mumkin). */
    private StudentStatusHistory lastHistory(Long student) {
        return inTx(() -> historyRepo.findByStudent_IdOrderByChangedAtDesc(student).stream()
            .max(java.util.Comparator.comparing(StudentStatusHistory::getId)).orElseThrow());
    }

    private void remove(Long student, Long group, String reason, ExitReasonCode code) {
        clock.setDate(d("10.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        groups.removeStudentFromGroup(group, student, reason, null, code);
    }

    private void freeze(Long student, Long group, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ADMIN);
        FreezeStudentRequest r = new FreezeStudentRequest();
        r.setGroupId(group);
        students.freezeStudent(student, r);
    }

    // ── yopish yo'llari ─────────────────────────────────────────────────

    @Test
    void deleteEndpoint_withoutReason_lastEnrollment_LEFT_withHistory() {
        Long s = fixtures.student();
        Long g = newGroup();
        enroll(s, g);
        clock.setDate(d("10.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        groups.removeStudentFromGroup(s, g);     // DELETE /groups/{g}/students/{s}

        assertThat(status(s)).isEqualTo(StudentStatus.LEFT);
        assertThat(lastHistory(s)).satisfies(h -> {
            assertThat(h.getFromStatus()).isEqualTo("ACTIVE");
            assertThat(h.getToStatus()).isEqualTo("LEFT");
        });
    }

    @Test
    void reasonOnlyCode_or_OTHER_alsoLEFT() {
        Long s = fixtures.student();
        Long g = newGroup();
        enroll(s, g);
        remove(s, g, null, ExitReasonCode.PRICE);
        assertThat(status(s)).isEqualTo(StudentStatus.LEFT);

        Long t = fixtures.student();
        Long g2 = newGroup();
        enroll(t, g2);
        remove(t, g2, "OTHER", null);
        assertThat(status(t)).isEqualTo(StudentStatus.LEFT);
    }

    @Test
    void leaveOneOfTwo_staysACTIVE_lastOneGraduated_GRADUATED() {
        Long s = fixtures.student();
        Long a = newGroup();
        Long b = newGroup();
        enroll(s, a);
        enroll(s, b);

        remove(s, a, "LEFT", null);
        assertThat(status(s)).as("boshqa guruhda o'qiydi").isEqualTo(StudentStatus.ACTIVE);

        remove(s, b, "GRADUATED", null);
        assertThat(status(s)).isEqualTo(StudentStatus.GRADUATED);
    }

    @Test
    void suspendedReason_SUSPENDED() {
        Long s = fixtures.student();
        Long g = newGroup();
        enroll(s, g);
        remove(s, g, "SUSPENDED", null);
        assertThat(status(s)).isEqualTo(StudentStatus.SUSPENDED);
    }

    // ── ochish yo'llari ─────────────────────────────────────────────────

    @Test
    void reEnrollingLeftStudent_ACTIVE_withHistory() {
        Long s = fixtures.student();
        Long g = newGroup();
        enroll(s, g);
        remove(s, g, "LEFT", null);
        assertThat(status(s)).isEqualTo(StudentStatus.LEFT);

        clock.setDate(d("20.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        StudentGroupRequest r = new StudentGroupRequest();
        r.setStudentId(s);
        r.setGroupId(newGroup());
        groups.addStudentToGroup(r);

        assertThat(status(s)).isEqualTo(StudentStatus.ACTIVE);
        assertThat(lastHistory(s)).satisfies(h -> {
            assertThat(h.getFromStatus()).isEqualTo("LEFT");
            assertThat(h.getToStatus()).isEqualTo("ACTIVE");
            assertThat(h.getReason()).isEqualTo("ENROLLED");
        });
    }

    @Test
    void transfer_keepsACTIVE_oldEnrollmentClosedAsTransferred() {
        Long s = fixtures.student();
        Long from = newGroup();
        Long to = newGroup();
        Long sg = enroll(s, from);
        clock.setDate(d("10.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        TransferGroupRequest r = new TransferGroupRequest();
        r.setFromGroupId(from);
        r.setToGroupId(to);
        students.transferGroup(s, r);

        assertThat(status(s)).isEqualTo(StudentStatus.ACTIVE);
        StudentGroup old = inTx(() -> sgRepo.findById(sg).orElseThrow());
        assertThat(old.getIsActive()).isFalse();
        assertThat(old.getExitReasonCode()).isEqualTo(ExitReasonCode.TRANSFERRED);
    }

    // ── muzlatish (mavjud mantiq) ───────────────────────────────────────

    @Test
    void freezeOneOfTwo_ACTIVE_freezeBoth_FROZEN_unfreeze_ACTIVE() {
        Long s = fixtures.student();
        Long a = newGroup();
        Long b = newGroup();
        enroll(s, a);
        enroll(s, b);
        freeze(s, a, "10.09.2026");
        assertThat(status(s)).isEqualTo(StudentStatus.ACTIVE);
        freeze(s, b, "10.09.2026");
        assertThat(status(s)).isEqualTo(StudentStatus.FROZEN);
        // tarix: oxirgi muzlatish ACTIVE → FROZEN (ilgari FROZEN → FROZEN yozilardi)
        assertThat(lastHistory(s)).satisfies(h -> {
            assertThat(h.getFromStatus()).isEqualTo("ACTIVE");
            assertThat(h.getToStatus()).isEqualTo("FROZEN");
        });

        clock.setDate(d("20.09.2026"));
        UnfreezeStudentRequest u = new UnfreezeStudentRequest();
        u.setGroupId(a);
        u.setPaymentStartDate(d("20.09.2026"));
        students.unfreezeStudent(s, u);
        assertThat(status(s)).isEqualTo(StudentStatus.ACTIVE);
    }

    // ── o'quvchini o'chirish ────────────────────────────────────────────

    @Test
    void deleteStudent_closesActiveAndFrozen_LEFT_noOpenEnrollmentLeft() {
        Long s = fixtures.student();
        Long a = newGroup();
        Long b = newGroup();
        enroll(s, a);
        Long frozenSg = enroll(s, b);
        freeze(s, b, "10.09.2026");

        clock.setDate(d("12.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        students.deleteStudent(s);

        assertThat(status(s)).isEqualTo(StudentStatus.LEFT);
        List<StudentGroup> all = inTx(() -> sgRepo.findByStudentId(s));
        assertThat(all).allSatisfy(sg -> {
            assertThat(sg.getIsActive()).isFalse();
            assertThat(sg.getFrozenFrom()).isNull();
        });
        StudentGroup wasFrozen = all.stream().filter(sg -> sg.getId().equals(frozenSg)).findFirst().orElseThrow();
        assertThat(wasFrozen.getLeaveDate()).isEqualTo(d("10.09.2026"));
        assertThat(wasFrozen.getExitReason()).isEqualTo("LEFT");

        // Hech qachon yozilmagan o'quvchi — o'chirish baribir LEFT
        Long lonely = fixtures.student();
        students.deleteStudent(lonely);
        assertThat(status(lonely)).isEqualTo(StudentStatus.LEFT);
    }

    // ── sof qoida ───────────────────────────────────────────────────────

    @Test
    void derive_rules() {
        StudentGroup active = StudentGroup.builder().id(1L).isActive(true).build();
        StudentGroup frozen = StudentGroup.builder().id(2L).isActive(false).frozenFrom(LocalDate.of(2026, 9, 1)).build();
        StudentGroup legacyFrozen = StudentGroup.builder().id(3L).isActive(false).exitReason("FROZEN").build();
        StudentGroup leftEarly = StudentGroup.builder().id(4L).isActive(false)
            .leaveDate(LocalDate.of(2026, 9, 1)).exitReasonCode(ExitReasonCode.GRADUATED).build();
        StudentGroup leftLate = StudentGroup.builder().id(5L).isActive(false)
            .leaveDate(LocalDate.of(2026, 9, 5)).exitReason("LEFT").exitReasonCode(ExitReasonCode.OTHER).build();

        assertThat(StudentStatusService.derive(StudentStatus.LEFT, List.of(active, leftLate))).isEqualTo(StudentStatus.ACTIVE);
        assertThat(StudentStatusService.derive(StudentStatus.ARCHIVED, List.of(active))).isEqualTo(StudentStatus.ACTIVE);
        assertThat(StudentStatusService.derive(StudentStatus.ACTIVE, List.of(frozen, leftLate))).isEqualTo(StudentStatus.FROZEN);
        assertThat(StudentStatusService.derive(StudentStatus.ACTIVE, List.of(legacyFrozen))).isEqualTo(StudentStatus.FROZEN);
        // oxirgi yopilgani hal qiladi
        assertThat(StudentStatusService.derive(StudentStatus.ACTIVE, List.of(leftEarly, leftLate))).isEqualTo(StudentStatus.LEFT);
        assertThat(StudentStatusService.derive(StudentStatus.ACTIVE, List.of(leftEarly))).isEqualTo(StudentStatus.GRADUATED);
        // admin holati ochiq yozilma bo'lmasa saqlanadi; yozilmasiz o'quvchiga tegilmaydi
        assertThat(StudentStatusService.derive(StudentStatus.ARCHIVED, List.of(leftLate))).isEqualTo(StudentStatus.ARCHIVED);
        assertThat(StudentStatusService.derive(StudentStatus.FINISHED, List.of(leftLate))).isEqualTo(StudentStatus.FINISHED);
        assertThat(StudentStatusService.derive(StudentStatus.ACTIVE, List.of())).isEqualTo(StudentStatus.ACTIVE);
    }

    // ── V73 (faqat PostgreSQL) ──────────────────────────────────────────

    @Test
    void v73_fixesExistingRows_writesHistory_idempotent() {
        Assumptions.assumeTrue(isPostgres(), "V73 — PostgreSQL skripti (pgtest)");
        // Zid holatlar: faol guruhda LEFT; guruhsiz qolgan ACTIVE; bitirgan ACTIVE; muzlatilgan ACTIVE;
        // arxivlangan (o'zgarmaydi); hech qachon yozilmagan (o'zgarmaydi)
        Long inGroupButLeft = fixtures.student();
        enroll(inGroupButLeft, newGroup());
        Long orphanActive = fixtures.student();
        Long sg1 = enroll(orphanActive, newGroup());
        Long graduated = fixtures.student();
        Long sg2 = enroll(graduated, newGroup());
        Long frozenActive = fixtures.student();
        Long sg3 = enroll(frozenActive, newGroup());
        Long archived = fixtures.student();
        Long sg4 = enroll(archived, newGroup());
        Long never = fixtures.student();

        jdbc.update("UPDATE students SET status = 'LEFT' WHERE id = ?", inGroupButLeft);
        jdbc.update("UPDATE student_groups SET is_active = false, leave_date = DATE '2026-09-05', exit_reason = NULL,"
            + " exit_reason_code = 'OTHER' WHERE id = ?", sg1);
        jdbc.update("UPDATE student_groups SET is_active = false, leave_date = DATE '2026-09-06',"
            + " exit_reason = 'GRADUATED', exit_reason_code = 'GRADUATED' WHERE id = ?", sg2);
        jdbc.update("UPDATE student_groups SET is_active = false, frozen_from = DATE '2026-09-07',"
            + " exit_reason = 'FROZEN', exit_reason_code = 'FROZEN' WHERE id = ?", sg3);
        jdbc.update("UPDATE student_groups SET is_active = false, leave_date = DATE '2026-09-08' WHERE id = ?", sg4);
        jdbc.update("UPDATE students SET status = 'ARCHIVED' WHERE id = ?", archived);

        runScript("db/migration/V73__student_status_from_enrollments.sql");

        assertThat(status(inGroupButLeft)).isEqualTo(StudentStatus.ACTIVE);
        assertThat(status(orphanActive)).isEqualTo(StudentStatus.LEFT);
        assertThat(status(graduated)).isEqualTo(StudentStatus.GRADUATED);
        assertThat(status(frozenActive)).isEqualTo(StudentStatus.FROZEN);
        assertThat(status(archived)).isEqualTo(StudentStatus.ARCHIVED);
        assertThat(status(never)).isEqualTo(StudentStatus.ACTIVE);
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM student_status_history WHERE reason = 'V73_SYNC'", Integer.class);
        assertThat(rows).isEqualTo(4);
        assertThat(lastHistory(orphanActive)).satisfies(h -> {
            assertThat(h.getFromStatus()).isEqualTo("ACTIVE");
            assertThat(h.getToStatus()).isEqualTo("LEFT");
        });

        runScript("db/migration/V73__student_status_from_enrollments.sql");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM student_status_history WHERE reason = 'V73_SYNC'", Integer.class)).isEqualTo(4);
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")));
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

package com.crm.dashboard;

import com.crm.billing.EnrollmentLifecycleService;
import com.crm.billing.PeriodCoverage;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.FreezeStudentRequest;
import com.crm.dto.request.LeadAssignRequest;
import com.crm.dto.request.LeadCommentRequest;
import com.crm.dto.request.LeadCreateRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.TaskCompleteRequest;
import com.crm.dto.request.TransferGroupRequest;
import com.crm.entity.Attendance;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Lead;
import com.crm.entity.LeadAssignment;
import com.crm.entity.LeadStage;
import com.crm.entity.StudentGroup;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.FunnelStep;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.TrialOutcome;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.LeadAssignmentRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.LeadStageRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TaskRepository;
import com.crm.repository.UserRepository;
import com.crm.service.LeadService;
import com.crm.service.LeadStageService;
import com.crm.service.PaymentService;
import com.crm.service.StudentService;
import com.crm.service.TaskService;
import com.crm.billing.AccrualService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Direktor dashboardi, 1-bosqich: sxema va yozish nuqtalari (director-dashboard §3, G1–G10). */
class DashboardSignalsTest extends AbstractBillingIT {

    @Autowired LeadService leadService;
    @Autowired LeadStageService stageService;
    @Autowired LeadStageRepository stageRepo;
    @Autowired LeadRepository leadRepo;
    @Autowired LeadAssignmentRepository assignmentRepo;
    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepo;
    @Autowired UserRepository userRepo;
    @Autowired LeadFunnelTracker tracker;
    @Autowired AttendanceSignals attendanceSignals;
    @Autowired AttendanceRepository attendanceRepo;
    @Autowired StudentRepository studentRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired BillingPeriodRepository periodRepo;
    @Autowired AccrualService accrual;
    @Autowired PaymentService payments;
    @Autowired StudentService students;
    @Autowired EnrollmentLifecycleService lifecycle;

    private static LocalDateTime t(String ddMMyyyy, int h, int m) {
        return d(ddMMyyyy).atTime(h, m);
    }

    private User user(UserRole role) {
        fixtures.loginAs(role);
        return userRepo.findByUsername("test-" + role.name().toLowerCase()).orElseThrow();
    }

    private Long staffLead(Long assigneeId, String status) {
        LeadCreateRequest r = new LeadCreateRequest();
        r.setFullName("Lid " + System.nanoTime());
        r.setPhone("+998901234567");
        r.setAssignedUserId(assigneeId);
        r.setStatus(status);
        return leadService.createLeadByStaff(r).getId();
    }

    private Lead lead(Long id) {
        return inTx(() -> leadRepo.findById(id).orElseThrow());
    }

    private List<LeadAssignment> assignments(Long leadId) {
        return inTx(() -> assignmentRepo.findByLeadIdOrderByAssignedAtAscIdAsc(leadId));
    }

    // ── G1: bosqichlar ──────────────────────────────────────────────────

    @Test
    void seededStages_haveFunnelSteps_perDecision1() {
        Map<String, FunnelStep> steps = new java.util.HashMap<>();
        for (LeadStage s : stageRepo.findAll()) {
            steps.put(s.getCode(), s.getFunnelStep());
        }
        assertThat(steps).containsEntry("VISITED_TRIAL", FunnelStep.VISITED)
            .containsEntry("CONTACTED", FunnelStep.CONTACTED)
            .containsEntry("ONLINE_ENROLLED", FunnelStep.NONE)
            .containsEntry("OFFLINE_ENROLLED", FunnelStep.NONE)
            .containsEntry("ONLINE_PAID", FunnelStep.VISITED)
            .containsEntry("NEW", FunnelStep.NONE);
        assertThat(stageService.funnelRank("NEW")).isZero();
        assertThat(stageService.funnelRank("CONTACTED")).isEqualTo(1);
        assertThat(stageService.funnelRank("VISITED_TRIAL")).isEqualTo(2);
        assertThat(stageService.funnelRank("CONVERTED_OFFLINE")).isEqualTo(3);
        assertThat(stageService.codesWithRankAtLeast(2))
            .contains("VISITED_TRIAL", "ONLINE_PAID", "CONVERTED_ONLINE").doesNotContain("ONLINE_ENROLLED");
    }

    // ── G2–G4: qadam sanalari, tayinlash, birinchi javob ───────────────

    @Test
    void stageDates_assignment_andFirstResponse() {
        User sm = user(UserRole.SALES_MANAGER);
        user(UserRole.SUPER_ADMIN);
        clock.setDateTime(t("01.10.2026", 9, 0));
        Long id = staffLead(sm.getId(), null);

        assertThat(assignments(id)).singleElement().satisfies(a -> {
            assertThat(a.getUserId()).isEqualTo(sm.getId());
            assertThat(a.getAssignedAt()).isEqualTo(t("01.10.2026", 9, 0));
            assertThat(a.getFirstResponseAt()).isNull();
        });

        // SA ning harakati SM ning javobi emas
        clock.setDateTime(t("01.10.2026", 9, 5));
        LeadCommentRequest c = new LeadCommentRequest();
        c.setText("SA izohi");
        leadService.addComment(id, c);
        assertThat(assignments(id).get(0).getFirstResponseAt()).isNull();

        fixtures.loginAs(UserRole.SALES_MANAGER);
        clock.setDateTime(t("01.10.2026", 9, 12));
        leadService.updateStatus(id, "CONTACTED", null);
        clock.setDateTime(t("01.10.2026", 15, 0));
        leadService.updateStatus(id, "VISITED_TRIAL", null);
        clock.setDateTime(t("02.10.2026", 10, 0));
        leadService.addComment(id, c);

        Lead l = lead(id);
        assertThat(l.getContactedAt()).isEqualTo(t("01.10.2026", 9, 12));
        assertThat(l.getVisitedAt()).isEqualTo(t("01.10.2026", 15, 0));
        assertThat(l.getConvertedAt()).isNull();
        assertThat(assignments(id)).singleElement().satisfies(a -> {
            assertThat(a.getFirstResponseAt()).isEqualTo(t("01.10.2026", 9, 12));
            assertThat(a.getFirstResponseKind()).isEqualTo(LeadAssignment.KIND_STATUS);
        });

        // Orqaga qaytish va qayta kirish sanani o'zgartirmaydi (write-once)
        leadService.updateStatus(id, "NEW", null);
        leadService.updateStatus(id, "CONTACTED", null);
        assertThat(lead(id).getContactedAt()).isEqualTo(t("01.10.2026", 9, 12));
    }

    @Test
    void reassignment_closesPrevious_andTaskCountsAsResponse() {
        User sm = user(UserRole.SALES_MANAGER);
        User admin = user(UserRole.ADMIN);
        user(UserRole.SUPER_ADMIN);
        clock.setDateTime(t("01.10.2026", 9, 0));
        Long id = staffLead(sm.getId(), null);

        clock.setDateTime(t("01.10.2026", 11, 0));
        LeadAssignRequest r = new LeadAssignRequest();
        r.setUserId(admin.getId());
        leadService.assignLead(id, r);
        leadService.assignLead(id, r);   // xuddi shu operatorga — yangi qator yo'q

        List<LeadAssignment> rows = assignments(id);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getUnassignedAt()).isEqualTo(t("01.10.2026", 11, 0));
        assertThat(rows.get(1).getUserId()).isEqualTo(admin.getId());
        assertThat(rows.get(1).getUnassignedAt()).isNull();

        Long taskId = inTx(() -> taskRepo.save(Task.builder()
            .title("Qo'ng'iroq").dueAt(t("01.10.2026", 12, 0)).status(TaskStatus.OPEN)
            .assignedTo(userRepo.findById(admin.getId()).orElseThrow())
            .lead(leadRepo.findById(id).orElseThrow()).build()).getId());
        fixtures.loginAs(UserRole.ADMIN);
        clock.setDateTime(t("01.10.2026", 11, 40));
        TaskCompleteRequest done = new TaskCompleteRequest();
        done.setResult("Gaplashildi");
        taskService.complete(taskId, done);

        assertThat(assignments(id).get(1)).satisfies(a -> {
            assertThat(a.getFirstResponseAt()).isEqualTo(t("01.10.2026", 11, 40));
            assertThat(a.getFirstResponseKind()).isEqualTo(LeadAssignment.KIND_TASK);
        });
        assertThat(assignments(id).get(0).getFirstResponseAt()).isNull();
    }

    @Test
    void conversion_setsContactedNotVisited_rejectedTracked() {
        user(UserRole.SUPER_ADMIN);
        clock.setDateTime(t("01.10.2026", 10, 0));
        Long id = staffLead(null, null);
        inTx(() -> {
            Lead l = leadRepo.findById(id).orElseThrow();
            tracker.onStageEntered(l, "CONVERTED_OFFLINE", t("01.10.2026", 10, 0));
            leadRepo.save(l);
        });
        Lead l = lead(id);
        assertThat(l.getConvertedAt()).isEqualTo(t("01.10.2026", 10, 0));
        assertThat(l.getContactedAt()).isEqualTo(t("01.10.2026", 10, 0));
        assertThat(l.getVisitedAt()).as("§7 #1: tashrif — davomat yoki VISITED bosqichi").isNull();

        Long rejected = staffLead(null, null);
        clock.setDateTime(t("02.10.2026", 10, 0));
        leadService.updateStatus(rejected, "REJECTED", null);
        assertThat(lead(rejected).getRejectedAt()).isEqualTo(t("02.10.2026", 10, 0));
        assertThat(lead(rejected).getContactedAt()).isNull();
    }

    // ── §7 #1, G6: davomatdan tashrif va sinov boshlanishi ─────────────

    private Attendance attend(Long studentId, Long groupId, String date, AttendanceStatus status) {
        return inTx(() -> {
            Attendance a = attendanceRepo.save(Attendance.builder()
                .student(studentRepo.findById(studentId).orElseThrow())
                .group(groupRepo.findById(groupId).orElseThrow())
                .attendanceDate(d(date)).status(status).build());
            attendanceSignals.onAttendanceSaved(a);
            return a;
        });
    }

    @Test
    void firstAttendance_marksLeadVisited_andTrialStart() {
        user(UserRole.SUPER_ADMIN);
        Long leadId = staffLead(null, null);
        Long student = fixtures.student();
        inTx(() -> {
            var s = studentRepo.findById(student).orElseThrow();
            s.setConvertedFromLeadId(leadId);
            studentRepo.save(s);
        });
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("05.10.2026")).trial().save();
        assertThat(inTx(() -> sgRepo.findById(sg).orElseThrow()).getTrialOutcome()).isEqualTo(TrialOutcome.IN_TRIAL);

        attend(student, group, "06.10.2026", AttendanceStatus.ABSENT);
        assertThat(lead(leadId).getVisitedAt()).isNull();
        attend(student, group, "07.10.2026", AttendanceStatus.PRESENT);
        attend(student, group, "05.10.2026", AttendanceStatus.LATE);   // orqaga sanalangan

        assertThat(lead(leadId).getVisitedAt()).isEqualTo(d("07.10.2026").atStartOfDay());
        StudentGroup after = inTx(() -> sgRepo.findById(sg).orElseThrow());
        assertThat(after.getTrialStartedAt()).isEqualTo(d("05.10.2026"));
    }

    // ── G5: davr qachon yopilgani ───────────────────────────────────────

    private static PeriodCoverage.Line line(long id, long amount, String date, Long related, boolean neutral) {
        return new PeriodCoverage.Line(id, BigDecimal.valueOf(amount), d(date), related,
            d(date).atTime(12, 0), neutral);
    }

    @Test
    void coverage_chronologicalFifo_numericCases() {
        // a) ikki davr, ikki to'lov: 15.09 davri 20.09 da, 15.10 davri 16.10 da yopiladi
        Map<Long, PeriodCoverage.Covered> a = PeriodCoverage.compute(List.of(
            line(1, -630_000, "15.09.2026", null, false),
            line(2, -630_000, "15.10.2026", null, false),
            line(3, 630_000, "20.09.2026", null, false),
            line(4, 630_000, "16.10.2026", null, false)));
        assertThat(a.get(1L).paidOn()).isEqualTo(d("20.09.2026"));
        assertThat(a.get(2L).paidOn()).isEqualTo(d("16.10.2026"));
        assertThat(a.get(2L).paidTxId()).isEqualTo(4L);

        // b) oldindan to'lov 1 260 000 (10.09) — ikkala davr 10.09 da yopilgan (≤ due → o'z vaqtida)
        Map<Long, PeriodCoverage.Covered> b = PeriodCoverage.compute(List.of(
            line(1, 1_260_000, "10.09.2026", null, false),
            line(2, -630_000, "15.09.2026", null, false),
            line(3, -630_000, "15.10.2026", null, false)));
        assertThat(b.get(2L).paidOn()).isEqualTo(d("10.09.2026"));
        assertThat(b.get(3L).paidOn()).isEqualTo(d("10.09.2026"));

        // c) qisman: 300 000 + 330 000 — davr ikkinchi to'lov kuni yopiladi
        Map<Long, PeriodCoverage.Covered> c = PeriodCoverage.compute(List.of(
            line(1, -630_000, "15.09.2026", null, false),
            line(2, 300_000, "16.09.2026", null, false),
            line(3, 330_000, "25.09.2026", null, false)));
        assertThat(c.get(1L).paidOn()).isEqualTo(d("25.09.2026"));

        // d) to'lov bekor qilindi (REVERSAL) — davr ochiq
        Map<Long, PeriodCoverage.Covered> dd = PeriodCoverage.compute(List.of(
            line(1, -630_000, "15.09.2026", null, false),
            line(2, 630_000, "16.09.2026", null, false),
            line(3, -630_000, "16.09.2026", 2L, false)));
        assertThat(dd).doesNotContainKey(1L);

        // e) migratsiya: v1 PERIOD_CHARGE va MIGRATION neytral — davr haqiqiy to'lov kuni yopiladi
        Map<Long, PeriodCoverage.Covered> e = PeriodCoverage.compute(List.of(
            line(1, 700_000, "20.09.2026", null, false),    // v1 PAYMENT
            line(2, -700_000, "20.09.2026", null, true),    // v1 PERIOD_CHARGE
            line(3, -700_000, "20.09.2026", null, false),   // migratsiya davri 20.09
            line(4, 700_000, "05.10.2026", null, true)));   // MIGRATION (T)
        assertThat(e.get(3L).paidOn()).isEqualTo(d("20.09.2026"));
    }

    @Test
    void coverage_writtenOnPayment_andClearedOnCancel() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("15.09.2026")).discount("10").save();
        accrual.accrueUpTo(sg, d("15.09.2026"));
        BillingPeriod p0 = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sg).get(0));
        assertThat(p0.getDueDate()).isEqualTo(d("15.09.2026"));
        assertThat(p0.getGraceUntil()).isEqualTo(d("18.09.2026"));
        assertThat(p0.getPaidOn()).isNull();

        fixtures.loginAs(UserRole.ACCOUNTANT);
        clock.setDate(d("20.09.2026"));
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(630_000));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        Long paymentId = payments.createPayment(r, null).getId();

        BillingPeriod paid = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sg).get(0));
        assertThat(paid.getPaidOn()).isEqualTo(d("20.09.2026"));
        assertThat(paid.getCoverageSource()).isEqualTo("FIFO");
        assertThat(paid.getPaidTxId()).isNotNull();

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payments.cancelPayment(paymentId, "Xato");
        BillingPeriod reopened = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sg).get(0));
        assertThat(reopened.getPaidOn()).isNull();
        assertThat(reopened.getPaidTxId()).isNull();
    }

    // ── G6, G10: sinov natijasi va chiqish sababi ───────────────────────

    @Test
    void trialConvertedByPayment_andNoShowOnLeave() {
        clock.setDate(d("06.10.2026"));
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("01.10.2026")).trial().save();
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(700_000));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
        StudentGroup converted = inTx(() -> sgRepo.findById(sg).orElseThrow());
        assertThat(converted.getTrialOutcome()).isEqualTo(TrialOutcome.CONVERTED);
        assertThat(converted.getTrialConvertedAt()).isEqualTo(d("06.10.2026"));

        Long student2 = fixtures.student();
        Long sg2 = fixtures.enrollment(student2, group).start(d("01.10.2026")).trial().save();
        fixtures.loginAs(UserRole.ADMIN);
        inTx(() -> lifecycle.leave(student2, group, "Narx qimmat", null, ExitReasonCode.PRICE));
        StudentGroup left = inTx(() -> sgRepo.findById(sg2).orElseThrow());
        assertThat(left.getTrialOutcome()).isEqualTo(TrialOutcome.NO_SHOW);
        assertThat(left.getExitReasonCode()).isEqualTo(ExitReasonCode.PRICE);
    }

    @Test
    void exitReasonCodes_freezeTransferLegacyLeave() {
        clock.setDate(d("20.09.2026"));
        Long group = fixtures.group(fixtures.course(700_000));
        Long other = fixtures.group(fixtures.course(700_000));
        Long s1 = fixtures.student();
        Long sg1 = fixtures.enrollment(s1, group).start(d("15.09.2026")).save();
        Long s2 = fixtures.student();
        Long sg2 = fixtures.enrollment(s2, group).start(d("15.09.2026")).save();
        Long s3 = fixtures.student();
        Long sg3 = fixtures.enrollment(s3, group).start(d("15.09.2026")).save();
        fixtures.loginAs(UserRole.ADMIN);

        FreezeStudentRequest f = new FreezeStudentRequest();
        f.setGroupId(group);
        students.freezeStudent(s1, f);
        TransferGroupRequest t = new TransferGroupRequest();
        t.setFromGroupId(group);
        t.setToGroupId(other);
        students.transferGroup(s2, t);
        inTx(() -> lifecycle.leave(s3, group, "LEFT", null));

        assertThat(inTx(() -> sgRepo.findById(sg1).orElseThrow()).getExitReasonCode()).isEqualTo(ExitReasonCode.FROZEN);
        assertThat(inTx(() -> sgRepo.findById(sg2).orElseThrow()).getExitReasonCode()).isEqualTo(ExitReasonCode.TRANSFERRED);
        assertThat(inTx(() -> sgRepo.findById(sg3).orElseThrow()).getExitReasonCode())
            .as("§7 #12: eski 'LEFT' matni → OTHER").isEqualTo(ExitReasonCode.OTHER);
        assertThat(ExitReasonCode.fromLegacy("graduated")).isEqualTo(ExitReasonCode.GRADUATED);
    }
}

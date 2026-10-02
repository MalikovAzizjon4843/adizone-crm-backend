package com.crm.dashboard;

import com.crm.billing.AccrualService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.PaymentRequest;
import com.crm.entity.Attendance;
import com.crm.entity.Lead;
import com.crm.entity.LeadAssignment;
import com.crm.entity.LeadComment;
import com.crm.entity.LeadStatusHistory;
import com.crm.entity.StudentGroup;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.TrialOutcome;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.LeadAssignmentRepository;
import com.crm.repository.LeadCommentRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.LeadStatusHistoryRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TaskRepository;
import com.crm.repository.UserRepository;
import com.crm.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Direktor dashboardi, 2-bosqich: tarixni to'ldirish (§2.2), dry-run → apply → idempotent. */
class DashboardBackfillTest extends AbstractBillingIT {

    @Autowired DashboardBackfillService backfill;
    @Autowired LeadRepository leadRepo;
    @Autowired LeadStatusHistoryRepository historyRepo;
    @Autowired LeadAssignmentRepository assignmentRepo;
    @Autowired LeadCommentRepository commentRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired UserRepository userRepo;
    @Autowired StudentRepository studentRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired AttendanceRepository attendanceRepo;
    @Autowired BillingPeriodRepository periodRepo;
    @Autowired AccrualService accrual;
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;

    private static LocalDateTime t(String date, int h, int m) {
        return d(date).atTime(h, m);
    }

    private User user(UserRole role) {
        fixtures.loginAs(role);
        return userRepo.findByUsername("test-" + role.name().toLowerCase()).orElseThrow();
    }

    /** V53 dan oldingi lid: kuzatuvchisiz, created_at SQL bilan. */
    private Long legacyLead(String status, LocalDateTime createdAt, User assignee) {
        Long id = inTx(() -> leadRepo.save(Lead.builder().fullName("Eski lid").phone("+998900000000")
            .status(status).assignedUser(assignee).assignedAt(assignee != null ? createdAt : null)
            .converted(false).build()).getId());
        jdbc.update("UPDATE leads SET created_at = ? WHERE id = ?", createdAt, id);
        return id;
    }

    private void history(Long leadId, String from, String to, LocalDateTime at, User by) {
        inTx(() -> historyRepo.save(LeadStatusHistory.builder().lead(leadRepo.findById(leadId).orElseThrow())
            .fromStatus(from).toStatus(to).changedAt(at).changedBy(by).build()));
    }

    private Lead lead(Long id) {
        return inTx(() -> leadRepo.findById(id).orElseThrow());
    }

    private StudentGroup sg(Long id) {
        return inTx(() -> sgRepo.findById(id).orElseThrow());
    }

    private void attend(Long student, Long group, String date) {
        inTx(() -> attendanceRepo.save(Attendance.builder()
            .student(studentRepo.findById(student).orElseThrow()).group(groupRepo.findById(group).orElseThrow())
            .attendanceDate(d(date)).status(AttendanceStatus.PRESENT).build()));
    }

    record World(Long l1, Long l2, Long l3, Long l4, Long l5, Long trialNow, Long trialConverted,
                 Long trialNoShow, Long graduated, Long frozen, Long paidSg, User sm, User admin) {
    }

    private World legacyWorld() {
        User sm = user(UserRole.SALES_MANAGER);
        User admin = user(UserRole.ADMIN);
        // L1: NEW → CONTACTED → VISITED_TRIAL → CONVERTED, SM ga tayinlangan, javob 11:00
        Long l1 = legacyLead("CONVERTED_OFFLINE", t("01.09.2026", 10, 0), sm);
        history(l1, "NEW", "CONTACTED", t("01.09.2026", 11, 0), sm);
        history(l1, "CONTACTED", "VISITED_TRIAL", t("03.09.2026", 16, 0), sm);
        history(l1, "VISITED_TRIAL", "CONVERTED_OFFLINE", t("05.09.2026", 9, 0), sm);
        // L2: tarixsiz, to'g'ridan CONTACTED da yaratilgan
        Long l2 = legacyLead("CONTACTED", t("02.09.2026", 8, 0), null);
        // L3: konvert, tashrif — o'quvchining birinchi davomatidan (06.09)
        Long l3 = legacyLead("CONVERTED_OFFLINE", t("03.09.2026", 8, 0), null);
        history(l3, "NEW", "CONVERTED_OFFLINE", t("04.09.2026", 12, 0), null);
        Long s3 = fixtures.student();
        jdbc.update("UPDATE students SET converted_from_lead_id = ? WHERE id = ?", l3, s3);
        Long g = fixtures.group(fixtures.course(700_000));
        fixtures.enrollment(s3, g).start(d("06.09.2026")).save();
        attend(s3, g, "06.09.2026");
        // L4: tashrif jonli yozilgan (07.09) — backfill ustidan yozmasligi kerak
        Long l4 = legacyLead("VISITED_TRIAL", t("01.09.2026", 8, 0), null);
        history(l4, "NEW", "VISITED_TRIAL", t("08.09.2026", 8, 0), null);
        jdbc.update("UPDATE leads SET visited_at = ? WHERE id = ?", t("07.09.2026", 9, 0), l4);
        // L5: ADMIN ga, javob — bajarilgan vazifa 09:10 (izoh 09:30 dan oldin)
        Long l5 = legacyLead("NEW", t("02.09.2026", 9, 0), admin);
        inTx(() -> taskRepo.save(Task.builder().title("Qo'ng'iroq").dueAt(t("02.09.2026", 12, 0))
            .status(TaskStatus.DONE).completedAt(t("02.09.2026", 9, 10))
            .completedBy(userRepo.findById(admin.getId()).orElseThrow())
            .assignedTo(userRepo.findById(admin.getId()).orElseThrow())
            .lead(leadRepo.findById(l5).orElseThrow()).build()));
        inTx(() -> commentRepo.save(LeadComment.builder().lead(leadRepo.findById(l5).orElseThrow())
            .author(userRepo.findById(admin.getId()).orElseThrow()).text("izoh")
            .createdAt(t("02.09.2026", 9, 30)).build()));

        // Sinovlar (PrePersist IN_TRIAL ni SQL bilan o'chiramiz — V53 dan oldingi holat)
        Long gt = fixtures.group(fixtures.course(700_000));
        Long a = fixtures.student();
        Long trialNow = fixtures.enrollment(a, gt).start(d("01.09.2026")).trial().save();
        attend(a, gt, "04.09.2026");
        Long b = fixtures.student();
        Long trialConverted = fixtures.enrollment(b, gt).start(d("10.09.2026")).save();
        jdbc.update("UPDATE student_groups SET join_date = ? WHERE id = ?", d("01.09.2026"), trialConverted);
        attend(b, gt, "03.09.2026");
        Long c = fixtures.student();
        Long trialNoShow = fixtures.enrollment(c, gt).start(d("01.09.2026")).trial().save();
        jdbc.update("UPDATE student_groups SET is_active = FALSE, leave_date = ? WHERE id = ?", d("05.09.2026"), trialNoShow);
        jdbc.update("UPDATE student_groups SET trial_outcome = NULL, trial_source = NULL");

        // Chiqish sabablari
        Long gr = fixtures.student();
        Long graduated = fixtures.enrollment(gr, gt).start(d("01.09.2026")).save();
        jdbc.update("UPDATE student_groups SET is_active = FALSE, exit_reason = 'GRADUATED' WHERE id = ?", graduated);
        Long fr = fixtures.student();
        Long frozen = fixtures.enrollment(fr, gt).start(d("01.09.2026")).save();
        jdbc.update("UPDATE student_groups SET is_active = FALSE, frozen_from = ?, exit_reason = 'FROZEN' WHERE id = ?",
            d("05.09.2026"), frozen);

        // Davr to'langan, lekin paid_on yo'q (V53 dan oldin)
        Long ps = fixtures.student();
        Long pg = fixtures.group(fixtures.course(700_000));
        Long paidSg = fixtures.enrollment(ps, pg).start(d("15.09.2026")).save();
        accrual.accrueUpTo(paidSg, d("15.09.2026"));
        fixtures.loginAs(UserRole.ACCOUNTANT);
        clock.setDate(d("17.09.2026"));
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(ps);
        r.setGroupId(pg);
        r.setAmount(BigDecimal.valueOf(700_000));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
        jdbc.update("UPDATE billing_periods SET paid_on = NULL, paid_at = NULL, paid_tx_id = NULL, "
            + "coverage_source = NULL, due_date = NULL, grace_until = NULL");
        return new World(l1, l2, l3, l4, l5, trialNow, trialConverted, trialNoShow, graduated, frozen, paidSg, sm, admin);
    }

    private int changes(DashboardBackfillService.Report r, String code) {
        return r.items().stream().filter(i -> i.code().equals(code)).findFirst().orElseThrow().changes();
    }

    @Test
    void dryRun_writesNothing_thenApply_thenIdempotent() {
        World w = legacyWorld();
        String before = jdbc.queryForList("SELECT id, contacted_at, visited_at, converted_at FROM leads ORDER BY id")
            + "|" + jdbc.queryForList("SELECT id, trial_outcome, trial_started_at, exit_reason_code FROM student_groups ORDER BY id")
            + "|" + jdbc.queryForList("SELECT id, paid_on, due_date FROM billing_periods ORDER BY id");

        DashboardBackfillService.Report dry = backfill.run(true, null);

        assertThat(dry.dryRun()).isTrue();
        String after = jdbc.queryForList("SELECT id, contacted_at, visited_at, converted_at FROM leads ORDER BY id")
            + "|" + jdbc.queryForList("SELECT id, trial_outcome, trial_started_at, exit_reason_code FROM student_groups ORDER BY id")
            + "|" + jdbc.queryForList("SELECT id, paid_on, due_date FROM billing_periods ORDER BY id");
        assertThat(after).isEqualTo(before);
        assertThat(assignmentRepo.count()).isZero();
        assertThat(changes(dry, "G2")).isEqualTo(4);        // L1, L2, L3, L4(contacted)
        assertThat(changes(dry, "G3/G4")).isEqualTo(2);     // L1 (SM), L5 (ADMIN)
        assertThat(changes(dry, "G5")).isEqualTo(1);
        assertThat(changes(dry, "G10")).isEqualTo(3);       // bitiruvchi, muzlatilgan, sinovdan chiqqan

        assertThatThrownBy(() -> backfill.run(false, null))
            .isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode()).isEqualTo("migration.confirmRequired");

        backfill.run(false, DashboardBackfillService.CONFIRM);

        Lead l1 = lead(w.l1());
        assertThat(l1.getContactedAt()).isEqualTo(t("01.09.2026", 11, 0));
        assertThat(l1.getVisitedAt()).isEqualTo(t("03.09.2026", 16, 0));
        assertThat(l1.getConvertedAt()).isEqualTo(t("05.09.2026", 9, 0));
        assertThat(lead(w.l2()).getContactedAt()).isEqualTo(t("02.09.2026", 8, 0));
        Lead l3 = lead(w.l3());
        assertThat(l3.getConvertedAt()).isEqualTo(t("04.09.2026", 12, 0));
        assertThat(l3.getVisitedAt()).isEqualTo(d("06.09.2026").atStartOfDay());
        assertThat(lead(w.l4()).getVisitedAt()).as("jonli qiymat saqlanadi").isEqualTo(t("07.09.2026", 9, 0));

        List<LeadAssignment> a1 = inTx(() -> assignmentRepo.findByLeadIdOrderByAssignedAtAscIdAsc(w.l1()));
        assertThat(a1).singleElement().satisfies(a -> {
            assertThat(a.getUserId()).isEqualTo(w.sm().getId());
            assertThat(a.getFirstResponseAt()).isEqualTo(t("01.09.2026", 11, 0));
            assertThat(a.getFirstResponseKind()).isEqualTo(LeadAssignment.KIND_STATUS);
            assertThat(a.getSource()).isEqualTo("BACKFILL");
        });
        assertThat(inTx(() -> assignmentRepo.findByLeadIdOrderByAssignedAtAscIdAsc(w.l5())).get(0))
            .satisfies(a -> {
                assertThat(a.getFirstResponseAt()).isEqualTo(t("02.09.2026", 9, 10));
                assertThat(a.getFirstResponseKind()).isEqualTo(LeadAssignment.KIND_TASK);
            });

        assertThat(sg(w.trialNow()).getTrialStartedAt()).isEqualTo(d("04.09.2026"));
        assertThat(sg(w.trialNow()).getTrialOutcome()).isEqualTo(TrialOutcome.IN_TRIAL);
        assertThat(sg(w.trialConverted())).satisfies(s -> {
            assertThat(s.getTrialStartedAt()).isEqualTo(d("03.09.2026"));
            assertThat(s.getTrialConvertedAt()).isEqualTo(d("10.09.2026"));
            assertThat(s.getTrialOutcome()).isEqualTo(TrialOutcome.CONVERTED);
            assertThat(s.getTrialSource()).isEqualTo("BACKFILL");
        });
        assertThat(sg(w.trialNoShow()).getTrialOutcome()).isEqualTo(TrialOutcome.NO_SHOW);
        assertThat(sg(w.graduated()).getExitReasonCode()).isEqualTo(ExitReasonCode.GRADUATED);
        assertThat(sg(w.frozen()).getExitReasonCode()).isEqualTo(ExitReasonCode.FROZEN);
        assertThat(inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(w.paidSg()).get(0)))
            .satisfies(p -> {
                assertThat(p.getPaidOn()).isEqualTo(d("17.09.2026"));
                assertThat(p.getDueDate()).isEqualTo(d("15.09.2026"));
                assertThat(p.getGraceUntil()).isEqualTo(d("18.09.2026"));
            });

        DashboardBackfillService.Report again = backfill.run(true, null);
        assertThat(again.items()).allSatisfy(i -> assertThat(i.changes()).as(i.code()).isZero());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void backfillEndpoint_superAdminOnly() throws Exception {
        mvc.perform(post("/api/admin/dashboard/backfill")).andExpect(status().isForbidden());
    }
}

package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.ManualTransferRepairRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.StudentGroupRequest;
import com.crm.dto.request.TransferGroupRequest;
import com.crm.dto.request.TransferStudentsRequest;
import com.crm.dto.response.ManualTransferDtos.Classification;
import com.crm.dto.response.ManualTransferDtos.RepairResult;
import com.crm.dto.response.ManualTransferDtos.RepairRow;
import com.crm.dto.response.ManualTransferDtos.Report;
import com.crm.dto.response.ManualTransferDtos.Row;
import com.crm.dto.response.ManualTransferDtos.TransferWarning;
import com.crm.entity.BillingPeriod;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.CashTransactionRepository;
import com.crm.repository.EnrollmentTransferLinkRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.service.GroupBulkTransferService;
import com.crm.service.GroupService;
import com.crm.service.PaymentService;
import com.crm.service.StudentService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Qo'lda ko'chirish (chiqarish TRANSFERRED + yangi guruhga qo'shish): aniqlash tasnifi, transfer'ga tenglash ta'miri
 * (dryRun = apply, idempotentlik), prod ssenariysi 0-000335, USE_TRANSFER ogohlantirishlari, ommaviy ko'chirish,
 * docs/ops/exit-readd-detect.sql ↔ Java mosligi (faqat pgtest).
 */
class ManualTransferTest extends AbstractBillingIT {

    @Autowired
    ManualTransferDetectionService detection;
    @Autowired
    ManualTransferRepairService repair;
    @Autowired
    TransferAdvisor advisor;
    @Autowired
    GroupService groupService;
    @Autowired
    StudentService students;
    @Autowired
    PaymentService payments;
    @Autowired
    AccrualService accrual;
    @Autowired
    GroupBulkTransferService bulk;
    @Autowired
    StudentGroupRepository sgRepo;
    @Autowired
    StudentRepository studentRepo;
    @Autowired
    BillingPeriodRepository periodRepo;
    @Autowired
    EnrollmentTransferLinkRepository linkRepo;
    @Autowired
    PaymentRepository paymentRepo;
    @Autowired
    CashTransactionRepository cashRepo;
    @Autowired
    JdbcTemplate jdbc;

    // ── yordamchilar ────────────────────────────────────────────────────

    private Long group() {
        return fixtures.group(fixtures.course(700_000));
    }

    /** Oylik 700 000, langar {@code start}, shu kuni accrual. */
    private Long enroll(Long student, Long group, String start) {
        Long sg = fixtures.enrollment(student, group).start(d(start)).save();
        accrue(sg, start);
        return sg;
    }

    private void accrue(Long sg, String date) {
        clock.setDate(d(date));
        accrual.accrueUpTo(sg, d(date));
    }

    private void pay(Long student, Long group, long amount, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
    }

    /** Admin oqimi: "Guruhga qo'shish" (yangi yozilma, hisob qo'shilgan kundan). */
    private Long manualAdd(Long student, Long group, String date, boolean trial) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ADMIN);
        StudentGroupRequest r = new StudentGroupRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setJoinDate(d(date));
        r.setPaymentStartDate(d(date));
        r.setIsTrial(trial);
        groupService.addStudentToGroup(r);
        Long sg = inTx(() -> sgRepo.findByStudentIdAndGroupIdAndIsActiveTrue(student, group).orElseThrow().getId());
        accrual.accrueUpTo(sg, d(date));
        return sg;
    }

    private Long manualAdd(Long student, Long group, String date) {
        return manualAdd(student, group, date, false);
    }

    /** Admin oqimi: "Guruhdan chiqarish", sabab "Boshqa guruhga o'tdi". */
    private List<TransferWarning> manualExit(Long student, Long group, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ADMIN);
        return groupService.removeStudentFromGroup(group, student, "TRANSFERRED", null, null);
    }

    private Report detect() {
        return detection.detect(null, null);
    }

    private Row row(Report report, Long oldSg, Long newSg) {
        return report.rows().stream()
            .filter(r -> r.oldEnrollmentId().equals(oldSg) && r.newEnrollmentId().equals(newSg))
            .findFirst().orElseThrow(() -> new AssertionError("juftlik topilmadi: " + oldSg + " → " + newSg));
    }

    private static ManualTransferRepairRequest pairs(Long... ids) {
        ManualTransferRepairRequest r = new ManualTransferRepairRequest();
        r.setPairs(new java.util.ArrayList<>());
        for (int i = 0; i < ids.length; i += 2) {
            ManualTransferRepairRequest.Pair p = new ManualTransferRepairRequest.Pair();
            p.setOldEnrollmentId(ids[i]);
            p.setNewEnrollmentId(ids[i + 1]);
            r.getPairs().add(p);
        }
        return r;
    }

    private static ManualTransferRepairRequest affected() {
        ManualTransferRepairRequest r = new ManualTransferRepairRequest();
        r.setClassification("AFFECTED");
        return r;
    }

    private RepairResult run(ManualTransferRepairRequest request, boolean dryRun) {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        return repair.repair(request, dryRun);
    }

    private StudentGroup sg(Long id) {
        return inTx(() -> sgRepo.findById(id).orElseThrow());
    }

    private List<BillingPeriod> periods(Long sgId) {
        return inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sgId));
    }

    private BigDecimal studentBalance(Long studentId) {
        return inTx(() -> studentRepo.findById(studentId).orElseThrow().getBalance());
    }

    /** Prod ssenariysi 0-000335: 25.11 davr −700 000, 26.11 to'lov, 27.11 yangi guruh + eski chiqarildi. */
    private record Case335(Long student, Long groupA, Long groupB, Long oldSg, Long newSg, List<TransferWarning> exitWarnings) {
    }

    private Case335 case335() {
        Long student = fixtures.student();
        Long a = group();
        Long b = group();
        Long oldSg = enroll(student, a, "25.11.2026");
        pay(student, a, 700_000, "26.11.2026");
        Long newSg = manualAdd(student, b, "27.11.2026");
        List<TransferWarning> w = manualExit(student, a, "27.11.2026");
        clock.setDate(d("28.11.2026"));
        return new Case335(student, a, b, oldSg, newSg, w);
    }

    // ── 0) transfer oqimi o'zgarmagan, tasnif: TRANSFER_OK ─────────────

    @Test
    void transferFlow_isTransferOk() {
        Long student = fixtures.student();
        Long a = group();
        Long b = group();
        Long oldSg = enroll(student, a, "25.11.2026");   // to'lanmagan: qarz ko'chadi (TRANSFER_OUT/IN)
        clock.setDate(d("27.11.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        TransferGroupRequest r = new TransferGroupRequest();
        r.setFromGroupId(a);
        r.setToGroupId(b);
        students.transferGroup(student, r);
        Long newSg = inTx(() -> sgRepo.findByStudentIdAndGroupIdAndIsActiveTrue(student, b).orElseThrow().getId());
        clock.setDate(d("28.11.2026"));

        Row row = row(detect(), oldSg, newSg);

        assertThat(row.classification()).isEqualTo(Classification.TRANSFER_OK);
        assertThat(row.reasonCode()).isEqualTo("TRANSFER_FLOW");
        assertThat(row.continuedAnchor()).isEqualTo(d("25.12.2026"));
        assertThat(row.newPaymentStartDate()).isEqualTo(d("25.12.2026"));
        assertThat(row.refundAmount()).isEqualByComparingTo("0");
        assertThat(row.unusedAmount()).isEqualByComparingTo("0");
    }

    // ── 1) 0-000335: AFFECTED → ta'mir → qarz 0 ─────────────────────────

    @Test
    void case335_affected_repairMakesDebtZero_dryRunEqualsApply_idempotent() {
        Case335 c = case335();
        long paymentsBefore = inTx(() -> paymentRepo.count());
        long cashBefore = inTx(() -> cashRepo.count());

        // chiqarishda ogohlantirish (bloklamaydi)
        assertThat(c.exitWarnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo("USE_TRANSFER");
            assertThat(w.fromEnrollmentId()).isEqualTo(c.oldSg());
            assertThat(w.toEnrollmentId()).isEqualTo(c.newSg());
            assertThat(w.unusedAmount()).isEqualByComparingTo("700000");
        });
        assertThat(sg(c.oldSg()).getIsActive()).isFalse();

        Report report = detect();
        Row row = row(report, c.oldSg(), c.newSg());
        assertThat(row.classification()).isEqualTo(Classification.AFFECTED);
        assertThat(row.continuedAnchor()).isEqualTo(d("25.12.2026"));
        assertThat(row.refundAmount()).isEqualByComparingTo("700000");
        assertThat(row.oldBalance()).isEqualByComparingTo("0");
        assertThat(row.unusedAmount()).isEqualByComparingTo("700000");
        assertThat(row.currentDebt()).isEqualByComparingTo("700000");
        assertThat(row.mode()).isEqualTo("REANCHOR");
        assertThat(report.affected()).isEqualTo(1);
        assertThat(report.affectedTotal()).isEqualByComparingTo("700000");

        // dryRun — hech narsa yozilmaydi
        RepairResult dry = run(pairs(c.oldSg(), c.newSg()), true);
        RepairRow dryRow = dry.rows().get(0);
        assertThat(dryRow.status()).isEqualTo("APPLIED");
        assertThat(dryRow.before().studentDebt()).isEqualByComparingTo("700000");
        assertThat(dryRow.after().studentDebt()).isEqualByComparingTo("0");
        assertThat(dryRow.after().newBalance()).isEqualByComparingTo("0");
        assertThat(dryRow.after().newPaymentStartDate()).isEqualTo(d("25.12.2026"));
        assertThat(sg(c.newSg()).getBalance()).isEqualByComparingTo("-700000");
        assertThat(sg(c.newSg()).getPaymentStartDate()).isEqualTo(d("27.11.2026"));
        assertThat(inTx(() -> linkRepo.count())).isZero();

        // apply (tasnif bo'yicha) — natija dryRun bilan bir xil
        RepairResult applied = run(affected(), false);
        assertThat(applied.applied()).isEqualTo(1);
        RepairRow appliedRow = applied.rows().get(0);
        assertThat(appliedRow.after()).isEqualTo(dryRow.after());
        assertThat(appliedRow.before()).isEqualTo(dryRow.before());
        assertThat(appliedRow.credit()).isEqualByComparingTo(dryRow.credit());

        StudentGroup n = sg(c.newSg());
        assertThat(n.getBalance()).isEqualByComparingTo("0");
        assertThat(n.getPaymentStartDate()).isEqualTo(d("25.12.2026"));
        assertThat(n.getDebtSince()).isNull();
        assertThat(periods(c.newSg())).singleElement().satisfies(p -> {
            assertThat(p.getStatus()).isEqualTo(BillingPeriodStatus.REFUNDED);
            assertThat(p.getRefundedAmount()).isEqualByComparingTo("700000");
        });
        assertThat(studentBalance(c.student())).isEqualByComparingTo("0");
        assertThat(inTx(() -> linkRepo.existsByOldStudentGroupIdAndNewStudentGroupId(c.oldSg(), c.newSg()))).isTrue();
        // kassa va to'lovlarga tegilmagan
        assertThat(inTx(() -> paymentRepo.count())).isEqualTo(paymentsBefore);
        assertThat(inTx(() -> cashRepo.count())).isEqualTo(cashBefore);

        // idempotent: ikkinchi marta — hech narsa
        RepairResult again = run(pairs(c.oldSg(), c.newSg()), false);
        assertThat(again.applied()).isZero();
        assertThat(again.rows().get(0).reasonCode()).isEqualTo("REPAIRED");
        assertThat(run(affected(), false).requested()).isZero();
        assertThat(sg(c.newSg()).getBalance()).isEqualByComparingTo("0");
        assertThat(row(detect(), c.oldSg(), c.newSg()).classification()).isEqualTo(Classification.TRANSFER_OK);

        // transfer davomi: keyingi davr eski davr tugagach
        accrue(c.newSg(), "25.12.2026");
        assertThat(periods(c.newSg())).hasSize(2).last().satisfies(p -> {
            assertThat(p.getPeriodStart()).isEqualTo(d("25.12.2026"));
            assertThat(p.getStatus()).isEqualTo(BillingPeriodStatus.CHARGED);
        });
        assertThat(sg(c.newSg()).getBalance()).isEqualByComparingTo("-700000");
    }

    // ── 2) NO_LOSS ──────────────────────────────────────────────────────

    @Test
    void unpaidOldPeriod_isNoLoss_andRepairSkips() {
        Long student = fixtures.student();
        Long a = group();
        Long b = group();
        Long oldSg = enroll(student, a, "25.11.2026");
        Long newSg = manualAdd(student, b, "27.11.2026");
        manualExit(student, a, "27.11.2026");
        clock.setDate(d("28.11.2026"));

        Row row = row(detect(), oldSg, newSg);
        assertThat(row.classification()).isEqualTo(Classification.NO_LOSS);
        assertThat(row.reasonCode()).isEqualTo("NO_LOSS_UNPAID");
        assertThat(row.unusedAmount()).isEqualByComparingTo("0");

        RepairResult r = run(pairs(oldSg, newSg), false);
        assertThat(r.applied()).isZero();
        assertThat(r.rows().get(0).reasonCode()).isEqualTo("NO_LOSS");
        assertThat(sg(newSg).getBalance()).isEqualByComparingTo("-700000");
    }

    @Test
    void fullyUsedOldPeriod_isNoLoss() {
        Long student = fixtures.student();
        Long a = group();
        Long b = group();
        Long oldSg = enroll(student, a, "25.10.2026");
        pay(student, a, 700_000, "26.10.2026");
        manualExit(student, a, "24.11.2026");
        Long newSg = manualAdd(student, b, "26.11.2026");
        clock.setDate(d("28.11.2026"));

        Row row = row(detect(), oldSg, newSg);

        assertThat(row.classification()).isEqualTo(Classification.NO_LOSS);
        assertThat(row.reasonCode()).isEqualTo("NO_LOSS");
        assertThat(row.continuedAnchor()).isEqualTo(d("25.11.2026"));
        assertThat(row.refundAmount()).isEqualByComparingTo("0");
    }

    // ── 3) UNCLEAR ──────────────────────────────────────────────────────

    @Test
    void multipleCandidates_trial_refundOnly_areUnclear() {
        // bir nechta nomzod
        Long s1 = fixtures.student();
        Long a1 = group();
        Long old1 = enroll(s1, a1, "25.11.2026");
        pay(s1, a1, 700_000, "26.11.2026");
        Long n1 = manualAdd(s1, group(), "27.11.2026");
        Long n2 = manualAdd(s1, group(), "27.11.2026");
        manualExit(s1, a1, "27.11.2026");

        // sinov yozilmasi
        Long s2 = fixtures.student();
        Long a2 = group();
        Long old2 = enroll(s2, a2, "25.11.2026");
        pay(s2, a2, 700_000, "26.11.2026");
        Long trialSg = manualAdd(s2, group(), "27.11.2026", true);
        manualExit(s2, a2, "27.11.2026");

        // eski ko'chirish: yangi yozilmada langardan keyingi davr ham bor (REFUND_ONLY)
        Long s3 = fixtures.student();
        Long a3 = group();
        Long old3 = enroll(s3, a3, "10.10.2026");
        pay(s3, a3, 700_000, "11.10.2026");
        Long n3 = manualAdd(s3, group(), "15.10.2026");
        manualExit(s3, a3, "15.10.2026");
        accrue(n3, "16.11.2026");
        clock.setDate(d("28.11.2026"));

        Report report = detect();
        assertThat(row(report, old1, n1).reasonCode()).isEqualTo("MULTIPLE_CANDIDATES");
        assertThat(row(report, old1, n2).reasonCode()).isEqualTo("MULTIPLE_CANDIDATES");
        assertThat(row(report, old1, n1).classification()).isEqualTo(Classification.UNCLEAR);
        assertThat(row(report, old2, trialSg).classification()).isEqualTo(Classification.UNCLEAR);
        assertThat(row(report, old2, trialSg).reasonCode()).isEqualTo("TRIAL");
        Row r3 = row(report, old3, n3);
        assertThat(r3.classification()).isEqualTo(Classification.UNCLEAR);
        assertThat(r3.reasonCode()).isEqualTo("REFUND_ONLY");
        assertThat(r3.continuedAnchor()).isEqualTo(d("10.11.2026"));
        assertThat(r3.unusedAmount()).isEqualByComparingTo("700000");
        assertThat(report.affected()).isZero();

        // AFFECTED rejimi UNCLEAR ga tegmaydi; aniq juftlik bilan REFUND_ONLY qo'llanadi (langar surilmaydi)
        assertThat(run(affected(), false).requested()).isZero();
        RepairResult r = run(pairs(old3, n3), false);
        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.rows().get(0).mode()).isEqualTo("REFUND_ONLY");
        assertThat(sg(n3).getPaymentStartDate()).isEqualTo(d("15.10.2026"));
        assertThat(sg(n3).getBalance()).isEqualByComparingTo("-700000");
        assertThat(periods(n3)).extracting(BillingPeriod::getStatus)
            .containsExactly(BillingPeriodStatus.REFUNDED, BillingPeriodStatus.CHARGED);
    }

    // ── 4) zanjir A→B→C ─────────────────────────────────────────────────

    @Test
    void chain_isUnclear_explicitPairsRepairInTimeOrder() {
        Long student = fixtures.student();
        Long a = group();
        Long b = group();
        Long c = group();
        Long sgA = enroll(student, a, "25.11.2026");
        pay(student, a, 700_000, "26.11.2026");
        Long sgB = manualAdd(student, b, "27.11.2026");
        manualExit(student, a, "27.11.2026");
        Long sgC = manualAdd(student, c, "27.11.2026");
        manualExit(student, b, "27.11.2026");
        clock.setDate(d("28.11.2026"));

        Report report = detect();
        Row ab = row(report, sgA, sgB);
        Row bc = row(report, sgB, sgC);
        assertThat(report.rows()).hasSize(2);
        assertThat(ab.classification()).isEqualTo(Classification.UNCLEAR);
        assertThat(ab.reasonCode()).isEqualTo("CHAIN");
        assertThat(bc.reasonCode()).isEqualTo("CHAIN");
        assertThat(ab.chainKey()).isEqualTo(bc.chainKey()).isEqualTo(student + ":" + sgA);
        assertThat(ab.currentDebt()).isEqualByComparingTo("700000");

        // teskari tartibda berilsa ham vaqt tartibida qo'llanadi; dryRun = apply
        RepairResult dry = run(pairs(sgB, sgC, sgA, sgB), true);
        assertThat(dry.rows()).extracting(RepairRow::oldEnrollmentId).containsExactly(sgA, sgB);
        assertThat(dry.applied()).isEqualTo(2);
        assertThat(dry.rows().get(1).after().studentDebt()).isEqualByComparingTo("0");
        assertThat(sg(sgC).getBalance()).isEqualByComparingTo("-700000");

        RepairResult applied = run(pairs(sgB, sgC, sgA, sgB), false);
        assertThat(applied.applied()).isEqualTo(2);
        assertThat(applied.rows().get(1).after()).isEqualTo(dry.rows().get(1).after());
        assertThat(sg(sgC).getBalance()).isEqualByComparingTo("0");
        assertThat(sg(sgC).getPaymentStartDate()).isEqualTo(d("25.12.2026"));
        assertThat(sg(sgB).getBalance()).isEqualByComparingTo("0");
        assertThat(studentBalance(student)).isEqualByComparingTo("0");

        RepairResult again = run(pairs(sgA, sgB, sgB, sgC), false);
        assertThat(again.applied()).isZero();
        assertThat(again.rows()).extracting(RepairRow::reasonCode).containsOnly("REPAIRED");
    }

    // ── 5) oldini olish: ogohlantirishlar ───────────────────────────────

    @Test
    void addToGroupCheck_warnsWhenActiveElsewhere_andAfterRecentExit() {
        Long student = fixtures.student();
        Long a = group();
        Long b = group();
        Long oldSg = enroll(student, a, "25.11.2026");
        pay(student, a, 700_000, "26.11.2026");
        clock.setDate(d("27.11.2026"));

        List<TransferWarning> active = advisor.addToGroupWarnings(student, b, d("27.11.2026"));
        assertThat(active).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo("USE_TRANSFER");
            assertThat(w.fromEnrollmentId()).isEqualTo(oldSg);
            assertThat(w.fromActive()).isTrue();
            assertThat(w.unusedAmount()).isEqualByComparingTo("700000");
            assertThat(w.continuedAnchor()).isEqualTo(d("25.12.2026"));
        });
        // hisob eski davr tugagach boshlansa — ikki marta hisob yo'q
        assertThat(advisor.addToGroupWarnings(student, b, d("25.12.2026"))).singleElement()
            .satisfies(w -> assertThat(w.unusedAmount()).isEqualByComparingTo("0"));

        // chiqqandan keyin (LEFT ham) 7 kun ichida — ishlatilmagan summa bilan
        fixtures.loginAs(UserRole.ADMIN);
        groupService.removeStudentFromGroup(a, student, "LEFT", null, null);
        clock.setDate(d("29.11.2026"));
        assertThat(advisor.addToGroupWarnings(student, b, null)).singleElement().satisfies(w -> {
            assertThat(w.fromActive()).isFalse();
            assertThat(w.fromEnrollmentId()).isEqualTo(oldSg);
            assertThat(w.unusedAmount()).isEqualByComparingTo("700000");
        });
        // 7 kundan keyin — yo'q
        clock.setDate(d("05.12.2026"));
        assertThat(advisor.addToGroupWarnings(student, b, null)).isEmpty();
    }

    @Test
    void exitWithoutOtherActiveEnrollment_hasNoWarning() {
        Long student = fixtures.student();
        Long a = group();
        enroll(student, a, "25.11.2026");
        assertThat(manualExit(student, a, "27.11.2026")).isEmpty();
    }

    @Test
    @WithMockUser(username = "test-admin", roles = "ADMIN")
    void api_addToGroup_returnsMetaWarnings_andExitReasonsHaveTransferHint() throws Exception {
        fixtures.user(UserRole.ADMIN, "test-admin");
        Long student = fixtures.student();
        Long a = group();
        Long b = group();
        Long oldSg = enroll(student, a, "25.11.2026");
        clock.setDate(d("27.11.2026"));

        mvc.perform(get("/api/students/" + student + "/add-to-group-check").param("groupId", b.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.warnings[0].code").value("USE_TRANSFER"))
            .andExpect(jsonPath("$.data.warnings[0].fromEnrollmentId").value(oldSg));

        mvc.perform(post("/api/groups/students").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":" + student + ",\"groupId\":" + b
                    + ",\"joinDate\":\"2026-11-27\",\"paymentStartDate\":\"2026-11-27\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data").value("OK"))
            .andExpect(jsonPath("$.meta.warnings[0].code").value("USE_TRANSFER"));

        mvc.perform(post("/api/groups/" + a + "/remove-student").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":" + student + ",\"reason\":\"TRANSFERRED\",\"reasonCode\":\"TRANSFERRED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.warnings[0].code").value("USE_TRANSFER"));

        mvc.perform(get("/api/enums/exit-reasons"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.value == 'TRANSFERRED')].transferHint").value(true))
            .andExpect(jsonPath("$.data[?(@.value == 'PRICE')].transferHint").isEmpty());

        // ta'mir endpointlari faqat SA
        mvc.perform(get("/api/admin/repair/manual-transfers")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "test-super_admin", roles = "SUPER_ADMIN")
    void api_detectAndRepair() throws Exception {
        Case335 c = case335();
        fixtures.loginAs(UserRole.SUPER_ADMIN);   // case335 ADMIN/ACCOUNTANT sifatida kirgan edi
        mvc.perform(get("/api/admin/repair/manual-transfers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.affected").value(1))
            .andExpect(jsonPath("$.data.rows[0].classification").value("AFFECTED"))
            .andExpect(jsonPath("$.data.rows[0].unusedAmount").value(700000));
        mvc.perform(post("/api/admin/repair/merge-into-transfer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classification\":\"AFFECTED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(true))
            .andExpect(jsonPath("$.data.applied").value(1));
        assertThat(sg(c.newSg()).getBalance()).isEqualByComparingTo("-700000");
        mvc.perform(post("/api/admin/repair/merge-into-transfer").param("dryRun", "false")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pairs\":[{\"oldEnrollmentId\":" + c.oldSg() + ",\"newEnrollmentId\":" + c.newSg() + "}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applied").value(1))
            .andExpect(jsonPath("$.data.rows[0].after.studentDebt").value(0));
        assertThat(sg(c.newSg()).getBalance()).isEqualByComparingTo("0");
    }

    // ── 6) ommaviy ko'chirish ───────────────────────────────────────────

    @Test
    void bulkTransfer_dryRunPreview_thenTransfersEligible_skipsBlocked() {
        Long a = group();
        Long b = group();
        Long paid = fixtures.student();
        Long debtor = fixtures.student();
        Long already = fixtures.student();
        Long paidSg = enroll(paid, a, "15.11.2026");
        Long debtorSg = enroll(debtor, a, "15.11.2026");
        enroll(already, a, "15.11.2026");
        enroll(already, b, "15.11.2026");
        pay(paid, a, 700_000, "16.11.2026");
        clock.setDate(d("20.11.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        TransferStudentsRequest req = new TransferStudentsRequest();
        req.setTargetGroupId(b);

        GroupBulkTransferService.Result dry = bulk.transfer(a, req, true, null);
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.canApply()).isTrue();
        assertThat(dry.transferred()).extracting(GroupBulkTransferService.Item::studentId)
            .containsExactlyInAnyOrder(paid, debtor);
        assertThat(dry.transferred()).allSatisfy(i ->
            assertThat(i.newPaymentStartDate()).isEqualTo(d("15.12.2026")));
        assertThat(dry.transferred()).filteredOn(i -> i.studentId().equals(debtor)).singleElement()
            .satisfies(i -> assertThat(i.credit()).isEqualByComparingTo("-700000"));
        assertThat(dry.skipped()).singleElement().satisfies(i -> {
            assertThat(i.studentId()).isEqualTo(already);
            assertThat(i.codes()).contains("ALREADY_IN_TARGET");
        });
        assertThat(sg(paidSg).getIsActive()).isTrue();

        GroupBulkTransferService.Result done = bulk.transfer(a, req, false, null);
        assertThat(done.transferred()).hasSize(2).allSatisfy(i -> assertThat(i.toEnrollmentId()).isNotNull());
        assertThat(done.skipped()).hasSize(1);
        assertThat(sg(paidSg).getIsActive()).isFalse();
        assertThat(sg(debtorSg).getIsActive()).isFalse();
        StudentGroup moved = inTx(() -> sgRepo.findByStudentIdAndGroupIdAndIsActiveTrue(paid, b).orElseThrow());
        assertThat(moved.getPaymentStartDate()).isEqualTo(d("15.12.2026"));
        assertThat(moved.getBalance()).isEqualByComparingTo("0");
        StudentGroup movedDebt = inTx(() -> sgRepo.findByStudentIdAndGroupIdAndIsActiveTrue(debtor, b).orElseThrow());
        assertThat(movedDebt.getBalance()).isEqualByComparingTo("-700000");
        // transfer oqimi bilan — aniqlashda TRANSFER_OK
        clock.setDate(d("21.11.2026"));
        assertThat(detect().rows()).isNotEmpty().allSatisfy(r ->
            assertThat(r.classification()).isEqualTo(Classification.TRANSFER_OK));
    }

    // ── 7) SQL (docs/ops/exit-readd-detect.sql) ↔ Java ──────────────────

    @Test
    void detectionSql_matchesJava_onPostgres() throws Exception {
        boolean postgres = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) con ->
            con.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")));
        Assumptions.assumeTrue(postgres, "faqat pgtest (PostgreSQL)");

        case335();
        // NO_LOSS
        Long s2 = fixtures.student();
        Long a2 = group();
        enroll(s2, a2, "25.11.2026");
        manualAdd(s2, group(), "27.11.2026");
        manualExit(s2, a2, "27.11.2026");
        // zanjir
        Long s3 = fixtures.student();
        Long a3 = group();
        Long b3 = group();
        enroll(s3, a3, "25.11.2026");
        pay(s3, a3, 700_000, "26.11.2026");
        manualAdd(s3, b3, "27.11.2026");
        manualExit(s3, a3, "27.11.2026");
        manualAdd(s3, group(), "27.11.2026");
        manualExit(s3, b3, "27.11.2026");
        // sinov va REFUND_ONLY
        Long s4 = fixtures.student();
        Long a4 = group();
        enroll(s4, a4, "10.10.2026");
        pay(s4, a4, 700_000, "11.10.2026");
        Long n4 = manualAdd(s4, group(), "15.10.2026");
        manualExit(s4, a4, "15.10.2026");
        accrue(n4, "16.11.2026");
        Long s5 = fixtures.student();
        Long a5 = group();
        enroll(s5, a5, "25.11.2026");
        manualAdd(s5, group(), "27.11.2026", true);
        manualExit(s5, a5, "27.11.2026");
        clock.setDate(d("28.11.2026"));

        Set<String> java = detect().rows().stream()
            .map(r -> r.oldEnrollmentId() + ">" + r.newEnrollmentId() + ":" + r.classification() + ":" + r.reasonCode()
                + ":" + r.unusedAmount().stripTrailingZeros().toPlainString())
            .collect(Collectors.toSet());

        String sql = Files.readString(Path.of("docs", "ops", "exit-readd-detect.sql"), StandardCharsets.UTF_8);
        String runnable = sql.lines().filter(l -> !l.strip().startsWith("\\")).collect(Collectors.joining("\n"))
            .replace(":'to'", "'2026-11-28'").replace(":'from'", "''").trim();
        if (runnable.endsWith(";")) {
            runnable = runnable.substring(0, runnable.length() - 1);
        }
        List<Map<String, Object>> sqlRows = jdbc.queryForList(runnable);
        Set<String> fromSql = new HashSet<>();
        for (Map<String, Object> m : sqlRows) {
            fromSql.add(m.get("old_enrollment_id") + ">" + m.get("new_enrollment_id") + ":" + m.get("classification")
                + ":" + m.get("reason_code") + ":" + new BigDecimal(m.get("unused_amount").toString())
                .stripTrailingZeros().toPlainString());
        }
        assertThat(fromSql).isEqualTo(java);
        assertThat(java).hasSize(6);
    }
}

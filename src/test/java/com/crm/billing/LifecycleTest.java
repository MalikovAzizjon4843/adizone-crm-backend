package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.FreezeStudentRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.TransferGroupRequest;
import com.crm.dto.request.UnfreezeStudentRequest;
import com.crm.dto.response.BillingLineDto;
import com.crm.dto.response.FreezeStudentResponse;
import com.crm.entity.Attendance;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.StudentStatusHistoryRepository;
import com.crm.service.PaymentService;
import com.crm.service.StudentPaymentLifecycleService;
import com.crm.service.StudentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 5-bosqich: §6.7–§6.10, §3.6, §13 #2/#3/#25; §12 T7.x, T8.x, T3.2, T6.2, C4. */
class LifecycleTest extends AbstractBillingIT {

    @Autowired
    StudentService students;
    @Autowired
    PaymentService payments;
    @Autowired
    AccrualService accrual;
    @Autowired
    EnrollmentLifecycleService lifecycle;
    @Autowired
    LessonChargeService lessons;
    @Autowired
    StudentPaymentLifecycleService lifecycleJobs;
    @Autowired
    BillingSnapshotService snapshots;
    @Autowired
    StudentGroupRepository sgRepo;
    @Autowired
    StudentRepository studentRepo;
    @Autowired
    GroupRepository groupRepo;
    @Autowired
    BalanceTransactionRepository txRepo;
    @Autowired
    BillingPeriodRepository periodRepo;
    @Autowired
    AttendanceRepository attendanceRepo;
    @Autowired
    StudentStatusHistoryRepository historyRepo;

    // ── yordamchilar ────────────────────────────────────────────────────

    record Ids(Long student, Long group, Long sg) {
    }

    /** 700 000 × 10% = 630 000, langar {@code start}. */
    private Ids monthly(String start) {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d(start)).discount("10").save();
        return new Ids(student, group, sg);
    }

    private void accrueOn(Ids ids, String date) {
        clock.setDate(d(date));
        accrual.accrueUpTo(ids.sg(), d(date));
    }

    private void pay(Ids ids, long amount, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(ids.student());
        r.setGroupId(ids.group());
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
    }

    private FreezeStudentResponse freeze(Ids ids, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ADMIN);
        FreezeStudentRequest r = new FreezeStudentRequest();
        r.setGroupId(ids.group());
        return students.freezeStudent(ids.student(), r);
    }

    private StudentGroup sg(Long id) {
        return inTx(() -> sgRepo.findById(id).orElseThrow());
    }

    private BigDecimal balance(Long sgId) {
        return sg(sgId).getBalance();
    }

    private List<BalanceTransaction> ledger(Long sgId) {
        return inTx(() -> txRepo.findByStudentGroup_IdOrderByIdAsc(sgId));
    }

    private List<BillingPeriod> periods(Long sgId) {
        return inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sgId));
    }

    private StudentStatus studentStatus(Long id) {
        return inTx(() -> studentRepo.findById(id).orElseThrow().getStatus());
    }

    private static List<String> shape(List<BillingLineDto> lines) {
        return lines.stream()
            .map(l -> l.getType() + " " + l.getAmount().stripTrailingZeros().toPlainString() + " " + l.getEffectiveDate())
            .toList();
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
            .isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode())
            .isEqualTo(code);
    }

    private Long attend(Ids ids, String date, AttendanceStatus status) {
        return inTx(() -> {
            Attendance a = attendanceRepo.findByStudentIdAndGroupIdAndAttendanceDate(ids.student(), ids.group(), d(date))
                .orElseGet(() -> Attendance.builder()
                    .student(studentRepo.findById(ids.student()).orElseThrow())
                    .group(groupRepo.findById(ids.group()).orElseThrow())
                    .attendanceDate(d(date))
                    .build());
            a.setStatus(status);
            Attendance saved = attendanceRepo.save(a);
            lessons.sync(saved);
            return saved.getId();
        });
    }

    // ── T7.x muzlatish ──────────────────────────────────────────────────

    @Test
    void freeze_proportionalRefund30() {
        Ids ids = monthly("15.09.2026");
        accrueOn(ids, "15.09.2026");
        pay(ids, 630_000, "16.09.2026");

        FreezeStudentResponse res = freeze(ids, "28.09.2026");

        assertThat(shape(res.getRefundLines())).containsExactly("PERIOD_REFUND 357000 2026-09-28");
        assertThat(res.getBalanceAfter()).isEqualByComparingTo("357000");
        assertThat(res.getStatusAfter()).isEqualTo("FROZEN");
        assertThat(res.getStudentStatusAfter()).isEqualTo("FROZEN");
        StudentGroup after = sg(ids.sg());
        assertThat(after.getFrozenFrom()).isEqualTo(d("28.09.2026"));
        assertThat(after.getIsActive()).isFalse();
        assertThat(after.getBalance()).isEqualByComparingTo("357000");
        assertThat(studentStatus(ids.student())).isEqualTo(StudentStatus.FROZEN);
        BillingPeriod p = periods(ids.sg()).get(0);
        assertThat(p.getStatus()).isEqualTo(BillingPeriodStatus.PARTIALLY_REFUNDED);
        assertThat(p.getRefundedAmount()).isEqualByComparingTo("357000");
        BalanceTransaction refund = ledger(ids.sg()).get(2);
        assertThat(refund.getRelatedTxId()).isEqualTo(p.getChargeTxId());
        assertThat(refund.getBillingPeriodId()).isEqualTo(p.getId());
    }

    @Test
    void freeze_proportionalRefund31() {
        Ids a = monthly("15.10.2026");
        accrueOn(a, "15.10.2026");
        assertThat(shape(freeze(a, "01.11.2026").getRefundLines()))
            .containsExactly("PERIOD_REFUND 284516 2026-11-01");

        Ids b = monthly("15.10.2026");
        accrueOn(b, "15.10.2026");
        FreezeStudentResponse full = freeze(b, "15.10.2026");
        assertThat(shape(full.getRefundLines())).containsExactly("PERIOD_REFUND 630000 2026-10-15");
        assertThat(periods(b.sg()).get(0).getStatus()).isEqualTo(BillingPeriodStatus.REFUNDED);
        assertThat(balance(b.sg())).isEqualByComparingTo("0");
    }

    @Test
    void freeze_onlySelectedEnrollment() {
        Ids a = monthly("15.09.2026");
        Long groupB = fixtures.group(fixtures.course(500_000));
        Long sgB = fixtures.enrollment(a.student(), groupB).start(d("15.09.2026")).save();
        Ids b = new Ids(a.student(), groupB, sgB);
        clock.setDate(d("20.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);

        assertCode(() -> students.freezeStudent(a.student(), new FreezeStudentRequest()), "payment.group.required");

        freeze(a, "20.09.2026");
        assertThat(studentStatus(a.student())).isEqualTo(StudentStatus.ACTIVE);
        assertThat(sg(sgB).getIsActive()).isTrue();
        assertThat(sg(sgB).getFrozenFrom()).isNull();

        FreezeStudentResponse second = freeze(b, "20.09.2026");
        assertThat(second.getStudentStatusAfter()).isEqualTo("FROZEN");
        assertThat(studentStatus(a.student())).isEqualTo(StudentStatus.FROZEN);
    }

    @Test
    void unfreeze_reanchors() {
        Ids ids = monthly("15.09.2026");
        accrueOn(ids, "15.09.2026");
        pay(ids, 630_000, "16.09.2026");
        freeze(ids, "28.09.2026");

        accrueOn(ids, "15.10.2026");
        assertThat(periods(ids.sg())).hasSize(1);
        assertThat(balance(ids.sg())).isEqualByComparingTo("357000");

        clock.setDate(d("20.10.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        UnfreezeStudentRequest r = new UnfreezeStudentRequest();
        r.setGroupId(ids.group());
        r.setPaymentStartDate(d("20.10.2026"));
        students.unfreezeStudent(ids.student(), r);

        StudentGroup after = sg(ids.sg());
        assertThat(after.getId()).isEqualTo(ids.sg());
        assertThat(after.getIsActive()).isTrue();
        assertThat(after.getFrozenFrom()).isNull();
        assertThat(after.getBalance()).isEqualByComparingTo("-273000");
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(after.getNextPaymentDate()).isEqualTo(d("20.10.2026"));
        assertThat(after.getNextPaymentAmount()).isEqualByComparingTo("273000");
        assertThat(periods(ids.sg())).extracting(BillingPeriod::getPeriodStart)
            .containsExactly(d("15.09.2026"), d("20.10.2026"));
        assertThat(inTx(() -> sgRepo.findByStudentId(ids.student()))).hasSize(1);
        assertThat(studentStatus(ids.student())).isEqualTo(StudentStatus.ACTIVE);
    }

    @Test
    void unfreeze_overlappingAnchorRejected() {
        Ids ids = monthly("15.09.2026");
        accrueOn(ids, "15.09.2026");
        freeze(ids, "28.09.2026");
        clock.setDate(d("01.10.2026"));
        UnfreezeStudentRequest r = new UnfreezeStudentRequest();
        r.setGroupId(ids.group());
        r.setPaymentStartDate(d("20.09.2026"));
        assertThatThrownBy(() -> students.unfreezeStudent(ids.student(), r))
            .isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode()).isEqualTo("billing.anchor.overlap");
    }

    @Test
    void freeze_unpaidKeepsDebt() {
        Ids ids = monthly("15.09.2026");
        accrueOn(ids, "15.09.2026");

        FreezeStudentResponse res = freeze(ids, "28.09.2026");

        assertThat(res.getBalanceAfter()).isEqualByComparingTo("-273000");
        assertThat(res.getStatusAfter()).isEqualTo("OVERDUE");
        StudentGroup after = sg(ids.sg());
        assertThat(after.getDebtSince()).isEqualTo(d("15.09.2026"));
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
    }

    @Test
    void freezePreview_equalsFreeze() {
        Ids ids = monthly("15.09.2026");
        clock.setDate(d("28.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        FreezeStudentRequest r = new FreezeStudentRequest();
        r.setGroupId(ids.group());

        FreezeStudentResponse preview = students.previewFreeze(ids.student(), r);
        FreezeStudentResponse done = students.freezeStudent(ids.student(), r);

        assertThat(shape(preview.getRefundLines())).containsExactly(
            "PERIOD_CHARGE -630000 2026-09-15", "PERIOD_REFUND 357000 2026-09-28");
        assertThat(shape(done.getRefundLines())).isEqualTo(shape(preview.getRefundLines()));
        assertThat(done.getBalanceAfter()).isEqualByComparingTo(preview.getBalanceAfter());
        assertThat(done.getStatusAfter()).isEqualTo(preview.getStatusAfter());
        assertThat(done.getRefundTotal()).isEqualByComparingTo(preview.getRefundTotal());
    }

    /** §13 #2. */
    @Test
    void freezeDate_rules() {
        Ids ids = monthly("15.09.2026");
        clock.setDate(d("20.10.2026"));
        attend(ids, "10.10.2026", AttendanceStatus.PRESENT);
        fixtures.loginAs(UserRole.ADMIN);
        FreezeStudentRequest r = new FreezeStudentRequest();
        r.setGroupId(ids.group());

        r.setFreezeDate(d("21.10.2026"));
        assertCode(() -> students.freezeStudent(ids.student(), r), "freeze.date.future");
        r.setFreezeDate(d("19.09.2026"));
        assertCode(() -> students.freezeStudent(ids.student(), r), "freeze.date.tooOld");
        r.setFreezeDate(d("09.10.2026"));
        assertCode(() -> students.freezeStudent(ids.student(), r), "freeze.date.beforeAttendance");
        r.setFreezeDate(d("12.10.2026"));
        assertThat(students.freezeStudent(ids.student(), r).getFreezeDate()).isEqualTo(d("12.10.2026"));
    }

    /** §13 #25: avto-arxiv §6.7 muzlatishni chaqiradi (qaytarim va tarix bilan). */
    @Test
    void autoArchive_usesFreezeFlow() {
        Ids ids = monthly("15.09.2026");
        clock.setDate(d("15.09.2026"));
        attend(ids, "15.09.2026", AttendanceStatus.PRESENT);
        accrueOn(ids, "15.09.2026");
        pay(ids, 630_000, "15.09.2026");
        accrueOn(ids, "15.10.2026");
        Ids fresh = monthly("15.09.2026");
        attend(fresh, "14.10.2026", AttendanceStatus.PRESENT);

        clock.setDate(d("20.10.2026"));
        lifecycleJobs.archiveInactiveStudents();

        StudentGroup after = sg(ids.sg());
        assertThat(after.getFrozenFrom()).isEqualTo(d("20.10.2026"));
        // 15.10–14.11 (31 kun), 20.10 dan 26 kun ishlatilmagan: 630 000 × 26 / 31 = 528 387
        assertThat(ledger(ids.sg()).get(ledger(ids.sg()).size() - 1).getAmount()).isEqualByComparingTo("528387");
        assertThat(studentStatus(ids.student())).isEqualTo(StudentStatus.FROZEN);
        assertThat(inTx(() -> historyRepo.findAll()).stream()
            .anyMatch(h -> "AUTO_ARCHIVE".equals(h.getReason()))).isTrue();
        assertThat(sg(fresh.sg()).getIsActive()).isTrue();
    }

    // ── T8.x transfer ───────────────────────────────────────────────────

    private Ids overridden600(String start) {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d(start)).override(600_000).discount("10").save();
        return new Ids(student, group, sg);
    }

    private StudentGroup transfer(Ids from, Long toGroup, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ADMIN);
        TransferGroupRequest r = new TransferGroupRequest();
        r.setFromGroupId(from.group());
        r.setToGroupId(toGroup);
        students.transferGroup(from.student(), r);
        return inTx(() -> sgRepo.findByStudentIdAndGroupIdAndIsActiveTrue(from.student(), toGroup).orElseThrow());
    }

    @Test
    void transfer_movesPositiveBalanceAndPricing() {
        Ids a = overridden600("15.10.2026");
        accrueOn(a, "15.10.2026");
        pay(a, 1_080_000, "16.10.2026");
        Long groupB = fixtures.group(fixtures.course(900_000));

        StudentGroup to = transfer(a, groupB, "25.10.2026");

        assertThat(ledger(a.sg())).last().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(BalanceTransactionType.TRANSFER_OUT);
            assertThat(t.getAmount()).isEqualByComparingTo("-540000");
        });
        assertThat(ledger(to.getId())).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(BalanceTransactionType.TRANSFER_IN);
            assertThat(t.getAmount()).isEqualByComparingTo("540000");
            assertThat(t.getEffectiveDate()).isEqualTo(d("25.10.2026"));
        });
        assertThat(balance(a.sg())).isEqualByComparingTo("0");
        assertThat(sg(a.sg()).getIsActive()).isFalse();
        assertThat(sg(a.sg()).getExitReason()).isEqualTo("TRANSFERRED");
        assertThat(to.getMonthlyPriceOverride()).isEqualByComparingTo("600000");
        assertThat(to.getDiscountPercentage()).isEqualByComparingTo("10");
        assertThat(to.getPaymentType()).isEqualTo(PaymentType.MONTHLY);
        assertThat(to.getPaymentStartDate()).isEqualTo(d("15.11.2026"));
        assertThat(to.getBalance()).isEqualByComparingTo("540000");

        accrual.accrueUpTo(to.getId(), d("15.11.2026"));
        assertThat(balance(to.getId())).isEqualByComparingTo("0");
    }

    @Test
    void transfer_movesDebtWithDebtSince() {
        Ids a = overridden600("15.10.2026");
        accrueOn(a, "15.10.2026");
        Long groupB = fixtures.group(fixtures.course(700_000));

        StudentGroup to = transfer(a, groupB, "25.10.2026");

        assertThat(to.getBalance()).isEqualByComparingTo("-540000");
        assertThat(to.getDebtSince()).isEqualTo(d("15.10.2026"));
        assertThat(to.getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(ledger(to.getId()).get(0).getEffectiveDate()).isEqualTo(d("15.10.2026"));
        assertThat(sg(a.sg()).getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void transfer_perLesson() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000, 80_000L));
        Long sgId = fixtures.enrollment(student, group).start(d("01.10.2026")).perLesson(80_000).save();
        Ids a = new Ids(student, group, sgId);
        pay(a, 240_000, "02.10.2026");
        Long groupB = fixtures.group(fixtures.course(700_000, 90_000L));

        StudentGroup to = transfer(a, groupB, "05.10.2026");

        assertThat(to.getPaymentType()).isEqualTo(PaymentType.PER_LESSON);
        assertThat(to.getLessonPrice()).isEqualByComparingTo("80000");
        assertThat(to.getBalance()).isEqualByComparingTo("240000");
        assertThat(balance(sgId)).isEqualByComparingTo("0");
    }

    @Test
    void transfer_noDoubleChargeInCurrentPeriod() {
        Ids a = monthly("15.10.2026");
        accrueOn(a, "15.10.2026");
        Long groupB = fixtures.group(fixtures.course(700_000));

        StudentGroup to = transfer(a, groupB, "25.10.2026");
        accrual.accrueUpTo(to.getId(), d("25.10.2026"));

        assertThat(periods(to.getId())).isEmpty();
        assertThat(to.getPaymentStartDate()).isEqualTo(d("15.11.2026"));
        accrueOn(new Ids(a.student(), groupB, to.getId()), "15.11.2026");
        assertThat(periods(to.getId())).hasSize(1);
        assertThat(periods(a.sg())).hasSize(1);
    }

    // ── §6.10 guruhdan chiqish ──────────────────────────────────────────

    @Test
    void leave_noRefund_noFurtherCharges_balanceStays() {
        Ids ids = monthly("15.09.2026");
        clock.setDate(d("20.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        inTx(() -> lifecycle.leave(ids.student(), ids.group(), "LEFT", "Ketdi"));

        assertThat(ledger(ids.sg())).extracting(BalanceTransaction::getType)
            .containsExactly(BalanceTransactionType.PERIOD_CHARGE);
        assertThat(balance(ids.sg())).isEqualByComparingTo("-630000");
        accrueOn(ids, "15.10.2026");
        assertThat(periods(ids.sg())).hasSize(1);
        StudentGroup after = sg(ids.sg());
        assertThat(after.getIsActive()).isFalse();
        assertThat(after.getLeaveDate()).isEqualTo(d("20.09.2026"));
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
    }

    // ── §3.6 langar ─────────────────────────────────────────────────────

    @Test
    void reanchor_overlapAndAccountantBackdate() {
        Ids ids = monthly("15.09.2026");
        accrueOn(ids, "15.09.2026");
        clock.setDate(d("20.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        assertCode(() -> students.updatePaymentStartDate(ids.student(), null, d("01.10.2026"), null),
            "billing.anchor.overlap");

        Ids other = monthly("15.10.2026");
        fixtures.loginAs(UserRole.ACCOUNTANT);
        assertCode(() -> students.updatePaymentStartDate(other.student(), null, d("01.08.2026"), null),
            "billing.anchor.backdateForbidden");
        fixtures.loginAs(UserRole.ADMIN);
        students.updatePaymentStartDate(other.student(), null, d("01.08.2026"), null);
        assertThat(periods(other.sg())).extracting(BillingPeriod::getPeriodStart)
            .containsExactly(d("01.08.2026"), d("01.09.2026"));
    }

    // ── PER_LESSON (§6.9, T3.2, T6.2, C4) ──────────────────────────────

    private Ids perLesson(long price, String discount, boolean trial) {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000, price));
        var b = fixtures.enrollment(student, group).start(d("01.10.2026")).perLesson(price).discount(discount);
        if (trial) {
            b.trial();
        }
        return new Ids(student, group, b.save());
    }

    @Test
    void perLesson_discountPriceChangeAndRefundOfOriginal() {
        Ids ids = perLesson(80_000, "10", false);
        clock.setDate(d("13.10.2026"));

        attend(ids, "06.10.2026", AttendanceStatus.PRESENT);
        Long a08 = attend(ids, "08.10.2026", AttendanceStatus.PRESENT);
        assertThat(balance(ids.sg())).isEqualByComparingTo("-144000");

        attend(ids, "08.10.2026", AttendanceStatus.PRESENT);      // double-click (C4)
        attend(ids, "08.10.2026", AttendanceStatus.ABSENT);       // billable → billable
        assertThat(ledger(ids.sg())).hasSize(2);

        inTx(() -> {
            StudentGroup s = sgRepo.findById(ids.sg()).orElseThrow();
            s.setLessonPrice(BigDecimal.valueOf(90_000));
            sgRepo.save(s);
        });
        attend(ids, "08.10.2026", AttendanceStatus.EXCUSED);
        BalanceTransaction refund = ledger(ids.sg()).get(2);
        assertThat(refund.getType()).isEqualTo(BalanceTransactionType.LESSON_REFUND);
        assertThat(refund.getAmount()).isEqualByComparingTo("72000");
        assertThat(refund.getReferenceId()).isEqualTo(a08);

        attend(ids, "13.10.2026", AttendanceStatus.PRESENT);
        assertThat(ledger(ids.sg()).get(3).getAmount()).isEqualByComparingTo("-81000");
        assertThat(balance(ids.sg())).isEqualByComparingTo("-153000");

        attend(ids, "08.10.2026", AttendanceStatus.EXCUSED);      // takror — yozuv yo'q
        attend(ids, "08.10.2026", AttendanceStatus.PRESENT);      // qayta billable — yangi narx
        assertThat(ledger(ids.sg())).last().satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("-81000"));
        assertThat(balance(ids.sg())).isEqualByComparingTo("-234000");
    }

    @Test
    void perLesson_trialNoCharge_thenConverted() {
        Ids ids = perLesson(80_000, "0", true);
        clock.setDate(d("05.10.2026"));
        attend(ids, "01.10.2026", AttendanceStatus.PRESENT);
        attend(ids, "03.10.2026", AttendanceStatus.PRESENT);
        assertThat(ledger(ids.sg())).isEmpty();

        clock.setDate(d("06.10.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        students.updatePaymentStartDate(ids.student(), null, d("06.10.2026"), false);
        attend(ids, "03.10.2026", AttendanceStatus.LATE);          // sinovdagi dars tahrirlandi — charge yo'q
        assertThat(ledger(ids.sg())).isEmpty();
        attend(ids, "06.10.2026", AttendanceStatus.PRESENT);
        assertThat(ledger(ids.sg())).singleElement()
            .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("-80000"));
    }

    @Test
    void perLesson_frozenKeepsBalance_noLedgerOnFreeze() {
        Ids ids = perLesson(80_000, "0", false);
        pay(ids, 240_000, "02.10.2026");
        FreezeStudentResponse res = freeze(ids, "05.10.2026");
        assertThat(res.getRefundLines()).isEmpty();
        assertThat(balance(ids.sg())).isEqualByComparingTo("240000");
        attend(ids, "05.10.2026", AttendanceStatus.PRESENT);       // muzlatilgan SG — charge yo'q
        assertThat(ledger(ids.sg())).extracting(BalanceTransaction::getType)
            .containsExactly(BalanceTransactionType.PAYMENT);
    }

    @Test
    void onLessonAttended_isIdempotent() {
        Ids ids = perLesson(80_000, "0", false);
        clock.setDate(d("08.10.2026"));
        attend(ids, "06.10.2026", AttendanceStatus.PRESENT);
        attend(ids, "08.10.2026", AttendanceStatus.PRESENT);
        lifecycleJobs.onLessonAttended(ids.student(), ids.group(), d("08.10.2026"));
        lifecycleJobs.onLessonAttended(ids.student(), ids.group(), d("08.10.2026"));

        StudentGroup after = sg(ids.sg());
        assertThat(after.getLessonsAttended()).isEqualTo(2);
        assertThat(after.getFirstLessonDate()).isEqualTo(d("06.10.2026"));
    }

}

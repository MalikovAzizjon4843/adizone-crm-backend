package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.dto.response.ExpectedPaymentsResponse;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.service.AnalyticsService;
import com.crm.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 3-bosqich: §4 (holat, FIFO, nextPaymentDate, yagona qarzdor ta'rifi), §8, §12 T2.x. */
class BillingStatusTest extends AbstractBillingIT {

    @Autowired
    AccrualService accrual;
    @Autowired
    LedgerService ledger;
    @Autowired
    BillingLocks locks;
    @Autowired
    BillingSnapshotService snapshots;
    @Autowired
    BillingStatusService status;
    @Autowired
    DebtorService debtors;
    @Autowired
    BillingProperties properties;
    @Autowired
    StudentGroupRepository sgRepo;
    @Autowired
    StudentRepository studentRepo;
    @Autowired
    BalanceTransactionRepository txRepo;
    @Autowired
    DashboardService dashboard;
    @Autowired
    AnalyticsService analytics;

    // ── yordamchilar ────────────────────────────────────────────────────

    private Long monthly(Long studentId, Long groupId, long fee, String start, String discount) {
        return fixtures.enrollment(studentId, groupId).start(d(start)).discount(discount).save();
    }

    private Long sg(long fee, String start, String discount) {
        return monthly(fixtures.student(), fixtures.group(fixtures.course(fee)), fee, start, discount);
    }

    /** Bugun = date, accrual va snapshot. */
    private void accrueOn(Long sgId, String date) {
        clock.setDate(d(date));
        accrual.accrueUpTo(sgId, d(date));
    }

    /** To'lov oqimi 4-bosqichda; bu yerda faqat ledger krediti (+ snapshot). */
    private void credit(Long sgId, long amount, String date) {
        clock.setDate(d(date));
        inTx(() -> {
            Long studentId = sgRepo.findStudentIdById(sgId).orElseThrow();
            StudentGroup locked = locks.lockEnrollmentWithStudent(studentId, sgId);
            ledger.post(LedgerService.Entry.builder().enrollment(locked)
                .type(BalanceTransactionType.PAYMENT).amount(BigDecimal.valueOf(amount))
                .effectiveDate(d(date)).build());
            snapshots.refresh(locked);
        });
    }

    private void today(String date) {
        clock.setDate(d(date));
        snapshots.refreshAllDue(d(date));
    }

    private StudentGroup sgNow(Long id) {
        return inTx(() -> sgRepo.findById(id).orElseThrow());
    }

    private BillingSnapshot snap(Long id) {
        return inTx(() -> status.snapshot(sgRepo.findById(id).orElseThrow(), clock.today()));
    }

    // ── T2.1 ───────────────────────────────────────────────────────────

    @Test
    void status_graceBoundary() {
        // R1 (§14.1): grace standarti 0, OVERDUE ⇔ today − debtSince ≥ grace — muddat kunining o'zida
        LocalDate debtSince = d("15.10.2026");
        BigDecimal neg = BigDecimal.valueOf(-630_000);
        assertThat(status.statusOf(neg, debtSince, d("14.10.2026"))).isEqualTo(PaymentStatus.PENDING);
        assertThat(status.statusOf(neg, debtSince, d("15.10.2026"))).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(status.statusOf(neg, debtSince, d("19.10.2026"))).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(status.statusOf(BigDecimal.ZERO, null, d("19.10.2026"))).isEqualTo(PaymentStatus.PAID);

        int saved = properties.getGraceDays();
        properties.setGraceDays(5);
        try {
            assertThat(status.statusOf(neg, debtSince, d("19.10.2026"))).isEqualTo(PaymentStatus.PENDING);
            assertThat(status.statusOf(neg, debtSince, d("20.10.2026"))).isEqualTo(PaymentStatus.OVERDUE);
        } finally {
            properties.setGraceDays(saved);
        }

        // saqlangan snapshot: charge yozilgan kuni — qarzdor
        Long sg = sg(630_000, "15.10.2026", "0");
        accrueOn(sg, "15.10.2026");
        assertThat(sgNow(sg).getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(sgNow(sg).getDebtSince()).isEqualTo(d("15.10.2026"));
    }

    /** T1.3 davomi: quvib yetilgan charge holati 15.10 dan hisoblanadi. */
    @Test
    void catchUp_statusFromEffectiveDate() {
        Long sg = sg(630_000, "15.09.2026", "0");
        accrueOn(sg, "15.09.2026");
        credit(sg, 630_000, "16.09.2026");
        accrueOn(sg, "17.10.2026");
        assertThat(sgNow(sg).getDebtSince()).isEqualTo(d("15.10.2026"));
        // quvib yetilgan charge — 15.10 dan qarz (R1: muddat kunidan)
        assertThat(sgNow(sg).getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(debtors.debtors(DebtorService.Filter.defaults(), d("17.10.2026")).getStudents().get(0)
            .getDaysOverdue()).isEqualTo(2);
    }

    // ── T2.2 / §6.1 ─────────────────────────────────────────────────────

    @Test
    void status_partialPaymentKeepsDebtSince() {
        Long sg = sg(700_000, "15.09.2026", "10");
        accrueOn(sg, "15.09.2026");
        credit(sg, 630_000, "16.09.2026");
        assertThat(sgNow(sg).getNextPaymentDate()).isEqualTo(d("15.10.2026"));
        assertThat(sgNow(sg).getNextPaymentAmount()).isEqualByComparingTo("630000");

        accrueOn(sg, "15.10.2026");
        credit(sg, 300_000, "20.10.2026");
        StudentGroup s = sgNow(sg);
        assertThat(s.getBalance()).isEqualByComparingTo("-330000");
        assertThat(s.getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(s.getDebtSince()).isEqualTo(d("15.10.2026"));
        assertThat(s.getNextPaymentDate()).isEqualTo(d("15.10.2026"));
        assertThat(s.getNextPaymentAmount()).isEqualByComparingTo("330000");

        credit(sg, 330_000, "22.10.2026");
        s = sgNow(sg);
        assertThat(s.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(s.getNextPaymentDate()).isEqualTo(d("15.11.2026"));
        assertThat(s.getNextPaymentAmount()).isEqualByComparingTo("630000");
    }

    // ── T2.5 / §6.2 ─────────────────────────────────────────────────────

    @Test
    void nextPaymentDate_prepaid() {
        Long sg = sg(700_000, "15.09.2026", "10");
        accrueOn(sg, "15.09.2026");
        credit(sg, 1_890_000, "16.09.2026");
        StudentGroup s = sgNow(sg);
        assertThat(s.getBalance()).isEqualByComparingTo("1260000");
        assertThat(s.getNextPaymentDate()).isEqualTo(d("15.12.2026"));
        assertThat(s.getNextPaymentAmount()).isEqualByComparingTo("630000");

        accrueOn(sg, "15.10.2026");
        accrueOn(sg, "15.11.2026");
        assertThat(sgNow(sg).getNextPaymentDate()).isEqualTo(d("15.12.2026"));
        accrueOn(sg, "15.12.2026");
        assertThat(sgNow(sg).getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(sgNow(sg).getNextPaymentDate()).isEqualTo(d("15.12.2026"));

        Long sg2 = sg(700_000, "15.09.2026", "10");
        accrueOn(sg2, "15.09.2026");
        credit(sg2, 930_000, "16.09.2026");
        assertThat(sgNow(sg2).getNextPaymentDate()).isEqualTo(d("15.10.2026"));
        assertThat(sgNow(sg2).getNextPaymentAmount()).isEqualByComparingTo("330000");
    }

    // ── T2.6 ───────────────────────────────────────────────────────────

    @Test
    void reversal_restoresOriginalDebtSince() {
        Long sg = sg(630_000, "15.09.2026", "0");
        accrueOn(sg, "15.09.2026");
        credit(sg, 630_000, "16.09.2026");
        clock.setDate(d("17.09.2026"));
        inTx(() -> {
            Long studentId = sgRepo.findStudentIdById(sg).orElseThrow();
            StudentGroup locked = locks.lockEnrollmentWithStudent(studentId, sg);
            BalanceTransaction payment = txRepo.findLedgerForFifo(sg).stream()
                .filter(t -> t.getType() == BalanceTransactionType.PAYMENT).findFirst().orElseThrow();
            ledger.reverse(payment, "xato");
            snapshots.refresh(locked);
        });
        StudentGroup s = sgNow(sg);
        assertThat(s.getDebtSince()).isEqualTo(d("15.09.2026"));
        assertThat(s.getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
    }

    // ── T2.4 — yagona manba ──────────────────────────────────────────────

    @Test
    void debtors_singleSourceOfTruth() {
        Long teacher = fixtures.teacher();
        Long course = fixtures.course(630_000);
        clock.setDate(d("01.10.2026"));

        // 2 ta OVERDUE (15.09 dan qarz), 1 ta kecha muddati kelgan (30.09 — R1: grace 0, u ham qarzdor),
        // 1 ta PAID, 1 FROZEN + muddati o'tgan qarz, 1 TRIAL
        Long overdue1 = enroll(teacher, course, "15.09.2026", false);
        Long overdue2 = enroll(teacher, course, "15.09.2026", false);
        Long pending = enroll(teacher, course, "30.09.2026", false);
        Long paid = enroll(teacher, course, "15.09.2026", false);
        Long frozen = enroll(teacher, course, "15.09.2026", false);
        Long trial = enroll(teacher, course, "15.09.2026", true);
        for (Long id : List.of(overdue1, overdue2, pending, paid, frozen, trial)) {
            accrual.accrueUpTo(id, d("01.10.2026"));
        }
        credit(paid, 630_000, "01.10.2026");
        inTx(() -> {
            StudentGroup f = sgRepo.findById(frozen).orElseThrow();
            f.setFrozenFrom(d("28.09.2026"));
            f.setIsActive(false);
        });
        today("01.10.2026");

        LocalDate today = d("01.10.2026");
        long javaCount = inTx(() -> sgRepo.findAll().stream().filter(s -> status.isOverdue(s, today)).count());
        long sqlCount = inTx(() -> sgRepo.count(Specification.where(status.overdue(today))));
        long summary = debtors.debtorSummary(DebtorService.Filter.defaults(), today).totalDebtors();
        long dash = inTx(() -> dashboard.getStats().getDebtors());
        long anal = inTx(() -> analytics.getDashboard().getDebtorCount());
        long kpi = inTx(() -> sgRepo.countActivePaymentStatsGroupedByTeacher(status.overdueBefore(today)).stream()
            .mapToLong(r -> ((Number) r[4]).longValue()).sum());

        assertThat(List.of(javaCount, sqlCount, summary, dash, anal, kpi)).containsOnly(4L);
        assertThat(sgNow(pending).getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(sgNow(frozen).getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(sgNow(trial).getPaymentStatus()).isEqualTo(PaymentStatus.TRIAL);
        assertThat(sgNow(paid).getPaymentStatus()).isEqualTo(PaymentStatus.PAID);

        DebtorsListResponse list = debtors.debtors(DebtorService.Filter.defaults(), today);
        assertThat(list.getStudents()).hasSize(4);
        DebtorsListResponse.DebtorStudent row = list.getStudents().get(0);
        assertThat(row.getDebt()).isEqualByComparingTo("630000");
        assertThat(row.getTotalDebt()).isEqualByComparingTo(row.getDebt());
        assertThat(row.getDebtSince()).isEqualTo(d("15.09.2026"));
        assertThat(row.getDaysOverdue()).isEqualTo(16);
        assertThat(list.getStudents().get(3).getDaysOverdue()).isEqualTo(1);     // 30.09 dan
        assertThat(list.getTotalDebt()).isEqualByComparingTo("2520000");
    }

    private Long enroll(Long teacher, Long course, String start, boolean trial) {
        Long g = fixtures.group(course, GroupStatus.ACTIVE, teacher);
        var b = fixtures.enrollment(fixtures.student(), g).start(d(start));
        if (trial) {
            b.trial();
        }
        return b.save();
    }

    /** §4.5 scope: yopilgan SG qarzi default ro'yxatda yo'q, scope=ALL da bor. */
    @Test
    void debtors_scope() {
        Long sg = sg(630_000, "15.09.2026", "0");
        accrueOn(sg, "15.09.2026");
        inTx(() -> {
            StudentGroup s = sgRepo.findById(sg).orElseThrow();
            s.setIsActive(false);
            s.setLeaveDate(d("20.09.2026"));
        });
        today("01.10.2026");
        assertThat(debtors.debtors(DebtorService.Filter.defaults(), d("01.10.2026")).getStudents()).isEmpty();
        assertThat(debtors.debtors(new DebtorService.Filter(DebtorService.Scope.ALL, null, null, null, null),
            d("01.10.2026")).getStudents()).hasSize(1);
        assertThat(debtors.debtorSummary(DebtorService.Filter.defaults(), d("01.10.2026")).closedDebt())
            .isEqualByComparingTo("630000");
    }

    // ── §8 ko'p guruh (Vali) ────────────────────────────────────────────

    @Test
    void multiGroup_studentAggregate() {
        Long vali = fixtures.student();
        Long a = monthly(vali, fixtures.group(fixtures.course(500_000)), 500_000, "05.10.2026", "0");
        Long b = monthly(vali, fixtures.group(fixtures.course(500_000)), 500_000, "20.10.2026", "0");

        accrueOn(a, "05.10.2026");
        credit(a, 500_000, "05.10.2026");
        Student s = inTx(() -> studentRepo.findById(vali).orElseThrow());
        assertThat(s.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(s.getNextPaymentDate()).isEqualTo(d("20.10.2026"));
        assertThat(s.getMonthlyFee()).isEqualByComparingTo("1000000");

        accrueOn(b, "20.10.2026");
        s = inTx(() -> studentRepo.findById(vali).orElseThrow());
        assertThat(s.getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);     // R1: muddat kuni
        assertThat(s.getNextPaymentDate()).isEqualTo(d("20.10.2026"));
        assertThat(s.getNextPaymentAmount()).isEqualByComparingTo("500000");
        assertThat(s.getDebt()).isEqualByComparingTo("500000");

        today("24.10.2026");
        assertThat(inTx(() -> studentRepo.findById(vali).orElseThrow().getPaymentStatus()))
            .isEqualTo(PaymentStatus.OVERDUE);

        // xato guruhga 1 000 000 — kesishuv yo'q
        credit(a, 1_000_000, "25.10.2026");
        s = inTx(() -> studentRepo.findById(vali).orElseThrow());
        assertThat(s.getBalance()).isEqualByComparingTo("500000");
        assertThat(s.getDebt()).isEqualByComparingTo("500000");
        assertThat(s.getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(s.getStatus()).isEqualTo(StudentStatus.ACTIVE);
    }

    // ── /expected ──────────────────────────────────────────────────────

    /** R2 (§14.2): bugun muddati kelgan to'lanmagan davr — qarzdor; kutilayotganda keyingi davri bilan qoladi. */
    @Test
    void expected_debtorKeepsNextPeriod() {
        Long sg = sg(630_000, "15.10.2026", "0");
        accrueOn(sg, "15.10.2026");
        ExpectedPaymentsResponse r = debtors.expected(null, d("15.11.2026"), d("15.10.2026"));
        assertThat(r.getDays()).hasSize(1);
        assertThat(r.getDays().get(0).getDate()).isEqualTo(d("15.11.2026"));
        assertThat(r.getDays().get(0).getStudents().get(0).getAmount()).isEqualByComparingTo("630000");
        assertThat(r.getDays().get(0).getStudents().get(0).getDebt()).isEqualByComparingTo("630000");
        assertThat(r.getDays().get(0).getStudents().get(0).getPaymentStatus()).isEqualTo("OVERDUE");
        // bugungi (muddati kelgan) sana kutilayotgan emas — oraliq bugundan boshlansa ham
        assertThat(debtors.expected(d("15.10.2026"), d("31.10.2026"), d("15.10.2026")).getDays()).isEmpty();
    }
}

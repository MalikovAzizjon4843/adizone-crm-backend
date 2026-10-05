package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.StudentGroupRequest;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.dto.response.ExpectedPaymentsResponse;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Group;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.service.GroupService;
import com.crm.service.PaymentService;
import com.crm.service.StudentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buyurtmachi qoidalari 2026-10-04 (billing-v2 §14): R1 qarzdor (grace 0), R2 kutilayotgan, R3 guruh tugashi,
 * R4 sinovdan keyin qo'shilib to'lamagan, R5 langar = qo'shilgan sana. Narx 700 000 × 10% = 630 000.
 */
class CustomerRulesTest extends AbstractBillingIT {

    @Autowired AccrualService accrual;
    @Autowired DebtorService debtors;
    @Autowired BillingStatusService status;
    @Autowired BillingProperties properties;
    @Autowired PaymentService payments;
    @Autowired StudentService students;
    @Autowired GroupService groups;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired BillingPeriodRepository periodRepo;

    record Ids(Long student, Long group, Long sg) {
    }

    private Ids monthly(String start) {
        return monthly(start, null);
    }

    private Ids monthly(String start, String groupEnd) {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        if (groupEnd != null) {
            inTx(() -> {
                Group g = groupRepo.findById(group).orElseThrow();
                g.setEndDate(d(groupEnd));
                groupRepo.save(g);
            });
        }
        Long sg = fixtures.enrollment(student, group).start(d(start)).discount("10").save();
        return new Ids(student, group, sg);
    }

    private void accrueOn(Ids ids, String date) {
        clock.setDate(d(date));
        accrual.accrueUpTo(ids.sg(), d(date));
    }

    private void pay(Ids ids, long amount, String date) {
        pay(ids, amount, date, null);
    }

    private void pay(Ids ids, long amount, String today, String paymentDate) {
        clock.setDate(d(today));
        // O'tgan sana bilan to'lov — faqat SA (2026-10-05); bugungisi — buxgalter
        fixtures.loginAs(paymentDate != null && d(paymentDate).isBefore(d(today)) ? UserRole.SUPER_ADMIN : UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(ids.student());
        r.setGroupId(ids.group());
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        r.setPaymentDate(paymentDate != null ? d(paymentDate) : null);
        payments.createPayment(r, null);
    }

    private StudentGroup sg(Long id) {
        return inTx(() -> sgRepo.findById(id).orElseThrow());
    }

    private PaymentStatus statusOn(Ids ids, String date) {
        StudentGroup e = sg(ids.sg());
        return status.statusOf(e.getBalance(), e.getDebtSince(), d(date));
    }

    private DebtorsListResponse.DebtorStudent debtor(Ids ids, String today) {
        return debtors.debtors(DebtorService.Filter.defaults(), d(today)).getStudents().stream()
            .filter(s -> s.getStudentId().equals(ids.student())).findFirst().orElse(null);
    }

    private List<ExpectedPaymentsResponse.ExpectedStudent> expected(Ids ids, String today) {
        clock.setDate(d(today));
        return debtors.expected(null, null, d(today)).getDays().stream()
            .flatMap(day -> day.getStudents().stream())
            .filter(s -> s.getStudentGroupId().equals(ids.sg()))
            .toList();
    }

    private List<LocalDate> periodStarts(Long sgId) {
        return inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sgId)).stream()
            .map(BillingPeriod::getPeriodStart).toList();
    }

    // ── R1: qarzdor — muddat kuni to'liq to'lanmagan, grace 0 ───────────

    @Test
    void r1_debtorOnDueDate_partialAlsoDebtor_fullPaymentClears() {
        assertThat(properties.getGraceDays()).isZero();
        Ids ids = monthly("15.09.2026");
        accrueOn(ids, "15.09.2026");

        assertThat(statusOn(ids, "15.09.2026")).isEqualTo(PaymentStatus.OVERDUE);
        DebtorsListResponse.DebtorStudent row = debtor(ids, "15.09.2026");
        assertThat(row).isNotNull();
        assertThat(row.getDaysOverdue()).isZero();
        assertThat(row.getDebt()).isEqualByComparingTo("630000");
        assertThat(inTx(() -> sgRepo.findAll(status.overdue(d("15.09.2026")))))
            .extracting(StudentGroup::getId).contains(ids.sg());

        pay(ids, 300_000, "15.09.2026");                         // chala
        assertThat(statusOn(ids, "15.09.2026")).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(debtor(ids, "15.09.2026").getDebt()).isEqualByComparingTo("330000");

        pay(ids, 330_000, "15.09.2026");
        assertThat(statusOn(ids, "15.09.2026")).isEqualTo(PaymentStatus.PAID);
        assertThat(debtor(ids, "15.09.2026")).isNull();
    }

    @Test
    void r1_beforeDueDate_notDebtor() {
        Ids ids = monthly("20.09.2026");
        accrueOn(ids, "19.09.2026");                             // davr hali ochilmagan
        assertThat(periodStarts(ids.sg())).isEmpty();
        assertThat(debtor(ids, "19.09.2026")).isNull();
    }

    @Test
    void r1_graceConfigurable_daysAtLeastGrace() {
        Ids ids = monthly("15.09.2026");
        accrueOn(ids, "15.09.2026");
        properties.setGraceDays(3);
        try {
            assertThat(statusOn(ids, "17.09.2026")).isEqualTo(PaymentStatus.PENDING);
            assertThat(statusOn(ids, "18.09.2026")).isEqualTo(PaymentStatus.OVERDUE);   // ilgari 19.09 dan
            assertThat(inTx(() -> sgRepo.findAll(status.overdue(d("17.09.2026"))))).isEmpty();
            assertThat(inTx(() -> sgRepo.findAll(status.overdue(d("18.09.2026"))))).hasSize(1);
        } finally {
            properties.setGraceDays(0);
        }
    }

    // ── R2: kutilayotgan — har yozilma bitta, bugundan keyin ─────────────

    /** Buyurtmachi misoli "29.09 qarzdor → 29.10 kutilayotgan" (QAROR 1: keyingi davr = langar kuni). */
    @Test
    void r2_debtorSince2909_expected2910() {
        Ids ids = monthly("29.09.2026");
        accrueOn(ids, "29.09.2026");
        clock.setDate(d("04.10.2026"));
        accrual.accrueUpTo(ids.sg(), d("04.10.2026"));

        assertThat(debtor(ids, "04.10.2026")).isNotNull();       // qarzdor
        assertThat(expected(ids, "04.10.2026")).singleElement().satisfies(e -> {   // va kutilayotgan
            assertThat(e.getAmount()).isEqualByComparingTo("630000");
            assertThat(e.getDebt()).isEqualByComparingTo("630000");
            assertThat(e.getPaymentStatus()).isEqualTo("OVERDUE");
            assertThat(e.getDaysUntil()).isEqualTo(25);           // 29.10
        });

        Ids day28 = monthly("28.09.2026");
        accrueOn(day28, "28.09.2026");
        assertThat(expected(day28, "04.10.2026")).singleElement().satisfies(e -> {
            assertThat(e.getDebt()).isEqualByComparingTo("630000");
            assertThat(e.getDaysUntil()).isEqualTo(24);           // 28.10
        });
    }

    @Test
    void r2_paidStudent_nextPeriod_prepaidBeyondWindowHidden_oneRowPerEnrollment() {
        Ids paid = monthly("15.09.2026");
        accrueOn(paid, "15.09.2026");
        pay(paid, 630_000, "15.09.2026");
        assertThat(expected(paid, "04.10.2026")).singleElement().satisfies(e -> {
            assertThat(e.getAmount()).isEqualByComparingTo("630000");
            assertThat(e.getDebt()).isEqualByComparingTo("0");
            assertThat(e.getDaysUntil()).isEqualTo(11);              // 15.10
        });

        Ids prepaid = monthly("15.09.2026");
        accrueOn(prepaid, "15.09.2026");
        pay(prepaid, 1_890_000, "15.09.2026");                       // 15.10 va 15.11 ham qoplangan → 15.12
        assertThat(expected(prepaid, "04.10.2026")).isEmpty();

        // Ikki oy to'lamagan qarzdor — baribir bitta qator (keyingi davr)
        Ids twoMonths = monthly("01.08.2026");
        accrueOn(twoMonths, "04.10.2026");
        assertThat(expected(twoMonths, "04.10.2026")).singleElement()
            .satisfies(e -> assertThat(e.getDebt()).isEqualByComparingTo("1890000"));
    }

    // ── QAROR 1: kalendar langar kuni bo'yicha, eski davrdan keyin zanjir ──

    @Test
    void calendar_anchor31_february_thenBackTo31() {
        Ids ids = monthly("31.01.2027");
        accrueOn(ids, "01.04.2027");
        assertThat(periodStarts(ids.sg()))
            .containsExactly(d("31.01.2027"), d("28.02.2027"), d("31.03.2027"));
        List<BillingPeriod> p = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(ids.sg()));
        assertThat(p.get(1).getPeriodEnd()).isEqualTo(d("30.03.2027"));
    }

    /**
     * Langar 29.08, davrlar eski qoida (29 → oy oxiri) bilan yozilgan — 29.08–29.09, 30.09–30.10. Keyingisi oxirgi davr
     * boshidan (30.09) keyingi langar kuni — 29.10; 30.09 davri oxiri 28.10 ga qisqartiriladi (ustma-ust yo'q).
     */
    @Test
    void calendar_existingOldRulePeriods_continueWithoutOverlap() {
        Ids ids = monthly("29.08.2026");
        inTx(() -> {
            for (String[] p : new String[][]{{"29.08.2026", "29.09.2026"}, {"30.09.2026", "30.10.2026"}}) {
                periodRepo.save(BillingPeriod.builder().studentGroupId(ids.sg())
                    .periodStart(d(p[0])).periodEnd(d(p[1]))
                    .fee(BigDecimal.valueOf(700_000)).discountPercentage(new BigDecimal("10"))
                    .amount(BigDecimal.valueOf(630_000))
                    .status(com.crm.entity.enums.BillingPeriodStatus.MIGRATED)
                    .createdAt(java.time.LocalDateTime.now()).build());
            }
        });

        accrueOn(ids, "30.11.2026");
        List<BillingPeriod> periods = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(ids.sg()));
        assertThat(periods).extracting(BillingPeriod::getPeriodStart).containsExactly(
            d("29.08.2026"), d("30.09.2026"), d("29.10.2026"), d("29.11.2026"));
        assertThat(periods.get(1).getPeriodEnd()).isEqualTo(d("28.10.2026"));        // eski oxir 30.10 → 28.10
        assertThat(periods.get(2).getPeriodEnd()).isEqualTo(d("28.11.2026"));
        for (int i = 1; i < periods.size(); i++) {             // ustma-ust ham, bo'shliq ham yo'q
            assertThat(periods.get(i).getPeriodStart()).isEqualTo(periods.get(i - 1).getPeriodEnd().plusDays(1));
        }
        assertThat(sg(ids.sg()).getNextPaymentDate()).isEqualTo(d("29.10.2026"));   // qarz: eng eski to'lanmagan davr
    }

    /**
     * Prod xatosi (sg 68, 73): langar 29.09 (va 30.09), yagona davr eski qoida bilan 29.09–30.10 saqlangan; to'lagan.
     * Kutilayotgan 29.10 (30.10) bo'lishi kerak, 31.10 emas; 29.10 da accrual eski davr oxirini 28.10 ga qisqartiradi.
     */
    @Test
    void calendar_prodCase_anchor29and30_singleOldRulePeriod() {
        for (String[] c : new String[][]{{"29.09.2026", "29.10.2026", "25"}, {"30.09.2026", "30.10.2026", "26"}}) {
            Ids ids = monthly(c[0]);
            accrueOn(ids, c[0]);
            inTx(() -> {                                          // eski qoida bilan saqlangan oxir
                BillingPeriod p = periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(ids.sg()).get(0);
                p.setPeriodEnd(d("30.10.2026"));
                periodRepo.save(p);
            });
            pay(ids, 630_000, c[0]);

            assertThat(sg(ids.sg()).getNextPaymentDate()).as(c[0]).isEqualTo(d(c[1]));
            assertThat(expected(ids, "04.10.2026")).as(c[0]).singleElement()
                .satisfies(e -> assertThat(e.getDaysUntil()).isEqualTo(Long.parseLong(c[2])));

            accrueOn(ids, c[1]);
            List<BillingPeriod> periods = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(ids.sg()));
            assertThat(periods).extracting(BillingPeriod::getPeriodStart).as(c[0]).containsExactly(d(c[0]), d(c[1]));
            assertThat(periods.get(0).getPeriodEnd()).as(c[0]).isEqualTo(d(c[1]).minusDays(1));
        }
    }

    // ── R3: guruh tugashi ────────────────────────────────────────────────

    @Test
    void r3_paid2009_groupUntil2010_noExpected_noNewPeriod() {
        Ids ids = monthly("20.09.2026", "20.10.2026");
        accrueOn(ids, "20.09.2026");
        pay(ids, 630_000, "20.09.2026");

        assertThat(expected(ids, "04.10.2026")).isEmpty();
        assertThat(sg(ids.sg()).getNextPaymentDate()).isNull();   // snapshot ham guruh tugashida to'xtaydi

        accrueOn(ids, "25.10.2026");                               // 20.10 dagi davr ochilmaydi
        assertThat(periodStarts(ids.sg())).containsExactly(d("20.09.2026"));
        assertThat(debtor(ids, "25.10.2026")).isNull();
    }

    @Test
    void r3_lastPartialPeriod_fullFee_thenStop() {
        Ids ids = monthly("05.09.2026", "20.10.2026");
        accrueOn(ids, "06.11.2026");
        List<BillingPeriod> periods = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(ids.sg()));
        assertThat(periods).extracting(BillingPeriod::getPeriodStart)
            .containsExactly(d("05.09.2026"), d("05.10.2026"));   // 05.11 ≥ 20.10 — yo'q
        assertThat(periods.get(1).getAmount()).isEqualByComparingTo("630000");   // qisman davr — to'liq narx
    }

    // ── R4 + R5: sinovdan keyin qo'shilish, langar = qo'shilgan sana ──────

    @Test
    void r4r5_trial15_joined17_paid20_billedEvery17() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("15.09.2026")).discount("10").trial().save();
        Ids ids = new Ids(student, group, sg);

        // 17.09: to'lovli qilindi (langar = qo'shilgan kun), to'lamadi → shu kundan qarzdor
        clock.setDate(d("17.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        students.updatePaymentStartDate(student, group, d("17.09.2026"), false);
        DebtorsListResponse.DebtorStudent row = debtor(ids, "17.09.2026");
        assertThat(row).isNotNull();
        assertThat(row.getDebtSince()).isEqualTo(d("17.09.2026"));
        assertThat(expected(ids, "17.09.2026")).singleElement()
            .satisfies(e -> assertThat(e.getDaysUntil()).isEqualTo(30));          // 17.10

        // 20.09: to'ladi — langar o'zgarmaydi
        pay(ids, 630_000, "20.09.2026");
        assertThat(sg(sg).getPaymentStartDate()).isEqualTo(d("17.09.2026"));
        accrueOn(ids, "18.10.2026");
        assertThat(periodStarts(sg)).containsExactly(d("17.09.2026"), d("17.10.2026"));
    }

    @Test
    void r5_addToGroup_withoutPaymentStart_anchorIsJoinDate_debtorFromJoin() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE);
        clock.setDate(d("20.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        StudentGroupRequest r = new StudentGroupRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setJoinDate(d("17.09.2026"));                            // forma: qo'shilgan sana, langar bo'sh
        groups.addStudentToGroup(r);

        StudentGroup e = inTx(() -> sgRepo.findByStudentIdAndGroupIdAndIsActiveTrue(student, group).orElseThrow());
        assertThat(e.getPaymentStartDate()).isEqualTo(d("17.09.2026"));          // ilgari: bugun (20.09)
        assertThat(periodStarts(e.getId())).containsExactly(d("17.09.2026"));
        assertThat(debtor(new Ids(student, group, e.getId()), "20.09.2026").getDebtSince())
            .isEqualTo(d("17.09.2026"));
    }

    @Test
    void r5_trialPaysDirectly_anchorIsConversionDay_notPaymentDate() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("15.09.2026")).discount("10").trial().save();
        Ids ids = new Ids(student, group, sg);

        pay(ids, 630_000, "20.09.2026", "18.09.2026");             // to'lov sanasi orqaga — langarga ta'sir qilmaydi
        StudentGroup e = sg(sg);
        assertThat(e.getIsTrial()).isFalse();
        assertThat(e.getPaymentStartDate()).isEqualTo(d("20.09.2026"));
        assertThat(periodStarts(sg)).containsExactly(d("20.09.2026"));
        assertThat(statusOn(ids, "20.09.2026")).isEqualTo(PaymentStatus.PAID);
    }
}

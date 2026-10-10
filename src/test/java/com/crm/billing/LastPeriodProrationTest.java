package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.GroupRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.ExpectedPaymentsResponse;
import com.crm.dto.response.GroupEndDateDtos;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Group;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Holiday;
import com.crm.entity.LessonException;
import com.crm.entity.Setting;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.repository.SettingRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.service.GroupService;
import com.crm.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Oxirgi davr darslar bo'yicha (buyurtmachi qoidasi 2026-10-10): guruh davr o'rtasida tugasa, oxirgi davr summasi
 * {@code min(c, uzs(c / 12) × darslar[period_start, end_date])}. Misol: c = 3 000 000, davr 26.10–25.11, tugash
 * 10.11, TOQ (Du/Cho/Ju) jadval — 26, 28, 30.10, 2, 4, 6, 9.11 = 7 dars → 1 750 000.
 */
class LastPeriodProrationTest extends AbstractBillingIT {

    private static final long C = 3_000_000;

    @Autowired AccrualService accrual;
    @Autowired GroupEndDateService endDates;
    @Autowired LastPeriodRepairService repair;
    @Autowired DebtorService debtors;
    @Autowired GroupService groups;
    @Autowired PaymentService payments;
    @Autowired GroupRepository groupRepo;
    @Autowired GroupScheduleDayRepository scheduleRepo;
    @Autowired HolidayRepository holidayRepo;
    @Autowired LessonExceptionRepository exceptionRepo;
    @Autowired SettingRepository settingRepo;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired BillingPeriodRepository periodRepo;
    @Autowired BalanceTransactionRepository txRepo;

    private record Sg(Long student, Long group, Long id) {
    }

    /** TOQ jadval (Du/Cho/Ju) — {@code toq = false} bo'lsa jadvalsiz guruh. */
    private Long group(String endDate, boolean toq) {
        Long id = fixtures.group(fixtures.course(C));
        inTx(() -> {
            Group g = groupRepo.findById(id).orElseThrow();
            g.setStartDate(d("01.09.2026"));
            g.setEndDate(endDate != null ? d(endDate) : null);
            groupRepo.save(g);
            if (toq) {
                for (String day : List.of("MONDAY", "WEDNESDAY", "FRIDAY")) {
                    scheduleRepo.save(GroupScheduleDay.builder().group(g).dayOfWeek(day)
                        .startTime("15:30").endTime("17:00").build());
                }
            }
        });
        return id;
    }

    private Sg enroll(Long group, String discount) {
        Long student = fixtures.student();
        return new Sg(student, group, fixtures.enrollment(student, group).start(d("26.09.2026"))
            .discount(discount).save());
    }

    private void accrueOn(Sg sg, String date) {
        clock.setDate(d(date));
        accrual.accrueUpTo(sg.id(), d(date));
    }

    private void pay(Sg sg, long amount, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(sg.student());
        r.setGroupId(sg.group());
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
    }

    private void updateEnd(Long groupId, String endDate) {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        GroupRequest r = inTx(() -> {
            Group g = groupRepo.findById(groupId).orElseThrow();
            GroupRequest req = new GroupRequest();
            req.setGroupName(g.getGroupName());
            req.setCourseId(g.getCourse().getId());
            req.setStartDate(g.getStartDate());
            req.setEndDate(d(endDate));
            req.setMaxStudents(g.getMaxStudents());
            return req;
        });
        groups.updateGroup(groupId, r);
    }

    private BillingPeriod period(Long sgId, String start) {
        return inTx(() -> periodRepo.findByStudentGroupIdAndPeriodStart(sgId, d(start)).orElseThrow());
    }

    private StudentGroup sg(Long id) {
        return inTx(() -> sgRepo.findById(id).orElseThrow());
    }

    private List<BalanceTransaction> ledgerOf(Long periodId) {
        return inTx(() -> txRepo.findAll()).stream()
            .filter(t -> periodId.equals(t.getBillingPeriodId()))
            .sorted(java.util.Comparator.comparing(BalanceTransaction::getId)).toList();
    }

    private ExpectedPaymentsResponse.ExpectedStudent expectedRow(Sg sg, String today) {
        clock.setDate(d(today));
        return debtors.expected(null, null, d(today)).getDays().stream()
            .flatMap(day -> day.getStudents().stream())
            .filter(s -> s.getStudentGroupId().equals(sg.id())).findFirst().orElse(null);
    }

    // ── Formula (sof) ───────────────────────────────────────────────────

    @Test
    void formula_example_andCapAtC() {
        AccrualCalculator.Proration seven = proration(7);
        AccrualCalculator.DueCharge c = AccrualCalculator.periodCharge(d("26.10.2026"), d("25.11.2026"),
            BigDecimal.valueOf(C), BigDecimal.ZERO, d("10.11.2026"), seven);
        assertThat(c.amount()).isEqualByComparingTo("1750000");
        assertThat(c.proratedLessons()).isEqualTo(7);
        assertThat(c.lessonPrice()).isEqualByComparingTo("250000");

        // 13 dars × 250 000 > c — to'liq c, darslar belgisiz
        AccrualCalculator.DueCharge capped = AccrualCalculator.periodCharge(d("26.10.2026"), d("25.11.2026"),
            BigDecimal.valueOf(C), BigDecimal.ZERO, d("24.11.2026"), proration(13));
        assertThat(capped.amount()).isEqualByComparingTo("3000000");
        assertThat(capped.prorated()).isFalse();

        // Tugash davr oxirgi kunida — to'liq davr; tugashsiz — to'liq
        assertThat(AccrualCalculator.isLastPeriod(d("25.11.2026"), d("26.10.2026"), d("25.11.2026"))).isFalse();
        assertThat(AccrualCalculator.periodCharge(d("26.10.2026"), d("25.11.2026"), BigDecimal.valueOf(C),
            null, null, seven).amount()).isEqualByComparingTo("3000000");
        // Dars narxi butun so'mga: 1 000 000 / 12 = 83 333.33 → 83 333
        assertThat(EnrollmentPricing.monthlyLessonPrice(BigDecimal.valueOf(1_000_000), 12)).isEqualByComparingTo("83333");
    }

    private static AccrualCalculator.Proration proration(int lessons) {
        return new AccrualCalculator.Proration() {
            @Override
            public int lessonsPerMonth() {
                return 12;
            }

            @Override
            public Integer lessons(LocalDate from, LocalDate to) {
                return lessons;
            }
        };
    }

    // ── Accrual ─────────────────────────────────────────────────────────

    @Test
    void accrual_example_7lessons_1750000() {
        Sg a = enroll(group("10.11.2026", true), "0");
        accrueOn(a, "26.10.2026");

        assertThat(period(a.id(), "26.09.2026").getAmount()).isEqualByComparingTo("3000000");
        assertThat(period(a.id(), "26.09.2026").getProratedLessons()).isNull();
        BillingPeriod last = period(a.id(), "26.10.2026");
        assertThat(last.getAmount()).isEqualByComparingTo("1750000");
        assertThat(last.getProratedLessons()).isEqualTo(7);
        assertThat(last.getLessonPrice()).isEqualByComparingTo("250000");
        BalanceTransaction charge = ledgerOf(last.getId()).get(0);
        assertThat(charge.getAmount()).isEqualByComparingTo("-1750000");
        assertThat(charge.getNote()).isEqualTo("26.10.2026–25.11.2026 (7 dars × 250 000)");
        assertThat(sg(a.id()).getBalance()).isEqualByComparingTo("-4750000");
    }

    @Test
    void accrual_discountedC_lessonPriceFromDiscounted() {
        Sg a = enroll(group("10.11.2026", true), "10");                    // c = 2 700 000
        accrueOn(a, "26.10.2026");
        BillingPeriod last = period(a.id(), "26.10.2026");
        assertThat(last.getLessonPrice()).isEqualByComparingTo("225000");
        assertThat(last.getAmount()).isEqualByComparingTo("1575000");      // 7 × 225 000
    }

    @Test
    void accrual_holidayAndCancelledLessonExcluded_extraAdded() {
        Long g = group("10.11.2026", true);
        inTx(() -> {
            holidayRepo.save(Holiday.builder().holidayDate(d("04.11.2026")).name("Bayram")
                .createdAt(LocalDateTime.now()).build());
            exceptionRepo.save(LessonException.builder().groupId(g).lessonDate(d("06.11.2026"))
                .kind(LessonException.Kind.CANCELLED).createdAt(LocalDateTime.now()).build());
            exceptionRepo.save(LessonException.builder().groupId(g).lessonDate(d("31.10.2026"))   // shanba
                .kind(LessonException.Kind.EXTRA).createdAt(LocalDateTime.now()).build());
        });
        Sg a = enroll(g, "0");
        accrueOn(a, "26.10.2026");
        // 26, 28, 30, 31 (qo'shimcha), 2, 9 — 04.11 bayram, 06.11 bekor qilingan
        assertThat(period(a.id(), "26.10.2026").getProratedLessons()).isEqualTo(6);
        assertThat(period(a.id(), "26.10.2026").getAmount()).isEqualByComparingTo("1500000");
    }

    @Test
    void accrual_groupWithoutSchedule_fullC() {
        Sg a = enroll(group("10.11.2026", false), "0");
        accrueOn(a, "26.10.2026");
        BillingPeriod last = period(a.id(), "26.10.2026");
        assertThat(last.getAmount()).isEqualByComparingTo("3000000");
        assertThat(last.getProratedLessons()).isNull();
        assertThat(last.getLessonPrice()).isNull();
    }

    @Test
    void lessonsPerMonthSetting() {
        try {
            inTx(() -> settingRepo.save(Setting.builder().settingKey(LessonProrationService.LESSONS_PER_MONTH_KEY)
                .settingValue("8").build()));
            Sg a = enroll(group("10.11.2026", true), "0");
            accrueOn(a, "26.10.2026");
            assertThat(period(a.id(), "26.10.2026").getLessonPrice()).isEqualByComparingTo("375000");
            assertThat(period(a.id(), "26.10.2026").getAmount()).isEqualByComparingTo("2625000");   // 7 × 375 000
        } finally {
            inTx(() -> settingRepo.findBySettingKey(LessonProrationService.LESSONS_PER_MONTH_KEY)
                .ifPresent(settingRepo::delete));
        }
    }

    // ── Kutilayotgan va keyingi to'lov ──────────────────────────────────

    @Test
    void expected_andNextPayment_useLastPeriodAmount() {
        Long g = group("10.11.2026", true);
        Sg paid = enroll(g, "0");
        Sg partial = enroll(g, "0");
        Sg debtor = enroll(g, "0");
        accrueOn(paid, "26.09.2026");
        accrueOn(partial, "26.09.2026");
        accrueOn(debtor, "26.09.2026");
        pay(paid, C, "26.09.2026");
        pay(partial, C + 500_000, "26.09.2026");

        ExpectedPaymentsResponse.ExpectedStudent row = expectedRow(paid, "20.10.2026");
        assertThat(row.getAmount()).isEqualByComparingTo("1750000");
        assertThat(row.getProratedLessons()).isEqualTo(7);
        assertThat(row.getLessonPrice()).isEqualByComparingTo("250000");
        assertThat(sg(paid.id()).getNextPaymentDate()).isEqualTo(d("26.10.2026"));
        assertThat(sg(paid.id()).getNextPaymentAmount()).isEqualByComparingTo("1750000");

        assertThat(expectedRow(partial, "20.10.2026").getAmount()).isEqualByComparingTo("1250000");   // 1 750 000 − 500 000
        assertThat(sg(partial.id()).getNextPaymentAmount()).isEqualByComparingTo("1250000");

        ExpectedPaymentsResponse.ExpectedStudent d = expectedRow(debtor, "20.10.2026");                 // qarzdor ham
        assertThat(d.getAmount()).isEqualByComparingTo("1750000");
        assertThat(d.getDebt()).isEqualByComparingTo("3000000");
        assertThat(d.getProratedLessons()).isEqualTo(7);
    }

    // ── TASK 2: tugash sanasi o'zgarganda yozilgan oxirgi davr ──────────

    @Test
    void endDateChange_recalculatesWrittenLastPeriod_extendThenShorten() {
        Long g = group("10.11.2026", true);
        Sg a = enroll(g, "0");
        accrueOn(a, "26.10.2026");
        Long periodId = period(a.id(), "26.10.2026").getId();
        assertThat(sg(a.id()).getBalance()).isEqualByComparingTo("-4750000");

        // Preview: yozilgan davr qayta hisobi ko'rinadi
        GroupEndDateDtos.ImpactRow preview = endDates.impact(g, d("14.12.2026")).enrollments().get(0);
        assertThat(preview.recalculated()).singleElement().satisfies(r -> {
            assertThat(r.oldAmount()).isEqualByComparingTo("1750000");
            assertThat(r.newAmount()).isEqualByComparingTo("3000000");
            assertThat(r.diff()).isEqualByComparingTo("1250000");
        });
        assertThat(preview.balanceAfter()).isEqualByComparingTo("-6000000");

        updateEnd(g, "14.12.2026");                                       // uzaytirish — davr endi to'liq
        BillingPeriod p = period(a.id(), "26.10.2026");
        assertThat(p.getAmount()).isEqualByComparingTo("3000000");
        assertThat(p.getProratedLessons()).isNull();
        assertThat(p.getLessonPrice()).isNull();
        assertThat(sg(a.id()).getBalance()).isEqualByComparingTo("-6000000");
        assertThat(ledgerOf(periodId)).extracting(BalanceTransaction::getType)
            .containsExactly(BalanceTransactionType.PERIOD_CHARGE, BalanceTransactionType.PERIOD_CHARGE);
        BalanceTransaction extra = ledgerOf(periodId).get(1);
        assertThat(extra.getAmount()).isEqualByComparingTo("-1250000");
        assertThat(extra.getRelatedTxId()).isEqualTo(p.getChargeTxId());
        assertThat(extra.getEffectiveDate()).isEqualTo(d("26.10.2026"));

        updateEnd(g, "04.11.2026");                                       // qisqartirish — 26, 28, 30, 2, 4 = 5 dars
        p = period(a.id(), "26.10.2026");
        assertThat(p.getAmount()).isEqualByComparingTo("1250000");
        assertThat(p.getProratedLessons()).isEqualTo(5);
        BalanceTransaction refund = ledgerOf(periodId).get(2);
        assertThat(refund.getType()).isEqualTo(BalanceTransactionType.PERIOD_REFUND);
        assertThat(refund.getAmount()).isEqualByComparingTo("1750000");
        assertThat(refund.getRelatedTxId()).isEqualTo(p.getChargeTxId());
        assertThat(sg(a.id()).getBalance()).isEqualByComparingTo("-4250000");
        assertThat(sg(a.id()).getDebtSince()).isEqualTo(d("26.09.2026"));
        assertThat(ledgerOf(periodId).stream().map(BalanceTransaction::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-1250000");

        updateEnd(g, "04.11.2026");                                       // o'zgarishsiz — yangi yozuv yo'q
        assertThat(ledgerOf(periodId)).hasSize(3);
    }

    // ── TASK 3: bir martalik tuzatish ───────────────────────────────────

    @Test
    void repair_dryRunThenApply_idempotent() throws Exception {
        Long g = group(null, true);
        Sg a = enroll(g, "0");
        Sg perLesson;
        accrueOn(a, "26.10.2026");                                        // tugashsiz — to'liq 3 000 000
        Long student = fixtures.student();
        perLesson = new Sg(student, g, fixtures.enrollment(student, g).start(d("26.09.2026")).perLesson(250_000).save());
        inTx(() -> {                                                      // qoida oldidan: tugash sanasi keyin kiritilgan
            Group grp = groupRepo.findById(g).orElseThrow();
            grp.setEndDate(d("10.11.2026"));
            groupRepo.save(grp);
        });
        Long periodId = period(a.id(), "26.10.2026").getId();

        GroupEndDateDtos.Repair dry = repair.repair(true);
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.rows()).singleElement().satisfies(r -> {
            assertThat(r.studentGroupId()).isEqualTo(a.id());
            assertThat(r.periodId()).isEqualTo(periodId);
            assertThat(r.oldAmount()).isEqualByComparingTo("3000000");
            assertThat(r.newAmount()).isEqualByComparingTo("1750000");
            assertThat(r.diff()).isEqualByComparingTo("-1250000");
            assertThat(r.lessons()).isEqualTo(7);
            assertThat(r.lessonPrice()).isEqualByComparingTo("250000");
            assertThat(r.groupEndDate()).isEqualTo(d("10.11.2026"));
        });
        assertThat(ledgerOf(periodId)).hasSize(1);                        // dryRun hech narsa yozmagan
        assertThat(period(a.id(), "26.10.2026").getAmount()).isEqualByComparingTo("3000000");

        mvc.perform(post("/api/admin/repair/prorate-last-periods").with(user("a").roles("ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/repair/prorate-last-periods").with(user("sa").roles("SUPER_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(true))
            .andExpect(jsonPath("$.data.periods").value(1));

        GroupEndDateDtos.Repair applied = repair.repair(false);
        assertThat(applied.periods()).isEqualTo(1);
        assertThat(applied.failed()).isZero();
        assertThat(applied.totalDiff()).isEqualByComparingTo("-1250000");
        BillingPeriod p = period(a.id(), "26.10.2026");
        assertThat(p.getAmount()).isEqualByComparingTo("1750000");
        assertThat(p.getProratedLessons()).isEqualTo(7);
        assertThat(ledgerOf(periodId)).extracting(BalanceTransaction::getType)
            .containsExactly(BalanceTransactionType.PERIOD_CHARGE, BalanceTransactionType.PERIOD_REFUND);
        assertThat(sg(a.id()).getBalance()).isEqualByComparingTo("-4750000");
        assertThat(sg(perLesson.id()).getBalance()).isEqualByComparingTo("0");   // PER_LESSON — tegilmaydi

        assertThat(repair.repair(true).rows()).isEmpty();                 // takroriy — farq yo'q
        assertThat(repair.repair(false).periods()).isZero();
        assertThat(ledgerOf(periodId)).hasSize(2);
    }
}

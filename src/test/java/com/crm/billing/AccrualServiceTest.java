package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingJobRun;
import com.crm.entity.BillingPeriod;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.StudentGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 2-bosqich: docs/design/billing-v2.md §3, §12 (T1.1–T1.4, T3.3, T3.4, T6.1). */
class AccrualServiceTest extends AbstractBillingIT {

    @Autowired
    AccrualService accrual;
    @Autowired
    BillingJobService job;
    @Autowired
    BillingProperties properties;
    @Autowired
    BillingPeriodRepository periodRepo;
    @Autowired
    BalanceTransactionRepository txRepo;
    @Autowired
    StudentGroupRepository sgRepo;

    private Long monthly(long fee, String start) {
        return fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(fee))).start(d(start)).save();
    }

    private List<BalanceTransaction> ledger(Long sg) {
        return inTx(() -> txRepo.findLedgerForFifo(sg));
    }

    private BigDecimal balance(Long sg) {
        return inTx(() -> sgRepo.findById(sg).orElseThrow().getBalance());
    }

    /** T1.1 */
    @Test
    void accrual_writesChargeOnBillingDay() {
        Long sg = monthly(700_000, "15.09.2026");
        clock.setDate(d("15.09.2026"));
        job.runDaily(d("15.09.2026"), "TEST");

        List<BalanceTransaction> tx = ledger(sg);
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).getType()).isEqualTo(BalanceTransactionType.PERIOD_CHARGE);
        assertThat(tx.get(0).getAmount()).isEqualByComparingTo("-700000");
        assertThat(tx.get(0).getEffectiveDate()).isEqualTo(d("15.09.2026"));
        assertThat(balance(sg)).isEqualByComparingTo("-700000");

        List<BillingPeriod> periods = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sg));
        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).getPeriodEnd()).isEqualTo(d("14.10.2026"));
        assertThat(periods.get(0).getStatus()).isEqualTo(BillingPeriodStatus.CHARGED);
        assertThat(periods.get(0).getChargeTxId()).isEqualTo(tx.get(0).getId());
        assertThat(periods.get(0).getAmount()).isEqualByComparingTo("700000");
    }

    /** T1.2: job 2 marta + 2 parallel thread → 1 davr, 1 yozuv. */
    @Test
    void accrual_isIdempotent() throws Exception {
        Long sg = monthly(700_000, "15.09.2026");
        clock.setDate(d("15.09.2026"));
        job.runDaily(d("15.09.2026"), "TEST");
        job.runDaily(d("15.09.2026"), "TEST");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    accrual.accrueUpTo(sg, d("15.09.2026"));
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        inTx(() -> assertThat(periodRepo.countByStudentGroupId(sg)).isEqualTo(1));
        assertThat(ledger(sg)).hasSize(1);
        assertThat(balance(sg)).isEqualByComparingTo("-700000");
    }

    /** T1.3: 14.10–17.10 server o'chiq; 17.10 da bitta charge, effective 15.10. */
    @Test
    void accrual_catchUpAfterDowntime() {
        Long sg = monthly(630_000, "15.09.2026");
        job.runDaily(d("15.09.2026"), "TEST");
        clock.setDate(d("17.10.2026"));
        job.runDaily(d("17.10.2026"), "STARTUP");

        List<BalanceTransaction> tx = ledger(sg);
        assertThat(tx).hasSize(2);
        BalanceTransaction caught = tx.get(1);
        assertThat(caught.getEffectiveDate()).isEqualTo(d("15.10.2026"));
        assertThat(caught.getCreatedAt().toLocalDate()).isEqualTo(d("17.10.2026"));
        assertThat(caught.getAmount()).isEqualByComparingTo("-630000");
    }

    /** T1.4: 01.10 da start 15.06 → 4 davr; max-catch-up = 2 → 2 ta + hisobot. */
    @Test
    void accrual_backdatedStart() {
        clock.setDate(d("01.10.2026"));
        Long sg = monthly(700_000, "15.06.2026");
        job.runDaily(d("01.10.2026"), "TEST");
        assertThat(ledger(sg)).extracting(BalanceTransaction::getEffectiveDate)
            .containsExactly(d("15.06.2026"), d("15.07.2026"), d("15.08.2026"), d("15.09.2026"));
        assertThat(balance(sg)).isEqualByComparingTo("-2800000");

        Long sg2 = monthly(700_000, "15.06.2026");
        int saved = properties.getMaxCatchUp();
        properties.setMaxCatchUp(2);
        try {
            BillingJobRun run = job.runDaily(d("01.10.2026"), "TEST");
            assertThat(ledger(sg2)).hasSize(2);
            assertThat(run.getCatchUpLimited()).isEqualTo(1);
            assertThat(run.getErrors()).contains(sg2 + ": catch-up");
        } finally {
            properties.setMaxCatchUp(saved);
        }
    }

    /** T3.3: chegirma keyingi davrdan; yozilgan davr o'zgarmaydi. */
    @Test
    void discount_changeAppliesNextPeriod() {
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000)))
            .start(d("15.09.2026")).discount("10").save();
        job.runDaily(d("15.09.2026"), "TEST");
        inTx(() -> {
            StudentGroup s = sgRepo.findById(sg).orElseThrow();
            s.setDiscountPercentage(BigDecimal.valueOf(20));
        });
        clock.setDate(d("15.10.2026"));
        job.runDaily(d("15.10.2026"), "TEST");

        assertThat(ledger(sg)).extracting(BalanceTransaction::getAmount)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal.valueOf(-630_000), BigDecimal.valueOf(-560_000));
        List<BillingPeriod> periods = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sg));
        assertThat(periods.get(0).getAmount()).isEqualByComparingTo("630000");
        assertThat(periods.get(0).getDiscountPercentage()).isEqualByComparingTo("10");
        assertThat(periods.get(1).getAmount()).isEqualByComparingTo("560000");
    }

    /** T3.4 (qism): d = 100 → davr qatori bor, ledger yo'q. */
    @Test
    void fullDiscount_periodRowWithoutLedger() {
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(500_000)))
            .start(d("15.09.2026")).discount("100").save();
        job.runDaily(d("15.09.2026"), "TEST");
        List<BillingPeriod> periods = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sg));
        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).getAmount()).isEqualByComparingTo("0");
        assertThat(periods.get(0).getChargeTxId()).isNull();
        assertThat(ledger(sg)).isEmpty();
        // ikkinchi ishga tushishda ham qayta yozilmaydi
        job.runDaily(d("15.09.2026"), "TEST");
        inTx(() -> assertThat(periodRepo.countByStudentGroupId(sg)).isEqualTo(1));
    }

    /** T6.1 (qism): sinovda accrual yo'q; to'lovli qilinib langar 06.10 → charge 06.10. */
    @Test
    void trial_noAccrual_thenChargeFromNewAnchor() {
        clock.setDate(d("01.10.2026"));
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000)))
            .start(d("01.10.2026")).trial().save();
        for (String day : List.of("01.10.2026", "03.10.2026", "05.10.2026")) {
            job.runDaily(d(day), "TEST");
        }
        assertThat(ledger(sg)).isEmpty();

        inTx(() -> {
            StudentGroup s = sgRepo.findById(sg).orElseThrow();
            s.setIsTrial(false);
            s.setPaymentStartDate(d("06.10.2026"));
        });
        clock.setDate(d("06.10.2026"));
        accrual.accrueUpTo(sg, d("06.10.2026"));
        List<BalanceTransaction> tx = ledger(sg);
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).getEffectiveDate()).isEqualTo(d("06.10.2026"));
        assertThat(tx.get(0).getAmount()).isEqualByComparingTo("-700000");
    }

    @Test
    void closedGroupAndFrozenEnrollment_notCharged() {
        Long course = fixtures.course(700_000);
        Long completed = fixtures.enrollment(fixtures.student(), fixtures.group(course, GroupStatus.COMPLETED))
            .start(d("15.09.2026")).save();
        Long frozen = fixtures.enrollment(fixtures.student(), fixtures.group(course)).start(d("15.09.2026")).save();
        inTx(() -> {
            StudentGroup s = sgRepo.findById(frozen).orElseThrow();
            s.setFrozenFrom(d("10.09.2026"));
            s.setIsActive(false);
        });
        job.runDaily(d("15.09.2026"), "TEST");
        assertThat(ledger(completed)).isEmpty();
        assertThat(ledger(frozen)).isEmpty();
    }

    /** Bitta SG yiqilsa qolganlari davom etadi (har SG alohida tranzaksiya). */
    @Test
    void job_isolatesFailures() {
        Long good = monthly(700_000, "15.09.2026");
        Long bad = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000)))
            .start(d("15.09.2026")).discount("150").save();
        BillingJobRun run = job.runDaily(d("15.09.2026"), "TEST");
        assertThat(run.getStatus()).isEqualTo("PARTIAL");
        assertThat(run.getFailed()).isEqualTo(1);
        assertThat(run.getErrors()).contains(bad + ":");
        assertThat(ledger(good)).hasSize(1);
        assertThat(ledger(bad)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void adminAccrue_rejectsFutureDate() throws Exception {
        clock.setDate(d("15.09.2026"));
        mvc.perform(post("/api/admin/billing/accrue").param("date", "2026-09-16"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("billing.accrue.futureDate"));
        mvc.perform(post("/api/admin/billing/accrue").param("date", "2026-09-15"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("OK"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAccrue_forbiddenForAdmin() throws Exception {
        mvc.perform(post("/api/admin/billing/accrue")).andExpect(status().isForbidden());
    }
}

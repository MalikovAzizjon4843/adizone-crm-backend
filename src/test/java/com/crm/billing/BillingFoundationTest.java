package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.entity.BillingPeriod;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.StudentGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 1-bosqich: billing_periods UNIQUE, chek sequence, qulflar, sxema guard. */
class BillingFoundationTest extends AbstractBillingIT {

    @Autowired
    BillingPeriodRepository periodRepo;
    @Autowired
    ReceiptNumberService receipts;
    @Autowired
    BillingLocks locks;
    @Autowired
    LedgerService ledger;
    @Autowired
    StudentGroupRepository sgRepo;
    @Autowired
    BalanceTransactionRepository txRepo;
    @Autowired
    BillingSchemaGuard guard;
    @Autowired
    JdbcTemplate jdbc;

    /** I4: bitta davr ikki marta yozilmaydi. */
    @Test
    void billingPeriod_uniquePerEnrollmentAndStart() {
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000))).save();
        inTx(() -> periodRepo.save(period(sg)));
        assertThatThrownBy(() -> inTx(() -> periodRepo.saveAndFlush(period(sg))))
            .isInstanceOf(DataIntegrityViolationException.class);
        inTx(() -> assertThat(periodRepo.countByStudentGroupId(sg)).isEqualTo(1));
    }

    private static BillingPeriod period(Long sgId) {
        return BillingPeriod.builder()
            .studentGroupId(sgId)
            .periodStart(d("15.09.2026"))
            .periodEnd(d("14.10.2026"))
            .fee(BigDecimal.valueOf(700_000))
            .discountPercentage(BigDecimal.ZERO)
            .amount(BigDecimal.valueOf(700_000))
            .status(BillingPeriodStatus.CHARGED)
            .build();
    }

    @Test
    void receipt_sequentialFormat() {
        String a = receipts.next();
        String b = receipts.next();
        assertThat(a).matches("RCP-\\d{5,}");
        assertThat(Long.parseLong(b.substring(4))).isEqualTo(Long.parseLong(a.substring(4)) + 1);
        assertThat(ReceiptNumberService.format(43)).isEqualTo("RCP-00043");
        assertThat(ReceiptNumberService.format(123456)).isEqualTo("RCP-123456");
    }

    /** C2: 20 parallel so'rov — 20 xil chek. */
    @Test
    void receipt_uniqueUnderParallelLoad() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> tx.execute(s -> receipts.next())));
            }
            Set<String> all = new HashSet<>();
            for (Future<String> f : futures) {
                all.add(f.get(10, TimeUnit.SECONDS));
            }
            assertThat(all).hasSize(20);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * §7.1 lost update: ikki thread bir SG ga parallel yozadi — qulf ostida
     * hech bir yozuv yo'qolmaydi va balans ledger yig'indisiga teng.
     */
    @Test
    void lock_preventsLostUpdates() throws Exception {
        Long student = fixtures.student();
        Long sg = fixtures.enrollment(student, fixtures.group(fixtures.course(700_000))).save();
        int threads = 4;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            tx.executeWithoutResult(s -> {
                                StudentGroup locked = locks.lockEnrollmentWithStudent(student, sg);
                                ledger.post(LedgerService.Entry.builder()
                                    .enrollment(locked)
                                    .type(BalanceTransactionType.PAYMENT)
                                    .amount(BigDecimal.valueOf(1_000))
                                    .build());
                            });
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors).isEmpty();
        inTx(() -> {
            assertThat(sgRepo.findById(sg).orElseThrow().getBalance())
                .isEqualByComparingTo(BigDecimal.valueOf(threads * perThread * 1_000L));
            assertThat(txRepo.findByStudentGroup_IdOrderByIdAsc(sg)).hasSize(threads * perThread);
        });
    }

    /** Qulf ostida o'qilgan holat yangi: tranzaksiyada avval eski nusxa yuklangan bo'lsa ham. */
    @Test
    void lock_refreshesStaleManagedEntity() {
        Long student = fixtures.student();
        Long sg = fixtures.enrollment(student, fixtures.group(fixtures.course(700_000))).save();
        inTx(() -> {
            StudentGroup stale = sgRepo.findById(sg).orElseThrow();
            assertThat(stale.getDiscountPercentage()).isEqualByComparingTo("0");
            // boshqa tranzaksiya (JDBC, alohida ulanish emas — lekin persistence context bilmaydi)
            jdbc.update("UPDATE student_groups SET discount_percentage = 10 WHERE id = ?", sg);
            StudentGroup locked = locks.lockEnrollmentWithStudent(student, sg);
            assertThat(locked).isSameAs(stale);
            assertThat(locked.getDiscountPercentage()).isEqualByComparingTo("10");
        });
    }

    @Test
    void lockPlan_ordersIdsAscending() {
        Long course = fixtures.course(700_000);
        Long student = fixtures.student();
        Long sg1 = fixtures.enrollment(student, fixtures.group(course)).save();
        Long sg2 = fixtures.enrollment(student, fixtures.group(course)).save();
        Long reg1 = fixtures.cashRegister(true);
        Long reg2 = fixtures.cashRegister(false);
        BillingLocks.Locked locked = inTx(() -> locks.acquire(BillingLocks.Plan.of()
            .student(student).enrollment(sg2).enrollment(sg1).cashRegister(reg2).cashRegister(reg1)));
        assertThat(locked.enrollments().keySet()).containsExactly(sg1, sg2);
        assertThat(locked.cashRegisters().keySet()).containsExactly(reg1, reg2);
        assertThat(locked.student().getId()).isEqualTo(student);
    }

    @Test
    void schemaGuard_allObjectsPresentInTestSchema() {
        assertThat(guard.findMissing()).isEmpty();
    }
}

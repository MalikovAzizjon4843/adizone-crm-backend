package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingHeldApplicationRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hold'dagi yozilmalar CRM'dan (SA): ro'yxat, preview (yangi langar bilan), apply (Idempotency-Key, sabab). */
class HeldEnrollmentTest extends AbstractBillingIT {

    private static final LocalDate T = LocalDate.of(2026, 10, 5);

    @Autowired HeldEnrollmentService held;
    @Autowired BillingMigrationService migration;
    @Autowired BillingProperties properties;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired StudentRepository studentRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired PaymentRepository paymentRepo;
    @Autowired BalanceTransactionRepository txRepo;
    @Autowired BillingPeriodRepository periodRepo;
    @Autowired BillingHeldApplicationRepository applicationRepo;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void enableBilling() {
        properties.setEnabled(true);
    }

    record Ids(Long student, Long group, Long sg) {
    }

    /** v1 yozilma: 20.09 langar, 20.09 da 700 000 to'lov va v1 PERIOD_CHARGE (LedgerService chetlab, eski kod kabi). */
    private Ids legacy() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("20.09.2026")).save();
        inTx(() -> paymentRepo.save(Payment.builder()
            .student(studentRepo.findById(student).orElseThrow())
            .group(groupRepo.findById(group).orElseThrow())
            .studentGroup(sgRepo.findById(sg).orElseThrow())
            .amount(BigDecimal.valueOf(700_000)).discountAmount(BigDecimal.ZERO)
            .payableAmount(BigDecimal.valueOf(700_000)).cashAmount(BigDecimal.valueOf(700_000))
            .paymentDate(d("20.09.2026")).periodStart(d("20.09.2026"))
            .receiptNumber("OLD-" + sg).status(PaymentStatus.PAID).build()));
        for (long amount : new long[]{700_000, -700_000}) {
            inTx(() -> {
                StudentGroup e = sgRepo.findById(sg).orElseThrow();
                txRepo.save(BalanceTransaction.builder().studentGroup(e).student(e.getStudent())
                    .type(amount > 0 ? BalanceTransactionType.PAYMENT : BalanceTransactionType.PERIOD_CHARGE)
                    .amount(BigDecimal.valueOf(amount)).balanceAfter(BigDecimal.ZERO)
                    .effectiveDate(d("20.09.2026")).note("v1").createdAt(d("20.09.2026").atTime(10, 0)).build());
            });
        }
        return new Ids(student, group, sg);
    }

    /** Migratsiya qo'llangan, {@code heldOne} chetlatilgan → hold. */
    private Ids appliedRunWithHeld() {
        clock.setDate(T);
        legacy();
        Ids heldOne = legacy();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        MigrationPlanner.Report r = migration.dryRun(T, false);
        Long runId = migration.approve(T, false, r.reportHash(), "Egasi", null).getId();
        properties.setEnabled(false);
        migration.apply(runId, "APPLY-" + runId, List.of(heldOne.sg()), false);
        properties.setEnabled(true);
        assertThat(inTx(() -> sgRepo.findById(heldOne.sg()).orElseThrow()).getBillingHold()).isTrue();
        return heldOne;
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode()).isEqualTo(code);
    }

    @Test
    void list_preview_withAnchorOverride_writesNothing() {
        Ids h = appliedRunWithHeld();

        HeldEnrollmentService.HeldList list = held.list();
        assertThat(list.rows()).extracting(HeldEnrollmentService.HeldRow::studentGroupId).containsExactly(h.sg());
        HeldEnrollmentService.HeldRow row = list.rows().get(0);
        assertThat(row.paymentStartDate()).isEqualTo(d("20.09.2026"));
        assertThat(row.paymentsCount()).isEqualTo(1);
        assertThat(row.paymentsSum()).isEqualByComparingTo("700000");
        assertThat(row.periods()).extracting(MigrationPlanner.PlannedPeriod::start).containsExactly(d("20.09.2026"));
        assertThat(row.balanceAfter()).isEqualByComparingTo("0");
        assertThat(row.planHash()).hasSize(64);

        HeldEnrollmentService.HeldRow preview = held.preview(h.sg(), d("25.09.2026"));
        assertThat(preview.paymentStartDate()).isEqualTo(d("25.09.2026"));
        assertThat(preview.periods()).extracting(MigrationPlanner.PlannedPeriod::start).containsExactly(d("25.09.2026"));
        assertThat(preview.planHash()).isNotEqualTo(row.planHash());
        // hech narsa yozilmagan
        StudentGroup sg = inTx(() -> sgRepo.findById(h.sg()).orElseThrow());
        assertThat(sg.getPaymentStartDate()).isEqualTo(d("20.09.2026"));
        assertThat(sg.getBillingHold()).isTrue();
        assertThat(inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(h.sg()))).isEmpty();
    }

    @Test
    void apply_requiresKeyAndReason_planHash_thenAppliesWithNewAnchor_idempotentReplay() {
        Ids h = appliedRunWithHeld();
        String hash = held.preview(h.sg(), d("25.09.2026")).planHash();

        assertCode(() -> held.apply(h.sg(), " ", "Sabab", null, null), "billing.held.idempotencyKeyRequired");
        assertCode(() -> held.apply(h.sg(), "k-1", "  ", null, null), "billing.held.reasonRequired");
        assertCode(() -> held.apply(h.sg(), "k-1", "Sabab", d("25.09.2026"), "eskirgan-hash"), "billing.held.planChanged");
        assertThat(inTx(() -> sgRepo.findById(h.sg()).orElseThrow()).getPaymentStartDate())
            .as("409 da tranzaksiya qaytdi").isEqualTo(d("20.09.2026"));

        HeldEnrollmentService.ApplyResult res = held.apply(h.sg(), "k-1",
            "Egasi bilan kelishildi: langar 25.09", d("25.09.2026"), hash);
        assertThat(res.replay()).isFalse();
        assertThat(res.anchorBefore()).isEqualTo(d("20.09.2026"));
        assertThat(res.anchorAfter()).isEqualTo(d("25.09.2026"));
        StudentGroup after = inTx(() -> sgRepo.findById(h.sg()).orElseThrow());
        assertThat(after.getBillingHold()).isNull();
        assertThat(after.getBalance()).isEqualByComparingTo("0");
        assertThat(inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(h.sg())))
            .extracting(BillingPeriod::getPeriodStart).containsExactly(d("25.09.2026"));
        assertThat(inTx(() -> applicationRepo.findByStudentGroupIdOrderByIdDesc(h.sg()))).singleElement()
            .satisfies(a -> {
                assertThat(a.getReason()).isEqualTo("Egasi bilan kelishildi: langar 25.09");
                assertThat(a.getIdempotencyKey()).isEqualTo("k-1");
                assertThat(a.getPlanHash()).isEqualTo(hash);
                assertThat(a.getAppliedBy()).isEqualTo("test-super_admin");
            });

        // Takror (o'sha kalit) — o'sha natija, ikkinchi yozuv yo'q
        HeldEnrollmentService.ApplyResult again = held.apply(h.sg(), "k-1", "boshqa", null, null);
        assertThat(again.replay()).isTrue();
        assertThat(again.anchorAfter()).isEqualTo(d("25.09.2026"));
        assertThat(inTx(() -> applicationRepo.findByStudentGroupIdOrderByIdDesc(h.sg()))).hasSize(1);
        // Kalit boshqa SG uchun — 409; yangi kalit bilan qayta — hold emas
        assertCode(() -> held.apply(h.sg() + 1000, "k-1", "Sabab", null, null), "billing.held.idempotencyConflict");
        assertCode(() -> held.apply(h.sg(), "k-2", "Sabab", null, null), "migration.sgNotHeld");
    }

    @Test
    void endpoints_superAdminOnly_headerRequired() throws Exception {
        Ids h = appliedRunWithHeld();
        mvc.perform(get("/api/admin/billing/held").with(user("a").roles("ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/billing/held").with(user("sa").roles("SUPER_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].studentGroupId").value(h.sg()));
        mvc.perform(post("/api/admin/billing/held/{id}/preview", h.sg()).with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentStartDate\":\"2026-09-25\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentStartDate").value("2026-09-25"));
        mvc.perform(post("/api/admin/billing/held/{id}/apply", h.sg()).with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("billing.held.idempotencyKeyRequired"));
    }
}

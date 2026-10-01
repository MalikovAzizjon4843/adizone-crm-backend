package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.RefundPayoutRequest;
import com.crm.dto.response.BillingLineDto;
import com.crm.dto.response.PaymentPreviewResponse;
import com.crm.dto.response.PaymentResponse;
import com.crm.dto.response.RefundPayoutResponse;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.BonusPenalty;
import com.crm.entity.CashRegister;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.BonusTargetType;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.BonusPenaltyRepository;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.service.BonusPenaltyService;
import com.crm.service.FinanceService;
import com.crm.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 4-bosqich: to'lov oqimi (§5, §6.4, §6.5, §7, §13 #10/#23/#24), §12 T1.6, T3.4, T4.x, T5.x, T6.3, C1, C3. */
class PaymentFlowTest extends AbstractBillingIT {

    @Autowired
    PaymentService payments;
    @Autowired
    AccrualService accrual;
    @Autowired
    RefundPayoutService refunds;
    @Autowired
    BonusPenaltyService bonusService;
    @Autowired
    FinanceService finance;
    @Autowired
    PaymentRepository paymentRepo;
    @Autowired
    BalanceTransactionRepository txRepo;
    @Autowired
    BillingPeriodRepository periodRepo;
    @Autowired
    BonusPenaltyRepository bonusRepo;
    @Autowired
    StudentGroupRepository sgRepo;
    @Autowired
    CashRegisterRepository registerRepo;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    // ── yordamchilar ────────────────────────────────────────────────────

    record Ids(Long student, Long group, Long sg) {
    }

    /** 700 000 × 10% = 630 000, start 15.09; 15.09 dagi davr yozilgan: B = −630 000. */
    private Ids charged() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("15.09.2026")).discount("10").save();
        accrual.accrueUpTo(sg, d("15.09.2026"));
        return new Ids(student, group, sg);
    }

    private PaymentRequest req(Ids ids, long amount, Long register) {
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(ids.student());
        r.setGroupId(ids.group());
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(register);
        r.setPaymentMethod(PaymentMethod.CASH);
        return r;
    }

    private Long bonus(Long studentId, BonusPenaltyKind kind, long amount, String reason) {
        return inTx(() -> {
            BonusPenalty b = new BonusPenalty();
            b.setKind(kind);
            b.setTargetType(BonusTargetType.STUDENT);
            b.setStudent(sgRepo.findByStudentId(studentId).get(0).getStudent());
            b.setAmount(BigDecimal.valueOf(amount));
            b.setReason(reason);
            b.setStatus(BonusPenaltyStatus.PENDING);
            b.setEffectiveDate(clock.today());
            return bonusRepo.save(b).getId();
        });
    }

    private List<BalanceTransaction> ledger(Long sg) {
        return inTx(() -> txRepo.findByStudentGroup_IdOrderByIdAsc(sg));
    }

    private BigDecimal balance(Long sg) {
        return inTx(() -> sgRepo.findById(sg).orElseThrow().getBalance());
    }

    private CashRegister register(Long id) {
        return inTx(() -> registerRepo.findById(id).orElseThrow());
    }

    private static BigDecimal m(long v) {
        return BigDecimal.valueOf(v);
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

    // ── T1.6, T3.4 ──────────────────────────────────────────────────────

    @Test
    void paymentIsCreditOnly() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        clock.setDate(d("16.09.2026"));

        PaymentResponse p = payments.createPayment(req(ids, 630_000, reg), null);

        assertThat(shape(p.getLines())).containsExactly("PAYMENT 630000 2026-09-16");
        assertThat(ledger(ids.sg())).extracting(BalanceTransaction::getType)
            .containsExactly(BalanceTransactionType.PERIOD_CHARGE, BalanceTransactionType.PAYMENT);
        assertThat(balance(ids.sg())).isEqualByComparingTo("0");
        assertThat(p.getStatusAfter()).isEqualTo("PAID");
        assertThat(p.getReceiptNumber()).startsWith("RCP-");
    }

    @Test
    void money_wholeSumsOnly() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = req(ids, 0, reg);
        r.setAmount(new BigDecimal("630000.50"));
        assertCode(() -> payments.createPayment(r, null), "money.wholeSumRequired");
        assertThat(inTx(() -> paymentRepo.count())).isZero();

        // d = 100% → davr qatori bor, ledger yozuvi yo'q
        Long student = fixtures.student();
        Long sg = fixtures.enrollment(student, fixtures.group(fixtures.course(700_000)))
            .start(d("15.09.2026")).discount("100").save();
        accrual.accrueUpTo(sg, d("15.09.2026"));
        List<BillingPeriod> periods = inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sg));
        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).getAmount()).isEqualByComparingTo("0");
        assertThat(ledger(sg)).isEmpty();
    }

    // ── T4.x bonus ──────────────────────────────────────────────────────

    @Test
    void bonus_creditOnPayment() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        Long b = bonus(ids.student(), BonusPenaltyKind.BONUS, 100_000, "Do'stini olib keldi");
        Long p = bonus(ids.student(), BonusPenaltyKind.PENALTY, 30_000, "Kitob yo'qotildi");
        fixtures.loginAs(UserRole.ACCOUNTANT);

        PaymentResponse res = payments.createPayment(req(ids, 560_000, reg), null);

        assertThat(shape(res.getLines())).containsExactly(
            "PAYMENT 560000 2026-09-15", "BONUS 100000 2026-09-15", "PENALTY -30000 2026-09-15");
        assertThat(balance(ids.sg())).isEqualByComparingTo("0");
        assertThat(res.getStatusAfter()).isEqualTo("PAID");
        BonusPenalty applied = inTx(() -> bonusRepo.findById(b).orElseThrow());
        assertThat(applied.getStatus()).isEqualTo(BonusPenaltyStatus.APPLIED);
        assertThat(applied.getLedgerTxId()).isNotNull();
        assertThat(applied.getAppliedToPaymentId()).isEqualTo(res.getId());
        assertThat(inTx(() -> bonusRepo.findById(p).orElseThrow()).getStatus()).isEqualTo(BonusPenaltyStatus.APPLIED);
    }

    @Test
    void preview_equalsCreate_withBonuses() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        bonus(ids.student(), BonusPenaltyKind.BONUS, 100_000, "Tavsiya");
        bonus(ids.student(), BonusPenaltyKind.PENALTY, 30_000, "Jarima");
        fixtures.loginAs(UserRole.ADMIN);
        PaymentRequest r = req(ids, 500_000, reg);
        r.setDiscountAmount(m(60_000));
        r.setDiscountReason("Aksiya");

        PaymentPreviewResponse preview = payments.previewPayment(r);
        r.setExpectedPlanHash(preview.getPlanHash());
        PaymentResponse created = payments.createPayment(r, null);

        assertThat(shape(created.getLines())).isEqualTo(shape(preview.getLines()));
        assertThat(shape(preview.getLines())).contains("DISCOUNT 60000 2026-09-15");
        assertThat(created.getPlanHash()).isEqualTo(preview.getPlanHash());
        assertThat(created.getBalanceAfter()).isEqualByComparingTo(preview.getBalanceAfter());
        assertThat(created.getStatusAfter()).isEqualTo(preview.getStatusAfter());
        assertThat(preview.getCashAmount()).isEqualByComparingTo("440000");
    }

    @Test
    void preview_includesPendingAccrual_andStaleHashConflicts() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("15.09.2026")).save();
        Long reg = fixtures.cashRegister(false);
        Ids ids = new Ids(student, group, sg);
        fixtures.loginAs(UserRole.ACCOUNTANT);

        // Accrual hali yozilmagan: preview uni "pending" qator sifatida ko'rsatadi
        PaymentPreviewResponse preview = payments.previewPayment(req(ids, 700_000, reg));
        assertThat(shape(preview.getLines())).containsExactly(
            "PERIOD_CHARGE -700000 2026-09-15", "PAYMENT 700000 2026-09-15");
        assertThat(preview.getLines().get(0).getPending()).isTrue();
        assertThat(preview.getStatusAfter()).isEqualTo("PAID");

        // Holat o'zgardi (bonus qo'shildi) — eski planHash bilan 409
        bonus(student, BonusPenaltyKind.BONUS, 50_000, "x");
        PaymentRequest r = req(ids, 700_000, reg);
        r.setExpectedPlanHash(preview.getPlanHash());
        assertCode(() -> payments.createPayment(r, null), "payment.plan.changed");
    }

    @Test
    void bonus_notAppliedWhenFlagFalse() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        Long b = bonus(ids.student(), BonusPenaltyKind.BONUS, 100_000, "x");
        bonus(ids.student(), BonusPenaltyKind.PENALTY, 30_000, "y");
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = req(ids, 560_000, reg);
        r.setApplyBonuses(false);

        PaymentResponse res = payments.createPayment(r, null);

        assertThat(shape(res.getLines())).containsExactly("PAYMENT 560000 2026-09-15");
        assertThat(balance(ids.sg())).isEqualByComparingTo("-70000");
        assertThat(res.getStatusAfter()).isEqualTo("PENDING");
        assertThat(inTx(() -> bonusRepo.findById(b).orElseThrow()).getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);
    }

    /** §12.1 Testcontainers ni so'raydi; bu yerda H2 ning {@code FOR UPDATE} qulfi bilan. */
    @Test
    void bonus_concurrentPaymentsApplyOnce() throws Exception {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        Long b = bonus(ids.student(), BonusPenaltyKind.BONUS, 100_000, "x");

        fixtures.loginAs(UserRole.ACCOUNTANT); // foydalanuvchi oldindan yaratiladi

        List<PaymentResponse> results = parallel(2, i -> {
            fixtures.loginAs(UserRole.ACCOUNTANT);
            return payments.createPayment(req(ids, 300_000, reg), null);
        });

        long withBonus = results.stream()
            .filter(r -> r.getLines().stream().anyMatch(l -> "BONUS".equals(l.getType()))).count();
        assertThat(withBonus).isEqualTo(1);
        assertThat(ledger(ids.sg())).filteredOn(t -> t.getType() == BalanceTransactionType.BONUS).hasSize(1);
        assertThat(balance(ids.sg())).isEqualByComparingTo("70000");
        assertThat(inTx(() -> bonusRepo.findById(b).orElseThrow()).getStatus()).isEqualTo(BonusPenaltyStatus.APPLIED);
    }

    @Test
    void bonus_cancelApplied() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        Long b = bonus(ids.student(), BonusPenaltyKind.BONUS, 100_000, "x");
        fixtures.loginAs(UserRole.ACCOUNTANT);
        payments.createPayment(req(ids, 530_000, reg), null);
        assertThat(balance(ids.sg())).isEqualByComparingTo("0");

        fixtures.loginAs(UserRole.ADMIN);
        assertCode(() -> bonusService.cancel(b, "Xato"), "bonus.cancel.forbidden");
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertCode(() -> bonusService.cancel(b, " "), "bonus.cancel.reasonRequired");

        var dto = bonusService.cancel(b, "Noto'g'ri berilgan");

        assertThat(dto.getStatus()).isEqualTo(BonusPenaltyStatus.CANCELLED);
        assertThat(dto.getCancelReason()).isEqualTo("Noto'g'ri berilgan");
        BalanceTransaction last = ledger(ids.sg()).get(ledger(ids.sg()).size() - 1);
        assertThat(last.getType()).isEqualTo(BalanceTransactionType.REVERSAL);
        assertThat(last.getAmount()).isEqualByComparingTo("-100000");
        assertThat(last.getRelatedTxId()).isEqualTo(dto.getLedgerTxId());
        assertThat(balance(ids.sg())).isEqualByComparingTo("-100000");
    }

    @Test
    void bonus_applyWithoutPayment() {
        Ids ids = charged();
        Long b = bonus(ids.student(), BonusPenaltyKind.BONUS, 100_000, "x");
        fixtures.loginAs(UserRole.ADMIN);

        var dto = bonusService.apply(b, null);

        assertThat(dto.getStatus()).isEqualTo(BonusPenaltyStatus.APPLIED);
        assertThat(dto.getStudentGroupId()).isEqualTo(ids.sg());
        assertThat(dto.getGroupId()).isEqualTo(ids.group());
        assertThat(balance(ids.sg())).isEqualByComparingTo("-530000");
        assertThatThrownBy(() -> bonusService.apply(b, null))
            .isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode()).isEqualTo("bonus.notPending");
    }

    // ── T5.x bekor qilish ───────────────────────────────────────────────

    @Test
    void cancel_reversesLedgerAndCash() throws Exception {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentResponse p = payments.createPayment(req(ids, 630_000, reg), null);
        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("630000");

        clock.setDate(d("17.09.2026"));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PaymentResponse c = payments.cancelPayment(p.getId(), "Summa xato kiritildi");

        assertThat(c.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(c.getCancelReason()).isEqualTo("Summa xato kiritildi");
        assertThat(shape(c.getReversalLines())).containsExactly("REVERSAL -630000 2026-09-15");
        assertThat(balance(ids.sg())).isEqualByComparingTo("-630000");
        assertThat(c.getStatusAfter()).isEqualTo("PENDING");
        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("0");
        assertThat(inTx(() -> paymentRepo.findById(p.getId()).orElseThrow()).getStatus())
            .isEqualTo(PaymentStatus.CANCELLED);

        long deadline = System.currentTimeMillis() + 5000;
        Integer audits = 0;
        while (System.currentTimeMillis() < deadline) {
            audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'PAYMENT_CANCEL'", Integer.class);
            if (audits != null && audits > 0) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void cancel_onlySuperAdmin_reasonRequired_once() throws Exception {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        Long paymentId = payments.createPayment(req(ids, 630_000, reg), null).getId();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();

        String url = "/api/payments/" + paymentId + "/cancel";
        mvc.perform(post(url).with(asRole("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Xato\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(url).with(asRole("SUPER_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("payment.cancel.reasonRequired"));
        mvc.perform(post(url).with(asRole("SUPER_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Xato kiritildi\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(post(url).with(asRole("SUPER_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Yana bir bor\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("payment.alreadyCancelled"));
    }

    /** §13 #23: {@code paymentDate ≥ bugun − 31 kun}; eskisi → 400, SA MANUAL_ADJUST bilan tuzatadi. */
    @Test
    void cancel_olderThan31Days_rejected() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        Long old = payments.createPayment(req(ids, 300_000, reg), null).getId();
        Long fresh;
        clock.setDate(d("16.10.2026"));
        fresh = payments.createPayment(req(ids, 330_000, reg), null).getId();

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        // 16.10 − 31 = 15.09 → 15.09 dagi to'lov hali chegarada
        assertThat(payments.cancelPayment(old, "Chegarada").getStatus()).isEqualTo(PaymentStatus.CANCELLED);

        Long older = inTx(() -> {
            Payment p = paymentRepo.findById(fresh).orElseThrow();
            p.setPaymentDate(d("14.09.2026"));
            return paymentRepo.save(p).getId();
        });
        assertCode(() -> payments.cancelPayment(older, "Juda eski"), "payment.cancel.tooOld");
    }

    @Test
    void cancel_returnsBonusToPending() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        Long b = bonus(ids.student(), BonusPenaltyKind.BONUS, 50_000, "Do'sti");
        fixtures.loginAs(UserRole.ACCOUNTANT);
        clock.setDate(d("16.09.2026"));
        PaymentResponse p = payments.createPayment(req(ids, 580_000, reg), null);
        assertThat(balance(ids.sg())).isEqualByComparingTo("0");

        clock.setDate(d("17.09.2026"));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PaymentResponse c = payments.cancelPayment(p.getId(), "Summa xato kiritildi");

        assertThat(shape(c.getReversalLines())).containsExactlyInAnyOrder(
            "REVERSAL -580000 2026-09-16", "REVERSAL -50000 2026-09-16");
        assertThat(balance(ids.sg())).isEqualByComparingTo("-630000");
        assertThat(c.getStatusAfter()).isEqualTo("PENDING");
        BonusPenalty back = inTx(() -> bonusRepo.findById(b).orElseThrow());
        assertThat(back.getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);
        assertThat(back.getAppliedToPaymentId()).isNull();
        assertThat(back.getLedgerTxId()).isNull();
    }

    @Test
    void cancel_splitPayment() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(true);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = req(ids, 630_000, reg);
        r.setPaymentMethod(PaymentMethod.CASH_AND_CARD);
        r.setCashPart(m(400_000));
        r.setCardPart(m(230_000));
        PaymentResponse p = payments.createPayment(r, null);
        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("400000");
        assertThat(register(reg).getPlasticBalance()).isEqualByComparingTo("230000");

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payments.cancelPayment(p.getId(), "Bekor");

        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("0");
        assertThat(register(reg).getPlasticBalance()).isEqualByComparingTo("0");
        assertThat(register(reg).getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void finance_excludesCancelled() {
        Ids a = charged();
        Ids b = charged();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        payments.createPayment(req(a, 630_000, reg), null);
        Long cancelled = payments.createPayment(req(b, 580_000, reg), null).getId();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payments.cancelPayment(cancelled, "Xato");

        var report = finance.getFinanceReport(d("01.09.2026"), d("30.09.2026"));

        assertThat(report.getTotalIncome()).isEqualByComparingTo("630000");
        assertThat(report.getIncomeByCategory().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("630000");
    }

    // ── T6.3 sinov ──────────────────────────────────────────────────────

    @Test
    void trial_paymentAutoConverts() {
        clock.setDate(d("06.10.2026"));
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("01.10.2026")).trial().save();
        accrual.accrueUpTo(sg, d("06.10.2026"));
        assertThat(ledger(sg)).isEmpty();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);

        PaymentResponse p = payments.createPayment(req(new Ids(student, group, sg), 700_000, reg), null);

        assertThat(shape(p.getLines())).containsExactly(
            "PERIOD_CHARGE -700000 2026-10-06", "PAYMENT 700000 2026-10-06");
        StudentGroup after = inTx(() -> sgRepo.findById(sg).orElseThrow());
        assertThat(after.getIsTrial()).isFalse();
        assertThat(after.getPaymentStartDate()).isEqualTo(d("06.10.2026"));
        assertThat(after.getBalance()).isEqualByComparingTo("0");
        assertThat(p.getStatusAfter()).isEqualTo("PAID");
        assertThat(p.getNextPaymentDate()).isEqualTo(d("06.11.2026"));
    }

    // ── Qoidalar: groupId, kassa, chegirma ──────────────────────────────

    @Test
    void groupId_requiredWithSeveralGroups_andMustBeEnrolled() {
        Ids ids = charged();
        Long second = fixtures.group(fixtures.course(500_000));
        fixtures.enrollment(ids.student(), second).start(d("15.09.2026")).save();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);

        PaymentRequest noGroup = req(ids, 630_000, reg);
        noGroup.setGroupId(null);
        assertCode(() -> payments.createPayment(noGroup, null), "payment.group.required");

        PaymentRequest foreign = req(ids, 630_000, reg);
        foreign.setGroupId(fixtures.group(fixtures.course(100_000)));
        assertCode(() -> payments.createPayment(foreign, null), "payment.enrollment.notFound");

        // Yagona guruh — groupId ixtiyoriy
        Ids single = charged();
        PaymentRequest implicit = req(single, 630_000, reg);
        implicit.setGroupId(null);
        assertThat(payments.createPayment(implicit, null).getStudentGroupId()).isEqualTo(single.sg());
    }

    @Test
    void cashRegister_required() {
        Ids ids = charged();
        fixtures.loginAs(UserRole.ACCOUNTANT);
        assertCode(() -> payments.createPayment(req(ids, 630_000, null), null), "payment.cashRegister.required");
    }

    /** §13 #10: DISCOUNT krediti faqat SA, A; sabab majburiy. */
    @Test
    void discount_onlyAdmins_reasonRequired() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        PaymentRequest r = req(ids, 630_000, reg);
        r.setDiscountAmount(m(30_000));

        fixtures.loginAs(UserRole.ACCOUNTANT);
        assertCode(() -> payments.createPayment(r, null), "payment.discount.forbidden");
        assertCode(() -> payments.previewPayment(r), "payment.discount.forbidden");

        fixtures.loginAs(UserRole.ADMIN);
        assertCode(() -> payments.createPayment(r, null), "payment.discount.reasonRequired");

        r.setDiscountReason("Ko'p bolali oila");
        PaymentResponse p = payments.createPayment(r, null);
        assertThat(shape(p.getLines())).containsExactly(
            "PAYMENT 600000 2026-09-15", "DISCOUNT 30000 2026-09-15");
        assertThat(p.getCashAmount()).isEqualByComparingTo("600000");
        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("600000");
        assertThat(balance(ids.sg())).isEqualByComparingTo("0");
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    void discount_forbiddenOverHttp() throws Exception {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        PaymentRequest r = req(ids, 630_000, reg);
        r.setDiscountAmount(m(30_000));
        r.setDiscountReason("x");
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(r)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("payment.discount.forbidden"));
    }

    // ── C1, C3 ──────────────────────────────────────────────────────────

    @Test
    void cash_concurrentIncome() throws Exception {
        Long reg = fixtures.cashRegister(false);
        inTx(() -> {
            CashRegister r = registerRepo.findById(reg).orElseThrow();
            r.setCashBalance(m(1_000_000));
            r.setBalance(m(1_000_000));
            registerRepo.save(r);
        });
        Ids a = charged();
        Ids b = charged();

        fixtures.loginAs(UserRole.ACCOUNTANT); // foydalanuvchi oldindan yaratiladi

        parallel(2, i -> {
            fixtures.loginAs(UserRole.ACCOUNTANT);
            return payments.createPayment(req(i == 0 ? a : b, 500_000, reg), null);
        });

        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("2000000");
        assertThat(register(reg).getBalance()).isEqualByComparingTo("2000000");
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    void idempotencyKey_replay() throws Exception {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        String body = json.writeValueAsString(req(ids, 630_000, reg));

        String first = mvc.perform(post("/api/payments").header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("X-Idempotent-Replay"))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/payments").header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Idempotent-Replay", "true"))
            .andExpect(jsonPath("$.data.id").value(json.readTree(first).at("/data/id").asLong()))
            .andExpect(jsonPath("$.data.lines[0].type").value("PAYMENT"));
        assertThat(inTx(() -> paymentRepo.count())).isEqualTo(1);
        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("630000");

        String other = json.writeValueAsString(req(ids, 600_000, reg));
        mvc.perform(post("/api/payments").header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(other))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("payment.idempotency.mismatch"));
    }

    // ── REFUND_PAYOUT (§13 #24) ─────────────────────────────────────────

    @Test
    void refundPayout_withinPositiveBalance() {
        Ids ids = charged();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        payments.createPayment(req(ids, 830_000, reg), null);
        assertThat(balance(ids.sg())).isEqualByComparingTo("200000");

        fixtures.loginAs(UserRole.ADMIN);
        RefundPayoutRequest r = new RefundPayoutRequest();
        r.setAmount(m(150_000));
        r.setCashRegisterId(reg);
        r.setReason("O'qishni to'xtatdi");
        RefundPayoutResponse res = refunds.payout(ids.student(), r);

        assertThat(res.getLine().getType()).isEqualTo("REFUND_PAYOUT");
        assertThat(res.getLine().getAmount()).isEqualByComparingTo("-150000");
        assertThat(res.getBalanceAfter()).isEqualByComparingTo("50000");
        assertThat(balance(ids.sg())).isEqualByComparingTo("50000");
        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("680000");
        BalanceTransaction tx = ledger(ids.sg()).get(ledger(ids.sg()).size() - 1);
        assertThat(tx.getReferenceId()).isEqualTo(res.getCashTransactionId());

        r.setAmount(m(100_000));
        assertCode(() -> refunds.payout(ids.student(), r), "refund.amount.exceedsBalance");
        r.setAmount(m(10_000));
        r.setReason(" ");
        assertCode(() -> refunds.payout(ids.student(), r), "refund.reason.required");
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    void refundPayout_forbiddenForAccountant() throws Exception {
        Ids ids = charged();
        mvc.perform(post("/api/students/" + ids.student() + "/refund-payout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("amount", 1000, "cashRegisterId", 1, "reason", "abc"))))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void bonusApply_endpoint() throws Exception {
        Ids ids = charged();
        Long b = bonus(ids.student(), BonusPenaltyKind.PENALTY, 20_000, "Kech qoldi");
        mvc.perform(post("/api/bonus-penalties/" + b + "/apply"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("APPLIED"));
        assertThat(balance(ids.sg())).isEqualByComparingTo("-650000");
        mvc.perform(patch("/api/bonus-penalties/" + b + "/cancel").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Xato\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("bonus.cancel.forbidden"));
    }

    // ── parallel yordamchi ──────────────────────────────────────────────

    interface Task<T> {
        T run(int i) throws Exception;
    }

    private <T> List<T> parallel(int n, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                Callable<T> c = () -> {
                    start.await();
                    try {
                        return task.run(idx);
                    } finally {
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                    }
                };
                futures.add(pool.submit(c));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asRole(String role) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("mvc-" + role.toLowerCase()).roles(role);
    }

}

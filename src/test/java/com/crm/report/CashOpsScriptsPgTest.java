package com.crm.report;

import com.crm.billing.AccrualService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.ExpenseCreateDto;
import com.crm.dto.request.IncomeCreateDto;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.TransferDto;
import com.crm.dto.response.CashCategoryTotalDto;
import com.crm.dto.response.FinanceReportResponse;
import com.crm.entity.CashRegister;
import com.crm.entity.enums.CashRegisterKind;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.repository.CashRegisterRepository;
import com.crm.service.CashHandoverService;
import com.crm.service.CashRegisterService;
import com.crm.service.FinanceService;
import com.crm.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * docs/ops dagi kassa skriptlari haqiqiy PostgreSQL'da (faqat pgtest profili; H2 da o'tkazib yuboriladi):
 * cash-category-backfill.sql (hisobot backfill'dan oldin va keyin bir xil), cash-cutover.sql (eski qoldiq Direktor
 * kassasiga, hisobotga ta'sirsiz, idempotent), cash-future-dates.sql (to'lov sanasiga tuzatish).
 */
class CashOpsScriptsPgTest extends AbstractBillingIT {

    private static final LocalDate WIDE_FROM = LocalDate.of(2000, 1, 1);
    private static final LocalDate WIDE_TO = LocalDate.of(2100, 12, 31);

    @Autowired CashRegisterService cash;
    @Autowired CashHandoverService handovers;
    @Autowired FinanceService finance;
    @Autowired PaymentService payments;
    @Autowired AccrualService accrual;
    @Autowired CashRegisterRepository registerRepo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void onlyPostgres() {
        assumeTrue(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")),
            "faqat pgtest profili (PostgreSQL)");
    }

    private void runScript(String name) throws Exception {
        jdbc.execute(Files.readString(Path.of("docs", "ops", name)));
    }

    private void income(Long reg, PaymentMethod m, long amount, String name, Long cashPart, Long cardPart) {
        IncomeCreateDto dto = new IncomeCreateDto();
        dto.setAmount(BigDecimal.valueOf(amount));
        dto.setPaymentMethod(m);
        dto.setTransactionType(name);
        dto.setCashPart(cashPart != null ? BigDecimal.valueOf(cashPart) : null);
        dto.setCardPart(cardPart != null ? BigDecimal.valueOf(cardPart) : null);
        cash.addIncome(reg, dto);
    }

    private void expense(Long reg, String category, PaymentMethod m, long amount) {
        ExpenseCreateDto dto = new ExpenseCreateDto();
        dto.setAmount(BigDecimal.valueOf(amount));
        dto.setPaymentMethod(m);
        dto.setCategory(category);
        cash.addExpense(reg, dto);
    }

    /** O'quvchi to'lovi (SA) shu kassaga — payment id. */
    private Long payment(Long reg, long amount) {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("15.09.2026")).save();
        accrual.accrueUpTo(sg, d("15.09.2026"));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(reg);
        r.setPaymentMethod(PaymentMethod.CASH);
        return payments.createPayment(r, null).getId();
    }

    private CashRegister register(Long id) {
        return inTx(() -> registerRepo.findById(id).orElseThrow());
    }

    private static String sig(List<CashCategoryTotalDto> rows) {
        return rows.stream().map(c -> c.getCategory() + "=" + c.getAmount().stripTrailingZeros().toPlainString())
            .toList().toString();
    }

    private static String sig(FinanceReportResponse r) {
        return List.of(r.getTotalIncome(), r.getExamFees(), r.getOtherIncome(), r.getTotalExpenses(), r.getRefunds(),
                r.getCashShortage(), r.getPayrollPaid(), r.getNetProfit()).stream()
            .map(v -> v.stripTrailingZeros().toPlainString()).toList()
            + " " + r.getIncomeByCategory() + " " + r.getExpenseByCategory() + " " + r.getIncomeByMethod()
            + " " + r.getExpenseByMethod();
    }

    // ── 1. Kategoriya backfill ──────────────────────────────────────────

    @Test
    void categoryBackfill_legacyRowsCategorized_reportsUnchanged() throws Exception {
        Long reg = fixtures.cashRegister(true);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        Long paid = payment(reg, 300_000);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payments.cancelPayment(paid, "Xato");
        payment(reg, 200_000);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        income(reg, PaymentMethod.CLICK, 150_000, "O'quvchi to'lovi", null, null);   // eski to'lov kirimi
        income(reg, PaymentMethod.CASH, 40_000, null, null, null);                     // nomsiz
        expense(reg, "DELIVERY", PaymentMethod.CASH, 9_000);
        expense(reg, "OTHER_DAILY", PaymentMethod.CASH, 4_000);
        expense(reg, "OTHER_DAILY", PaymentMethod.CASH, 6_000);
        // Hammasini "eski" holatga: kategoriyasiz, payment_id siz; "Xarajat: RENT" va nomsiz chiqim
        jdbc.update("UPDATE cash_transactions SET category = NULL, payment_id = NULL");
        jdbc.update("UPDATE cash_transactions SET transaction_name = 'Xarajat: RENT' WHERE amount = 4000");
        jdbc.update("UPDATE cash_transactions SET transaction_name = NULL WHERE amount = 6000");
        jdbc.update("UPDATE cash_transactions SET transaction_name = 'O''quvchiga pul qaytarildi' WHERE amount = 9000");

        String reportBefore = sig(finance.getFinanceReport(WIDE_FROM, WIDE_TO));
        String kassaBefore = sig(cash.getCategoryReport(reg, null, null));

        runScript("cash-category-backfill.sql");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions WHERE category IS NULL "
            + "AND type IN ('INCOME','EXPENSE','REVERSAL')", Long.class)).isZero();
        Map<String, Object> cats = Map.of(
            "INCOME", jdbc.queryForList("SELECT category FROM cash_transactions WHERE type = 'INCOME' ORDER BY id",
                String.class),
            "EXPENSE", jdbc.queryForList("SELECT category FROM cash_transactions WHERE type = 'EXPENSE' ORDER BY id",
                String.class),
            "REVERSAL", jdbc.queryForList("SELECT category FROM cash_transactions WHERE type = 'REVERSAL'",
                String.class));
        assertThat(cats.get("INCOME")).isEqualTo(
            List.of("STUDENT_PAYMENT", "STUDENT_PAYMENT", "STUDENT_PAYMENT", "OTHER_INCOME"));
        assertThat(cats.get("EXPENSE")).isEqualTo(List.of("REFUND", "RENT", "OTHER"));
        assertThat(cats.get("REVERSAL")).isEqualTo(List.of("STUDENT_PAYMENT"));
        // Hisobot backfill'ga bog'liq emas: qoida AYNAN bir xil
        assertThat(sig(finance.getFinanceReport(WIDE_FROM, WIDE_TO))).isEqualTo(reportBefore);
        assertThat(sig(cash.getCategoryReport(reg, null, null))).isEqualTo(kassaBefore);
        FinanceReportResponse r = finance.getFinanceReport(WIDE_FROM, WIDE_TO);
        assertThat(r.getOtherIncome()).isEqualByComparingTo("40000");      // eski to'lov kirimi boshqa kirim EMAS
        assertThat(r.getRefunds()).isEqualByComparingTo("9000");

        runScript("cash-category-backfill.sql");                            // idempotent
        assertThat(sig(finance.getFinanceReport(WIDE_FROM, WIDE_TO))).isEqualTo(reportBefore);
    }

    // ── 2. Cut-over ─────────────────────────────────────────────────────

    @Test
    void cutover_movesLegacyBalancesToDirector_perMethod_reportUnchanged_idempotent() throws Exception {
        Long a = fixtures.cashRegister(true);
        Long b = fixtures.cashRegister(false);
        Long adminId = fixtures.user(UserRole.ADMIN, "test-admin");
        jdbc.update("UPDATE cash_registers SET moderator_id = ? WHERE id = ?", adminId, a);
        jdbc.update("DELETE FROM cash_register_members");          // a'zolarni cut-over yaratadi
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payment(a, 630_000);                                         // CASH, payment_id bilan
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        income(a, PaymentMethod.CARD, 200_000, "O'quvchi to'lovi", null, null);
        income(a, PaymentMethod.CLICK, 100_000, null, null, null);
        income(a, PaymentMethod.CASH_AND_CARD, 300_000, null, 120_000L, 180_000L);
        income(a, PaymentMethod.TERMINAL, 50_000, null, null, null);
        jdbc.update("UPDATE cash_transactions SET payment_method = 'BANK' WHERE amount = 50000");   // eski BANK
        expense(a, "DELIVERY", PaymentMethod.CASH, 30_000);
        expense(a, "OTHER_DAILY", PaymentMethod.CLICK, 120_000);                       // CLICK sofi manfiy
        TransferDto t = new TransferDto();
        t.setFromCashRegisterId(a);
        t.setToCashRegisterId(b);
        t.setAmount(BigDecimal.valueOf(10_000));
        t.setPaymentMethod(PaymentMethod.CASH);
        cash.transfer(t);
        income(b, PaymentMethod.CASH, 70_000, null, null, null);
        // Tranzaksiyasiz farq (boshlang'ich qoldiq): b kassada +5 000 naqd, +7 000 plastik
        jdbc.update("UPDATE cash_registers SET cash_balance = cash_balance + 5000, plastic_balance = plastic_balance + 7000,"
            + " balance = balance + 12000 WHERE id = ?", b);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_registers WHERE kind = 'DIRECTOR'", Long.class)).isZero();

        CashRegister aBefore = register(a);
        CashRegister bBefore = register(b);
        String reportBefore = sig(finance.getFinanceReport(WIDE_FROM, WIDE_TO));

        runScript("cash-cutover.sql");

        Long dir = jdbc.queryForObject("SELECT id FROM cash_registers WHERE kind = 'DIRECTOR'", Long.class);
        CashRegister aAfter = register(a);
        CashRegister bAfter = register(b);
        CashRegister dAfter = register(dir);
        assertThat(aAfter.getCashBalance()).isEqualByComparingTo("0");
        assertThat(aAfter.getPlasticBalance()).isEqualByComparingTo("0");
        assertThat(bAfter.getCashBalance()).isEqualByComparingTo("0");
        assertThat(bAfter.getPlasticBalance()).isEqualByComparingTo("0");
        assertThat(dAfter.getKind()).isEqualTo(CashRegisterKind.DIRECTOR);
        assertThat(dAfter.getCashBalance()).isEqualByComparingTo(aBefore.getCashBalance().add(bBefore.getCashBalance()));
        assertThat(dAfter.getPlasticBalance())
            .isEqualByComparingTo(aBefore.getPlasticBalance().add(bBefore.getPlasticBalance()));
        assertThat(dAfter.getBalance()).isEqualByComparingTo(dAfter.getCashBalance().add(dAfter.getPlasticBalance()));

        // Usul bo'yicha (tranzaksiyalardan) admin kassasi a — hammasi 0 (CLICK manfiy sof — teskari o'tkazma)
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertThat(cash.getBalance(a).getByMethod()).allSatisfy(c ->
            assertThat(c.getNet()).as(c.getChannel()).isEqualByComparingTo("0"));
        assertThat(cash.getBalance(a).isReconciled()).isTrue();
        // O'tkazmalar — kategoriyasiz, oddiy juftlik, legacy topshirishga bog'langan
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions WHERE note = 'Cut-over (V86): eski qoldiq'"
            + " AND (type <> 'TRANSFER' OR category IS NOT NULL OR handover_id IS NULL)", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions i JOIN cash_transactions o "
            + "ON o.id = i.related_tx_id WHERE i.transaction_name = 'Ko''chirish (kirim)' "
            + "AND i.note = 'Cut-over (V86): eski qoldiq' AND o.transaction_name = 'Ko''chirish (chiqim)' "
            + "AND o.amount = i.amount AND o.payment_method = i.payment_method", Long.class))
            .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions "
                + "WHERE transaction_name = 'Ko''chirish (chiqim)' AND note = 'Cut-over (V86): eski qoldiq'", Long.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions WHERE payment_method = 'CLICK' "
            + "AND note = 'Cut-over (V86): eski qoldiq' AND cash_register_id = ? AND transaction_name = 'Ko''chirish (chiqim)'",
            Long.class, dir)).isEqualTo(1L);
        // A'zo, topshirish
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_register_members WHERE cash_register_id = ? AND user_id = ?",
            Long.class, a, adminId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions WHERE handover_id IS NULL "
            + "AND cash_register_id IN (?, ?)", Long.class, a, b)).isZero();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertThat(handovers.preview(a).getTransactionCount()).isZero();
        assertThat(handovers.preview(a).getExpectedCash()).isEqualByComparingTo("0");
        // Moliya hisobotiga ta'sir yo'q
        assertThat(sig(finance.getFinanceReport(WIDE_FROM, WIDE_TO))).isEqualTo(reportBefore);

        // Idempotent: ikkinchi marta ko'chirmaydi
        long txCount = jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions", Long.class);
        long handoverCount = jdbc.queryForObject("SELECT COUNT(*) FROM cash_handovers", Long.class);
        runScript("cash-cutover.sql");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions", Long.class)).isEqualTo(txCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_handovers", Long.class)).isEqualTo(handoverCount);
        assertThat(register(dir).getCashBalance()).isEqualByComparingTo(dAfter.getCashBalance());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_registers WHERE kind = 'DIRECTOR'", Long.class))
            .isEqualTo(1L);
    }

    // ── 3. Kelajak sanalari ─────────────────────────────────────────────

    @Test
    void futureDates_fixedToPaymentDate_byPaymentIdOrReceipt_othersUntouched() throws Exception {
        Long reg = fixtures.cashRegister(false);
        Long p1 = payment(reg, 100_000);
        Long p2 = payment(reg, 200_000);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        expense(reg, "DELIVERY", PaymentMethod.CASH, 5_000);
        LocalDate future = jdbc.queryForObject("SELECT current_date + 11", LocalDate.class);
        jdbc.update("UPDATE cash_transactions SET transaction_date = current_date + 11");
        // p2 — eski usul: payment_id yo'q, chek izohi bo'yicha topiladi
        jdbc.update("UPDATE cash_transactions SET payment_id = NULL WHERE payment_id = ?", p2);
        LocalDate payDate = jdbc.queryForObject("SELECT payment_date FROM payments WHERE id = ?", LocalDate.class, p1);

        runScript("cash-future-dates.sql");

        assertThat(jdbc.queryForObject("SELECT transaction_date FROM cash_transactions WHERE payment_id = ?",
            LocalDate.class, p1)).isEqualTo(payDate);
        assertThat(jdbc.queryForObject("SELECT transaction_date FROM cash_transactions WHERE amount = 200000",
            LocalDate.class)).isEqualTo(payDate);
        assertThat(jdbc.queryForObject("SELECT transaction_date FROM cash_transactions WHERE type = 'EXPENSE'",
            LocalDate.class)).isEqualTo(future);                    // to'lovsiz — qo'lda
        runScript("cash-future-dates.sql");                         // idempotent
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions WHERE transaction_date > current_date",
            Long.class)).isEqualTo(1L);
    }
}

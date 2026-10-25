package com.crm.service;

import com.crm.dto.request.ExpenseRequest;
import com.crm.dto.response.ExpenseResponse;
import com.crm.dto.response.FinanceReportResponse;
import com.crm.entity.CashCategory;
import com.crm.entity.CashRegister;
import com.crm.entity.Expense;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.CashCategoryScope;
import com.crm.entity.enums.CashDirection;
import com.crm.entity.enums.PaymentChannel;
import com.crm.entity.enums.PaymentMethod;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class FinanceService {

    private final ExpenseRepository expenseRepository;
    private final IncomeRepository incomeRepository;
    private final PaymentRepository paymentRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final CashRegisterService cashRegisterService;
    private final PayrollRepository payrollRepository;
    private final com.crm.repository.CashTransactionRepository cashTransactionRepository;
    private final CashChannelService cashChannelService;
    private final com.crm.billing.PaymentDatePolicy datePolicy;
    private final CashAccessService access;
    private final CashCategoryService categoryService;
    private final CashCategoryTotals categoryTotals;

    /**
     * Xarajatlar ro'yxati. SA — hammasi; ADMIN / ACCOUNTANT — faqat o'z kassasidagi DAILY xarajatlar
     * (V86, cash-and-books §1.5).
     */
    @Transactional(readOnly = true)
    public Page<ExpenseResponse> getExpensesFiltered(
            LocalDate from, LocalDate to, String category, int page, int size) {

        String categoryFilter = CashCategoryService.normalize(category);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "expenseDate"));
        Map<String, CashCategory> categories = categoryService.all();

        final LocalDate fromDate = from;
        final LocalDate toDate = to;

        Specification<Expense> spec = Specification.where(null);
        if (fromDate != null) {
            spec = spec.and((root, q, cb) ->
                cb.greaterThanOrEqualTo(root.get("expenseDate"), fromDate));
        }
        if (toDate != null) {
            spec = spec.and((root, q, cb) ->
                cb.lessThanOrEqualTo(root.get("expenseDate"), toDate));
        }
        if (categoryFilter != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("category"), categoryFilter));
        }
        List<Long> visible = access.visibleRegisterIds();
        if (visible != null) {
            List<String> daily = categories.values().stream()
                .filter(c -> c.getScope() == CashCategoryScope.DAILY)
                .map(CashCategory::getCode)
                .toList();
            if (visible.isEmpty() || daily.isEmpty()) {
                spec = spec.and((root, q, cb) -> cb.disjunction());
            } else {
                spec = spec.and((root, q, cb) -> cb.and(
                    root.get("cashRegister").get("id").in(visible),
                    root.get("category").in(daily)));
            }
        }

        return expenseRepository.findAll(spec, pageable).map(e -> toResponse(e, categories));
    }

    /**
     * Xarajat (V86 qoidasi, {@link CashCategoryService#checkManual}): DAILY — admin o'z kassasidan (kassa
     * majburiy); STRATEGIC — faqat SA, kassa ko'rsatilsa faqat Direktor kassasi; SYSTEM (oylik, qaytarish,
     * kamomad ...) — bu yerdan emas.
     */
    @Transactional
    public ExpenseResponse createExpense(ExpenseRequest request) {
        datePolicy.check(request.getExpenseDate());     // kelajak — 400, o'tgan — faqat SA
        String code = CashCategoryService.normalize(request.getCategory());
        CashRegister register = request.getCashRegisterId() != null
            ? access.requireAccessible(request.getCashRegisterId()) : null;
        CashCategory category = categoryService.checkManual(code, CashDirection.OUT, register);

        Expense expense = Expense.builder()
            .category(category.getCode())
            .title(request.getTitle())
            .amount(request.getAmount())
            .expenseDate(request.getExpenseDate())
            .description(request.getDescription())
            .notes(request.getNotes())
            .build();

        if (request.getTeacherId() != null) {
            Teacher teacher = teacherRepository.findById(request.getTeacherId())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));
            expense.setTeacher(teacher);
        }

        Expense saved = expenseRepository.save(expense);

        if (register != null) {
            PaymentMethod cashMethod = resolveCashPaymentMethod(request.getPaymentMethodForCash());
            User creator = currentUser();
            var cashTx = cashRegisterService.recordExpense(
                register.getId(),
                saved.getAmount(),
                cashMethod,
                category.getName() + ": " + saved.getTitle(),
                saved.getDescription(),
                saved.getExpenseDate(),
                creator,
                null,
                null,
                null,
                null,
                request.getCashPart(),
                request.getCardPart(),
                null,
                category.getCode());
            saved.setCashRegister(cashTx.getCashRegister());
            saved = expenseRepository.save(saved);
        }

        return toResponse(saved, Map.of(category.getCode(), category));
    }

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElse(null);
    }

    /** Eski "PLASTIC" nomi ham qo'llab-quvvatlanadi (-> CARD). Ko'rsatilmasa — naqd. */
    private static PaymentMethod resolveCashPaymentMethod(String paymentMethodForCash) {
        return PaymentMethod.parseOrDefault(paymentMethodForCash, PaymentMethod.CASH);
    }

    // ── Moliya hisoboti (faqat SA — controller; cash-and-books §1.3) ─────────────────

    /**
     * Kassa oqimi tasnifi — moliya hisoboti va analitika uchun YAGONA qoida. Manbasi boshqa joyda bo'lgan
     * oqim ({@code link}: PAYMENT — payments, EXAM — examFees, PAYROLL — payroll) o'tkazib yuboriladi.
     * Kirim kategoriyalari — "boshqa kirim"; REFUND — alohida; qolgan chiqim (CASH_SHORTAGE ham) — xarajat.
     */
    public static final class Classified {
        public final Map<String, BigDecimal> incomeByCategory = new LinkedHashMap<>();
        public final Map<String, BigDecimal> expenseByCategory = new LinkedHashMap<>();
        public final Map<String, BigDecimal> expenseByScope = new LinkedHashMap<>();
        public BigDecimal otherIncome = BigDecimal.ZERO;
        public BigDecimal expenses = BigDecimal.ZERO;
        public BigDecimal refunds = BigDecimal.ZERO;
        public BigDecimal cashShortage = BigDecimal.ZERO;
        /** Kitob sotuvi (BOOK_SALE, V87) — alohida qator, "boshqa kirim" emas. */
        public BigDecimal bookSales = BigDecimal.ZERO;
        /** Yetkazib beruvchiga to'lov (BOOK_PURCHASE, V87) — alohida qator, xarajatlar yig'indisida emas. */
        public BigDecimal bookPurchases = BigDecimal.ZERO;

        void add(String rawCategory, String link, BigDecimal amount, Map<String, CashCategory> categories) {
            if (link != null || amount == null || amount.signum() == 0) {
                return;
            }
            CashCategory c = categories.get(rawCategory);
            if (c == null) {
                c = categories.get(CashCategories.OTHER);
            }
            String code = c != null ? c.getCode() : CashCategories.OTHER;
            if (CashCategories.BOOK_SALE.equals(code)) {
                bookSales = bookSales.add(amount);
                return;
            }
            if (CashCategories.BOOK_PURCHASE.equals(code)) {
                bookPurchases = bookPurchases.add(amount);
                return;
            }
            if (c != null && c.getDirection() == CashDirection.IN) {
                incomeByCategory.merge(code, amount, BigDecimal::add);
                otherIncome = otherIncome.add(amount);
                return;
            }
            if (CashCategories.REFUND.equals(code)) {
                refunds = refunds.add(amount);
                return;
            }
            addExpense(code, c != null ? c.getScope() : CashCategoryScope.STRATEGIC, amount);
            if (CashCategories.CASH_SHORTAGE.equals(code)) {
                cashShortage = cashShortage.add(amount);
            }
        }

        void addExpense(String code, CashCategoryScope scope, BigDecimal amount) {
            expenseByCategory.merge(code, amount, BigDecimal::add);
            expenseByScope.merge(scope.name(), amount, BigDecimal::add);
            expenses = expenses.add(amount);
        }
    }

    /** Davr bo'yicha tasnif: kassa yozuvlari + kassasiz xarajatlar ({@code expenses.cash_register_id IS NULL}). */
    @Transactional(readOnly = true)
    public Classified classify(LocalDate from, LocalDate to) {
        Map<String, CashCategory> categories = categoryService.all();
        Classified out = new Classified();
        for (CashCategoryTotals.Total t : categoryTotals.totals(null, from, to)) {
            out.add(t.category(), t.link(), t.amount(), categories);
        }
        for (Object[] row : expenseRepository.sumByCategoryWithoutRegister(from, to)) {
            addWithoutRegister(out, (String) row[0], (BigDecimal) row[1], categories);
        }
        return out;
    }

    /** Kunlar bo'yicha tasnif (analitika qatorlari) — {@link #classify} bilan bir xil qoida. */
    @Transactional(readOnly = true)
    public Map<LocalDate, Classified> classifyByDay(LocalDate from, LocalDate to) {
        Map<String, CashCategory> categories = categoryService.all();
        Map<LocalDate, Classified> out = new TreeMap<>();
        for (CashCategoryTotals.Row r : categoryTotals.rows(null, from, to)) {
            out.computeIfAbsent(r.date(), d -> new Classified()).add(r.category(), r.link(), r.amount(), categories);
        }
        for (Object[] row : expenseRepository.sumByDateWithoutRegister(from, to)) {
            Classified c = out.computeIfAbsent((LocalDate) row[0], d -> new Classified());
            CashCategory other = categories.get(CashCategories.OTHER);
            c.addExpense(CashCategories.OTHER, other != null ? other.getScope() : CashCategoryScope.STRATEGIC,
                (BigDecimal) row[1]);
        }
        return out;
    }

    private static void addWithoutRegister(Classified out, String code, BigDecimal amount,
                                           Map<String, CashCategory> categories) {
        if (amount == null || amount.signum() == 0) {
            return;
        }
        CashCategory c = categories.get(code);
        out.addExpense(c != null ? c.getCode() : (code != null ? code : CashCategories.OTHER),
            c != null ? c.getScope() : CashCategoryScope.STRATEGIC, amount);
    }

    /**
     * {@code netProfit = totalIncome + examFees + otherIncome + bookSales − totalExpenses − payrollPaid − refunds
     * − bookPurchases}
     * (cash-and-books §1.3). Kassaga bog'liq oqim — {@code cash_transactions.category} dan; o'quvchi to'lovi
     * — payments, imtihon — examFees, oylik — payroll (ularning kassa yozuvi ikki marta sanalmaydi).
     */
    @Transactional(readOnly = true)
    public FinanceReportResponse getFinanceReport(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();

        BigDecimal totalIncome = Optional.ofNullable(
            paymentRepository.sumCashAmountByDateRange(start, end)
        ).orElse(BigDecimal.ZERO);

        Classified cash = classify(start, end);

        // Income by category: o'quvchi to'lovlari (income jadvali) + kassadagi boshqa kirim kategoriyalari
        Map<String, BigDecimal> incomeByCategory = new LinkedHashMap<>();
        incomeRepository.sumByCategory(start, end).forEach(row ->
            incomeByCategory.put(row[0].toString(), (BigDecimal) row[1]));
        cash.incomeByCategory.forEach((k, v) -> incomeByCategory.merge(k, v, BigDecimal::add));

        // Oylik (payroll-v2 §11 #8): PAID, paidAt ∈ [start, end]; CANCELLED kirmaydi.
        // Oylikning kassa chiqimi (payroll_id) xarajatlarga kirmaydi — ikki marta ayirilmaydi.
        Map<String, BigDecimal> payrollByRole = new LinkedHashMap<>();
        BigDecimal payrollPaid = BigDecimal.ZERO;
        for (Object[] row : payrollRepository.sumPaidByRole(start.atStartOfDay(), end.plusDays(1).atStartOfDay())) {
            String role = row[0] != null ? row[0].toString() : "OTHER";
            BigDecimal sum = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            payrollByRole.merge(role, sum, BigDecimal::add);
            payrollPaid = payrollPaid.add(sum);
        }

        // Imtihon to'lovlari (leaves-exams-contracts §4.2): o'quvchi to'lovi emas — totalIncome ga kirmaydi,
        // lekin markaz daromadi: netProfit ga qo'shiladi. Kirim − bekor qilinganlarning REVERSAL i.
        BigDecimal examFees = Optional.ofNullable(cashTransactionRepository.sumExamFees(start, end))
            .orElse(BigDecimal.ZERO);

        // Kassa oqimi to'lov usuli guruhlari bo'yicha (hamma kassalar birga)
        Map<PaymentChannel, CashChannelService.Flow> flows = cashChannelService.flows(null, start, end);
        Map<String, BigDecimal> incomeByMethod = new LinkedHashMap<>();
        Map<String, BigDecimal> expenseByMethod = new LinkedHashMap<>();
        for (PaymentChannel c : PaymentChannel.values()) {
            incomeByMethod.put(c.name(), flows.get(c).netIncome());
            expenseByMethod.put(c.name(), flows.get(c).netExpense());
        }

        return FinanceReportResponse.builder()
            .totalIncome(totalIncome)
            .examFees(examFees)
            .otherIncome(cash.otherIncome)
            .totalExpenses(cash.expenses)
            .refunds(cash.refunds)
            .cashShortage(cash.cashShortage)
            .payrollPaid(payrollPaid)
            .payrollByRole(payrollByRole)
            .bookSales(cash.bookSales)
            .bookSalesByKind(categoryTotals.bookSaleKinds(null, start, end, false))
            .bookPurchases(cash.bookPurchases)
            .netProfit(totalIncome.add(examFees).add(cash.otherIncome).add(cash.bookSales)
                .subtract(cash.expenses).subtract(payrollPaid).subtract(cash.refunds).subtract(cash.bookPurchases))
            .incomeByCategory(incomeByCategory)
            .expenseByCategory(cash.expenseByCategory)
            .expenseByScope(cash.expenseByScope)
            .incomeByMethod(incomeByMethod)
            .expenseByMethod(expenseByMethod)
            .cashFlowByMethod(CashChannelService.toDtos(flows))
            .period(start + " to " + end)
            .build();
    }

    private ExpenseResponse toResponse(Expense e, Map<String, CashCategory> categories) {
        CashCategory c = categories.get(e.getCategory());
        return ExpenseResponse.builder()
            .id(e.getId())
            .uuid(e.getUuid())
            .category(e.getCategory())
            .categoryName(c != null ? c.getName() : e.getCategory())
            .title(e.getTitle())
            .amount(e.getAmount())
            .expenseDate(e.getExpenseDate())
            .teacherName(e.getTeacher() != null
                ? e.getTeacher().getFirstName() + " " + e.getTeacher().getLastName() : null)
            .description(e.getDescription())
            .createdAt(e.getCreatedAt())
            .cashRegisterId(e.getCashRegister() != null ? e.getCashRegister().getId() : null)
            .cashRegisterName(e.getCashRegister() != null ? e.getCashRegister().getName() : null)
            .build();
    }
}

package com.crm.report;

import com.crm.billing.AccrualService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.books.BookCatalogService;
import com.crm.books.BookDtos.AdjustmentRequest;
import com.crm.books.BookDtos.BookDto;
import com.crm.books.BookDtos.BookRequest;
import com.crm.books.BookDtos.CostLineRequest;
import com.crm.books.BookDtos.CostsRequest;
import com.crm.books.BookDtos.EligibilityDto;
import com.crm.books.BookDtos.GiftRequest;
import com.crm.books.BookDtos.LevelRequest;
import com.crm.books.BookDtos.ReceiptDto;
import com.crm.books.BookDtos.ReceiptLineRequest;
import com.crm.books.BookDtos.ReceiptRequest;
import com.crm.books.BookDtos.SaleDto;
import com.crm.books.BookDtos.SaleLineRequest;
import com.crm.books.BookDtos.SaleRequest;
import com.crm.books.BookDtos.SalesReport;
import com.crm.books.BookDtos.SupplierPaymentRequest;
import com.crm.books.BookDtos.SupplierRequest;
import com.crm.books.BookGiftService;
import com.crm.books.BookReceiptService;
import com.crm.books.BookReportService;
import com.crm.books.BookSaleService;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.FinanceReportResponse;
import com.crm.entity.Attendance;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.BookItemRepository;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.StudentRepository;
import com.crm.service.CashRegisterService;
import com.crm.service.FinanceService;
import com.crm.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kitoblar ombori — 2-bosqich (docs/design/cash-and-books.md §2, V87): ombor minus himoyasi va parallel sotuv,
 * bepul kitob shartlari, tannarx yashirilishi, Direktor kassasidan to'lov, kam qolgan bildirishnomasi, hisobotlar.
 */
class BookInventoryTest extends AbstractBillingIT {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);       // AbstractBillingIT soati (seshanba)

    @Autowired BookCatalogService catalog;
    @Autowired BookReceiptService receipts;
    @Autowired BookSaleService sales;
    @Autowired BookGiftService gifts;
    @Autowired BookReportService reports;
    @Autowired CashRegisterService cash;
    @Autowired FinanceService finance;
    @Autowired PaymentService payments;
    @Autowired AccrualService accrual;
    @Autowired BookItemRepository itemRepo;
    @Autowired CashRegisterRepository registerRepo;
    @Autowired AttendanceRepository attendanceRepo;
    @Autowired StudentRepository studentRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired GroupScheduleDayRepository scheduleRepo;
    @Autowired JdbcTemplate jdbc;

    private Long reg;          // admin kassasi (kassa-adm1)
    private Long otherReg;
    private Long dir;
    private Long levelA1;
    private Long study;        // Turk A1 darsligi, narx 120 000
    private Long fiction;      // badiiy, narx 50 000
    private Long supplier;

    @BeforeEach
    void setUp() {
        reg = fixtures.bareRegister(false);
        otherReg = fixtures.bareRegister(false);
        dir = fixtures.directorRegister();
        fixtures.member(reg, fixtures.user(UserRole.ADMIN, "kitob-adm1"));
        fixtures.member(otherReg, fixtures.user(UserRole.ADMIN, "kitob-adm2"));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        levelA1 = catalog.createLevel(new LevelRequest("Turk", "a1", null, 1, true, null)).id();
        study = catalog.create(new BookRequest("STUDY", "Turk tili A1", null, levelA1, BigDecimal.valueOf(120_000),
            null, true)).id();
        fiction = catalog.create(new BookRequest("FICTION", "Kichik shahzoda", "Ekzyuperi", null,
            BigDecimal.valueOf(50_000), null, true)).id();
        catalog.updateLevel(levelA1, new LevelRequest(null, null, null, null, null, study));
        supplier = receipts.createSupplier(new SupplierRequest("Kitob uyi", "+998901234567", null, true)).id();
    }

    private ReceiptDto receive(Long book, int qty, BigDecimal unitCost) {
        return receipts.create(new ReceiptRequest(supplier, null, null,
            List.of(new ReceiptLineRequest(book, qty, unitCost))));
    }

    private SaleDto sell(Long register, String key, Object... bookQty) {
        List<SaleLineRequest> lines = new ArrayList<>();
        for (int i = 0; i < bookQty.length; i += 2) {
            lines.add(new SaleLineRequest((Long) bookQty[i], (Integer) bookQty[i + 1]));
        }
        return sales.create(new SaleRequest(null, "Tashqi xaridor", "+998901112233", register, PaymentMethod.CASH,
            null, null, lines, null), key);
    }

    private int stock(Long book) {
        return inTx(() -> itemRepo.findById(book).orElseThrow().getStockQty());
    }

    private static RequestPostProcessor as(String username, String role) {
        return user(username).roles(role);
    }

    /** O'quvchi + yozilma (guruh darajasi A1), oylik 700 000; to'lov {@code paid} — bugun (kunning soati). */
    private Long[] newStudentPaying(long paid, boolean withLevel) {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        if (withLevel) {
            jdbc.update("UPDATE groups SET level_id = ? WHERE id = ?", levelA1, group);
        }
        Long sg = fixtures.enrollment(student, group).start(DAY).save();
        accrual.accrueUpTo(sg, DAY);
        if (paid > 0) {
            fixtures.loginAs(UserRole.SUPER_ADMIN);
            PaymentRequest r = new PaymentRequest();
            r.setStudentId(student);
            r.setGroupId(group);
            r.setAmount(BigDecimal.valueOf(paid));
            r.setCashRegisterId(reg);
            r.setPaymentMethod(PaymentMethod.CASH);
            payments.createPayment(r, null);
        }
        return new Long[]{student, group, sg};
    }

    private void attended(Long student, Long group, LocalDate date) {
        inTx(() -> attendanceRepo.save(Attendance.builder()
            .student(studentRepo.findById(student).orElseThrow())
            .group(groupRepo.findById(group).orElseThrow())
            .attendanceDate(date)
            .status(AttendanceStatus.PRESENT)
            .build()));
    }

    // ── 1. Ombor: minus himoyasi, parallel sotuv, kirim, tuzatish ───────

    @Test
    void stock_neverNegative_parallelSalesOfLastCopy_onlyOneWins() throws Exception {
        receive(fiction, 1, BigDecimal.valueOf(30_000));
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        assertThatThrownBy(() -> sell(reg, null, fiction, 2))
            .isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo("book.stock.insufficient"));
        assertThat(stock(fiction)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions", Long.class)).isZero();

        // Ikki sotuvchi bir vaqtda oxirgi nusxani sotadi — bittasi o'tadi, ikkinchisi 409
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> seller = () -> {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "kitob-adm1", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            start.await();
            try {
                sell(reg, null, fiction, 1);
                return "OK";
            } catch (ConflictException e) {
                return e.getCode();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        List<Future<String>> results = List.of(pool.submit(seller), pool.submit(seller));
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "book.stock.insufficient");
        assertThat(stock(fiction)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_sales", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT MIN(qty_after) FROM book_stock_movements WHERE book_item_id = ?",
            Integer.class, fiction)).isZero();
    }

    @Test
    void receipt_adminQtyOnly_stockUpImmediately_costBySaOnly_openingBalanceAdjustment() throws Exception {
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        assertThatThrownBy(() -> receive(study, 10, BigDecimal.valueOf(60_000)))
            .isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.getCode()).isEqualTo("book.cost.directorOnly"));
        ReceiptDto r = receive(study, 10, null);
        assertThat(stock(study)).isEqualTo(10);
        assertThat(r.totalCost()).isNull();
        assertThat(r.lines().get(0).unitCost()).isNull();

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        Long lineId = receipts.get(r.id()).lines().get(0).id();
        ReceiptDto costed = receipts.setCosts(r.id(), new CostsRequest(List.of(new CostLineRequest(lineId,
            BigDecimal.valueOf(60_000)))));
        assertThat(costed.totalCost()).isEqualByComparingTo("600000");
        assertThat(costed.status().name()).isEqualTo("UNPAID");
        assertThat(catalog.get(study).avgCost()).isEqualByComparingTo("60000");

        // Boshlang'ich qoldiq — ADJUSTMENT (soni + tannarx), sabab majburiy; o'rtacha tannarx tortiladi
        assertThatThrownBy(() -> catalog.adjust(new AdjustmentRequest(study, 5, BigDecimal.valueOf(90_000), " ")))
            .isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.getCode()).isEqualTo("book.adjust.reasonRequired"));
        catalog.adjust(new AdjustmentRequest(study, 5, BigDecimal.valueOf(90_000), "Boshlang'ich qoldiq"));
        assertThat(stock(study)).isEqualTo(15);
        assertThat(catalog.get(study).avgCost()).isEqualByComparingTo("70000");     // (10×60k + 5×90k) / 15
        assertThatThrownBy(() -> catalog.adjust(new AdjustmentRequest(study, -16, null, "Hisobdan chiqarish")))
            .isInstanceOf(ConflictException.class);
        // Tuzatish — faqat SA
        mvc.perform(post("/api/books/adjustments").with(as("kitob-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookItemId\":" + study + ",\"qtyDelta\":1,\"reason\":\"test\"}"))
            .andExpect(status().isForbidden());
    }

    // ── 2. Tannarx yashirilishi ─────────────────────────────────────────

    @Test
    void cost_hiddenFromNonDirector_inApi() throws Exception {
        receive(study, 4, BigDecimal.valueOf(60_000));
        mvc.perform(get("/api/books/" + study).with(as("kitob-adm1", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stockQty").value(4))
            .andExpect(jsonPath("$.data.avgCost").doesNotExist())
            .andExpect(jsonPath("$.data.stockValue").doesNotExist());
        mvc.perform(get("/api/books/" + study).with(as("test-super_admin", "SUPER_ADMIN")))
            .andExpect(jsonPath("$.data.avgCost").value(60000));
        mvc.perform(get("/api/books/" + study + "/movements").with(as("kitob-adm1", "ADMIN")))
            .andExpect(jsonPath("$.data.content[0].unitCost").doesNotExist());
        mvc.perform(get("/api/books/reports/supplier-debts").with(as("kitob-adm1", "ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/books/receipts/1/payments").with(as("kitob-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/books").with(as("kitob-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"FICTION\",\"title\":\"X\",\"salePrice\":1}"))
            .andExpect(status().isForbidden());

        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        SaleDto sale = sell(reg, null, study, 1);
        mvc.perform(get("/api/books/sales/" + sale.id()).with(as("kitob-adm1", "ADMIN")))
            .andExpect(jsonPath("$.data.lines[0].unitPrice").value(120000))
            .andExpect(jsonPath("$.data.lines[0].unitCost").doesNotExist());
        // mvc.perform so'rov oxirida test oqimidagi SecurityContext ni tozalaydi — servis chaqiruvidan oldin qayta kirish
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        SalesReport adminReport = reports.sales(DAY.minusDays(30), DAY.plusDays(30), "ITEM");
        assertThat(adminReport.total().revenue()).isEqualByComparingTo("120000");
        assertThat(adminReport.total().cost()).isNull();
        assertThat(adminReport.total().margin()).isNull();
    }

    // ── 3. Yetkazib beruvchiga to'lov — faqat SA, Direktor kassasidan ───

    @Test
    void supplierPayment_directorRegisterOnly_partial_thenPaid_costPending409() {
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        ReceiptDto pending = receive(fiction, 10, null);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        SupplierPaymentRequest pay = new SupplierPaymentRequest(BigDecimal.valueOf(100_000), dir, PaymentMethod.CASH,
            null, null, null);
        assertThatThrownBy(() -> receipts.pay(pending.id(), pay, null))
            .isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo("book.receipt.costPending"));

        ReceiptDto r = receive(fiction, 10, BigDecimal.valueOf(30_000));           // 300 000
        assertThatThrownBy(() -> receipts.pay(r.id(), new SupplierPaymentRequest(BigDecimal.valueOf(100_000), reg,
            PaymentMethod.CASH, null, null, null), null))
            .isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.getCode()).isEqualTo("cash.directorRegisterRequired"));
        assertThat(receipts.pay(r.id(), pay, "sup-1").idempotentReplay()).isFalse();
        assertThat(receipts.pay(r.id(), pay, "sup-1").idempotentReplay()).isTrue();      // takror — ikkinchi chiqim yo'q
        assertThat(receipts.get(r.id()).status().name()).isEqualTo("PARTIAL");
        assertThatThrownBy(() -> receipts.pay(r.id(), new SupplierPaymentRequest(BigDecimal.valueOf(250_000), dir,
            PaymentMethod.CASH, null, null, null), null))
            .isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.getCode()).isEqualTo("book.payment.exceedsDebt"));
        receipts.pay(r.id(), new SupplierPaymentRequest(BigDecimal.valueOf(200_000), dir, PaymentMethod.CARD,
            null, null, null), null);
        assertThat(receipts.get(r.id()).status().name()).isEqualTo("PAID");
        assertThat(inTx(() -> registerRepo.findById(dir).orElseThrow().getBalance())).isEqualByComparingTo("-300000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions WHERE category = 'BOOK_PURCHASE' "
            + "AND book_supplier_payment_id IS NOT NULL", Long.class)).isEqualTo(2L);
        assertThat(receipts.debts()).singleElement().satisfies(d -> {
            assertThat(d.debt()).isEqualByComparingTo("0");
            assertThat(d.costPendingReceipts()).isEqualTo(1L);
        });
    }

    // ── 4. Sotuv: savat, o'z kassasi, idempotentlik, hisobotlar ─────────

    @Test
    void sale_cartMixed_ownRegisterOnly_idempotent_reportsAndFinance() {
        receive(study, 5, BigDecimal.valueOf(60_000));
        receive(fiction, 5, BigDecimal.valueOf(30_000));
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        assertThatThrownBy(() -> sell(otherReg, null, fiction, 1)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> sell(dir, null, fiction, 1)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> sales.create(new SaleRequest(null, " ", null, reg, PaymentMethod.CASH, null, null,
            List.of(new SaleLineRequest(fiction, 1)), null), null))
            .isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.getCode()).isEqualTo("book.sale.buyerRequired"));
        assertThatThrownBy(() -> sales.create(new SaleRequest(null, "X", null, reg, PaymentMethod.CASH, null, null,
            List.of(new SaleLineRequest(fiction, 1)), BigDecimal.valueOf(1)), null))
            .isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo("book.sale.priceChanged"));

        SaleDto s = sell(reg, "sale-1", study, 2, fiction, 1, study, 1);        // savat: 3 darslik + 1 badiiy
        assertThat(s.totalAmount()).isEqualByComparingTo("410000");
        assertThat(s.lines()).hasSize(2);
        assertThat(sell(reg, "sale-1", study, 2).idempotentReplay()).isTrue();
        assertThat(stock(study)).isEqualTo(2);
        assertThat(stock(fiction)).isEqualTo(4);

        // Kassa: bitta INCOME BOOK_SALE; ko'rinishda O'quv / Badiiy alohida
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions WHERE category = 'BOOK_SALE' "
            + "AND book_sale_id = ?", Long.class, s.id())).isEqualTo(1L);
        assertThat(cash.getCategoryReport(reg, null, null))
            .extracting(c -> c.getCategory() + "=" + c.getAmount().stripTrailingZeros().toPlainString())
            .containsExactly("BOOK_SALE_STUDY=360000", "BOOK_SALE_FICTION=50000");

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        SalesReport byKind = reports.sales(DAY.minusDays(1), DAY.plusDays(40), "KIND");
        assertThat(byKind.rows()).extracting(r -> r.key() + ":" + r.qty() + ":"
                + r.revenue().stripTrailingZeros().toPlainString() + ":" + r.cost().stripTrailingZeros().toPlainString())
            .containsExactly("FICTION:1:50000:30000", "STUDY:3:360000:180000");
        assertThat(byKind.total().margin()).isEqualByComparingTo("200000");
        assertThat(reports.sales(DAY.minusDays(1), DAY.plusDays(40), "ITEM").rows()).hasSize(2);

        FinanceReportResponse fin = finance.getFinanceReport(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1));
        assertThat(fin.getBookSales()).isEqualByComparingTo("410000");
        assertThat(fin.getBookSalesByKind().get("STUDY")).isEqualByComparingTo("360000");
        assertThat(fin.getBookSalesByKind().get("FICTION")).isEqualByComparingTo("50000");
        assertThat(fin.getOtherIncome()).isEqualByComparingTo("0");        // kitob sotuvi "boshqa kirim" emas
        assertThat(fin.getNetProfit()).isEqualByComparingTo("410000");
    }

    // ── 5. Kam qolgan bildirishnomasi ───────────────────────────────────

    @Test
    void lowStock_notifiesSaAndAdminBell_oncePerBookPerDay() {
        catalog.update(fiction, new BookRequest(null, null, null, null, null, 3, null));
        receive(fiction, 6, BigDecimal.valueOf(30_000));
        Long sa = fixtures.user(UserRole.SUPER_ADMIN, "test-super_admin");
        Long admin = fixtures.user(UserRole.ADMIN, "kitob-adm1");
        String key = "lowstock:" + fiction + ":" + DAY;
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        sell(reg, null, fiction, 2);                                   // 4 > 3 — xabar yo'q
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE dedupe_key = ?", Long.class, key))
            .isZero();
        sell(reg, null, fiction, 1);                                   // 3 ≤ 3 — SA va ADMIN ga
        sell(reg, null, fiction, 1);                                   // shu kun — takror yo'q
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE dedupe_key = ? AND user_id = ?",
            Long.class, key, sa)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE dedupe_key = ? AND user_id = ?",
            Long.class, key, admin)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT type FROM user_notifications WHERE dedupe_key = ? AND user_id = ?",
            String.class, key, sa)).isEqualTo("BOOK_LOW_STOCK");
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        // Badiiy: 2 ≤ 3; darslik bu testda kirim qilinmagan: 0 ≤ 30 — u ham kam qolgan (tartib: tur, nom)
        assertThat(reports.lowStock()).extracting(BookDto::id).containsExactly(fiction, study);
        assertThat(reports.lowStock()).extracting(BookDto::stockQty).containsExactly(2, 0);
    }

    // ── 6. Bepul kitob ──────────────────────────────────────────────────

    @Test
    void gift_firstVisitDay_fullyPaid_once_notNextDay() {
        receive(study, 3, BigDecimal.valueOf(60_000));
        Long[] s = newStudentPaying(700_000, true);
        attended(s[0], s[1], DAY);
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        EligibilityDto e = gifts.eligibility(s[0], null);
        assertThat(e.eligible()).isTrue();
        assertThat(e.bookItemId()).isEqualTo(study);
        assertThat(e.levelName()).isEqualTo("Turk A1");
        assertThat(e.firstVisitAssumed()).isFalse();

        gifts.give(new GiftRequest(s[0], null));
        assertThat(stock(study)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions WHERE student_id = ? "
            + "AND category <> 'STUDENT_PAYMENT'", Long.class, s[0])).isZero();       // kassaga yozilmaydi
        assertThat(gifts.eligibility(s[0], null).reason()).isEqualTo(BookGiftService.ALREADY_GIVEN);
        assertThatThrownBy(() -> gifts.give(new GiftRequest(s[0], null)))
            .isInstanceOfSatisfying(ConflictException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("book.gift.notEligible"));
        assertThat(stock(study)).isEqualTo(2);

        // Boshqa o'quvchi: bugun shartga mos, lekin ertasi kuni — mumkin emas
        Long[] t = newStudentPaying(700_000, true);
        attended(t[0], t[1], DAY);
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        assertThat(gifts.eligibility(t[0], null).eligible()).isTrue();
        clock.setDate(DAY.plusDays(1));
        assertThat(gifts.eligibility(t[0], null).reason()).isEqualTo(BookGiftService.NOT_FIRST_VISIT_DAY);
    }

    @Test
    void gift_reasons_notFullyPaid_noLevel_noGiftBook_outOfStock_assumedFirstVisitWithoutAttendance() {
        // To'liq to'lanmagan
        Long[] partial = newStudentPaying(300_000, true);
        attended(partial[0], partial[1], DAY);
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        assertThat(gifts.eligibility(partial[0], null).reason()).isEqualTo(BookGiftService.NOT_FULLY_PAID);

        // Guruhda daraja yo'q
        Long[] noLevel = newStudentPaying(700_000, false);
        attended(noLevel[0], noLevel[1], DAY);
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        assertThat(gifts.eligibility(noLevel[0], null).reason()).isEqualTo(BookGiftService.NO_LEVEL);

        // Omborda yo'q
        Long[] noStock = newStudentPaying(700_000, true);
        attended(noStock[0], noStock[1], DAY);
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        assertThat(gifts.eligibility(noStock[0], null).reason()).isEqualTo(BookGiftService.OUT_OF_STOCK);

        // Darajada bepul kitob belgilanmagan
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        catalog.updateLevel(levelA1, new LevelRequest(null, null, null, null, null, 0L));
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        assertThat(gifts.eligibility(noStock[0], null).reason()).isEqualTo(BookGiftService.NO_GIFT_BOOK);

        // Davomat belgilanmagan, lekin bugun guruhida dars bor — birinchi kun deb olinadi
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        catalog.updateLevel(levelA1, new LevelRequest(null, null, null, null, null, study));
        receive(study, 1, BigDecimal.valueOf(60_000));
        Long[] unmarked = newStudentPaying(700_000, true);
        fixtures.loginAs("kitob-adm1", UserRole.ADMIN);
        assertThat(gifts.eligibility(unmarked[0], null).reason()).isEqualTo(BookGiftService.NOT_FIRST_VISIT_DAY);
        inTx(() -> scheduleRepo.save(GroupScheduleDay.builder()
            .group(groupRepo.findById(unmarked[1]).orElseThrow())
            .dayOfWeek(DAY.getDayOfWeek().name())
            .startTime("10:00")
            .endTime("11:30")
            .build()));
        EligibilityDto assumed = gifts.eligibility(unmarked[0], null);
        assertThat(assumed.eligible()).isTrue();
        assertThat(assumed.firstVisitAssumed()).isTrue();
        assertThat(gifts.give(new GiftRequest(unmarked[0], null)).firstVisitAssumed()).isTrue();
    }
}

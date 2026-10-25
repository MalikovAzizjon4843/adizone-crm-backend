package com.crm.books;

import com.crm.entity.enums.BookKind;
import com.crm.entity.enums.BookMovementType;
import com.crm.entity.enums.BookReceiptStatus;
import com.crm.entity.enums.PaymentMethod;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Kitoblar ombori API so'rov va javoblari (docs/design/cash-and-books.md §2.3).
 * <b>Tannarx</b> ({@code avgCost}, {@code unitCost}, {@code totalCost}, {@code cost}, {@code margin} …) faqat
 * SUPER_ADMIN javobida to'ldiriladi; boshqalarda null va {@link JsonInclude.Include#NON_NULL} — JSON da umuman yo'q.
 */
public final class BookDtos {

    private BookDtos() {
    }

    // ── Darajalar ──────────────────────────────────────────────────────

    public record LevelRequest(String language, String code, String name, Integer sortOrder, Boolean active,
                               Long giftBookItemId) {
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record LevelDto(Long id, String language, String code, String name, int sortOrder, boolean active,
                           Long giftBookItemId, String giftBookTitle) {
    }

    // ── Katalog ────────────────────────────────────────────────────────

    public record BookRequest(String kind, String title, String author, Long levelId, BigDecimal salePrice,
                              Integer minStock, Boolean active) {
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record BookDto(Long id, BookKind kind, String title, String author, Long levelId, String levelName,
                          BigDecimal salePrice, int stockQty, int minStock, boolean low, boolean active,
                          BigDecimal avgCost, BigDecimal stockValue) {
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record MovementDto(Long id, Long bookItemId, BookMovementType type, int qtyDelta, int qtyAfter,
                              BigDecimal unitCost, String refType, Long refId, String reason, String createdByName,
                              LocalDateTime createdAt) {
    }

    /** {@code POST /api/books/adjustments} (SA): boshlang'ich qoldiq ham — musbat son + tannarx. */
    public record AdjustmentRequest(Long bookItemId, Integer qtyDelta, BigDecimal unitCost, String reason) {
    }

    // ── Yetkazib beruvchi, kirim, to'lov ───────────────────────────────

    public record SupplierRequest(String name, String phone, String note, Boolean active) {
    }

    public record SupplierDto(Long id, String name, String phone, String note, boolean active) {
    }

    public record ReceiptLineRequest(Long bookItemId, Integer qty, BigDecimal unitCost) {
    }

    /** Admin — faqat son ({@code unitCost} yuborsa 403); SA — son va tannarx. */
    public record ReceiptRequest(Long supplierId, LocalDate receiptDate, String note, List<ReceiptLineRequest> lines) {
    }

    public record CostLineRequest(Long lineId, BigDecimal unitCost) {
    }

    public record CostsRequest(List<CostLineRequest> lines) {
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record ReceiptLineDto(Long id, Long bookItemId, String bookTitle, BookKind kind, int qty,
                                 BigDecimal unitCost, BigDecimal lineCost) {
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record ReceiptDto(Long id, Long supplierId, String supplierName, LocalDate receiptDate,
                             BookReceiptStatus status, String note, String createdByName, LocalDateTime createdAt,
                             List<ReceiptLineDto> lines, BigDecimal totalCost, BigDecimal paidAmount,
                             BigDecimal remaining, List<SupplierPaymentDto> payments) {
    }

    public record SupplierPaymentRequest(BigDecimal amount, Long cashRegisterId, PaymentMethod paymentMethod,
                                         BigDecimal cashPart, BigDecimal cardPart, String note) {
    }

    public record SupplierPaymentDto(Long id, Long receiptId, Long supplierId, BigDecimal amount, Long cashRegisterId,
                                     PaymentMethod paymentMethod, Long cashTransactionId, LocalDate paidAt,
                                     String note, boolean idempotentReplay) {
    }

    public record SupplierDebtDto(Long supplierId, String supplierName, BigDecimal totalCost, BigDecimal paid,
                                  BigDecimal debt, long costPendingReceipts) {
    }

    // ── Sotuv ──────────────────────────────────────────────────────────

    public record SaleLineRequest(Long bookItemId, Integer qty) {
    }

    /**
     * Narx so'rovda YO'Q — server {@code sale_price} ni oladi; {@code expectedTotal} (UI dagi jami) mos kelmasa — 409.
     * O'quvchi ({@code studentId}) yoki tashqi xaridor ({@code buyerName}, {@code buyerPhone}).
     */
    public record SaleRequest(Long studentId, String buyerName, String buyerPhone, Long cashRegisterId,
                              PaymentMethod paymentMethod, BigDecimal cashPart, BigDecimal cardPart,
                              List<SaleLineRequest> lines, BigDecimal expectedTotal) {
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record SaleLineDto(Long bookItemId, String bookTitle, BookKind kind, int qty, BigDecimal unitPrice,
                              BigDecimal lineTotal, BigDecimal unitCost) {
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record SaleDto(Long id, String receiptNumber, Long studentId, String studentName, String buyerName,
                          String buyerPhone, BigDecimal totalAmount, Long cashRegisterId, String cashRegisterName,
                          PaymentMethod paymentMethod, Long cashTransactionId, LocalDate saleDate,
                          String createdByName, LocalDateTime createdAt, List<SaleLineDto> lines,
                          Boolean idempotentReplay) {
    }

    // ── Bepul kitob ────────────────────────────────────────────────────

    public record GiftRequest(Long studentId, Long studentGroupId) {
    }

    /**
     * {@code reason}: ALREADY_GIVEN, NOT_FIRST_VISIT_DAY, NOT_FULLY_PAID, NO_LEVEL, NO_GIFT_BOOK, OUT_OF_STOCK;
     * eligible bo'lsa null.
     */
    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record EligibilityDto(Long studentId, boolean eligible, String reason, LocalDate firstVisitDate,
                                 Boolean firstVisitAssumed, Long studentGroupId, String groupName, Long levelId,
                                 String levelName, Long bookItemId, String bookTitle, Integer stockQty) {
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record GiftDto(Long id, Long studentId, String studentName, Long studentGroupId, Long levelId,
                          String levelName, Long bookItemId, String bookTitle, LocalDate firstVisitDate,
                          boolean firstVisitAssumed, LocalDate giftDate, String createdByName,
                          BigDecimal unitCost) {
    }

    // ── Hisobotlar ─────────────────────────────────────────────────────

    /** {@code key}: kitob id / tur / sana ({@code groupBy} bo'yicha). {@code cost}, {@code margin} — faqat SA. */
    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record SalesRow(String key, String label, long qty, BigDecimal revenue, BigDecimal cost,
                           BigDecimal margin, Boolean costIncomplete) {
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record SalesReport(LocalDate from, LocalDate to, String groupBy, List<SalesRow> rows, SalesRow total,
                              Long giftsCount, BigDecimal giftsCost) {
    }
}

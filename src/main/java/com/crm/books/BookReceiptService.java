package com.crm.books;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.billing.BillingLocks;
import com.crm.billing.BillingStatusService;
import com.crm.billing.PaymentDatePolicy;
import com.crm.books.BookDtos.CostLineRequest;
import com.crm.books.BookDtos.CostsRequest;
import com.crm.books.BookDtos.ReceiptDto;
import com.crm.books.BookDtos.ReceiptLineDto;
import com.crm.books.BookDtos.ReceiptLineRequest;
import com.crm.books.BookDtos.ReceiptRequest;
import com.crm.books.BookDtos.SupplierDebtDto;
import com.crm.books.BookDtos.SupplierDto;
import com.crm.books.BookDtos.SupplierPaymentDto;
import com.crm.books.BookDtos.SupplierPaymentRequest;
import com.crm.books.BookDtos.SupplierRequest;
import com.crm.entity.BookItem;
import com.crm.entity.BookReceipt;
import com.crm.entity.BookReceiptLine;
import com.crm.entity.BookSupplier;
import com.crm.entity.BookSupplierPayment;
import com.crm.entity.CashRegister;
import com.crm.entity.CashTransaction;
import com.crm.entity.enums.BookMovementType;
import com.crm.entity.enums.BookReceiptStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.BookItemRepository;
import com.crm.repository.BookReceiptLineRepository;
import com.crm.repository.BookReceiptRepository;
import com.crm.repository.BookSupplierPaymentRepository;
import com.crm.repository.BookSupplierRepository;
import com.crm.repository.UserRepository;
import com.crm.service.CashAccessService;
import com.crm.service.CashCategories;
import com.crm.service.CashRegisterService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Yetkazib beruvchilar, kitob kirimi va yetkazib beruvchiga to'lov (docs/design/cash-and-books.md §2.2):
 * <ul>
 *   <li>kirim — SA, ADMIN: admin faqat sonini kiritadi (tannarx yuborsa 403), ombor DARHOL ko'payadi;</li>
 *   <li>tannarx — faqat SA ({@code COST_PENDING → UNPAID}); to'lov bo'lguncha o'zgartirsa bo'ladi;</li>
 *   <li>to'lov — faqat SA, faqat Direktor kassasidan, kitob kelgandan (kirimdan) keyin, qisman ham;
 *       kassa EXPENSE {@code BOOK_PURCHASE}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BookReceiptService {

    private static final int KEY_MAX = 100;

    private final BookSupplierRepository supplierRepository;
    private final BookReceiptRepository receiptRepository;
    private final BookReceiptLineRepository lineRepository;
    private final BookSupplierPaymentRepository paymentRepository;
    private final BookItemRepository itemRepository;
    private final UserRepository userRepository;
    private final BookStockService stock;
    private final CashAccessService access;
    private final CashRegisterService cashRegisterService;
    private final BillingLocks locks;
    private final BillingStatusService statusService;
    private final PaymentDatePolicy datePolicy;

    // ── Yetkazib beruvchilar ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SupplierDto> suppliers() {
        return supplierRepository.findAllByOrderByNameAsc().stream().map(BookReceiptService::toDto).toList();
    }

    @Transactional
    public SupplierDto createSupplier(SupplierRequest r) {
        access.requireDirector();
        BookSupplier s = new BookSupplier();
        applySupplier(s, r, true);
        return toDto(supplierRepository.save(s));
    }

    @Transactional
    public SupplierDto updateSupplier(Long id, SupplierRequest r) {
        access.requireDirector();
        BookSupplier s = supplierRepository.findById(id)
            .orElseThrow(() -> CodedException.notFound("book.supplier.notFound", id));
        applySupplier(s, r, false);
        return toDto(supplierRepository.save(s));
    }

    private void applySupplier(BookSupplier s, SupplierRequest r, boolean create) {
        String name = BookCatalogService.trim(r.name());
        if (create || name != null) {
            if (name == null || name.length() > 150) {
                throw CodedException.badRequest("book.supplier.nameRequired");
            }
            boolean duplicate = supplierRepository.findByNameIgnoreCase(name).stream()
                .anyMatch(o -> !o.getId().equals(s.getId()));
            if (duplicate) {
                throw new ConflictException("book.supplier.duplicate", name);
            }
            s.setName(name);
        }
        if (r.phone() != null) {
            s.setPhone(BookCatalogService.trim(r.phone()));
        }
        if (r.note() != null) {
            s.setNote(BookCatalogService.trim(r.note()));
        }
        if (r.active() != null) {
            s.setActive(r.active());
        }
    }

    /** SA: yetkazib beruvchi qarzi = Σ tannarx (to'ldirilgan kirimlar) − Σ to'langan. */
    @Transactional(readOnly = true)
    public List<SupplierDebtDto> debts() {
        access.requireDirector();
        List<SupplierDebtDto> out = new ArrayList<>();
        for (BookSupplier s : supplierRepository.findAllByOrderByNameAsc()) {
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal paid = BigDecimal.ZERO;
            long pending = 0;
            for (BookReceipt r : receiptRepository.findBySupplierId(s.getId())) {
                if (r.getStatus() == BookReceiptStatus.COST_PENDING) {
                    pending++;
                    continue;
                }
                total = total.add(nz(r.getTotalCost()));
                paid = paid.add(nz(r.getPaidAmount()));
            }
            out.add(new SupplierDebtDto(s.getId(), s.getName(), total, paid, total.subtract(paid), pending));
        }
        return out;
    }

    // ── Kirim ──────────────────────────────────────────────────────────

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "BookReceipt",
        summary = "'Kitob kirimi: ' + #result.supplierName() + ', ' + #result.lines().size() + ' qator'",
        entityId = "#result.id()")
    public ReceiptDto create(ReceiptRequest r) {
        boolean director = access.isUnrestricted();
        List<ReceiptLineRequest> lines = r.lines() != null ? r.lines() : List.of();
        if (lines.isEmpty()) {
            throw CodedException.badRequest("book.receipt.linesRequired");
        }
        for (ReceiptLineRequest l : lines) {
            if (l.bookItemId() == null || l.qty() == null || l.qty() <= 0) {
                throw CodedException.badRequest("book.receipt.qtyInvalid");
            }
            if (l.unitCost() != null) {
                if (!director) {
                    throw CodedException.forbidden("book.cost.directorOnly");
                }
                if (l.unitCost().signum() < 0) {
                    throw CodedException.badRequest("book.receipt.costInvalid");
                }
            }
        }
        BookSupplier supplier = Optional.ofNullable(r.supplierId()).flatMap(supplierRepository::findById)
            .filter(BookSupplier::isActive)
            .orElseThrow(() -> CodedException.notFound("book.supplier.notFound", r.supplierId()));
        LocalDate date = r.receiptDate() != null ? datePolicy.check(r.receiptDate()) : statusService.today();

        Map<Long, BookItem> items = stock.lockItems(lines.stream().map(ReceiptLineRequest::bookItemId).toList());
        boolean allCosted = lines.stream().allMatch(l -> l.unitCost() != null);
        BookReceipt receipt = receiptRepository.save(BookReceipt.builder()
            .supplierId(supplier.getId())
            .receiptDate(date)
            .status(allCosted ? BookReceiptStatus.UNPAID : BookReceiptStatus.COST_PENDING)
            .totalCost(allCosted ? lines.stream().map(l -> l.unitCost().multiply(BigDecimal.valueOf(l.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add) : null)
            .paidAmount(BigDecimal.ZERO)
            .note(BookCatalogService.trim(r.note()))
            .createdBy(access.currentUserId())
            .costFilledBy(allCosted ? access.currentUserId() : null)
            .costFilledAt(allCosted ? LocalDateTime.now() : null)
            .build());
        for (ReceiptLineRequest l : lines) {
            BookItem item = items.get(l.bookItemId());
            BookReceiptLine line = lineRepository.save(BookReceiptLine.builder()
                .receiptId(receipt.getId())
                .bookItemId(item.getId())
                .qty(l.qty())
                .unitCost(l.unitCost())
                .build());
            stock.move(item, BookMovementType.RECEIPT, l.qty(), l.unitCost(), "RECEIPT_LINE", line.getId(), null);
            stock.applyCost(item, l.qty(), l.unitCost());
        }
        return toDto(receipt, director);
    }

    /** SA: qator tannarxlari; hammasi to'ldirilishi shart. To'lov bo'lgan kirimda — 409. */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "BookReceipt",
        summary = "'Kirim tannarxi: ' + #result.totalCost()", entityId = "#id")
    public ReceiptDto setCosts(Long id, CostsRequest r) {
        access.requireDirector();
        BookReceipt receipt = locks.lock(BookReceipt.class, id);
        if (nz(receipt.getPaidAmount()).signum() > 0) {
            throw new ConflictException("book.receipt.costLocked");
        }
        List<BookReceiptLine> lines = lineRepository.findByReceiptIdOrderByIdAsc(id);
        Map<Long, BigDecimal> requested = new LinkedHashMap<>();
        for (CostLineRequest c : r.lines() != null ? r.lines() : List.<CostLineRequest>of()) {
            if (c.lineId() == null || c.unitCost() == null || c.unitCost().signum() < 0) {
                throw CodedException.badRequest("book.receipt.costInvalid");
            }
            requested.put(c.lineId(), c.unitCost());
        }
        if (!lines.stream().map(BookReceiptLine::getId).toList().containsAll(requested.keySet())) {
            throw CodedException.badRequest("book.receipt.costInvalid");
        }
        Map<Long, BookItem> items = stock.lockItems(lines.stream().map(BookReceiptLine::getBookItemId).toList());
        BigDecimal total = BigDecimal.ZERO;
        for (BookReceiptLine line : lines) {
            BigDecimal next = requested.getOrDefault(line.getId(), line.getUnitCost());
            if (next == null) {
                throw CodedException.badRequest("book.receipt.costMissing", line.getId());
            }
            if (line.getUnitCost() == null || line.getUnitCost().compareTo(next) != 0) {
                stock.reCost(items.get(line.getBookItemId()), line.getQty(), line.getUnitCost(), next);
                line.setUnitCost(next);
                lineRepository.save(line);
            }
            total = total.add(next.multiply(BigDecimal.valueOf(line.getQty())));
        }
        receipt.setTotalCost(total);
        receipt.setStatus(BookReceiptStatus.UNPAID);
        receipt.setCostFilledBy(access.currentUserId());
        receipt.setCostFilledAt(LocalDateTime.now());
        return toDto(receiptRepository.save(receipt), true);
    }

    /** SA — hammasi; ADMIN — o'zi kiritganlari (tannarxsiz). */
    @Transactional(readOnly = true)
    public Page<ReceiptDto> list(Long supplierId, String status, int page, int size) {
        boolean director = access.isUnrestricted();
        Specification<BookReceipt> spec = Specification.where(null);
        if (supplierId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("supplierId"), supplierId));
        }
        if (status != null && !status.isBlank()) {
            BookReceiptStatus st = parseStatus(status);
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), st));
        }
        if (!director) {
            Long me = access.currentUserId();
            spec = spec.and((root, q, cb) -> me == null ? cb.disjunction() : cb.equal(root.get("createdBy"), me));
        }
        return receiptRepository.findAll(spec, PageRequest.of(page, Math.min(Math.max(size, 1), 200),
            Sort.by(Sort.Direction.DESC, "receiptDate", "id"))).map(x -> toDto(x, director));
    }

    @Transactional(readOnly = true)
    public ReceiptDto get(Long id) {
        BookReceipt receipt = receiptRepository.findById(id)
            .orElseThrow(() -> CodedException.notFound("book.receipt.notFound", id));
        boolean director = access.isUnrestricted();
        if (!director && !receipt.getCreatedBy().equals(access.currentUserId())) {
            throw CodedException.notFound("book.receipt.notFound", id);
        }
        return toDto(receipt, director);
    }

    // ── Yetkazib beruvchiga to'lov (SA, Direktor kassasi) ──────────────

    @Transactional
    @Audited(action = AuditAction.PAYMENT, entity = "BookReceipt",
        summary = "'Yetkazib beruvchiga to''lov: ' + #result.amount()", entityId = "#id")
    public SupplierPaymentDto pay(Long id, SupplierPaymentRequest r, String idempotencyKey) {
        access.requireDirector();
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            Optional<BookSupplierPayment> existing = paymentRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), id);
            }
        }
        if (r.amount() == null || r.amount().signum() <= 0) {
            throw CodedException.badRequest("book.payment.amountInvalid");
        }
        if (r.cashRegisterId() == null) {
            throw CodedException.badRequest("payment.cashRegister.required");
        }
        BookReceipt receipt = locks.lock(BookReceipt.class, id);
        if (key != null) {
            Optional<BookSupplierPayment> existing = paymentRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), id);
            }
        }
        if (receipt.getStatus() == BookReceiptStatus.COST_PENDING) {
            throw new ConflictException("book.receipt.costPending");
        }
        BigDecimal remaining = nz(receipt.getTotalCost()).subtract(nz(receipt.getPaidAmount()));
        if (r.amount().compareTo(remaining) > 0) {
            throw CodedException.badRequest("book.payment.exceedsDebt", remaining.stripTrailingZeros().toPlainString());
        }
        CashRegister register = access.requireAccessible(r.cashRegisterId());
        access.requireDirectorRegister(register);
        BookSupplier supplier = supplierRepository.findById(receipt.getSupplierId()).orElseThrow();
        PaymentMethod method = r.paymentMethod() != null ? r.paymentMethod() : PaymentMethod.CASH;
        LocalDate today = statusService.today();

        BookSupplierPayment payment = paymentRepository.save(BookSupplierPayment.builder()
            .receiptId(receipt.getId())
            .supplierId(supplier.getId())
            .amount(r.amount())
            .cashRegisterId(register.getId())
            .paymentMethod(method)
            .cashPart(r.cashPart())
            .cardPart(r.cardPart())
            .paidAt(today)
            .note(BookCatalogService.trim(r.note()))
            .idempotencyKey(key)
            .createdBy(access.currentUserId())
            .build());
        CashTransaction tx = cashRegisterService.recordExpense(register.getId(), r.amount(), method,
            "Kitob xaridi: " + supplier.getName(), "Kirim #" + receipt.getId() + " (" + receipt.getReceiptDate() + ")",
            today, null, null, null, null, null, r.cashPart(), r.cardPart(), null, CashCategories.BOOK_PURCHASE);
        tx.setBookSupplierPaymentId(payment.getId());
        payment.setCashTransactionId(tx.getId());
        paymentRepository.save(payment);

        receipt.setPaidAmount(nz(receipt.getPaidAmount()).add(r.amount()));
        receipt.setStatus(receipt.getPaidAmount().compareTo(nz(receipt.getTotalCost())) >= 0
            ? BookReceiptStatus.PAID : BookReceiptStatus.PARTIAL);
        receiptRepository.save(receipt);
        return toDto(payment, false);
    }

    private SupplierPaymentDto replay(BookSupplierPayment p, Long receiptId) {
        if (!p.getReceiptId().equals(receiptId)) {
            throw new ConflictException("book.idempotency.mismatch");
        }
        return toDto(p, true);
    }

    // ── Mapping ────────────────────────────────────────────────────────

    private ReceiptDto toDto(BookReceipt r, boolean director) {
        String supplierName = supplierRepository.findById(r.getSupplierId()).map(BookSupplier::getName).orElse(null);
        List<ReceiptLineDto> lines = new ArrayList<>();
        for (BookReceiptLine l : lineRepository.findByReceiptIdOrderByIdAsc(r.getId())) {
            BookItem item = itemRepository.findById(l.getBookItemId()).orElse(null);
            lines.add(new ReceiptLineDto(l.getId(), l.getBookItemId(), item != null ? item.getTitle() : null,
                item != null ? item.getKind() : null, l.getQty(),
                director ? l.getUnitCost() : null,
                director && l.getUnitCost() != null ? l.getUnitCost().multiply(BigDecimal.valueOf(l.getQty())) : null));
        }
        List<SupplierPaymentDto> payments = director
            ? paymentRepository.findByReceiptIdOrderByIdAsc(r.getId()).stream().map(p -> toDto(p, false)).toList()
            : null;
        String by = r.getCreatedBy() != null ? userRepository.findById(r.getCreatedBy())
            .map(u -> u.getFirstName() + " " + u.getLastName()).orElse(null) : null;
        return new ReceiptDto(r.getId(), r.getSupplierId(), supplierName, r.getReceiptDate(), r.getStatus(), r.getNote(),
            by, r.getCreatedAt(), lines,
            director ? r.getTotalCost() : null,
            director ? r.getPaidAmount() : null,
            director && r.getTotalCost() != null ? r.getTotalCost().subtract(nz(r.getPaidAmount())) : null,
            payments);
    }

    private static SupplierPaymentDto toDto(BookSupplierPayment p, boolean replay) {
        return new SupplierPaymentDto(p.getId(), p.getReceiptId(), p.getSupplierId(), p.getAmount(), p.getCashRegisterId(),
            p.getPaymentMethod(), p.getCashTransactionId(), p.getPaidAt(), p.getNote(), replay);
    }

    private static SupplierDto toDto(BookSupplier s) {
        return new SupplierDto(s.getId(), s.getName(), s.getPhone(), s.getNote(), s.isActive());
    }

    private static BookReceiptStatus parseStatus(String raw) {
        try {
            return BookReceiptStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("book.receipt.statusInvalid", raw);
        }
    }

    static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String k = key.trim();
        if (k.length() > KEY_MAX) {
            throw CodedException.badRequest("payroll.idempotency.keyTooLong", KEY_MAX);
        }
        return k;
    }

    static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}

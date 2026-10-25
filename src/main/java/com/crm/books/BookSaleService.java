package com.crm.books;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.billing.BillingStatusService;
import com.crm.billing.ReceiptNumberService;
import com.crm.books.BookDtos.SaleDto;
import com.crm.books.BookDtos.SaleLineDto;
import com.crm.books.BookDtos.SaleLineRequest;
import com.crm.books.BookDtos.SaleRequest;
import com.crm.entity.BookItem;
import com.crm.entity.BookSale;
import com.crm.entity.BookSaleLine;
import com.crm.entity.CashRegister;
import com.crm.entity.CashTransaction;
import com.crm.entity.Student;
import com.crm.entity.enums.BookMovementType;
import com.crm.entity.enums.PaymentMethod;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.BookItemRepository;
import com.crm.repository.BookSaleLineRepository;
import com.crm.repository.BookSaleRepository;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.StudentRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kitob sotuvi (docs/design/cash-and-books.md §2.2): bitta chek — savat (o'quv va badiiy birga), narx serverdan
 * ({@code sale_price}), chegirma yo'q; o'quvchiga YOKI tashqi xaridorga. Kassa — sotuvchining o'z kassasi
 * ({@link CashAccessService}: admin — a'zo bo'lgan kassa). Qulf tartibi: kitoblar (id bo'yicha) → kassa.
 * Ombor minusga tushmaydi (409). {@code Idempotency-Key} — takror bosish ikkinchi chek yaratmaydi.
 */
@Service
@RequiredArgsConstructor
public class BookSaleService {

    private final BookSaleRepository saleRepository;
    private final BookSaleLineRepository lineRepository;
    private final BookItemRepository itemRepository;
    private final StudentRepository studentRepository;
    private final CashRegisterRepository registerRepository;
    private final UserRepository userRepository;
    private final BookStockService stock;
    private final CashAccessService access;
    private final CashRegisterService cashRegisterService;
    private final ReceiptNumberService receiptNumbers;
    private final BillingStatusService statusService;

    @Transactional
    @Audited(action = AuditAction.PAYMENT, entity = "BookSale",
        summary = "'Kitob sotuvi ' + #result.receiptNumber() + ': ' + #result.totalAmount()", entityId = "#result.id()")
    public SaleDto create(SaleRequest r, String idempotencyKey) {
        String key = BookReceiptService.normalizeKey(idempotencyKey);
        if (key != null) {
            Optional<BookSale> existing = saleRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), r);
            }
        }
        // Xaridor: o'quvchi yoki tashqi (ism majburiy)
        Student student = null;
        if (r.studentId() != null) {
            student = studentRepository.findById(r.studentId())
                .orElseThrow(() -> new ResourceNotFoundException("Student", r.studentId()));
        } else if (BookCatalogService.trim(r.buyerName()) == null) {
            throw CodedException.badRequest("book.sale.buyerRequired");
        }
        // Savat: kitob → soni (takror qatorlar qo'shiladi)
        Map<Long, Integer> cart = new LinkedHashMap<>();
        for (SaleLineRequest l : r.lines() != null ? r.lines() : List.<SaleLineRequest>of()) {
            if (l.bookItemId() == null || l.qty() == null || l.qty() <= 0) {
                throw CodedException.badRequest("book.sale.qtyInvalid");
            }
            cart.merge(l.bookItemId(), l.qty(), Integer::sum);
        }
        if (cart.isEmpty()) {
            throw CodedException.badRequest("book.sale.linesRequired");
        }
        if (r.cashRegisterId() == null) {
            throw CodedException.badRequest("payment.cashRegister.required");
        }
        CashRegister register = access.requireAccessible(r.cashRegisterId());     // o'z kassasi (boshqasi — 404)

        Map<Long, BookItem> items = stock.lockItems(cart.keySet());
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> e : cart.entrySet()) {
            BookItem item = items.get(e.getKey());
            if (!item.isActive()) {
                throw CodedException.badRequest("book.item.inactive", item.getTitle());
            }
            total = total.add(item.getSalePrice().multiply(BigDecimal.valueOf(e.getValue())));
        }
        if (total.signum() <= 0) {
            throw CodedException.badRequest("book.sale.totalZero");
        }
        if (r.expectedTotal() != null && r.expectedTotal().compareTo(total) != 0) {
            throw new ConflictException("book.sale.priceChanged", total.stripTrailingZeros().toPlainString());
        }
        if (key != null) {
            Optional<BookSale> existing = saleRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), r);
            }
        }

        LocalDate today = statusService.today();
        PaymentMethod method = r.paymentMethod() != null ? r.paymentMethod() : PaymentMethod.CASH;
        String receipt = receiptNumbers.next();
        BookSale sale = saleRepository.save(BookSale.builder()
            .receiptNumber(receipt)
            .studentId(student != null ? student.getId() : null)
            .buyerName(student != null ? null : BookCatalogService.trim(r.buyerName()))
            .buyerPhone(student != null ? null : BookCatalogService.trim(r.buyerPhone()))
            .totalAmount(total)
            .cashRegisterId(register.getId())
            .paymentMethod(method)
            .cashPart(r.cashPart())
            .cardPart(r.cardPart())
            .saleDate(today)
            .idempotencyKey(key)
            .createdBy(access.currentUserId())
            .build());
        for (Map.Entry<Long, Integer> e : cart.entrySet()) {
            BookItem item = items.get(e.getKey());
            int qty = e.getValue();
            BookSaleLine line = lineRepository.save(BookSaleLine.builder()
                .saleId(sale.getId())
                .bookItemId(item.getId())
                .kind(item.getKind())
                .qty(qty)
                .unitPrice(item.getSalePrice())
                .unitCost(item.getAvgCost())
                .lineTotal(item.getSalePrice().multiply(BigDecimal.valueOf(qty)))
                .build());
            stock.move(item, BookMovementType.SALE, -qty, item.getAvgCost(), "SALE_LINE", line.getId(), null);
        }
        CashTransaction tx = cashRegisterService.recordIncome(register.getId(), total, method, student,
            "Kitob sotuvi", "Chek " + receipt, today, r.cashPart(), r.cardPart(), null, CashCategories.BOOK_SALE);
        tx.setBookSaleId(sale.getId());
        sale.setCashTransactionId(tx.getId());
        return toDto(saleRepository.save(sale), access.isUnrestricted(), false);
    }

    private SaleDto replay(BookSale sale, SaleRequest r) {
        if (r.cashRegisterId() != null && !sale.getCashRegisterId().equals(r.cashRegisterId())) {
            throw new ConflictException("book.idempotency.mismatch");
        }
        access.requireAccessible(sale.getCashRegisterId());
        return toDto(sale, access.isUnrestricted(), true);
    }

    /** SA — hammasi; ADMIN / ACCOUNTANT — o'z kassasidagi sotuvlar. */
    @Transactional(readOnly = true)
    public Page<SaleDto> list(LocalDate from, LocalDate to, Long studentId, int page, int size) {
        Specification<BookSale> spec = Specification.where(null);
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("saleDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("saleDate"), to));
        }
        if (studentId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("studentId"), studentId));
        }
        List<Long> visible = access.visibleRegisterIds();
        if (visible != null) {
            spec = visible.isEmpty() ? spec.and((root, q, cb) -> cb.disjunction())
                : spec.and((root, q, cb) -> root.get("cashRegisterId").in(visible));
        }
        boolean director = access.isUnrestricted();
        return saleRepository.findAll(spec, PageRequest.of(page, Math.min(Math.max(size, 1), 200),
            Sort.by(Sort.Direction.DESC, "saleDate", "id"))).map(s -> toDto(s, director, false));
    }

    @Transactional(readOnly = true)
    public SaleDto get(Long id) {
        BookSale sale = saleRepository.findById(id).orElseThrow(() -> CodedException.notFound("book.sale.notFound", id));
        access.requireAccessible(sale.getCashRegisterId());
        return toDto(sale, access.isUnrestricted(), false);
    }

    private SaleDto toDto(BookSale s, boolean director, boolean replay) {
        List<SaleLineDto> lines = new ArrayList<>();
        for (BookSaleLine l : lineRepository.findBySaleIdOrderByIdAsc(s.getId())) {
            String title = itemRepository.findById(l.getBookItemId()).map(BookItem::getTitle).orElse(null);
            lines.add(new SaleLineDto(l.getBookItemId(), title, l.getKind(), l.getQty(), l.getUnitPrice(),
                l.getLineTotal(), director ? l.getUnitCost() : null));
        }
        String studentName = s.getStudentId() != null ? studentRepository.findById(s.getStudentId())
            .map(st -> st.getFirstName() + " " + st.getLastName()).orElse(null) : null;
        String registerName = registerRepository.findById(s.getCashRegisterId()).map(CashRegister::getName).orElse(null);
        String by = s.getCreatedBy() != null ? userRepository.findById(s.getCreatedBy())
            .map(u -> u.getFirstName() + " " + u.getLastName()).orElse(null) : null;
        return new SaleDto(s.getId(), s.getReceiptNumber(), s.getStudentId(), studentName, s.getBuyerName(),
            s.getBuyerPhone(), s.getTotalAmount(), s.getCashRegisterId(), registerName, s.getPaymentMethod(),
            s.getCashTransactionId(), s.getSaleDate(), by, s.getCreatedAt(), lines, replay ? Boolean.TRUE : null);
    }
}

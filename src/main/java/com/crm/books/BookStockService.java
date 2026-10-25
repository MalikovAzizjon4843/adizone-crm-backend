package com.crm.books;

import com.crm.billing.BillingLocks;
import com.crm.entity.BookItem;
import com.crm.entity.BookStockMovement;
import com.crm.entity.enums.BookMovementType;
import com.crm.entity.enums.UserRole;
import com.crm.exception.ConflictException;
import com.crm.notification.NotificationRequest;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import com.crm.repository.BookItemRepository;
import com.crm.repository.BookStockMovementRepository;
import com.crm.service.CashAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Ombor qoldig'i — YAGONA o'zgartirish nuqtasi (docs/design/cash-and-books.md §2.1, V87):
 * <ul>
 *   <li>kitob qatori qulf ostida ({@link BillingLocks#lock}, id o'sish tartibida — parallel sotuvlarda deadlock yo'q);</li>
 *   <li>qoldiq minusga tushmaydi — 409 {@code book.stock.insufficient} (bazada ham {@code CHECK stock_qty >= 0});</li>
 *   <li>har o'zgarish — {@code book_stock_movements} jurnaliga ({@code qty_after} bilan);</li>
 *   <li>kamaygandan keyin qoldiq ≤ chegara — SA va ADMIN qo'ng'iroqchasiga {@code BOOK_LOW_STOCK}
 *       (kalit {@code lowstock:{bookId}:{kun}} — kuniga bittadan).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BookStockService {

    private final BillingLocks locks;
    private final BookItemRepository itemRepository;
    private final BookStockMovementRepository movementRepository;
    private final NotificationService notifications;
    private final CashAccessService access;
    private final Clock billingClock;

    /** Kitob qatorlarini qulflaydi (id o'sish tartibida); yo'q kitob — 404. */
    public Map<Long, BookItem> lockItems(Collection<Long> ids) {
        Map<Long, BookItem> out = new LinkedHashMap<>();
        for (Long id : new TreeSet<>(ids)) {
            out.put(id, locks.lock(BookItem.class, id));
        }
        return out;
    }

    /**
     * Qoldiqni {@code delta} ga o'zgartiradi (qatori qulflangan bo'lishi shart). Minusga tushsa — 409.
     *
     * @param unitCost harakat tannarxi (kirim — kirim narxi, chiqim — o'rtacha tannarx nusxasi); null — noma'lum
     */
    public BookStockMovement move(BookItem item, BookMovementType type, int delta, BigDecimal unitCost,
                                  String refType, Long refId, String reason) {
        int after = item.getStockQty() + delta;
        if (after < 0) {
            throw new ConflictException("book.stock.insufficient", item.getTitle(), item.getStockQty());
        }
        item.setStockQty(after);
        itemRepository.save(item);
        BookStockMovement m = movementRepository.save(BookStockMovement.builder()
            .bookItemId(item.getId())
            .type(type)
            .qtyDelta(delta)
            .qtyAfter(after)
            .unitCost(unitCost)
            .refType(refType)
            .refId(refId)
            .reason(reason)
            .createdBy(access.currentUserId())
            .build());
        if (delta < 0) {
            notifyIfLow(item);
        }
        return m;
    }

    /**
     * O'rtacha tortilgan tannarx: {@code qty} dona {@code unitCost} dan (allaqachon qoldiqda). Avvalgi tannarx
     * yo'q yoki qoldiq shu partiyadan oshmasa — {@code unitCost}. Tannarx keyin to'ldirilsa (orada sotuv bo'lgan) —
     * taxminiy (dizayn §2.5).
     */
    public void applyCost(BookItem item, int qty, BigDecimal unitCost) {
        if (unitCost == null || qty <= 0) {
            return;
        }
        int stock = item.getStockQty();
        BigDecimal old = item.getAvgCost();
        if (old == null || stock <= qty) {
            item.setAvgCost(unitCost.setScale(2, RoundingMode.HALF_UP));
        } else {
            BigDecimal total = old.multiply(BigDecimal.valueOf(stock - qty)).add(unitCost.multiply(BigDecimal.valueOf(qty)));
            item.setAvgCost(total.divide(BigDecimal.valueOf(stock), 2, RoundingMode.HALF_UP));
        }
        itemRepository.save(item);
    }

    /** Partiya tannarxi o'zgardi ({@code oldCost → newCost}, {@code qty} dona): o'rtacha tuzatiladi. */
    public void reCost(BookItem item, int qty, BigDecimal oldCost, BigDecimal newCost) {
        if (oldCost == null) {
            applyCost(item, qty, newCost);
            return;
        }
        int stock = item.getStockQty();
        if (stock <= 0 || item.getAvgCost() == null) {
            return;
        }
        BigDecimal total = item.getAvgCost().multiply(BigDecimal.valueOf(stock))
            .add(newCost.subtract(oldCost).multiply(BigDecimal.valueOf(Math.min(qty, stock))));
        BigDecimal avg = total.divide(BigDecimal.valueOf(stock), 2, RoundingMode.HALF_UP);
        item.setAvgCost(avg.signum() < 0 ? BigDecimal.ZERO : avg);
        itemRepository.save(item);
    }

    public static boolean isLow(BookItem item) {
        return item.isActive() && item.getStockQty() <= item.getMinStock();
    }

    private void notifyIfLow(BookItem item) {
        if (!isLow(item)) {
            return;
        }
        LocalDate today = LocalDate.now(billingClock);
        notifications.dispatch(NotificationRequest.builder()
            .type(NotificationType.BOOK_LOW_STOCK)
            .staffRole(UserRole.SUPER_ADMIN)
            .staffRole(UserRole.ADMIN)
            .arg(item.getTitle())
            .arg(String.valueOf(item.getStockQty()))
            .arg(String.valueOf(item.getMinStock()))
            .dedupeKey("lowstock:" + item.getId() + ":" + today)
            .link("/books")
            .entityType("BookItem")
            .entityId(item.getId())
            .build());
    }
}

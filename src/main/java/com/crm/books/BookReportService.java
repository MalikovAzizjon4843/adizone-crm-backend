package com.crm.books;

import com.crm.books.BookDtos.BookDto;
import com.crm.books.BookDtos.SalesReport;
import com.crm.books.BookDtos.SalesRow;
import com.crm.entity.BookItem;
import com.crm.entity.enums.BookKind;
import com.crm.exception.CodedException;
import com.crm.repository.BookItemRepository;
import com.crm.service.CashAccessService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Kitob hisobotlari (docs/design/cash-and-books.md §2.3): qoldiq, kam qolganlar, davr bo'yicha sotuv (kitob / tur /
 * kun) — SA, ADMIN, ACCOUNTANT (admin — o'z kassasidagi sotuvlar); tannarx, marja, sovg'alar tannarxi — faqat SA.
 * Yetkazib beruvchi qarzi — {@link BookReceiptService#debts()} (SA).
 */
@Service
@RequiredArgsConstructor
public class BookReportService {

    private final BookItemRepository itemRepository;
    private final BookCatalogService catalog;
    private final CashAccessService access;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<BookDto> stock(String kind, Long levelId) {
        return catalog.list(kind, levelId, Boolean.TRUE, false);
    }

    @Transactional(readOnly = true)
    public List<BookDto> lowStock() {
        return catalog.list(null, null, Boolean.TRUE, true);
    }

    /**
     * Davr bo'yicha sotuv: {@code groupBy} = ITEM (standart) | KIND | DAY. Tushum — {@code line_total};
     * SA uchun tannarx (Σ qty × unit_cost; tannarxi noma'lum qatorlar — {@code costIncomplete}) va marja.
     */
    @Transactional(readOnly = true)
    public SalesReport sales(LocalDate from, LocalDate to, String groupBy) {
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate t = to != null ? to : LocalDate.now();
        String g = groupBy == null || groupBy.isBlank() ? "ITEM" : groupBy.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ITEM", "KIND", "DAY").contains(g)) {
            throw CodedException.badRequest("book.report.groupByInvalid", groupBy);
        }
        boolean director = access.isUnrestricted();
        List<Long> visible = access.visibleRegisterIds();
        if (visible != null && visible.isEmpty()) {
            return new SalesReport(f, t, g, List.of(), row("TOTAL", "Jami", new Acc(), director), null, null);
        }
        String jpql = """
            SELECT l.bookItemId, l.kind, s.saleDate, SUM(l.qty), SUM(l.lineTotal),
                   SUM(CASE WHEN l.unitCost IS NULL THEN 0 ELSE l.unitCost * l.qty END),
                   SUM(CASE WHEN l.unitCost IS NULL THEN 1 ELSE 0 END)
            FROM BookSaleLine l, BookSale s
            WHERE s.id = l.saleId AND s.saleDate BETWEEN :f AND :t
            """ + (visible != null ? " AND s.cashRegisterId IN :regs" : "") + """

            GROUP BY l.bookItemId, l.kind, s.saleDate
            """;
        TypedQuery<Object[]> q = em.createQuery(jpql, Object[].class).setParameter("f", f).setParameter("t", t);
        if (visible != null) {
            q.setParameter("regs", visible);
        }
        Map<Long, BookItem> items = itemRepository.findAll().stream()
            .collect(Collectors.toMap(BookItem::getId, Function.identity()));
        Map<String, Acc> groups = new TreeMap<>();
        Map<String, String> labels = new TreeMap<>();
        Acc total = new Acc();
        for (Object[] r : q.getResultList()) {
            Long itemId = (Long) r[0];
            BookKind kind = (BookKind) r[1];
            LocalDate day = (LocalDate) r[2];
            String key = switch (g) {
                case "KIND" -> kind.name();
                case "DAY" -> day.toString();
                default -> String.valueOf(itemId);
            };
            labels.putIfAbsent(key, switch (g) {
                case "KIND" -> kind == BookKind.STUDY ? "O'quv kitob" : "Badiiy kitob";
                case "DAY" -> day.toString();
                default -> items.containsKey(itemId) ? items.get(itemId).getTitle() : key;
            });
            Acc a = groups.computeIfAbsent(key, k -> new Acc());
            a.add(r);
            total.add(r);
        }
        List<SalesRow> rows = new ArrayList<>();
        groups.forEach((k, a) -> rows.add(row(k, labels.get(k), a, director)));

        Long giftsCount = null;
        BigDecimal giftsCost = null;
        if (director) {
            Object[] gifts = em.createQuery("""
                    SELECT COUNT(g), COALESCE(SUM(g.unitCost), 0) FROM BookGift g WHERE g.giftDate BETWEEN :f AND :t
                    """, Object[].class).setParameter("f", f).setParameter("t", t).getSingleResult();
            giftsCount = ((Number) gifts[0]).longValue();
            giftsCost = dec(gifts[1]);
        }
        return new SalesReport(f, t, g, rows, row("TOTAL", "Jami", total, director), giftsCount, giftsCost);
    }

    private static SalesRow row(String key, String label, Acc a, boolean director) {
        return new SalesRow(key, label, a.qty, a.revenue,
            director ? a.cost : null,
            director ? a.revenue.subtract(a.cost) : null,
            director ? a.unknownCost > 0 : null);
    }

    private static final class Acc {
        long qty;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        long unknownCost;

        void add(Object[] r) {
            qty += ((Number) r[3]).longValue();
            revenue = revenue.add(dec(r[4]));
            cost = cost.add(dec(r[5]));
            unknownCost += ((Number) r[6]).longValue();
        }
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }
}

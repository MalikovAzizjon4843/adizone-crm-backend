package com.crm.books;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.books.BookDtos.AdjustmentRequest;
import com.crm.books.BookDtos.BookDto;
import com.crm.books.BookDtos.BookRequest;
import com.crm.books.BookDtos.LevelDto;
import com.crm.books.BookDtos.LevelRequest;
import com.crm.books.BookDtos.MovementDto;
import com.crm.entity.BookItem;
import com.crm.entity.BookStockMovement;
import com.crm.entity.StudyLevel;
import com.crm.entity.enums.BookKind;
import com.crm.entity.enums.BookMovementType;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.BookItemRepository;
import com.crm.repository.BookStockMovementRepository;
import com.crm.repository.StudyLevelRepository;
import com.crm.repository.UserRepository;
import com.crm.service.CashAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Darajalar va kitob katalogi (docs/design/cash-and-books.md §2.1, §2.3): ko'rish — SA, ADMIN, ACCOUNTANT
 * (darajalar — o'qituvchi ham); yaratish, narx, chegara, tuzatish (boshlang'ich qoldiq ham) — faqat SA.
 * Tannarx ({@code avgCost}) DTO da faqat SA uchun.
 */
@Service
@RequiredArgsConstructor
public class BookCatalogService {

    private final StudyLevelRepository levelRepository;
    private final BookItemRepository itemRepository;
    private final BookStockMovementRepository movementRepository;
    private final UserRepository userRepository;
    private final BookStockService stock;
    private final CashAccessService access;

    /** Kam qolgan chegarasi standarti (buyurtmachi: o'quv 30, badiiy 3) — kitobda o'zgaradi. */
    @Value(value = "${app.books.low-stock.study:30}")
    private int defaultMinStudy;

    @Value(value = "${app.books.low-stock.fiction:3}")
    private int defaultMinFiction;

    // ── Darajalar ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LevelDto> levels() {
        Map<Long, BookItem> books = itemRepository.findAll().stream()
            .collect(Collectors.toMap(BookItem::getId, Function.identity()));
        return levelRepository.findAllByOrderByLanguageAscSortOrderAscCodeAsc().stream()
            .map(l -> toDto(l, books.get(l.getGiftBookItemId())))
            .toList();
    }

    @Transactional
    public LevelDto createLevel(LevelRequest r) {
        access.requireDirector();
        StudyLevel level = new StudyLevel();
        applyLevel(level, r, true);
        return toDto(levelRepository.save(level), giftBook(level));
    }

    @Transactional
    public LevelDto updateLevel(Long id, LevelRequest r) {
        access.requireDirector();
        StudyLevel level = levelRepository.findById(id)
            .orElseThrow(() -> CodedException.notFound("book.level.notFound", id));
        applyLevel(level, r, false);
        return toDto(levelRepository.save(level), giftBook(level));
    }

    private void applyLevel(StudyLevel level, LevelRequest r, boolean create) {
        String language = trim(r.language());
        String code = trim(r.code());
        if (create || language != null) {
            if (language == null) {
                throw CodedException.badRequest("book.level.languageRequired");
            }
            level.setLanguage(language);
        }
        if (create || code != null) {
            if (code == null) {
                throw CodedException.badRequest("book.level.codeRequired");
            }
            level.setCode(code.toUpperCase(Locale.ROOT));
        }
        boolean duplicate = levelRepository.findByLanguageIgnoreCaseAndCodeIgnoreCase(level.getLanguage(), level.getCode())
            .stream().anyMatch(l -> !l.getId().equals(level.getId()));
        if (duplicate) {
            throw new ConflictException("book.level.duplicate", level.getLanguage() + " " + level.getCode());
        }
        String name = trim(r.name());
        if (name != null) {
            level.setName(name);
        } else if (create || language != null || code != null) {
            level.setName(level.getLanguage() + " " + level.getCode());
        }
        if (r.sortOrder() != null) {
            level.setSortOrder(r.sortOrder());
        }
        if (r.active() != null) {
            level.setActive(r.active());
        }
        if (r.giftBookItemId() != null) {
            if (r.giftBookItemId() == 0) {
                level.setGiftBookItemId(null);
            } else {
                BookItem book = itemRepository.findById(r.giftBookItemId())
                    .orElseThrow(() -> CodedException.notFound("book.notFound", r.giftBookItemId()));
                // Bepul kitob — shu darajadagi o'quv kitobi (daraja yangi bo'lsa — keyin, PUT bilan)
                if (book.getKind() != BookKind.STUDY || level.getId() == null || !level.getId().equals(book.getLevelId())) {
                    throw CodedException.badRequest("book.level.giftBookInvalid");
                }
                level.setGiftBookItemId(book.getId());
            }
        }
    }

    public StudyLevel requireLevel(Long id) {
        return levelRepository.findById(id).orElseThrow(() -> CodedException.notFound("book.level.notFound", id));
    }

    private BookItem giftBook(StudyLevel level) {
        return level.getGiftBookItemId() != null ? itemRepository.findById(level.getGiftBookItemId()).orElse(null) : null;
    }

    static LevelDto toDto(StudyLevel l, BookItem gift) {
        return new LevelDto(l.getId(), l.getLanguage(), l.getCode(), l.getName(), l.getSortOrder(), l.isActive(),
            l.getGiftBookItemId(), gift != null ? gift.getTitle() : null);
    }

    // ── Katalog ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BookDto> list(String kind, Long levelId, Boolean active, boolean lowOnly) {
        BookKind k = parseKind(kind, false);
        Specification<BookItem> spec = Specification.where(null);
        if (k != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("kind"), k));
        }
        if (levelId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("levelId"), levelId));
        }
        if (active != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("active"), active));
        }
        Map<Long, StudyLevel> levels = levelMap();
        boolean director = access.isUnrestricted();
        return itemRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "kind", "title")).stream()
            .filter(b -> !lowOnly || BookStockService.isLow(b))
            .map(b -> toDto(b, levels, director))
            .toList();
    }

    @Transactional(readOnly = true)
    public BookDto get(Long id) {
        return toDto(require(id), levelMap(), access.isUnrestricted());
    }

    @Transactional
    public BookDto create(BookRequest r) {
        access.requireDirector();
        BookItem b = new BookItem();
        b.setKind(parseKind(r.kind(), true));
        b.setTitle(requireTitle(r.title()));
        b.setAuthor(trim(r.author()));
        b.setLevelId(r.levelId());
        b.setSalePrice(requirePrice(r.salePrice()));
        b.setMinStock(r.minStock() != null ? requireMin(r.minStock())
            : (b.getKind() == BookKind.STUDY ? defaultMinStudy : defaultMinFiction));
        b.setActive(r.active() == null || r.active());
        validate(b);
        return toDto(itemRepository.save(b), levelMap(), true);
    }

    /** Tur o'zgarmaydi (sotuv va hisobot tarixi buzilmasin); qolgan maydonlar berilsa o'zgaradi. */
    @Transactional
    public BookDto update(Long id, BookRequest r) {
        access.requireDirector();
        BookItem b = require(id);
        if (r.kind() != null && parseKind(r.kind(), true) != b.getKind()) {
            throw CodedException.badRequest("book.item.kindImmutable");
        }
        if (r.title() != null) {
            b.setTitle(requireTitle(r.title()));
        }
        if (r.author() != null) {
            b.setAuthor(trim(r.author()));
        }
        if (r.levelId() != null && b.getKind() == BookKind.STUDY) {
            b.setLevelId(r.levelId());
        }
        if (r.salePrice() != null) {
            b.setSalePrice(requirePrice(r.salePrice()));
        }
        if (r.minStock() != null) {
            b.setMinStock(requireMin(r.minStock()));
        }
        if (r.active() != null) {
            b.setActive(r.active());
        }
        validate(b);
        return toDto(itemRepository.save(b), levelMap(), true);
    }

    @Transactional(readOnly = true)
    public Page<MovementDto> movements(Long id, int page, int size) {
        require(id);
        boolean director = access.isUnrestricted();
        return movementRepository.findByBookItemIdOrderByIdDesc(id, PageRequest.of(page, Math.min(Math.max(size, 1), 200)))
            .map(m -> toDto(m, director));
    }

    /**
     * Tuzatish (SA): sabab majburiy. Boshlang'ich qoldiq — musbat son + tannarx (o'rtacha tannarxga qo'shiladi);
     * manfiy — hisobdan chiqarish (yo'qolgan, shikastlangan). Qoldiq minusga tushmaydi.
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "BookItem",
        summary = "'Ombor tuzatildi: ' + #r.qtyDelta() + ' (' + #r.reason() + ')'", entityId = "#r.bookItemId()")
    public MovementDto adjust(AdjustmentRequest r) {
        access.requireDirector();
        String reason = trim(r.reason());
        if (reason == null || reason.length() < 3) {
            throw CodedException.badRequest("book.adjust.reasonRequired");
        }
        if (r.bookItemId() == null || r.qtyDelta() == null || r.qtyDelta() == 0) {
            throw CodedException.badRequest("book.adjust.qtyInvalid");
        }
        if (r.unitCost() != null && (r.unitCost().signum() < 0 || r.qtyDelta() < 0)) {
            throw CodedException.badRequest("book.receipt.costInvalid");
        }
        BookItem item = stock.lockItems(List.of(r.bookItemId())).get(r.bookItemId());
        BigDecimal unitCost = r.qtyDelta() > 0 ? r.unitCost() : item.getAvgCost();
        BookStockMovement m = stock.move(item, BookMovementType.ADJUSTMENT, r.qtyDelta(), unitCost,
            "ADJUSTMENT", null, reason);
        if (r.qtyDelta() > 0 && r.unitCost() != null) {
            stock.applyCost(item, r.qtyDelta(), r.unitCost());
        }
        return toDto(m, true);
    }

    // ── Yordamchilar ───────────────────────────────────────────────────

    public BookItem require(Long id) {
        return itemRepository.findById(id).orElseThrow(() -> CodedException.notFound("book.notFound", id));
    }

    public Map<Long, StudyLevel> levelMap() {
        return levelRepository.findAll().stream().collect(Collectors.toMap(StudyLevel::getId, Function.identity()));
    }

    private void validate(BookItem b) {
        if (b.getKind() == BookKind.STUDY) {
            if (b.getLevelId() == null) {
                throw CodedException.badRequest("book.item.levelRequired");
            }
            requireLevel(b.getLevelId());
        } else {
            b.setLevelId(null);
        }
        boolean duplicate = itemRepository.findByTitleIgnoreCaseAndKind(b.getTitle(), b.getKind()).stream()
            .anyMatch(o -> !o.getId().equals(b.getId()) && java.util.Objects.equals(o.getLevelId(), b.getLevelId()));
        if (duplicate) {
            throw new ConflictException("book.item.duplicate", b.getTitle());
        }
    }

    BookDto toDto(BookItem b, Map<Long, StudyLevel> levels, boolean director) {
        StudyLevel level = b.getLevelId() != null ? levels.get(b.getLevelId()) : null;
        BigDecimal value = director && b.getAvgCost() != null
            ? b.getAvgCost().multiply(BigDecimal.valueOf(b.getStockQty())) : null;
        return new BookDto(b.getId(), b.getKind(), b.getTitle(), b.getAuthor(), b.getLevelId(),
            level != null ? level.getName() : null, b.getSalePrice(), b.getStockQty(), b.getMinStock(),
            BookStockService.isLow(b), b.isActive(), director ? b.getAvgCost() : null, value);
    }

    private MovementDto toDto(BookStockMovement m, boolean director) {
        String by = m.getCreatedBy() != null ? userRepository.findById(m.getCreatedBy())
            .map(u -> u.getFirstName() + " " + u.getLastName()).orElse(null) : null;
        return new MovementDto(m.getId(), m.getBookItemId(), m.getType(), m.getQtyDelta(), m.getQtyAfter(),
            director ? m.getUnitCost() : null, m.getRefType(), m.getRefId(), m.getReason(), by, m.getCreatedAt());
    }

    static BookKind parseKind(String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw CodedException.badRequest("book.item.kindInvalid", raw);
            }
            return null;
        }
        try {
            return BookKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("book.item.kindInvalid", raw);
        }
    }

    private static String requireTitle(String raw) {
        String t = trim(raw);
        if (t == null || t.length() > 200) {
            throw CodedException.badRequest("book.item.titleRequired");
        }
        return t;
    }

    private static BigDecimal requirePrice(BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw CodedException.badRequest("book.item.priceInvalid");
        }
        return price;
    }

    private static int requireMin(int min) {
        if (min < 0) {
            throw CodedException.badRequest("book.item.minStockInvalid");
        }
        return min;
    }

    static String trim(String s) {
        return s != null && !s.isBlank() ? s.trim() : null;
    }
}

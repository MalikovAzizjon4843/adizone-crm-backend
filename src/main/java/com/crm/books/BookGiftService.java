package com.crm.books;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.billing.BillingLocks;
import com.crm.billing.BillingStatusService;
import com.crm.billing.EnrollmentPricing;
import com.crm.billing.Money;
import com.crm.books.BookDtos.EligibilityDto;
import com.crm.books.BookDtos.GiftDto;
import com.crm.books.BookDtos.GiftRequest;
import com.crm.entity.BookGift;
import com.crm.entity.BookItem;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.StudyLevel;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.BookKind;
import com.crm.entity.enums.BookMovementType;
import com.crm.entity.enums.PaymentStatus;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BookGiftRepository;
import com.crm.repository.BookItemRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.StudyLevelRepository;
import com.crm.repository.UserRepository;
import com.crm.service.CashAccessService;
import com.crm.service.GroupScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Bepul kitob (docs/design/cash-and-books.md §2.2, buyurtmachi qarori 2026-10-18):
 * <ol>
 *   <li>bugun — o'quvchining markazga BIRINCHI kelgan kuni: eng erta PRESENT / LATE davomat bugun; davomat hali
 *       belgilanmagan bo'lsa — guruhida bugun dars bor (standart: {@code first_visit_assumed}). Bugundan oldingi
 *       to'langan to'lovi bo'lsa — yangi o'quvchi emas;</li>
 *   <li>shu kuni oylik to'liq to'langan: yozilma bo'yicha bugungi to'lovlar (brutto, chegirma bilan) ≥ oylik narx
 *       {@code c(sg)} (chegirmadan keyin; PER_LESSON — guruhning oylik narxi) va yozilma qarzi yo'q (ledger ≥ 0);</li>
 *   <li>avval bepul kitob olmagan ({@code book_gifts.student_id} UNIQUE);</li>
 *   <li>guruhning darajasi bor, darajada bepul kitob belgilangan va omborda bor.</li>
 * </ol>
 * Ertasidan mumkin emas (1-shart). Ombor kamayadi (GIFT), kassaga yozilmaydi. Qulf: o'quvchi → kitob.
 */
@Service
@RequiredArgsConstructor
public class BookGiftService {

    public static final String ALREADY_GIVEN = "ALREADY_GIVEN";
    public static final String NOT_FIRST_VISIT_DAY = "NOT_FIRST_VISIT_DAY";
    public static final String NOT_FULLY_PAID = "NOT_FULLY_PAID";
    public static final String NO_LEVEL = "NO_LEVEL";
    public static final String NO_GIFT_BOOK = "NO_GIFT_BOOK";
    public static final String OUT_OF_STOCK = "OUT_OF_STOCK";

    private static final EnumSet<AttendanceStatus> ATTENDED = EnumSet.of(AttendanceStatus.PRESENT, AttendanceStatus.LATE);

    private final BookGiftRepository giftRepository;
    private final BookItemRepository itemRepository;
    private final StudyLevelRepository levelRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository enrollmentRepository;
    private final PaymentRepository paymentRepository;
    private final BalanceTransactionRepository ledgerRepository;
    private final AttendanceRepository attendanceRepository;
    private final UserRepository userRepository;
    private final GroupScheduleService scheduleService;
    private final BillingStatusService statusService;
    private final BillingLocks locks;
    private final BookStockService stock;
    private final CashAccessService access;

    /** Natija: mos yozilma va kitob (eligible) yoki birinchi buzilgan shart sababi. */
    record Check(EligibilityDto dto, StudentGroup enrollment, Payment payment, BookItem book) {
    }

    @Transactional(readOnly = true)
    public EligibilityDto eligibility(Long studentId, Long studentGroupId) {
        return check(studentId, studentGroupId).dto();
    }

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "Student",
        summary = "'Bepul kitob: ' + #result.bookTitle()", entityId = "#r.studentId()")
    public GiftDto give(GiftRequest r) {
        if (r.studentId() == null) {
            throw CodedException.badRequest("book.gift.studentRequired");
        }
        // Qulf: o'quvchi (parallel ikki bosish — ikkinchisi birinchisini kutadi va ALREADY_GIVEN oladi)
        locks.acquire(BillingLocks.Plan.of().student(r.studentId()));
        Check c = check(r.studentId(), r.studentGroupId());
        if (!c.dto().eligible()) {
            throw new ConflictException("book.gift.notEligible", c.dto().reason())
                .withData(java.util.Map.of("reason", c.dto().reason()));
        }
        BookItem book = stock.lockItems(List.of(c.book().getId())).get(c.book().getId());
        LocalDate today = statusService.today();
        BookGift gift = giftRepository.save(BookGift.builder()
            .studentId(r.studentId())
            .studentGroupId(c.enrollment().getId())
            .paymentId(c.payment() != null ? c.payment().getId() : null)
            .levelId(book.getLevelId())
            .bookItemId(book.getId())
            .firstVisitDate(c.dto().firstVisitDate())
            .firstVisitAssumed(Boolean.TRUE.equals(c.dto().firstVisitAssumed()))
            .unitCost(book.getAvgCost())
            .giftDate(today)
            .createdBy(access.currentUserId())
            .build());
        stock.move(book, BookMovementType.GIFT, -1, book.getAvgCost(), "GIFT", gift.getId(), null);
        return toDto(gift, access.isUnrestricted());
    }

    @Transactional(readOnly = true)
    public Page<GiftDto> list(LocalDate from, LocalDate to, int page, int size) {
        boolean director = access.isUnrestricted();
        return giftRepository.findAll((root, q, cb) -> cb.and(
                from != null ? cb.greaterThanOrEqualTo(root.get("giftDate"), from) : cb.conjunction(),
                to != null ? cb.lessThanOrEqualTo(root.get("giftDate"), to) : cb.conjunction()),
            PageRequest.of(page, Math.min(Math.max(size, 1), 200), Sort.by(Sort.Direction.DESC, "giftDate", "id")))
            .map(g -> toDto(g, director));
    }

    // ── Shart ──────────────────────────────────────────────────────────

    Check check(Long studentId, Long preferredEnrollmentId) {
        studentRepository.findById(studentId).orElseThrow(() -> new ResourceNotFoundException("Student", studentId));
        LocalDate today = statusService.today();
        if (giftRepository.existsByStudentId(studentId)) {
            return no(studentId, ALREADY_GIVEN, null, null);
        }
        List<StudentGroup> active = enrollmentRepository.findByStudentIdAndIsActiveTrue(studentId);

        // 1. Birinchi kelgan kun
        LocalDate firstAttended = attendanceRepository.findFirstAttendedDate(studentId, ATTENDED);
        LocalDate firstVisit;
        boolean assumed = false;
        if (firstAttended != null) {
            firstVisit = firstAttended;
        } else if (active.stream().anyMatch(sg -> scheduleService.hasLessonOn(sg.getGroup().getId(), today))) {
            firstVisit = today;          // davomat hali belgilanmagan, bugun dars bor
            assumed = true;
        } else {
            return no(studentId, NOT_FIRST_VISIT_DAY, null, null);
        }
        List<Payment> payments = paymentRepository.findByStudentIdOrderByPaymentDateDesc(studentId).stream()
            .filter(p -> p.getStatus() == PaymentStatus.PAID)
            .toList();
        boolean paidBefore = payments.stream().anyMatch(p -> p.getPaymentDate().isBefore(today));
        if (!firstVisit.equals(today) || paidBefore) {
            return no(studentId, NOT_FIRST_VISIT_DAY, firstVisit, assumed);
        }

        // 2. Shu kuni oylik to'liq to'langan yozilma(lar)
        List<StudentGroup> paid = active.stream()
            .filter(sg -> fullyPaidToday(sg, payments, today))
            .sorted(Comparator.comparing((StudentGroup sg) -> !sg.getId().equals(preferredEnrollmentId))
                .thenComparing(sg -> sg.getGroup().getLevelId() == null)
                .thenComparing(StudentGroup::getId))
            .toList();
        if (paid.isEmpty()) {
            return no(studentId, NOT_FULLY_PAID, firstVisit, assumed);
        }
        StudentGroup sg = paid.get(0);
        Payment payment = payments.stream()
            .filter(p -> p.getStudentGroup() != null && p.getStudentGroup().getId().equals(sg.getId())
                && p.getPaymentDate().equals(today))
            .findFirst().orElse(null);

        // 3. Daraja va uning bepul kitobi
        Long levelId = sg.getGroup().getLevelId();
        if (levelId == null) {
            return no(studentId, NO_LEVEL, firstVisit, assumed, sg, null, null);
        }
        StudyLevel level = levelRepository.findById(levelId).orElse(null);
        if (level == null) {
            return no(studentId, NO_LEVEL, firstVisit, assumed, sg, null, null);
        }
        BookItem book = level.getGiftBookItemId() != null
            ? itemRepository.findById(level.getGiftBookItemId()).orElse(null) : null;
        if (book == null || !book.isActive() || book.getKind() != BookKind.STUDY) {
            return no(studentId, NO_GIFT_BOOK, firstVisit, assumed, sg, level, null);
        }
        if (book.getStockQty() <= 0) {
            return no(studentId, OUT_OF_STOCK, firstVisit, assumed, sg, level, book);
        }
        EligibilityDto dto = new EligibilityDto(studentId, true, null, firstVisit, assumed, sg.getId(),
            sg.getGroup().getGroupName(), level.getId(), level.getName(), book.getId(), book.getTitle(),
            book.getStockQty());
        return new Check(dto, sg, payment, book);
    }

    /** Bugungi to'lovlar (brutto) ≥ oylik narx (chegirmadan keyin) va yozilma qarzi yo'q. */
    private boolean fullyPaidToday(StudentGroup sg, List<Payment> payments, LocalDate today) {
        List<Payment> todays = payments.stream()
            .filter(p -> p.getStudentGroup() != null && p.getStudentGroup().getId().equals(sg.getId())
                && p.getPaymentDate().equals(today))
            .toList();
        if (todays.isEmpty()) {
            return false;
        }
        BigDecimal gross = todays.stream().map(p -> Money.nz(p.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = EnrollmentPricing.effectiveMonthlyFee(sg);
        BigDecimal balance = Money.nz(ledgerRepository.sumAmountByStudentGroupId(sg.getId()));
        return gross.compareTo(fee) >= 0 && balance.signum() >= 0;
    }

    private static Check no(Long studentId, String reason, LocalDate firstVisit, Boolean assumed) {
        return no(studentId, reason, firstVisit, assumed, null, null, null);
    }

    private static Check no(Long studentId, String reason, LocalDate firstVisit, Boolean assumed, StudentGroup sg,
                            StudyLevel level, BookItem book) {
        return new Check(new EligibilityDto(studentId, false, reason, firstVisit, assumed,
            sg != null ? sg.getId() : null, sg != null ? sg.getGroup().getGroupName() : null,
            level != null ? level.getId() : null, level != null ? level.getName() : null,
            book != null ? book.getId() : null, book != null ? book.getTitle() : null,
            book != null ? book.getStockQty() : null), sg, null, book);
    }

    private GiftDto toDto(BookGift g, boolean director) {
        String student = studentRepository.findById(g.getStudentId())
            .map(s -> s.getFirstName() + " " + s.getLastName()).orElse(null);
        String level = levelRepository.findById(g.getLevelId()).map(StudyLevel::getName).orElse(null);
        String book = itemRepository.findById(g.getBookItemId()).map(BookItem::getTitle).orElse(null);
        String by = g.getCreatedBy() != null ? userRepository.findById(g.getCreatedBy())
            .map(u -> u.getFirstName() + " " + u.getLastName()).orElse(null) : null;
        return new GiftDto(g.getId(), g.getStudentId(), student, g.getStudentGroupId(), g.getLevelId(), level,
            g.getBookItemId(), book, g.getFirstVisitDate(), g.isFirstVisitAssumed(), g.getGiftDate(), by,
            director ? g.getUnitCost() : null);
    }
}

package com.crm.miniapp;

import com.crm.billing.BillingStatusService;
import com.crm.billing.EnrollmentPricing;
import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import com.crm.entity.Attendance;
import com.crm.entity.Classroom;
import com.crm.entity.Group;
import com.crm.entity.Holiday;
import com.crm.entity.LessonException;
import com.crm.entity.LessonSubstitution;
import com.crm.entity.Payment;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.Teacher;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.SubstitutionStatus;
import com.crm.exception.CodedException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.repository.LessonSubstitutionRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.service.GroupScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mini App ekranlari (docs/design/telegram-platform.md §3.5, miniapp-api.md §3) — FAQAT O'QISH.
 * Billing ma'lumoti saqlangan snapshot'dan (billing-v2 §4, §8) olinadi: bu servis hech narsa
 * yozmaydi va snapshot'ni yangilamaydi ({@code BillingSnapshotService} ni chaqirmaydi).
 *
 * <p>Kirish huquqi chaqiruvchida: har metod {@link MiniAppAuthService#requireStudent} dan o'tgan
 * {@link Student} oladi.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MiniAppQueryService {

    /** Jadval oralig'i chegarasi (kun). */
    static final int MAX_SCHEDULE_DAYS = 62;
    /** Keyingi darsni shuncha kun oldinga qidiramiz. */
    private static final int NEXT_LESSON_HORIZON_DAYS = 14;
    private static final int HISTORY_LIMIT = 100;

    private final StudentGroupRepository studentGroupRepository;
    private final StudentRepository studentRepository;
    private final GroupScheduleService groupScheduleService;
    private final HolidayRepository holidayRepository;
    private final LessonExceptionRepository lessonExceptionRepository;
    private final LessonSubstitutionRepository lessonSubstitutionRepository;
    private final AttendanceRepository attendanceRepository;
    private final PaymentRepository paymentRepository;
    private final BillingStatusService billingStatusService;
    private final MiniAppAuthService authService;
    private final Clock billingClock;

    // ── Bosh sahifa ──────────────────────────────────────────────────────

    public AppDtos.Home home(Student student) {
        List<StudentGroup> sgs = openEnrollments(student.getId());
        LocalDateTime now = LocalDateTime.now(billingClock);
        LocalDate today = now.toLocalDate();
        AppDtos.Lesson next = lessons(sgs, today, today.plusDays(NEXT_LESSON_HORIZON_DAYS), now).stream()
            .filter(l -> "PLANNED".equals(l.status()) || "EXTRA".equals(l.status()))
            .filter(l -> l.date().isAfter(today) || l.endTime() == null
                || LocalTime.parse(l.endTime()).isAfter(now.toLocalTime()))
            .findFirst()
            .orElse(null);
        return new AppDtos.Home(header(student), groupCards(sgs), next, balance(sgs, today),
            attendanceSummary(student.getId(), YearMonth.from(today)));
    }

    // ── Jadval ───────────────────────────────────────────────────────────

    public AppDtos.Schedule schedule(Student student, LocalDate from, LocalDate to) {
        LocalDateTime now = LocalDateTime.now(billingClock);
        LocalDate start = from != null ? from : now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = to != null ? to : start.plusDays(6);
        if (end.isBefore(start) || Duration.between(start.atStartOfDay(), end.atStartOfDay()).toDays() >= MAX_SCHEDULE_DAYS) {
            throw CodedException.badRequest("app.schedule.range.invalid", MAX_SCHEDULE_DAYS);
        }
        List<StudentGroup> sgs = openEnrollments(student.getId());
        return new AppDtos.Schedule(start, end, authService.support().address(), groupCards(sgs),
            lessons(sgs, start, end, now));
    }

    // ── Davomat ──────────────────────────────────────────────────────────

    public AppDtos.AttendanceMonth attendance(Student student, String month) {
        YearMonth ym = parseMonth(month);
        LocalDate today = LocalDate.now(billingClock);
        List<Attendance> rows = attendanceRepository.findByStudentIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            student.getId(), ym.atDay(1), ym.atEndOfMonth());
        int present = 0, late = 0, absent = 0, excused = 0;
        List<AppDtos.AttendanceDay> days = new ArrayList<>();
        for (Attendance a : rows) {
            String status = status(a);
            switch (status) {
                case "PRESENT" -> present++;
                case "LATE" -> late++;
                case "EXCUSED" -> excused++;
                default -> absent++;
            }
            days.add(new AppDtos.AttendanceDay(a.getAttendanceDate(), a.getGroup().getId(),
                a.getGroup().getGroupName(), status,
                "EXCUSED".equals(status) ? MiniAppAuthService.blankToNull(a.getExcuseReason()) : null));
        }
        int total = present + late + absent + excused;
        AppDtos.LastMissed lastMissed = attendanceRepository
            .findTop10ByStudentIdAndStatusAndAttendanceDateLessThanEqualOrderByAttendanceDateDesc(
                student.getId(), AttendanceStatus.ABSENT, today).stream()
            .filter(a -> !Boolean.TRUE.equals(a.getExcused()))
            .findFirst()
            .map(a -> new AppDtos.LastMissed(a.getAttendanceDate(), a.getAttendanceDate().getDayOfWeek().name(),
                a.getGroup().getGroupName()))
            .orElse(null);
        return new AppDtos.AttendanceMonth(ym.toString(), new AppDtos.Counts(present, late, absent, excused),
            total, rate(present + late, total), days, lastMissed);
    }

    // ── To'lov ───────────────────────────────────────────────────────────

    public AppDtos.Payments payments(Student student) {
        LocalDate today = LocalDate.now(billingClock);
        List<StudentGroup> sgs = openEnrollments(student.getId());
        List<AppDtos.EnrollmentFee> fees = sgs.stream().map(sg -> {
            BigDecimal balance = nz(sg.getBalance());
            boolean perLesson = sg.getPaymentType() == PaymentType.PER_LESSON;
            return new AppDtos.EnrollmentFee(sg.getId(), sg.getGroup().getId(), sg.getGroup().getGroupName(),
                courseName(sg.getGroup()), sg.getPaymentType() != null ? sg.getPaymentType().name() : null,
                perLesson ? EnrollmentPricing.lessonPrice(sg) : EnrollmentPricing.monthlyFee(sg),
                sg.getDiscountPercentage() != null ? sg.getDiscountPercentage() : BigDecimal.ZERO,
                perLesson ? EnrollmentPricing.effectiveLessonPrice(sg) : EnrollmentPricing.effectiveMonthlyFee(sg),
                balance, debt(balance), status(sg, today).name(), sg.getDebtSince(),
                sg.getNextPaymentDate(), sg.getNextPaymentAmount());
        }).toList();

        List<AppDtos.PaymentItem> history = paymentRepository.findByStudentIdOrderByPaymentDateDesc(student.getId())
            .stream()
            .filter(p -> p.getStatus() == PaymentStatus.PAID || p.getStatus() == PaymentStatus.CANCELLED)
            .sorted(Comparator.comparing(Payment::getPaymentDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Payment::getId, Comparator.reverseOrder()))
            .limit(HISTORY_LIMIT)
            .map(p -> new AppDtos.PaymentItem(p.getId(), p.getPaymentDate(), p.getPeriodStart(), p.getPeriodEnd(),
                p.getGroup() != null ? p.getGroup().getGroupName() : null,
                p.getPaymentMethod() != null ? p.getPaymentMethod().name() : null,
                p.getPaymentMethod() != null ? p.getPaymentMethod().getLabel() : null,
                paidAmount(p), p.getStatus().name(), p.getReceiptNumber()))
            .toList();

        AppDtos.Support support = authService.support();
        return new AppDtos.Payments(balance(sgs, today), fees, history,
            new AppDtos.HowToPay(false, support.address(), support.phone()));
    }

    // ── Profil ───────────────────────────────────────────────────────────

    public AppDtos.ProfileScreen profile(AppPrincipal principal, Student student) {
        AppIdentity identity = authService.identity(principal);
        List<StudentGroup> sgs = openEnrollments(student.getId());
        List<AppDtos.Enrollment> enrollments = sgs.stream().map(sg -> {
            Group g = sg.getGroup();
            return new AppDtos.Enrollment(g.getId(), g.getGroupName(), courseName(g),
                sg.getStudyFormat() != null ? sg.getStudyFormat().name() : null,
                roomOf(g, null), enrollmentStatus(sg), teacherName(g.getTeacher()), sg.getJoinDate());
        }).toList();

        List<AppIdentityStudent> links = authService.links(identity.getId());
        Map<Long, Student> linked = studentRepository.findAllById(
                links.stream().map(AppIdentityStudent::getStudentId).toList()).stream()
            .collect(Collectors.toMap(Student::getId, Function.identity()));
        List<AppDtos.LinkedStudent> linkedStudents = links.stream()
            .filter(l -> linked.containsKey(l.getStudentId()))
            .map(l -> new AppDtos.LinkedStudent(l.getStudentId(), fullName(linked.get(l.getStudentId())),
                l.getRelation().name()))
            .toList();

        return new AppDtos.ProfileScreen(
            new AppDtos.StudentProfile(student.getId(), fullName(student),
                MiniAppAuthService.maskPhone(com.crm.util.PhoneUtils.canonical(student.getPhone())),
                student.getBirthDate()),
            enrollments,
            new AppDtos.Link(identity.getKind().name(), MiniAppAuthService.maskPhone(identity.getPhoneCanonical()),
                identity.getLinkedAt(), linkedStudents),
            authService.support());
    }

    // ── Yordamchilar: yozilmalar, guruh kartasi ─────────────────────────

    /** Ochiq yozilmalar (faol yoki muzlatilgan — {@link BillingStatusService#isOpen}), yangilari oldin. */
    private List<StudentGroup> openEnrollments(Long studentId) {
        return studentGroupRepository.findByStudentIdOrderByJoinDateDesc(studentId).stream()
            .filter(BillingStatusService::isOpen)
            .toList();
    }

    private List<AppDtos.GroupCard> groupCards(List<StudentGroup> sgs) {
        List<AppDtos.GroupCard> cards = new ArrayList<>();
        for (StudentGroup sg : sgs) {
            Group g = sg.getGroup();
            List<GroupScheduleService.LessonSlot> slots = sortedSlots(g.getId());
            GroupScheduleService.LessonSlot first = slots.isEmpty() ? null : slots.get(0);
            cards.add(new AppDtos.GroupCard(g.getId(), g.getGroupName(), courseName(g), teacherName(g.getTeacher()),
                slots.stream().map(GroupScheduleService.LessonSlot::dayOfWeek).distinct().toList(),
                first != null ? first.startTime() : null, first != null ? first.endTime() : null,
                roomOf(g, first), sg.getStudyFormat() != null ? sg.getStudyFormat().name() : null,
                enrollmentStatus(sg)));
        }
        return cards;
    }

    private List<GroupScheduleService.LessonSlot> sortedSlots(Long groupId) {
        return groupScheduleService.lessonSlots(groupId).stream()
            .filter(s -> dayOf(s) != null)
            .sorted(Comparator.comparing(MiniAppQueryService::dayOf))
            .toList();
    }

    private static DayOfWeek dayOf(GroupScheduleService.LessonSlot slot) {
        try {
            return DayOfWeek.valueOf(slot.dayOfWeek());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    // ── Darslar: jadval + bayramlar + istisnolar + o'rinbosar ────────────

    /**
     * {@link GroupScheduleService#hasLessonOn} bilan bir xil qoida: bayram — dars yo'q (CANCELLED,
     * {@code note} = bayram nomi); CANCELLED/MOVED istisnosi — asl kunda; EXTRA yoki MOVED (yangi kun) —
     * qo'shimcha dars. Faqat yozilma davri ({@code joinDate..leaveDate}) va guruh sanalari ichida.
     */
    private List<AppDtos.Lesson> lessons(List<StudentGroup> sgs, LocalDate from, LocalDate to, LocalDateTime now) {
        Map<LocalDate, String> holidays = holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(from, to)
            .stream().collect(Collectors.toMap(Holiday::getHolidayDate, Holiday::getName, (a, b) -> a));
        Map<Long, LessonSubstitution> substitutions = new java.util.HashMap<>();
        for (LessonSubstitution s : lessonSubstitutionRepository.findActiveBetween(from, to, SubstitutionStatus.CANCELLED)) {
            substitutions.put(key(s.getGroup().getId(), s.getLessonDate()), s);
        }

        List<AppDtos.Lesson> out = new ArrayList<>();
        for (StudentGroup sg : sgs) {
            Group g = sg.getGroup();
            List<GroupScheduleService.LessonSlot> slots = sortedSlots(g.getId());
            List<LessonException> exceptions = lessonExceptionRepository.findByGroupIdOrderByLessonDateDesc(g.getId());
            if (slots.isEmpty() && exceptions.isEmpty()) {
                continue;
            }
            String teacher = teacherName(g.getTeacher());
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (!inEnrollment(sg, g, d)) {
                    continue;
                }
                LocalDate date = d;
                GroupScheduleService.LessonSlot slot = slotOn(slots, date);
                LessonSubstitution sub = substitutions.get(key(g.getId(), date));
                String substitute = sub != null ? teacherName(sub.getSubstituteTeacher()) : null;
                String holiday = holidays.get(date);
                boolean regular = slots.stream().anyMatch(s -> date.getDayOfWeek() == dayOf(s));

                if (regular) {
                    LessonException moved = find(exceptions, e -> e.getKind() == LessonException.Kind.MOVED
                        && date.equals(e.getLessonDate()));
                    LessonException cancelled = find(exceptions, e -> e.getKind() == LessonException.Kind.CANCELLED
                        && date.equals(e.getLessonDate()));
                    String status = holiday != null || cancelled != null ? "CANCELLED"
                        : moved != null ? "MOVED" : "PLANNED";
                    out.add(lesson(date, g, slot, status, teacher, "PLANNED".equals(status) ? substitute : null,
                        moved != null && holiday == null ? moved.getMovedTo() : null, null, holiday, now));
                }
                if (holiday != null) {
                    continue;
                }
                LessonException extra = find(exceptions, e -> e.getKind() == LessonException.Kind.EXTRA
                    && date.equals(e.getLessonDate()));
                LessonException movedIn = find(exceptions, e -> e.getKind() == LessonException.Kind.MOVED
                    && date.equals(e.getMovedTo()));
                if (extra != null || movedIn != null) {
                    out.add(lesson(date, g, slot, "EXTRA", teacher, substitute, null,
                        movedIn != null ? movedIn.getLessonDate() : null, null, now));
                }
            }
        }
        out.sort(Comparator.comparing(AppDtos.Lesson::date)
            .thenComparing(l -> l.startTime() != null ? l.startTime() : "")
            .thenComparing(AppDtos.Lesson::groupId));
        return out;
    }

    private AppDtos.Lesson lesson(LocalDate date, Group g, GroupScheduleService.LessonSlot slot, String status,
                                  String teacher, String substitute, LocalDate movedTo, LocalDate movedFrom,
                                  String note, LocalDateTime now) {
        String start = slot != null ? slot.startTime() : null;
        Long startsIn = null;
        if (start != null && ("PLANNED".equals(status) || "EXTRA".equals(status))) {
            try {
                long minutes = Duration.between(now, date.atTime(LocalTime.parse(start))).toMinutes();
                startsIn = Math.max(0, minutes);
            } catch (DateTimeParseException ignored) {
                // Noto'g'ri vaqt matni — startsInMinutes bo'lmaydi
            }
        }
        return new AppDtos.Lesson(date, g.getId(), g.getGroupName(), start, slot != null ? slot.endTime() : null,
            roomOf(g, slot), status, teacher, substitute, movedTo, movedFrom, note, startsIn);
    }

    /** Shu kunning sloti; jadvaldan tashqari kunda (EXTRA) — birinchi slot ({@code GroupScheduleService.slotOn}). */
    private static GroupScheduleService.LessonSlot slotOn(List<GroupScheduleService.LessonSlot> slots, LocalDate date) {
        return slots.stream().filter(s -> date.getDayOfWeek() == dayOf(s)).findFirst()
            .orElse(slots.isEmpty() ? null : slots.get(0));
    }

    private static boolean inEnrollment(StudentGroup sg, Group g, LocalDate d) {
        if (sg.getJoinDate() != null && d.isBefore(sg.getJoinDate())) {
            return false;
        }
        if (sg.getLeaveDate() != null && d.isAfter(sg.getLeaveDate())) {
            return false;
        }
        if (g.getStartDate() != null && d.isBefore(g.getStartDate())) {
            return false;
        }
        return g.getEndDate() == null || !d.isAfter(g.getEndDate());
    }

    private static LessonException find(List<LessonException> list, java.util.function.Predicate<LessonException> p) {
        return list.stream().filter(p).findFirst().orElse(null);
    }

    private static long key(Long groupId, LocalDate date) {
        return groupId * 100_000L + date.toEpochDay();
    }

    // ── Balans (billing snapshot, faqat o'qish) ─────────────────────────

    private AppDtos.Balance balance(List<StudentGroup> sgs, LocalDate today) {
        if (sgs.isEmpty()) {
            return new AppDtos.Balance(BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null);
        }
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal debt = BigDecimal.ZERO;
        LocalDate debtSince = null;
        List<PaymentStatus> statuses = new ArrayList<>();
        for (StudentGroup sg : sgs) {
            BigDecimal b = nz(sg.getBalance());
            balance = balance.add(b);
            debt = debt.add(debt(b));
            if (sg.getDebtSince() != null && (debtSince == null || sg.getDebtSince().isBefore(debtSince))) {
                debtSince = sg.getDebtSince();
            }
            statuses.add(status(sg, today));
        }
        LocalDate nextDate = sgs.stream().map(StudentGroup::getNextPaymentDate).filter(Objects::nonNull)
            .min(Comparator.naturalOrder()).orElse(null);
        BigDecimal nextAmount = nextDate == null ? null : sgs.stream()
            .filter(sg -> nextDate.equals(sg.getNextPaymentDate()))
            .map(sg -> nz(sg.getNextPaymentAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AppDtos.Balance(balance, debt, aggregate(statuses).name(), debtSince, nextDate, nextAmount);
    }

    private PaymentStatus status(StudentGroup sg, LocalDate today) {
        return billingStatusService.displayStatus(sg, nz(sg.getBalance()), sg.getDebtSince(), today);
    }

    /** Qarz ustun: OVERDUE → PENDING; qarz yo'q — hammasi FROZEN/TRIAL bo'lsa o'sha, aks holda PAID. */
    private static PaymentStatus aggregate(List<PaymentStatus> statuses) {
        if (statuses.contains(PaymentStatus.OVERDUE)) {
            return PaymentStatus.OVERDUE;
        }
        if (statuses.contains(PaymentStatus.PENDING)) {
            return PaymentStatus.PENDING;
        }
        for (PaymentStatus only : List.of(PaymentStatus.FROZEN, PaymentStatus.TRIAL)) {
            if (statuses.stream().allMatch(s -> s == only)) {
                return only;
            }
        }
        return PaymentStatus.PAID;
    }

    private static BigDecimal paidAmount(Payment p) {
        if (p.getPayableAmount() != null) {
            return p.getPayableAmount();
        }
        return nz(p.getAmount()).subtract(nz(p.getDiscountAmount()));
    }

    // ── Davomat yig'indisi ───────────────────────────────────────────────

    private AppDtos.AttendanceSummary attendanceSummary(Long studentId, YearMonth ym) {
        int present = 0, late = 0, absent = 0, excused = 0;
        for (Attendance a : attendanceRepository.findByStudentIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                studentId, ym.atDay(1), ym.atEndOfMonth())) {
            switch (status(a)) {
                case "PRESENT" -> present++;
                case "LATE" -> late++;
                case "EXCUSED" -> excused++;
                default -> absent++;
            }
        }
        int total = present + late + absent + excused;
        return new AppDtos.AttendanceSummary(ym.toString(), present, late, absent, excused, total,
            rate(present + late, total));
    }

    /** EXCUSED holati yoki "sababli" belgisi — EXCUSED; o'qituvchining ichki izohi ({@code notes}) berilmaydi. */
    private static String status(Attendance a) {
        if (a.getStatus() == AttendanceStatus.EXCUSED || Boolean.TRUE.equals(a.getExcused())) {
            return "EXCUSED";
        }
        return a.getStatus().name();
    }

    private static Integer rate(int attended, int total) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(attended * 100L).divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP).intValue();
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(billingClock);
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw CodedException.badRequest("app.month.invalid");
        }
    }

    // ── Kichik yordamchilar ──────────────────────────────────────────────

    private static AppDtos.StudentHeader header(Student s) {
        String initials = (initial(s.getFirstName()) + initial(s.getLastName())).toUpperCase(java.util.Locale.ROOT);
        return new AppDtos.StudentHeader(s.getId(), s.getFirstName(), s.getLastName(), initials);
    }

    private static String initial(String s) {
        return s == null || s.isBlank() ? "" : s.trim().substring(0, 1);
    }

    private static String enrollmentStatus(StudentGroup sg) {
        if (sg.getFrozenFrom() != null) {
            return "FROZEN";
        }
        return Boolean.TRUE.equals(sg.getIsTrial()) ? "TRIAL" : "ACTIVE";
    }

    private static String roomOf(Group g, GroupScheduleService.LessonSlot slot) {
        Classroom room = slot != null && slot.room() != null ? slot.room() : g.getClassroom();
        if (room != null && room.getRoomName() != null) {
            return room.getRoomName();
        }
        return MiniAppAuthService.blankToNull(g.getRoom());
    }

    private static String courseName(Group g) {
        return g.getCourse() != null ? g.getCourse().getCourseName() : null;
    }

    /** O'quvchiga o'qituvchining faqat ismi (telefon, Telegram berilmaydi — §3.5). */
    private static String teacherName(Teacher t) {
        if (t == null) {
            return null;
        }
        return ((t.getFirstName() != null ? t.getFirstName() : "") + " "
            + (t.getLastName() != null ? t.getLastName() : "")).trim();
    }

    private static String fullName(Student s) {
        return (s.getFirstName() + " " + s.getLastName()).trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal debt(BigDecimal balance) {
        return balance.signum() < 0 ? balance.negate() : BigDecimal.ZERO;
    }
}

package com.crm.service;

import com.crm.dto.response.DebtorsListResponse;
import com.crm.dto.response.ExpectedPaymentsResponse;
import com.crm.entity.Group;
import com.crm.entity.Payment;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentScheduleService {

    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final PaymentRepository paymentRepository;

    @Transactional
    public LocalDate recalculateForStudent(Long studentId) {
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null) {
            return null;
        }
        return recalculateAllForStudent(student);
    }

    @Transactional
    public LocalDate recalculateForStudent(Student student) {
        return recalculateAllForStudent(student);
    }

    /**
     * Active StudentGroup larni qayta hisoblaydi va agregatni Student ga yozadi.
     * nextPaymentDate = eng erta sg.nextPaymentDate
     * paymentStatus = eng og'ir status
     * monthlyFee = active guruhlar fee yig'indisi
     * paymentStartDate = eng erta sg.paymentStartDate
     */
    @Transactional
    public LocalDate recalculateAllForStudent(Student student) {
        if (student == null || student.getId() == null) {
            return null;
        }

        studentGroupRepository.flush();
        List<StudentGroup> active = findActiveGroups(student.getId());
        if (active.isEmpty()) {
            return clearOrInferWithoutGroup(student);
        }

        for (StudentGroup sg : active) {
            recalculate(sg);
        }

        applyStudentAggregate(student, active);
        studentRepository.save(student);

        log.info("Aggregate student={} groups={} → status={} next={} fee={}",
            studentFullName(student), active.size(),
            student.getPaymentStatus(), student.getNextPaymentDate(), student.getMonthlyFee());
        return student.getNextPaymentDate();
    }

    /** StudentGroup asosida nextPaymentDate / status / fee yangilash (faqat sg). */
    @Transactional
    public LocalDate recalculate(StudentGroup sg) {
        if (sg == null) {
            return null;
        }
        Student student = sg.getStudent();
        if (student == null) {
            return null;
        }

        ensureGroupFeeAndStartDate(sg);

        LocalDate base = sg.getPaymentStartDate() != null
            ? sg.getPaymentStartDate()
            : resolvePaymentStartDate(student, sg);
        LocalDate next = calculateNextPaymentDate(student, sg);
        if (next == null) {
            next = base != null ? base : LocalDate.now();
        }

        LocalDate lastPeriodEnd = null;
        Payment lastWithPeriod = findLastPaymentForEnrollment(sg, student.getId());
        if (lastWithPeriod != null) {
            lastPeriodEnd = lastWithPeriod.getPeriodEnd();
        } else {
            log.info("recalc NEW sg={} paymentStart={} joinDate={} → next={}",
                sg.getId(), sg.getPaymentStartDate(), sg.getJoinDate(), next);
        }

        PaymentStatus status = resolvePaymentStatus(student, sg, next);

        sg.setNextPaymentDate(next);
        sg.setNextPaymentDue(next);
        sg.setPaymentStatus(status.name());
        studentGroupRepository.save(sg);

        log.info("Recalc sg={} student={} lastPeriodEnd={} → nextPaymentDate={} status={} fee={}",
            sg.getId(), studentFullName(student), lastPeriodEnd, next, status,
            sg.getMonthlyPriceOverride());
        return next;
    }

    private void ensureGroupFeeAndStartDate(StudentGroup sg) {
        if (sg.getMonthlyPriceOverride() == null
                || sg.getMonthlyPriceOverride().compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal fee = resolveMonthlyFeeFromGroup(sg);
            if (fee != null && fee.compareTo(BigDecimal.ZERO) > 0) {
                sg.setMonthlyPriceOverride(fee);
            } else if (sg.getMonthlyPriceOverride() == null) {
                sg.setMonthlyPriceOverride(BigDecimal.ZERO);
            }
        }
        if (sg.getPaymentStartDate() == null) {
            sg.setPaymentStartDate(sg.getJoinDate() != null ? sg.getJoinDate() : LocalDate.now());
        }
    }

    private void applyStudentAggregate(Student student, List<StudentGroup> active) {
        LocalDate earliestNext = null;
        LocalDate earliestStart = null;
        BigDecimal feeSum = BigDecimal.ZERO;
        PaymentStatus worst = null;

        for (StudentGroup sg : active) {
            LocalDate sgNext = sg.getNextPaymentDate();
            if (sgNext == null) {
                sgNext = sg.getPaymentStartDate() != null
                    ? sg.getPaymentStartDate()
                    : sg.getJoinDate();
            }
            if (sgNext != null
                    && (earliestNext == null || sgNext.isBefore(earliestNext))) {
                earliestNext = sgNext;
            }
            if (sg.getPaymentStartDate() != null
                    && (earliestStart == null || sg.getPaymentStartDate().isBefore(earliestStart))) {
                earliestStart = sg.getPaymentStartDate();
            }
            BigDecimal fee = sg.getMonthlyPriceOverride() != null
                ? sg.getMonthlyPriceOverride()
                : resolveMonthlyFee(sg);
            if (fee != null) {
                feeSum = feeSum.add(fee);
            }
            PaymentStatus sgStatus = parsePaymentStatus(sg.getPaymentStatus());
            if (worst == null || severity(sgStatus) > severity(worst)) {
                worst = sgStatus;
            }
        }

        student.setNextPaymentDate(earliestNext);
        student.setPaymentStartDate(earliestStart);
        student.setMonthlyFee(feeSum);
        student.setPaymentStatus(worst != null ? worst : PaymentStatus.PENDING);
    }

    private LocalDate clearOrInferWithoutGroup(Student student) {
        Payment last = paymentRepository
            .findFirstByStudent_IdAndPeriodEndIsNotNullOrderByPeriodEndDesc(student.getId())
            .or(() -> paymentRepository.findFirstByStudent_IdOrderByPaymentDateDesc(student.getId()))
            .orElse(null);
        if (last != null) {
            LocalDate next = last.getPeriodEnd() != null
                ? last.getPeriodEnd().plusDays(1)
                : (last.getPaymentDate() != null
                    ? last.getPaymentDate().plusMonths(1)
                    : LocalDate.now().plusMonths(1));
            student.setNextPaymentDate(next);
            if (student.getPaymentStatus() == null
                    || student.getPaymentStatus() == PaymentStatus.PENDING) {
                student.setPaymentStatus(PaymentStatus.PAID);
            }
            studentRepository.save(student);
            log.info("Recalc sg=null student={} lastPeriodEnd={} → nextPaymentDate={} status={}",
                studentFullName(student), last.getPeriodEnd(), next, student.getPaymentStatus());
            return next;
        }
        student.setNextPaymentDate(null);
        student.setMonthlyFee(null);
        if (student.getPaymentStatus() == null) {
            student.setPaymentStatus(PaymentStatus.PENDING);
        }
        studentRepository.save(student);
        return null;
    }

    private List<StudentGroup> findActiveGroups(Long studentId) {
        return studentGroupRepository.findActiveByStudentId(studentId).stream()
            .sorted(Comparator.comparing(StudentGroup::getJoinDate,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .collect(Collectors.toList());
    }

    private static PaymentStatus parsePaymentStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return PaymentStatus.PENDING;
        }
        try {
            return PaymentStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PaymentStatus.PENDING;
        }
    }

    /** SUSPENDED > OVERDUE > PENDING > TRIAL > PAID */
    private static int severity(PaymentStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case SUSPENDED, ARCHIVED -> 5;
            case OVERDUE -> 4;
            case PENDING, PARTIAL, CANCELLED -> 3;
            case TRIAL -> 2;
            case PAID -> 1;
        };
    }

    @Transactional
    public void clearPaymentSchedule(Long studentId) {
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null) {
            return;
        }
        if (!findActiveGroups(studentId).isEmpty()) {
            recalculateAllForStudent(student);
            return;
        }
        student.setNextPaymentDate(null);
        student.setMonthlyFee(null);
        student.setPaymentStatus(PaymentStatus.PENDING);
        studentRepository.save(student);
    }

    /**
     * Oxirgi to'lov = eng katta periodEnd (shu StudentGroup, yo'qsa student).
     * periodEnd null → paidDate + 1 oy.
     * To'lov yo'q → paymentStartDate / joinDate.
     */
    public LocalDate calculateNextPaymentDate(Student student, StudentGroup enrollment) {
        LocalDate base = resolvePaymentStartDate(student, enrollment);

        Payment last = findLastPaymentForEnrollment(enrollment, student.getId());
        if (last == null) {
            return base;
        }

        if (last.getPeriodEnd() != null) {
            return last.getPeriodEnd().plusDays(1);
        }

        LocalDate payDate = last.getPaymentDate() != null
            ? last.getPaymentDate()
            : (last.getCreatedAt() != null ? last.getCreatedAt().toLocalDate() : base);
        return payDate.plusMonths(1);
    }

    private Payment findLastPaymentForEnrollment(StudentGroup enrollment, Long studentId) {
        if (enrollment != null && enrollment.getId() != null) {
            Payment bySg = paymentRepository
                .findFirstByStudentGroup_IdAndPeriodEndIsNotNullOrderByPeriodEndDesc(enrollment.getId())
                .orElse(null);
            if (bySg != null) {
                return bySg;
            }
            Payment anySg = paymentRepository
                .findFirstByStudentGroup_IdOrderByPaymentDateDesc(enrollment.getId())
                .orElse(null);
            if (anySg != null) {
                return anySg;
            }
        }
        return paymentRepository
            .findFirstByStudent_IdAndPeriodEndIsNotNullOrderByPeriodEndDesc(studentId)
            .or(() -> paymentRepository.findFirstByStudent_IdOrderByPaymentDateDesc(studentId))
            .orElse(null);
    }

    /** Legacy signature — prefers paymentStartDate over joinDate. */
    public LocalDate calculateNextPaymentDate(Long studentId, LocalDate joinDateFallback) {
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null) {
            return joinDateFallback != null ? joinDateFallback : LocalDate.now();
        }
        StudentGroup enrollment = studentGroupRepository
            .findActiveByStudentId(studentId)
            .stream()
            .findFirst()
            .orElse(null);
        if (enrollment == null) {
            LocalDate base = student.getPaymentStartDate() != null
                ? student.getPaymentStartDate() : joinDateFallback;
            return base != null ? base : LocalDate.now();
        }
        return calculateNextPaymentDate(student, enrollment);
    }

    private static String studentFullName(Student student) {
        return (student.getFirstName() != null ? student.getFirstName() : "")
            + " "
            + (student.getLastName() != null ? student.getLastName() : "");
    }

    public PaymentStatus resolvePaymentStatus(Student student, StudentGroup enrollment, LocalDate nextPaymentDate) {
        LocalDate today = LocalDate.now();

        boolean hasPayment = paymentRepository
            .findFirstByStudent_IdOrderByPaymentDateDesc(student.getId())
            .isPresent();

        // To'lov bor → PAID (SUSPENDED/ARCHIVED dan ustun; to'lovdan keyin tiklanadi)
        PaymentStatus status;
        if (hasPayment) {
            status = PaymentStatus.PAID;
        } else {
            String currentSg = enrollment != null ? enrollment.getPaymentStatus() : null;
            PaymentStatus currentStudent = student.getPaymentStatus();
            if ("SUSPENDED".equals(currentSg) || currentStudent == PaymentStatus.SUSPENDED) {
                return PaymentStatus.SUSPENDED;
            }
            if ("ARCHIVED".equals(currentSg) || currentStudent == PaymentStatus.ARCHIVED) {
                return PaymentStatus.ARCHIVED;
            }
            if (enrollment != null && Boolean.TRUE.equals(enrollment.getIsTrial())) {
                status = PaymentStatus.TRIAL;
            } else {
                status = PaymentStatus.PENDING;
            }
        }

        if (nextPaymentDate != null && nextPaymentDate.isBefore(today)
                && status != PaymentStatus.ARCHIVED) {
            status = PaymentStatus.OVERDUE;
        }
        return status;
    }

    public static LocalDate resolvePaymentStartDate(Student student, StudentGroup enrollment) {
        if (enrollment != null && enrollment.getPaymentStartDate() != null) {
            return enrollment.getPaymentStartDate();
        }
        if (enrollment != null && enrollment.getJoinDate() != null) {
            return enrollment.getJoinDate();
        }
        if (student != null && student.getPaymentStartDate() != null) {
            return student.getPaymentStartDate();
        }
        return LocalDate.now();
    }

    public static LocalDate addMonthsKeepingDay(LocalDate from, int months, int preferredDay) {
        YearMonth ym = YearMonth.from(from).plusMonths(months);
        int day = Math.min(Math.max(preferredDay, 1), ym.lengthOfMonth());
        return ym.atDay(day);
    }

    public static BigDecimal resolveMonthlyFee(StudentGroup sg) {
        if (sg.getMonthlyPriceOverride() != null
                && sg.getMonthlyPriceOverride().compareTo(BigDecimal.ZERO) > 0) {
            return sg.getMonthlyPriceOverride();
        }
        BigDecimal fromGroup = resolveMonthlyFeeFromGroup(sg);
        if (fromGroup != null && fromGroup.compareTo(BigDecimal.ZERO) > 0) {
            return fromGroup;
        }
        if (sg.getStudent() != null && sg.getStudent().getMonthlyFee() != null) {
            return sg.getStudent().getMonthlyFee();
        }
        return BigDecimal.ZERO;
    }

    /** Guruh/kurs narxi (StudentGroup.monthlyPriceOverride to'ldirish uchun). */
    public static BigDecimal resolveMonthlyFeeFromGroup(StudentGroup sg) {
        Group g = sg.getGroup();
        if (g != null && g.getCourse() != null && g.getCourse().getMonthlyPrice() != null) {
            return g.getCourse().getMonthlyPrice();
        }
        return BigDecimal.ZERO;
    }

    @Transactional
    public void updatePaymentStartDate(Long studentId, LocalDate paymentStartDate, Boolean isTrial) {
        Student student = studentRepository.findById(studentId)
            .orElseThrow(() -> new com.crm.exception.ResourceNotFoundException("Student", studentId));
        student.setPaymentStartDate(paymentStartDate);

        StudentGroup enrollment = studentGroupRepository
            .findActiveByStudentId(studentId)
            .stream()
            .max(Comparator.comparing(StudentGroup::getJoinDate,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .orElse(null);

        if (enrollment != null) {
            enrollment.setPaymentStartDate(paymentStartDate);
            if (isTrial != null) {
                enrollment.setIsTrial(isTrial);
            }
            studentGroupRepository.save(enrollment);
        }

        studentRepository.save(student);
        recalculateForStudent(student);
    }

    @Transactional(readOnly = true)
    public ExpectedPaymentsResponse getExpectedPayments(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : YearMonth.from(LocalDate.now()).atEndOfMonth();
        LocalDate today = LocalDate.now();

        Specification<Student> spec = (root, query, cb) -> {
            query.distinct(true);
            var sgJoin = root.join("studentGroups");
            return cb.and(
                cb.isTrue(sgJoin.get("isActive")),
                cb.isNull(sgJoin.get("leaveDate")),
                cb.equal(root.get("status"), StudentStatus.ACTIVE),
                cb.isNotNull(root.get("nextPaymentDate")),
                cb.greaterThanOrEqualTo(root.get("nextPaymentDate"), start),
                cb.lessThanOrEqualTo(root.get("nextPaymentDate"), end)
            );
        };

        List<Student> students = studentRepository.findAll(spec);
        Map<LocalDate, List<ExpectedPaymentsResponse.ExpectedStudent>> byDate = new LinkedHashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Student s : students) {
            StudentGroup sg = studentGroupRepository.findActiveByStudentId(s.getId())
                .stream().findFirst().orElse(null);
            LocalDate due = s.getNextPaymentDate();
            if (due == null) {
                continue;
            }
            BigDecimal amount = s.getMonthlyFee() != null
                ? s.getMonthlyFee()
                : (sg != null ? resolveMonthlyFee(sg) : BigDecimal.ZERO);
            long daysUntil = ChronoUnit.DAYS.between(today, due);
            String status = daysUntil < 0 ? "OVERDUE" : "PENDING";

            ExpectedPaymentsResponse.ExpectedStudent row =
                ExpectedPaymentsResponse.ExpectedStudent.builder()
                    .studentId(s.getId())
                    .fullName(s.getFirstName() + " " + s.getLastName())
                    .phone(s.getPhone())
                    .groupName(sg != null && sg.getGroup() != null
                        ? sg.getGroup().getGroupName() : null)
                    .amount(amount)
                    .paymentStatus(status)
                    .daysUntil(daysUntil)
                    .build();

            byDate.computeIfAbsent(due, d -> new ArrayList<>()).add(row);
            totalAmount = totalAmount.add(amount != null ? amount : BigDecimal.ZERO);
        }

        List<ExpectedPaymentsResponse.DayBucket> days = byDate.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> {
                BigDecimal dayTotal = e.getValue().stream()
                    .map(ExpectedPaymentsResponse.ExpectedStudent::getAmount)
                    .filter(a -> a != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                return ExpectedPaymentsResponse.DayBucket.builder()
                    .date(e.getKey())
                    .studentCount(e.getValue().size())
                    .dayTotal(dayTotal)
                    .students(e.getValue())
                    .build();
            })
            .collect(Collectors.toList());

        return ExpectedPaymentsResponse.builder()
            .totalStudents(students.size())
            .totalAmount(totalAmount)
            .days(days)
            .build();
    }

    @Transactional(readOnly = true)
    public DebtorsListResponse getDebtorsByDate() {
        LocalDate today = LocalDate.now();

        Specification<Student> spec = (root, query, cb) -> {
            query.distinct(true);
            var sgJoin = root.join("studentGroups");
            return cb.and(
                cb.isTrue(sgJoin.get("isActive")),
                cb.isNull(sgJoin.get("leaveDate")),
                cb.equal(root.get("status"), StudentStatus.ACTIVE),
                cb.isNotNull(root.get("nextPaymentDate")),
                cb.lessThan(root.get("nextPaymentDate"), today)
            );
        };

        List<Student> students = studentRepository.findAll(spec);
        List<DebtorsListResponse.DebtorStudent> rows = new ArrayList<>();
        BigDecimal totalDebt = BigDecimal.ZERO;
        long overdue7Plus = 0;

        for (Student s : students) {
            StudentGroup sg = studentGroupRepository.findActiveByStudentId(s.getId())
                .stream().findFirst().orElse(null);
            LocalDate due = s.getNextPaymentDate();
            if (due == null) {
                continue;
            }
            long daysOverdue = ChronoUnit.DAYS.between(due, today);
            if (daysOverdue < 1) {
                continue;
            }
            if (daysOverdue >= 7) {
                overdue7Plus++;
            }

            BigDecimal amount = s.getMonthlyFee() != null
                ? s.getMonthlyFee()
                : (sg != null ? resolveMonthlyFee(sg) : BigDecimal.ZERO);
            if (amount == null) {
                amount = BigDecimal.ZERO;
            }

            long monthsUnpaid = ChronoUnit.MONTHS.between(
                due.withDayOfMonth(1), today.withDayOfMonth(1));
            if (monthsUnpaid < 1) {
                monthsUnpaid = 1;
            }
            BigDecimal studentDebt = amount.multiply(BigDecimal.valueOf(monthsUnpaid));
            totalDebt = totalDebt.add(studentDebt);

            rows.add(DebtorsListResponse.DebtorStudent.builder()
                .studentId(s.getId())
                .fullName(s.getFirstName() + " " + s.getLastName())
                .phone(s.getPhone())
                .groupName(sg != null && sg.getGroup() != null
                    ? sg.getGroup().getGroupName() : null)
                .nextPaymentDate(due)
                .daysOverdue(daysOverdue)
                .amount(amount)
                .monthsUnpaid(monthsUnpaid)
                .totalDebt(studentDebt)
                .build());
        }

        rows.sort(Comparator.comparingLong(DebtorsListResponse.DebtorStudent::getDaysOverdue).reversed());

        return DebtorsListResponse.builder()
            .totalDebtors(rows.size())
            .overdue7Plus(overdue7Plus)
            .totalDebt(totalDebt)
            .students(rows)
            .build();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDebtorsSummary() {
        DebtorsListResponse report = getDebtorsByDate();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDebtors", report.getTotalDebtors());
        summary.put("overdue7Plus", report.getOverdue7Plus());
        summary.put("totalDebt", report.getTotalDebt());
        return summary;
    }

    @Transactional
    public Map<String, Integer> fixPaymentPeriods() {
        int paymentsFixed = fixMissingPaymentPeriodsInternal();

        int groupsRecalculated = 0;
        java.util.LinkedHashSet<Long> studentIds = new java.util.LinkedHashSet<>();
        List<StudentGroup> enrollments = studentGroupRepository.findAllActiveEnrollments();
        for (StudentGroup sg : enrollments) {
            if (sg.getStudent() == null) {
                continue;
            }
            ensureGroupFeeAndStartDate(sg);
            recalculate(sg);
            studentIds.add(sg.getStudent().getId());
            groupsRecalculated++;
        }
        for (Long studentId : studentIds) {
            Student student = studentRepository.findById(studentId).orElse(null);
            if (student != null) {
                recalculateAllForStudent(student);
            }
        }

        return Map.of(
            "paymentsFixed", paymentsFixed,
            "groupsRecalculated", groupsRecalculated
        );
    }

    @Transactional
    public Map<String, Object> recalculateAllActiveStudents() {
        logActiveFlagInconsistencies();

        int studentsProcessed = 0;
        int groupsRecalculated = 0;
        int aggregatesUpdated = 0;
        int paymentsFixed = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        // Avval periodStart/periodEnd bo'sh to'lovlarni to'ldir
        paymentsFixed = fixMissingPaymentPeriodsInternal();

        List<StudentStatus> statuses = List.of(
            StudentStatus.ACTIVE, StudentStatus.SUSPENDED, StudentStatus.FROZEN);
        List<Student> students = studentRepository.findByStatusIn(statuses);

        for (Student student : students) {
            List<StudentGroup> active = findActiveGroups(student.getId());
            int groupCount = active.size();

            for (StudentGroup sg : active) {
                ensureGroupFeeAndStartDate(sg);
                ensurePaymentPeriodsForGroup(sg);
                recalculate(sg);
                groupsRecalculated++;
            }

            LocalDate beforeNext = student.getNextPaymentDate();
            PaymentStatus beforeStatus = student.getPaymentStatus();
            BigDecimal beforeFee = student.getMonthlyFee();

            if (active.isEmpty()) {
                clearOrInferWithoutGroup(student);
            } else {
                applyStudentAggregate(student, active);
                studentRepository.save(student);
            }
            studentsProcessed++;

            boolean changed = !java.util.Objects.equals(beforeNext, student.getNextPaymentDate())
                || beforeStatus != student.getPaymentStatus()
                || !java.util.Objects.equals(beforeFee, student.getMonthlyFee());
            if (changed) {
                aggregatesUpdated++;
            }

            log.info("Repair student={} groups={} → status={} next={} fee={}",
                studentFullName(student), groupCount,
                student.getPaymentStatus(), student.getNextPaymentDate(), student.getMonthlyFee());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studentId", student.getId());
            row.put("name", studentFullName(student).trim());
            row.put("groups", groupCount);
            row.put("paymentStatus", student.getPaymentStatus() != null
                ? student.getPaymentStatus().name() : null);
            row.put("nextPaymentDate", student.getNextPaymentDate());
            row.put("monthlyFee", student.getMonthlyFee());
            row.put("changed", changed);
            details.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentsProcessed", studentsProcessed);
        result.put("groupsRecalculated", groupsRecalculated);
        result.put("aggregatesUpdated", aggregatesUpdated);
        result.put("paymentsFixed", paymentsFixed);
        result.put("inconsistentActiveWithLeaveDate",
            studentGroupRepository.countActiveFlagButHasLeaveDate());
        result.put("inconsistentInactiveWithoutLeaveDate",
            studentGroupRepository.countInactiveFlagButNoLeaveDate());
        result.put("details", details);
        return result;
    }

    /**
     * Diagnostika: isActive va leaveDate nomuvofiq yozuvlar sonini log qiladi.
     * active = isActive=true AND leaveDate IS NULL — ikkalasi mos bo'lishi kerak.
     */
    public void logActiveFlagInconsistencies() {
        long activeButLeft = studentGroupRepository.countActiveFlagButHasLeaveDate();
        long inactiveNoLeave = studentGroupRepository.countInactiveFlagButNoLeaveDate();
        if (activeButLeft > 0 || inactiveNoLeave > 0) {
            log.warn("StudentGroup active-flag inconsistency: "
                    + "isActive=true & leaveDate!=null → {}, "
                    + "isActive=false & leaveDate=null → {} "
                    + "(repair tavsiya etiladi)",
                activeButLeft, inactiveNoLeave);
        } else {
            log.info("StudentGroup active-flag check OK: no inconsistencies");
        }
    }

    private int fixMissingPaymentPeriodsInternal() {
        List<Payment> missing = paymentRepository.findWithMissingPeriods();
        int paymentsFixed = 0;
        for (Payment p : missing) {
            fillPaymentPeriod(p);
            paymentRepository.save(p);
            paymentsFixed++;
        }
        return paymentsFixed;
    }

    private void ensurePaymentPeriodsForGroup(StudentGroup sg) {
        if (sg.getId() == null || sg.getStudent() == null) {
            return;
        }
        List<Payment> payments = paymentRepository
            .findByStudentIdOrderByPaymentDateDesc(sg.getStudent().getId());
        for (Payment p : payments) {
            boolean matchesGroup = p.getStudentGroup() == null
                || (p.getStudentGroup().getId() != null
                    && p.getStudentGroup().getId().equals(sg.getId()))
                || (p.getGroup() != null && sg.getGroup() != null
                    && p.getGroup().getId().equals(sg.getGroup().getId()));
            if (!matchesGroup) {
                continue;
            }
            if (p.getPeriodStart() == null || p.getPeriodEnd() == null) {
                if (p.getStudentGroup() == null) {
                    p.setStudentGroup(sg);
                }
                fillPaymentPeriod(p);
                paymentRepository.save(p);
            }
        }
    }

    private void fillPaymentPeriod(Payment p) {
        LocalDate periodStart = p.getPeriodStart();
        LocalDate periodEnd = p.getPeriodEnd();

        if (periodStart == null) {
            periodStart = p.getPaymentDate() != null
                ? p.getPaymentDate()
                : (p.getCreatedAt() != null
                    ? p.getCreatedAt().toLocalDate()
                    : LocalDate.now());
        }

        if (periodEnd == null) {
            BigDecimal fee = BigDecimal.ZERO;
            StudentGroup sg = p.getStudentGroup();
            if (sg == null && p.getStudent() != null) {
                sg = findActiveGroups(p.getStudent().getId()).stream().findFirst().orElse(null);
            }
            if (sg != null) {
                ensureGroupFeeAndStartDate(sg);
                fee = resolveMonthlyFee(sg);
            } else if (p.getStudent() != null && p.getStudent().getMonthlyFee() != null) {
                fee = p.getStudent().getMonthlyFee();
            }

            int months = 1;
            if (fee != null && fee.compareTo(BigDecimal.ZERO) > 0 && p.getAmount() != null) {
                months = p.getAmount().divide(fee, 0, java.math.RoundingMode.DOWN).intValue();
                if (months < 1) {
                    months = 1;
                }
            }
            periodEnd = periodStart.plusMonths(months).minusDays(1);
        }

        p.setPeriodStart(periodStart);
        p.setPeriodEnd(periodEnd);
    }

    /** Har kuni 00:05 — sana asosida recalculate (yagona status manbasi). */
    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void updateOverdueStatusesDaily() {
        LocalDate today = LocalDate.now();
        log.info("Daily payment recalculate (date-based) for {}", today);

        List<Student> students = studentRepository.findByStatusIn(
            List.of(StudentStatus.ACTIVE, StudentStatus.SUSPENDED, StudentStatus.FROZEN));
        int processed = 0;
        for (Student student : students) {
            recalculateAllForStudent(student);
            processed++;
        }
        log.info("Daily payment recalculate done: {} students", processed);
    }
}

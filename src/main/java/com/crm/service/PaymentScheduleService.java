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
        return recalculateForStudent(student);
    }

    @Transactional
    public LocalDate recalculateForStudent(Student student) {
        StudentGroup enrollment = studentGroupRepository
            .findByStudentIdAndIsActiveTrue(student.getId())
            .stream()
            .max(Comparator.comparing(StudentGroup::getJoinDate,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .orElse(null);

        if (enrollment == null) {
            // PAID bo'lsa ham guruh yo'q — next ni to'lovdan hisobla
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
                if (student.getPaymentStatus() == null) {
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

        return recalculate(enrollment);
    }

    /** StudentGroup asosida nextPaymentDate / status yangilash. */
    @Transactional
    public LocalDate recalculate(StudentGroup sg) {
        if (sg == null) {
            return null;
        }
        Student student = sg.getStudent();
        if (student == null) {
            return null;
        }

        if (student.getPaymentStatus() == null) {
            student.setPaymentStatus(PaymentStatus.PENDING);
        }

        BigDecimal fee = student.getMonthlyFee() != null
            ? student.getMonthlyFee()
            : resolveMonthlyFee(sg);
        if (fee == null) {
            fee = BigDecimal.ZERO;
        }
        student.setMonthlyFee(fee);

        LocalDate base = resolvePaymentStartDate(student, sg);
        if (student.getPaymentStartDate() == null) {
            student.setPaymentStartDate(base);
        }
        if (sg.getPaymentStartDate() == null) {
            sg.setPaymentStartDate(base);
        }

        LocalDate lastPeriodEnd = null;
        LocalDate next = calculateNextPaymentDate(student, sg);
        // Guard: PAID bo'lsa next hech qachon NULL bo'lmasin
        if (next == null) {
            next = base != null ? base : LocalDate.now();
        }

        Payment lastWithPeriod = findLastPaymentForEnrollment(sg, student.getId());
        if (lastWithPeriod != null) {
            lastPeriodEnd = lastWithPeriod.getPeriodEnd();
        }

        PaymentStatus status = resolvePaymentStatus(student, sg, next);

        student.setNextPaymentDate(next);
        student.setPaymentStatus(status);
        sg.setNextPaymentDate(next);
        sg.setNextPaymentDue(next);
        sg.setPaymentStatus(status.name());
        studentGroupRepository.save(sg);
        studentRepository.save(student);

        log.info("Recalc sg={} student={} lastPeriodEnd={} → nextPaymentDate={} status={}",
            sg.getId(), studentFullName(student), lastPeriodEnd, next, status);
        return next;
    }

    @Transactional
    public void clearPaymentSchedule(Long studentId) {
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null) {
            return;
        }
        if (!studentGroupRepository.findByStudentIdAndIsActiveTrue(studentId).isEmpty()) {
            recalculateForStudent(student);
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
            .findByStudentIdAndIsActiveTrue(studentId)
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
        if (student.getPaymentStartDate() != null) {
            return student.getPaymentStartDate();
        }
        if (enrollment != null && enrollment.getPaymentStartDate() != null) {
            return enrollment.getPaymentStartDate();
        }
        if (enrollment != null && enrollment.getJoinDate() != null) {
            return enrollment.getJoinDate();
        }
        return LocalDate.now();
    }

    public static LocalDate addMonthsKeepingDay(LocalDate from, int months, int preferredDay) {
        YearMonth ym = YearMonth.from(from).plusMonths(months);
        int day = Math.min(Math.max(preferredDay, 1), ym.lengthOfMonth());
        return ym.atDay(day);
    }

    public static BigDecimal resolveMonthlyFee(StudentGroup sg) {
        if (sg.getMonthlyPriceOverride() != null) {
            return sg.getMonthlyPriceOverride();
        }
        if (sg.getStudent() != null && sg.getStudent().getMonthlyFee() != null) {
            return sg.getStudent().getMonthlyFee();
        }
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
            .findByStudentIdAndIsActiveTrue(studentId)
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
            StudentGroup sg = studentGroupRepository.findByStudentIdAndIsActiveTrue(s.getId())
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
            StudentGroup sg = studentGroupRepository.findByStudentIdAndIsActiveTrue(s.getId())
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
        List<Payment> missing = paymentRepository.findWithMissingPeriods();
        int paymentsFixed = 0;

        for (Payment p : missing) {
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
                    sg = studentGroupRepository.findByStudentIdAndIsActiveTrue(p.getStudent().getId())
                        .stream().findFirst().orElse(null);
                }
                if (sg != null) {
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
            paymentRepository.save(p);
            paymentsFixed++;
        }

        int groupsRecalculated = 0;
        List<StudentGroup> enrollments = studentGroupRepository.findAllActiveEnrollments();
        for (StudentGroup sg : enrollments) {
            if (sg.getStudent() == null) {
                continue;
            }
            recalculate(sg);
            groupsRecalculated++;
        }

        return Map.of(
            "paymentsFixed", paymentsFixed,
            "groupsRecalculated", groupsRecalculated
        );
    }

    @Transactional
    public Map<String, Integer> recalculateAllActiveStudents() {
        int updated = 0;
        int skipped = 0;
        int statusFilled = 0;

        // Fill NULL paymentStatus for all students (guruhsiz ham)
        List<Student> allStudents = studentRepository.findAll();
        for (Student s : allStudents) {
            boolean dirty = false;
            if (s.getPaymentStatus() == null) {
                s.setPaymentStatus(PaymentStatus.PENDING);
                dirty = true;
                statusFilled++;
            }
            if (dirty) {
                studentRepository.save(s);
            }
        }

        List<StudentGroup> enrollments = studentGroupRepository.findAllActiveEnrollments();
        for (StudentGroup sg : enrollments) {
            if (sg.getStudent() == null) {
                skipped++;
                continue;
            }
            Student student = sg.getStudent();
            if (student.getMonthlyFee() == null) {
                student.setMonthlyFee(resolveMonthlyFee(sg));
            }
            if (student.getPaymentStartDate() == null) {
                student.setPaymentStartDate(resolvePaymentStartDate(student, sg));
            }
            recalculate(sg);
            updated++;
        }
        return Map.of(
            "updated", updated,
            "skipped", skipped,
            "statusFilled", statusFilled
        );
    }

    /** Har kuni 00:05 — faqat paymentStatus yangilanadi; ro'yxatlar sanaga tayanadi. */
    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void updateOverdueStatusesDaily() {
        LocalDate today = LocalDate.now();
        log.info("Daily payment status update for nextPaymentDate < {}", today);

        List<StudentGroup> active = studentGroupRepository.findAllActiveEnrollments();
        for (StudentGroup sg : active) {
            Student student = sg.getStudent();
            LocalDate due = student != null && student.getNextPaymentDate() != null
                ? student.getNextPaymentDate()
                : sg.getNextPaymentDate();
            if (due == null || !due.isBefore(today)) {
                continue;
            }
            long monthsOverdue = ChronoUnit.MONTHS.between(
                due.withDayOfMonth(1), today.withDayOfMonth(1));
            if (monthsOverdue >= 3) {
                if (!"SUSPENDED".equals(sg.getPaymentStatus())) {
                    sg.setPaymentStatus(PaymentStatus.SUSPENDED.name());
                    sg.setSuspendedAt(java.time.LocalDateTime.now());
                    sg.setSuspensionReason("3+ oy to'lovsiz (auto)");
                    studentGroupRepository.save(sg);
                }
                if (student != null && student.getPaymentStatus() != PaymentStatus.SUSPENDED) {
                    student.setPaymentStatus(PaymentStatus.SUSPENDED);
                    studentRepository.save(student);
                }
            } else if (!"SUSPENDED".equals(sg.getPaymentStatus())
                    && !"OVERDUE".equals(sg.getPaymentStatus())) {
                sg.setPaymentStatus(PaymentStatus.OVERDUE.name());
                studentGroupRepository.save(sg);
                if (student != null && student.getPaymentStatus() != PaymentStatus.SUSPENDED) {
                    student.setPaymentStatus(PaymentStatus.OVERDUE);
                    studentRepository.save(student);
                }
            }
        }
    }
}

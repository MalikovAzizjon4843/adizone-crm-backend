package com.crm.billing;

import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Saqlanadigan snapshot — docs/design/billing-v2.md §4.4, §8.
 *
 * <p>I7: {@code paymentStatus}, {@code nextPaymentDate}, {@code debtSince} FAQAT shu
 * yerda yoziladi (arxitektura testi). Dashboard va ro'yxatlar har so'rovda FIFO
 * hisoblamasligi uchun natija SG va o'quvchida saqlanadi; u har ledger amalidan
 * keyin o'sha tranzaksiyada va kunlik job'da yangilanadi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingSnapshotService {

    private final BillingStatusService statusService;
    private final StudentGroupRepository studentGroupRepository;
    private final StudentRepository studentRepository;
    private final TransactionTemplate transactionTemplate;

    /** SG snapshot'i + o'quvchi agregati. Ledger amalidan keyin chaqiriladi. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BillingSnapshot refresh(StudentGroup sg) {
        BillingSnapshot snapshot = writeEnrollment(sg, statusService.today());
        refreshStudent(sg.getStudent());
        return snapshot;
    }

    /** O'quvchining barcha SG lari va agregati. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void refreshStudentFully(Long studentId) {
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null) {
            return;
        }
        LocalDate today = statusService.today();
        for (StudentGroup sg : studentGroupRepository.findByStudentId(studentId)) {
            writeEnrollment(sg, today);
        }
        refreshStudent(student);
    }

    private BillingSnapshot writeEnrollment(StudentGroup sg, LocalDate today) {
        BillingSnapshot s = statusService.snapshot(sg, today);
        sg.setDebtSince(s.debtSince());
        sg.setNextPaymentDate(s.nextPaymentDate());
        sg.setNextPaymentAmount(s.nextPaymentAmount());
        sg.setPaymentStatus(s.status());
        studentGroupRepository.save(sg);
        return s;
    }

    /**
     * O'quvchi agregati (§8). {@code balance} ni LedgerService yozadi (I2), bu yerda
     * qolganlari. Kesishuv yo'q: bir SG ning musbat balansi boshqasining qarzini
     * yopmaydi — {@code balance ≥ 0} bo'lsa ham {@code debt > 0} bo'lishi mumkin.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshStudent(Student student) {
        if (student == null || student.getId() == null) {
            return;
        }
        LocalDate today = statusService.today();
        List<StudentGroup> all = studentGroupRepository.findByStudentId(student.getId());

        BigDecimal debt = BigDecimal.ZERO;
        BigDecimal monthlyFee = BigDecimal.ZERO;
        LocalDate next = null;
        boolean anyOverdue = false;
        boolean anyPending = false;
        int open = 0;
        int frozen = 0;
        int trial = 0;

        for (StudentGroup sg : all) {
            BigDecimal b = Money.nz(sg.getBalance());
            if (b.signum() < 0) {
                debt = debt.add(b.negate());
            }
            PaymentStatus money = statusService.statusOf(b, sg.getDebtSince(), today);
            anyOverdue |= money == PaymentStatus.OVERDUE;
            anyPending |= money == PaymentStatus.PENDING;

            if (!BillingStatusService.isOpen(sg)) {
                continue;
            }
            open++;
            if (sg.getFrozenFrom() != null) {
                frozen++;
                continue;
            }
            if (Boolean.TRUE.equals(sg.getIsTrial())) {
                trial++;
                continue;
            }
            if (sg.getPaymentType() != PaymentType.PER_LESSON) {
                monthlyFee = monthlyFee.add(EnrollmentPricing.effectiveMonthlyFee(sg));
            }
            if (sg.getNextPaymentDate() != null && (next == null || sg.getNextPaymentDate().isBefore(next))) {
                next = sg.getNextPaymentDate();
            }
        }

        BigDecimal nextAmount = null;
        if (next != null) {
            nextAmount = BigDecimal.ZERO;
            for (StudentGroup sg : all) {
                if (BillingStatusService.isBillingOpen(sg) && Objects.equals(sg.getNextPaymentDate(), next)) {
                    nextAmount = nextAmount.add(Money.nz(sg.getNextPaymentAmount()));
                }
            }
        }

        boolean allFrozen = open > 0 && frozen == open;
        PaymentStatus status;
        if (anyOverdue) {
            status = PaymentStatus.OVERDUE;
        } else if (anyPending) {
            status = PaymentStatus.PENDING;
        } else if (allFrozen) {
            status = PaymentStatus.FROZEN;
        } else if (open > 0 && trial == open - frozen && trial > 0) {
            status = PaymentStatus.TRIAL;
        } else {
            status = PaymentStatus.PAID;
        }

        student.setDebt(debt);
        student.setMonthlyFee(monthlyFee);
        student.setNextPaymentDate(next);
        student.setNextPaymentAmount(nextAmount);
        student.setPaymentStatus(status);
        // student.status: faqat ACTIVE ↔ FROZEN (LEFT/GRADUATED ga tegilmaydi)
        if (allFrozen && student.getStatus() == StudentStatus.ACTIVE) {
            student.setStatus(StudentStatus.FROZEN);
        } else if (!allFrozen && open > frozen && student.getStatus() == StudentStatus.FROZEN) {
            student.setStatus(StudentStatus.ACTIVE);
        }
        studentRepository.save(student);
    }

    /**
     * Kunlik: vaqt o'tishi bilan PENDING → OVERDUE (§3.7). Holat faqat qarzli SG da
     * vaqtga bog'liq, shuning uchun faqat shular va ularning o'quvchilari.
     * Har o'quvchi alohida tranzaksiyada.
     */
    public int refreshAllDue(LocalDate today) {
        Set<Long> studentIds = new LinkedHashSet<>(studentGroupRepository.findStudentIdsForDailyRefresh());
        int done = 0;
        for (Long id : studentIds) {
            try {
                transactionTemplate.executeWithoutResult(s -> refreshStudentFully(id));
                done++;
            } catch (RuntimeException e) {
                log.error("Snapshot yangilanmadi student={}: {}", id, e.getMessage(), e);
            }
        }
        return done;
    }
}

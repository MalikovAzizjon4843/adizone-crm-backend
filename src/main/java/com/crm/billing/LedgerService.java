package com.crm.billing;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.User;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.TeacherAttribution;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Ledger ga YAGONA kirish nuqtasi — docs/design/billing-v2.md §1.2 I2, I3, I8.
 *
 * <ul>
 *   <li>{@code setBalance(...)} faqat shu klassda chaqiriladi (arxitektura testi).</li>
 *   <li>Yozuv faqat INSERT; xato teskari yozuv ({@link #reverse}) bilan tuzatiladi.</li>
 *   <li>Summa butun so'm, nol emas, belgisi turga mos ({@link BalanceTransactionType#sign()}).</li>
 * </ul>
 *
 * <p>Chaqiruvchi SG ni {@link BillingLocks} bilan qulflagan bo'lishi kerak. Shunga
 * qaramay "oldingi balans" keshdan emas, ledger yig'indisidan olinadi: SG
 * tranzaksiyada qulfsiz o'qilgan bo'lsa ham kesh xatosi ledgerga o'tmaydi (I1).
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final BalanceTransactionRepository balanceTransactionRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final Clock billingClock;

    /** Bitta ledger yozuvi. */
    @Getter
    @Builder
    public static class Entry {
        private final StudentGroup enrollment;
        private final BalanceTransactionType type;
        private final BigDecimal amount;
        private final LocalDate effectiveDate;
        private final Long referenceId;
        private final Long relatedTxId;
        private final Long billingPeriodId;
        private final Long migrationRunId;
        /** Payroll v2 (§8): faqat LESSON_CHARGE — dars paytidagi o'qituvchi. */
        private final Long teacherId;
        private final String note;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BalanceTransaction post(Entry e) {
        StudentGroup sg = e.getEnrollment();
        if (sg == null || sg.getId() == null) {
            throw new IllegalStateException("Ledger: yozilma (StudentGroup) majburiy");
        }
        Student student = sg.getStudent();
        if (student == null || student.getId() == null) {
            throw new IllegalStateException("Ledger: o'quvchi topilmadi (sg=" + sg.getId() + ")");
        }
        BalanceTransactionType type = e.getType();
        BigDecimal amount = e.getAmount();
        validate(type, amount, e.getRelatedTxId());

        BigDecimal before = balanceTransactionRepository.sumAmountByStudentGroupId(sg.getId());
        BigDecimal after = Money.nz(before).add(amount);

        sg.setBalance(after);
        studentGroupRepository.save(sg);

        LocalDate effective = e.getEffectiveDate() != null
            ? e.getEffectiveDate() : LocalDate.now(billingClock);

        BalanceTransaction tx = BalanceTransaction.builder()
            .studentGroup(sg)
            .student(student)
            .type(type)
            .amount(amount)
            .balanceAfter(after)
            .referenceId(e.getReferenceId())
            .relatedTxId(e.getRelatedTxId())
            .billingPeriodId(e.getBillingPeriodId())
            .migrationRunId(e.getMigrationRunId())
            .teacherId(e.getTeacherId())
            .teacherSource(e.getTeacherId() != null ? TeacherAttribution.LIVE : null)
            .effectiveDate(effective)
            .note(e.getNote())
            .createdBy(currentUserOrNull())
            .createdAt(LocalDateTime.now(billingClock))
            .build();
        BalanceTransaction saved = balanceTransactionRepository.save(tx);

        // I1: student.balance = Σ sg.balance (yopilgan SG lar ham)
        student.setBalance(studentGroupRepository.sumBalanceByStudentId(student.getId()));
        studentRepository.save(student);
        return saved;
    }

    /**
     * Teskari yozuv (I3): summa — aslining teskarisi, {@code effective_date} —
     * aslining sanasi (FIFO da qarz asl majburiyat sanasidan qaytadi, §4.1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BalanceTransaction reverse(BalanceTransaction original, String note) {
        if (original.getType() == BalanceTransactionType.REVERSAL) {
            throw new IllegalStateException("REVERSAL ni qayta bekor qilib bo'lmaydi");
        }
        boolean alreadyReversed = balanceTransactionRepository.findByRelatedTxId(original.getId()).stream()
            .anyMatch(t -> t.getType() == BalanceTransactionType.REVERSAL);
        if (alreadyReversed) {
            throw new IllegalStateException("Yozuv allaqachon bekor qilingan: " + original.getId());
        }
        return post(Entry.builder()
            .enrollment(original.getStudentGroup())
            .type(BalanceTransactionType.REVERSAL)
            .amount(original.getAmount().negate())
            .effectiveDate(original.getEffectiveDate())
            .referenceId(original.getReferenceId())
            .relatedTxId(original.getId())
            .billingPeriodId(original.getBillingPeriodId())
            .note(note)
            .build());
    }

    /**
     * I1 ni ledger yozuvisiz tiklash: SG yopilganda/ochilganda student agregati
     * o'zgarmaydi, lekin eski ma'lumotda farq bo'lsa (A10) shu bilan tekislanadi.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void syncStudentBalance(Student student) {
        student.setBalance(studentGroupRepository.sumBalanceByStudentId(student.getId()));
        studentRepository.save(student);
    }

    private void validate(BalanceTransactionType type, BigDecimal amount, Long relatedTxId) {
        if (type == null) {
            throw new IllegalStateException("Ledger: tur majburiy");
        }
        if (amount == null || amount.signum() == 0) {
            throw new IllegalStateException("Ledger: summa nol bo'lishi mumkin emas (" + type + ")");
        }
        if (!Money.isWhole(amount)) {
            // I8 — foydalanuvchi summalari oldinroq 400 bilan rad etiladi; bu yerga
            // yetib kelgan kasr dasturdagi xato.
            throw new IllegalStateException("Ledger: summa butun so'mda bo'lishi kerak: " + amount);
        }
        switch (type.sign()) {
            case POSITIVE -> {
                if (amount.signum() < 0) {
                    throw new IllegalStateException(type + " musbat bo'lishi kerak: " + amount);
                }
            }
            case NEGATIVE -> {
                if (amount.signum() > 0) {
                    throw new IllegalStateException(type + " manfiy bo'lishi kerak: " + amount);
                }
            }
            case OPPOSITE_OF_RELATED -> {
                if (relatedTxId == null) {
                    throw new IllegalStateException("REVERSAL uchun asl yozuv (relatedTxId) majburiy");
                }
                BalanceTransaction original = balanceTransactionRepository.findById(relatedTxId)
                    .orElseThrow(() -> new IllegalStateException("Asl yozuv topilmadi: " + relatedTxId));
                if (original.getAmount().negate().compareTo(amount) != 0) {
                    throw new IllegalStateException("REVERSAL summasi asl yozuvning teskarisi bo'lishi kerak");
                }
            }
            case LEGACY -> throw new IllegalStateException(type + " endi yozilmaydi (legacy)");
            case ANY -> {
                // ikkala belgi ham mumkin
            }
        }
    }

    private User currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }
}

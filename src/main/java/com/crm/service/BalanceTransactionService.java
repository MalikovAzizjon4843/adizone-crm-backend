package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.billing.BillingGate;
import com.crm.billing.BillingLocks;
import com.crm.billing.BillingSnapshotService;
import com.crm.billing.BillingStatusService;
import com.crm.billing.LedgerService;
import com.crm.billing.Money;
import com.crm.dto.response.BalanceHistoryItemDto;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O'quvchi balans tarixi va SUPER_ADMIN ning qo'lda amallari (billing v2 §10.2). Yozuv —
 * faqat {@link LedgerService#post} orqali, qulf ostida (§7.2).
 */
@Service
@RequiredArgsConstructor
public class BalanceTransactionService {

    private static final Set<BalanceTransactionType> PAYMENT_LINKED =
        Set.of(BalanceTransactionType.PAYMENT, BalanceTransactionType.DISCOUNT);

    private final BalanceTransactionRepository balanceTransactionRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final StudentRepository studentRepository;
    private final BillingPeriodRepository periodRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final BillingLocks locks;
    private final BillingSnapshotService snapshotService;
    private final BillingStatusService statusService;
    private final BillingGate gate;

    @Transactional(readOnly = true)
    public List<BalanceHistoryItemDto> getHistory(
            Long studentId, Long groupId, LocalDate from, LocalDate to) {
        if (!studentRepository.existsById(studentId)) {
            throw new ResourceNotFoundException("Student", studentId);
        }
        List<BalanceTransaction> rows = balanceTransactionRepository.findAll(
            historySpec(studentId, groupId, from, to),
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Map<Long, BillingPeriod> periods = periodRepository.findAllById(rows.stream()
                .map(BalanceTransaction::getBillingPeriodId).filter(Objects::nonNull).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(BillingPeriod::getId, Function.identity()));
        Map<Long, Payment> payments = paymentRepository.findAllById(rows.stream()
                .filter(t -> PAYMENT_LINKED.contains(t.getType()) && t.getReferenceId() != null)
                .map(BalanceTransaction::getReferenceId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(Payment::getId, Function.identity()));
        return rows.stream().map(t -> toHistoryDto(t, periods, payments)).toList();
    }

    /**
     * Ixtiyoriy filtrlar — faqat berilganlari SQL ga tushadi. {@code (:p IS NULL OR ...)}
     * shakli PostgreSQL'da parametr tipini aniqlay olmay yiqiladi.
     * {@code to} — kun oxirigacha (keyingi kun boshidan qat'iy kichik).
     */
    private static Specification<BalanceTransaction> historySpec(
            Long studentId, Long groupId, LocalDate from, LocalDate to) {
        Specification<BalanceTransaction> spec = (root, q, cb) ->
            cb.equal(root.get("student").get("id"), studentId);
        if (groupId != null) {
            spec = spec.and((root, q, cb) ->
                cb.equal(root.get("studentGroup").get("group").get("id"), groupId));
        }
        if (from != null) {
            LocalDateTime start = from.atStartOfDay();
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        }
        if (to != null) {
            LocalDateTime exclusiveEnd = to.plusDays(1).atStartOfDay();
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("createdAt"), exclusiveEnd));
        }
        return spec;
    }

    /**
     * {@code POST /api/students/{id}/balance-adjust} (SA): {@code MANUAL_ADJUST}, summa butun
     * va nol emas, {@code effectiveDate} ixtiyoriy (default bugun).
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Balance",
        summary = "'Balans qo''lda tuzatildi: ' + #amount + ' (' + #note + ')'",
        entityId = "#studentId")
    public BalanceHistoryItemDto manualAdjust(Long studentId, Long groupId, BigDecimal amount, String note,
                                              LocalDate effectiveDate) {
        gate.requireWritable();
        if (note == null || note.isBlank()) {
            throw CodedException.badRequest("balanceAdjust.note.required");
        }
        if (amount == null || amount.signum() == 0) {
            throw CodedException.badRequest("balanceAdjust.amount.required");
        }
        if (!Money.isWhole(amount)) {
            throw CodedException.badRequest("money.wholeSumRequired");
        }
        LocalDate today = statusService.today();
        if (effectiveDate != null && effectiveDate.isAfter(today)) {
            throw CodedException.badRequest("payment.date.future");
        }
        StudentGroup candidate = enrollment(studentId, groupId);
        StudentGroup sg = locks.lockEnrollmentWithStudent(studentId, candidate.getId());
        BalanceTransaction tx = ledgerService.post(LedgerService.Entry.builder()
            .enrollment(sg)
            .type(BalanceTransactionType.MANUAL_ADJUST)
            .amount(amount)
            .effectiveDate(effectiveDate != null ? effectiveDate : today)
            .note(note.trim())
            .build());
        snapshotService.refresh(sg);
        return toHistoryDto(tx, Map.of(), Map.of());
    }

    /**
     * {@code POST /api/students/{id}/balance-transfer} (SA, §13 #6): bir o'quvchining ikki
     * guruhi orasida qo'lda ko'chirish — {@code TRANSFER_OUT}/{@code TRANSFER_IN} juftligi.
     * Avtomatik kesishuv yo'q; bu yagona qo'lda yo'l.
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Balance",
        summary = "'Guruhlar orasida balans ko''chirildi: ' + #amount + ' (' + #note + ')'",
        entityId = "#studentId")
    public List<BalanceHistoryItemDto> transferBetweenGroups(Long studentId, Long fromGroupId, Long toGroupId,
                                                             BigDecimal amount, String note) {
        gate.requireWritable();
        if (note == null || note.trim().length() < 3) {
            throw CodedException.badRequest("balanceTransfer.note.required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw CodedException.badRequest("payment.amount.positive");
        }
        if (!Money.isWhole(amount)) {
            throw CodedException.badRequest("money.wholeSumRequired");
        }
        if (Objects.equals(fromGroupId, toGroupId)) {
            throw CodedException.badRequest("balanceTransfer.sameGroup");
        }
        StudentGroup from = enrollment(studentId, fromGroupId);
        StudentGroup to = enrollment(studentId, toGroupId);
        BillingLocks.Locked locked = locks.acquire(BillingLocks.Plan.of()
            .student(studentId).enrollments(List.of(from.getId(), to.getId())));
        StudentGroup a = locked.enrollment(from.getId());
        StudentGroup b = locked.enrollment(to.getId());
        LocalDate today = statusService.today();
        BalanceTransaction out = ledgerService.post(LedgerService.Entry.builder()
            .enrollment(a).type(BalanceTransactionType.TRANSFER_OUT).amount(amount.negate())
            .effectiveDate(today).referenceId(b.getId())
            .note("Qo'lda ko'chirildi → " + groupName(b) + ": " + note.trim()).build());
        BalanceTransaction in = ledgerService.post(LedgerService.Entry.builder()
            .enrollment(b).type(BalanceTransactionType.TRANSFER_IN).amount(amount)
            .effectiveDate(today).referenceId(a.getId()).relatedTxId(out.getId())
            .note("Qo'lda ko'chirildi ← " + groupName(a) + ": " + note.trim()).build());
        snapshotService.refresh(a);
        snapshotService.refresh(b);
        return List.of(toHistoryDto(out, Map.of(), Map.of()), toHistoryDto(in, Map.of(), Map.of()));
    }

    public static String typeLabel(BalanceTransactionType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case LESSON_CHARGE -> "Dars uchun yechildi";
            case LESSON_REFUND -> "Davomat qaytarildi";
            case PAYMENT -> "To'lov qabul qilindi";
            case PERIOD_CHARGE -> "Davr to'lovi";
            case PERIOD_REFUND -> "Davr qaytarimi";
            case FREEZE -> "Muzlatish";
            case UNFREEZE -> "Muzlatishdan chiqarish";
            case MANUAL_ADJUST -> "Qo'lda tuzatish";
            case DISCOUNT -> "Chegirma";
            case BONUS -> "Bonus";
            case PENALTY -> "Jarima";
            case REVERSAL -> "Bekor qilindi";
            case TRANSFER_OUT -> "Ko'chirish (chiqim)";
            case TRANSFER_IN -> "Ko'chirish (kirim)";
            case MIGRATION -> "Migratsiya";
            case REFUND_PAYOUT -> "Pul qaytarildi";
        };
    }

    /** O'quvchining shu guruhdagi yozilmasi: faoli, bo'lmasa eng oxirgisi (yopilgan/muzlatilgan). */
    private StudentGroup enrollment(Long studentId, Long groupId) {
        if (groupId == null) {
            throw CodedException.badRequest("payment.group.required");
        }
        return studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
            .or(() -> studentGroupRepository.findByStudentId(studentId).stream()
                .filter(g -> g.getGroup() != null && groupId.equals(g.getGroup().getId()))
                .max(Comparator.comparing(StudentGroup::getId)))
            .orElseThrow(() -> CodedException.notFound("payment.enrollment.notFound"));
    }

    private static String groupName(StudentGroup sg) {
        return sg.getGroup() != null ? sg.getGroup().getGroupName() : "";
    }

    private BalanceHistoryItemDto toHistoryDto(BalanceTransaction t, Map<Long, BillingPeriod> periods,
                                               Map<Long, Payment> payments) {
        String createdBy = null;
        if (t.getCreatedBy() != null) {
            createdBy = ((t.getCreatedBy().getFirstName() != null ? t.getCreatedBy().getFirstName() : "")
                + " "
                + (t.getCreatedBy().getLastName() != null ? t.getCreatedBy().getLastName() : "")).trim();
            if (createdBy.isEmpty()) {
                createdBy = t.getCreatedBy().getUsername();
            }
        }
        BillingPeriod period = t.getBillingPeriodId() != null ? periods.get(t.getBillingPeriodId()) : null;
        Long paymentId = PAYMENT_LINKED.contains(t.getType()) ? t.getReferenceId() : null;
        Payment payment = paymentId != null ? payments.get(paymentId) : null;
        return BalanceHistoryItemDto.builder()
            .id(t.getId())
            .date(t.getCreatedAt())
            .effectiveDate(t.getEffectiveDate())
            .type(t.getType())
            .typeLabel(typeLabel(t.getType()))
            .amount(t.getAmount())
            .balanceAfter(t.getBalanceAfter())
            .note(t.getNote())
            .relatedTxId(t.getRelatedTxId())
            .billingPeriod(period != null
                ? new BalanceHistoryItemDto.PeriodRef(period.getPeriodStart(), period.getPeriodEnd(),
                    period.getAmount(), period.getProratedLessons(), period.getLessonPrice()) : null)
            .paymentId(paymentId)
            .receiptNumber(payment != null ? payment.getReceiptNumber() : null)
            .groupId(t.getStudentGroup() != null && t.getStudentGroup().getGroup() != null
                ? t.getStudentGroup().getGroup().getId() : null)
            .groupName(t.getStudentGroup() != null && t.getStudentGroup().getGroup() != null
                ? t.getStudentGroup().getGroup().getGroupName() : null)
            .createdBy(createdBy)
            .build();
    }
}

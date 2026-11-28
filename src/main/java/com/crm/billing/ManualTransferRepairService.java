package com.crm.billing;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.dto.request.ManualTransferRepairRequest;
import com.crm.dto.response.ManualTransferDtos.Classification;
import com.crm.dto.response.ManualTransferDtos.PairState;
import com.crm.dto.response.ManualTransferDtos.PeriodState;
import com.crm.dto.response.ManualTransferDtos.RepairResult;
import com.crm.dto.response.ManualTransferDtos.RepairRow;
import com.crm.dto.response.ManualTransferDtos.Row;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.exception.CodedException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/admin/repair/merge-into-transfer} (SA): qo'lda ko'chirishni transfer natijasiga tenglash.
 * Hisob — {@link EnrollmentLifecycleService#mergeManualTransfer} (transfer servisi, parallel hisob yo'q).
 *
 * <ul>
 *   <li>Juftliklar o'quvchi bo'yicha guruhlanadi va vaqt tartibida (eski yozilma chiqish sanasi, id) qo'llanadi —
 *       zanjir A→B→C da (B,C) (A,B) ning natijasini ko'radi. Bir juftlik qo'llanmasa (xato), o'sha o'quvchining
 *       keyingilari o'tkazib yuboriladi.</li>
 *   <li>Qo'llash: har juftlik ALOHIDA tranzaksiyada (REQUIRES_NEW).</li>
 *   <li>dryRun: AYNAN o'sha kod o'quvchi bo'yicha bitta tranzaksiyada bajariladi va ROLLBACK qilinadi — oldin/keyin
 *       holat apply bilan bir xil hisobdan chiqadi, bazada hech narsa qolmaydi.</li>
 *   <li>Idempotent: qo'llangan juftlik ikkinchi marta {@code REPAIRED} bilan o'tkazib yuboriladi.</li>
 *   <li>Kassa va to'lovlarga tegilmaydi — faqat ledger (PERIOD_REFUND, TRANSFER_OUT/IN) va davrlar.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualTransferRepairService {

    private final ManualTransferDetectionService detection;
    private final EnrollmentLifecycleService lifecycle;
    private final StudentGroupRepository studentGroupRepository;
    private final BillingPeriodRepository periodRepository;
    private final BalanceTransactionRepository transactionRepository;
    private final BillingGate gate;
    private final PlatformTransactionManager transactionManager;

    private record Target(Long studentId, Long oldId, Long newId, LocalDate exit) {
    }

    @Audited(action = AuditAction.REPAIR_MANUAL_TRANSFER, entity = "StudentGroup",
        summary = "'Qo''lda ko''chirish transfer''ga tenglandi: ' + #result.applied() + ' juftlik, kredit '"
            + " + #result.totalCredit() + ' so''m'")
    public RepairResult repair(ManualTransferRepairRequest request, boolean dryRun) {
        if (dryRun) {
            AuditContext.skip();
        } else {
            gate.requireWritable();
        }
        boolean explicit = request != null && request.getPairs() != null && !request.getPairs().isEmpty();
        List<Target> targets = explicit ? explicitTargets(request) : affectedTargets(request);

        Map<Long, List<Target>> byStudent = new LinkedHashMap<>();
        targets.stream()
            .sorted(Comparator.comparing(Target::studentId)
                .thenComparing(Target::exit, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Target::oldId))
            .forEach(t -> byStudent.computeIfAbsent(t.studentId(), k -> new ArrayList<>()).add(t));

        List<RepairRow> rows = new ArrayList<>();
        for (List<Target> list : byStudent.values()) {
            rows.addAll(dryRun ? simulate(list, explicit) : apply(list, explicit));
        }
        int applied = (int) rows.stream().filter(r -> "APPLIED".equals(r.status())).count();
        int failed = (int) rows.stream().filter(r -> "FAILED".equals(r.status())).count();
        BigDecimal total = rows.stream().filter(r -> "APPLIED".equals(r.status()))
            .map(RepairRow::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!dryRun) {
            AuditContext.change("applied", null, applied);
            AuditContext.change("totalCredit", null, Money.normalize(total).toPlainString());
            AuditContext.change("pairs", null, rows.stream()
                .map(r -> "sg#" + r.oldEnrollmentId() + "→sg#" + r.newEnrollmentId() + " " + r.status()
                    + (r.reasonCode() != null ? " (" + r.reasonCode() + ")" : "")
                    + ("APPLIED".equals(r.status()) ? " " + r.credit().toPlainString() : ""))
                .toList());
        }
        return new RepairResult(dryRun, targets.size(), applied, rows.size() - applied - failed, failed,
            Money.normalize(total), rows);
    }

    // ── nishonlar ───────────────────────────────────────────────────────

    private List<Target> explicitTargets(ManualTransferRepairRequest request) {
        List<Target> out = new ArrayList<>();
        TransactionTemplate read = template(true);
        for (ManualTransferRepairRequest.Pair p : request.getPairs()) {
            if (p == null || p.getOldEnrollmentId() == null || p.getNewEnrollmentId() == null) {
                throw CodedException.badRequest("repair.manualTransfer.request");
            }
            Target t = read.execute(s -> {
                StudentGroup o = studentGroupRepository.findById(p.getOldEnrollmentId())
                    .orElseThrow(() -> CodedException.notFound("error.studentGroup.notFound", p.getOldEnrollmentId()));
                return new Target(o.getStudent().getId(), o.getId(), p.getNewEnrollmentId(),
                    ManualTransferDetectionService.exitDate(o));
            });
            out.add(t);
        }
        return out;
    }

    private List<Target> affectedTargets(ManualTransferRepairRequest request) {
        if (request == null || request.getClassification() == null
                || !Classification.AFFECTED.name().equalsIgnoreCase(request.getClassification().trim())) {
            throw CodedException.badRequest("repair.manualTransfer.request");
        }
        if (request.getFrom() != null && request.getTo() != null && request.getFrom().isAfter(request.getTo())) {
            throw CodedException.badRequest("repair.manualTransfer.dateRange");
        }
        return detection.detect(request.getFrom(), request.getTo()).rows().stream()
            .filter(r -> r.classification() == Classification.AFFECTED)
            .map(ManualTransferRepairService::target)
            .toList();
    }

    private static Target target(Row r) {
        return new Target(r.studentId(), r.oldEnrollmentId(), r.newEnrollmentId(), r.oldExitDate());
    }

    // ── bajarish ────────────────────────────────────────────────────────

    /** Qo'llash: har juftlik o'z tranzaksiyasida; xato — o'quvchining keyingi juftliklari o'tkazib yuboriladi. */
    private List<RepairRow> apply(List<Target> list, boolean explicit) {
        List<RepairRow> rows = new ArrayList<>();
        TransactionTemplate perPair = template(false);
        boolean broken = false;
        for (Target t : list) {
            if (broken) {
                rows.add(skipped(t, "PREVIOUS_FAILED"));
                continue;
            }
            try {
                rows.add(perPair.execute(s -> process(t, explicit)));
            } catch (RuntimeException e) {
                log.error("Qo'lda ko'chirish ta'miri yiqildi sg {} → {}: {}", t.oldId(), t.newId(), e.getMessage(), e);
                rows.add(failed(t, e));
                broken = true;
            }
        }
        return rows;
    }

    /** dryRun: o'quvchining barcha juftliklari bitta tranzaksiyada (zanjir natijasi ko'rinadi), oxirida rollback. */
    private List<RepairRow> simulate(List<Target> list, boolean explicit) {
        List<RepairRow> rows = new ArrayList<>();
        try {
            template(false).executeWithoutResult(s -> {
                try {
                    for (Target t : list) {
                        rows.add(process(t, explicit));
                    }
                } finally {
                    s.setRollbackOnly();
                }
            });
        } catch (RuntimeException e) {
            log.warn("Qo'lda ko'chirish dryRun yiqildi (o'quvchi {}): {}", list.get(0).studentId(), e.getMessage());
            int failedAt = rows.size();
            for (int i = failedAt; i < list.size(); i++) {
                rows.add(i == failedAt ? failed(list.get(i), e) : skipped(list.get(i), "PREVIOUS_FAILED"));
            }
        }
        return rows;
    }

    private RepairRow process(Target t, boolean explicit) {
        PairState before = state(t.oldId(), t.newId());
        EnrollmentLifecycleService.MergeOutcome outcome = lifecycle.mergeManualTransfer(t.oldId(), t.newId(), explicit);
        EnrollmentLifecycleService.MergePlan plan = outcome.plan();
        StudentGroup o = studentGroupRepository.findById(t.oldId()).orElseThrow();
        String name = name(o.getStudent());
        if (!outcome.applied()) {
            return new RepairRow(t.studentId(), name, t.oldId(), t.newId(), "SKIPPED", outcome.skipReason(),
                ManualTransferDetectionService.reason(outcome.skipReason(), plan),
                plan != null ? plan.mode().name() : null, plan != null ? plan.continuedAnchor() : null,
                plan != null ? Money.normalize(plan.refundTotal()) : null,
                plan != null ? Money.normalize(plan.moved()) : null,
                plan != null ? Money.normalize(plan.credit()) : null, before, before);
        }
        PairState after = state(t.oldId(), t.newId());
        return new RepairRow(t.studentId(), name, t.oldId(), t.newId(), "APPLIED", null,
            "Transfer'ga tenglandi (" + plan.mode() + "): hisob " + plan.continuedAnchor() + " dan",
            plan.mode().name(), plan.continuedAnchor(), Money.normalize(plan.refundTotal()),
            Money.normalize(plan.moved()), Money.normalize(plan.credit()), before, after);
    }

    private PairState state(Long oldId, Long newId) {
        StudentGroup o = studentGroupRepository.findById(oldId).orElseThrow();
        StudentGroup n = studentGroupRepository.findById(newId)
            .orElseThrow(() -> CodedException.notFound("error.studentGroup.notFound", newId));
        Long studentId = o.getStudent().getId();
        BigDecimal debt = studentGroupRepository.findByStudentId(studentId).stream()
            .filter(sg -> Boolean.TRUE.equals(sg.getIsActive()))
            .map(sg -> Money.nz(transactionRepository.sumAmountByStudentGroupId(sg.getId())).negate().max(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PeriodState> periods = periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(newId).stream()
            .map(p -> new PeriodState(p.getId(), p.getPeriodStart(), p.getPeriodEnd(), Money.normalize(Money.nz(p.getAmount())),
                Money.normalize(Money.nz(p.getRefundedAmount())), p.getStatus().name()))
            .toList();
        return new PairState(
            Money.normalize(Money.nz(transactionRepository.sumAmountByStudentGroupId(oldId))),
            Money.normalize(Money.nz(transactionRepository.sumAmountByStudentGroupId(newId))),
            Money.normalize(Money.nz(studentGroupRepository.sumBalanceByStudentId(studentId))),
            Money.normalize(debt), n.getPaymentStartDate(), periods);
    }

    private static RepairRow skipped(Target t, String code) {
        return new RepairRow(t.studentId(), null, t.oldId(), t.newId(), "SKIPPED", code,
            ManualTransferDetectionService.reason(code, null), null, null, null, null, null, null, null);
    }

    private static RepairRow failed(Target t, RuntimeException e) {
        return new RepairRow(t.studentId(), null, t.oldId(), t.newId(), "FAILED", "ERROR", e.getMessage(),
            null, null, null, null, null, null, null);
    }

    private TransactionTemplate template(boolean readOnly) {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setReadOnly(readOnly);
        return t;
    }

    private static String name(Student s) {
        return s == null ? null : ((s.getFirstName() != null ? s.getFirstName() : "") + " "
            + (s.getLastName() != null ? s.getLastName() : "")).trim();
    }
}

package com.crm.billing;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.dto.response.GroupEndDateDtos;
import com.crm.entity.Group;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Bir martalik tuzatish (SA): qoida (2026-10-10) oldidan to'liq narx bilan yozilgan oxirgi davrlar —
 * {@code POST /api/admin/repair/prorate-last-periods}. Mexanizm — {@link LastPeriodRecalcService} (guruh tugash sanasi
 * o'zgargandagi bilan bir xil). {@code dryRun} — faqat o'qiydi. Qo'llashda har yozilma ALOHIDA tranzaksiyada, qulf
 * ostida qayta rejalanadi: bittasining xatosi qolganlarini to'xtatmaydi, takroriy chaqiruv farq topmaydi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LastPeriodRepairService {

    private final BillingPeriodRepository periodRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final LastPeriodRecalcService recalcService;
    private final BillingSnapshotService snapshotService;
    private final BillingLocks locks;
    private final BillingGate gate;
    private final PlatformTransactionManager transactionManager;

    @Audited(action = AuditAction.REPAIR, entity = "BillingPeriod",
        summary = "'Oxirgi davrlar darslar bo''yicha qayta hisoblandi: ' + #result.periods + ' davr, farq '"
            + " + #result.totalDiff + ' so''m'")
    public GroupEndDateDtos.Repair repair(boolean dryRun) {
        if (dryRun) {
            AuditContext.skip();
        } else {
            gate.requireWritable();
        }
        List<Long> candidates = periodRepository.findStudentGroupIdsForLastPeriodReview();
        TransactionTemplate perEnrollment = new TransactionTemplate(transactionManager);
        perEnrollment.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        perEnrollment.setReadOnly(dryRun);

        List<GroupEndDateDtos.RepairRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int enrollments = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Long sgId : candidates) {
            try {
                List<GroupEndDateDtos.RepairRow> done = perEnrollment.execute(s -> dryRun ? preview(sgId) : apply(sgId));
                if (done != null && !done.isEmpty()) {
                    enrollments++;
                    rows.addAll(done);
                    for (GroupEndDateDtos.RepairRow r : done) {
                        total = total.add(r.diff());
                    }
                }
            } catch (RuntimeException e) {
                log.error("Oxirgi davr tuzatilmadi sg={}: {}", sgId, e.getMessage(), e);
                errors.add("sg=" + sgId + ": " + e.getMessage());
            }
        }
        if (!dryRun) {
            AuditContext.change("enrollments", null, enrollments);
            AuditContext.change("periods", null, rows.size());
            AuditContext.change("totalDiff", null, Money.normalize(total).toPlainString());
            AuditContext.change("rows", null, rows.stream()
                .map(r -> "sg#" + r.studentGroupId() + " davr#" + r.periodId() + " " + r.oldAmount().toPlainString()
                    + "→" + r.newAmount().toPlainString())
                .toList());
            if (!errors.isEmpty()) {
                AuditContext.change("errors", null, errors);
            }
        }
        return new GroupEndDateDtos.Repair(dryRun, candidates.size(), enrollments, rows.size(), Money.normalize(total),
            errors.size(), rows, errors);
    }

    private List<GroupEndDateDtos.RepairRow> preview(Long sgId) {
        StudentGroup sg = studentGroupRepository.findById(sgId).orElse(null);
        return sg == null ? List.of() : rows(sg, recalcService.plan(sg));
    }

    private List<GroupEndDateDtos.RepairRow> apply(Long sgId) {
        Long studentId = studentGroupRepository.findStudentIdById(sgId).orElse(null);
        if (studentId == null) {
            return List.of();
        }
        StudentGroup sg = locks.lockEnrollmentWithStudent(studentId, sgId);
        List<GroupEndDateDtos.Recalc> applied = recalcService.apply(sg);
        if (!applied.isEmpty()) {
            snapshotService.refresh(sg);
        }
        return rows(sg, applied);
    }

    private static List<GroupEndDateDtos.RepairRow> rows(StudentGroup sg, List<GroupEndDateDtos.Recalc> recalcs) {
        Student s = sg.getStudent();
        Group g = sg.getGroup();
        String name = s == null ? null : ((s.getFirstName() != null ? s.getFirstName() : "") + " "
            + (s.getLastName() != null ? s.getLastName() : "")).trim();
        return recalcs.stream().map(r -> new GroupEndDateDtos.RepairRow(sg.getId(), s != null ? s.getId() : null, name,
                g != null ? g.getId() : null, g != null ? g.getGroupName() : null, g != null ? g.getEndDate() : null,
                r.periodId(), r.start(), r.end(), r.oldAmount(), r.newAmount(), r.diff(), r.oldLessons(), r.newLessons(),
                r.lessonPrice()))
            .toList();
    }
}

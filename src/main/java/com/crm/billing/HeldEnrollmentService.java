package com.crm.billing;

import com.crm.entity.BillingHeldApplication;
import com.crm.entity.BillingMigrationRun;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.PaymentStatus;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.BillingHeldApplicationRepository;
import com.crm.repository.BillingMigrationRunRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;

/**
 * Hold'dagi yozilmalar (migratsiya qo'llanmagan, billing-v2 §9.7) — CRM'dan ko'rish va qo'llash (SA).
 *
 * <ul>
 *   <li>ro'yxat — har SG uchun oxirgi qo'llangan run parametrlari bilan dry-run ({@link MigrationPlanner#planOne});</li>
 *   <li>preview — xuddi shu, ixtiyoriy yangi langar bilan, hech narsa yozmaydi;</li>
 *   <li>apply — mavjud {@link BillingMigrationService#applySg} mantiqi; avval (berilsa) langar tuzatiladi; Idempotency-Key
 *       va sabab majburiy; har qo'llash {@code billing_held_applications} ga yoziladi (V77).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class HeldEnrollmentService {

    private final StudentGroupRepository studentGroupRepository;
    private final BillingMigrationRunRepository runRepository;
    private final PaymentRepository paymentRepository;
    private final BillingHeldApplicationRepository applicationRepository;
    private final MigrationPlanner planner;
    private final BillingMigrationService migrationService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;
    private final Clock billingClock;

    /** Ro'yxat / preview qatori: yozilma, to'lovlar va dry-run natijasi. */
    public record HeldRow(
        Long studentGroupId, Long studentId, String studentName, String phone, Long groupId, String groupName,
        LocalDate joinDate, LocalDate paymentStartDate, long paymentsCount, BigDecimal paymentsSum,
        BigDecimal paidNet, BigDecimal otherLedger,
        String category, List<MigrationPlanner.PlannedPeriod> periods, BigDecimal charges, BigDecimal migrationAmount,
        BigDecimal netAdjustment,
        BigDecimal balanceBefore, BigDecimal balanceAfter, BigDecimal debtAfter, LocalDate debtSinceAfter,
        String statusAfter, LocalDate nextPaymentDateAfter, SortedSet<String> anomalies, boolean blocking,
        String planHash) {
    }

    public record HeldList(Long migrationRunId, LocalDate cutover, int total, List<HeldRow> rows) {
    }

    public record ApplyResult(Long studentGroupId, Long migrationRunId, LocalDate anchorBefore, LocalDate anchorAfter,
                              String reason, boolean replay, HeldRow after) {
    }

    // ── o'qish ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public HeldList list() {
        BillingMigrationRun run = latestAppliedRun();
        MigrationPlanner.Options o = options(run);
        List<HeldRow> rows = new ArrayList<>();
        for (StudentGroup sg : studentGroupRepository.findHeldWithStudentAndGroup()) {
            rows.add(row(sg, planner.planOne(sg, o)));
        }
        return new HeldList(run.getId(), run.getCutoverDate(), rows.size(), rows);
    }

    /** Hech narsa yozmaydi: {@code paymentStartDate} berilsa — shu langar bilan reja (tranzaksiya qaytariladi). */
    public HeldRow preview(Long sgId, LocalDate paymentStartDate) {
        BillingMigrationRun run = latestAppliedRun();
        MigrationPlanner.Options o = options(run);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            StudentGroup sg = requireHeld(sgId);
            if (paymentStartDate != null) {
                sg.setPaymentStartDate(paymentStartDate);     // faqat xotirada; read-only + rollback — yozilmaydi
            }
            HeldRow r = row(sg, planner.planOne(sg, o));
            status.setRollbackOnly();
            return r;
        });
    }

    // ── qo'llash ────────────────────────────────────────────────────────

    /**
     * @param idempotencyKey majburiy; shu kalit bilan avval shu SG qo'llangan bo'lsa — o'sha natija ({@code replay = true}),
     *                       boshqa SG uchun ishlatilgan bo'lsa — 409
     * @param reason         majburiy (≤ 1000)
     * @param paymentStartDate berilsa va farq qilsa — qo'llashdan oldin langar shu sanaga o'rnatiladi
     * @param expectedPlanHash berilsa — preview'dagi reja o'zgarmaganini tekshiradi (aks holda 409)
     */
    @Transactional
    public ApplyResult apply(Long sgId, String idempotencyKey, String reason, LocalDate paymentStartDate,
                             String expectedPlanHash) {
        String key = idempotencyKey != null ? idempotencyKey.trim() : "";
        if (key.isEmpty() || key.length() > 100) {
            throw CodedException.badRequest("billing.held.idempotencyKeyRequired");
        }
        String why = reason != null ? reason.trim() : "";
        if (why.isEmpty() || why.length() > 1000) {
            throw CodedException.badRequest("billing.held.reasonRequired");
        }
        BillingHeldApplication previous = applicationRepository.findByIdempotencyKey(key).orElse(null);
        if (previous != null) {
            if (!previous.getStudentGroupId().equals(sgId)) {
                throw new ConflictException("billing.held.idempotencyConflict");
            }
            StudentGroup sg = studentGroupRepository.findById(sgId)
                .orElseThrow(() -> CodedException.notFound("error.studentGroup.notFound", sgId));
            BillingMigrationRun run = runRepository.findById(previous.getMigrationRunId()).orElseThrow();
            return new ApplyResult(sgId, run.getId(), previous.getAnchorBefore(), previous.getAnchorAfter(),
                previous.getReason(), true, row(sg, planner.planOne(sg, options(run))));
        }

        BillingMigrationRun run = latestAppliedRun();
        MigrationPlanner.Options o = options(run);
        StudentGroup sg = requireHeld(sgId);
        LocalDate anchorBefore = sg.getPaymentStartDate();
        if (paymentStartDate != null && !paymentStartDate.equals(anchorBefore)) {
            sg.setPaymentStartDate(paymentStartDate);
            studentGroupRepository.saveAndFlush(sg);
        }
        HeldRow planned = row(sg, planner.planOne(sg, o));
        if (expectedPlanHash != null && !expectedPlanHash.isBlank() && !expectedPlanHash.equals(planned.planHash())) {
            throw new ConflictException("billing.held.planChanged");
        }

        migrationService.applySg(run.getId(), sgId, "APPLY-" + run.getId() + "-" + sgId);
        StudentGroup after = studentGroupRepository.findById(sgId).orElseThrow();
        HeldRow afterRow = row(after, planner.planOne(after, o));

        applicationRepository.save(BillingHeldApplication.builder()
            .studentGroupId(sgId)
            .migrationRunId(run.getId())
            .idempotencyKey(key)
            .reason(why)
            .anchorBefore(anchorBefore)
            .anchorAfter(after.getPaymentStartDate())
            .planHash(planned.planHash())
            .resultJson(json(planned))
            .appliedBy(currentUsername())
            .appliedAt(LocalDateTime.now(billingClock))
            .build());
        return new ApplyResult(sgId, run.getId(), anchorBefore, after.getPaymentStartDate(), why, false, afterRow);
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    /** Hold'dagi SG lar eng oxirgi qo'llangan run parametrlari (T, G) bilan, sof hisobda (§9.7.1) rejalanadi. */
    BillingMigrationRun latestAppliedRun() {
        return runRepository.findAllByOrderByIdDesc().stream()
            .filter(r -> r.getAppliedAt() != null
                && (r.getStatus() == BillingMigrationRun.Status.APPLIED
                    || r.getStatus() == BillingMigrationRun.Status.APPLIED_WITH_ERRORS))
            .findFirst()
            .orElseThrow(() -> new ConflictException("migration.notApplied"));
    }

    private static MigrationPlanner.Options options(BillingMigrationRun run) {
        return new MigrationPlanner.Options(run.getCutoverDate(), run.getGoLiveDate(), run.isA14UsePayable()).asHeldNet();
    }

    private StudentGroup requireHeld(Long sgId) {
        StudentGroup sg = studentGroupRepository.findById(sgId)
            .orElseThrow(() -> CodedException.notFound("error.studentGroup.notFound", sgId));
        if (!Boolean.TRUE.equals(sg.getBillingHold())) {
            throw new ConflictException("migration.sgNotHeld");
        }
        return sg;
    }

    private HeldRow row(StudentGroup sg, MigrationPlanner.SgPlan p) {
        List<Payment> payments = paymentRepository.findByStudentGroup_IdAndStatusOrderByPaymentDateAscIdAsc(
            sg.getId(), PaymentStatus.PAID);
        BigDecimal sum = payments.stream().map(Payment::getAmount).filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new HeldRow(
            sg.getId(), p.studentId(), p.studentName(),
            sg.getStudent() != null ? sg.getStudent().getPhone() : null,
            p.groupId(), p.groupName(), sg.getJoinDate(), sg.getPaymentStartDate(),
            payments.size(), Money.normalize(sum), p.paidNet(), p.keptLedger(),
            p.category(), p.periods(), Money.normalize(p.charges()), Money.normalize(p.migrationAmount()),
            p.netAdjustment(),
            Money.normalize(p.storedBalance()), Money.normalize(p.target()), Money.normalize(p.newDebt()),
            p.newDebtSince(), p.newStatus(), p.newNextPaymentDate(), p.anomalies(), p.blocking(),
            planHash(sg, p));
    }

    /** Preview va apply orasida reja o'zgarmaganini tekshirish uchun: langar, davrlar, summalar. */
    static String planHash(StudentGroup sg, MigrationPlanner.SgPlan p) {
        StringBuilder sb = new StringBuilder().append(sg.getId()).append('|').append(sg.getPaymentStartDate());
        for (MigrationPlanner.PlannedPeriod pp : p.periods()) {
            sb.append('|').append(pp.start()).append(':').append(pp.end()).append(':').append(pp.status())
                .append(':').append(Money.normalize(pp.amount()).toPlainString());
        }
        sb.append('|').append(Money.normalize(p.migrationAmount()).toPlainString())
            .append('|').append(Money.normalize(p.netAdjustment()).toPlainString())
            .append('|').append(Money.normalize(p.target()).toPlainString())
            .append('|').append(p.anomalies());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }
}

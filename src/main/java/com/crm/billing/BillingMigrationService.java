package com.crm.billing;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingMigrationRun;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Payment;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.TeacherAttribution;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingMigrationRunRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.util.MigrationXlsx;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Billing v2 migratsiyasini boshqarish (§9.4–§9.7, §13 #18/#20). Hech biri avtomatik
 * ishlamaydi — faqat SUPER_ADMIN endpointlari orqali.
 *
 * <ol>
 *   <li><b>Dry-run</b> — {@link MigrationPlanner#dryRun}: hech narsa yozmaydi.</li>
 *   <li><b>Tasdiq</b> — egasi hisobotni tasdiqlaydi; hisobot qayta hisoblanadi va hash mos
 *       bo'lsagina {@code billing_migration_runs} ga APPROVED qator yoziladi.</li>
 *   <li><b>Apply</b> — faqat APPROVED run, tasdiq parametri {@code confirm=APPLY-<runId>},
 *       billing o'chirilgan ({@code app.billing.enabled=false}) va hisobot hash'i o'zgarmagan
 *       bo'lsa. Har SG alohida tranzaksiyada; yozuvlar {@code migration_run_id} bilan.</li>
 *   <li><b>Qaytarish</b> — to'liq: 72 soat ichida {@code pg_dump} dan tiklash (runbook);
 *       qisman: {@link #revertSg} (REVERSAL + run davrlarini o'chirish + hold).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingMigrationService {

    private final MigrationPlanner planner;
    private final BillingMigrationRunRepository runRepository;
    private final BillingLocks locks;
    private final LedgerService ledger;
    private final AccrualService accrualService;
    private final BillingSnapshotService snapshotService;
    private final BillingStatusService statusService;
    private final BillingGate gate;
    private final BillingProperties properties;
    private final BillingPeriodRepository periodRepository;
    private final BalanceTransactionRepository transactionRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final StudentRepository studentRepository;
    private final PaymentRepository paymentRepository;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;
    private final Clock billingClock;

    // ── 1. Dry-run ──────────────────────────────────────────────────────

    public MigrationPlanner.Report dryRun(LocalDate cutover, boolean a14UsePayable) {
        return planner.dryRun(options(cutover, a14UsePayable));
    }

    // ── 2. Tasdiq (§13 #20) ─────────────────────────────────────────────

    /**
     * Egasi tasdiqlagan dry-run hisobotini qayd etadi. Hisobot shu yerda qayta hisoblanadi:
     * {@code reportHash} mos kelmasa (ma'lumot o'zgargan yoki boshqa hisobot) — 409.
     */
    @Transactional
    public BillingMigrationRun approve(LocalDate cutover, boolean a14UsePayable, String reportHash,
                                       String owner, String note) {
        if (owner == null || owner.isBlank()) {
            throw CodedException.badRequest("migration.ownerRequired");
        }
        MigrationPlanner.Report report = planner.dryRun(options(cutover, a14UsePayable));
        if (reportHash == null || !reportHash.equals(report.reportHash())) {
            throw new ConflictException("migration.reportChanged");
        }
        return runRepository.save(BillingMigrationRun.builder()
            .status(BillingMigrationRun.Status.APPROVED)
            .cutoverDate(report.cutover())
            .goLiveDate(report.goLive())
            .a14UsePayable(a14UsePayable)
            .reportHash(report.reportHash())
            .maxTxId(report.maxTxId())
            .maxPaymentId(report.maxPaymentId())
            .sgTotal(report.summary().sgTotal())
            .approvedByOwner(owner.trim())
            .approvalNote(note)
            .approvedBy(currentUsername())
            .approvedAt(LocalDateTime.now(billingClock))
            .summaryJson(json(report.summary()))
            .build());
    }

    // ── 3. Apply ────────────────────────────────────────────────────────

    public record ApplyResult(BillingMigrationRun run, List<Long> migrated, List<Long> held,
                              List<String> errors) {
    }

    /**
     * Faqat SUPER_ADMIN (controller). Tranzaksiyasiz: har SG o'z tranzaksiyasida (§9.6),
     * run holati alohida yoziladi.
     */
    public ApplyResult apply(Long runId, String confirm, Collection<Long> exclude, boolean clearOverrides) {
        BillingMigrationRun run = runRepository.findById(runId)
            .orElseThrow(() -> CodedException.notFound("migration.notFound", runId));
        if (!("APPLY-" + runId).equals(confirm)) {
            throw CodedException.badRequest("migration.confirmRequired", "APPLY-" + runId);
        }
        if (run.getStatus() != BillingMigrationRun.Status.APPROVED) {
            throw new ConflictException("migration.notApproved");
        }
        if (gate.isEnabled()) {
            throw new ConflictException("migration.billingEnabled");
        }
        MigrationPlanner.Options o = options(run);
        MigrationPlanner.Report report = planner.dryRun(o);
        if (!report.reportHash().equals(run.getReportHash())) {
            throw new ConflictException("migration.reportChanged");
        }

        Set<Long> excluded = exclude != null ? new TreeSet<>(exclude) : new TreeSet<>();
        run.setStatus(BillingMigrationRun.Status.APPLYING);
        run.setAppliedBy(currentUsername());
        run.setExcludedSgIds(excluded.stream().map(String::valueOf).collect(Collectors.joining(",")));
        run.setClearOverrides(clearOverrides);
        BillingMigrationRun saved = inNewTx(() -> runRepository.save(run));

        List<Long> migrated = new ArrayList<>();
        List<Long> held = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (MigrationPlanner.SgPlan row : report.rows()) {
            Long sgId = row.studentGroupId();
            boolean mCategory = "M".equals(row.category()) && !row.alreadyMigrated();
            if (mCategory && (excluded.contains(sgId) || row.blocking())) {
                inNewTx(() -> setHold(sgId, true));
                held.add(sgId);
                continue;
            }
            boolean touch = row.writes()
                || (clearOverrides && row.anomalies().contains("A15"))
                || "F".equals(row.category());
            if (!touch) {
                continue;
            }
            try {
                inNewTx(() -> applyOne(sgId, saved.getId(), o, clearOverrides));
                if (row.writes()) {
                    migrated.add(sgId);
                }
            } catch (RuntimeException e) {
                log.error("Migratsiya sg={} yiqildi: {}", sgId, e.getMessage(), e);
                errors.add("sg=" + sgId + ": " + e.getMessage());
                inNewTx(() -> setHold(sgId, true));
                held.add(sgId);
            }
        }

        LocalDateTime now = LocalDateTime.now(billingClock);
        saved.setStatus(errors.isEmpty() ? BillingMigrationRun.Status.APPLIED
            : BillingMigrationRun.Status.APPLIED_WITH_ERRORS);
        saved.setAppliedAt(now);
        saved.setRollbackDeadline(now.plusHours(properties.getMigrationRollbackHours()));
        saved.setMigrated(migrated.size());
        saved.setHeld(held.size());
        saved.setFailed(errors.size());
        saved.setErrors(errors.isEmpty() ? null : String.join("\n", errors));
        BillingMigrationRun done = inNewTx(() -> runRepository.save(saved));
        log.info("Migratsiya run={} qo'llandi: migrated={}, held={}, failed={}",
            runId, migrated.size(), held.size(), errors.size());
        return new ApplyResult(done, migrated, held, errors);
    }

    /** Bitta SG: qulf ostida qayta hisob va aynan rejadagi yozuvlar (§9.2). */
    private Void applyOne(Long sgId, Long runId, MigrationPlanner.Options o, boolean clearOverrides) {
        Long studentId = studentGroupRepository.findStudentIdById(sgId).orElseThrow();
        StudentGroup sg = locks.lockEnrollmentWithStudent(studentId, sgId);
        MigrationPlanner.SgPlan plan = planner.planOne(sg, o);

        if (plan.writes()) {
            for (MigrationPlanner.PlannedPeriod p : plan.periods()) {
                BillingPeriod period = periodRepository.save(BillingPeriod.builder()
                    .studentGroupId(sg.getId())
                    .periodStart(p.start())
                    .periodEnd(p.end())
                    .fee(p.fee())
                    .discountPercentage(BigDecimal.ZERO)
                    .amount(p.amount())
                    .status(p.status())
                    .migrationRunId(runId)
                    // Payroll v2 (§8): o'tgan davr o'qituvchisi noma'lum — hozirgisi, taxminiy
                    .teacherId(sg.getGroup() != null && sg.getGroup().getTeacher() != null
                        ? sg.getGroup().getTeacher().getId() : null)
                    .teacherSource(TeacherAttribution.ESTIMATED)
                    .build());
                if (p.status() == BillingPeriodStatus.CHARGED && p.amount().signum() > 0) {
                    BalanceTransaction tx = ledger.post(LedgerService.Entry.builder()
                        .enrollment(sg)
                        .type(BalanceTransactionType.PERIOD_CHARGE)
                        .amount(p.amount().negate())
                        .effectiveDate(p.start())
                        .billingPeriodId(period.getId())
                        .migrationRunId(runId)
                        .note("Migratsiya: davr " + p.start() + "–" + p.end())
                        .build());
                    period.setChargeTxId(tx.getId());
                    periodRepository.save(period);
                }
            }
            if (plan.migrationAmount().signum() != 0) {
                ledger.post(LedgerService.Entry.builder()
                    .enrollment(sg)
                    .type(BalanceTransactionType.MIGRATION)
                    .amount(plan.migrationAmount())
                    .effectiveDate(o.cutover())
                    .migrationRunId(runId)
                    .note("Migratsiya: eski davr debetlari neytrallandi (legacyPC "
                        + plan.legacyPeriodCharges().toPlainString() + ", ta'mir "
                        + plan.repairAdjustments().toPlainString() + ")")
                    .build());
            }
            if (plan.netAdjustment().signum() != 0) {
                // §9.7.1 (faqat hold sof hisobi): v1 ledger kreditlari → to'lovlar jadvalidagi sof summa
                ledger.post(LedgerService.Entry.builder()
                    .enrollment(sg)
                    .type(BalanceTransactionType.MANUAL_ADJUST)
                    .amount(plan.netAdjustment())
                    .effectiveDate(o.cutover())
                    .migrationRunId(runId)
                    .note(MigrationPlanner.HELD_NET_PREFIX + " sof hisob: to'lovlar "
                        + plan.paidNet().toPlainString() + ", boshqa yozuvlar " + plan.keptLedger().toPlainString()
                        + ", davrlar " + plan.charges().toPlainString())
                    .build());
            }
        }
        if (EnrollmentLifecycleService.isFrozen(sg) && sg.getFrozenFrom() == null) {
            // Eski muzlatilgan SG: frozen_from to'ldiriladi (§6.7 modeli)
            LocalDate from = sg.getExitDate() != null ? sg.getExitDate()
                : (sg.getLeaveDate() != null ? sg.getLeaveDate() : o.cutover());
            sg.setFrozenFrom(from);
        }
        if (clearOverrides && plan.anomalies().contains("A15")) {
            sg.setMonthlyPriceOverride(null);   // §9.5, egasi A15 ro'yxatini tasdiqlagan
        }
        sg.setBillingHold(null);
        studentGroupRepository.save(sg);
        snapshotService.refresh(sg);
        return null;
    }


    private Void setHold(Long sgId, boolean hold) {
        studentGroupRepository.findById(sgId).ifPresent(sg -> {
            sg.setBillingHold(hold ? Boolean.TRUE : null);
            studentGroupRepository.save(sg);
        });
        return null;
    }

    // ── 4. Qisman qaytarish va qayta qo'llash (§9.7) ───────────────────

    public record RevertResult(Long runId, Long studentGroupId, int reversed, int periodsDeleted,
                               BigDecimal balanceAfter, List<String> warnings) {
    }

    /**
     * Run'ning shu SG dagi yozuvlariga REVERSAL, run davrlari o'chiriladi (yagona DELETE
     * istisnosi — faqat {@code migration_run_id} bo'yicha), SG hold (MIGRATION_PENDING).
     */
    @Transactional
    public RevertResult revertSg(Long runId, Long sgId, String confirm) {
        String expected = "REVERT-" + runId + "-" + sgId;
        if (!expected.equals(confirm)) {
            throw CodedException.badRequest("migration.confirmRequired", expected);
        }
        BillingMigrationRun run = runRepository.findById(runId)
            .orElseThrow(() -> CodedException.notFound("migration.notFound", runId));
        if (run.getAppliedAt() == null) {
            throw new ConflictException("migration.notApplied");
        }
        Long studentId = studentGroupRepository.findStudentIdById(sgId)
            .orElseThrow(() -> CodedException.notFound("error.studentGroup.notFound", sgId));
        StudentGroup sg = locks.lockEnrollmentWithStudent(studentId, sgId);

        Set<Long> alreadyReversed = new HashSet<>();
        for (BalanceTransaction t : transactionRepository.findByStudentGroup_IdOrderByIdAsc(sgId)) {
            if (t.getType() == BalanceTransactionType.REVERSAL && t.getRelatedTxId() != null) {
                alreadyReversed.add(t.getRelatedTxId());
            }
        }
        int reversed = 0;
        for (BalanceTransaction t : transactionRepository.findByMigrationRunIdAndStudentGroup_Id(runId, sgId)) {
            if (t.getType() == BalanceTransactionType.REVERSAL || alreadyReversed.contains(t.getId())) {
                continue;
            }
            ledger.reverse(t, "Migratsiya qaytarildi (run #" + runId + ")");
            reversed++;
        }
        List<BillingPeriod> periods = periodRepository.findByMigrationRunIdAndStudentGroupId(runId, sgId);
        periodRepository.deleteAll(periods);
        periodRepository.flush();

        List<String> warnings = new ArrayList<>();
        if (!periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sgId).isEmpty()) {
            warnings.add("migration.revert.laterPeriods");
        }
        sg.setBillingHold(Boolean.TRUE);
        studentGroupRepository.save(sg);
        BillingSnapshot after = snapshotService.refresh(sg);
        return new RevertResult(runId, sgId, reversed, periods.size(), after.balance(), warnings);
    }

    /**
     * Hold'dagi bitta SG ni qayta qo'llash (§9.7 "migratsiyani SG bo'yicha qayta qo'llash").
     * Run parametrlari (T, G, A14) bilan; keyin hold olinadi va bugungacha accrual.
     */
    @Transactional
    public MigrationPlanner.SgPlan applySg(Long runId, Long sgId, String confirm) {
        String expected = "APPLY-" + runId + "-" + sgId;
        if (!expected.equals(confirm)) {
            throw CodedException.badRequest("migration.confirmRequired", expected);
        }
        BillingMigrationRun run = runRepository.findById(runId)
            .orElseThrow(() -> CodedException.notFound("migration.notFound", runId));
        if (run.getAppliedAt() == null) {
            throw new ConflictException("migration.notApplied");
        }
        StudentGroup current = studentGroupRepository.findById(sgId)
            .orElseThrow(() -> CodedException.notFound("error.studentGroup.notFound", sgId));
        if (!Boolean.TRUE.equals(current.getBillingHold())) {
            throw new ConflictException("migration.sgNotHeld");
        }
        MigrationPlanner.Options o = options(run).asHeldNet();   // §9.7.1: hold — sof hisob
        if (planner.planOne(current, o).alreadyMigrated()) {
            throw new ConflictException("migration.sgAlreadyMigrated");
        }
        applyOne(sgId, runId, o, Boolean.TRUE.equals(run.getClearOverrides()));
        StudentGroup sg = studentGroupRepository.findById(sgId).orElseThrow();
        if (gate.isEnabled()) {
            accrualService.accrueLocked(sg, statusService.today());
        }
        return planner.planOne(sg, o);
    }

    // ── 5. Tekshiruv (§9.6 qadam 4) ────────────────────────────────────

    public record Verification(boolean ok, int sgChecked, List<Long> sgBalanceMismatch,
                               List<Long> studentBalanceMismatch, List<Long> chargedPeriodsWithoutLedger,
                               List<Long> paymentsWithoutCash, List<Long> heldEnrollments) {
    }

    /** I1 (SG va o'quvchi balansi = ledger), I4 (davr ↔ charge), I5 (v2 to'lov ↔ kassa). Faqat o'qiydi. */
    @Transactional(readOnly = true)
    public Verification verify() {
        List<Long> sgMismatch = new ArrayList<>();
        List<Long> held = new ArrayList<>();
        List<Long> periodsWithout = new ArrayList<>();
        List<StudentGroup> all = studentGroupRepository.findAll(Sort.by("id"));
        for (StudentGroup sg : all) {
            BigDecimal sum = Money.nz(transactionRepository.sumAmountByStudentGroupId(sg.getId()));
            if (!Money.eq(Money.nz(sg.getBalance()), sum)) {
                sgMismatch.add(sg.getId());
            }
            if (Boolean.TRUE.equals(sg.getBillingHold())) {
                held.add(sg.getId());
            }
            for (BillingPeriod p : periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sg.getId())) {
                if (p.getStatus() != BillingPeriodStatus.MIGRATED && Money.nz(p.getAmount()).signum() > 0
                        && p.getChargeTxId() == null) {
                    periodsWithout.add(p.getId());
                }
            }
        }
        List<Long> studentMismatch = new ArrayList<>();
        for (Student s : studentRepository.findAll(Sort.by("id"))) {
            BigDecimal sum = Money.nz(studentGroupRepository.sumBalanceByStudentId(s.getId()));
            if (!Money.eq(Money.nz(s.getBalance()), sum)) {
                studentMismatch.add(s.getId());
            }
        }
        long since = runRepository.findAllByOrderByIdDesc().stream()
            .filter(r -> r.getAppliedAt() != null && r.getMaxPaymentId() != null)
            .map(BillingMigrationRun::getMaxPaymentId).findFirst().orElse(Long.MAX_VALUE);
        List<Long> paymentsWithout = paymentRepository.findAll(Sort.by("id")).stream()
            .filter(p -> p.getId() > since && p.getStatus() == PaymentStatus.PAID
                && Money.nz(p.getCashAmount()).signum() > 0 && p.getCashTransactionId() == null)
            .map(Payment::getId).toList();
        boolean ok = sgMismatch.isEmpty() && studentMismatch.isEmpty() && periodsWithout.isEmpty()
            && paymentsWithout.isEmpty();
        return new Verification(ok, all.size(), cap(sgMismatch), cap(studentMismatch), cap(periodsWithout),
            cap(paymentsWithout), cap(held));
    }

    // ── 6. Rollback ma'lumoti (§9.7) ───────────────────────────────────

    /** Cutover'dan keyin kiritilgan to'lovlar (bekor qilinganlari ham) — eski UI ga qayta kiritish uchun. */
    @Transactional(readOnly = true)
    public byte[] paymentsSinceXlsx(LocalDateTime from) {
        if (from == null) {
            throw CodedException.badRequest("migration.cutoverRequired");
        }
        // Lazy bog'lanishlar (o'quvchi, guruh, kassa) tranzaksiya ichida o'qiladi
        return MigrationXlsx.payments(paymentRepository.findByCreatedAtGreaterThanEqualOrderByIdAsc(from));
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BillingMigrationRun> runs() {
        return runRepository.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public BillingMigrationRun run(Long id) {
        return runRepository.findById(id).orElseThrow(() -> CodedException.notFound("migration.notFound", id));
    }

    private MigrationPlanner.Options options(LocalDate cutover, boolean a14UsePayable) {
        if (cutover == null) {
            throw CodedException.badRequest("migration.cutoverRequired");
        }
        return new MigrationPlanner.Options(cutover, properties.getMigrationGoLive(), a14UsePayable);
    }

    private static MigrationPlanner.Options options(BillingMigrationRun run) {
        return new MigrationPlanner.Options(run.getCutoverDate(), run.getGoLiveDate(), run.isA14UsePayable());
    }

    private <T> T inNewTx(java.util.function.Supplier<T> body) {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t.execute(s -> body.get());
    }

    private static <T> List<T> cap(List<T> list) {
        return list.size() > 200 ? list.subList(0, 200) : list;
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }
}

package com.crm.billing;

import com.crm.dto.response.ManualTransferDtos.Classification;
import com.crm.dto.response.ManualTransferDtos.Report;
import com.crm.dto.response.ManualTransferDtos.Row;
import com.crm.entity.Group;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.exception.CodedException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.EnrollmentTransferLinkRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Qo'lda ko'chirishlarni aniqlash va tasniflash ({@code GET /api/admin/repair/manual-transfers}); SQL ko'rinishi —
 * {@code docs/ops/exit-readd-detect.sql} (xuddi shu qoidalar). Faqat o'qiydi.
 *
 * <p><b>Juftlik.</b> Eski yozilma O — TRANSFERRED sabab bilan yopilgan (muzlatilmagan); yangi yozilma N — o'sha o'quvchi,
 * {@code N.id > O.id}, {@code |N.join_date − O.exit_date| ≤ 7}. Zanjir (A→B→C): O ning nomzodlaridan keyinroq yaratilgan
 * boshqa TRANSFERRED chiqishning nomzodlari chiqariladi (A → {B}, B → {C}). Bir nechta nomzod qolsa yoki hech biri
 * qolmasa (nomzodni keyingi chiqish olgan) — UNCLEAR.
 *
 * <p><b>Tasnif</b> — {@link EnrollmentLifecycleService#planMerge} (transfer qoidasi) bo'yicha, shu tartibda:
 * TRANSFER_OK (ta'mir bog'lanishi; yoki ustma-ust davr yo'q va TRANSFER ledger juftligi / langar uzluksiz) →
 * UNCLEAR (nomzod noaniq, zanjir, sinov) → NO_LOSS (kredit ≤ 0) → UNCLEAR (PER_LESSON, muzlatish, hold; REFUND_ONLY) →
 * AFFECTED.
 */
@Service
@RequiredArgsConstructor
public class ManualTransferDetectionService {

    public static final int WINDOW_DAYS = 7;
    public static final int DEFAULT_RANGE_DAYS = 60;

    private final StudentGroupRepository studentGroupRepository;
    private final BalanceTransactionRepository transactionRepository;
    private final EnrollmentTransferLinkRepository linkRepository;
    private final EnrollmentLifecycleService lifecycle;
    private final BillingStatusService statusService;

    /** Juftlik; {@code structural} — nomzod noaniqligi (MULTIPLE_CANDIDATES / NEW_SHARED), {@code chainKey} — zanjir. */
    record Pair(StudentGroup old, StudentGroup neu, String structural, String chainKey) {
    }

    @Transactional(readOnly = true)
    public Report detect(LocalDate from, LocalDate to) {
        LocalDate today = statusService.today();
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS);
        if (start.isAfter(end)) {
            throw CodedException.badRequest("repair.manualTransfer.dateRange");
        }
        List<Row> rows = new ArrayList<>();
        List<Long> studentIds = new ArrayList<>(studentGroupRepository.findStudentIdsWithTransferredExit(start, end));
        studentIds.sort(Comparator.naturalOrder());
        for (Long studentId : studentIds) {
            List<StudentGroup> all = studentGroupRepository.findByStudentId(studentId);
            for (Pair p : pairs(all, start, end)) {
                rows.add(classify(p, all));
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Classification c : Classification.values()) {
            counts.put(c.name(), rows.stream().filter(r -> r.classification() == c).count());
        }
        List<Row> affected = rows.stream().filter(r -> r.classification() == Classification.AFFECTED).toList();
        BigDecimal total = affected.stream().map(Row::unusedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Report(start, end, rows.size(), counts, affected.size(), Money.normalize(total), rows);
    }

    // ── juftliklar ──────────────────────────────────────────────────────

    List<Pair> pairs(List<StudentGroup> enrollments, LocalDate from, LocalDate to) {
        List<StudentGroup> all = enrollments.stream().sorted(Comparator.comparing(StudentGroup::getId)).toList();
        List<StudentGroup> exits = all.stream().filter(ManualTransferDetectionService::isTransferredExit).toList();
        Map<Long, List<StudentGroup>> raw = new HashMap<>();
        for (StudentGroup o : exits) {
            LocalDate exit = exitDate(o);
            raw.put(o.getId(), all.stream()
                .filter(n -> n.getId() > o.getId() && n.getJoinDate() != null
                    && Math.abs(ChronoUnit.DAYS.between(exit, n.getJoinDate())) <= WINDOW_DAYS)
                .toList());
        }
        List<Pair> found = new ArrayList<>();
        for (StudentGroup o : exits) {
            List<StudentGroup> candidates = raw.get(o.getId());
            if (candidates.isEmpty()) {
                continue;
            }
            Set<Long> claimed = new HashSet<>();
            for (StudentGroup later : exits) {
                if (later.getId() > o.getId()) {
                    raw.get(later.getId()).forEach(n -> claimed.add(n.getId()));
                }
            }
            List<StudentGroup> own = candidates.stream().filter(n -> !claimed.contains(n.getId())).toList();
            if (own.isEmpty()) {
                candidates.forEach(n -> found.add(new Pair(o, n, "NEW_SHARED", null)));
            } else if (own.size() > 1) {
                own.forEach(n -> found.add(new Pair(o, n, "MULTIPLE_CANDIDATES", null)));
            } else {
                found.add(new Pair(o, own.get(0), null, null));
            }
        }
        // Zanjir: juftlikning yangi yozilmasi boshqasining eskisi (yoki aksincha) — kalit zanjir boshidagi eski yozilma
        Map<Long, Pair> byNew = new HashMap<>();
        Set<Long> olds = new HashSet<>();
        for (Pair p : found) {
            if (p.structural() == null) {
                byNew.put(p.neu().getId(), p);
                olds.add(p.old().getId());
            }
        }
        List<Pair> out = new ArrayList<>();
        for (Pair p : found) {
            LocalDate exit = exitDate(p.old());
            if (exit.isBefore(from) || exit.isAfter(to)) {
                continue;
            }
            String chain = null;
            if (p.structural() == null && (byNew.containsKey(p.old().getId()) || olds.contains(p.neu().getId()))) {
                Pair root = p;
                Set<Long> seen = new HashSet<>();
                while (byNew.containsKey(root.old().getId()) && seen.add(root.old().getId())) {
                    root = byNew.get(root.old().getId());
                }
                chain = p.old().getStudent().getId() + ":" + root.old().getId();
            }
            out.add(new Pair(p.old(), p.neu(), p.structural(), chain));
        }
        return out;
    }

    static boolean isTransferredExit(StudentGroup sg) {
        return !Boolean.TRUE.equals(sg.getIsActive()) && sg.getFrozenFrom() == null
            && (sg.getExitReasonCode() == ExitReasonCode.TRANSFERRED
                || (sg.getExitReason() != null && "TRANSFERRED".equals(sg.getExitReason().trim().toUpperCase(Locale.ROOT))))
            && exitDate(sg) != null;
    }

    static LocalDate exitDate(StudentGroup sg) {
        return sg.getExitDate() != null ? sg.getExitDate() : sg.getLeaveDate();
    }

    // ── tasnif ──────────────────────────────────────────────────────────

    Row classify(Pair p, List<StudentGroup> all) {
        StudentGroup o = p.old();
        StudentGroup n = p.neu();
        EnrollmentLifecycleService.MergePlan plan = lifecycle.planMerge(o, n);
        Classification c;
        String code;
        if (linkRepository.existsByOldStudentGroupIdAndNewStudentGroupId(o.getId(), n.getId())) {
            c = Classification.TRANSFER_OK;
            code = "REPAIRED";
        } else if (plan.refundTotal().signum() == 0 && (ledgerLinked(o, n)
                || (plan.moved().signum() == 0 && Objects.equals(n.getPaymentStartDate(), plan.continuedAnchor())))) {
            c = Classification.TRANSFER_OK;
            code = "TRANSFER_FLOW";
        } else if (p.structural() != null) {
            c = Classification.UNCLEAR;
            code = p.structural();
        } else if (p.chainKey() != null) {
            c = Classification.UNCLEAR;
            code = "CHAIN";
        } else if (Boolean.TRUE.equals(o.getIsTrial()) || Boolean.TRUE.equals(n.getIsTrial())) {
            c = Classification.UNCLEAR;
            code = "TRIAL";
        } else if (plan.credit().signum() <= 0) {
            c = Classification.NO_LOSS;
            code = plan.refundTotal().signum() > 0 ? "NO_LOSS_UNPAID" : "NO_LOSS";
        } else {
            String blocker = lifecycle.mergeBlocker(o, n);
            if (blocker != null) {
                c = Classification.UNCLEAR;
                code = blocker;
            } else if (plan.mode() == EnrollmentLifecycleService.MergeMode.REFUND_ONLY) {
                c = Classification.UNCLEAR;
                code = "REFUND_ONLY";
            } else {
                c = Classification.AFFECTED;
                code = "AFFECTED";
            }
        }
        BigDecimal debt = all.stream().filter(sg -> Boolean.TRUE.equals(sg.getIsActive()))
            .map(sg -> Money.nz(sg.getBalance()).negate().max(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        Student s = o.getStudent();
        Group og = o.getGroup();
        Group ng = n.getGroup();
        return new Row(s.getId(), name(s), s.getAdmissionNumber(),
            o.getId(), og != null ? og.getId() : null, og != null ? og.getGroupName() : null, o.getJoinDate(), exitDate(o),
            n.getId(), ng != null ? ng.getId() : null, ng != null ? ng.getGroupName() : null, n.getJoinDate(),
            n.getPaymentStartDate(), Boolean.TRUE.equals(n.getIsActive()),
            plan.continuedAnchor(), plan.mode().name(), Money.normalize(plan.refundTotal()),
            Money.normalize(plan.moved()), Money.normalize(plan.credit().max(BigDecimal.ZERO)),
            Money.normalize(Money.nz(n.getBalance())), Money.normalize(debt),
            c, code, reason(code, plan), p.chainKey());
    }

    private boolean ledgerLinked(StudentGroup o, StudentGroup n) {
        return transactionRepository.existsByStudentGroup_IdAndTypeAndReferenceId(
                o.getId(), BalanceTransactionType.TRANSFER_OUT, n.getId())
            || transactionRepository.existsByStudentGroup_IdAndTypeAndReferenceId(
                n.getId(), BalanceTransactionType.TRANSFER_IN, o.getId());
    }

    /** Sabab matni (o'zbekcha) — aniqlash va ta'mir javobida bir xil. */
    public static String reason(String code, EnrollmentLifecycleService.MergePlan plan) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "REPAIRED" -> "Ta'mirlangan (REPAIR_MANUAL_TRANSFER) — transfer bilan bog'langan";
            case "TRANSFER_FLOW" -> "Guruhga ko'chirish oqimi (yoki unga teng): langar uzluksiz, ustma-ust hisob yo'q";
            case "MULTIPLE_CANDIDATES" -> "Bir nechta yangi yozilma nomzodi — qaysi biriga ko'chgani noaniq";
            case "NEW_SHARED" -> "Yangi yozilma keyingi boshqa chiqishga ham nomzod (bir nechta guruhdan bittaga)";
            case "CHAIN" -> "Zanjir (A→B→C): juftliklarni birga ko'rib, aniq pairs bilan vaqt tartibida ta'mirlang";
            case "NO_LOSS_UNPAID" -> "Eski davr to'lanmagan — ko'chadigan pul yo'q (eski qarz yopilgan yozilmada qoldi)";
            case "NO_LOSS" -> "Ko'chadigan pul yo'q (eski davr ishlatilgan yoki ustma-ust hisob yo'q)";
            case "TRIAL" -> "Sinov yozilmasi — qo'lda ko'ring";
            case "PER_LESSON" -> "Darsbay to'lov (PER_LESSON) — qo'lda ko'ring";
            case "NEW_FROZEN" -> "Yangi yozilma muzlatilgan — qo'lda ko'ring";
            case "OLD_FROZEN" -> "Eski yozilma muzlatilgan";
            case "OLD_ACTIVE" -> "Eski yozilma hali faol — \"Guruhga ko'chirish\" dan foydalaning";
            case "BILLING_HOLD" -> "Yangi yozilma billing hold (migratsiya kutilmoqda)";
            case "INVALID_PAIR" -> "Juftlik noto'g'ri (boshqa o'quvchi yoki bir xil yozilma)";
            case "PREVIOUS_FAILED" -> "Shu o'quvchining oldingi juftligi qo'llanmadi";
            case "REFUND_ONLY" -> plan == null ? "Yangi yozilmada langardan keyin boshlangan davr bor"
                : "Yangi yozilmada " + plan.laterPeriodStart() + " dan boshlangan davr ham bor — langarni "
                    + plan.continuedAnchor() + " ga surib bo'lmaydi; aniq pairs bilan qo'llansa faqat ustma-ust davrlar "
                    + "qaytariladi (" + ChronoUnit.DAYS.between(plan.continuedAnchor(), plan.laterPeriodStart())
                    + " kun hisobsiz qoladi)";
            case "AFFECTED" -> plan == null ? "Ta'mir kerak"
                : "Eski guruhda " + plan.continuedAnchor().minusDays(1) + " gacha qoplangan, yangi guruhda shu davr "
                    + "uchun yana hisob — " + Money.normalize(plan.credit()).toPlainString() + " so'm kredit o'tadi";
            default -> code;
        };
    }

    private static String name(Student s) {
        return s == null ? null : ((s.getFirstName() != null ? s.getFirstName() : "") + " "
            + (s.getLastName() != null ? s.getLastName() : "")).trim();
    }
}

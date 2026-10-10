package com.crm.billing;

import com.crm.dto.response.GroupEndDateDtos;
import com.crm.entity.Group;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Guruh tugash sanasi va billing (billing-v2 R3, §14.3).
 *
 * <p>R3: {@code end_date} kuni yoki undan keyin boshlanadigan davr ochilmaydi ({@link AccrualCalculator#isAccruable}).
 * {@code end_date} noto'g'ri (o'tgan yoki boshlanishdan oldin) kiritilsa, guruhning barcha yozilmalarida hisob jim
 * to'xtaydi. Bu klass: sanalar validatsiyasi, o'zgarish ta'siri preview'i (yozmaydi), o'zgargandan keyin shu
 * tranzaksiyada yetishmagan davrlarni yozish (accrual qoidalari bilan) va diqqat talab qiladigan guruhlar ro'yxati.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupEndDateService {

    /** Hisob yuradigan guruh holatlari ({@link AccrualCalculator#isAccruable}, §13 #14). */
    public static final Set<GroupStatus> BILLING_STATUSES = EnumSet.of(GroupStatus.ACTIVE, GroupStatus.FORMING);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final BillingStatusService statusService;
    private final AccrualService accrualService;
    private final BillingLocks locks;
    private final BillingGate gate;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final LastPeriodRecalcService recalcService;

    // ── Validatsiya ─────────────────────────────────────────────────────

    /**
     * Guruh yaratish / tahrirlash: {@code end_date > start_date} (400 {@code group.endDate.beforeStart}); ACTIVE/FORMING
     * guruhda {@code end_date ≥ bugun} (400 {@code group.endDate.past}). {@code end_date} bo'sh — cheklovsiz.
     */
    public void validateDates(LocalDate startDate, LocalDate endDate, GroupStatus status) {
        if (endDate == null) {
            return;
        }
        if (startDate != null && !endDate.isAfter(startDate)) {
            throw CodedException.badRequest("group.endDate.beforeStart", endDate.format(FMT), startDate.format(FMT));
        }
        validateNotPast(endDate, status);
    }

    /** Holat o'zgarishi (PATCH status): ACTIVE/FORMING ga o'tkazilayotgan guruhning {@code end_date} o'tmagan bo'lsin. */
    public void validateNotPast(LocalDate endDate, GroupStatus status) {
        if (endDate != null && status != null && BILLING_STATUSES.contains(status)
                && endDate.isBefore(statusService.today())) {
            throw CodedException.badRequest("group.endDate.past", endDate.format(FMT));
        }
    }

    // ── Ta'sir preview'i (faqat o'qiydi) ────────────────────────────────

    /**
     * {@code end_date = newEndDate} bo'lsa nima yoziladi: har ochiq yozilma uchun bugungacha yetishmagan davrlar
     * (aynan {@link AccrualService#planWithGroupEnd} — accrual yozadigan davrlar), balans oldin → keyin, qarzdorlik va
     * yangi keyingi to'lov sanasi. Validatsiya {@link #validateDates} bilan bir xil.
     */
    @Transactional(readOnly = true)
    public GroupEndDateDtos.Impact impact(Long groupId, LocalDate newEndDate) {
        Group group = groupRepository.findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
        validateDates(group.getStartDate(), newEndDate, group.getStatus());
        LocalDate today = statusService.today();

        List<GroupEndDateDtos.ImpactRow> rows = new ArrayList<>();
        int affected = 0;
        int periods = 0;
        int recalculated = 0;
        int held = 0;
        int debtorsBefore = 0;
        int debtorsAfter = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (StudentGroup sg : openEnrollments(groupId)) {
            List<FifoDebt.Line> lines = new ArrayList<>();
            List<LocalDate> starts = new ArrayList<>();
            BigDecimal charge = BigDecimal.ZERO;
            // Yozilgan oxirgi davr(lar) qayta hisobi — catch-up'dagi kabi asl charge'ga bog'langan farq
            List<GroupEndDateDtos.Recalc> recalcs = recalcService.plan(sg, newEndDate);
            for (GroupEndDateDtos.Recalc r : recalcs) {
                if (r.diff().signum() != 0) {
                    lines.add(new FifoDebt.Line(null, r.diff().negate(), r.start(), r.chargeTxId()));
                    charge = charge.add(r.diff());
                }
            }
            AccrualCalculator.Result plan = accrualService.planWithGroupEnd(sg, today, newEndDate);
            List<GroupEndDateDtos.Period> planned = new ArrayList<>();
            for (AccrualCalculator.DueCharge c : plan.charges()) {
                starts.add(c.periodStart());
                planned.add(period(c.periodStart(), c.periodEnd(), c.amount(), c.proratedLessons(), c.lessonPrice()));
                if (c.amount().signum() > 0) {
                    lines.add(new FifoDebt.Line(null, c.amount().negate(), c.periodStart(), null));
                    charge = charge.add(c.amount());
                }
            }
            BillingSnapshot before = statusService.snapshot(sg, today);
            BillingSnapshot after = statusService.snapshotWith(sg, lines, starts, today, newEndDate);
            boolean wasDebtor = before.status() == PaymentStatus.OVERDUE;
            boolean isDebtor = after.status() == PaymentStatus.OVERDUE;

            rows.add(new GroupEndDateDtos.ImpactRow(sg.getId(), sg.getStudent().getId(), fullName(sg.getStudent()),
                skipReason(sg, group), planned, recalcs, Money.normalize(charge),
                Money.normalize(before.balance()), Money.normalize(after.balance()), Money.normalize(after.debt()),
                after.debtSince(), before.status().name(), after.status().name(), wasDebtor, isDebtor,
                before.nextPaymentDate(), after.nextPaymentDate(),
                after.nextPaymentAmount() != null ? Money.normalize(after.nextPaymentAmount()) : null));
            if (!planned.isEmpty() || !recalcs.isEmpty()) {
                affected++;
            }
            periods += planned.size();
            recalculated += recalcs.size();
            amount = amount.add(charge);
            held += Boolean.TRUE.equals(sg.getBillingHold()) ? 1 : 0;
            debtorsBefore += wasDebtor ? 1 : 0;
            debtorsAfter += isDebtor ? 1 : 0;
        }
        return new GroupEndDateDtos.Impact(group.getId(), group.getGroupName(),
            group.getStatus() != null ? group.getStatus().name() : null, group.getStartDate(), group.getEndDate(),
            newEndDate, today, rows,
            new GroupEndDateDtos.ImpactTotals(rows.size(), affected, periods, recalculated, Money.normalize(amount),
                debtorsBefore, debtorsAfter, held));
    }

    // ── O'zgargandan keyin: yetishmagan davrlar (shu tranzaksiyada) ────

    /**
     * Guruh {@code end_date} yoki holati o'zgargandan keyin chaqiriladi (chaqiruvchi tranzaksiyasida, guruh
     * o'zgarishi flush qilingan). Har ochiq yozilma qulf ostida {@link AccrualService#accrueLocked} dan o'tadi:
     * langardan (yoki oxirgi yozilgan davrdan) bugungacha yetishmagan davrlar yoziladi — 0 davrli yozilma ham
     * langardan boshlab; hold'dagi yozilmada davr yozilmaydi ({@link AccrualService#plan}); snapshot har birida
     * yangilanadi. Qisqartirilganda hech narsa o'chirilmaydi — accrual faqat qo'shadi.
     * Billing o'chiq bo'lsa ({@code app.billing.enabled=false}) — o'tkazib yuboriladi, kunlik job keyin yozadi.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public GroupEndDateDtos.CatchUp catchUp(Group group) {
        List<GroupEndDateDtos.CatchUpRow> rows = new ArrayList<>();
        if (!gate.isEnabled()) {
            log.warn("Billing o'chiq — guruh {} uchun catch-up o'tkazib yuborildi", group.getId());
            return new GroupEndDateDtos.CatchUp(group.getId(), 0, 0, 0, BigDecimal.ZERO, rows);
        }
        LocalDate today = statusService.today();
        int periods = 0;
        int recalculated = 0;
        BigDecimal amount = BigDecimal.ZERO;
        // Qulf tartibi (BillingLocks): o'quvchi → yozilma, id o'sishida
        List<StudentGroup> open = openEnrollments(group.getId()).stream()
            .sorted(Comparator.comparing((StudentGroup sg) -> sg.getStudent().getId()).thenComparing(StudentGroup::getId))
            .toList();
        for (StudentGroup candidate : open) {
            Long studentId = candidate.getStudent().getId();
            StudentGroup sg = locks.lockEnrollmentWithStudent(studentId, candidate.getId());
            // Avval yozilgan oxirgi davr yangi tugash sanasi bilan qayta hisoblanadi, keyin yetishmagan davrlar
            List<GroupEndDateDtos.Recalc> recalcs = recalcService.apply(sg);
            AccrualService.AccrualResult result = accrualService.accrueLocked(sg, today);
            if (result.created().isEmpty() && recalcs.isEmpty()) {
                continue;
            }
            List<GroupEndDateDtos.Period> written = result.created().stream()
                .map(p -> period(p.getPeriodStart(), p.getPeriodEnd(), p.getAmount(), p.getProratedLessons(),
                    p.getLessonPrice()))
                .toList();
            BigDecimal charge = written.stream().map(GroupEndDateDtos.Period::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(recalcs.stream().map(GroupEndDateDtos.Recalc::diff).reduce(BigDecimal.ZERO, BigDecimal::add));
            rows.add(new GroupEndDateDtos.CatchUpRow(sg.getId(), studentId, written, recalcs, Money.normalize(charge)));
            periods += written.size();
            recalculated += recalcs.size();
            amount = amount.add(charge);
        }
        if (periods > 0 || recalculated > 0) {
            log.info("Guruh {} end_date/holat o'zgardi — {} yozilmada {} davr yozildi, {} davr qayta hisoblandi ({} so'm)",
                group.getId(), rows.size(), periods, recalculated, amount.toPlainString());
        }
        return new GroupEndDateDtos.CatchUp(group.getId(), rows.size(), periods, recalculated, Money.normalize(amount),
            rows);
    }

    // ── Diqqat talab qiladigan guruhlar ─────────────────────────────────

    /**
     * ACTIVE/FORMING, {@code end_date ≤ bugun} (yoki boshlanishdan oldin) guruhlar va ularda R3 sababli to'xtagan
     * yozilmalar: tugash sanasisiz hisoblansa bugungacha yoziladigan davr bor, joriy sana bilan — yo'q.
     */
    @Transactional(readOnly = true)
    public GroupEndDateDtos.Attention attention() {
        LocalDate today = statusService.today();
        List<GroupEndDateDtos.AttentionRow> rows = new ArrayList<>();
        int stoppedTotal = 0;
        for (Group g : groupRepository.findEndDateAttention(BILLING_STATUSES, today)) {
            int open = 0;
            int stopped = 0;
            int held = 0;
            int missed = 0;
            BigDecimal missedAmount = BigDecimal.ZERO;
            for (StudentGroup sg : openEnrollments(g.getId())) {
                open++;
                if (Boolean.TRUE.equals(sg.getBillingHold())) {
                    held++;
                    continue;
                }
                List<AccrualCalculator.DueCharge> withoutEnd = accrualService.planWithGroupEnd(sg, today, null).charges();
                int withEnd = accrualService.plan(sg, today).charges().size();
                if (withoutEnd.size() > withEnd) {
                    stopped++;
                    missed += withoutEnd.size() - withEnd;
                    for (AccrualCalculator.DueCharge c : withoutEnd.subList(withEnd, withoutEnd.size())) {
                        missedAmount = missedAmount.add(c.amount());
                    }
                }
            }
            stoppedTotal += stopped;
            rows.add(new GroupEndDateDtos.AttentionRow(g.getId(), g.getGroupName(), g.getStatus().name(),
                g.getStartDate(), g.getEndDate(), g.getStartDate() != null && !g.getEndDate().isAfter(g.getStartDate()),
                open, stopped, held, missed, Money.normalize(missedAmount)));
        }
        return new GroupEndDateDtos.Attention(today, rows.size(), stoppedTotal, rows);
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private static GroupEndDateDtos.Period period(LocalDate start, LocalDate end, BigDecimal amount, Integer lessons,
                                                  BigDecimal lessonPrice) {
        return new GroupEndDateDtos.Period(start, end, Money.normalize(Money.nz(amount)), lessons,
            lessonPrice != null ? Money.normalize(lessonPrice) : null);
    }

    private List<StudentGroup> openEnrollments(Long groupId) {
        return studentGroupRepository.findByGroupId(groupId).stream()
            .filter(BillingStatusService::isOpen)
            .sorted(Comparator.comparing(StudentGroup::getId))
            .toList();
    }

    private static String skipReason(StudentGroup sg, Group group) {
        if (Boolean.TRUE.equals(sg.getBillingHold())) {
            return "HOLD";
        }
        if (sg.getFrozenFrom() != null || !Boolean.TRUE.equals(sg.getIsActive())) {
            return "FROZEN";
        }
        if (Boolean.TRUE.equals(sg.getIsTrial())) {
            return "TRIAL";
        }
        if (sg.getPaymentType() == PaymentType.PER_LESSON) {
            return "PER_LESSON";
        }
        if (sg.getPaymentStartDate() == null) {
            return "NO_ANCHOR";
        }
        if (group.getStatus() != null && !BILLING_STATUSES.contains(group.getStatus())) {
            return "GROUP_STATUS";
        }
        return null;
    }

    private static String fullName(Student s) {
        return ((s.getFirstName() != null ? s.getFirstName() : "") + " "
            + (s.getLastName() != null ? s.getLastName() : "")).trim();
    }
}

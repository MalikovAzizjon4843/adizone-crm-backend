package com.crm.billing;

import com.crm.dto.response.DebtorsListResponse;
import com.crm.dto.response.ExpectedPaymentsResponse;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Qarzdorlar va kutilayotgan to'lovlar — YAGONA ta'rif (docs/design/billing-v2.md §4.5).
 *
 * <blockquote>Qarzdor = kamida bitta SG si OVERDUE bo'lgan o'quvchi.
 * Qarz = Σ max(0, −sg.balance) (shu o'quvchining OVERDUE va PENDING SG lari).</blockquote>
 *
 * Dashboard, analitika, {@code /debtors}, {@code /debtors/summary}, Telegram eslatma
 * hammasi shu klassni chaqiradi; KPI SQL'i {@link BillingStatusService#overdueBefore}
 * bilan bir xil shartni ishlatadi.
 */
@Service
@RequiredArgsConstructor
public class DebtorService {

    private final BillingStatusService statusService;
    private final StudentGroupRepository studentGroupRepository;

    public enum Scope {
        /** Faol yoki muzlatilgan o'quvchining ochiq SG lari (default, §13 #4). */
        ACTIVE,
        /** Yopilgan SG qarzlari ham. */
        ALL;

        public static Scope parse(String raw) {
            return raw != null && raw.trim().equalsIgnoreCase("ALL") ? ALL : ACTIVE;
        }
    }

    public record Filter(Scope scope, Integer minDays, Long groupId, Integer page, Integer size) {
        public static Filter defaults() {
            return new Filter(Scope.ACTIVE, null, null, null, null);
        }
    }

    public record Summary(long totalDebtors, long overdue7Plus, BigDecimal totalDebt, BigDecimal closedDebt) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalDebtors", totalDebtors);
            m.put("overdue7Plus", overdue7Plus);
            m.put("totalDebt", totalDebt);
            m.put("closedDebt", closedDebt);
            return m;
        }
    }

    @Transactional(readOnly = true)
    public DebtorsListResponse debtors(Filter filter, LocalDate today) {
        List<DebtorsListResponse.DebtorStudent> all = collect(filter, today);
        int page = filter.page() != null ? Math.max(filter.page(), 0) : 0;
        int size = filter.size() != null ? Math.min(Math.max(filter.size(), 1), 500) : all.size();
        List<DebtorsListResponse.DebtorStudent> pageRows = all.stream()
            .skip((long) page * Math.max(size, 1))
            .limit(Math.max(size, 0))
            .toList();
        Summary summary = summarize(all, filter, today);
        return DebtorsListResponse.builder()
            .totalDebtors(summary.totalDebtors())
            .overdue7Plus(summary.overdue7Plus())
            .totalDebt(summary.totalDebt())
            .scope(filter.scope().name())
            .page(page)
            .size(size)
            .students(new ArrayList<>(pageRows))
            .build();
    }

    @Transactional(readOnly = true)
    public Summary debtorSummary(Filter filter, LocalDate today) {
        return summarize(collect(filter, today), filter, today);
    }

    /** Qarzdor o'quvchilar soni (dashboard, analitika). */
    @Transactional(readOnly = true)
    public long countDebtors(LocalDate today) {
        return debtorSummary(Filter.defaults(), today).totalDebtors();
    }

    private Summary summarize(List<DebtorsListResponse.DebtorStudent> rows, Filter filter, LocalDate today) {
        BigDecimal total = rows.stream().map(DebtorsListResponse.DebtorStudent::getDebt)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        long seven = rows.stream().filter(r -> r.getDaysOverdue() >= 7).count();
        BigDecimal closed = BigDecimal.ZERO;
        if (filter.scope() == Scope.ACTIVE) {
            for (StudentGroup sg : studentGroupRepository.findWithDebt()) {
                if (!BillingStatusService.isOpen(sg)) {
                    closed = closed.add(Money.nz(sg.getBalance()).negate());
                }
            }
        }
        return new Summary(rows.size(), seven, total, closed);
    }

    private List<DebtorsListResponse.DebtorStudent> collect(Filter filter, LocalDate today) {
        Map<Long, List<StudentGroup>> byStudent = new LinkedHashMap<>();
        for (StudentGroup sg : studentGroupRepository.findWithDebt()) {
            if (!inScope(sg, filter.scope())) {
                continue;
            }
            byStudent.computeIfAbsent(sg.getStudent().getId(), k -> new ArrayList<>()).add(sg);
        }

        List<DebtorsListResponse.DebtorStudent> rows = new ArrayList<>();
        for (List<StudentGroup> sgs : byStudent.values()) {
            List<StudentGroup> overdue = sgs.stream().filter(sg -> statusService.isOverdue(sg, today)).toList();
            if (overdue.isEmpty()) {
                continue;
            }
            if (filter.groupId() != null && sgs.stream().noneMatch(sg -> filter.groupId().equals(sg.getGroup().getId()))) {
                continue;
            }
            LocalDate debtSince = overdue.stream().map(StudentGroup::getDebtSince)
                .min(Comparator.naturalOrder()).orElseThrow();
            long days = ChronoUnit.DAYS.between(debtSince, today);
            if (filter.minDays() != null && days < filter.minDays()) {
                continue;
            }
            rows.add(toRow(sgs.get(0).getStudent(), sgs, debtSince, days, today));
        }
        rows.sort(Comparator.comparingLong(DebtorsListResponse.DebtorStudent::getDaysOverdue).reversed()
            .thenComparing(DebtorsListResponse.DebtorStudent::getStudentId));
        return rows;
    }

    private boolean inScope(StudentGroup sg, Scope scope) {
        if (scope == Scope.ALL) {
            return true;
        }
        StudentStatus st = sg.getStudent().getStatus();
        return BillingStatusService.isOpen(sg) && (st == StudentStatus.ACTIVE || st == StudentStatus.FROZEN);
    }

    private DebtorsListResponse.DebtorStudent toRow(Student s, List<StudentGroup> sgs, LocalDate debtSince,
                                                    long days, LocalDate today) {
        List<DebtorsListResponse.DebtorGroup> groups = sgs.stream()
            .sorted(Comparator.comparing(StudentGroup::getDebtSince))
            .map(sg -> DebtorsListResponse.DebtorGroup.builder()
                .studentGroupId(sg.getId())
                .groupId(sg.getGroup().getId())
                .groupName(sg.getGroup().getGroupName())
                .debt(Money.nz(sg.getBalance()).negate())
                .debtSince(sg.getDebtSince())
                .daysOverdue(ChronoUnit.DAYS.between(sg.getDebtSince(), today))
                .status(statusService.statusOf(sg.getBalance(), sg.getDebtSince(), today).name())
                .closed(!BillingStatusService.isOpen(sg))
                .build())
            .toList();
        BigDecimal debt = groups.stream().map(DebtorsListResponse.DebtorGroup::getDebt)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal monthly = Money.nz(s.getMonthlyFee());
        long monthsUnpaid = monthly.signum() > 0 ? Money.ceilDiv(debt, monthly) : 1;
        String fullName = ((s.getFirstName() != null ? s.getFirstName() : "") + " "
            + (s.getLastName() != null ? s.getLastName() : "")).trim();
        return DebtorsListResponse.DebtorStudent.builder()
            .studentId(s.getId())
            .fullName(fullName)
            .phone(s.getPhone())
            .debt(debt)
            .debtSince(debtSince)
            .daysOverdue(days)
            .status(PaymentStatus.OVERDUE.name())
            .monthlyAmount(monthly)
            .groups(new ArrayList<>(groups))
            .totalDebt(debt)
            .amount(monthly)
            .monthsUnpaid(Math.max(monthsUnpaid, 1))
            .nextPaymentDate(debtSince)
            .groupName(groups.isEmpty() ? null : groups.get(0).getGroupName())
            .build();
    }

    // ── Kutilayotgan to'lovlar ─────────────────────────────────────────

    /**
     * {@code GET /api/payments/expected} — SG {@code next_payment_date ∈ [from, to]},
     * holati PAID yoki PENDING, summa — {@code next_payment_amount}. Default {@code from}
     * — BUGUN (bugun yozilgan charge ham ko'rinadi, §4.3 #8).
     */
    @Transactional(readOnly = true)
    public ExpectedPaymentsResponse expected(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate start = from != null ? from : today;
        LocalDate end = to != null ? to : today.plusDays(30);

        Map<LocalDate, List<ExpectedPaymentsResponse.ExpectedStudent>> byDate = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        java.util.Set<Long> students = new java.util.HashSet<>();
        for (StudentGroup sg : studentGroupRepository.findWithNextPaymentBetween(start, end)) {
            if (!BillingStatusService.isBillingOpen(sg)) {
                continue;
            }
            PaymentStatus st = statusService.statusOf(sg.getBalance(), sg.getDebtSince(), today);
            if (st == PaymentStatus.OVERDUE) {
                continue;
            }
            Student s = sg.getStudent();
            if (s.getStatus() != StudentStatus.ACTIVE) {
                continue;
            }
            BigDecimal amount = Money.nz(sg.getNextPaymentAmount());
            byDate.computeIfAbsent(sg.getNextPaymentDate(), k -> new ArrayList<>())
                .add(ExpectedPaymentsResponse.ExpectedStudent.builder()
                    .studentId(s.getId())
                    .fullName(((s.getFirstName() != null ? s.getFirstName() : "") + " "
                        + (s.getLastName() != null ? s.getLastName() : "")).trim())
                    .phone(s.getPhone())
                    .studentGroupId(sg.getId())
                    .groupId(sg.getGroup().getId())
                    .groupName(sg.getGroup().getGroupName())
                    .amount(amount)
                    .paymentStatus(st.name())
                    .daysUntil(ChronoUnit.DAYS.between(today, sg.getNextPaymentDate()))
                    .build());
            total = total.add(amount);
            students.add(s.getId());
        }

        List<ExpectedPaymentsResponse.DayBucket> days = new ArrayList<>();
        byDate.forEach((date, list) -> days.add(ExpectedPaymentsResponse.DayBucket.builder()
            .date(date)
            .studentCount(list.size())
            .dayTotal(list.stream().map(ExpectedPaymentsResponse.ExpectedStudent::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
            .students(list)
            .build()));
        return ExpectedPaymentsResponse.builder()
            .totalStudents(students.size())
            .totalAmount(total)
            .days(days)
            .build();
    }
}

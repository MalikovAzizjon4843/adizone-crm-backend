package com.crm.dashboard;

import com.crm.config.Messages;
import com.crm.dashboard.AnalyticsDtos.SourceGroupBy;
import com.crm.dashboard.AnalyticsDtos.SourceReport;
import com.crm.dashboard.AnalyticsDtos.SourceRow;
import com.crm.dashboard.AnalyticsDtos.StudentSourceRow;
import com.crm.dashboard.AnalyticsDtos.StudentsBySource;
import com.crm.entity.Lead;
import com.crm.entity.MetaLeadForm;
import com.crm.entity.Student;
import com.crm.entity.enums.PaymentStatus;
import com.crm.exception.CodedException;
import com.crm.util.SourceCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Manba statistikasi — o'quvchilar qayerdan kelgan.
 *
 * <p><b>{@code GET /api/analytics/sources}</b> — kogorta: {@code [from, to]} kunlarida
 * (Asia/Tashkent, {@code [from 00:00, to+1 00:00)}) yaratilgan lidlar; har birining HOZIRGACHA
 * yetgan qadamlari. Tashrif — {@code leads.visited_at} (VISITED voronka qadami yoki birinchi davomat),
 * konvertatsiya — lid o'quvchiga aylangan, birinchi to'lov / tushum — shu o'quvchining PAID,
 * {@code cash_amount > 0} to'lovlari (bugungacha). Importlar default chiqariladi — analitika
 * overview bilan bir xil to'plam ({@link FunnelMetricsService#cohortLeadFacts}).
 *
 * <p><b>{@code GET /api/analytics/students-by-source}</b> — hozir o'qiyotganlar: kamida bitta
 * {@code is_active = true AND leave_date IS NULL} yozilmasi bor o'quvchilar, {@code students.source} bo'yicha.
 *
 * <p>Manbasiz qiymatlar doim alohida {@link SourceCatalog#UNKNOWN} qatorida, ro'yxat oxirida.
 */
@Service
@RequiredArgsConstructor
public class SourceAnalyticsService {

    private final DashboardQueries queries;
    private final Messages messages;
    private final Clock billingClock;

    @Transactional(readOnly = true)
    public SourceReport sources(LocalDate from, LocalDate to, String groupBy, Boolean includeImported) {
        LocalDate today = LocalDate.now(billingClock);
        LocalDate f = from != null ? from : (to != null ? to : today).withDayOfMonth(1);
        LocalDate t = to != null ? to : (f.isAfter(today) ? f : today);
        if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) + 1 > DashboardPeriod.MAX_CUSTOM_DAYS) {
            throw CodedException.badRequest("analytics.period.invalid", DashboardPeriod.MAX_CUSTOM_DAYS);
        }
        SourceGroupBy g = parseGroupBy(groupBy);
        boolean withImported = Boolean.TRUE.equals(includeImported);

        List<Lead> cohort = queries.leadsCreated(f.atStartOfDay(), t.plusDays(1).atStartOfDay()).stream()
            .filter(l -> withImported || l.getImportBatch() == null)
            .toList();
        Map<Long, Student> students = queries.studentsByLead(cohort.stream().map(Lead::getId).toList());
        java.util.Set<Long> payers = new java.util.HashSet<>();
        Map<Long, BigDecimal> revenueByStudent = new HashMap<>();
        loadPayments(students.values().stream().map(Student::getId).toList(), today, payers, revenueByStudent);

        Function<Lead, String> keyOf = g == SourceGroupBy.SOURCE
            ? l -> SourceCatalog.keyOf(l.getSource())
            : l -> l.getMetaFormId() != null && !l.getMetaFormId().isBlank()
                ? l.getMetaFormId().trim() : SourceCatalog.UNKNOWN;

        Map<String, Acc> byKey = new LinkedHashMap<>();
        Acc total = new Acc();
        for (Lead l : cohort) {
            Student s = students.get(l.getId());
            Acc acc = byKey.computeIfAbsent(keyOf.apply(l), k -> new Acc());
            boolean visited = l.getVisitedAt() != null;
            boolean converted = s != null || l.getConvertedAt() != null;
            boolean paid = s != null && payers.contains(s.getId());
            BigDecimal revenue = s != null ? revenueByStudent.getOrDefault(s.getId(), BigDecimal.ZERO) : BigDecimal.ZERO;
            acc.add(visited, converted, paid, revenue);
            total.add(visited, converted, paid, revenue);
        }
        byKey.putIfAbsent(SourceCatalog.UNKNOWN, new Acc());

        Map<String, String> formNames = g == SourceGroupBy.META_FORM ? metaFormNames(byKey.keySet()) : Map.of();
        List<SourceRow> rows = new ArrayList<>();
        byKey.forEach((key, acc) -> rows.add(acc.row(key, label(g, key, formNames))));
        rows.sort(unknownLast(SourceRow::key, Comparator.comparingLong(SourceRow::leads).reversed()
            .thenComparing(SourceRow::key)));
        return new SourceReport(f, t, g, withImported, total.row("TOTAL", null), rows);
    }

    @Transactional(readOnly = true)
    public StudentsBySource studentsBySource() {
        List<Object[]> active = queries.em().createQuery("""
                SELECT DISTINCT s.id, s.source FROM StudentGroup sg JOIN sg.student s
                WHERE sg.isActive = true AND sg.leaveDate IS NULL
                """, Object[].class).getResultList();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : active) {
            counts.merge(SourceCatalog.keyOf((String) row[1]), 1L, Long::sum);
        }
        counts.putIfAbsent(SourceCatalog.UNKNOWN, 0L);
        long total = active.size();
        List<StudentSourceRow> rows = new ArrayList<>();
        counts.forEach((key, n) -> rows.add(new StudentSourceRow(key, label(SourceGroupBy.SOURCE, key, Map.of()),
            n, Ratios.percent(n, total))));
        rows.sort(unknownLast(StudentSourceRow::source, Comparator.comparingLong(StudentSourceRow::students).reversed()
            .thenComparing(StudentSourceRow::source)));
        return new StudentsBySource(LocalDate.now(billingClock), total, rows);
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    /** Kamida bitta PAID, {@code cash_amount &gt; 0} to'lovi bor o'quvchilar va ularning Σ cash_amount (bugungacha). */
    private void loadPayments(List<Long> studentIds, LocalDate today,
                              java.util.Set<Long> payers, Map<Long, BigDecimal> revenue) {
        if (studentIds.isEmpty()) {
            return;
        }
        for (Object[] row : queries.em().createQuery("""
                SELECT p.student.id, SUM(p.cashAmount) FROM Payment p
                WHERE p.status = :paid AND p.cashAmount > 0 AND p.paymentDate <= :today
                  AND p.student.id IN :ids
                GROUP BY p.student.id
                """, Object[].class)
            .setParameter("paid", PaymentStatus.PAID).setParameter("today", today)
            .setParameter("ids", studentIds).getResultList()) {
            Long id = (Long) row[0];
            payers.add(id);
            revenue.put(id, row[1] instanceof BigDecimal b ? b : BigDecimal.ZERO);
        }
    }

    private Map<String, String> metaFormNames(java.util.Collection<String> formIds) {
        List<String> ids = formIds.stream().filter(id -> !SourceCatalog.UNKNOWN.equals(id)).toList();
        Map<String, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        queries.em().createQuery("SELECT f FROM MetaLeadForm f WHERE f.formId IN :ids", MetaLeadForm.class)
            .setParameter("ids", ids).getResultList()
            .forEach(form -> names.put(form.getFormId(), form.getName()));
        return names;
    }

    private String label(SourceGroupBy g, String key, Map<String, String> formNames) {
        if (g == SourceGroupBy.META_FORM) {
            if (SourceCatalog.UNKNOWN.equals(key)) {
                return messages.get("analytics.metaForm.none");
            }
            return Objects.requireNonNullElse(formNames.get(key), key);
        }
        return messages.getOrDefault("source." + key, key);
    }

    private static SourceGroupBy parseGroupBy(String raw) {
        if (raw == null || raw.isBlank()) {
            return SourceGroupBy.SOURCE;
        }
        try {
            return SourceGroupBy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("analytics.sources.groupBy.invalid", raw);
        }
    }

    private static <T> Comparator<T> unknownLast(Function<T, String> key, Comparator<T> then) {
        return Comparator.<T, Boolean>comparing(r -> SourceCatalog.UNKNOWN.equals(key.apply(r))).thenComparing(then);
    }

    private static final class Acc {
        long leads;
        long visited;
        long converted;
        long firstPayments;
        BigDecimal revenue = BigDecimal.ZERO;

        void add(boolean isVisited, boolean isConverted, boolean isPaid, BigDecimal amount) {
            leads++;
            if (isVisited) {
                visited++;
            }
            if (isConverted) {
                converted++;
            }
            if (isPaid) {
                firstPayments++;
            }
            revenue = revenue.add(amount);
        }

        SourceRow row(String key, String label) {
            return new SourceRow(key, label, leads, visited, converted, firstPayments, revenue,
                Ratios.percent(firstPayments, leads));
        }
    }
}

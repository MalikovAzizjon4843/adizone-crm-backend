package com.crm.dashboard;

import com.crm.dashboard.DirectorDtos.CohortStep;
import com.crm.dashboard.DirectorDtos.FirstPayments;
import com.crm.dashboard.DirectorDtos.FunnelActivity;
import com.crm.dashboard.DirectorDtos.FunnelCohort;
import com.crm.dashboard.DirectorDtos.FunnelLeadRow;
import com.crm.entity.Lead;
import com.crm.entity.Student;
import com.crm.service.LeadStageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Lid voronkasi (director-dashboard §1.1): kunlik faollik (mustaqil hodisalar) va kogorta
 * (davrda kelgan lidlar qayergacha yetdi). Importlar default chiqariladi (§7 #18).
 */
@Service
@RequiredArgsConstructor
public class FunnelMetricsService {

    public enum Step { CREATED, CONTACTED, VISITED, CONVERTED, FIRST_PAYMENT, REJECTED }

    public record Filter(boolean includeImported, Long operatorId) {
        public static Filter defaults() {
            return new Filter(false, null);
        }
    }

    private final DashboardQueries queries;
    private final LeadStageService leadStageService;

    // ── Faollik ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FunnelActivity activity(DashboardPeriod p, Filter f) {
        List<Lead> leads = queries.leadsTouched(p.start(), p.endExclusive()).stream().filter(l -> keep(l, f)).toList();
        List<DashboardQueries.FirstPayment> firsts = firstPayments(p, f);
        Map<Long, Student> payers = queries.studentsByIds(firsts.stream().map(DashboardQueries.FirstPayment::studentId).toList());
        long fromLeads = firsts.stream().filter(fp -> payers.get(fp.studentId()) != null
            && payers.get(fp.studentId()).getConvertedFromLeadId() != null).count();
        return new FunnelActivity(
            count(leads, p, Lead::getCreatedAt),
            count(leads, p, Lead::getContactedAt),
            count(leads, p, Lead::getVisitedAt),
            count(leads, p, Lead::getConvertedAt),
            count(leads, p, Lead::getRejectedAt),
            new FirstPayments(firsts.size(), fromLeads, firsts.size() - fromLeads));
    }

    /** Drill-down: shu qadamga P ichida (birinchi marta) yetganlar. */
    @Transactional(readOnly = true)
    public List<FunnelLeadRow> activityRows(DashboardPeriod p, Step step, Filter f) {
        if (step == Step.FIRST_PAYMENT) {
            return firstPaymentRows(firstPayments(p, f));
        }
        Function<Lead, LocalDateTime> at = stepTime(step);
        List<Lead> leads = queries.leadsTouched(p.start(), p.endExclusive()).stream()
            .filter(l -> keep(l, f)).filter(l -> p.contains(at.apply(l)))
            .sorted(Comparator.comparing(at)).toList();
        return leadRows(leads, at);
    }

    private List<DashboardQueries.FirstPayment> firstPayments(DashboardPeriod p, Filter f) {
        List<DashboardQueries.FirstPayment> firsts = queries.firstPaymentsBetween(p.from(), p.to());
        if (f.operatorId() == null && f.includeImported()) {
            return firsts;
        }
        Map<Long, Student> students = queries.studentsByIds(firsts.stream().map(DashboardQueries.FirstPayment::studentId).toList());
        Map<Long, Lead> leads = byId(queries.leadsByIds(students.values().stream()
            .map(Student::getConvertedFromLeadId).filter(java.util.Objects::nonNull).toList()));
        return firsts.stream().filter(fp -> {
            Student s = students.get(fp.studentId());
            Lead lead = s != null && s.getConvertedFromLeadId() != null ? leads.get(s.getConvertedFromLeadId()) : null;
            if (lead == null) {
                return f.operatorId() == null;      // lidsiz o'quvchi operator filtriga tushmaydi
            }
            return keep(lead, f);
        }).toList();
    }

    // ── Kogorta ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FunnelCohort cohort(DashboardPeriod p, Filter f) {
        List<Lead> cohort = cohortLeads(p, f);
        LocalDateTime asOf = p.asOf();
        Map<Long, Student> students = queries.studentsByLead(cohort.stream().map(Lead::getId).toList());
        Map<Long, DashboardQueries.FirstPayment> firsts =
            queries.firstPaymentsOf(students.values().stream().map(Student::getId).toList());
        List<CohortStep> steps = new ArrayList<>();
        for (Step step : List.of(Step.CREATED, Step.CONTACTED, Step.VISITED, Step.CONVERTED, Step.FIRST_PAYMENT)) {
            List<Long> days = new ArrayList<>();
            long reached = 0;
            for (Lead l : cohort) {
                LocalDateTime t = step == Step.FIRST_PAYMENT ? firstPaymentAt(l, students, firsts) : stepTime(step).apply(l);
                if (t != null && t.isBefore(asOf)) {
                    reached++;
                    days.add(Math.max(0, ChronoUnit.DAYS.between(l.getCreatedAt().toLocalDate(), t.toLocalDate())));
                }
            }
            Long median = step == Step.CREATED ? null : Ratios.median(days);
            steps.add(new CohortStep(step.name(), reached, Ratios.percent(reached, cohort.size()),
                median != null ? BigDecimal.valueOf(median) : null));
        }
        return new FunnelCohort(cohort.size(), steps);
    }

    @Transactional(readOnly = true)
    public List<FunnelLeadRow> cohortRows(DashboardPeriod p, Step step, Filter f) {
        List<Lead> cohort = cohortLeads(p, f);
        LocalDateTime asOf = p.asOf();
        Map<Long, Student> students = queries.studentsByLead(cohort.stream().map(Lead::getId).toList());
        Map<Long, DashboardQueries.FirstPayment> firsts =
            queries.firstPaymentsOf(students.values().stream().map(Student::getId).toList());
        Function<Lead, LocalDateTime> at = step == Step.FIRST_PAYMENT
            ? l -> firstPaymentAt(l, students, firsts) : stepTime(step);
        List<Lead> reached = cohort.stream().filter(l -> {
            LocalDateTime t = at.apply(l);
            return t != null && t.isBefore(asOf);
        }).sorted(Comparator.comparing(Lead::getCreatedAt)).toList();
        return leadRows(reached, at);
    }

    /** Kogorta lidi (analitika, phase6 §1): manba, yaratilgan payt, P oxirigacha konvert qilinganmi, birinchi to'lov bormi. */
    public record CohortLead(String source, LocalDateTime createdAt, boolean converted, boolean firstPayment) {
    }

    /** P da yaratilgan lidlar (import qilinganlarsiz — {@link #cohort} bilan bir xil to'plam). */
    @Transactional(readOnly = true)
    public List<CohortLead> cohortLeadFacts(DashboardPeriod p, Filter f) {
        List<Lead> cohort = cohortLeads(p, f);
        LocalDateTime asOf = p.asOf();
        Map<Long, Student> students = queries.studentsByLead(cohort.stream().map(Lead::getId).toList());
        Map<Long, DashboardQueries.FirstPayment> firsts =
            queries.firstPaymentsOf(students.values().stream().map(Student::getId).toList());
        return cohort.stream().map(l -> {
            LocalDateTime conv = l.getConvertedAt();
            LocalDateTime paid = firstPaymentAt(l, students, firsts);
            return new CohortLead(l.getSource(), l.getCreatedAt(), conv != null && conv.isBefore(asOf),
                paid != null && paid.isBefore(asOf));
        }).toList();
    }

    private List<Lead> cohortLeads(DashboardPeriod p, Filter f) {
        return queries.leadsCreated(p.start(), p.endExclusive()).stream().filter(l -> keep(l, f)).toList();
    }

    private static LocalDateTime firstPaymentAt(Lead l, Map<Long, Student> students,
                                                Map<Long, DashboardQueries.FirstPayment> firsts) {
        Student s = students.get(l.getId());
        DashboardQueries.FirstPayment fp = s != null ? firsts.get(s.getId()) : null;
        return fp != null ? fp.date().atStartOfDay() : null;
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private static boolean keep(Lead l, Filter f) {
        if (!f.includeImported() && l.getImportBatch() != null) {
            return false;
        }
        return f.operatorId() == null
            || (l.getAssignedUser() != null && f.operatorId().equals(l.getAssignedUser().getId()));
    }

    private static long count(List<Lead> leads, DashboardPeriod p, Function<Lead, LocalDateTime> at) {
        return leads.stream().filter(l -> p.contains(at.apply(l))).count();
    }

    static Function<Lead, LocalDateTime> stepTime(Step step) {
        return switch (step) {
            case CREATED -> Lead::getCreatedAt;
            case CONTACTED -> Lead::getContactedAt;
            case VISITED -> Lead::getVisitedAt;
            case CONVERTED -> Lead::getConvertedAt;
            case REJECTED -> Lead::getRejectedAt;
            case FIRST_PAYMENT -> l -> null;
        };
    }

    public static Step parseStep(String raw) {
        try {
            return Step.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw com.crm.exception.CodedException.badRequest("dashboard.param.invalid", "step");
        }
    }

    private List<FunnelLeadRow> leadRows(List<Lead> leads, Function<Lead, LocalDateTime> at) {
        Map<Long, Student> students = queries.studentsByLead(leads.stream().map(Lead::getId).toList());
        Map<Long, DashboardQueries.FirstPayment> firsts =
            queries.firstPaymentsOf(students.values().stream().map(Student::getId).toList());
        return leads.stream().map(l -> {
            Student s = students.get(l.getId());
            DashboardQueries.FirstPayment fp = s != null ? firsts.get(s.getId()) : null;
            return new FunnelLeadRow(l.getId(), l.getFullName(), l.getPhone(), l.getSource(), l.getCreatedAt(),
                at.apply(l), l.getStatus(),
                l.getAssignedUser() != null ? l.getAssignedUser().getId() : null,
                l.getAssignedUser() != null ? name(l.getAssignedUser().getFirstName(), l.getAssignedUser().getLastName()) : null,
                s != null ? s.getId() : null, fp != null ? fp.date() : null, fp != null ? fp.amount() : null);
        }).toList();
    }

    private List<FunnelLeadRow> firstPaymentRows(List<DashboardQueries.FirstPayment> firsts) {
        Map<Long, Student> students = queries.studentsByIds(firsts.stream().map(DashboardQueries.FirstPayment::studentId).toList());
        Map<Long, Lead> leads = byId(queries.leadsByIds(students.values().stream()
            .map(Student::getConvertedFromLeadId).filter(java.util.Objects::nonNull).toList()));
        return firsts.stream().sorted(Comparator.comparing(DashboardQueries.FirstPayment::date)
                .thenComparing(DashboardQueries.FirstPayment::paymentId))
            .map(fp -> {
                Student s = students.get(fp.studentId());
                Lead l = s != null && s.getConvertedFromLeadId() != null ? leads.get(s.getConvertedFromLeadId()) : null;
                return new FunnelLeadRow(l != null ? l.getId() : null,
                    l != null ? l.getFullName() : (s != null ? name(s.getFirstName(), s.getLastName()) : null),
                    l != null ? l.getPhone() : (s != null ? s.getPhone() : null),
                    l != null ? l.getSource() : null, l != null ? l.getCreatedAt() : null,
                    fp.date().atStartOfDay(), l != null ? l.getStatus() : null,
                    l != null && l.getAssignedUser() != null ? l.getAssignedUser().getId() : null,
                    l != null && l.getAssignedUser() != null
                        ? name(l.getAssignedUser().getFirstName(), l.getAssignedUser().getLastName()) : null,
                    fp.studentId(), fp.date(), fp.amount());
            }).toList();
    }

    private static Map<Long, Lead> byId(List<Lead> leads) {
        Map<Long, Lead> m = new java.util.HashMap<>();
        leads.forEach(l -> m.put(l.getId(), l));
        return m;
    }

    static String name(String first, String last) {
        return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
    }

}

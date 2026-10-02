package com.crm.dashboard;

import com.crm.dashboard.DirectorDtos.CountPercent;
import com.crm.dashboard.DirectorDtos.TrialRow;
import com.crm.dashboard.DirectorDtos.TrialsSection;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sinov → to'lov (director-dashboard §1.5, §7 #9/#10). Kogorta — sinovga KELGANLAR
 * ({@code trial_started_at ∈ P}); {@code k = to'lov kuni − sinov kuni}: D0 (sinov kuni), D1,
 * D2_7, D8_PLUS; to'lamagan — IN_TRIAL (sinovda, ≤ 14 kun) yoki NOT_PAID.
 */
@Service
@RequiredArgsConstructor
public class TrialMetricsService {

    public enum Bucket { D0, D1, D2_7, D8_PLUS, IN_TRIAL, NOT_PAID, NO_SHOW }

    private final DashboardQueries queries;
    private final DashboardProperties properties;

    @Transactional(readOnly = true)
    public TrialsSection summary(DashboardPeriod p) {
        List<TrialRow> cohort = cohortRows(p);
        long noShow = noShowRows(p).size();
        Map<String, CountPercent> buckets = new LinkedHashMap<>();
        for (Bucket b : List.of(Bucket.D0, Bucket.D1, Bucket.D2_7, Bucket.D8_PLUS, Bucket.IN_TRIAL, Bucket.NOT_PAID)) {
            long n = cohort.stream().filter(r -> b.name().equals(r.bucket())).count();
            buckets.put(b.name(), new CountPercent(n, Ratios.percent(n, cohort.size())));
        }
        long paid = cohort.stream().filter(r -> r.convertedDay() != null).count();
        long decided = cohort.size() - buckets.get(Bucket.IN_TRIAL.name()).count();
        return new TrialsSection(cohort.size(), noShow, buckets, Ratios.percent(paid, decided), stayed(cohort, p),
            cohort.stream().anyMatch(TrialRow::estimated));
    }

    @Transactional(readOnly = true)
    public List<TrialRow> rows(DashboardPeriod p, Bucket bucket) {
        if (bucket == Bucket.NO_SHOW) {
            return noShowRows(p);
        }
        return cohortRows(p).stream().filter(r -> bucket.name().equals(r.bucket())).toList();
    }

    public static Bucket parseBucket(String raw) {
        try {
            return Bucket.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw com.crm.exception.CodedException.badRequest("dashboard.param.invalid", "bucket");
        }
    }

    List<TrialRow> cohortRows(DashboardPeriod p) {
        List<StudentGroup> sgs = queries.em().createQuery("""
                SELECT sg FROM StudentGroup sg JOIN FETCH sg.student LEFT JOIN FETCH sg.group
                WHERE sg.trialStartedAt BETWEEN :f AND :t
                """, StudentGroup.class).setParameter("f", p.from()).setParameter("t", p.to()).getResultList();
        Map<Long, LocalDate> paidDay = firstPaymentAfterTrial(sgs);
        LocalDate asOf = p.asOfDate();
        List<TrialRow> rows = new ArrayList<>();
        for (StudentGroup sg : sgs) {
            LocalDate started = sg.getTrialStartedAt();
            LocalDate paid = paidDay.get(sg.getId());
            Integer days = null;
            String bucket;
            if (paid != null && !paid.isAfter(asOf)) {
                days = (int) ChronoUnit.DAYS.between(started, paid);
                bucket = days <= 0 ? "D0" : days == 1 ? "D1" : days <= 7 ? "D2_7" : "D8_PLUS";
            } else {
                paid = null;
                boolean stillTrial = Boolean.TRUE.equals(sg.getIsTrial()) && Boolean.TRUE.equals(sg.getIsActive());
                bucket = stillTrial && ChronoUnit.DAYS.between(started, asOf) <= properties.getTrialDecisionDays()
                    ? "IN_TRIAL" : "NOT_PAID";
            }
            rows.add(row(sg, paid, days, bucket));
        }
        rows.sort(Comparator.comparing(TrialRow::trialStartedAt).thenComparing(TrialRow::studentGroupId));
        return rows;
    }

    /** Sinovga yozilgan (P da), lekin hali hech kelmagan. */
    List<TrialRow> noShowRows(DashboardPeriod p) {
        return queries.em().createQuery("""
                SELECT sg FROM StudentGroup sg JOIN FETCH sg.student LEFT JOIN FETCH sg.group
                WHERE sg.trialOutcome IS NOT NULL AND sg.trialStartedAt IS NULL
                  AND sg.joinDate BETWEEN :f AND :t
                """, StudentGroup.class).setParameter("f", p.from()).setParameter("t", p.to()).getResultList()
            .stream().map(sg -> row(sg, null, null, "NO_SHOW"))
            .sorted(Comparator.comparing(TrialRow::studentGroupId)).toList();
    }

    /** SG ning sinov kunidan keyingi birinchi PAID ({@code cash > 0}) to'lovi. */
    private Map<Long, LocalDate> firstPaymentAfterTrial(List<StudentGroup> sgs) {
        Map<Long, LocalDate> out = new HashMap<>();
        if (sgs.isEmpty()) {
            return out;
        }
        Map<Long, LocalDate> startBySg = new HashMap<>();
        sgs.forEach(sg -> startBySg.put(sg.getId(), sg.getTrialStartedAt()));
        for (Payment pay : queries.em().createQuery("""
                SELECT p FROM Payment p WHERE p.studentGroup.id IN :ids AND p.status = :paid AND p.cashAmount > 0
                ORDER BY p.paymentDate, p.id
                """, Payment.class).setParameter("ids", startBySg.keySet()).setParameter("paid", PaymentStatus.PAID)
            .getResultList()) {
            Long sgId = pay.getStudentGroup().getId();
            if (!out.containsKey(sgId) && !pay.getPaymentDate().isBefore(startBySg.get(sgId))) {
                out.put(sgId, pay.getPaymentDate());
            }
        }
        return out;
    }

    /** To'laganlardan {@code paid + 30} kunda yozilmasi hali ochiq bo'lganlar % (shu kun kelganlar orasida). */
    private java.math.BigDecimal stayed(List<TrialRow> cohort, DashboardPeriod p) {
        long eligible = 0;
        long stayed = 0;
        Map<Long, StudentGroup> sgs = new HashMap<>();
        if (!cohort.isEmpty()) {
            queries.em().createQuery("SELECT sg FROM StudentGroup sg WHERE sg.id IN :ids", StudentGroup.class)
                .setParameter("ids", cohort.stream().map(TrialRow::studentGroupId).toList()).getResultList()
                .forEach(sg -> sgs.put(sg.getId(), sg));
        }
        for (TrialRow r : cohort) {
            if (r.convertedDay() == null) {
                continue;
            }
            LocalDate check = r.convertedDay().plusDays(properties.getTrialStayDays());
            if (check.isAfter(p.asOfDate())) {
                continue;
            }
            eligible++;
            StudentGroup sg = sgs.get(r.studentGroupId());
            boolean open = sg.getFrozenFrom() != null
                || (sg.getLeaveDate() == null ? Boolean.TRUE.equals(sg.getIsActive()) : sg.getLeaveDate().isAfter(check));
            if (open) {
                stayed++;
            }
        }
        return Ratios.percent(stayed, eligible);
    }

    private TrialRow row(StudentGroup sg, LocalDate paid, Integer days, String bucket) {
        var s = sg.getStudent();
        return new TrialRow(sg.getId(), s.getId(), FunnelMetricsService.name(s.getFirstName(), s.getLastName()),
            s.getPhone(), sg.getGroup() != null ? sg.getGroup().getGroupName() : null, sg.getTrialStartedAt(),
            paid, days, bucket, sg.getTrialOutcome() != null ? sg.getTrialOutcome().name() : null,
            s.getConvertedFromLeadId(), "BACKFILL".equals(sg.getTrialSource()));
    }
}

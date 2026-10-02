package com.crm.dashboard;

import com.crm.billing.Money;
import com.crm.dashboard.DirectorDtos.CollectionRow;
import com.crm.dashboard.DirectorDtos.CollectionsSection;
import com.crm.dashboard.DirectorDtos.CountAmount;
import com.crm.entity.BillingPeriod;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Muddati kelgan to'lovlar (director-dashboard §1.2) — {@code billing_periods} asosida, faqat
 * MONTHLY (PER_LESSON da muddat yo'q, §7 #6). Toifa {@code asOf} kuni bo'yicha:
 * ON_TIME ({@code paid_on ≤ grace_until}), LATE, PENDING (to'lanmagan, muddat ichida), UNPAID.
 */
@Service
@RequiredArgsConstructor
public class CollectionsMetricsService {

    public enum Bucket { DUE, COLLECTED, ON_TIME, LATE, PENDING, UNPAID, COLLECTED_ON_DAY }

    private static final Set<BillingPeriodStatus> COLLECTIBLE =
        Set.of(BillingPeriodStatus.CHARGED, BillingPeriodStatus.PARTIALLY_REFUNDED);

    private final DashboardQueries queries;

    @Transactional(readOnly = true)
    public CollectionsSection summary(DashboardPeriod p) {
        List<CollectionRow> due = dueRows(p);
        List<CollectionRow> onDay = collectedOnDayRows(p);
        List<CollectionRow> collected = due.stream().filter(r -> r.paidOn() != null && !r.paidOn().isAfter(p.asOfDate())).toList();
        List<Long> delays = collected.stream().map(r -> Math.max(0, r.delayDays())).toList();
        List<Long> lateDelays = due.stream().filter(r -> "LATE".equals(r.bucket())).map(CollectionRow::delayDays).toList();
        return new CollectionsSection(
            ca(due), ca(collected),
            ca(bucket(due, Bucket.ON_TIME)), ca(bucket(due, Bucket.LATE)),
            ca(bucket(due, Bucket.PENDING)), ca(bucket(due, Bucket.UNPAID)),
            Ratios.percent(collected.size(), due.size()),
            Ratios.average(delays), Ratios.average(lateDelays),
            ca(onDay), cashIn(p),
            due.stream().anyMatch(CollectionRow::estimated) || onDay.stream().anyMatch(CollectionRow::estimated));
    }

    @Transactional(readOnly = true)
    public List<CollectionRow> rows(DashboardPeriod p, Bucket bucket) {
        return switch (bucket) {
            case DUE -> dueRows(p);
            case COLLECTED -> dueRows(p).stream()
                .filter(r -> r.paidOn() != null && !r.paidOn().isAfter(p.asOfDate())).toList();
            case COLLECTED_ON_DAY -> collectedOnDayRows(p);
            default -> bucket(dueRows(p), bucket);
        };
    }

    public static Bucket parseBucket(String raw) {
        try {
            return Bucket.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw com.crm.exception.CodedException.badRequest("dashboard.param.invalid", "bucket");
        }
    }

    // ── qatorlar ────────────────────────────────────────────────────────

    private List<CollectionRow> dueRows(DashboardPeriod p) {
        List<BillingPeriod> periods = queries.em().createQuery("""
                SELECT bp FROM BillingPeriod bp
                WHERE bp.dueDate BETWEEN :f AND :t AND bp.status IN :st AND bp.chargeTxId IS NOT NULL
                """, BillingPeriod.class)
            .setParameter("f", p.from()).setParameter("t", p.to()).setParameter("st", COLLECTIBLE)
            .getResultList();
        return toRows(periods, p.asOfDate());
    }

    private List<CollectionRow> collectedOnDayRows(DashboardPeriod p) {
        List<BillingPeriod> periods = queries.em().createQuery("""
                SELECT bp FROM BillingPeriod bp
                WHERE bp.paidOn BETWEEN :f AND :t AND bp.status IN :st AND bp.chargeTxId IS NOT NULL
                """, BillingPeriod.class)
            .setParameter("f", p.from()).setParameter("t", p.to()).setParameter("st", COLLECTIBLE)
            .getResultList();
        return toRows(periods, p.asOfDate());
    }

    private List<CollectionRow> toRows(List<BillingPeriod> periods, LocalDate asOf) {
        Map<Long, StudentGroup> sgs = enrollments(periods.stream().map(BillingPeriod::getStudentGroupId).toList());
        List<CollectionRow> rows = new ArrayList<>();
        for (BillingPeriod bp : periods) {
            BigDecimal amountDue = Money.nz(bp.getAmount()).subtract(Money.nz(bp.getRefundedAmount()));
            StudentGroup sg = sgs.get(bp.getStudentGroupId());
            if (amountDue.signum() <= 0 || sg == null || Boolean.TRUE.equals(sg.getBillingHold())) {
                continue;
            }
            LocalDate due = bp.getDueDate() != null ? bp.getDueDate() : bp.getPeriodStart();
            LocalDate grace = bp.getGraceUntil() != null ? bp.getGraceUntil() : due.plusDays(3);
            boolean paid = bp.getPaidOn() != null && !bp.getPaidOn().isAfter(asOf);
            String bucket;
            if (paid) {
                bucket = !bp.getPaidOn().isAfter(grace) ? "ON_TIME" : "LATE";
            } else {
                bucket = asOf.isAfter(grace) ? "UNPAID" : "PENDING";
            }
            Long delay = paid ? ChronoUnit.DAYS.between(due, bp.getPaidOn()) : null;
            var student = sg.getStudent();
            rows.add(new CollectionRow(bp.getId(), sg.getId(), student.getId(),
                FunnelMetricsService.name(student.getFirstName(), student.getLastName()), student.getPhone(),
                sg.getGroup() != null ? sg.getGroup().getGroupName() : null,
                bp.getPeriodStart(), bp.getPeriodEnd(), due, grace, Money.normalize(amountDue),
                paid ? bp.getPaidOn() : null, paid ? bp.getPaidAt() : null, delay, bucket,
                bp.getCoverageSource(), bp.getMigrationRunId() != null));
        }
        rows.sort(Comparator.comparing(CollectionRow::dueDate).thenComparing(CollectionRow::periodId));
        return rows;
    }

    private Map<Long, StudentGroup> enrollments(Collection<Long> ids) {
        Map<Long, StudentGroup> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        queries.em().createQuery("""
                SELECT sg FROM StudentGroup sg JOIN FETCH sg.student LEFT JOIN FETCH sg.group
                WHERE sg.id IN :ids
                """, StudentGroup.class)
            .setParameter("ids", Set.copyOf(ids)).getResultList().forEach(sg -> out.put(sg.getId(), sg));
        return out;
    }

    private BigDecimal cashIn(DashboardPeriod p) {
        BigDecimal sum = queries.em().createQuery("""
                SELECT COALESCE(SUM(p.cashAmount), 0) FROM Payment p
                WHERE p.status = :paid AND p.paymentDate BETWEEN :f AND :t
                """, BigDecimal.class)
            .setParameter("paid", PaymentStatus.PAID).setParameter("f", p.from()).setParameter("t", p.to())
            .getSingleResult();
        return Money.normalize(Money.nz(sum));
    }

    private static List<CollectionRow> bucket(List<CollectionRow> rows, Bucket b) {
        return rows.stream().filter(r -> b.name().equals(r.bucket())).toList();
    }

    private static CountAmount ca(List<CollectionRow> rows) {
        return new CountAmount(rows.size(), Money.normalize(rows.stream().map(CollectionRow::amountDue)
            .reduce(BigDecimal.ZERO, BigDecimal::add)));
    }
}

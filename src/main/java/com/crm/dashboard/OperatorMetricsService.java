package com.crm.dashboard;

import com.crm.billing.Money;
import com.crm.dashboard.DirectorDtos.Activity;
import com.crm.dashboard.DirectorDtos.CountAmount;
import com.crm.dashboard.DirectorDtos.FirstResponse;
import com.crm.dashboard.DirectorDtos.OperatorLeadRow;
import com.crm.dashboard.DirectorDtos.OperatorRow;
import com.crm.dashboard.DirectorDtos.OperatorTaskRow;
import com.crm.dashboard.DirectorDtos.OperatorsSection;
import com.crm.dashboard.DirectorDtos.TaskStats;
import com.crm.entity.Lead;
import com.crm.entity.LeadAssignment;
import com.crm.entity.Student;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.LeadAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Operator faoliyati (director-dashboard §1.7, §7 #13/#14). Operator — ADMIN, SALES_HEAD, SALES_MANAGER
 * (SA faqat davrda lid tayinlangan bo'lsa). Javob vaqti — ish soatlarida; import lidlari
 * chiqariladi.
 */
@Service
@RequiredArgsConstructor
public class OperatorMetricsService {

    public enum LeadMetric { ASSIGNED, NO_RESPONSE, SLOW_RESPONSE, CONVERTED }

    public enum TaskState { DONE, DONE_LATE, OVERDUE_OPEN }

    public static final long SLOW_MINUTES = 60;
    public static final int TOP = 5;

    private final DashboardQueries queries;
    private final LeadAssignmentRepository assignmentRepository;
    private final DashboardProperties properties;

    private record Assigned(LeadAssignment a, Lead lead, LocalDateTime t0, Long minutes, boolean noResponse) {
    }

    @Transactional(readOnly = true)
    public OperatorsSection summary(DashboardPeriod p) {
        List<OperatorRow> rows = operators(p);
        List<Long> allMinutes = new ArrayList<>();
        for (Assigned a : assigned(p, null)) {
            if (a.minutes() != null) {
                allMinutes.add(a.minutes());
            }
        }
        return new OperatorsSection(rows.size(),
            rows.stream().mapToLong(OperatorRow::assigned).sum(),
            Ratios.median(allMinutes),
            rows.stream().mapToLong(OperatorRow::noResponse).sum(),
            rows.stream().mapToLong(r -> r.tasks().done()).sum(),
            rows.stream().mapToLong(r -> r.tasks().overdueOpen()).sum(),
            rows.stream().limit(TOP).toList(),
            rows.stream().anyMatch(OperatorRow::estimated));
    }

    @Transactional(readOnly = true)
    public List<OperatorRow> operators(DashboardPeriod p) {
        LocalDateTime a = p.start();
        LocalDateTime b = p.endExclusive();
        LocalDateTime asOf = p.asOf();
        List<Assigned> assigned = assigned(p, null);
        Map<Long, List<Assigned>> byUser = new HashMap<>();
        assigned.forEach(x -> byUser.computeIfAbsent(x.a().getUserId(), k -> new ArrayList<>()).add(x));

        Set<Long> userIds = new HashSet<>(byUser.keySet());
        List<User> users = queries.em().createQuery("""
                SELECT u FROM User u WHERE u.isActive = true AND u.role IN :roles
                """, User.class).setParameter("roles", List.of(UserRole.ADMIN, UserRole.SALES_HEAD, UserRole.SALES_MANAGER)).getResultList();
        users.forEach(u -> userIds.add(u.getId()));
        Map<Long, User> userById = new HashMap<>();
        queries.em().createQuery("SELECT u FROM User u WHERE u.id IN :ids", User.class)
            .setParameter("ids", userIds.isEmpty() ? Set.of(-1L) : userIds).getResultList()
            .forEach(u -> userById.put(u.getId(), u));

        Map<Long, long[]> tasks = new HashMap<>();
        for (Task t : queries.em().createQuery("""
                SELECT t FROM Task t WHERE t.completedAt >= :a AND t.completedAt < :b AND t.status = :done
                """, Task.class).setParameter("a", a).setParameter("b", b).setParameter("done", TaskStatus.DONE)
            .getResultList()) {
            if (t.getCompletedBy() == null) {
                continue;
            }
            long[] c = tasks.computeIfAbsent(t.getCompletedBy().getId(), k -> new long[3]);
            c[0]++;
            if (t.getDueAt() != null && t.getCompletedAt().isAfter(t.getDueAt())) {
                c[1]++;
            }
        }
        for (Object[] r : queries.em().createQuery("""
                SELECT t.assignedTo.id, COUNT(t) FROM Task t WHERE t.status = :open AND t.dueAt < :asOf
                GROUP BY t.assignedTo.id
                """, Object[].class).setParameter("open", TaskStatus.OPEN).setParameter("asOf", asOf).getResultList()) {
            tasks.computeIfAbsent((Long) r[0], k -> new long[3])[2] = (Long) r[1];
        }
        Map<Long, Object[]> received = new HashMap<>();
        for (Object[] r : queries.em().createQuery("""
                SELECT p.receivedBy.id, COUNT(p), COALESCE(SUM(p.cashAmount), 0) FROM Payment p
                WHERE p.status = :paid AND p.paymentDate BETWEEN :f AND :t AND p.receivedBy IS NOT NULL
                GROUP BY p.receivedBy.id
                """, Object[].class).setParameter("paid", PaymentStatus.PAID).setParameter("f", p.from())
            .setParameter("t", p.to()).getResultList()) {
            received.put((Long) r[0], r);
        }
        Map<Long, Long> statusChanges = grouped("""
                SELECT h.changedBy.id, COUNT(h) FROM LeadStatusHistory h
                WHERE h.changedAt >= :a AND h.changedAt < :b AND h.changedBy IS NOT NULL GROUP BY h.changedBy.id
                """, a, b);
        Map<Long, Long> comments = grouped("""
                SELECT c.author.id, COUNT(c) FROM LeadComment c
                WHERE c.createdAt >= :a AND c.createdAt < :b GROUP BY c.author.id
                """, a, b);
        Map<Long, LocalDateTime> lastSeen = new HashMap<>();
        for (Object[] r : queries.em().createQuery("""
                SELECT l.userId, MAX(l.createdAt) FROM AuditLog l
                WHERE l.action = 'LOGIN' AND l.createdAt < :asOf AND l.userId IS NOT NULL GROUP BY l.userId
                """, Object[].class).setParameter("asOf", asOf).getResultList()) {
            lastSeen.put((Long) r[0], (LocalDateTime) r[1]);
        }
        Map<Long, Long> firstPayments = new HashMap<>();
        List<DashboardQueries.FirstPayment> firsts = queries.firstPaymentsBetween(p.from(), p.to());
        Map<Long, Student> payers = queries.studentsByIds(firsts.stream().map(DashboardQueries.FirstPayment::studentId).toList());
        payers.values().forEach(s -> {
            if (s.getAttributedUserId() != null) {
                firstPayments.merge(s.getAttributedUserId(), 1L, Long::sum);
            }
        });

        List<OperatorRow> rows = new ArrayList<>();
        for (Long id : userIds) {
            User u = userById.get(id);
            if (u == null) {
                continue;
            }
            List<Assigned> mine = byUser.getOrDefault(id, List.of());
            List<Long> minutes = mine.stream().map(Assigned::minutes).filter(java.util.Objects::nonNull).toList();
            long within15 = minutes.stream().filter(m -> m <= 15).count();
            long within60 = minutes.stream().filter(m -> m <= 60).count();
            long converted = mine.stream().map(Assigned::lead).distinct()
                .filter(l -> l.getConvertedAt() != null && l.getConvertedAt().isBefore(asOf)).count();
            long[] t = tasks.getOrDefault(id, new long[3]);
            Object[] rec = received.get(id);
            rows.add(new OperatorRow(id, FunnelMetricsService.name(u.getFirstName(), u.getLastName()),
                u.getRole() != null ? u.getRole().name() : null, mine.size(),
                new FirstResponse(Ratios.median(minutes), Ratios.quantile(minutes, 0.9),
                    Ratios.percent(within15, mine.size()), Ratios.percent(within60, mine.size())),
                mine.stream().filter(Assigned::noResponse).count(),
                new TaskStats(t[0], t[1], t[2]), converted, Ratios.percent(converted, mine.size()),
                firstPayments.getOrDefault(id, 0L),
                rec != null ? new CountAmount((Long) rec[1], Money.normalize((BigDecimal) rec[2])) : CountAmount.zero(),
                new Activity(statusChanges.getOrDefault(id, 0L), comments.getOrDefault(id, 0L), lastSeen.get(id)),
                mine.stream().anyMatch(x -> "BACKFILL".equals(x.a().getSource()))));
        }
        rows.sort(Comparator.comparingLong(OperatorRow::assigned).reversed()
            .thenComparing(OperatorRow::fullName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return rows;
    }

    @Transactional(readOnly = true)
    public List<OperatorLeadRow> leads(DashboardPeriod p, Long userId, LeadMetric metric) {
        return assigned(p, userId).stream().filter(x -> switch (metric) {
                case ASSIGNED -> true;
                case NO_RESPONSE -> x.noResponse();
                case SLOW_RESPONSE -> x.minutes() != null && x.minutes() > SLOW_MINUTES;
                case CONVERTED -> x.lead().getConvertedAt() != null && x.lead().getConvertedAt().isBefore(p.asOf());
            })
            .map(x -> new OperatorLeadRow(x.lead().getId(), x.lead().getFullName(), x.lead().getPhone(),
                x.a().getAssignedAt(), x.a().getFirstResponseAt(), x.minutes(), x.a().getFirstResponseKind(),
                x.lead().getStatus(), x.lead().getConvertedAt(), metric.name()))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<OperatorTaskRow> tasks(DashboardPeriod p, Long userId, TaskState state) {
        List<Task> list = state == TaskState.OVERDUE_OPEN
            ? queries.em().createQuery("""
                SELECT t FROM Task t WHERE t.assignedTo.id = :u AND t.status = :open AND t.dueAt < :asOf ORDER BY t.dueAt
                """, Task.class).setParameter("u", userId).setParameter("open", TaskStatus.OPEN)
                .setParameter("asOf", p.asOf()).getResultList()
            : queries.em().createQuery("""
                SELECT t FROM Task t WHERE t.completedBy.id = :u AND t.status = :done
                  AND t.completedAt >= :a AND t.completedAt < :b ORDER BY t.completedAt
                """, Task.class).setParameter("u", userId).setParameter("done", TaskStatus.DONE)
                .setParameter("a", p.start()).setParameter("b", p.endExclusive()).getResultList()
                .stream().filter(t -> state == TaskState.DONE
                    || (t.getDueAt() != null && t.getCompletedAt().isAfter(t.getDueAt()))).toList();
        return list.stream().map(t -> new OperatorTaskRow(t.getId(), t.getTitle(),
            t.getType() != null ? t.getType().name() : null, t.getLead() != null ? t.getLead().getId() : null,
            t.getStudent() != null ? t.getStudent().getId() : null, t.getDueAt(), t.getCompletedAt(), t.getResult(),
            state.name())).toList();
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String raw, String param) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw com.crm.exception.CodedException.badRequest("dashboard.param.invalid", param);
        }
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private List<Assigned> assigned(DashboardPeriod p, Long userId) {
        BusinessHours hours = BusinessHours.of(properties);
        List<LeadAssignment> rows = assignmentRepository.findAssignedBetween(p.start(), p.endExclusive()).stream()
            .filter(x -> userId == null || userId.equals(x.getUserId())).toList();
        Map<Long, Lead> leads = new HashMap<>();
        queries.leadsByIds(rows.stream().map(LeadAssignment::getLeadId).distinct().toList())
            .forEach(l -> leads.put(l.getId(), l));
        LocalDateTime asOf = p.asOf();
        long noResponseMinutes = properties.getNoResponseWorkHours() * 60L;
        List<Assigned> out = new ArrayList<>();
        for (LeadAssignment x : rows) {
            Lead lead = leads.get(x.getLeadId());
            if (lead == null || lead.getImportBatch() != null) {
                continue;
            }
            LocalDateTime t0 = lead.getCreatedAt() != null && lead.getCreatedAt().isAfter(x.getAssignedAt())
                ? lead.getCreatedAt() : x.getAssignedAt();
            LocalDateTime responded = x.getFirstResponseAt() != null && x.getFirstResponseAt().isBefore(asOf)
                ? x.getFirstResponseAt() : null;
            Long minutes = responded != null ? hours.minutesBetween(t0, responded) : null;
            boolean noResponse = responded == null && hours.minutesBetween(t0, asOf) >= noResponseMinutes;
            out.add(new Assigned(x, lead, t0, minutes, noResponse));
        }
        return out;
    }

    private Map<Long, Long> grouped(String jpql, LocalDateTime a, LocalDateTime b) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] r : queries.em().createQuery(jpql, Object[].class).setParameter("a", a).setParameter("b", b)
            .getResultList()) {
            out.put((Long) r[0], (Long) r[1]);
        }
        return out;
    }
}

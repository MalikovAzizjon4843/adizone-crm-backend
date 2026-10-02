package com.crm.dashboard;

import com.crm.entity.Lead;
import com.crm.entity.Payment;
import com.crm.entity.Student;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard so'rovlari bir joyda (JPQL). Faqat o'qiydi; chaqiruvchi read-only tranzaksiyada.
 */
@Component
@RequiredArgsConstructor
public class DashboardQueries {

    private final EntityManager em;

    /** Birinchi PAID to'lov (§1.1.3): o'quvchi bo'yicha, {@code cash_amount > 0}, FORMER_STUDENT emas. */
    public record FirstPayment(Long studentId, Long paymentId, LocalDate date, BigDecimal amount) {
    }

    /** Leads — davrda yaratilgan yoki qadamga yetgan (bitta so'rov, filtr Java'da). */
    public List<Lead> leadsTouched(LocalDateTime a, LocalDateTime b) {
        return em.createQuery("""
                SELECT l FROM Lead l LEFT JOIN FETCH l.assignedUser
                WHERE (l.createdAt >= :a AND l.createdAt < :b)
                   OR (l.contactedAt >= :a AND l.contactedAt < :b)
                   OR (l.visitedAt >= :a AND l.visitedAt < :b)
                   OR (l.convertedAt >= :a AND l.convertedAt < :b)
                   OR (l.rejectedAt >= :a AND l.rejectedAt < :b)
                """, Lead.class)
            .setParameter("a", a).setParameter("b", b).getResultList();
    }

    public List<Lead> leadsCreated(LocalDateTime a, LocalDateTime b) {
        return em.createQuery("""
                SELECT l FROM Lead l LEFT JOIN FETCH l.assignedUser
                WHERE l.createdAt >= :a AND l.createdAt < :b
                """, Lead.class)
            .setParameter("a", a).setParameter("b", b).getResultList();
    }

    public List<Lead> leadsByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return em.createQuery("SELECT l FROM Lead l LEFT JOIN FETCH l.assignedUser WHERE l.id IN :ids", Lead.class)
            .setParameter("ids", ids).getResultList();
    }

    /** lead_id → student (konvert qilingan). */
    public Map<Long, Student> studentsByLead(Collection<Long> leadIds) {
        Map<Long, Student> out = new HashMap<>();
        if (leadIds.isEmpty()) {
            return out;
        }
        em.createQuery("SELECT s FROM Student s WHERE s.convertedFromLeadId IN :ids", Student.class)
            .setParameter("ids", leadIds).getResultList()
            .forEach(s -> out.putIfAbsent(s.getConvertedFromLeadId(), s));
        return out;
    }

    /** Birinchi to'lovi [from, to] ga tushgan o'quvchilar. */
    public List<FirstPayment> firstPaymentsBetween(LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createQuery("""
                SELECT p.student.id, MIN(p.paymentDate) FROM Payment p
                WHERE p.status = :paid AND p.cashAmount > 0
                  AND (p.student.marketingSource IS NULL OR p.student.marketingSource <> :former)
                GROUP BY p.student.id
                HAVING MIN(p.paymentDate) BETWEEN :f AND :t
                """, Object[].class)
            .setParameter("paid", PaymentStatus.PAID).setParameter("former", MarketingSource.FORMER_STUDENT)
            .setParameter("f", from).setParameter("t", to).getResultList();
        Map<Long, LocalDate> firstDate = new HashMap<>();
        rows.forEach(r -> firstDate.put((Long) r[0], (LocalDate) r[1]));
        return resolve(firstDate);
    }

    /** Berilgan o'quvchilarning birinchi to'lovi (cohort uchun). */
    public Map<Long, FirstPayment> firstPaymentsOf(Collection<Long> studentIds) {
        Map<Long, FirstPayment> out = new HashMap<>();
        if (studentIds.isEmpty()) {
            return out;
        }
        List<Object[]> rows = em.createQuery("""
                SELECT p.student.id, MIN(p.paymentDate) FROM Payment p
                WHERE p.status = :paid AND p.cashAmount > 0 AND p.student.id IN :ids
                GROUP BY p.student.id
                """, Object[].class)
            .setParameter("paid", PaymentStatus.PAID).setParameter("ids", studentIds).getResultList();
        Map<Long, LocalDate> firstDate = new HashMap<>();
        rows.forEach(r -> firstDate.put((Long) r[0], (LocalDate) r[1]));
        resolve(firstDate).forEach(fp -> out.put(fp.studentId(), fp));
        return out;
    }

    private List<FirstPayment> resolve(Map<Long, LocalDate> firstDate) {
        if (firstDate.isEmpty()) {
            return List.of();
        }
        Map<Long, FirstPayment> best = new HashMap<>();
        for (Payment p : em.createQuery("""
                SELECT p FROM Payment p
                WHERE p.status = :paid AND p.cashAmount > 0 AND p.student.id IN :ids
                ORDER BY p.paymentDate, p.id
                """, Payment.class)
            .setParameter("paid", PaymentStatus.PAID).setParameter("ids", firstDate.keySet()).getResultList()) {
            Long sid = p.getStudent().getId();
            if (!best.containsKey(sid) && p.getPaymentDate().equals(firstDate.get(sid))) {
                best.put(sid, new FirstPayment(sid, p.getId(), p.getPaymentDate(), p.getCashAmount()));
            }
        }
        return List.copyOf(best.values());
    }

    public Map<Long, Student> studentsByIds(Collection<Long> ids) {
        Map<Long, Student> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        em.createQuery("SELECT s FROM Student s WHERE s.id IN :ids", Student.class)
            .setParameter("ids", ids).getResultList().forEach(s -> out.put(s.getId(), s));
        return out;
    }

    public EntityManager em() {
        return em;
    }
}

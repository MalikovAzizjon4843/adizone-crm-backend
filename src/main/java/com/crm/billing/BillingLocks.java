package com.crm.billing;

import com.crm.entity.BonusPenalty;
import com.crm.entity.CashRegister;
import com.crm.entity.Payment;
import com.crm.entity.Payroll;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Pessimistik qulflar ({@code SELECT … FOR UPDATE}) — docs/design/billing-v2.md §7.
 *
 * <p><b>Qulf tartibi</b> (deadlock yo'q):
 * <pre>
 * Student → StudentGroup(lar) id o'sishida → Payment → Payroll → BonusPenalty(lar) id o'sishida → CashRegister(lar) id o'sishida
 * </pre>
 * Har bir amal o'z qulflarini BITTA {@link #acquire(Plan)} chaqiruvi bilan oladi —
 * tartib shu metod ichida majburlanadi, chaqiruvchi uni buza olmaydi.
 *
 * <p><b>Eskirgan holat.</b> Entity shu tranzaksiyada avval qulfsiz o'qilgan bo'lsa,
 * {@code FOR UPDATE} so'rovi persistence context dagi eski nusxani qaytaradi.
 * Shuning uchun boshqariladigan (managed) entity qulf ostida {@code refresh}
 * qilinadi. {@code Student} istisno: uning kolleksiyalari {@code CascadeType.ALL},
 * refresh ularni ham qayta yuklardi — o'quvchi faqat qulflanadi, agregat esa
 * baribir SG lardan qayta hisoblanadi (§8).
 */
@Component
@RequiredArgsConstructor
public class BillingLocks {

    /** Qulfni kutish chegarasi; oshsa — 409 {@code concurrency.busy}. */
    public static final int LOCK_TIMEOUT_MS = 5000;

    private static final Map<String, Object> HINTS =
        Map.of("jakarta.persistence.lock.timeout", LOCK_TIMEOUT_MS);

    private final EntityManager entityManager;

    /** Qulflanishi kerak bo'lgan qatorlar. Tartib {@link #acquire} da. */
    public static final class Plan {
        private Long studentId;
        private final TreeSet<Long> enrollmentIds = new TreeSet<>();
        private Long paymentId;
        private Long payrollId;
        private final TreeSet<Long> bonusIds = new TreeSet<>();
        private final TreeSet<Long> cashRegisterIds = new TreeSet<>();

        public static Plan of() {
            return new Plan();
        }

        public Plan student(Long id) {
            this.studentId = id;
            return this;
        }

        public Plan enrollment(Long id) {
            if (id != null) {
                enrollmentIds.add(id);
            }
            return this;
        }

        public Plan enrollments(Collection<Long> ids) {
            ids.forEach(this::enrollment);
            return this;
        }

        public Plan payment(Long id) {
            this.paymentId = id;
            return this;
        }

        public Plan payroll(Long id) {
            this.payrollId = id;
            return this;
        }

        public Plan bonuses(Collection<Long> ids) {
            ids.forEach(id -> {
                if (id != null) {
                    bonusIds.add(id);
                }
            });
            return this;
        }

        public Plan cashRegister(Long id) {
            if (id != null) {
                cashRegisterIds.add(id);
            }
            return this;
        }
    }

    /** Qulflangan qatorlar — qulf ostida qayta o'qilgan, joriy holat. */
    public record Locked(
        Student student,
        Map<Long, StudentGroup> enrollments,
        Payment payment,
        Payroll payroll,
        List<BonusPenalty> bonuses,
        Map<Long, CashRegister> cashRegisters) {

        public StudentGroup enrollment(Long id) {
            return enrollments.get(id);
        }

        public CashRegister cashRegister(Long id) {
            return cashRegisters.get(id);
        }
    }

    public Locked acquire(Plan plan) {
        Student student = plan.studentId != null ? lockStudent(plan.studentId) : null;

        Map<Long, StudentGroup> enrollments = new LinkedHashMap<>();
        for (Long id : plan.enrollmentIds) {
            enrollments.put(id, lockRefreshing(StudentGroup.class, id));
        }

        Payment payment = plan.paymentId != null ? lockRefreshing(Payment.class, plan.paymentId) : null;

        Payroll payroll = plan.payrollId != null ? lockRefreshing(Payroll.class, plan.payrollId) : null;

        List<BonusPenalty> bonuses = new ArrayList<>();
        for (Long id : plan.bonusIds) {
            bonuses.add(lockRefreshing(BonusPenalty.class, id));
        }

        Map<Long, CashRegister> registers = new LinkedHashMap<>();
        for (Long id : plan.cashRegisterIds) {
            registers.put(id, lockRefreshing(CashRegister.class, id));
        }

        return new Locked(student, enrollments, payment, payroll, bonuses, registers);
    }

    /**
     * Faqat kassa(lar) — kassa amallari (kirim/chiqim/o'tkazma) uchun. To'lov oqimida
     * kassa {@link #acquire} bilan student/SG dan keyin olinadi; bu yerda qayta
     * qulflash zararsiz (qulf allaqachon shu tranzaksiyada).
     */
    public CashRegister lockCashRegister(Long id) {
        return lockRefreshing(CashRegister.class, id);
    }

    /** Qisqa yo'l: o'quvchi + bitta yozilma (accrual, muzlatish). */
    public StudentGroup lockEnrollmentWithStudent(Long studentId, Long enrollmentId) {
        return acquire(Plan.of().student(studentId).enrollment(enrollmentId)).enrollment(enrollmentId);
    }

    private Student lockStudent(Long id) {
        try {
            Student managed = findManaged(Student.class, id);
            if (managed != null) {
                entityManager.lock(managed, LockModeType.PESSIMISTIC_WRITE, HINTS);
                return managed;
            }
            Student found = entityManager.find(Student.class, id, LockModeType.PESSIMISTIC_WRITE, HINTS);
            if (found == null) {
                throw new ResourceNotFoundException("Student", id);
            }
            return found;
        } catch (PessimisticLockException | LockTimeoutException | PessimisticLockingFailureException e) {
            throw busy(e);
        }
    }

    private <T> T lockRefreshing(Class<T> type, Long id) {
        try {
            T managed = findManaged(type, id);
            if (managed != null) {
                // Kutilayotgan o'zgarishlar refresh da yo'qolmasin
                entityManager.flush();
                entityManager.refresh(managed, LockModeType.PESSIMISTIC_WRITE, HINTS);
                return managed;
            }
            T found = entityManager.find(type, id, LockModeType.PESSIMISTIC_WRITE, HINTS);
            if (found == null) {
                throw new ResourceNotFoundException(type.getSimpleName(), id);
            }
            return found;
        } catch (PessimisticLockException | LockTimeoutException | PessimisticLockingFailureException e) {
            throw busy(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T findManaged(Class<T> type, Long id) {
        SessionImplementor session = entityManager.unwrap(SessionImplementor.class);
        EntityPersister persister = session.getFactory().getMappingMetamodel().getEntityDescriptor(type);
        Object entity = session.getPersistenceContextInternal()
            .getEntity(session.generateEntityKey(id, persister));
        return (T) entity;
    }

    private static ConflictException busy(RuntimeException cause) {
        ConflictException ex = new ConflictException("concurrency.busy");
        ex.initCause(cause);
        return ex;
    }
}

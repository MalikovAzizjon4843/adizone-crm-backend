package com.crm.billing;

import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.StudentStatusHistory;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.StudentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * O'quvchi holati ({@code students.status}) yozilmalardan kelib chiqadi — YAGONA qoida shu yerda.
 *
 * <ol>
 *   <li>Faol yozilma bor (nofaol emas, muzlatilmagan; sinov ham) → {@code ACTIVE};</li>
 *   <li>faol yo'q, muzlatilgani bor → {@code FROZEN};</li>
 *   <li>ochiq yozilma qolmagan → oxirgi yopilgan yozilma sababiga ko'ra {@code GRADUATED}
 *       (kod yoki matn GRADUATED), {@code SUSPENDED} (matn SUSPENDED) yoki {@code LEFT}.
 *       Admin qo'ygan {@code ARCHIVED} / {@code FINISHED} / {@code SUSPENDED} shu holatda saqlanadi;</li>
 *   <li>hech qachon yozilmagan o'quvchi — holat o'zgarmaydi.</li>
 * </ol>
 *
 * <p>{@link BillingSnapshotService#refreshStudent} har yozilma / ledger amalidan keyin chaqiradi:
 * guruhga qo'shish, chiqarish (remove-student, DELETE), ko'chirish, muzlatish va qaytarish,
 * to'lov, kunlik job — hammasi shu orqali. Holat tarixini ({@code student_status_history})
 * foydalanuvchi amali o'zi yozadi ({@link #recordIfChanged}) — sabab va izoh u yerda ma'lum.
 */
@Service
@RequiredArgsConstructor
public class StudentStatusService {

    /** Admin qo'lda qo'yadigan yakuniy holatlar — ochiq yozilma bo'lmasa saqlanadi. */
    private static final Set<StudentStatus> MANUAL_TERMINAL =
        EnumSet.of(StudentStatus.ARCHIVED, StudentStatus.FINISHED, StudentStatus.SUSPENDED);

    private static final String REASON_SUSPENDED = "SUSPENDED";

    private final StudentGroupRepository studentGroupRepository;
    private final StudentRepository studentRepository;
    private final StudentStatusHistoryRepository historyRepository;
    private final Clock billingClock;

    /** @return oldingi holat */
    @Transactional
    public StudentStatus sync(Student student) {
        if (student == null || student.getId() == null) {
            return null;
        }
        return sync(student, studentGroupRepository.findByStudentId(student.getId()));
    }

    /** {@code all} — o'quvchining BARCHA yozilmalari (chaqiruvchi allaqachon o'qigan bo'lsa). */
    @Transactional
    public StudentStatus sync(Student student, Collection<StudentGroup> all) {
        StudentStatus previous = student.getStatus();
        StudentStatus target = derive(previous, all);
        if (target != previous) {
            student.setStatus(target);
            studentRepository.save(student);
        }
        return previous;
    }

    /** Holat o'zgargan bo'lsa tarixga bitta qator (amal boshidagi {@code before} bilan). */
    @Transactional
    public void recordIfChanged(Student student, StudentStatus before, String reason, String notes) {
        StudentStatus after = student.getStatus();
        if (after == before) {
            return;
        }
        StudentStatusHistory h = new StudentStatusHistory();
        h.setStudent(student);
        h.setFromStatus(before != null ? before.name() : null);
        h.setToStatus(after != null ? after.name() : null);
        h.setReason(reason);
        h.setNotes(notes);
        h.setBalanceSnapshot(student.getBalance());
        h.setChangedAt(LocalDateTime.now(billingClock));
        historyRepository.save(h);
    }

    /** Sof qoida (yuqoridagi 1–4). */
    public static StudentStatus derive(StudentStatus current, Collection<StudentGroup> all) {
        if (all == null || all.isEmpty()) {
            return current;
        }
        boolean frozen = false;
        for (StudentGroup sg : all) {
            if (isActive(sg)) {
                return StudentStatus.ACTIVE;
            }
            frozen |= EnrollmentLifecycleService.isFrozen(sg);
        }
        if (frozen) {
            return StudentStatus.FROZEN;
        }
        if (current != null && MANUAL_TERMINAL.contains(current)) {
            return current;
        }
        StudentGroup last = lastClosed(all);
        if (last != null && isGraduated(last)) {
            return StudentStatus.GRADUATED;
        }
        if (last != null && REASON_SUSPENDED.equalsIgnoreCase(trim(last.getExitReason()))) {
            return StudentStatus.SUSPENDED;
        }
        return StudentStatus.LEFT;
    }

    static boolean isActive(StudentGroup sg) {
        return Boolean.TRUE.equals(sg.getIsActive()) && sg.getFrozenFrom() == null;
    }

    static boolean isGraduated(StudentGroup sg) {
        return sg.getExitReasonCode() == ExitReasonCode.GRADUATED
            || "GRADUATED".equalsIgnoreCase(trim(sg.getExitReason()));
    }

    /** Eng oxirgi yopilgan: {@code leave_date ?? exit_date}, keyin {@code updated_at}, keyin id. */
    private static StudentGroup lastClosed(Collection<StudentGroup> all) {
        List<StudentGroup> closed = all.stream()
            .filter(sg -> !isActive(sg) && !EnrollmentLifecycleService.isFrozen(sg))
            .toList();
        return closed.stream()
            .max(Comparator.comparing((StudentGroup sg) -> closedOn(sg), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(StudentGroup::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(StudentGroup::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
    }

    private static LocalDate closedOn(StudentGroup sg) {
        return sg.getLeaveDate() != null ? sg.getLeaveDate() : sg.getExitDate();
    }

    private static String trim(String s) {
        return s != null ? s.trim() : null;
    }
}

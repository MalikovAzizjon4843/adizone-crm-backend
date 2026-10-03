package com.crm.service;

import com.crm.billing.Money;
import com.crm.dto.response.FrozenStudentResponse;
import com.crm.dto.response.LeftStudentResponse;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Chiqib ketganlar" va "Muzlatilganlar" ro'yxatlari — batch so'rovlar (N+1 yo'q).
 *
 * <p>Ilgari {@code /left} oddiy {@code StudentResponse} qaytarardi: unda oxirgi guruh, chiqish
 * sanasi va sababi umuman yo'q edi, {@code currentGroup} esa faqat FAOL yozilmadan olinardi —
 * chiqib ketgan o'quvchida doim null. {@code /frozen} summasi {@code students.balance} (barcha
 * guruhlar yig'indisi) edi, muzlatilgan yozilmaning o'z ledger qoldig'i emas.
 */
@Service
@RequiredArgsConstructor
public class StudentRosterService {

    /** IN (...) ro'yxati uchun bo'lak — PostgreSQL parametr chegarasidan ancha past. */
    private static final int CHUNK = 1000;

    private static final List<StudentStatus> LEFT_STATUSES = List.of(StudentStatus.LEFT, StudentStatus.GRADUATED);

    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final BalanceTransactionRepository balanceTransactionRepository;

    @Transactional(readOnly = true)
    public List<LeftStudentResponse> getLeftStudents() {
        List<Student> students = studentRepository.findByStatusIn(LEFT_STATUSES);
        Map<Long, List<StudentGroup>> byStudent = enrollmentsByStudent(
            students.stream().map(Student::getId).toList());

        List<LeftStudentResponse> rows = new ArrayList<>();
        for (Student s : students) {
            StudentGroup last = lastEnrollment(byStudent.getOrDefault(s.getId(), List.of()));
            boolean closed = last != null && !Boolean.TRUE.equals(last.getIsActive());
            rows.add(LeftStudentResponse.builder()
                .id(s.getId())
                .firstName(s.getFirstName())
                .lastName(s.getLastName())
                .fullName(fullName(s))
                .phone(s.getPhone())
                .parentPhone(s.getParentPhone())
                .status(s.getStatus())
                .lastGroupId(last != null && last.getGroup() != null ? last.getGroup().getId() : null)
                .lastGroupName(last != null && last.getGroup() != null ? last.getGroup().getGroupName() : null)
                .lastStudentGroupId(last != null ? last.getId() : null)
                .leaveDate(closed ? closedOn(last) : null)
                .exitReason(closed ? exitReason(last) : null)
                .exitReasonCode(closed ? last.getExitReasonCode() : null)
                .exitNotes(closed ? last.getExitNotes() : null)
                .balance(Money.normalize(Money.nz(s.getBalance())))
                .debt(Money.normalize(Money.nz(s.getDebt())))
                .updatedAt(s.getUpdatedAt())
                .build());
        }
        // Eng yangi chiqqanlar tepada; sanasizlar oxirida
        rows.sort(Comparator.comparing(LeftStudentResponse::getLeaveDate,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(LeftStudentResponse::getId, Comparator.reverseOrder()));
        return rows;
    }

    /**
     * Har muzlatilgan yozilma alohida (o'quvchi ichida), qoldiq — ledger'dan. O'quvchi holati
     * ARCHIVED bo'lsa ko'rsatilmaydi. {@code status = FROZEN}, lekin muzlatilgan yozilmasi
     * topilmagan eski qator ham ro'yxatda qoladi (yozilmalarsiz, oxirgi guruh bilan) — avvalgi
     * xulq yo'qolmasin.
     */
    @Transactional(readOnly = true)
    public List<FrozenStudentResponse> getFrozenStudents() {
        Map<Long, List<StudentGroup>> frozenByStudent = new LinkedHashMap<>();
        Map<Long, Student> students = new LinkedHashMap<>();
        for (StudentGroup sg : studentGroupRepository.findFrozenWithStudentAndGroup()) {
            Student s = sg.getStudent();
            if (s == null || s.getStatus() == StudentStatus.ARCHIVED) {
                continue;
            }
            students.putIfAbsent(s.getId(), s);
            frozenByStudent.computeIfAbsent(s.getId(), k -> new ArrayList<>()).add(sg);
        }
        List<Student> legacy = new ArrayList<>();
        for (Student s : studentRepository.findByStatus(StudentStatus.FROZEN)) {
            if (!students.containsKey(s.getId())) {
                students.put(s.getId(), s);
                legacy.add(s);
            }
        }

        Map<Long, BigDecimal> ledger = ledgerBalances(frozenByStudent.values().stream()
            .flatMap(List::stream).map(StudentGroup::getId).toList());
        Map<Long, List<StudentGroup>> legacyEnrollments = enrollmentsByStudent(
            legacy.stream().map(Student::getId).toList());

        List<FrozenStudentResponse> rows = new ArrayList<>();
        for (Student s : students.values()) {
            List<StudentGroup> frozen = frozenByStudent.getOrDefault(s.getId(), List.of());
            List<FrozenStudentResponse.FrozenEnrollment> items = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal remaining = BigDecimal.ZERO;
            BigDecimal debt = BigDecimal.ZERO;
            for (StudentGroup sg : frozen) {
                BigDecimal b = ledger.getOrDefault(sg.getId(), BigDecimal.ZERO);
                BigDecimal rem = b.signum() > 0 ? b : BigDecimal.ZERO;
                BigDecimal owed = b.signum() < 0 ? b.negate() : BigDecimal.ZERO;
                total = total.add(b);
                remaining = remaining.add(rem);
                debt = debt.add(owed);
                items.add(FrozenStudentResponse.FrozenEnrollment.builder()
                    .studentGroupId(sg.getId())
                    .groupId(sg.getGroup() != null ? sg.getGroup().getId() : null)
                    .groupName(sg.getGroup() != null ? sg.getGroup().getGroupName() : null)
                    .frozenFrom(frozenFrom(sg))
                    .balance(Money.normalize(b))
                    .remainingAmount(Money.normalize(rem))
                    .debt(Money.normalize(owed))
                    .note(sg.getExitNotes())
                    .build());
            }
            items.sort(Comparator.comparing(FrozenStudentResponse.FrozenEnrollment::getFrozenFrom,
                    Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(FrozenStudentResponse.FrozenEnrollment::getStudentGroupId, Comparator.reverseOrder()));

            Long lastGroupId;
            String lastGroupName;
            LocalDate frozenDate;
            if (!items.isEmpty()) {
                FrozenStudentResponse.FrozenEnrollment latest = items.get(0);
                lastGroupId = latest.getGroupId();
                lastGroupName = latest.getGroupName();
                frozenDate = latest.getFrozenFrom();
            } else {
                StudentGroup last = lastEnrollment(legacyEnrollments.getOrDefault(s.getId(), List.of()));
                lastGroupId = last != null && last.getGroup() != null ? last.getGroup().getId() : null;
                lastGroupName = last != null && last.getGroup() != null ? last.getGroup().getGroupName() : null;
                frozenDate = last != null && last.getLeaveDate() != null ? last.getLeaveDate()
                    : (s.getUpdatedAt() != null ? s.getUpdatedAt().toLocalDate() : null);
            }

            rows.add(FrozenStudentResponse.builder()
                .studentId(s.getId())
                .fullName(fullName(s))
                .phone(s.getPhone())
                .studentStatus(s.getStatus())
                .frozenDate(frozenDate)
                .balance(Money.normalize(total))
                .lastGroupId(lastGroupId)
                .lastGroupName(lastGroupName)
                .totalRemaining(Money.normalize(remaining))
                .totalDebt(Money.normalize(debt))
                .enrollments(items)
                .build());
        }
        rows.sort(Comparator.comparing(FrozenStudentResponse::getFrozenDate,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(FrozenStudentResponse::getStudentId, Comparator.reverseOrder()));
        return rows;
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private Map<Long, List<StudentGroup>> enrollmentsByStudent(List<Long> studentIds) {
        Map<Long, List<StudentGroup>> out = new HashMap<>();
        for (List<Long> chunk : chunks(studentIds)) {
            for (StudentGroup sg : studentGroupRepository.findWithGroupByStudentIds(chunk)) {
                out.computeIfAbsent(sg.getStudent().getId(), k -> new ArrayList<>()).add(sg);
            }
        }
        return out;
    }

    private Map<Long, BigDecimal> ledgerBalances(List<Long> sgIds) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (List<Long> chunk : chunks(sgIds)) {
            for (Object[] row : balanceTransactionRepository.sumAmountGroupedByStudentGroupIds(chunk)) {
                out.put(((Number) row[0]).longValue(), Money.nz((BigDecimal) row[1]));
            }
        }
        return out;
    }

    /**
     * Oxirgi yopilgan yozilma ({@code leave_date ?? exit_date}, keyin id); yopilgani bo'lmasa —
     * eng oxirgi qo'shilgani.
     */
    static StudentGroup lastEnrollment(Collection<StudentGroup> all) {
        Comparator<StudentGroup> byClose = Comparator
            .comparing(StudentRosterService::closedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(StudentGroup::getId);
        StudentGroup closed = all.stream()
            .filter(sg -> !Boolean.TRUE.equals(sg.getIsActive()))
            .max(byClose)
            .orElse(null);
        if (closed != null) {
            return closed;
        }
        return all.stream()
            .max(Comparator.comparing(StudentGroup::getJoinDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(StudentGroup::getId))
            .orElse(null);
    }

    private static LocalDate closedOn(StudentGroup sg) {
        return sg.getLeaveDate() != null ? sg.getLeaveDate() : sg.getExitDate();
    }

    private static LocalDate frozenFrom(StudentGroup sg) {
        return sg.getFrozenFrom() != null ? sg.getFrozenFrom() : closedOn(sg);
    }

    private static String exitReason(StudentGroup sg) {
        if (sg.getExitReason() != null && !sg.getExitReason().isBlank()) {
            return sg.getExitReason();
        }
        return sg.getExitReasonCode() != null ? sg.getExitReasonCode().name() : null;
    }

    private static String fullName(Student s) {
        return ((s.getFirstName() != null ? s.getFirstName() : "")
            + " " + (s.getLastName() != null ? s.getLastName() : "")).trim();
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            out.add(ids.subList(i, Math.min(ids.size(), i + CHUNK)));
        }
        return out;
    }
}

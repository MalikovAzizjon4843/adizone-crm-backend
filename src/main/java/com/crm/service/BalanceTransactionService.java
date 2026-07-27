package com.crm.service;

import com.crm.dto.response.BalanceHistoryItemDto;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.User;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BalanceTransactionService {

    private final BalanceTransactionRepository balanceTransactionRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;

    /**
     * SG balansini o'zgartiradi va audit yozuv yaratadi.
     * student.balance = barcha StudentGroup.balance yig'indisi.
     */
    @Transactional
    public BalanceTransaction record(
            StudentGroup sg,
            BalanceTransactionType type,
            BigDecimal amount,
            Long referenceId,
            String note) {
        if (sg == null) {
            throw new BadRequestException("StudentGroup majburiy");
        }
        if (type == null) {
            throw new BadRequestException("Transaction type majburiy");
        }
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }

        Student student = sg.getStudent();
        if (student == null || student.getId() == null) {
            throw new BadRequestException("Student topilmadi");
        }

        BigDecimal before = nz(sg.getBalance());
        BigDecimal after = before.add(amount);
        sg.setBalance(after);
        studentGroupRepository.save(sg);

        syncStudentBalanceFromGroups(student);

        BalanceTransaction tx = BalanceTransaction.builder()
            .studentGroup(sg)
            .student(student)
            .type(type)
            .amount(amount)
            .balanceAfter(after)
            .referenceId(referenceId)
            .note(note)
            .createdBy(currentUserOrNull())
            .createdAt(LocalDateTime.now())
            .build();
        return balanceTransactionRepository.save(tx);
    }

    /** Student.balance = barcha guruh balanslari yig'indisi. */
    @Transactional
    public void syncStudentBalanceFromGroups(Student student) {
        if (student == null || student.getId() == null) {
            return;
        }
        BigDecimal sum = studentGroupRepository.findByStudentId(student.getId()).stream()
            .map(g -> nz(g.getBalance()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        student.setBalance(sum);
        studentRepository.save(student);
    }

    /** @deprecated use {@link #record} */
    @Transactional
    public BalanceTransaction recordStudentOnly(
            Student student,
            StudentGroup sg,
            BalanceTransactionType type,
            BigDecimal amount,
            Long referenceId,
            String note) {
        if (sg != null) {
            return record(sg, type, amount, referenceId, note);
        }
        throw new BadRequestException("StudentGroup majburiy");
    }

    @Transactional(readOnly = true)
    public List<BalanceHistoryItemDto> getHistory(
            Long studentId, Long groupId, LocalDate from, LocalDate to) {
        if (!studentRepository.existsById(studentId)) {
            throw new ResourceNotFoundException("Student", studentId);
        }
        LocalDateTime fromDt = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDt = to != null ? to.atTime(LocalTime.MAX) : null;
        return balanceTransactionRepository.findHistory(studentId, groupId, fromDt, toDt)
            .stream()
            .map(this::toHistoryDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> verifyBalances() {
        List<StudentGroup> all = studentGroupRepository.findAll();
        List<Map<String, Object>> mismatched = new ArrayList<>();
        int checked = 0;

        for (StudentGroup sg : all) {
            checked++;
            BigDecimal stored = nz(sg.getBalance());
            BigDecimal calculated = nz(
                balanceTransactionRepository.sumAmountByStudentGroupId(sg.getId()));
            if (stored.compareTo(calculated) != 0) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("studentGroupId", sg.getId());
                row.put("studentId", sg.getStudent() != null ? sg.getStudent().getId() : null);
                row.put("stored", stored);
                row.put("calculated", calculated);
                row.put("diff", stored.subtract(calculated));
                mismatched.add(row);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", checked);
        result.put("mismatched", mismatched);
        result.put("mismatchCount", mismatched.size());
        return result;
    }

    @Transactional
    public BalanceHistoryItemDto manualAdjust(Long studentId, Long groupId, BigDecimal amount, String note) {
        if (note == null || note.isBlank()) {
            throw new BadRequestException("Sabab majburiy");
        }
        if (amount == null) {
            throw new BadRequestException("Summa majburiy");
        }
        StudentGroup sg = studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
            .or(() -> studentGroupRepository.findByStudentId(studentId).stream()
                .filter(g -> g.getGroup() != null && groupId.equals(g.getGroup().getId()))
                .findFirst())
            .orElseThrow(() -> new ResourceNotFoundException("StudentGroup", groupId));

        if (sg.getStudent() == null || !studentId.equals(sg.getStudent().getId())) {
            throw new BadRequestException("Guruh bu o'quvchiga tegishli emas");
        }

        BalanceTransaction tx = record(sg, BalanceTransactionType.MANUAL_ADJUST, amount, null, note.trim());
        return toHistoryDto(tx);
    }

    public static String typeLabel(BalanceTransactionType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case LESSON_CHARGE -> "Dars uchun yechildi";
            case LESSON_REFUND -> "Davomat qaytarildi";
            case PAYMENT -> "To'lov qabul qilindi";
            case FREEZE -> "Muzlatish";
            case UNFREEZE -> "Muzlatishdan chiqarish";
            case MANUAL_ADJUST -> "Qo'lda tuzatish";
        };
    }

    private BalanceHistoryItemDto toHistoryDto(BalanceTransaction t) {
        String createdBy = null;
        if (t.getCreatedBy() != null) {
            createdBy = ((t.getCreatedBy().getFirstName() != null ? t.getCreatedBy().getFirstName() : "")
                + " "
                + (t.getCreatedBy().getLastName() != null ? t.getCreatedBy().getLastName() : "")).trim();
            if (createdBy.isEmpty()) {
                createdBy = t.getCreatedBy().getUsername();
            }
        }
        return BalanceHistoryItemDto.builder()
            .date(t.getCreatedAt())
            .type(t.getType())
            .typeLabel(typeLabel(t.getType()))
            .amount(t.getAmount())
            .balanceAfter(t.getBalanceAfter())
            .note(t.getNote())
            .groupId(t.getStudentGroup() != null && t.getStudentGroup().getGroup() != null
                ? t.getStudentGroup().getGroup().getId() : null)
            .groupName(t.getStudentGroup() != null && t.getStudentGroup().getGroup() != null
                ? t.getStudentGroup().getGroup().getGroupName() : null)
            .createdBy(createdBy)
            .build();
    }

    private User currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}

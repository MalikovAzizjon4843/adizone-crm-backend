package com.crm.dto.response;

import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.StudentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code GET /api/students/left} qatori. Oxirgi guruh — o'quvchining eng oxirgi YOPILGAN
 * yozilmasi ({@code leave_date}, keyin {@code id} bo'yicha); yopilgani yo'q bo'lsa (nomuvofiq
 * eski qator) — eng oxirgi qo'shilgani, {@code leaveDate = null}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeftStudentResponse {
    /** O'quvchi id si (front {@code s.id} ni ishlatadi). */
    private Long id;
    private String firstName;
    private String lastName;
    private String fullName;
    private String phone;
    private String parentPhone;
    private StudentStatus status;
    private Long lastGroupId;
    private String lastGroupName;
    private Long lastStudentGroupId;
    /** Yozilma yopilgan kun ({@code student_groups.leave_date}, bo'lmasa {@code exit_date}). */
    private LocalDate leaveDate;
    /** Front kaliti: erkin matn ({@code exit_reason}), bo'lmasa {@code exitReasonCode} nomi. */
    private String exitReason;
    private ExitReasonCode exitReasonCode;
    private String exitNotes;
    /** Billing v2: Σ sg.balance (I1, ledger). */
    private BigDecimal balance;
    /** Billing v2: Σ max(0, −sg.balance). */
    private BigDecimal debt;
    private LocalDateTime updatedAt;
}

package com.crm.dto.response;

import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.BonusTargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BonusPenaltyDto {
    private Long id;
    private String uuid;
    private BonusPenaltyKind kind;
    private BonusTargetType targetType;
    private Long studentId;
    private String studentName;
    private Long teacherId;
    private String teacherName;
    /** STAFF: xodim (ADMIN/SALES_MANAGER). */
    private Long userId;
    private String userName;
    private BigDecimal amount;
    private String reason;
    private BonusPenaltyStatus status;
    private LocalDate effectiveDate;
    private LocalDateTime createdAt;
    private String createdByName;
    private String targetName;
    private BigDecimal signedAmount;
    /** Billing v2 (§6.5). */
    private Long studentGroupId;
    private Long groupId;
    private Long ledgerTxId;
    private Long appliedToPaymentId;
    /** TEACHER: qaysi oylikka qo'llangan (APPROVE paytida, payroll-v2 §4). */
    private Long appliedToPayrollId;
    private String cancelReason;
}

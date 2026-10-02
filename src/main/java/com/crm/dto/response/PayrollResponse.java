package com.crm.dto.response;

import com.crm.entity.enums.PayrollStatus;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Payroll — docs/design/payroll-v2-api.md. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PayrollResponse {
    private Long id;
    private UUID uuid;
    private Long teacherId;
    /** Xodim ismi (o'qituvchi bo'lsa — Teacher dan). */
    private String teacherName;
    private Long userId;
    private String userName;
    private String role;
    private Integer month;
    private Integer year;
    private PayrollStatus status;

    /** = FIXED qatori. */
    private BigDecimal basicSalary;
    /** = gross − FIXED (rol formulasi qatorlari). */
    private BigDecimal allowances;
    private BigDecimal deductions;
    private BigDecimal grossSalary;
    /** DRAFT da — kutilayotgan (PENDING) bonuslar; APPROVED dan keyin — qo'llangan. */
    private BigDecimal bonusPenaltyAdjustment;
    private BigDecimal netSalary;
    private Integer paidStudentCount;
    /** TEACHER: to'lagan birliklar (davrlar + PER_LESSON ulushlari, kasr bo'lishi mumkin — §11 #2). */
    private BigDecimal paidStudentUnits;
    private Integer newStudentCount;
    private Boolean kpiApplied;
    private BigDecimal kpiAmount;
    /** Tuzilgan obyekt (payroll-v2 §6); v1 yozuvlarida eski shakl. */
    private JsonNode calculationDetails;
    private Integer calcVersion;

    private LocalDate paymentDate;
    /** PaymentMethod nomi (value); to'lanmagan oylikda null. */
    private String paymentMethod;
    private String paymentMethodLabel;
    private String paymentMethodIcon;
    private Long cashRegisterId;
    private String cashRegisterName;
    private Long cashTransactionId;

    private LocalDateTime approvedAt;
    private String approvedByName;
    private LocalDateTime paidAt;
    private String paidByName;
    private LocalDateTime cancelledAt;
    private String cancelledByName;
    private String cancelReason;

    private String notes;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

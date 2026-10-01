package com.crm.dto.response;

import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private Long id;
    private UUID uuid;
    private Long studentId;
    private String studentName;
    private Long groupId;
    private String groupName;
    private BigDecimal amount;
    private BigDecimal discountAmount;
    private BigDecimal bonusDiscount;
    /** Balansdan yechilgan summa */
    private BigDecimal balanceUsed;
    /** gross - discount: to'lov sifatida hisoblangan summa. */
    private BigDecimal payable;
    /** Kassaga tushgan real pul: payable - balanceUsed. */
    private BigDecimal cashAmount;
    private String receiptNumber;
    /** Amount formatted for display, e.g. "800 000 so'm" */
    private String formattedAmount;
    private LocalDate paymentDate;
    private PaymentMethod paymentMethod;
    private PaymentStatus status;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String description;
    private LocalDateTime createdAt;
    private Long cashRegisterId;
    private String cashRegisterName;

    // ── Billing v2 (§10.2) ──
    private Long studentGroupId;
    /** Yozilgan qatorlar: accrual, PAYMENT, DISCOUNT, BONUS, PENALTY. */
    private java.util.List<BillingLineDto> lines;
    private BigDecimal balanceAfter;
    private BigDecimal debtAfter;
    private String statusAfter;
    private LocalDate nextPaymentDate;
    private BigDecimal nextPaymentAmount;
    private String planHash;
    // bekor qilish
    private LocalDateTime cancelledAt;
    private String cancelledByName;
    private String cancelReason;
    private java.util.List<BillingLineDto> reversalLines;
    private java.util.List<String> warnings;
    /** Idempotency-Key bo'yicha qaytarilgan (yangi to'lov yaratilmagan). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean replay;
}

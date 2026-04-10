package com.crm.dto.request;

import com.crm.entity.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PaymentRequest {
    @NotNull
    private Long studentId;
    private Long groupId;
    @NotNull
    @DecimalMin("0.0")
    private BigDecimal amount;
    private PaymentMethod paymentMethod = PaymentMethod.CASH;
    private LocalDate paymentDate;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String description;
    private String notes;
    private BigDecimal discountAmount;
}

package com.crm.dto.request;
import com.crm.entity.enums.PaymentMethod;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
@Data
public class PaymentRequest {
    @NotNull private Long studentId;
    private Long groupId;
    @NotNull @DecimalMin("0.01") private BigDecimal amount;
    private PaymentMethod paymentMethod = PaymentMethod.CASH;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String description;
    private String notes;
}

package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PaymentStartDateRequest {
    @NotNull
    private LocalDate paymentStartDate;
    /** Optional — true = TRIAL, false = to'lovli; null = o'zgartirilmaydi */
    private Boolean isTrial;
}

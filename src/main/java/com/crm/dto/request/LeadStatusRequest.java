package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class LeadStatusRequest {

    @NotBlank(message = "{leadStatus.status.required}")
    private String status;

    /**
     * To'lov summasi — ixtiyoriy. Yangi bosqich {@code requiresAmount}
     * bo'lsa va lidda hali summa bo'lmasa, majburiy bo'lib qoladi.
     *
     * <p>Berilsa eski qiymat ustiga yoziladi.
     */
    private BigDecimal amount;
}

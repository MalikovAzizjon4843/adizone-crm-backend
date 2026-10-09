package com.crm.dto.request;

import jakarta.validation.Valid;
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

    /**
     * Yangi bosqich {@code requires_task} bo'lsa va lidda ochiq vazifa bo'lmasa
     * MAJBURIY (400 {@code lead.task.required}); boshqa holatda berilsa ham yaratiladi.
     * Bosqich o'tishi bilan bitta tranzaksiyada.
     */
    @Valid
    private NextTaskRequest nextTask;
}

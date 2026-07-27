package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UnfreezeStudentRequest {
    @NotNull
    private Long groupId;
    @NotNull
    private LocalDate paymentStartDate;
}

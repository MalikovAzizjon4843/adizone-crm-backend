package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class StudentCreateAndAddRequest {

    @NotBlank(message = "Ism majburiy")
    private String firstName;

    @NotBlank(message = "Familiya majburiy")
    private String lastName;

    @NotBlank(message = "Telefon majburiy")
    private String phone;

    private String gender;
    private String marketingSource;
    private String parentPhone;

    @NotNull(message = "To'lov boshlanish sanasi majburiy")
    private LocalDate paymentStartDate;

    @NotNull(message = "Oylik to'lov summasi majburiy")
    private BigDecimal monthlyFee;

    private Boolean isTrial = false;
}

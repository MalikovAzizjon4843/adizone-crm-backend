package com.crm.dto.request;

import com.crm.entity.enums.PaymentType;
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

    /** MONTHLY uchun tavsiya etiladi; PER_LESSON da ixtiyoriy */
    private BigDecimal monthlyFee;

    private PaymentType paymentType;
    private BigDecimal lessonPrice;

    private Boolean isTrial = false;
}

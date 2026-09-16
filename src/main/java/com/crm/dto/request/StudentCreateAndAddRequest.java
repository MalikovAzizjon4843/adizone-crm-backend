package com.crm.dto.request;

import com.crm.config.PhoneDeserializer;
import com.crm.entity.enums.PaymentType;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class StudentCreateAndAddRequest {

    @NotBlank(message = "{studentCreateAndAdd.firstName.required}")
    private String firstName;

    @NotBlank(message = "{studentCreateAndAdd.lastName.required}")
    private String lastName;

    @NotBlank(message = "{studentCreateAndAdd.phone.required}")
    @JsonDeserialize(using = PhoneDeserializer.class)
    private String phone;

    private String gender;
    private String marketingSource;

    @JsonDeserialize(using = PhoneDeserializer.class)
    private String parentPhone;

    @NotNull(message = "{studentCreateAndAdd.paymentStartDate.required}")
    private LocalDate paymentStartDate;

    /** MONTHLY uchun tavsiya etiladi; PER_LESSON da ixtiyoriy */
    private BigDecimal monthlyFee;

    private PaymentType paymentType;
    private BigDecimal lessonPrice;

    private Boolean isTrial = false;
}

package com.crm.dto.request;

import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudyFormat;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class LeadConvertRequest {

    /**
     * MAJBURIY: lid qaysi CONVERTED bosqichiga tushishini shu hal qiladi
     * (CONVERTED_ONLINE yoki CONVERTED_OFFLINE) va guruhdagi yozuvga ham
     * o'tadi.
     */
    @NotNull(message = "{leadConvert.studyFormat.required}")
    private StudyFormat studyFormat;

    private Long groupId;
    private LocalDate paymentStartDate;
    private BigDecimal monthlyFee;
    private PaymentType paymentType;
    private BigDecimal lessonPrice;
    private Boolean isTrial;
}

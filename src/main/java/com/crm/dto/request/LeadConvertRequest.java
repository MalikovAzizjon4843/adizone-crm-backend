package com.crm.dto.request;

import com.crm.entity.enums.PaymentType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class LeadConvertRequest {

    private Long groupId;
    private LocalDate paymentStartDate;
    private BigDecimal monthlyFee;
    private PaymentType paymentType;
    private BigDecimal lessonPrice;
    private Boolean isTrial;
}

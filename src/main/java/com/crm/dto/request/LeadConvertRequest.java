package com.crm.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class LeadConvertRequest {

    private Long groupId;
    private LocalDate paymentStartDate;
    private BigDecimal monthlyFee;
    private Boolean isTrial;
}

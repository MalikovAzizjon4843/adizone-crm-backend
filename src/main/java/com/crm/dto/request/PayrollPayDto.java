package com.crm.dto.request;

import lombok.Data;

@Data
public class PayrollPayDto {
    private String paymentMethod;
    private Long cashRegisterId;
    /** Cash bucket: CASH or PLASTIC. Defaults to CASH when omitted. */
    private String paymentMethodForCash;
}

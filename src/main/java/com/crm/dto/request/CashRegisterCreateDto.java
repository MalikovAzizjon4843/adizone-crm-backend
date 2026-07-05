package com.crm.dto.request;

import lombok.Data;

@Data
public class CashRegisterCreateDto {
    private String name;
    private Long moderatorId;
    private Boolean acceptOnlinePayment;
    private Boolean archived;
}

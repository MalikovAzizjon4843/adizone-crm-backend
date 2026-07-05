package com.crm.dto.request;

import com.crm.entity.enums.CashPaymentMethod;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TransferDto {
    private Long fromCashRegisterId;
    private Long toCashRegisterId;
    private BigDecimal amount;
    private CashPaymentMethod paymentMethod;
    private String note;
}

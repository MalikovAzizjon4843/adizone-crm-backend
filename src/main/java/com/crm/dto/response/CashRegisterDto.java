package com.crm.dto.response;

import com.crm.entity.enums.CashRegisterStatus;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CashRegisterDto {
    private Long id;
    private String uuid;
    private String name;
    private Long moderatorId;
    private String moderatorName;
    private BigDecimal balance;
    private BigDecimal plasticBalance;
    private BigDecimal cashBalance;
    private CashRegisterStatus status;
    private boolean acceptOnlinePayment;
    private boolean archived;
}

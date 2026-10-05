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
    /**
     * Qoldiq to'lov usuli guruhlari bo'yicha (CASH, CARD, TERMINAL, ONLINE, OTHER) — kassa
     * tranzaksiyalaridan. Tranzaksiyasiz o'zgarish (boshlang'ich qoldiq) bu yerda yo'q —
     * {@code GET /{id}/balance} dagi {@code unattributed*} ga qarang.
     */
    private java.util.Map<String, BigDecimal> balanceByMethod;
    private CashRegisterStatus status;
    private boolean acceptOnlinePayment;
    private boolean archived;
}

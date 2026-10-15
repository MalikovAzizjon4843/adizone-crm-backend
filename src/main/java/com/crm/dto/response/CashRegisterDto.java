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
    /** ADMIN — admin kassasi, DIRECTOR — Direktor kassasi (faqat SA ko'radi). */
    private com.crm.entity.enums.CashRegisterKind kind;
    /** Razmen (topshirishdan keyin kassada qoladi). */
    private BigDecimal floatAmount;
    /** A'zolar: ADMIN / ACCOUNTANT (V86). */
    private java.util.List<Member> members;

    public record Member(Long userId, String name, String role) {
    }
}

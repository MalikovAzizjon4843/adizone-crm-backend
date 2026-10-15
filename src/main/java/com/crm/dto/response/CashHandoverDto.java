package com.crm.dto.response;

import com.crm.entity.enums.CashHandoverStatus;
import com.crm.entity.enums.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Kunlik topshirish (docs/design/cash-and-books.md §1.2.3). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashHandoverDto {
    private Long id;
    private String uuid;
    private Long cashRegisterId;
    private String cashRegisterName;
    private Long targetCashRegisterId;
    private String targetCashRegisterName;
    private LocalDate businessDate;
    private CashHandoverStatus status;
    private BigDecimal floatAmount;
    /** Topshirish paytidagi {@code cash_balance − float_amount}. */
    private BigDecimal expectedCash;
    private BigDecimal declaredCash;
    private BigDecimal countedCash;
    /** counted − expected: manfiy — kamomad, musbat — ortiqcha. */
    private BigDecimal cashDiff;
    private String note;
    private String diffNote;
    private String rejectReason;
    private Long submittedBy;
    private String submittedByName;
    private LocalDateTime submittedAt;
    private Long decidedBy;
    private String decidedByName;
    private LocalDateTime decidedAt;
    private boolean legacy;
    private boolean idempotentReplay;
    private List<Line> lines;

    /** Usul bo'yicha solishtirish: kutilgan (tizim) va haqiqiy (Direktor kiritgan). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private PaymentMethod paymentMethod;
        private BigDecimal expectedIn;
        private BigDecimal expectedOut;
        private BigDecimal expectedNet;
        private BigDecimal actual;
        private BigDecimal diff;
    }
}

package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * To'lov preview'i — hech narsa saqlanmaydi (docs/design/billing-v2.md §5.4).
 * Create bilan BITTA {@code plan()} dan hisoblanadi: {@code lines} va {@code planHash} teng.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentPreviewResponse {
    // ── v2 ──
    private Long studentGroupId;
    private String groupName;
    @Builder.Default
    private List<BillingLineDto> lines = new ArrayList<>();
    /** Ledgerdagi joriy balans (rejadagi accrual'siz). */
    private BigDecimal balanceBefore;
    /** Rejadagi accrual'dan keyingi, to'lovdan oldingi qarz. */
    private BigDecimal debtBefore;
    private BigDecimal debtAfter;
    private String statusAfter;
    private LocalDate nextPaymentDateAfter;
    private BigDecimal nextPaymentAmountAfter;
    /** Sinovdagi o'quvchi to'lovli qilinadi (§6.6). */
    private Boolean convertsTrial;
    /** Create so'roviga {@code expectedPlanHash} sifatida yuborilsa — o'zgargan bo'lsa 409. */
    private String planHash;
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    // ── eski front mosligi ──
    /** So'rovdagi to'liq summa. */
    private BigDecimal gross;
    private BigDecimal discount;
    /** gross − discount */
    private BigDecimal payable;
    /** v2 da doim 0 — musbat balans keyingi davrni o'zi yopadi. */
    private BigDecimal balanceUsed;
    /** Kassaga tushadigan real pul. */
    private BigDecimal cashAmount;
    /** O'quvchining joriy balansi (barcha SG). */
    private BigDecimal studentBalance;
    /** Yozilma balansi to'lovdan keyin. */
    private BigDecimal balanceAfter;
}

package com.crm.dto.request;

import com.crm.entity.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PaymentRequest {
    @NotNull(message = "{payment.studentId.required}")
    private Long studentId;
    private Long groupId;
    @NotNull(message = "{payment.amount.required}")
    @DecimalMin(value = "0.0", message = "{payment.amount.min}")
    private BigDecimal amount;
    private PaymentMethod paymentMethod = PaymentMethod.CASH;
    private LocalDate paymentDate;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String description;
    private String notes;
    private BigDecimal discountAmount;
    /** Cash register that receives this payment as income. */
    private Long cashRegisterId;
    /** Kassaga yoziladigan usulni majburan belgilaydi (PaymentMethod nomi).
     *  Ko'rsatilmasa — paymentMethod ishlatiladi. Eski "PLASTIC" -> CARD. */
    private String paymentMethodForCash;
    /** Faqat CASH_AND_CARD uchun: kassaga tushadigan summaning naqd qismi. */
    private BigDecimal cashPart;
    /** Faqat CASH_AND_CARD uchun: kassaga tushadigan summaning karta qismi. */
    private BigDecimal cardPart;
    /** O'quvchi balansidan foydalanilsinmi. null/false — balans ishlatilmaydi. */
    private Boolean useBalance;
    /**
     * Frontend hisoblagan balans summasi — FAQAT ma'lumot uchun saqlanadi,
     * hisobda ISHLATILMAYDI. Haqiqiy qiymatni backend o'zi hisoblaydi.
     */
    private BigDecimal balanceAmount;
    /** Apply pending student bonus/penalty on create. Defaults to true when null. */
    private Boolean applyBonuses;
}

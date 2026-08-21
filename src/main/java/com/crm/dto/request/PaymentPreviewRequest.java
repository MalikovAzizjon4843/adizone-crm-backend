package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** To'lovni saqlamasdan oldindan hisoblash so'rovi. */
@Data
public class PaymentPreviewRequest {
    @NotNull(message = "{paymentPreview.studentId.required}")
    private Long studentId;
    private Long groupId;
    /** To'liq summa — chegirma ham, balans ham ayrilmagan. null/manfiy -> 0. */
    private BigDecimal amount;
    private BigDecimal discountAmount;
    /** O'quvchi balansidan foydalanilsinmi. */
    private Boolean useBalance;
}

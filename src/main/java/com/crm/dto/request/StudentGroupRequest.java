package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class StudentGroupRequest {
    @NotNull
    private Long studentId;
    @NotNull
    private Long groupId;
    private LocalDate joinDate;
    /** true = 1-dars bepul (TRIAL); default false = to'lovli (PENDING) */
    private Boolean isTrial = false;
    /** To'lov qaysi sanadan hisoblanadi; default = bugun */
    private LocalDate paymentStartDate;
    /** Ixtiyoriy oylik to'lov; bo'sh bo'lsa guruh/kursdan olinadi */
    private BigDecimal monthlyFee;
    private BigDecimal discountPercentage;
    /** Legacy — monthlyFee bo'sh bo'lsa shu ishlatiladi */
    private BigDecimal monthlyPriceOverride;
    private String notes;
}

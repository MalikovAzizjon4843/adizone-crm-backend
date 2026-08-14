package com.crm.dto.request;
import com.crm.entity.enums.ExpenseCategory;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
@Data
public class ExpenseRequest {
    @NotNull private ExpenseCategory category;
    @NotBlank private String title;
    @NotNull @DecimalMin("0.01") private BigDecimal amount;
    @NotNull private LocalDate expenseDate;
    private Long teacherId;
    private String description;
    private String notes;
    /** Cash register to deduct this expense from. */
    private Long cashRegisterId;
    /** Kassaga yoziladigan usul (PaymentMethod nomi). Ko'rsatilmasa — CASH.
     *  Eski "PLASTIC" -> CARD. */
    private String paymentMethodForCash;
    /** Faqat CASH_AND_CARD uchun: naqd qismi (cashPart + cardPart = amount). */
    private BigDecimal cashPart;
    /** Faqat CASH_AND_CARD uchun: karta qismi. */
    private BigDecimal cardPart;
}

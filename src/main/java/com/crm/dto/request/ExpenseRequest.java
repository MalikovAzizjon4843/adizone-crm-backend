package com.crm.dto.request;
import com.crm.entity.enums.ExpenseCategory;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
@Data
public class ExpenseRequest {
    @NotNull(message = "{expense.category.required}") private ExpenseCategory category;
    @NotBlank(message = "{expense.title.required}") private String title;
    @NotNull(message = "{expense.amount.required}") @DecimalMin("0.01", message = "{expense.amount.min}") private BigDecimal amount;
    @NotNull(message = "{expense.expenseDate.required}") private LocalDate expenseDate;
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

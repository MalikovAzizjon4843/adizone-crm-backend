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
}

package com.crm.dto.request;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
@Data
public class StudentGroupRequest {
    @NotNull private Long studentId;
    @NotNull private Long groupId;
    private LocalDate joinDate;
    private BigDecimal discountPercentage;
    private BigDecimal monthlyPriceOverride;
    private String notes;
}

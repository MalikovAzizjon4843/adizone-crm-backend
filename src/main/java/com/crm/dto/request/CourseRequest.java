package com.crm.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class CourseRequest {
    @NotBlank private String courseName;
    private String description;
    @NotNull @Min(1) private Integer durationMonths;
    @NotNull @Min(1) private Integer lessonsCount;
    @NotNull @DecimalMin("0.0") private BigDecimal monthlyPrice;
}

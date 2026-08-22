package com.crm.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class CourseRequest {
    @NotBlank(message = "{course.courseName.required}") private String courseName;
    private String description;
    @NotNull(message = "{course.durationMonths.required}") @Min(value = 1, message = "{course.durationMonths.min}") private Integer durationMonths;
    @NotNull(message = "{course.lessonsCount.required}") @Min(value = 1, message = "{course.lessonsCount.min}") private Integer lessonsCount;
    @NotNull(message = "{course.monthlyPrice.required}") @DecimalMin(value = "0.0", message = "{course.monthlyPrice.min}") private BigDecimal monthlyPrice;
    /** Ixtiyoriy — bir dars narxi */
    @DecimalMin(value = "0.0", message = "{course.lessonPrice.min}") private BigDecimal lessonPrice;
}

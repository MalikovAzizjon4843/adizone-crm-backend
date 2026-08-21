package com.crm.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class CourseRequest {
    @NotBlank(message = "{course.courseName.required}") private String courseName;
    private String description;
    @NotNull(message = "{course.durationMonths.required}") @Min(1, message = "{course.durationMonths.min}") private Integer durationMonths;
    @NotNull(message = "{course.lessonsCount.required}") @Min(1, message = "{course.lessonsCount.min}") private Integer lessonsCount;
    @NotNull(message = "{course.monthlyPrice.required}") @DecimalMin("0.0", message = "{course.monthlyPrice.min}") private BigDecimal monthlyPrice;
    /** Ixtiyoriy — bir dars narxi */
    @DecimalMin("0.0", message = "{course.lessonPrice.min}") private BigDecimal lessonPrice;
}

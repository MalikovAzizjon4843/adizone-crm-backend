package com.crm.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
@Data
public class TeacherRequest {
    @NotBlank private String firstName;
    @NotBlank private String lastName;
    @NotBlank private String phone;
    private String email;
    private String subjectSpecialization;
    private BigDecimal monthlySalary;
    private LocalDate hireDate;
    private String notes;
    private Long userId;
}

package com.crm.dto.response;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TeacherResponse {
    private Long id;
    private UUID uuid;
    private String firstName;
    private String lastName;
    private String phone;
    private String email;
    private String subjectSpecialization;
    private BigDecimal monthlySalary;
    private LocalDate hireDate;
    private Boolean isActive;
    private String notes;
    private int activeGroupsCount;
    private LocalDateTime createdAt;
}

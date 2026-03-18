package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PromotionRequest {
    @NotNull(message = "Student ID is required")
    private Long studentId;
    private Long fromClassId;
    private Long toClassId;
    private Long fromSectionId;
    private Long toSectionId;
    private String fromAcademicYear;
    private String toAcademicYear;
    private LocalDate promotionDate;
    private Long promotedById;
    private String remarks;
}

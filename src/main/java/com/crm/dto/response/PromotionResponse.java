package com.crm.dto.response;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PromotionResponse {
    private Long id;
    private Long studentId;
    private String studentName;
    private Long fromClassId;
    private String fromClassName;
    private Long toClassId;
    private String toClassName;
    private Long fromSectionId;
    private String fromSectionName;
    private Long toSectionId;
    private String toSectionName;
    private String fromAcademicYear;
    private String toAcademicYear;
    private LocalDate promotionDate;
    private String promotedByName;
    private String remarks;
    private LocalDateTime createdAt;
}

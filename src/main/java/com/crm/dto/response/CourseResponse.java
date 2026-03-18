package com.crm.dto.response;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CourseResponse {
    private Long id;
    private UUID uuid;
    private String courseName;
    private String description;
    private Integer durationMonths;
    private Integer lessonsCount;
    private BigDecimal monthlyPrice;
    private Boolean isActive;
    private LocalDateTime createdAt;
}

package com.crm.dto.response;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ClassResponse {
    private Long id;
    private String className;
    private String classCode;
    private Boolean isActive;
    private LocalDateTime createdAt;
}

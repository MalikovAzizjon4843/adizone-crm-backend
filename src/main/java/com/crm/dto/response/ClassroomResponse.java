package com.crm.dto.response;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ClassroomResponse {
    private Long id;
    private String roomName;
    private String roomNumber;
    private Integer capacity;
    private String floor;
    private Boolean isActive;
    private LocalDateTime createdAt;
}

package com.crm.dto.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NoticeResponse {
    private Long id;
    private UUID uuid;
    private String title;
    private String content;
    private String noticeType;
    private String targetRole;
    private Boolean isPublished;
    private LocalDateTime publishedAt;
    private LocalDateTime expiresAt;
    private String createdByName;
    private LocalDateTime createdAt;
}

package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NoticeRequest {
    @NotBlank(message = "Title is required")
    private String title;
    @NotBlank(message = "Content is required")
    private String content;
    private String noticeType;
    private String targetRole;
    private Boolean isPublished;
    private LocalDateTime publishedAt;
    private LocalDateTime expiresAt;
    private Long createdById;
}

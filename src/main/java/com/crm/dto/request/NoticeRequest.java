package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class NoticeRequest {
    @NotBlank(message = "Title is required")
    private String title;
    @NotBlank(message = "Content is required")
    private String content;
    private LocalDate noticeDate;
    /** ALL, TEACHERS, STUDENTS, PARENTS */
    private String publishedTo;
    private String noticeType;
    private String targetRole;
    private Boolean isActive;
    private Boolean isPublished;
    private LocalDateTime publishedAt;
    /** Full timestamp expiry (preferred if both sent). */
    private LocalDateTime expiresAt;
    /** Calendar-day expiry; maps to expiresAt end-of-day when expiresAt is null. */
    private LocalDate expiryDate;
    private Long createdById;
}

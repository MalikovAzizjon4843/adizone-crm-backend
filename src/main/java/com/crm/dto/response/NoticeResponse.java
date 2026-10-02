package com.crm.dto.response;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoticeResponse {
    private Long id;
    private UUID uuid;
    private String title;
    private String content;
    private LocalDate noticeDate;
    private String publishedTo;
    private String noticeType;
    private String targetRole;
    /** Auditoriya rollari; bo'sh — hamma ({@code audienceAll = true}). */
    private List<String> targetRoles;
    private Boolean audienceAll;
    private Boolean isActive;
    private Boolean isPublished;
    private LocalDateTime publishedAt;
    private LocalDateTime expiresAt;
    /** Calendar date derived from expiresAt (null = never expires). */
    private LocalDate expiryDate;
    private Boolean isExpired;
    private Boolean isRead;
    private String createdByName;
    private LocalDateTime createdAt;
}

package com.crm.dto.request;

import com.crm.entity.enums.UserRole;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class NoticeRequest {
    @NotBlank(message = "{notice.title.required}")
    private String title;
    @NotBlank(message = "{notice.content.required}")
    private String content;
    private LocalDate noticeDate;
    /**
     * Auditoriya — rollar (phase5-audit N-01). {@code []} — hamma. Berilmasa — eski maydonlar
     * o'qiladi: {@code targetRole} (bitta rol), keyin {@code publishedTo} (ALL, TEACHERS, STUDENTS,
     * PARENTS). Uchchalasi ham yo'q bo'lsa PUT da auditoriya o'zgarmaydi, POST da — hamma.
     */
    private List<UserRole> targetRoles;
    /** @deprecated {@link #targetRoles} ishlating. ALL, TEACHERS, STUDENTS, PARENTS */
    @Deprecated
    private String publishedTo;
    private String noticeType;
    /** @deprecated {@link #targetRoles} ishlating. */
    @Deprecated
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

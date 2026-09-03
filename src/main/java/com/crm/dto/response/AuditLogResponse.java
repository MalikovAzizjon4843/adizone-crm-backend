package com.crm.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {
    private Long id;
    private LocalDateTime createdAt;
    private Long userId;
    private String username;
    private String userRole;
    private String action;
    private String entityType;
    private Long entityId;
    private String entityLabel;
    private String summary;
    /** Parse qilingan detailsJson — {"changes":[...]}. Bo'sh bo'lsa null. */
    private Object details;
    private String ipAddress;
}

package com.crm.dto.response;

import com.crm.entity.enums.LeadStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadResponse {
    private Long id;
    private UUID uuid;
    private String fullName;
    private String phone;
    private String parentPhone;
    private String address;
    private String course;
    private String format;
    private LeadStatus status;
    private String source;
    private String notes;
    private Boolean converted;
    private Long studentId;
    private String studentName;
    private Long assignedUserId;
    private String assignedUserName;
    private LocalDateTime assignedAt;
    private long commentsCount;
    private String lastCommentText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

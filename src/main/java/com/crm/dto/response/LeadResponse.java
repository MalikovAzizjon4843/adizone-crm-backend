package com.crm.dto.response;

import com.crm.entity.enums.LeadTaskState;
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
    /** Bosqich kodi ({@code lead_stages.code}). */
    private String status;
    /** Joriy tildagi nomi — {@code lead_stages} dan. */
    private String statusLabel;
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

    /**
     * Eng yaqin OCHIQ vazifa. Saqlanmaydi — har so'rovda batch so'rov bilan
     * yuklanadi (qarang {@code TaskService.loadNextOpenTasks}).
     */
    private LocalDateTime nextTaskDueAt;
    private String nextTaskTitle;
    /** Kanban kartasidagi rangli nuqta: NONE/PLANNED/TODAY/OVERDUE. */
    private LeadTaskState taskState;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

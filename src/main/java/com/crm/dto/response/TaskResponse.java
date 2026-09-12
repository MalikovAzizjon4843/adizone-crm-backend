package com.crm.dto.response;

import com.crm.entity.enums.LeadTaskState;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.TaskType;
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
public class TaskResponse {
    private Long id;
    private UUID uuid;
    private String title;
    private String description;
    private TaskType type;
    private String typeLabel;
    private TaskStatus status;
    private String statusLabel;
    private LocalDateTime dueAt;
    private Boolean allDay;
    /** Kartadagi rang: NONE/PLANNED/TODAY/OVERDUE. Yopilgan vazifada NONE. */
    private LeadTaskState state;
    private Long assignedToId;
    private String assignedToName;
    private Long createdById;
    private String createdByName;
    private Long leadId;
    private String leadName;
    private Long studentId;
    private String studentName;
    private LocalDateTime completedAt;
    private Long completedById;
    private String completedByName;
    private String result;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

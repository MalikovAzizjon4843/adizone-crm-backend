package com.crm.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuspendedStudentResponse {
    private Long studentId;
    private String studentName;
    private Long groupId;
    private String groupName;
    private LocalDateTime suspendedAt;
    private String suspensionReason;
    private Long daysSinceSuspended;
}

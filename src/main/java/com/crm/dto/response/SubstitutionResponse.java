package com.crm.dto.response;

import com.crm.entity.enums.SubstitutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubstitutionResponse {
    private Long id;
    private Long groupId;
    private String groupName;
    private LocalDate lessonDate;
    private String startTime;
    private String endTime;
    private Long originalTeacherId;
    private String originalTeacherName;
    private Long substituteTeacherId;
    private String substituteTeacherName;
    private Long leaveRequestId;
    private SubstitutionStatus status;
    private LocalDateTime conductedAt;
    private String conductedByName;
    private String note;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime cancelledAt;
    private String cancelledByName;
    private String cancelReason;
    /** Faqat {@code GET /api/substitutions/my}: joriy o'qituvchi bu darsda kim. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Role role;

    public enum Role {
        /** Guruh o'qituvchisi — darsini boshqa o'qituvchi o'tadi. */
        ORIGINAL,
        /** Darsni o'tuvchi (o'rinbosar). */
        SUBSTITUTE
    }
}

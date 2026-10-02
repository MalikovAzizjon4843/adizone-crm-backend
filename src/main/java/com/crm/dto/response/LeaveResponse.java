package com.crm.dto.response;

import com.crm.entity.enums.LeaveStatus;
import com.crm.entity.enums.LeaveType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** leaves-exams-contracts §1.3. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LeaveResponse {
    private Long id;
    private UUID uuid;
    private Long userId;
    private String userName;
    private String userRole;
    private Long teacherId;
    /** @deprecated {@code userName} (o'qituvchi bo'lsa — profil nomi). */
    private String teacherName;
    private Long requesterId;
    private String requesterName;
    private LeaveType leaveType;
    private LocalDate fromDate;
    private LocalDate toDate;
    /** Kalendar kunlari. */
    private int days;
    /** Ish kunlari: Du–Sha, bayramlarsiz. */
    private int workdays;
    private String reason;
    private LeaveStatus status;
    private Boolean paid;
    private Long decidedById;
    private String decidedByName;
    private LocalDateTime decidedAt;
    private String decisionNote;
    private LocalDateTime cancelledAt;
    private String cancelledByName;
    private LocalDateTime createdAt;
    /** Faqat approve javobida (o'qituvchi bo'lsa): shu davrdagi rejadagi darslar. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<AffectedLessonDto> affectedLessons;
}

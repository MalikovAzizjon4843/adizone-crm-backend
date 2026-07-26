package com.crm.dto.response;

import com.crm.entity.enums.UnlockRequestStatus;
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
public class AttendanceUnlockResponseDto {
    private Long id;
    private Long teacherId;
    private String teacherName;
    private Long groupId;
    private String groupName;
    private LocalDate attendanceDate;
    private UnlockRequestStatus status;
    private String teacherNote;
    private Long reviewedById;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}

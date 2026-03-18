package com.crm.dto.response;
import com.crm.entity.enums.AttendanceStatus;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AttendanceResponse {
    private Long id;
    private Long studentId;
    private String studentName;
    private Long groupId;
    private String groupName;
    private LocalDate attendanceDate;
    private AttendanceStatus status;
    private String notes;
    private LocalDateTime createdAt;
}

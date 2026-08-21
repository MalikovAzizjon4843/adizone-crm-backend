package com.crm.dto.request;
import com.crm.entity.enums.AttendanceStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;
@Data
public class AttendanceRequest {
    @NotNull(message = "{attendance.groupId.required}") private Long groupId;
    @NotNull(message = "{attendance.date.required}") private LocalDate date;
    @NotNull(message = "{attendance.attendances.required}") private List<StudentAttendanceItem> attendances;
    @Data
    public static class StudentAttendanceItem {
        private Long studentId;
        private AttendanceStatus status;
        private String notes;
        private Boolean excused;
        private String excuseReason;
    }
}

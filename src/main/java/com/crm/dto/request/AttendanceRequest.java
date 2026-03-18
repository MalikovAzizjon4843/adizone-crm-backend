package com.crm.dto.request;
import com.crm.entity.enums.AttendanceStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;
@Data
public class AttendanceRequest {
    @NotNull private Long groupId;
    @NotNull private LocalDate date;
    @NotNull private List<StudentAttendanceItem> attendances;
    @Data
    public static class StudentAttendanceItem {
        private Long studentId;
        private AttendanceStatus status;
        private String notes;
    }
}

package com.crm.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;
@Data
public class GroupRequest {
    @NotBlank private String groupName;
    @NotNull private Long courseId;
    private Long teacherId;
    private String room;
    @Min(1) private Integer maxStudents = 20;
    @NotNull private LocalDate startDate;
    private LocalDate endDate;
    private String notes;
    private List<ScheduleRequest> schedules;
}

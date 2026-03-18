package com.crm.dto.response;
import com.crm.entity.enums.GroupStatus;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class GroupResponse {
    private Long id;
    private UUID uuid;
    private String groupName;
    private Long courseId;
    private String courseName;
    private Long teacherId;
    private String teacherName;
    private String room;
    private Integer maxStudents;
    private Integer currentStudents;
    private LocalDate startDate;
    private LocalDate endDate;
    private GroupStatus status;
    private List<ScheduleResponse> schedules;
    private LocalDateTime createdAt;
}

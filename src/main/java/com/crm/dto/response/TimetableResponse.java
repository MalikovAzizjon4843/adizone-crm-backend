package com.crm.dto.response;

import lombok.*;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TimetableResponse {
    private Long id;
    private Long groupId;
    private String groupName;
    private Long classId;
    private String className;
    private Long sectionId;
    private String sectionName;
    private Long subjectId;
    private String subjectName;
    private Long teacherId;
    private String teacherName;
    private Long classroomId;
    private String roomName;
    private String roomNumber;
    private String dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private String academicYear;
    private LocalDateTime createdAt;
}

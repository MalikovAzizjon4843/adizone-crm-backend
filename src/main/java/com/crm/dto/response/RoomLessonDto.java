package com.crm.dto.response;

import lombok.Data;

@Data
public class RoomLessonDto {
    private Long id;
    private Long classroomId;
    private Long groupId;
    private String groupName;
    private Long teacherId;
    private String teacherName;
    private String startTime;
    private String endTime;
    private long studentCount;
    private Integer capacity;
    private Integer freeSeats;
    private String courseColor;
}

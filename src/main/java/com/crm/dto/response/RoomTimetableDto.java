package com.crm.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class RoomTimetableDto {
    private List<ClassroomBriefDto> classrooms;
    private List<RoomLessonDto> lessons;
}

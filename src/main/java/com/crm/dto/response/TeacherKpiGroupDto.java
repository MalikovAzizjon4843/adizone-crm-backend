package com.crm.dto.response;

import lombok.Data;

@Data
public class TeacherKpiGroupDto {
    private Long groupId;
    private String groupName;
    private long studentCount;
    private String roomName;
    private Integer capacity;
    private Integer freeSeats;
}

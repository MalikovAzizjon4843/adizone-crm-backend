package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClassroomRequest {
    @NotBlank(message = "Room name is required")
    private String roomName;
    private String roomNumber;
    private Integer capacity;
    private String floor;
}

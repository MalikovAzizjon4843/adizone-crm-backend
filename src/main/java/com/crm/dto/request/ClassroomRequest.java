package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClassroomRequest {
    @NotBlank
    private String roomNumber;
    private Integer capacity;
    /** THEORY, PRACTICE, LAB, OTHER */
    private String roomType;
    private String description;
    private Boolean isActive;
}

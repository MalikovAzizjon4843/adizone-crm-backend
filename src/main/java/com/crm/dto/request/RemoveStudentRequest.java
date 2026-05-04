package com.crm.dto.request;

import lombok.Data;

@Data
public class RemoveStudentRequest {
    private Long studentId;
    // GRADUATED, LEFT, TRANSFERRED, SUSPENDED, OTHER
    private String reason;
    private String notes;
}

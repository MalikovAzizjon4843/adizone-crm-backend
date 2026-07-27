package com.crm.dto.request;

import lombok.Data;

@Data
public class FreezeStudentRequest {
    /** null = barcha active guruhlar */
    private Long groupId;
    private String reason;
    private String note;
}

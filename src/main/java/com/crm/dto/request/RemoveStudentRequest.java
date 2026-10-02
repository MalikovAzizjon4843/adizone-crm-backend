package com.crm.dto.request;

import lombok.Data;

@Data
public class RemoveStudentRequest {
    private Long studentId;
    // GRADUATED, LEFT, TRANSFERRED, SUSPENDED, OTHER
    private String reason;
    private String notes;
    /** Direktor dashboardi (§3.4, §7 #12): berilmasa {@code reason} matnidan, aks holda OTHER. */
    private com.crm.entity.enums.ExitReasonCode reasonCode;
}

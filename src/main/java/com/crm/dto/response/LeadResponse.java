package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadResponse {
    private Long id;
    private UUID uuid;
    private String fullName;
    private String phone;
    private String address;
    private String course;
    private String format;
    private String status;
    private String source;
    private String notes;
    private Boolean converted;
    private Long studentId;
    private String studentName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

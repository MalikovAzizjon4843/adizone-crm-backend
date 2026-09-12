package com.crm.dto.response;

import com.crm.entity.enums.LeadStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadStatusHistoryResponse {
    private Long id;
    private LeadStatus fromStatus;
    private String fromStatusLabel;
    private LeadStatus toStatus;
    private String toStatusLabel;
    private Long changedById;
    private String changedByName;
    private LocalDateTime changedAt;
    private String note;
    /** Oldingi bosqichda necha kun turgani. Birinchi yozuvda null. */
    private Long daysInPreviousStatus;
}

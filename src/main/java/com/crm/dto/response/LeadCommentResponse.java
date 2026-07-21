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
public class LeadCommentResponse {
    private Long id;
    private Long leadId;
    private Long authorId;
    private String authorFullName;
    private String text;
    private LeadStatus statusAtComment;
    private LocalDateTime createdAt;
}

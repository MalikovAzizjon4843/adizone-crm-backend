package com.crm.dto.request;

import lombok.Data;

/** {@code POST /api/exams/{id}/registrations/{regId}/cancel} — sabab majburiy (3–500 belgi). */
@Data
public class ExamRegistrationCancelRequest {
    private String reason;
}

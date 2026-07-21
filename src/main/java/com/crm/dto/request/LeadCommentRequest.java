package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LeadCommentRequest {

    @NotBlank(message = "Izoh matni majburiy")
    private String text;
}

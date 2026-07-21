package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LeadStatusRequest {

    @NotBlank(message = "Status majburiy")
    private String status;
}

package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LeadRequest {

    @NotBlank(message = "{lead.fullName.required}")
    private String fullName;

    @NotBlank(message = "{lead.phone.required}")
    private String phone;

    private String parentPhone;
    private String address;
    private String course;
    private String format;
    private String source;
    private String notes;
}

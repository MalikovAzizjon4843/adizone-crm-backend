package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LeadRequest {

    @NotBlank(message = "Ism majburiy")
    private String fullName;

    @NotBlank(message = "Telefon majburiy")
    private String phone;

    private String address;
    private String course;
    private String format;
    private String source;
    private String notes;
}

package com.crm.dto.request;

import com.crm.config.PhoneDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LeadRequest {

    @NotBlank(message = "{lead.fullName.required}")
    private String fullName;

    @NotBlank(message = "{lead.phone.required}")
    @JsonDeserialize(using = PhoneDeserializer.class)
    private String phone;

    @JsonDeserialize(using = PhoneDeserializer.class)
    private String parentPhone;

    private String address;
    private String course;
    private String format;
    private String source;
    private String notes;
}

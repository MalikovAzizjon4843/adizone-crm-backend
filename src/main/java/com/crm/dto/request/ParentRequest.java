package com.crm.dto.request;

import com.crm.config.PhoneDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ParentRequest {

    @NotBlank(message = "{parent.fullName.required}")
    private String fullName;

    @NotBlank(message = "{parent.phone.required}")
    @JsonDeserialize(using = PhoneDeserializer.class)
    private String phone;

    private String address;

    private String telegramChatId;

    /** FATHER, MOTHER, or OTHER */
    private String relation;

    private Long studentId;
}

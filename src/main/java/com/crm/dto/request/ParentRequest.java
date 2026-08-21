package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ParentRequest {

    @NotBlank(message = "{parent.fullName.required}")
    private String fullName;

    @NotBlank(message = "{parent.phone.required}")
    private String phone;

    private String address;

    private String telegramChatId;

    /** FATHER, MOTHER, or OTHER */
    private String relation;

    private Long studentId;
}

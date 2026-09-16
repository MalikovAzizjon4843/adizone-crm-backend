package com.crm.dto.request;

import com.crm.config.PhoneDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

@Data
public class StudentParentRequest {

    /** To'liq ism (ustunlik). */
    private String fullName;

    private String firstName;
    private String lastName;

    @JsonDeserialize(using = PhoneDeserializer.class)
    private String phone;

    /** FATHER, MOTHER, OTHER */
    private String relation;

    private String address;

    private Boolean isPrimary;
}

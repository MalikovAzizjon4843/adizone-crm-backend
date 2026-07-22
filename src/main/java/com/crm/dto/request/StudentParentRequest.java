package com.crm.dto.request;

import lombok.Data;

@Data
public class StudentParentRequest {

    /** To'liq ism (ustunlik). */
    private String fullName;

    private String firstName;
    private String lastName;

    private String phone;

    /** FATHER, MOTHER, OTHER */
    private String relation;

    private String address;

    private Boolean isPrimary;
}

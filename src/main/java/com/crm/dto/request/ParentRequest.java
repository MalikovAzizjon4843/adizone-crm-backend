package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ParentRequest {

    @NotBlank(message = "First name is required")
    private String firstName;

    private String lastName;
    private String phone;
    private String email;
    private String occupation;
    private String address;
    private String photoUrl;
    private String relation;
}

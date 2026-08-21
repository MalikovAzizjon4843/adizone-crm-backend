package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank(message = "{login.username.required}")
    private String username;
    @NotBlank(message = "{login.password.required}")
    private String password;
}

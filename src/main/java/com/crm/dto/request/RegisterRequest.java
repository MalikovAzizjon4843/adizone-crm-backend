package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "Ism majburiy")
    private String firstName;

    @NotBlank(message = "Username majburiy")
    private String username;

    @NotBlank(message = "Parol majburiy")
    @Size(min = 6, message = "Parol kamida 6 belgi")
    private String password;

    @NotBlank(message = "Parolni tasdiqlang")
    private String confirmPassword;
}

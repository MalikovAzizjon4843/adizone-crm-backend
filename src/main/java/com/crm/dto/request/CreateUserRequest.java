package com.crm.dto.request;

import com.crm.entity.enums.UserRole;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateUserRequest {
    @NotBlank private String firstName;
    @NotBlank private String lastName;
    @NotBlank private String username;
    @NotBlank private String password;
    private String phone;
    private String email;
    private UserRole role;
    private Boolean isActive = true;
}

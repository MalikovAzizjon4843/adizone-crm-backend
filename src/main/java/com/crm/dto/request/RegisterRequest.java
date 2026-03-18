package com.crm.dto.request;
import com.crm.entity.enums.UserRole;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class RegisterRequest {
    @NotBlank @Size(min=3,max=50) private String username;
    @Email private String email;
    @NotBlank @Size(min=6) private String password;
    @NotBlank private String firstName;
    @NotBlank private String lastName;
    private String phone;
    private UserRole role = UserRole.ADMIN;
}

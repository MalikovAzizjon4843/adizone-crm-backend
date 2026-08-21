package com.crm.dto.request;

import com.crm.entity.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateUserRequest {

    @NotBlank(message = "{user.firstName.required}")
    @Size(max = 100, message = "{user.firstName.size}")
    private String firstName;

    @NotBlank(message = "{user.lastName.required}")
    @Size(max = 100, message = "{user.lastName.size}")
    private String lastName;

    /** Bo'sh qoldirilsa ism-familiyadan avtomatik yaratiladi (N.Yunusova). */
    @Size(max = 100, message = "{user.username.size}")
    private String username;

    @NotBlank(message = "{user.password.required}")
    @Size(min = 6, message = "{user.password.size}")
    private String password;

    @Pattern(
        regexp = "^$|^\\+998\\d{9}$|^\\d{9}$",
        message = "{user.phone.pattern}")
    private String phone;

    @Email(message = "{user.email.invalid}")
    @Size(max = 255, message = "{user.email.size}")
    private String email;

    @NotNull(message = "{user.role.required}")
    private UserRole role;

    private Boolean isActive = true;
}

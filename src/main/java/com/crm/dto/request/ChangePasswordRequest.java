package com.crm.dto.request;

import com.crm.security.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    @NotBlank(message = "{changePassword.newPassword.required}")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = "{user.password.size}")
    private String newPassword;
    private String currentPassword;
}

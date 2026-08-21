package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    @NotBlank(message = "{changePassword.newPassword.required}")
    private String newPassword;
    private String currentPassword;
}

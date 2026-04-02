package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    @NotBlank
    private String newPassword;
    private String currentPassword;
}

package com.crm.dto.request;

import com.crm.security.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** {@code POST /api/users/me/password}. Noto'g'ri joriy parol — 400 {@code user.password.invalid}. */
@Data
public class MyPasswordChangeRequest {

    @NotBlank(message = "{user.password.currentRequired}")
    private String currentPassword;

    @NotBlank(message = "{changePassword.newPassword.required}")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = "{user.password.size}")
    private String newPassword;
}

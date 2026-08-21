package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Admin tiklagan vaqtinchalik parol — faqat shu javobda bir marta ko'rsatiladi. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetResponse {
    private String username;
    private String temporaryPassword;
}

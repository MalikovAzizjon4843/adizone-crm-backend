package com.crm.dto.response;

import com.crm.entity.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** {@code GET/PUT /api/users/me} — joriy foydalanuvchining o'z hisobi. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyProfileResponse {
    private Long id;
    private String fullName;
    private String firstName;
    private String lastName;
    private String username;
    private UserRole role;
    private String phone;
    /** {@code users.photo_url} — {@code /api/files/...}. */
    private String avatarUrl;
    private LocalDateTime createdAt;
    /** {@code users.last_login} — oxirgi muvaffaqiyatli login. */
    private LocalDateTime lastLoginAt;
    /** Shu foydalanuvchiga bog'langan o'qituvchi profili ({@code teachers.user_id}); bo'lmasa null. */
    private Long teacherId;
}

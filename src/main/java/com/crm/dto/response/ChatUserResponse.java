package com.crm.dto.response;

import com.crm.entity.enums.UserRole;
import lombok.*;

/**
 * Chatda odam ko'rinadigan eng kichik shakl: avatar, ism, rol.
 * {@code GET /api/chat/users} va suhbat ishtirokchilari shuni qaytaradi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatUserResponse {
    private Long id;
    private String fullName;
    private String username;
    private UserRole role;
    private String photoUrl;
}

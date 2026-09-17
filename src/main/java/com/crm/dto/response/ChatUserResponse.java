package com.crm.dto.response;

import com.crm.entity.enums.UserRole;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Chatda odam ko'rinadigan eng kichik shakl: avatar, ism, rol va
 * onlayn holati. {@code GET /api/chat/users} va suhbat ishtirokchilari
 * shuni qaytaradi.
 *
 * <p>{@code online} xotiradagi sessiya sanog'idan olinadi, ya'ni
 * qo'shimcha SQL yo'q; {@code lastSeenAt} esa {@code users} jadvalidan
 * allaqachon yuklangan qatorda keladi.
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

    /** Shu daqiqada kamida bitta WebSocket sessiyasi ochiqmi. */
    private boolean online;

    /** Oxirgi sessiya uzilgan payt; onlayn bo'lsa e'tiborga olinmaydi. */
    private LocalDateTime lastSeenAt;
}

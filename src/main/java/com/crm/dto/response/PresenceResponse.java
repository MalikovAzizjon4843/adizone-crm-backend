package com.crm.dto.response;

import lombok.*;

import java.time.LocalDateTime;

/**
 * Onlayn holat: {@code GET /api/chat/presence} ro'yxatining bir qatori
 * va {@code /topic/presence} ga yuboriladigan hodisa — bitta shakl,
 * frontend ikkovini bir xil qayta ishlaydi.
 *
 * <p>{@code lastSeenAt} onlayn foydalanuvchida ham to'ldiriladi (oldingi
 * uzilish payti), lekin u holda ko'rsatilmaydi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresenceResponse {

    public static final String EVENT_TYPE = "PRESENCE";

    @Builder.Default
    private String type = EVENT_TYPE;

    private Long userId;
    private boolean online;
    private LocalDateTime lastSeenAt;
}

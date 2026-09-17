package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * {@code /app/chat.typing} tanasi.
 *
 * <p>{@code typing: false} ham yuboriladi — foydalanuvchi matnni
 * o'chirib tashlasa, boshqalar uchun belgi darhol yo'qolsin.
 */
@Data
public class ChatTypingRequest {

    @NotNull(message = "{chat.conversationId.required}")
    private Long conversationId;

    @NotNull(message = "{chat.typing.required}")
    private Boolean typing;
}

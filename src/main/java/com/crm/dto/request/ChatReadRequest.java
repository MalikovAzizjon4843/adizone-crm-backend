package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** {@code /app/chat.read} tanasi: shu xabargacha (shu xabar ham) o'qildi. */
@Data
public class ChatReadRequest {

    @NotNull(message = "{chat.conversationId.required}")
    private Long conversationId;

    @NotNull(message = "{chat.messageId.required}")
    private Long messageId;
}

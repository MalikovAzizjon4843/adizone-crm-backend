package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** {@code /app/chat.delete} tanasi. */
@Data
public class ChatDeleteRequest {

    @NotNull(message = "{chat.messageId.required}")
    private Long messageId;
}

package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Kim bilan yozishmoqchi — DIRECT suhbat bor bo'lsa mavjudi qaytariladi. */
@Data
public class DirectConversationRequest {

    @NotNull(message = "{chat.userId.required}")
    private Long userId;
}

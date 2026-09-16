package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Suhbatni ro'yxat tepasiga qadash — faqat shu foydalanuvchi uchun. */
@Data
public class ConversationPinRequest {

    @NotNull(message = "{chat.pinned.required}")
    private Boolean pinned;
}

package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Guruh yaratish. Yaratuvchi ro'yxatda bo'lmasa ham o'zi qo'shiladi —
 * o'zi kirmagan guruhni ochib qo'yish ma'nosiz.
 */
@Data
public class GroupConversationRequest {

    @NotBlank(message = "{chat.title.required}")
    @Size(max = 255, message = "{chat.title.size}")
    private String title;

    @NotEmpty(message = "{chat.userIds.required}")
    private List<Long> userIds;
}

package com.crm.dto.request;

import com.crm.entity.Message;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * {@code /app/chat.edit} tanasi.
 *
 * <p>Bo'sh matn bilan tahrirlab bo'lmaydi: matnni butunlay olib tashlash
 * o'chirish demakdir va buning o'z amali bor.
 */
@Data
public class ChatEditRequest {

    @NotNull(message = "{chat.messageId.required}")
    private Long messageId;

    @NotBlank(message = "{chat.text.required}")
    @Size(max = Message.TEXT_MAX, message = "{chat.text.size}")
    private String text;
}

package com.crm.dto.request;

import com.crm.entity.Message;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * {@code /app/chat.send} tanasi.
 *
 * <p>{@code clientId} — frontend o'ylab topadigan vaqtinchalik id.
 * Xabar hali serverga yetib bormasidan lentaga qo'yiladi, javobda shu
 * qiymat qaytgach esa haqiqiy xabarga almashtiriladi. Server uni
 * saqlamaydi — faqat aks ettiradi.
 */
@Data
public class ChatSendRequest {

    @NotNull(message = "{chat.conversationId.required}")
    private Long conversationId;

    @NotBlank(message = "{chat.text.required}")
    @Size(max = Message.TEXT_MAX, message = "{chat.text.size}")
    private String text;

    @Size(max = 64, message = "{chat.clientId.size}")
    private String clientId;
}

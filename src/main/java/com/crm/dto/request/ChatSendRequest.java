package com.crm.dto.request;

import com.crm.entity.Message;
import com.crm.entity.MessageAttachment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * {@code /app/chat.send} tanasi.
 *
 * <p>{@code clientId} — frontend o'ylab topadigan vaqtinchalik id.
 * Xabar hali serverga yetib bormasidan lentaga qo'yiladi, javobda shu
 * qiymat qaytgach esa haqiqiy xabarga almashtiriladi. Server uni
 * saqlamaydi — faqat aks ettiradi.
 *
 * <p>{@code text} bu yerda majburiy emas: biriktirmasi bor xabarda matn
 * ixtiyoriy izoh. Ikkovi ham bo'sh bo'lsa — 400, buni
 * {@code ChatService} tekshiradi, chunki qoida ikki maydonga tegishli
 * va bitta annotatsiyaga sig'maydi.
 */
@Data
public class ChatSendRequest {

    @NotNull(message = "{chat.conversationId.required}")
    private Long conversationId;

    @Size(max = Message.TEXT_MAX, message = "{chat.text.size}")
    private String text;

    @Size(max = 64, message = "{chat.clientId.size}")
    private String clientId;

    /** Javob berilayotgan xabar; shu suhbatdan bo'lishi shart. */
    private Long replyToId;

    /** {@code POST /api/chat/upload} javoblari, o'zgarishsiz. */
    @Valid
    @Size(max = MessageAttachment.MAX_PER_MESSAGE,
        message = "{chat.attachment.tooMany}")
    private List<ChatAttachmentRequest> attachments;
}

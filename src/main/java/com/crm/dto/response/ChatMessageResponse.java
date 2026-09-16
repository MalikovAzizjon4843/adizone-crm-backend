package com.crm.dto.response;

import com.crm.entity.enums.MessageType;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bitta xabar — REST lentasida ham, {@code /topic/conversation.{id}} ga
 * yuborilganda ham shu shakl.
 *
 * <p>{@code type} — hodisa turi ({@code "MESSAGE"}), xabar turi emas:
 * bitta topikka o'qilganlik hodisalari ham keladi, frontend ikkalasini
 * shu maydon bo'yicha ajratadi. Xabarning o'z turi {@code messageType} da.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageResponse {

    /** Hodisa turi — doim {@code "MESSAGE"}. */
    public static final String EVENT_TYPE = "MESSAGE";

    @Builder.Default
    private String type = EVENT_TYPE;

    private Long id;
    private UUID uuid;
    private Long conversationId;
    private Long senderId;
    private String senderName;
    private String senderPhotoUrl;
    private String text;
    private MessageType messageType;
    private Long replyToId;

    /** Yuboruvchining vaqtinchalik id si — faqat unga qaytadi, bazada yo'q. */
    private String clientId;

    private LocalDateTime createdAt;
    private LocalDateTime editedAt;
}

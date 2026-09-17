package com.crm.dto.response;

import com.crm.entity.enums.MessageType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * Qaysi suhbatdan topilgani — faqat umumiy qidiruv javobida.
     *
     * <p>DIRECT da suhbatdosh ismi, GROUP da guruh nomi. Lentadagi va
     * tarqatiladigan xabarlarda null bo'ladi, shuning uchun NON_NULL:
     * har bir xabar paketiga bo'sh maydon qo'shilmasin.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String conversationTitle;

    private Long senderId;
    private String senderName;
    private String senderPhotoUrl;
    private String text;
    private MessageType messageType;
    private Long replyToId;

    /** Yuboruvchining vaqtinchalik id si — faqat unga qaytadi, bazada yo'q. */
    private String clientId;

    /**
     * Biriktirilgan fayllar, {@code sortOrder} bo'yicha. O'chirilgan
     * xabarda {@code null}.
     */
    private List<ChatAttachmentResponse> attachments;

    /** Javob berilgan xabarning iqtibosi; javob bo'lmasa null. */
    private ChatReplyPreviewResponse replyTo;

    private LocalDateTime createdAt;
    private LocalDateTime editedAt;

    /**
     * To'ldirilgan bo'lsa xabar o'chirilgan: {@code text} va
     * {@code attachments} null keladi, xabarning o'zi esa lentada
     * qoladi — frontend o'rniga "Xabar o'chirildi" chizadi.
     */
    private LocalDateTime deletedAt;
}

package com.crm.dto.response;

import lombok.*;

/**
 * Xabar o'chirilgani haqidagi hodisa.
 *
 * <p>Xabarning o'zi lentadan yo'qolmaydi — frontend uning o'rniga
 * "Xabar o'chirildi" chizadi. {@code deletedBy} kerak: admin o'chirgan
 * bo'lsa buni ko'rsatish mumkin.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDeletedResponse {

    public static final String EVENT_TYPE = "DELETED";

    @Builder.Default
    private String type = EVENT_TYPE;

    private Long conversationId;
    private Long messageId;
    private Long deletedBy;
}

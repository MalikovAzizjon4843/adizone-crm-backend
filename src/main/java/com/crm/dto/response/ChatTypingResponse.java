package com.crm.dto.response;

import lombok.*;

/**
 * "Yozmoqda" hodisasi. Bazaga yozilmaydi va tarixda qolmaydi —
 * faqat shu daqiqada ulangan ishtirokchilarga yetadi.
 *
 * <p>Server taymer yuritmaydi: belgini frontend uch soniyadan keyin
 * o'zi o'chiradi, shuning uchun "to'xtadi" hodisasi kechiksa ham
 * ekranda "yozmoqda" osilib qolmaydi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatTypingResponse {

    public static final String EVENT_TYPE = "TYPING";

    @Builder.Default
    private String type = EVENT_TYPE;

    private Long conversationId;
    private Long userId;
    private String userName;
    private boolean typing;
}

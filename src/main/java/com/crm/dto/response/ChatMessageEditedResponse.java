package com.crm.dto.response;

import lombok.*;

import java.time.LocalDateTime;

/** Xabar tahrirlangani haqidagi hodisa. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEditedResponse {

    public static final String EVENT_TYPE = "EDITED";

    @Builder.Default
    private String type = EVENT_TYPE;

    /** Hodisa qaysi topikka ketishini shu maydon hal qiladi. */
    private Long conversationId;

    private Long messageId;
    private String text;
    private LocalDateTime editedAt;
}

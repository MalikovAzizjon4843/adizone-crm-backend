package com.crm.dto.response;

import lombok.*;

/**
 * O'qilganlik hodisasi: {@code {"type":"READ","userId":…,"messageId":…}}.
 * DIRECT suhbatda frontend shundan ✓✓ chizadi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatReadReceiptResponse {

    public static final String EVENT_TYPE = "READ";

    @Builder.Default
    private String type = EVENT_TYPE;

    private Long conversationId;
    private Long userId;
    private Long messageId;
}

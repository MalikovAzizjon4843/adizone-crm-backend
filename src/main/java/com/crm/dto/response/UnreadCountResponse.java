package com.crm.dto.response;

import lombok.*;

/** {@code GET /api/chat/unread-count} javobi: {@code { "count": 7 }}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnreadCountResponse {
    private long count;
}

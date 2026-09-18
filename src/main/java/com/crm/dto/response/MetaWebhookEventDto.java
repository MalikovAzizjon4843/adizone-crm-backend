package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Webhook eventi - diagnostika ro'yxati uchun.
 *
 * <p>{@code rawPayload} ataylab QO'SHILMAGAN: u kilobaytlab joy egallaydi
 * va ro'yxat so'roviga kerak emas. Kerak bo'lsa bazadan o'qiladi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaWebhookEventDto {

    private Long id;
    private String leadgenId;
    private String formId;
    private String formName;
    private String pageId;
    private String adId;
    private Long createdTimeMs;
    private String status;
    private Integer attempts;
    private String errorMessage;
    private Long leadId;
    private Instant receivedAt;
    private Instant processedAt;
}

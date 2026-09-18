package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** {@code GET /api/meta/status} - integratsiya sog'ligi bir qarashda. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaStatusDto {

    private boolean enabled;
    private String apiVersion;
    private String pageId;

    /**
     * Sozlamalarda sir bor-yo'qligi. Qiymatning O'ZI hech qachon
     * qaytarilmaydi - token javobga tushsa, u brauzer tarixiga,
     * proxy loglariga va ekran suratlariga ham tushadi.
     */
    private boolean systemTokenConfigured;
    private boolean appSecretConfigured;
    private boolean verifyTokenConfigured;
    private boolean taskAssigneeConfigured;

    /**
     * Webhook imzosi haqiqatan tekshirilayotganmi.
     *
     * <p>{@code appSecretConfigured} dan alohida: sir bo'lsa ham
     * {@code meta.verify-signature: false} bilan tekshiruv o'chirilgan
     * bo'lishi mumkin. Bu maydon "hozir nima bo'lyapti" degan savolga
     * javob beradi va sozlash sahifasida qizil belgi sifatida
     * ko'rsatilishi kerak.
     */
    private boolean signatureVerified;

    /** Sahifa tokeni keshda va muddati tugamagan. */
    private boolean pageTokenCached;

    private Instant lastSyncedAt;

    private long formsTotal;
    private long formsUnmapped;

    private long eventsPending;
    private long eventsFailed;
    private long eventsProcessed;
    private long eventsSkipped;
}

package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Meta webhook yuborgan bitta {@code leadgen} xabari.
 *
 * <p>Webhook so'rovi FAQAT shu qatorni yozadi va darhol 200 qaytaradi:
 * Meta javobni 20 soniya kutadi va kechikkan har bir yetkazib berishni
 * qayta urinishga, keyin esa obunani o'chirishga olib keladi. Graph API
 * so'rovi va lid yaratish keyinroq, {@code MetaLeadProcessingService}
 * siklida bo'ladi.
 *
 * <p><b>{@code leadgenId} UNIQUE — idempotentlikning yagona kafolati.</b>
 * Meta bir xil {@code leadgen_id} ni bir necha marta yuboradi (qayta
 * urinish, bir nechta obuna, tarmoq uzilishi). Takroriy INSERT bazada
 * rad etiladi va kod uni jimgina o'tkazib yuboradi.
 */
@Entity
@Table(name = "meta_webhook_events", indexes = {
    @Index(name = "idx_meta_webhook_events_status", columnList = "status, attempts"),
    @Index(name = "idx_meta_webhook_events_form", columnList = "form_id"),
    @Index(name = "idx_meta_webhook_events_received", columnList = "received_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaWebhookEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** Shuncha urinishdan keyin event FAILED bo'ladi va qo'lda retry kutadi. */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "leadgen_id", nullable = false, unique = true, length = 64)
    private String leadgenId;

    @Column(name = "form_id", length = 64)
    private String formId;

    @Column(name = "page_id", length = 64)
    private String pageId;

    /** Meta webhook da {@code adgroup_id} deb keladi. */
    @Column(name = "ad_id", length = 64)
    private String adId;

    /** Meta bergan unix vaqt (soniya) — millisekundga aylantirilgan. */
    @Column(name = "created_time_ms")
    private Long createdTimeMs;

    /** Webhook yuborgan xom JSON — hech qachon o'zgartirilmaydi. */
    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Yaratilgan (yoki topilgan) lid. JPA bog'lanishi ATAYLAB yo'q: lid
     * o'chirilsa ham event tarixi qolishi kerak, FK esa o'chirishni
     * bloklardi.
     */
    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    protected void onCreate() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (status == null) {
            status = STATUS_PENDING;
        }
        if (attempts == null) {
            attempts = 0;
        }
    }
}

package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Telefon qo'ng'irog'i (V90 {@code calls}, docs/design/calls.md §2). Ilova sinxron qiladi; {@code (deviceId,
 * deviceCallKey)} UNIQUE — qayta yuborishda dublikat yo'q. {@link #contactType} — sinxron paytidagi moslashuv:
 * LEAD → {@link #leadId}; STUDENT → {@link #studentId}; PARENT → {@link #studentId} (farzandi) va ixtiyoriy
 * {@link #parentId}; UNKNOWN — hech biri (bazada CHECK).
 */
@Entity
@Table(name = "calls",
    uniqueConstraints = @UniqueConstraint(name = "ux_calls_device_key", columnNames = {"device_id", "device_call_key"}),
    indexes = {
        @Index(name = "idx_calls_user_started", columnList = "user_id, started_at"),
        @Index(name = "idx_calls_phone_started", columnList = "phone_normalized, started_at")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhoneCall {

    public enum Direction {
        IN, OUT, MISSED, REJECTED;

        /** Kiruvchi tomondagi javobsiz (o'tkazib yuborilgan yoki rad etilgan). */
        public boolean unanswered() {
            return this == MISSED || this == REJECTED;
        }
    }

    public enum ContactType { LEAD, STUDENT, PARENT, UNKNOWN }

    public enum ResultCode { TALKED, NO_ANSWER, CALLBACK, THINKING, TRIAL_BOOKED, REJECTED, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private Direction direction;

    @Column(name = "phone_raw", nullable = false, length = 40)
    private String phoneRaw;

    @Column(name = "phone_normalized", length = 20)
    private String phoneNormalized;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "duration_sec", nullable = false)
    private int durationSec;

    @Column(name = "device_call_key", nullable = false, length = 100)
    private String deviceCallKey;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 10)
    @Builder.Default
    private ContactType contactType = ContactType.UNKNOWN;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "auto_lead_created", nullable = false)
    private boolean autoLeadCreated;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "result_code", length = 20)
    private ResultCode resultCode;

    @Column(name = "note", length = 2000)
    private String note;

    @Column(name = "result_by")
    private Long resultBy;

    @Column(name = "result_at")
    private LocalDateTime resultAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

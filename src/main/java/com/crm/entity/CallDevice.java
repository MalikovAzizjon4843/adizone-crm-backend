package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * "Adizone Qo'ng'iroqlar" ilovasi o'rnatilgan telefon (V90, docs/design/calls.md §3). Qurilma tokeni bir marta
 * ko'rsatiladi, bazada faqat SHA-256 ({@link #tokenHash}). Bitta xodimda va bitta {@link #deviceUid} da bittadan
 * ortiq faol ({@code revokedAt == null}) qurilma yo'q — prod'da qisman UNIQUE indekslar, kodda {@code CallDeviceService}.
 */
@Entity
@Table(name = "call_devices",
    uniqueConstraints = @UniqueConstraint(name = "ux_call_devices_token_hash", columnNames = "token_hash"),
    indexes = @Index(name = "idx_call_devices_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_uid", nullable = false, length = 100)
    private String deviceUid;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "android_version", length = 30)
    private String androidVersion;

    @Column(name = "app_version", length = 30)
    private String appVersion;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "fcm_token", length = 512)
    private String fcmToken;

    @Column(name = "perm_call_log")
    private Boolean permCallLog;

    @Column(name = "perm_recordings")
    private Boolean permRecordings;

    @Column(name = "perm_battery_opt")
    private Boolean permBatteryOpt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    /**
     * V91: qaysi qo'ng'iroqlar sinxron qilinadi. {@code KNOWN_ONLY} — CRM'da lid / o'quvchi / ota-onaga mos
     * kelmagani saqlanmaydi va avto-lid ochilmaydi (shaxsiy telefon). NULL (V91 dan oldingi qator) — {@code ALL}.
     */
    @Enumerated(value = EnumType.STRING)
    @Column(name = "sync_mode", length = 12)
    private SyncMode syncMode;

    /** V91: bundan oldingi qo'ng'iroq sinxron qilinmaydi (Toshkent vaqti). NULL — chegara yo'q (eski qurilmalar). */
    @Column(name = "sync_from")
    private LocalDateTime syncFrom;

    public enum SyncMode { ALL, KNOWN_ONLY }

    public boolean isActive() {
        return revokedAt == null;
    }

    public SyncMode effectiveSyncMode() {
        return syncMode != null ? syncMode : SyncMode.ALL;
    }
}

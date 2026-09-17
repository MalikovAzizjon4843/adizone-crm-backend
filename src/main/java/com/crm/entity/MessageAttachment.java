package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Xabarga biriktirilgan fayl yoki rasm.
 *
 * <p>Bitta xabarda bir nechtasi bo'lishi mumkin — rasmlar galereyasi
 * bitta xabar bo'lib ko'rinadi, o'nta alohida xabar emas. {@code sortOrder}
 * ularning ekrandagi tartibini saqlaydi.
 *
 * <p>Fayl mazmuni bu yerda emas: u {@code FileStorageService} orqali
 * diskka yoziladi, jadvalda faqat {@code fileUrl} qoladi. Xabar yumshoq
 * o'chirilganda biriktirmalar javobda berilmaydi, lekin qatorlar ham,
 * fayllar ham joyida turadi — tiklash uchun.
 *
 * <p>{@code Message} tomonida {@code @OneToMany} ataylab yo'q: lenta
 * sahifasi biriktirmalarni bitta {@code IN} so'rovi bilan oladi, aks
 * holda har bir xabar uchun alohida so'rov ketardi.
 */
@Entity
@Table(name = "message_attachments", indexes = {
    @Index(name = "idx_message_attachments_message", columnList = "message_id, sort_order")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageAttachment {

    /** Bitta xabarga biriktirilishi mumkin bo'lgan fayllar soni. */
    public static final int MAX_PER_MESSAGE = 10;

    /** Ovozli xabarning eng uzun davomiyligi — besh daqiqa. */
    public static final int VOICE_MAX_DURATION_MS = 5 * 60 * 1000;

    /** To'lqin shaklidagi ustunlar soni va ustun balandligining chegarasi. */
    public static final int WAVEFORM_MAX_POINTS = 50;
    public static final int WAVEFORM_MAX_VALUE = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    /** Backend bergan yo'l, masalan {@code /api/files/<uuid>.png}. */
    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    /** Foydalanuvchi ko'radigan nom — diskdagi nom emas. */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "content_type", length = 100)
    private String contentType;

    /** Faqat rasmlar uchun; frontend yuklashdan oldin joy ajratadi. */
    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    /** Ovoz uzunligi millisekundda; faqat {@code audio/*} da. */
    @Column(name = "duration_ms")
    private Integer durationMs;

    /**
     * To'lqin shakli: vergul bilan ajratilgan {@code 0..100} sonlar,
     * ko'pi bilan {@link #WAVEFORM_MAX_POINTS} ta.
     *
     * <p>Matn sifatida saqlanadi, alohida jadval emas: bu — chizish
     * uchun qaraladigan surat, so'rov solinadigan ma'lumot emas.
     * Uni brauzer yozib beradi; server faqat shaklini tekshiradi.
     */
    @Column(name = "waveform", length = 500)
    private String waveform;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

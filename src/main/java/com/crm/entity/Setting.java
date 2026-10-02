package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Markaz sozlamasi (kalit → matn) — legacy {@code settings} jadvali qayta ishlatiladi
 * (leaves-exams-contracts §5.1). Rekvizitlar {@code center.*} kalitlarida; eski
 * {@code school_name}, {@code currency} ... kalitlariga kod tegmaydi.
 *
 * <p>{@code updated_by} — oddiy ustun (FK emas): legacy jadvaldagi turi noma'lum.
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Setting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_key", nullable = false, unique = true, length = 100)
    private String settingKey;

    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String settingValue;

    @Column(length = 255)
    private String description;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

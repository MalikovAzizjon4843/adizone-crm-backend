package com.crm.entity;

import com.crm.entity.converter.LeadStatusConverter;
import com.crm.entity.enums.LeadStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Lid bosqichlari tarixi — {@code StudentStatusHistory} naqshi bo'yicha.
 *
 * <p>Ikki narsa uchun kerak: (1) lid kartasidagi lenta "New leads dan
 * Retarget ga o'tdi" ko'rinishida ko'rsatilsin, (2) bosqichda o'rtacha necha
 * kun turgani hisoblansin. Ikkinchisi voronka dinamik bo'lishidan OLDIN
 * yig'ila boshlashi kerak — aks holda o'tishga qadar tarix bo'lmaydi.
 *
 * <p>{@code fromStatus} birinchi o'zgarishda ham to'ldiriladi (NEW dan),
 * lekin bazadagi eski lidlar uchun tarix yo'q — frontend bo'sh ro'yxatni
 * "tarix yuritilmagan" deb ko'rsatishi kerak.
 */
@Entity
@Table(name = "lead_status_history", indexes = {
    @Index(name = "idx_lead_status_history_lead", columnList = "lead_id, changed_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    @Convert(converter = LeadStatusConverter.class)
    @Column(name = "from_status", length = 30)
    private LeadStatus fromStatus;

    @Convert(converter = LeadStatusConverter.class)
    @Column(name = "to_status", length = 30, nullable = false)
    private LeadStatus toStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by")
    private User changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    /** Ixtiyoriy izoh — hozircha tizim yozuvlari uchun (masalan konvert). */
    @Column(columnDefinition = "TEXT")
    private String note;

    @PrePersist
    protected void onCreate() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }
}

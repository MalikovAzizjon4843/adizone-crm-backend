package com.crm.entity;

import com.crm.entity.converter.StageKindConverter;
import com.crm.entity.enums.StageKind;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/**
 * Lid voronkasining bosqichi. Bitta voronka — alohida {@code pipeline}
 * jadvali ataylab yo'q, chunki o'quv markaziga ikkinchi voronka kerak emas.
 *
 * <p><b>{@code code} o'zgarmas.</b> Yaratilgandan keyin hech qachon
 * tahrirlanmaydi: {@code leads.status}, {@code lead_status_history} va
 * {@code lead_comments.status_at_comment} aynan shu matnni saqlaydi.
 * Nomni o'zgartirish uchun {@code nameUz}/{@code nameRu}/{@code nameEn} bor.
 *
 * <p><b>{@code kind} — kod tayanadigan yagona belgi.</b> Konvert va rad
 * etish mantiqi bosqich nomiga emas, shu maydonga qaraydi.
 *
 * <p>{@code Lead.status}, {@code lead_status_history} va
 * {@code lead_comments.status_at_comment} shu {@code code} ni matn sifatida
 * saqlaydi — ular orasida JPA bog'lanishi yo'q, bog'lovchi faqat matn.
 */
@Entity
@Table(name = "lead_stages", indexes = {
    @Index(name = "idx_lead_stages_sort", columnList = "sort_order")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadStage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    /** Texnik kalit — yaratilgandan keyin o'zgarmaydi. */
    @Column(nullable = false, unique = true, length = 50, updatable = false)
    private String code;

    @Column(name = "name_uz", nullable = false, length = 100)
    private String nameUz;

    @Column(name = "name_ru", nullable = false, length = 100)
    private String nameRu;

    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    /** secondary | info | warning | success | danger */
    @Column(nullable = false, length = 20)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Convert(converter = StageKindConverter.class)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StageKind kind = StageKind.OPEN;

    /**
     * Shu bosqichga o'tishda summa MAJBURIY bo'ladimi.
     *
     * <p>Avval bu qoida kodda {@code Set.of("ONLINE_PAID", "OFFLINE_PAID")}
     * bo'lib turardi va buyurtmachi yangi to'lov bosqichi qo'shsa u ro'yxatga
     * tushmasdi. Endi bayroq bosqichning o'zida — sozlash sahifasidan
     * belgilanadi.
     *
     * <p>Shu bayroqli bosqichga o'tganda avtomatik "to'lov qabul qilindi"
     * izohi ham yoziladi.
     */
    @Column(name = "requires_amount", nullable = false)
    @Builder.Default
    private Boolean requiresAmount = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @PrePersist
    protected void onCreate() {
        if (kind == null) {
            kind = StageKind.OPEN;
        }
        if (isActive == null) {
            isActive = true;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }
}

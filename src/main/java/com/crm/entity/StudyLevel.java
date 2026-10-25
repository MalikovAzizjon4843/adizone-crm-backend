package com.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * O'quv darajasi (docs/design/cash-and-books.md §2.1, V87) — sozlanadigan, tilga bog'liq: "Turk A1", "Turk A2" …
 * Guruhga belgilanadi ({@code groups.level_id}; kursdagi — yangi guruh uchun standart). Har darajaga bitta bepul
 * beriladigan o'quv kitobi ({@code giftBookItemId}). (til, kod) — katta-kichik harfsiz UNIQUE (V87 indeksi).
 */
@Entity
@Table(name = "study_levels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudyLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "language", nullable = false, length = 50)
    private String language;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Shu darajaga bepul beriladigan o'quv kitobi ({@code book_items.id}, kind = STUDY, shu daraja). */
    @Column(name = "gift_book_item_id")
    private Long giftBookItemId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

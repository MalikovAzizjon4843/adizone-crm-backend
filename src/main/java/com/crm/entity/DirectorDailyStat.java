package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Kunlik dashboard snapshot'i (director-dashboard §3.6, G11): bo'lim DTO si JSON matn
 * sifatida. 20:00 — {@code final = false}, 23:55 — {@code final = true}; oxirgi 7 kun har
 * kecha qayta hisoblanadi ({@code version++}), {@code debtors} bundan mustasno.
 */
@Entity
@Table(name = "director_daily_stats")
@IdClass(DirectorDailyStat.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DirectorDailyStat {

    @Id
    @Column(name = "stat_date")
    private LocalDate statDate;

    @Id
    @Column(name = "section", length = 30)
    private String section;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @Column(name = "is_final", nullable = false)
    private boolean finalized;

    @Column(name = "version", nullable = false)
    private int version;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private LocalDate statDate;
        private String section;
    }
}

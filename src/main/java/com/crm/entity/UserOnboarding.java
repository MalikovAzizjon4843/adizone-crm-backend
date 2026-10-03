package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Xodim ko'rgan onboarding turlari (CRM frontend "tour"lari) — bir xodim + bir kalit = bitta qator (V67).
 * Kalit formati {@code UserOnboardingService.KEY_PATTERN}; bazada ham CHECK bor.
 */
@Entity
@Table(name = "user_onboarding")
@IdClass(UserOnboarding.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserOnboarding {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "tour_key", nullable = false, length = 80)
    private String tourKey;

    @Column(name = "seen_at", nullable = false)
    private LocalDateTime seenAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long userId;
        private String tourKey;
    }
}

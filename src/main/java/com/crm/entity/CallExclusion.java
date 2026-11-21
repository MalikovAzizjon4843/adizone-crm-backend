package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Xodimning shaxsiy raqami — bu raqam bilan qo'ng'iroqlar sinxron qilinmaydi (V90). */
@Entity
@Table(name = "call_exclusions",
    uniqueConstraints = @UniqueConstraint(name = "ux_call_exclusions_user_phone",
        columnNames = {"user_id", "phone_normalized"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "phone_normalized", nullable = false, length = 20)
    private String phoneNormalized;

    @Column(name = "label", length = 100)
    private String label;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Kunlik Telegram xulosasi jurnali — bir kunga bir chat'ga bitta xabar (director-dashboard §5). */
@Entity
@Table(name = "director_digest_log",
    uniqueConstraints = @UniqueConstraint(name = "uk_director_digest_log", columnNames = {"stat_date", "chat_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DirectorDigestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "chat_id", nullable = false, length = 64)
    private String chatId;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(nullable = false)
    private boolean ok;

    @Column(columnDefinition = "TEXT")
    private String error;
}

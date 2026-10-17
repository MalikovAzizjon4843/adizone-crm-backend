package com.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Mini App: e'lon o'qilganligi (V85) — {@code notice_reads} xodimlar ({@code users}) uchun, bu jadval
 * {@code AppIdentity} uchun. E'lon yoki identity o'chirilsa — CASCADE (PostgreSQL FK, V85); kod ham o'zi tozalaydi.
 */
@Entity
@Table(name = "app_notice_reads",
    uniqueConstraints = @UniqueConstraint(name = "ux_app_notice_reads_notice_identity",
        columnNames = {"notice_id", "identity_id"}),
    indexes = @Index(name = "idx_app_notice_reads_identity", columnList = "identity_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppNoticeRead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notice_id", nullable = false)
    private Long noticeId;

    @Column(name = "identity_id", nullable = false)
    private Long identityId;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;
}

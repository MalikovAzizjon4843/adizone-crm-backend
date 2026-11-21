package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Qo'ng'iroq yozuvi (V90). {@link #path} — {@code app.calls.recording-dir} ga nisbatan; retention faylni o'chirganda
 * {@code path = null}, {@link #deletedAt} to'ldiriladi, qolgan metama'lumot saqlanadi.
 */
@Entity
@Table(name = "call_recordings",
    uniqueConstraints = @UniqueConstraint(name = "ux_call_recordings_call", columnNames = "call_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallRecording {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_id", nullable = false)
    private Long callId;

    @Column(name = "path", length = 500)
    private String path;

    @Column(name = "mime", nullable = false, length = 100)
    private String mime;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isAvailable() {
        return path != null && deletedAt == null;
    }
}

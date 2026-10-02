package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Guruh darsining istisnosi (director-dashboard §3.5, G8): CANCELLED — o'sha kuni dars yo'q,
 * MOVED — {@code movedTo} kuniga ko'chdi, EXTRA — jadvalda yo'q qo'shimcha dars.
 */
@Entity
@Table(name = "lesson_exceptions",
    uniqueConstraints = @UniqueConstraint(name = "uk_lesson_exceptions",
        columnNames = {"group_id", "lesson_date", "kind"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonException {

    public enum Kind { CANCELLED, MOVED, EXTRA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "lesson_date", nullable = false)
    private LocalDate lessonDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "moved_to")
    private LocalDate movedTo;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

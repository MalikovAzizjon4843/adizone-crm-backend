package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * O'quvchi / ota-onaning dars kuniga sabab bildirishi (docs/design/telegram-platform.md §11.2) — "kelmayman",
 * "kechikaman" yoki boshqa. Davomatni o'zgartirmaydi: o'qituvchi uni davomat ekranida ko'radi va o'zi belgilaydi.
 * Bitta o'quvchi + guruh + sana uchun bitta faol (V69 qisman UNIQUE).
 */
@Entity
@Table(name = "absence_notices", indexes = {
    @Index(name = "idx_absence_notices_group_date", columnList = "group_id, lesson_date"),
    @Index(name = "idx_absence_notices_identity", columnList = "identity_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AbsenceNotice {

    public enum Type { ABSENT, LATE, OTHER }

    public enum Status { ACTIVE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kim yubordi — Mini App identity'si. */
    @Column(name = "identity_id", nullable = false)
    private Long identityId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "lesson_date", nullable = false)
    private LocalDate lessonDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    @Column(length = 500)
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    /** Yuborilgan paytdagi bog'lanish: SELF (o'quvchi o'zi) yoki PARENT. */
    @Column(name = "submitted_as", length = 10)
    private String submittedAs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
}

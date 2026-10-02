package com.crm.entity;

import com.crm.entity.enums.SubstitutionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Guruhning muayyan darsini guruh o'qituvchisi emas, boshqa o'qituvchi o'tdi (leaves-exams-contracts §2,
 * buyurtmachi qarori): o'rinbosar majburiy emas — o'qituvchilar o'zaro kelishadi, SA/A esa "shu guruhning
 * shu sanadagi darsini X o'tdi" deb belgilaydi. O'rinbosar shu dars davomatini belgilay oladi va har
 * o'tilgan (CONDUCTED) dars uchun {@code SalaryRule.substituteLessonRate} oladi; asosiy o'qituvchidan
 * ayirilmaydi. O'quvchi to'lovi va {@code LESSON_CHARGE.teacher_id} o'zgarmaydi (D6).
 *
 * <p>Bir darsga bitta faol belgi — V61 qisman UNIQUE {@code (group_id, lesson_date) WHERE status <> 'CANCELLED'}.
 */
@Entity
@Table(name = "lesson_substitutions", indexes = {
    @Index(name = "idx_lesson_substitutions_substitute", columnList = "substitute_teacher_id, lesson_date"),
    @Index(name = "idx_lesson_substitutions_original", columnList = "original_teacher_id, lesson_date"),
    @Index(name = "idx_lesson_substitutions_leave", columnList = "leave_request_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonSubstitution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @Column(name = "lesson_date", nullable = false)
    private LocalDate lessonDate;

    /** Belgilash paytidagi guruh o'qituvchisi (snapshot). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_teacher_id", nullable = false)
    private Teacher originalTeacher;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "substitute_teacher_id", nullable = false)
    private Teacher substituteTeacher;

    /** Ta'tildan kelib chiqqan bo'lsa (ixtiyoriy). */
    @Column(name = "leave_request_id")
    private Long leaveRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubstitutionStatus status = SubstitutionStatus.PLANNED;

    @Column(name = "conducted_at")
    private LocalDateTime conductedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conducted_by")
    private User conductedBy;

    @Column(length = 500)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
}

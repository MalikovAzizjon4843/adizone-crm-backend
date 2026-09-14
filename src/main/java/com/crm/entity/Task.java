package com.crm.entity;

import com.crm.entity.converter.TaskStatusConverter;
import com.crm.entity.converter.TaskTypeConverter;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.TaskType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Operator vazifasi (zadacha). Lidga YOKI o'quvchiga bog'lanadi, yoki
 * ikkalasiga ham emas — mustaqil vazifa ("bugun hisobotni yuboraman").
 * Taqiqlanadigan yagona holat — ikkalasi birga to'ldirilishi.
 *
 * <p>Bu cheklov {@code TaskCreateRequest} dagi {@code @AssertTrue} da va
 * {@code TaskService.create()} da tekshiriladi, bazada emas:
 * {@code EnumCheckConstraintCleaner} CHECK constraintlarni tozalab yuboradi.
 *
 * <p><b>{@code allDay} va {@code dueAt}:</b> "kun davomida" vazifa uchun
 * {@code dueAt} shu kunning 23:59 ga normallashtiriladi
 * ({@code TaskService.normalizeDueAt}). Shu sababli barcha so'rovlar va
 * "muddati o'tdi" tekshiruvi yagona {@code due_at} ustuni bilan ishlaydi —
 * hech qayerda {@code allDay} uchun alohida shart yozilmaydi.
 */
@Entity
@Table(name = "tasks", indexes = {
    @Index(name = "idx_tasks_assigned_due", columnList = "assigned_to, status, due_at"),
    @Index(name = "idx_tasks_lead", columnList = "lead_id, status, due_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Convert(converter = TaskTypeConverter.class)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TaskType type = TaskType.CALL;

    @Convert(converter = TaskStatusConverter.class)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TaskStatus status = TaskStatus.OPEN;

    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

    /** true bo'lsa frontend vaqtni ko'rsatmaydi, faqat sanani. */
    @Column(name = "all_day", nullable = false)
    @Builder.Default
    private Boolean allDay = false;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_to", nullable = false)
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private Lead lead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by")
    private User completedBy;

    /** amoCRM "Rezultat vypolneniya" — DONE uchun majburiy. */
    @Column(columnDefinition = "TEXT")
    private String result;

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = TaskStatus.OPEN;
        }
        if (type == null) {
            type = TaskType.CALL;
        }
        if (allDay == null) {
            allDay = false;
        }
    }
}

package com.crm.entity;

import com.crm.entity.converter.LeaveStatusConverter;
import com.crm.entity.converter.LeaveTypeConverter;
import com.crm.entity.enums.LeaveStatus;
import com.crm.entity.enums.LeaveType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Xodim ta'tili (leaves-exams-contracts §1). {@code user} — kim ta'tilda (D1: barcha xodimlar),
 * {@code teacher} — o'qituvchi bo'lsa avtomatik (o'rinbosar va dashboard uchun), {@code requester} —
 * arizani kim berdi (o'zi yoki SA/A). Haqli/haqsiz — tasdiqlashda ({@code paid}, D2).
 *
 * <p>Eski {@code approved_by/approved_at} ustunlari bazada qoladi (V61 ularni {@code decided_*} ga ko'chiradi).
 */
@Entity
@Table(name = "leave_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Leave extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    /** Kim ta'tilda. V61 backfill'dan keyin NOT NULL (ddl-auto mavjud qatorlarga NOT NULL qo'sha olmaydi). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id")
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    @Convert(converter = LeaveTypeConverter.class)
    @Column(name = "leave_type", nullable = false, length = 30)
    private LeaveType leaveType;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /** Faqat ariza beruvchining sababi (rad etish izohi bu yerga qo'shilmaydi — L-08). */
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Convert(converter = LeaveStatusConverter.class)
    @Column(length = 20)
    @Builder.Default
    private LeaveStatus status = LeaveStatus.PENDING;

    /** PENDING/REJECTED/CANCELLED da NULL; APPROVED da majburiy (V61 CHECK). */
    @Column(name = "paid")
    private Boolean paid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
}

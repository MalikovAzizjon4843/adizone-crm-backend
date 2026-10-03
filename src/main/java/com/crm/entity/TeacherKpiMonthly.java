package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * O'qituvchi KPI ning oy yakunidagi snapshot'i (V72). Har oyning 1-kuni 01:00 da o'tgan oy
 * yoziladi ({@code TeacherKpiSnapshotService}); yopilgan oy KPI si shu yerdan o'qiladi.
 * Xom sonlar ham saqlanadi — foiz qanday chiqqani ko'rinsin, qayta hisoblash taqqoslansin.
 * {@code (teacher_id, month_start)} UNIQUE — V72 indeksi.
 */
@Entity
@Table(name = "teacher_kpi_monthly", indexes = {
    @Index(name = "idx_teacher_kpi_monthly_month", columnList = "month_start")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeacherKpiMonthly {

    public static final String SOURCE_JOB = "JOB";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    /** Oyning 1-kuni. */
    @Column(name = "month_start", nullable = false)
    private LocalDate monthStart;

    @Column(name = "attendance_present", nullable = false)
    private Integer attendancePresent;

    @Column(name = "attendance_total", nullable = false)
    private Integer attendanceTotal;

    /** Muddati shu oyda kelgan, natijasi ma'lum davrlar (to'langan yoki grace tugagan). */
    @Column(name = "periods_decided", nullable = false)
    private Integer periodsDecided;

    @Column(name = "periods_paid", nullable = false)
    private Integer periodsPaid;

    @Column(name = "periods_on_time", nullable = false)
    private Integer periodsOnTime;

    /** Hisoblash paytida hali grace ichida va to'lanmagan — maxrajga kirmaydi. */
    @Column(name = "periods_pending", nullable = false)
    private Integer periodsPending;

    @Column(name = "open_at_end", nullable = false)
    private Integer openAtEnd;

    @Column(name = "graduated", nullable = false)
    private Integer graduated;

    @Column(name = "churned", nullable = false)
    private Integer churned;

    @Column(name = "attendance_rate")
    private Double attendanceRate;

    @Column(name = "payment_rate")
    private Double paymentRate;

    @Column(name = "on_time_payment_rate")
    private Double onTimePaymentRate;

    @Column(name = "retention_rate")
    private Double retentionRate;

    @Column(name = "overall_score")
    private Double overallScore;

    @Column(name = "insufficient_data", nullable = false)
    private Boolean insufficientData;

    @Column(name = "group_count", nullable = false)
    private Integer groupCount;

    @Column(name = "student_count", nullable = false)
    private Integer studentCount;

    /** JOB | MANUAL */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}

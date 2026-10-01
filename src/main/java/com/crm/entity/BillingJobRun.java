package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Billing job'ining har bir ishga tushishi — {@code billing_job_runs} (§3.7, §10.1).
 * Admin {@code GET /api/admin/billing/job-runs} orqali ko'radi: nechta SG,
 * nechta davr yozildi, qaysilari yiqildi.
 */
@Entity
@Table(name = "billing_job_runs", indexes = @Index(name = "idx_billing_job_runs_started", columnList = "started_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingJobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ACCRUAL va h.k. */
    @Column(name = "job_name", length = 40, nullable = false)
    private String jobName;

    /** SCHEDULED / STARTUP / ADMIN. */
    @Column(name = "trigger_source", length = 20, nullable = false)
    private String triggerSource;

    /** Qaysi "bugun" uchun hisoblandi (Toshkent). */
    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    /** RUNNING / OK / PARTIAL / FAILED. */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "candidates")
    private Integer candidates;

    @Column(name = "processed")
    private Integer processed;

    @Column(name = "periods_created")
    private Integer periodsCreated;

    @Column(name = "failed")
    private Integer failed;

    /** Catch-up chegarasiga yetgan SG lar (qo'lda tekshirish, A16). */
    @Column(name = "catch_up_limited")
    private Integer catchUpLimited;

    /** Har qatorda "sgId: sabab". */
    @Column(name = "errors", columnDefinition = "TEXT")
    private String errors;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}

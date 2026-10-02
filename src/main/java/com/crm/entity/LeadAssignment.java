package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Lid tayinlash tarixi (director-dashboard §1.7, §3.1, G3/G4). {@code leads.assigned_user_id}
 * faqat oxirgisini saqlaydi; bu jadval har tayinlashni va operatorning birinchi javobini.
 */
@Entity
@Table(name = "lead_assignments", indexes = {
    @Index(name = "idx_lead_assignments_user", columnList = "user_id, assigned_at"),
    @Index(name = "idx_lead_assignments_lead", columnList = "lead_id, assigned_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadAssignment {

    public static final String KIND_STATUS = "STATUS";
    public static final String KIND_TASK = "TASK";
    public static final String KIND_COMMENT = "COMMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "unassigned_at")
    private LocalDateTime unassignedAt;

    @Column(name = "first_response_at")
    private LocalDateTime firstResponseAt;

    /** STATUS | TASK | COMMENT. */
    @Column(name = "first_response_kind", length = 20)
    private String firstResponseKind;

    /** LIVE | BACKFILL. */
    @Column(name = "source", length = 20, nullable = false)
    @Builder.Default
    private String source = "LIVE";
}

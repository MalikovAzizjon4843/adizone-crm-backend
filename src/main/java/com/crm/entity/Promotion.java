package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "promotions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Promotion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_class_id")
    private ClassEntity fromClass;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_class_id")
    private ClassEntity toClass;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_section_id")
    private Section fromSection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_section_id")
    private Section toSection;

    @Column(name = "from_academic_year", length = 20)
    private String fromAcademicYear;

    @Column(name = "to_academic_year", length = 20)
    private String toAcademicYear;

    @Column(name = "promotion_date")
    private LocalDate promotionDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promoted_by")
    private User promotedBy;

    @Column(columnDefinition = "TEXT")
    private String remarks;
}

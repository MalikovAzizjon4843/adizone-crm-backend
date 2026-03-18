package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "student_parents",
    uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "parent_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StudentParent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", nullable = false)
    private Parent parent;

    @Column(length = 20)
    private String relation;

    @Column(name = "is_primary")
    private Boolean isPrimary = false;
}

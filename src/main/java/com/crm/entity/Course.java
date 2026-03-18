package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "courses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Course extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @Column(name = "course_name", nullable = false)
    private String courseName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "duration_months", nullable = false)
    private Integer durationMonths;

    @Column(name = "lessons_count", nullable = false)
    private Integer lessonsCount;

    @Column(name = "monthly_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "is_active")
    private Boolean isActive = true;
}

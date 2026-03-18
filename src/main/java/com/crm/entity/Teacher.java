package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "teachers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Teacher extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(name = "subject_specialization")
    private String subjectSpecialization;

    @Column(name = "monthly_salary", precision = 12, scale = 2)
    private BigDecimal monthlySalary;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "teacher", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Group> groups = new ArrayList<>();
}

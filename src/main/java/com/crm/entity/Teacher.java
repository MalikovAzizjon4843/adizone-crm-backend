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

    @Column(name = "teacher_code", length = 50)
    private String teacherCode;

    @Column(length = 10)
    private String gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "marital_status", length = 20)
    private String maritalStatus;

    @Column(name = "father_name", length = 100)
    private String fatherName;

    @Column(name = "mother_name", length = 100)
    private String motherName;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "permanent_address", columnDefinition = "TEXT")
    private String permanentAddress;

    @Column(name = "passport_info", length = 50)
    private String passportInfo;

    @Column(length = 255)
    private String qualification;

    @Column(name = "work_experience", length = 100)
    private String workExperience;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    @Column(length = 20)
    private String status = "ACTIVE";

    @Column(name = "basic_salary", precision = 12, scale = 2)
    private BigDecimal basicSalary;

    @Column(name = "medical_leaves")
    private Integer medicalLeaves = 0;

    @Column(name = "casual_leaves")
    private Integer casualLeaves = 0;

    @Column(name = "maternity_leaves")
    private Integer maternityLeaves = 0;

    @Column(name = "sick_leaves")
    private Integer sickLeaves = 0;

    @OneToMany(mappedBy = "teacher", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Group> groups = new ArrayList<>();
}

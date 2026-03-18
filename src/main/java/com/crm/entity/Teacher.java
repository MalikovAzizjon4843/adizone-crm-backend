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
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
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

    // ── Core fields (existed before V5) ─────────────────────────────
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

    // ── Personal (V5) ───────────────────────────────────────────────
    @Column(name = "teacher_code", length = 50)
    private String teacherCode;

    @Column(length = 10)
    private String gender;

    @Column(name = "blood_group", length = 5)
    private String bloodGroup;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "marital_status", length = 20)
    private String maritalStatus;

    @Column(name = "father_name", length = 100)
    private String fatherName;

    @Column(name = "mother_name", length = 100)
    private String motherName;

    @Column(length = 50)
    private String religion;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "permanent_address", columnDefinition = "TEXT")
    private String permanentAddress;

    @Column(name = "pan_number", length = 50)
    private String panNumber;

    // ── Academic / Employment (V5) ───────────────────────────────────
    @Column(length = 255)
    private String qualification;

    @Column(name = "work_experience", length = 100)
    private String workExperience;

    @Column(name = "previous_school", length = 255)
    private String previousSchool;

    @Column(name = "previous_school_address", columnDefinition = "TEXT")
    private String previousSchoolAddress;

    @Column(name = "previous_school_phone", length = 20)
    private String previousSchoolPhone;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    @Column(name = "leaving_date")
    private LocalDate leavingDate;

    @Column(length = 20)
    private String status = "ACTIVE";

    // ── Payroll (V5) ────────────────────────────────────────────────
    @Column(name = "epf_number", length = 50)
    private String epfNumber;

    @Column(name = "basic_salary", precision = 12, scale = 2)
    private BigDecimal basicSalary;

    @Column(name = "contract_type", length = 50)
    private String contractType;

    @Column(name = "work_shift", length = 50)
    private String workShift;

    @Column(name = "work_location", length = 100)
    private String workLocation;

    // ── Leaves (V5) ─────────────────────────────────────────────────
    @Column(name = "medical_leaves")
    private Integer medicalLeaves = 0;

    @Column(name = "casual_leaves")
    private Integer casualLeaves = 0;

    @Column(name = "maternity_leaves")
    private Integer maternityLeaves = 0;

    @Column(name = "sick_leaves")
    private Integer sickLeaves = 0;

    // ── Bank (V5) ───────────────────────────────────────────────────
    @Column(name = "bank_account_name", length = 100)
    private String bankAccountName;

    @Column(name = "bank_account_number", length = 50)
    private String bankAccountNumber;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "ifsc_code", length = 20)
    private String ifscCode;

    @Column(name = "branch_name", length = 100)
    private String branchName;

    // ── Relationships ───────────────────────────────────────────────
    @OneToMany(mappedBy = "teacher", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Group> groups = new ArrayList<>();
}

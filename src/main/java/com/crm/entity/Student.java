package com.crm.entity;

import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "students")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Student extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    // ── Core fields (existed before V4) ─────────────────────────────
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "parent_phone", length = 20)
    private String parentPhone;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "marketing_source")
    private MarketingSource marketingSource = MarketingSource.OTHER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_student_id")
    private Student referralStudent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StudentStatus status = StudentStatus.ACTIVE;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(columnDefinition = "TEXT")
    private String address;

    // ── Academic info (V4) ──────────────────────────────────────────
    @Column(name = "admission_number", length = 50)
    private String admissionNumber;

    @Column(name = "admission_date")
    private LocalDate admissionDate;

    @Column(name = "roll_number", length = 50)
    private String rollNumber;

    @Column(name = "academic_year", length = 20)
    private String academicYear;

    // ── Extended personal (V4) ───────────────────────────────────────
    @Column(length = 10)
    private String gender;

    @Column(name = "blood_group", length = 5)
    private String bloodGroup;

    @Column(length = 50)
    private String religion;

    @Column(length = 50)
    private String category;

    @Column(name = "mother_tongue", length = 50)
    private String motherTongue;

    @Column(length = 255)
    private String email;

    @Column(name = "current_address", columnDefinition = "TEXT")
    private String currentAddress;

    @Column(name = "permanent_address", columnDefinition = "TEXT")
    private String permanentAddress;

    // ── Father info (V4) ────────────────────────────────────────────
    @Column(name = "father_name", length = 100)
    private String fatherName;

    @Column(name = "father_phone", length = 20)
    private String fatherPhone;

    @Column(name = "father_email", length = 100)
    private String fatherEmail;

    @Column(name = "father_occupation", length = 100)
    private String fatherOccupation;

    // ── Mother info (V4) ────────────────────────────────────────────
    @Column(name = "mother_name", length = 100)
    private String motherName;

    @Column(name = "mother_phone", length = 20)
    private String motherPhone;

    @Column(name = "mother_email", length = 100)
    private String motherEmail;

    @Column(name = "mother_occupation", length = 100)
    private String motherOccupation;

    // ── Guardian info (V4) ──────────────────────────────────────────
    @Column(name = "guardian_name", length = 100)
    private String guardianName;

    @Column(name = "guardian_relation", length = 50)
    private String guardianRelation;

    @Column(name = "guardian_phone", length = 20)
    private String guardianPhone;

    @Column(name = "guardian_email", length = 100)
    private String guardianEmail;

    @Column(name = "guardian_occupation", length = 100)
    private String guardianOccupation;

    @Column(name = "guardian_address", columnDefinition = "TEXT")
    private String guardianAddress;

    // ── Medical (V4) ────────────────────────────────────────────────
    @Column(name = "medical_condition", length = 20)
    private String medicalCondition;

    @Column(columnDefinition = "TEXT")
    private String allergies;

    @Column(columnDefinition = "TEXT")
    private String medications;

    // ── Previous school (V4) ────────────────────────────────────────
    @Column(name = "previous_school_name", length = 255)
    private String previousSchoolName;

    @Column(name = "previous_school_address", columnDefinition = "TEXT")
    private String previousSchoolAddress;

    // ── Bank info (V4) ──────────────────────────────────────────────
    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "bank_account_number", length = 50)
    private String bankAccountNumber;

    // ── Relationships ───────────────────────────────────────────────
    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<StudentGroup> studentGroups = new ArrayList<>();

    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Payment> payments = new ArrayList<>();

    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Attendance> attendances = new ArrayList<>();
}

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
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Student extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

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

    @Column(length = 10)
    private String gender;

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

    @Column(name = "admission_number", length = 50)
    private String admissionNumber;

    @Column(name = "admission_date")
    private LocalDate admissionDate;

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

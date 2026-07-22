package com.crm.entity;

import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.StudentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.math.BigDecimal;
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

    @Column(nullable = false, length = 32)
    private String phone;

    @Column(name = "parent_phone", length = 32)
    private String parentPhone;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(length = 50)
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

    @Column(name = "converted_from_lead_id")
    private Long convertedFromLeadId;

    /** To'lov hisoblash boshlanish sanasi (nextPaymentDate shundan). */
    @Column(name = "payment_start_date")
    private LocalDate paymentStartDate;

    /** Keyingi to'lov muddati (sana asosidagi qarzdorlik). */
    @Column(name = "next_payment_date")
    private LocalDate nextPaymentDate;

    /** Oylik to'lov miqdori (guruh/kursdan). */
    @Column(name = "monthly_fee", precision = 12, scale = 2)
    private BigDecimal monthlyFee;

    /** TRIAL, PENDING, PAID, OVERDUE, SUSPENDED, ARCHIVED — hech qachon null bo'lmasin. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

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

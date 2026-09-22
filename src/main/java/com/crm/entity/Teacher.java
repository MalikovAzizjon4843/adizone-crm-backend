package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "teachers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Teacher extends BaseEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_ON_LEAVE = "ON_LEAVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    /**
     * Tizimdagi hisob. Bitta User — ko'pi bilan bitta Teacher profili:
     * {@code unique = true} buni sxema darajasida kafolatlaydi
     * (indeks V50 migratsiyasida aniq yoziladi).
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 32)
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
    @Builder.Default
    private Boolean isActive = true;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "teacher_code", length = 50)
    private String teacherCode;

    @Column(length = 10)
    private String gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

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
    @Builder.Default
    private String status = STATUS_ACTIVE;

    @Column(name = "basic_salary", precision = 12, scale = 2)
    private BigDecimal basicSalary;

    @Column(name = "medical_leaves")
    @Builder.Default
    private Integer medicalLeaves = 0;

    @Column(name = "casual_leaves")
    @Builder.Default
    private Integer casualLeaves = 0;

    @Column(name = "maternity_leaves")
    @Builder.Default
    private Integer maternityLeaves = 0;

    @Column(name = "sick_leaves")
    @Builder.Default
    private Integer sickLeaves = 0;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    // ------------------------------------------------------------------
    // status <-> is_active izchilligi — YAGONA joy
    //
    //   status = INACTIVE          -> is_active = false
    //   status = ACTIVE / ON_LEAVE -> is_active = true
    //
    // Ikkala setter ham bir-birini yangilaydi, shuning uchun qaysi maydon
    // yozilishidan qat'i nazar ular zid bo'lib qolmaydi. Hibernate maydonlarga
    // to'g'ridan-to'g'ri murojaat qiladi (@Id maydonda), ya'ni bazadan o'qishda
    // bu setterlar CHAQIRILMAYDI.
    // ------------------------------------------------------------------

    public void setStatus(String status) {
        this.status = normalizeStatus(status);
        this.isActive = !STATUS_INACTIVE.equals(this.status);
    }

    /**
     * {@code false} -> INACTIVE. {@code true} -> INACTIVE dan ACTIVE ga qaytadi,
     * ON_LEAVE esa saqlanadi (ta'tildagi o'qituvchi faol hisoblanadi).
     */
    public void setIsActive(Boolean isActive) {
        boolean active = !Boolean.FALSE.equals(isActive);
        this.isActive = active;
        if (!active) {
            this.status = STATUS_INACTIVE;
        } else if (this.status == null || STATUS_INACTIVE.equals(this.status)) {
            this.status = STATUS_ACTIVE;
        }
    }

    /**
     * Setterlarni chetlab o'tadigan yo'llar uchun himoya (builder, all-args
     * konstruktor, eski zid qatorlar). Zid holatda NOFAOL ustun: bloklangan
     * o'qituvchi hech qachon o'z-o'zidan faollashib qolmasin.
     */
    @PrePersist
    @PreUpdate
    void enforceStatusConsistency() {
        if (status == null) {
            status = Boolean.FALSE.equals(isActive) ? STATUS_INACTIVE : STATUS_ACTIVE;
        }
        if (STATUS_INACTIVE.equals(status) || Boolean.FALSE.equals(isActive)) {
            status = STATUS_INACTIVE;
            isActive = false;
        } else {
            isActive = true;
        }
    }

    /** Bo'sh qiymat -> ACTIVE; registr va bo'shliqlar e'tiborsiz; noma'lum qiymat -> 400. */
    public static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATUS_ACTIVE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case STATUS_ACTIVE, STATUS_INACTIVE, STATUS_ON_LEAVE -> normalized;
            default -> throw new IllegalArgumentException(
                "Noma'lum o'qituvchi statusi: " + raw + " (ACTIVE, INACTIVE yoki ON_LEAVE)");
        };
    }

    @OneToMany(mappedBy = "teacher", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Group> groups = new ArrayList<>();
}

package com.crm.dto.response;

import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudentStatus;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StudentResponse {
    private Long id;
    private UUID uuid;

    // Core
    private String firstName;
    private String lastName;
    private String phone;
    private String parentPhone;
    private LocalDate birthDate;
    private MarketingSource marketingSource;
    private StudentStatus status;
    private String notes;
    private String address;
    private String photoUrl;

    // Academic
    private String admissionNumber;
    private LocalDate admissionDate;
    private String rollNumber;
    private String academicYear;

    // Personal
    private String gender;
    private String bloodGroup;
    private String religion;
    private String category;
    private String motherTongue;
    private String email;
    private String currentAddress;
    private String permanentAddress;

    // Father
    private String fatherName;
    private String fatherPhone;
    private String fatherEmail;
    private String fatherOccupation;

    // Mother
    private String motherName;
    private String motherPhone;
    private String motherEmail;
    private String motherOccupation;

    // Guardian
    private String guardianName;
    private String guardianRelation;
    private String guardianPhone;
    private String guardianEmail;
    private String guardianOccupation;
    private String guardianAddress;

    // Medical
    private String medicalCondition;
    private String allergies;
    private String medications;

    // Previous school
    private String previousSchoolName;
    private String previousSchoolAddress;

    // Bank
    private String bankName;
    private String bankAccountNumber;

    private LocalDateTime createdAt;
}

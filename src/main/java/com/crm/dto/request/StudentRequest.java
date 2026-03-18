package com.crm.dto.request;

import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudentStatus;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class StudentRequest {

    // Core (required)
    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Phone is required")
    private String phone;

    // Core (optional)
    private String parentPhone;
    private LocalDate birthDate;
    private MarketingSource marketingSource = MarketingSource.OTHER;
    private Long referralStudentId;
    private StudentStatus status = StudentStatus.ACTIVE;
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
}

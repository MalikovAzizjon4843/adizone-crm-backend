package com.crm.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TeacherResponse {
    private Long id;
    private UUID uuid;

    // Core
    private String firstName;
    private String lastName;
    private String phone;
    private String email;
    private String subjectSpecialization;
    private BigDecimal monthlySalary;
    private LocalDate hireDate;
    private Boolean isActive;
    private String notes;
    private int activeGroupsCount;

    // Personal (V5)
    private String teacherCode;
    private String gender;
    private String bloodGroup;
    private LocalDate dateOfBirth;
    private String maritalStatus;
    private String fatherName;
    private String motherName;
    private String religion;
    private String address;
    private String permanentAddress;
    private String panNumber;

    // Academic / Employment
    private String qualification;
    private String workExperience;
    private String previousSchool;
    private String previousSchoolAddress;
    private String previousSchoolPhone;
    private LocalDate joiningDate;
    private LocalDate leavingDate;
    private String status;

    // Payroll
    private String epfNumber;
    private BigDecimal basicSalary;
    private String contractType;
    private String workShift;
    private String workLocation;

    // Leaves
    private Integer medicalLeaves;
    private Integer casualLeaves;
    private Integer maternityLeaves;
    private Integer sickLeaves;

    // Bank
    private String bankAccountName;
    private String bankAccountNumber;
    private String bankName;
    private String ifscCode;
    private String branchName;

    private LocalDateTime createdAt;
}

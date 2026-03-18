package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class TeacherRequest {

    // Core (required)
    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Phone is required")
    private String phone;

    // Core (optional)
    private String email;
    private String subjectSpecialization;
    private BigDecimal monthlySalary;
    private LocalDate hireDate;
    private String notes;
    private Long userId;

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
}

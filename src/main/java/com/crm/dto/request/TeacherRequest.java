package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class TeacherRequest {

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Phone is required")
    private String phone;

    private String email;
    private String subjectSpecialization;
    private BigDecimal monthlySalary;
    private LocalDate hireDate;
    private String notes;
    private Long userId;

    private String gender;
    private LocalDate dateOfBirth;
    private String maritalStatus;
    private String fatherName;
    private String motherName;
    private String address;
    private String permanentAddress;
    private String passportInfo;

    private String qualification;
    private String workExperience;
    private LocalDate joiningDate;
    private String status;

    private BigDecimal basicSalary;

    private Integer medicalLeaves;
    private Integer casualLeaves;
    private Integer maternityLeaves;
    private Integer sickLeaves;
}

package com.crm.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherResponse {
    private Long id;
    private UUID uuid;
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
    private String teacherCode;
    private String gender;
    private LocalDate dateOfBirth;
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
    private String photoUrl;
    private List<GroupSummary> groups;
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupSummary {
        private Long id;
        private String groupName;
        private String courseName;
    }
}
